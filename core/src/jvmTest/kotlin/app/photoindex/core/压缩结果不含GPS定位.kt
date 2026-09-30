package app.photoindex.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class 压缩结果不含GPS定位 {
    @Test
    fun 去掉APP1后GPS指针消失且JFIF和图像数据还在() {
        val jpeg = jpegWithGps(littleEndian = true)
        assertTrue(jpegHasGpsExif(jpeg))

        val stripped = jpegWithoutLocation(jpeg)

        assertFalse(jpegHasGpsExif(stripped))
        assertEquals(0xFF, stripped[0].toInt() and 0xFF)
        assertEquals(0xD8, stripped[1].toInt() and 0xFF)
        assertTrue(stripped.containsSequence(jfifPayload))
        assertTrue(stripped.containsSequence(byteArrayOf(0x11, 0x22)))
        assertFalse(stripped.containsSequence(exifHeader))
    }

    @Test
    fun 大端Exif里的GPS指针也会被去掉() {
        val jpeg = jpegWithGps(littleEndian = false)
        assertTrue(jpegHasGpsExif(jpeg))
        assertFalse(jpegHasGpsExif(jpegWithoutLocation(jpeg)))
    }

    @Test
    fun 长边决定目标尺寸且不放大小图() {
        assertEquals(1280 to 960, fittedSize(4000, 3000, 1280))
        assertEquals(480 to 640, fittedSize(960, 1280, 640))
        assertEquals(960 to 1280, fittedSize(960, 1280, 1280))
        assertEquals(100 to 80, fittedSize(100, 80, null))
        assertEquals(2, decodeSampleSize(4000, 3000, 1280))
        assertEquals(1, decodeSampleSize(100, 80, 1280))
        assertEquals(1, decodeSampleSize(4000, 3000, null))
    }

    @Test
    fun HEIC文件头能认出来() {
        val heic = ByteArray(12)
        heic[3] = 0x18
        heic[4] = 'f'.code.toByte()
        heic[5] = 't'.code.toByte()
        heic[6] = 'y'.code.toByte()
        heic[7] = 'p'.code.toByte()
        heic[8] = 'h'.code.toByte()
        heic[9] = 'e'.code.toByte()
        heic[10] = 'i'.code.toByte()
        heic[11] = 'c'.code.toByte()
        assertTrue(looksLikeHeic(heic))
        assertFalse(looksLikeHeic(byteArrayOf(0xFF.toByte(), 0xD8.toByte())))
    }
}

private val exifHeader = bytes(0x45, 0x78, 0x69, 0x66, 0x00, 0x00)

private val jfifPayload = bytes(0x4A, 0x46, 0x49, 0x46, 0x00)

private fun jpegWithGps(littleEndian: Boolean): ByteArray {
    val tiff = if (littleEndian) {
        bytes(
            'I'.code, 'I'.code,
            0x2A, 0x00,
            0x08, 0x00, 0x00, 0x00,
            0x01, 0x00,
            0x25, 0x88,
            0x04, 0x00,
            0x01, 0x00, 0x00, 0x00,
            0x1A, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,
        )
    } else {
        bytes(
            'M'.code, 'M'.code,
            0x00, 0x2A,
            0x00, 0x00, 0x00, 0x08,
            0x00, 0x01,
            0x88, 0x25,
            0x00, 0x04,
            0x00, 0x00, 0x00, 0x01,
            0x00, 0x00, 0x00, 0x1A,
            0x00, 0x00, 0x00, 0x00,
        )
    }
    val payload = exifHeader + tiff
    val length = payload.size + 2
    val app1 = bytes(0xFF, 0xE1, length shr 8, length and 0xFF) + payload
    val jfif = bytes(
        0xFF, 0xE0, 0x00, 0x10,
        0x4A, 0x46, 0x49, 0x46, 0x00,
        0x01, 0x01, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00,
    )
    val sos = bytes(0xFF, 0xDA, 0x00, 0x02, 0x11, 0x22, 0xFF, 0xD9)
    return bytes(0xFF, 0xD8) + jfif + app1 + sos
}

private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

private fun ByteArray.containsSequence(needle: ByteArray): Boolean {
    if (needle.isEmpty() || needle.size > size) return false
    for (start in 0..size - needle.size) {
        var matched = true
        for (index in needle.indices) {
            if (this[start + index] != needle[index]) {
                matched = false
                break
            }
        }
        if (matched) return true
    }
    return false
}
