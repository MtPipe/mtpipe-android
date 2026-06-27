package org.mtpipe.proxy

import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private val secureRandom = SecureRandom()

private fun bytes(vararg ints: Int) = ByteArray(ints.size) { ints[it].toByte() }

fun u8(i: Int): ByteArray = bytes(i and 0xFF)

fun u16(i: Int): ByteArray = bytes((i shr 8) and 0xFF, i and 0xFF)

fun u24(i: Int): ByteArray = bytes((i shr 16) and 0xFF, (i shr 8) and 0xFF, i and 0xFF)

fun arr8(contents: ByteArray): ByteArray = u8(contents.size) + contents
fun arr16(contents: ByteArray): ByteArray = u16(contents.size) + contents
fun arr24(contents: ByteArray): ByteArray = u24(contents.size) + contents

fun grease(): ByteArray {
    val b = secureRandom.nextInt(16)
    val g = (b shl 12) or (b shl 4) or 0x0A0A
    return u16(g)
}

fun rng(n: Int): ByteArray = ByteArray(n).also { secureRandom.nextBytes(it) }

fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key, "HmacSHA256"))
    return mac.doFinal(data)
}

fun clientHelloBuilder(
    sessionId: ByteArray,
    secretKey: ByteArray,
    sni: ByteArray?
): ByteArray {
    val sniExt = if (sni != null && sni.isNotEmpty()) {
        u16(0x0000) + arr16(arr16(bytes(0x00) + arr16(sni)))
    } else {
        ByteArray(0)
    }

    val echLen = intArrayOf(144, 176, 208, 240)[secureRandom.nextInt(4)]

    val cipherSuites = grease() +
        bytes(0x13, 0x01, 0x13, 0x02, 0x13, 0x03) +
        bytes(0xC0, 0x2B, 0xC0, 0x2F, 0xC0, 0x2C, 0xC0, 0x30) +
        bytes(0xCC, 0xA8, 0xCC, 0xA8) +
        bytes(0xC0, 0x13, 0xC0, 0x14) +
        bytes(0x00, 0x9C, 0x00, 0x9D) +
        bytes(0x00, 0x2F, 0x00, 0x35)

    val extensions = mutableListOf<ByteArray>()

    extensions.add(u16(0x0000) + arr16(arr16(bytes(0x00) + arr16(sni ?: ByteArray(0)))))
    extensions.add(u16(0x0005) + arr16(bytes(0x01, 0x00, 0x00, 0x00, 0x00)))
    extensions.add(u16(0x000A) + arr16(arr16(grease() + bytes(0x11, 0xEC, 0x00, 0x1D, 0x00, 0x17, 0x00, 0x18))))
    extensions.add(u16(0x000B) + arr16(bytes(0x00, 0x00)))
    extensions.add(u16(0x000D) + arr16(arr16(bytes(0x04, 0x03, 0x08, 0x04, 0x04, 0x01, 0x05, 0x03, 0x08, 0x05, 0x05, 0x01, 0x08, 0x06, 0x06, 0x01))))
    extensions.add(u16(0x0010) + arr16(arr16(bytes(0x02) + "h2".toByteArray() + bytes(0x08) + "http/1.1".toByteArray())))
    extensions.add(u16(0x0012) + arr16(ByteArray(0)))
    extensions.add(u16(0x0017) + arr16(ByteArray(0)))
    extensions.add(u16(0x001B) + arr16(arr8(bytes(0x00, 0x02))))
    extensions.add(u16(0x0023) + arr16(ByteArray(0)))
    extensions.add(u16(0x002D) + arr16(bytes(0x01, 0x01)))
    extensions.add(u16(0x002B) + arr16(arr8(grease() + bytes(0x03, 0x04, 0x03, 0x03))))

    val keyShareInner = grease() + arr16(ByteArray(0)) +
        u16(0x11EC) + arr16(rng(1216)) +
        u16(0x001D) + arr16(rng(32))
    extensions.add(u16(0x0033) + arr16(arr16(keyShareInner)))

    extensions.add(u16(0x4469) + arr16(bytes(0x00, 0x03, 0x02) + "h2".toByteArray()))
    extensions.add(u16(0xFE0D) + arr16(
        bytes(0x00, 0x00, 0x01, 0x00, 0x01) + rng(1) +
        bytes(0x00, 0x20) + rng(32) + arr16(rng(echLen))
    ))
    extensions.add(u16(0xFF01) + arr16(bytes(0x00)))

    extensions.shuffle()

    val extensionsBytes = grease() + bytes(0x00, 0x00) + extensions.reduce { acc, ext -> acc + ext } + grease() + bytes(0x00, 0x00)

    val clientHelloBody = bytes(0x03, 0x03) +
        ByteArray(32) +
        arr8(sessionId) +
        arr16(cipherSuites) +
        arr8(bytes(0x00)) +
        arr16(extensionsBytes)

    val ch = bytes(0x16, 0x03, 0x01) +
        arr16(bytes(0x01) + arr24(clientHelloBody))

    val digest = hmacSha256(secretKey, ch).toMutableList()
    val timestamp = System.currentTimeMillis() / 1000
    for (i in 0..3) {
        digest[28 + i] = (digest[28 + i].toInt() xor ((timestamp shr (i * 8)) and 0xFF).toInt()).toByte()
    }

    val result = ch.copyOf()
    System.arraycopy(digest.toByteArray(), 0, result, 11, 32)
    return result
}
