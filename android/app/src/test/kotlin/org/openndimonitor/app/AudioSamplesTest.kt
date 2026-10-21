package org.openndimonitor.app

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class AudioSamplesTest {
    @Test fun sendsNativeEndianPlanarStereoWithCorrectScaling() {
        val buffer = ByteBuffer.allocateDirect(16)
        assertEquals(2, AudioSamples.writePlanar(shortArrayOf(Short.MIN_VALUE, 16384, 0, -16384), 4, 2, buffer))
        val samples = buffer.duplicate().order(ByteOrder.nativeOrder()).asFloatBuffer()
        assertEquals(-1f, samples.get(), 0f)
        assertEquals(0f, samples.get(), 0f)
        assertEquals(0.5f, samples.get(), 0f)
        assertEquals(-0.5f, samples.get(), 0f)
    }

    @Test fun reusesBufferWithoutSendingOldSamples() {
        val buffer = ByteBuffer.allocateDirect(16)
        AudioSamples.writePlanar(shortArrayOf(1, 2, 3, 4), 4, 1, buffer)
        assertEquals(1, AudioSamples.writePlanar(shortArrayOf(16384), 1, 1, buffer))
        assertEquals(0.5f, buffer.getFloat(0), 0f)
    }
}
