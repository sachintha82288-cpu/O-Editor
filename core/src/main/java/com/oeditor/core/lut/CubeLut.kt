package com.oeditor.core.lut

import android.graphics.Bitmap
import java.io.BufferedReader
import java.io.InputStream

/**
 * Parser for Adobe/Resolve `.cube` 3D LUTs.
 *
 * The cube is repacked into a tile atlas (size slices across) so it can be uploaded as a
 * normal 2D texture and sampled by the LUT shader on any GLES 3.0 device.
 */
object CubeLut {

    data class Lut3D(val size: Int, val data: FloatArray) {

        fun toAtlas(): Bitmap {
            val tiles = size
            val tileSize = size
            val width = tiles * tileSize
            val height = tileSize
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val pixels = IntArray(width * height)
            var index = 0
            for (b in 0 until size) {
                for (g in 0 until size) {
                    for (r in 0 until size) {
                        val x = b * tileSize + r
                        val y = g
                        if (x < width && y < height && index + 2 < data.size) {
                            val rr = (data[index].coerceIn(0f, 1f) * 255).toInt()
                            val gg = (data[index + 1].coerceIn(0f, 1f) * 255).toInt()
                            val bb = (data[index + 2].coerceIn(0f, 1f) * 255).toInt()
                            pixels[y * width + x] = (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
                        }
                        index += 3
                    }
                }
            }
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
            return bitmap
        }
    }

    fun parse(input: InputStream): Lut3D? {
        var size = 0
        val values = ArrayList<Float>(33 * 33 * 33 * 3)
        BufferedReader(input.reader()).useLines { lines ->
            for (raw in lines) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                if (line.startsWith("TITLE")) continue
                if (line.startsWith("LUT_3D_SIZE")) {
                    size = line.split(" ").last().toIntOrNull() ?: 0
                    continue
                }
                if (line.startsWith("LUT_1D_SIZE") || line.startsWith("DOMAIN_MIN") ||
                    line.startsWith("DOMAIN_MAX")
                ) continue
                val parts = line.split(Regex("\\s+"))
                if (parts.size == 3) {
                    val r = parts[0].toFloatOrNull()
                    val g = parts[1].toFloatOrNull()
                    val b = parts[2].toFloatOrNull()
                    if (r != null && g != null && b != null) {
                        values.add(r); values.add(g); values.add(b)
                    }
                }
            }
        }
        if (size <= 0 || values.size < size * size * size * 3) return null
        return Lut3D(size, values.toFloatArray())
    }
}
