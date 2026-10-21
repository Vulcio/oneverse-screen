package org.openndimonitor.app

import java.net.URI
import java.util.Locale

/** Rules shared by settings and the web bridge, independent of Android UI state. */
internal object MonitorPolicy {
    fun normalizeUrl(input: String): String? {
        val raw = input.trim()
        if (raw.isEmpty() || raw.any { it.isWhitespace() || it.isISOControl() }) return null
        val candidate = if (raw.contains("://")) raw else "https://$raw"
        val uri = runCatching { URI(candidate) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
        if (scheme !in setOf("http", "https") || uri.host.isNullOrBlank() ||
            uri.port !in -1..65535 || uri.rawUserInfo != null) return null
        return scheme + candidate.substring(candidate.indexOf(':'))
    }

    fun origin(input: String): String? {
        val url = normalizeUrl(input) ?: return null
        val uri = URI(url)
        val port = uri.port.takeIf { it >= 0 } ?: if (uri.scheme == "https") 443 else 80
        return "${uri.scheme}://${uri.host.lowercase(Locale.ROOT)}:$port"
    }

    fun validViewport(x: Double, y: Double, width: Double, height: Double): Boolean =
        listOf(x, y, width, height).all { it.isFinite() } &&
            x >= 0 && y >= 0 && width > 0 && height > 0 &&
            x + width <= 100 && y + height <= 100

    fun cameraRotation(sensor: Int, displayDegrees: Int, front: Boolean): Int =
        ((sensor + if (front) displayDegrees else -displayDegrees) % 360 + 360) % 360
}
