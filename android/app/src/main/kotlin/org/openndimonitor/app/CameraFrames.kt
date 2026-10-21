package org.openndimonitor.app

import android.graphics.Bitmap
import android.graphics.Matrix
import android.hardware.camera2.CameraCharacteristics
import android.media.Image
import android.view.Surface

internal object CameraFrames {
    fun rotation(characteristics: CameraCharacteristics, displayRotation: Int): Int {
        val displayDegrees = when (displayRotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        return MonitorPolicy.cameraRotation(
            characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0,
            displayDegrees,
            characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        )
    }

    fun preview(image: Image, rotation: Int): Bitmap {
        val width = image.width
        val height = image.height
        val targetWidth = minOf(width, 640)
        val targetHeight = (height.toLong() * targetWidth / width).toInt().coerceAtLeast(1)
        val pixels = IntArray(targetWidth * targetHeight)
        val (yPlane, uPlane, vPlane) = image.planes
        // Plane positions can be nonzero: absolute indexing must start at the slice.
        val yBuffer = yPlane.buffer.slice()
        val uBuffer = uPlane.buffer.slice()
        val vBuffer = vPlane.buffer.slice()
        for (py in 0 until targetHeight) {
            val sy = py * height / targetHeight
            for (px in 0 until targetWidth) {
                val sx = px * width / targetWidth
                val y = (yBuffer.get(sy * yPlane.rowStride + sx * yPlane.pixelStride).toInt() and 0xFF) - 16
                val u = (uBuffer.get(sy / 2 * uPlane.rowStride + sx / 2 * uPlane.pixelStride).toInt() and 0xFF) - 128
                val v = (vBuffer.get(sy / 2 * vPlane.rowStride + sx / 2 * vPlane.pixelStride).toInt() and 0xFF) - 128
                val luminance = maxOf(0, y) * 1192
                val red = (luminance + 1634 * v).coerceIn(0, 262143) shr 10
                val green = (luminance - 833 * v - 400 * u).coerceIn(0, 262143) shr 10
                val blue = (luminance + 2066 * u).coerceIn(0, 262143) shr 10
                pixels[py * targetWidth + px] = (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
            }
        }
        val raw = Bitmap.createBitmap(pixels, targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        if (rotation == 0) return raw
        val rotated = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(rotation.toFloat()) }, true)
        if (rotated !== raw) raw.recycle() // Never displayed; safe to release immediately.
        return rotated
    }
}
