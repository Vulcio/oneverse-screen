package org.oneverse.screen

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object AudioSamples {
    fun writePlanar(samples: ShortArray, count: Int, channels: Int, destination: ByteBuffer): Int {
        require(channels > 0 && count in 0..samples.size)
        val frames = count / channels
        require(frames.toLong() * channels * 4 <= destination.capacity())
        destination.clear()
        // JNI reads float pointers in native byte order; ByteBuffer defaults to big endian.
        val floats = destination.order(ByteOrder.nativeOrder()).asFloatBuffer()
        for (channel in 0 until channels) {
            for (frame in 0 until frames) floats.put(samples[frame * channels + channel] / 32768f)
        }
        return frames
    }
}
