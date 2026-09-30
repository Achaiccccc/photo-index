package app.photoindex.core

import kotlin.math.max

/**
 * 一张原图的字节。调用方每次只提供一张，读完即可丢掉。
 */
fun interface ImageBytes {
    fun read(): ByteArray
}

/** 平台压缩的结果。失败时带原因，不把异常抛到调用方外面。 */
sealed interface JpegCompressResult {
    class Ready(
        val bytes: ByteArray,
        val widthPx: Int,
        val heightPx: Int,
    ) : JpegCompressResult

    class Failed(val reason: String) : JpegCompressResult
}

/**
 * 按长边和 JPEG 质量压成一张 JPEG。
 * longEdge 为 null 表示不缩小，但仍重新编码，以便去掉定位信息。
 * 实现不得把上一张的位图留在字段里。桌面端以后另写一份。
 * 打包流程用的 [ImageCompressor] 仍按图片 ID 取图，测试继续用假实现。
 */
interface JpegCompressor {
    fun compress(image: ImageBytes, longEdge: Int?, jpegQuality: Int): JpegCompressResult
}

/**
 * 压缩后的目标宽高。长边正好等于 [longEdge]；原图更小则不放大。
 * null 表示不缩小。
 */
fun fittedSize(widthPx: Int, heightPx: Int, longEdge: Int?): Pair<Int, Int> {
    require(widthPx > 0 && heightPx > 0) { "原图宽高必须是正像素" }
    if (longEdge == null) return widthPx to heightPx
    require(longEdge > 0) { "长边必须是正像素" }
    val longest = max(widthPx, heightPx)
    if (longest <= longEdge) return widthPx to heightPx
    val shortSide = (minOf(widthPx, heightPx).toLong() * longEdge / longest).toInt().coerceAtLeast(1)
    return if (widthPx >= heightPx) longEdge to shortSide else shortSide to longEdge
}

/** 解码时的采样步长，避免按原图像素整张进内存。不缩小时为 1。 */
fun decodeSampleSize(widthPx: Int, heightPx: Int, longEdge: Int?): Int {
    require(widthPx > 0 && heightPx > 0) { "原图宽高必须是正像素" }
    if (longEdge == null) return 1
    require(longEdge > 0) { "长边必须是正像素" }
    var sample = 1
    while (max(widthPx, heightPx) / (sample * 2) >= longEdge) {
        sample *= 2
    }
    return sample
}

private val exifHeader = byteArrayOf(0x45, 0x78, 0x69, 0x66, 0x00, 0x00)

/**
 * 去掉 JPEG 里 APP1 段（Exif 和 XMP 都在这里），定位信息随之消失。
 * 不是 JPEG 或结构读不下去时原样返回，调用方再检查是否仍带 GPS。
 */
fun jpegWithoutLocation(jpeg: ByteArray): ByteArray {
    if (jpeg.size < 4 || markerByte(jpeg, 0) != 0xFF || markerByte(jpeg, 1) != 0xD8) return jpeg
    val out = ByteBuffer()
    out.write(0xFF)
    out.write(0xD8)
    var index = 2
    while (index < jpeg.size) {
        if (markerByte(jpeg, index) != 0xFF) {
            out.write(jpeg, index, jpeg.size - index)
            return out.toByteArray()
        }
        while (index < jpeg.size && markerByte(jpeg, index) == 0xFF) index++
        if (index >= jpeg.size) break
        val marker = markerByte(jpeg, index)
        index++
        if (marker == 0xD9) {
            out.write(0xFF)
            out.write(marker)
            break
        }
        if (marker == 0xDA) {
            out.write(0xFF)
            out.write(marker)
            if (index < jpeg.size) out.write(jpeg, index, jpeg.size - index)
            break
        }
        if (marker == 0x01 || marker in 0xD0..0xD7) {
            out.write(0xFF)
            out.write(marker)
            continue
        }
        if (index + 2 > jpeg.size) return jpeg
        val length = (markerByte(jpeg, index) shl 8) or markerByte(jpeg, index + 1)
        if (length < 2 || index + length > jpeg.size) return jpeg
        if (marker != 0xE1) {
            out.write(0xFF)
            out.write(marker)
            out.write(jpeg, index, length)
        }
        index += length
    }
    return out.toByteArray()
}

