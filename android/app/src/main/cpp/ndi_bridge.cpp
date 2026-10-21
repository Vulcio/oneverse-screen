#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <cstdint>
#include <limits>
#include <mutex>
#include <vector>
#include <Processing.NDI.Lib.h>

#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "OpenNdiMonitor", __VA_ARGS__)

namespace {
std::mutex ndi_mutex;
bool initialized = false;
NDIlib_find_instance_t finder = nullptr;
NDIlib_recv_instance_t receiver = nullptr;
NDIlib_send_instance_t sender = nullptr;
std::vector<uint8_t> sender_video_buffer;

void destroyReceiverLocked() {
    if (receiver) {
        NDIlib_recv_destroy(receiver);
        receiver = nullptr;
    }
}

void destroySenderLocked() {
    if (sender) {
        NDIlib_send_destroy(sender);
        sender = nullptr;
    }
}
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_openndimonitor_app_NdiNative_initialize(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> guard(ndi_mutex);
    if (initialized) return JNI_TRUE;
    if (!NDIlib_initialize()) {
        LOGE("NDIlib_initialize failed");
        return JNI_FALSE;
    }
    NDIlib_find_create_t settings(true, nullptr, nullptr);
    finder = NDIlib_find_create_v2(&settings);
    if (!finder) {
        NDIlib_destroy();
        LOGE("NDI finder creation failed");
        return JNI_FALSE;
    }
    initialized = true;
    return JNI_TRUE;
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_org_openndimonitor_app_NdiNative_findSources(JNIEnv* env, jobject, jint timeout_ms) {
    std::lock_guard<std::mutex> guard(ndi_mutex);
    jclass stringClass = env->FindClass("java/lang/String");
    jclass stringArrayClass = env->FindClass("[Ljava/lang/String;");
    if (!initialized || !finder) return env->NewObjectArray(0, stringArrayClass, nullptr);

    NDIlib_find_wait_for_sources(finder, static_cast<uint32_t>(std::max(0, timeout_ms)));
    uint32_t count = 0;
    const NDIlib_source_t* sources = NDIlib_find_get_current_sources(finder, &count);
    jobjectArray result = env->NewObjectArray(static_cast<jsize>(count), stringArrayClass, nullptr);
    for (uint32_t i = 0; sources && i < count; ++i) {
        jobjectArray row = env->NewObjectArray(2, stringClass, nullptr);
        jstring name = env->NewStringUTF(sources[i].p_ndi_name ? sources[i].p_ndi_name : "");
        jstring address = env->NewStringUTF(sources[i].p_url_address ? sources[i].p_url_address : "");
        env->SetObjectArrayElement(row, 0, name);
        env->SetObjectArrayElement(row, 1, address);
        env->SetObjectArrayElement(result, static_cast<jsize>(i), row);
        env->DeleteLocalRef(name);
        env->DeleteLocalRef(address);
        env->DeleteLocalRef(row);
    }
    return result;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_openndimonitor_app_NdiNative_connect(JNIEnv* env, jobject, jstring name, jstring address) {
    std::lock_guard<std::mutex> guard(ndi_mutex);
    if (!initialized) return JNI_FALSE;
    const char* name_chars = env->GetStringUTFChars(name, nullptr);
    const char* address_chars = env->GetStringUTFChars(address, nullptr);
    NDIlib_source_t source{};
    source.p_ndi_name = name_chars;
    source.p_url_address = address_chars;

    destroyReceiverLocked();
    NDIlib_recv_create_v3_t settings{};
    settings.source_to_connect_to = source;
    settings.color_format = NDIlib_recv_color_format_BGRX_BGRA;
    settings.bandwidth = NDIlib_recv_bandwidth_highest;
    settings.allow_video_fields = false;
    settings.p_ndi_recv_name = "Oneverse Screen";
    receiver = NDIlib_recv_create_v3(&settings);

    env->ReleaseStringUTFChars(name, name_chars);
    env->ReleaseStringUTFChars(address, address_chars);
    if (!receiver) {
        LOGE("NDI receiver creation failed");
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

extern "C" JNIEXPORT jint JNICALL
Java_org_openndimonitor_app_NdiNative_connectionCount(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> guard(ndi_mutex);
    return receiver ? NDIlib_recv_get_no_connections(receiver) : -1;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_openndimonitor_app_NdiNative_startSender(JNIEnv* env, jobject, jstring name) {
    std::lock_guard<std::mutex> guard(ndi_mutex);
    if (!initialized || !name) return JNI_FALSE;
    const char* name_chars = env->GetStringUTFChars(name, nullptr);
    if (!name_chars) return JNI_FALSE;
    destroySenderLocked();
    NDIlib_send_create_t settings{};
    settings.p_ndi_name = name_chars;
    settings.p_groups = nullptr;
    settings.clock_video = false;
    settings.clock_audio = false;
    sender = NDIlib_send_create(&settings);
    env->ReleaseStringUTFChars(name, name_chars);
    return sender ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_openndimonitor_app_NdiNative_sendVideo(JNIEnv* env, jobject, jobject pixels,
        jint width, jint height, jint row_stride, jint pixel_stride) {
    std::lock_guard<std::mutex> guard(ndi_mutex);
    if (!sender || !pixels || width <= 0 || height <= 0 || pixel_stride < 4 || row_stride < width * pixel_stride) return JNI_FALSE;
    const auto* source = static_cast<const uint8_t*>(env->GetDirectBufferAddress(pixels));
    const jlong capacity = env->GetDirectBufferCapacity(pixels);
    const int64_t needed = static_cast<int64_t>(height - 1) * row_stride +
        static_cast<int64_t>(width - 1) * pixel_stride + 4;
    if (!source || capacity < needed || static_cast<int64_t>(width) * height * 4 > std::numeric_limits<int>::max()) return JNI_FALSE;

    sender_video_buffer.resize(static_cast<size_t>(width) * height * 4);
    for (int y = 0; y < height; ++y) {
        const uint8_t* src = source + static_cast<int64_t>(y) * row_stride;
        uint8_t* dst = sender_video_buffer.data() + static_cast<int64_t>(y) * width * 4;
        for (int x = 0; x < width; ++x) {
            const uint8_t* pixel = src + static_cast<int64_t>(x) * pixel_stride;
            dst[x * 4] = pixel[0];
            dst[x * 4 + 1] = pixel[1];
            dst[x * 4 + 2] = pixel[2];
            dst[x * 4 + 3] = pixel[3];
        }
    }
    NDIlib_video_frame_v2_t frame{};
    frame.xres = width;
    frame.yres = height;
    frame.FourCC = NDIlib_FourCC_video_type_RGBA;
    frame.frame_rate_N = 30;
    frame.frame_rate_D = 1;
    frame.picture_aspect_ratio = static_cast<float>(width) / height;
    frame.frame_format_type = NDIlib_frame_format_type_progressive;
    frame.timecode = NDIlib_send_timecode_synthesize;
    frame.p_data = sender_video_buffer.data();
    frame.line_stride_in_bytes = width * 4;
    NDIlib_send_send_video_v2(sender, &frame);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_openndimonitor_app_NdiNative_sendYuvVideo(JNIEnv* env, jobject, jobject y_plane,
        jint y_row_stride, jint y_pixel_stride, jobject u_plane, jint u_row_stride,
        jint u_pixel_stride, jobject v_plane, jint v_row_stride, jint v_pixel_stride,
        jint width, jint height) {
    std::lock_guard<std::mutex> guard(ndi_mutex);
    if (!sender || !y_plane || !u_plane || !v_plane || width <= 0 || height <= 0) return JNI_FALSE;
    const auto* y_data = static_cast<const uint8_t*>(env->GetDirectBufferAddress(y_plane));
    const auto* u_data = static_cast<const uint8_t*>(env->GetDirectBufferAddress(u_plane));
    const auto* v_data = static_cast<const uint8_t*>(env->GetDirectBufferAddress(v_plane));
    const jlong y_capacity = env->GetDirectBufferCapacity(y_plane);
    const jlong u_capacity = env->GetDirectBufferCapacity(u_plane);
    const jlong v_capacity = env->GetDirectBufferCapacity(v_plane);
    if (!y_data || !u_data || !v_data || y_row_stride <= 0 || y_pixel_stride <= 0 ||
        u_row_stride <= 0 || u_pixel_stride <= 0 || v_row_stride <= 0 || v_pixel_stride <= 0 ||
        y_capacity < static_cast<jlong>(height - 1) * y_row_stride + static_cast<jlong>(width - 1) * y_pixel_stride + 1 ||
        u_capacity < static_cast<jlong>((height + 1) / 2 - 1) * u_row_stride + static_cast<jlong>((width + 1) / 2 - 1) * u_pixel_stride + 1 ||
        v_capacity < static_cast<jlong>((height + 1) / 2 - 1) * v_row_stride + static_cast<jlong>((width + 1) / 2 - 1) * v_pixel_stride + 1 ||
        static_cast<int64_t>(width) * height * 4 > std::numeric_limits<int>::max()) return JNI_FALSE;

    sender_video_buffer.resize(static_cast<size_t>(width) * height * 4);
    for (int y = 0; y < height; ++y) {
        uint8_t* dst = sender_video_buffer.data() + static_cast<int64_t>(y) * width * 4;
        const int chroma_y = y / 2;
        for (int x = 0; x < width; ++x) {
            const int luminance = std::max(0, static_cast<int>(y_data[static_cast<int64_t>(y) * y_row_stride + static_cast<int64_t>(x) * y_pixel_stride]) - 16);
            const int chroma_x = x / 2;
            const int u = static_cast<int>(u_data[static_cast<int64_t>(chroma_y) * u_row_stride + static_cast<int64_t>(chroma_x) * u_pixel_stride]) - 128;
            const int v = static_cast<int>(v_data[static_cast<int64_t>(chroma_y) * v_row_stride + static_cast<int64_t>(chroma_x) * v_pixel_stride]) - 128;
            const int c = 298 * luminance;
            dst[x * 4] = static_cast<uint8_t>(std::clamp((c + 409 * v + 128) >> 8, 0, 255));
            dst[x * 4 + 1] = static_cast<uint8_t>(std::clamp((c - 100 * u - 208 * v + 128) >> 8, 0, 255));
            dst[x * 4 + 2] = static_cast<uint8_t>(std::clamp((c + 516 * u + 128) >> 8, 0, 255));
            dst[x * 4 + 3] = 0xFF;
        }
    }
    NDIlib_video_frame_v2_t frame{};
    frame.xres = width;
    frame.yres = height;
    frame.FourCC = NDIlib_FourCC_video_type_RGBA;
    frame.frame_rate_N = 30;
    frame.frame_rate_D = 1;
    frame.picture_aspect_ratio = static_cast<float>(width) / height;
    frame.frame_format_type = NDIlib_frame_format_type_progressive;
    frame.timecode = NDIlib_send_timecode_synthesize;
    frame.p_data = sender_video_buffer.data();
    frame.line_stride_in_bytes = width * 4;
    NDIlib_send_send_video_v2(sender, &frame);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_openndimonitor_app_NdiNative_sendAudio(JNIEnv* env, jobject, jobject samples,
        jint sample_rate, jint channels, jint sample_count) {
    std::lock_guard<std::mutex> guard(ndi_mutex);
    if (!sender || !samples || sample_rate <= 0 || channels <= 0 || sample_count <= 0) return JNI_FALSE;
    auto* data = static_cast<float*>(env->GetDirectBufferAddress(samples));
    const jlong capacity = env->GetDirectBufferCapacity(samples);
    if (!data || capacity < static_cast<jlong>(channels) * sample_count * sizeof(float)) return JNI_FALSE;
    NDIlib_audio_frame_v2_t frame{};
    frame.sample_rate = sample_rate;
    frame.no_channels = channels;
    frame.no_samples = sample_count;
    frame.timecode = NDIlib_send_timecode_synthesize;
    frame.p_data = data;
    frame.channel_stride_in_bytes = sample_count * static_cast<int>(sizeof(float));
    NDIlib_send_send_audio_v2(sender, &frame);
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_org_openndimonitor_app_NdiNative_stopSender(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> guard(ndi_mutex);
    destroySenderLocked();
}

extern "C" JNIEXPORT jintArray JNICALL
Java_org_openndimonitor_app_NdiNative_capture(JNIEnv* env, jobject, jobject target, jint timeout_ms) {
    std::lock_guard<std::mutex> guard(ndi_mutex);
    if (!receiver || !target) return nullptr;
    auto* destination = static_cast<uint8_t*>(env->GetDirectBufferAddress(target));
    const jlong capacity = env->GetDirectBufferCapacity(target);
    if (!destination || capacity <= 0) return nullptr;

    NDIlib_video_frame_v2_t frame{};
    const auto type = NDIlib_recv_capture_v2(receiver, &frame, nullptr, nullptr,
        static_cast<uint32_t>(std::max(0, timeout_ms)));
    if (type != NDIlib_frame_type_video || !frame.p_data || frame.xres <= 0 || frame.yres <= 0) {
        if (type == NDIlib_frame_type_video) NDIlib_recv_free_video_v2(receiver, &frame);
        return nullptr;
    }

    constexpr int64_t max_frame_bytes = 64LL * 1024 * 1024;
    constexpr int max_frame_dimension = 8192;
    if (frame.xres > max_frame_dimension || frame.yres > max_frame_dimension) {
        NDIlib_recv_free_video_v2(receiver, &frame);
        LOGE("Rejected oversized NDI frame dimensions: %d x %d", frame.xres, frame.yres);
        return nullptr;
    }
    const int64_t row_bytes = static_cast<int64_t>(frame.xres) * 4;
    const int64_t required_bytes = row_bytes * frame.yres;
    if (required_bytes <= 0 || required_bytes > max_frame_bytes || required_bytes > std::numeric_limits<jint>::max() ||
        frame.line_stride_in_bytes < 0 ||
        (frame.line_stride_in_bytes > 0 && frame.line_stride_in_bytes < row_bytes)) {
        NDIlib_recv_free_video_v2(receiver, &frame);
        LOGE("Rejected invalid or oversized NDI frame: %d x %d, stride %d",
             frame.xres, frame.yres, frame.line_stride_in_bytes);
        return nullptr;
    }
    if (frame.FourCC == NDIlib_FourCC_video_type_BGRX || frame.FourCC == NDIlib_FourCC_video_type_BGRA) {
        if (capacity >= required_bytes) {
            const int source_stride = frame.line_stride_in_bytes > 0 ? frame.line_stride_in_bytes : static_cast<int>(row_bytes);
            for (int y = 0; y < frame.yres; ++y) {
                const auto* source_row = frame.p_data + static_cast<int64_t>(y) * source_stride;
                auto* target_row = destination + static_cast<int64_t>(y) * row_bytes;
                std::copy(source_row, source_row + row_bytes, target_row);
                if (frame.FourCC == NDIlib_FourCC_video_type_BGRX) {
                    for (int64_t x = 3; x < row_bytes; x += 4) target_row[x] = 0xFF;
                }
                for (int64_t x = 0; x < row_bytes; x += 4) std::swap(target_row[x], target_row[x + 2]);
            }
        }
    } else {
        LOGE("Unexpected NDI video pixel format: %u", static_cast<unsigned>(frame.FourCC));
    }
    const int width = frame.xres;
    const int height = frame.yres;
    const int fourcc = static_cast<int>(frame.FourCC);
    const int frame_rate_n = frame.frame_rate_N;
    const int frame_rate_d = frame.frame_rate_D;
    NDIlib_recv_free_video_v2(receiver, &frame);

    jint values[6] = {
        width,
        height,
        static_cast<jint>(required_bytes),
        fourcc,
        frame_rate_n,
        frame_rate_d
    };
    jintArray result = env->NewIntArray(6);
    env->SetIntArrayRegion(result, 0, 6, values);
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_org_openndimonitor_app_NdiNative_disconnect(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> guard(ndi_mutex);
    destroyReceiverLocked();
}

extern "C" JNIEXPORT void JNICALL
Java_org_openndimonitor_app_NdiNative_shutdown(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> guard(ndi_mutex);
    destroySenderLocked();
    destroyReceiverLocked();
    if (finder) {
        NDIlib_find_destroy(finder);
        finder = nullptr;
    }
    if (initialized) {
        NDIlib_destroy();
        initialized = false;
    }
}

