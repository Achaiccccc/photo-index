package app.photoindex.core

/**
 * 同一组材料始终得到同一个本地 ID。
 * 来源和图片用它做主键，重复扫描不会另起一行。
 */
fun stableLocalId(vararg parts: String): String {
    return sha256Hex(parts.joinToString("\u0000").encodeToByteArray())
}

/** 写入批文件时给压缩后的 JPEG 算内容哈希。结果是小写十六进制。 */
internal fun sha256Hex(bytes: ByteArray): String {
    val digest = sha256(bytes)
    val hex = StringBuilder(digest.size * 2)
    for (byte in digest) {
        val value = byte.toInt() and 0xFF
        hex.append(HEX[value ushr 4])
        hex.append(HEX[value and 0x0F])
    }
    return hex.toString()
}

private fun sha256(message: ByteArray): ByteArray {
    val bitLength = message.size.toLong() shl 3
    var paddedSize = message.size + 1
    while (paddedSize % 64 != 56) paddedSize++
    val padded = ByteArray(paddedSize + 8)
    message.copyInto(padded)
    padded[message.size] = 0x80.toByte()
    for (index in 0 until 8) {
        padded[padded.size - 1 - index] = ((bitLength ushr (8 * index)) and 0xFF).toByte()
    }

    val state = intArrayOf(
        0x6a09e667,
        0xbb67ae85.toInt(),
        0x3c6ef372,
        0xa54ff53a.toInt(),
        0x510e527f,
        0x9b05688c.toInt(),
        0x1f83d9ab,
        0x5be0cd19,
    )
    var offset = 0
    while (offset < padded.size) {
        compressBlock(padded, offset, state)
        offset += 64
    }

    val out = ByteArray(32)
    for (index in state.indices) {
        val value = state[index]
        out[index * 4] = (value ushr 24).toByte()
        out[index * 4 + 1] = (value ushr 16).toByte()
        out[index * 4 + 2] = (value ushr 8).toByte()
        out[index * 4 + 3] = value.toByte()
    }
    return out
}

private fun compressBlock(block: ByteArray, offset: Int, state: IntArray) {
    val words = IntArray(64)
    for (index in 0 until 16) {
        val start = offset + index * 4
        words[index] = ((block[start].toInt() and 0xFF) shl 24) or
            ((block[start + 1].toInt() and 0xFF) shl 16) or
            ((block[start + 2].toInt() and 0xFF) shl 8) or
            (block[start + 3].toInt() and 0xFF)
    }
    for (index in 16 until 64) {
        val first = words[index - 15]
        val second = words[index - 2]
        val small = first.rotateRight(7) xor first.rotateRight(18) xor (first ushr 3)
        val large = second.rotateRight(17) xor second.rotateRight(19) xor (second ushr 10)
        words[index] = words[index - 16] + small + words[index - 7] + large
    }

    var a = state[0]
    var b = state[1]
    var c = state[2]
    var d = state[3]
    var e = state[4]
    var f = state[5]
    var g = state[6]
    var h = state[7]
    for (index in 0 until 64) {
        val sum1 = e.rotateRight(6) xor e.rotateRight(11) xor e.rotateRight(25)
        val choose = (e and f) xor (e.inv() and g)
        val temp1 = h + sum1 + choose + SHA256_ROUND[index] + words[index]
        val sum0 = a.rotateRight(2) xor a.rotateRight(13) xor a.rotateRight(22)
        val majority = (a and b) xor (a and c) xor (b and c)
        val temp2 = sum0 + majority
        h = g
        g = f
        f = e
        e = d + temp1
        d = c
        c = b
        b = a
        a = temp1 + temp2
    }
    state[0] += a
    state[1] += b
    state[2] += c
    state[3] += d
    state[4] += e
    state[5] += f
    state[6] += g
    state[7] += h
}

private fun Int.rotateRight(bits: Int): Int = (this ushr bits) or (this shl (32 - bits))

private const val HEX = "0123456789abcdef"

private val SHA256_ROUND: IntArray = intArrayOf(
    0x428a2f98, 0x71374491, 0xb5c0fbcf.toInt(), 0xe9b5dba5.toInt(),
    0x3956c25b, 0x59f111f1, 0x923f82a4.toInt(), 0xab1c5ed5.toInt(),
    0xd807aa98.toInt(), 0x12835b01, 0x243185be, 0x550c7dc3,
    0x72be5d74, 0x80deb1fe.toInt(), 0x9bdc06a7.toInt(), 0xc19bf174.toInt(),
    0xe49b69c1.toInt(), 0xefbe4786.toInt(), 0x0fc19dc6, 0x240ca1cc,
    0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
    0x983e5152.toInt(), 0xa831c66d.toInt(), 0xb00327c8.toInt(), 0xbf597fc7.toInt(),
    0xc6e00bf3.toInt(), 0xd5a79147.toInt(), 0x06ca6351, 0x14292967,
    0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13,
    0x650a7354, 0x766a0abb, 0x81c2c92e.toInt(), 0x92722c85.toInt(),
    0xa2bfe8a1.toInt(), 0xa81a664b.toInt(), 0xc24b8b70.toInt(), 0xc76c51a3.toInt(),
    0xd192e819.toInt(), 0xd6990624.toInt(), 0xf40e3585.toInt(), 0x106aa070,
    0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5,
    0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
    0x748f82ee, 0x78a5636f, 0x84c87814.toInt(), 0x8cc70208.toInt(),
    0x90befffa.toInt(), 0xa4506ceb.toInt(), 0xbef9a3f7.toInt(), 0xc67178f2.toInt(),
)