/** APP1 里的 Exif 是否带有 GPS IFD 指针（标签 0x8825）。 */
fun jpegHasGpsExif(jpeg: ByteArray): Boolean {
    if (jpeg.size < 4 || markerByte(jpeg, 0) != 0xFF || markerByte(jpeg, 1) != 0xD8) return false
    var index = 2
    while (index < jpeg.size) {
        if (markerByte(jpeg, index) != 0xFF) return false
        while (index < jpeg.size && markerByte(jpeg, index) == 0xFF) index++
        if (index >= jpeg.size) return false
        val marker = markerByte(jpeg, index)
        index++
        if (marker == 0xDA || marker == 0xD9) return false
        if (marker == 0x01 || marker in 0xD0..0xD7) continue
        if (index + 2 > jpeg.size) return false
        val length = (markerByte(jpeg, index) shl 8) or markerByte(jpeg, index + 1)
        if (length < 2 || index + length > jpeg.size) return false
        if (marker == 0xE1 && length >= 8) {
            val payloadStart = index + 2
            if (startsWith(jpeg, payloadStart, exifHeader)) {
                val tiffStart = payloadStart + exifHeader.size
                val tiffEnd = index + length
                if (tiffHasGpsPointer(jpeg, tiffStart, tiffEnd)) return true
            }
        }
        index += length
    }
    return false
}

/** 文件头是 ftyp，品牌属于 HEIC/HEIF 时返回 true。解不开这类文件时原因里要写明 HEIC。 */
fun looksLikeHeic(bytes: ByteArray): Boolean {
    if (bytes.size < 12) return false
    if (markerByte(bytes, 4) != 'f'.code ||
        markerByte(bytes, 5) != 't'.code ||
        markerByte(bytes, 6) != 'y'.code ||
        markerByte(bytes, 7) != 'p'.code
    ) {
        return false
    }
    val brand = buildString {
        for (offset in 8 until 12) append(markerByte(bytes, offset).toChar())
    }
    return brand == "heic" || brand == "heix" || brand == "heif" || brand == "mif1" || brand == "msf1"
}

private fun tiffHasGpsPointer(data: ByteArray, start: Int, end: Int): Boolean {
    if (end - start < 8) return false
    val little = markerByte(data, start) == 'I'.code && markerByte(data, start + 1) == 'I'.code
    val big = markerByte(data, start) == 'M'.code && markerByte(data, start + 1) == 'M'.code
    if (!little && !big) return false
    if (readU16(data, start + 2, little) != 42) return false
    val ifdOffset = readU32(data, start + 4, little)
    return ifdHasTag(data, start, end, ifdOffset, 0x8825, little)
}

private fun ifdHasTag(
    data: ByteArray,
    start: Int,
    end: Int,
    ifdOffset: Int,
    tag: Int,
    little: Boolean,
): Boolean {
    val ifd = start + ifdOffset
    if (ifdOffset < 0 || ifd + 2 > end) return false
    val count = readU16(data, ifd, little)
    var entry = ifd + 2
    repeat(count) {
        if (entry + 12 > end) return false
        if (readU16(data, entry, little) == tag) return true
        entry += 12
    }
    return false
}

private fun readU16(data: ByteArray, offset: Int, little: Boolean): Int {
    val first = markerByte(data, offset)
    val second = markerByte(data, offset + 1)
    return if (little) first or (second shl 8) else (first shl 8) or second
}

private fun readU32(data: ByteArray, offset: Int, little: Boolean): Int {
    val bytes = IntArray(4) { markerByte(data, offset + it) }
    return if (little) {
        bytes[0] or (bytes[1] shl 8) or (bytes[2] shl 16) or (bytes[3] shl 24)
    } else {
        (bytes[0] shl 24) or (bytes[1] shl 16) or (bytes[2] shl 8) or bytes[3]
    }
}

private fun startsWith(data: ByteArray, offset: Int, prefix: ByteArray): Boolean {
    if (offset < 0 || offset + prefix.size > data.size) return false
    for (index in prefix.indices) {
        if (data[offset + index] != prefix[index]) return false
    }
    return true
}

private fun markerByte(data: ByteArray, offset: Int): Int = data[offset].toInt() and 0xFF

private class ByteBuffer {
    private var data = ByteArray(64)
    private var size = 0

    fun write(byte: Int) {
        ensure(1)
        data[size++] = byte.toByte()
    }

    fun write(bytes: ByteArray, offset: Int, length: Int) {
        ensure(length)
        bytes.copyInto(data, size, offset, offset + length)
        size += length
    }

    fun toByteArray(): ByteArray = data.copyOf(size)

    private fun ensure(extra: Int) {
        val needed = size + extra
        if (needed <= data.size) return
        var capacity = data.size
        while (capacity < needed) capacity *= 2
        data = data.copyOf(capacity)
    }
}
