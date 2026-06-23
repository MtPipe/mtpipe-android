package org.mtpipe.proxy

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.Socket

fun Socket.recvExact(n: Int): ByteArray = inputStream.recvExact(n)

fun InputStream.recvExact(n: Int): ByteArray {
    val buf = ByteArrayOutputStream()
    while (buf.size() < n) {
        val rem = n - buf.size()
        val chunk = ByteArray(rem)
        val read = read(chunk, 0, rem)
        if (read == -1) throw IOException("EOF")
        buf.write(chunk, 0, read)
    }
    return buf.toByteArray()
}
