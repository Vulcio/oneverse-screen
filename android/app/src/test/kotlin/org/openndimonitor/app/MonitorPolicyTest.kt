package org.openndimonitor.app

import org.junit.Assert.*
import org.junit.Test

class MonitorPolicyTest {
    @Test fun acceptsLocalHttpAndDefaultHttps() {
        assertEquals("https://screen.oneverse.top", MonitorPolicy.normalizeUrl("screen.oneverse.top"))
        assertEquals("http://192.168.1.20:8080/screen?a=1#top", MonitorPolicy.normalizeUrl(" http://192.168.1.20:8080/screen?a=1#top "))
        assertEquals("http://localhost:8000/", MonitorPolicy.normalizeUrl("HTTP://localhost:8000/"))
        assertEquals("http://[::1]:8080/", MonitorPolicy.normalizeUrl("http://[::1]:8080/"))
    }

    @Test fun rejectsMalformedOrUnsafeAddresses() {
        listOf("", "https://", "https://bad host/", "https://example.com:99999/", "javascript:alert(1)",
            "file:///sdcard/data", "https://user:password@example.com/", "https://example.com/\npage")
            .forEach { assertNull(it, MonitorPolicy.normalizeUrl(it)) }
    }

    @Test fun originIncludesSchemeAndEffectivePort() {
        assertEquals(MonitorPolicy.origin("https://EXAMPLE.com/a"), MonitorPolicy.origin("https://example.com:443/b"))
        assertNotEquals(MonitorPolicy.origin("https://example.com"), MonitorPolicy.origin("http://example.com"))
        assertNotEquals(MonitorPolicy.origin("https://example.com"), MonitorPolicy.origin("https://example.com:444"))
    }

    @Test fun rejectsInvalidViewportAndAllowsScreenEdges() {
        assertTrue(MonitorPolicy.validViewport(0.0, 0.0, 100.0, 100.0))
        assertTrue(MonitorPolicy.validViewport(90.0, 90.0, 10.0, 10.0))
        assertFalse(MonitorPolicy.validViewport(Double.NaN, 0.0, 10.0, 10.0))
        assertFalse(MonitorPolicy.validViewport(0.0, 0.0, Double.POSITIVE_INFINITY, 10.0))
        assertFalse(MonitorPolicy.validViewport(-1.0, 0.0, 10.0, 10.0))
        assertFalse(MonitorPolicy.validViewport(0.0, 0.0, 0.0, 10.0))
        assertFalse(MonitorPolicy.validViewport(99.0, 0.0, 2.0, 10.0))
    }

    @Test fun cameraOrientationHandlesBothLensDirectionsAndAllDisplayRotations() {
        assertEquals(listOf(90, 0, 270, 180), listOf(0, 90, 180, 270).map { MonitorPolicy.cameraRotation(90, it, false) })
        assertEquals(listOf(270, 0, 90, 180), listOf(0, 90, 180, 270).map { MonitorPolicy.cameraRotation(270, it, true) })
    }
}
