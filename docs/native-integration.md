# Android NDI SDK integration

The Android app uses the official NDI Android SDK through `NdiNative` and `ndi_bridge.cpp` for source discovery, receiving, and sending.

## Local SDK setup

Keep the installed SDK outside the repository. Its default path is `C:/Program Files/NDI/NDI 6 SDK (Android)`. To override it, add this to `android/local.properties`:

```properties
ndiAndroidSdk=C:/path/to/NDI 6 SDK (Android)
```

Gradle exposes `Lib/<ABI>/libndi.so` to packaging and CMake compiles against `Include/Processing.NDI.Lib.h`. The project builds arm64, armeabi-v7a, and x86_64.

## Runtime

NDI initialization and the finder are shared for the process lifetime. Stopping the Activity disconnects its receiver without destroying a sender owned by the contribution service. The finder is polled for source changes. Choosing a source creates one NDI receiver; the TV preview requests four-channel RGB and draws each frame to fit the screen while preserving its aspect ratio. Contribute mode uses Android MediaProjection for display capture or Camera2 for camera capture, then sends frames through the SDK's NDI sender API. It caps outgoing video at 30 fps and 1280 pixels on the longest dimension. Optional screen playback audio and microphone audio are sent when selected and permitted. A multicast lock stays held while the Activity is active to support local-network discovery on Wi-Fi.

Before distributing an APK, check the current NDI SDK agreement and third-party license files under `Lib/<ABI>/`. The APK contains the NDI SDK binary even though this source repository does not.


Camera preview and sending use the sensor orientation and display rotation to produce the same upright aspect ratio. The native conversion rotates the output pixels and updates the frame dimensions accordingly. Audio is converted from interleaved PCM16 to planar float samples in native byte order. A single background capture queue orders sender startup, image callbacks, and teardown across service instances; the audio recording thread is stopped before its recorder is released.
