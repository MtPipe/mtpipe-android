package org.mtpipe.proxy

import android.util.Log
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

class MtpipeProxy(
    private val serverAddr: String,
    private val serverPort: Int,
    secret: String,
    private val listenPort: Int = 19796
) {
    private val secretHex = secret
    private val secretBytes: ByteArray
    private val sni: ByteArray?
    private val hasSni: Boolean

    private var serverSocket: ServerSocket? = null
    @Volatile
    private var running = false
    private var _clientCount = 0
    val clientCount: Int get() = _clientCount

    @Volatile
    var isRunning = false
        private set

    var onStatusChanged: ((String) -> Unit)? = null
    var onClientCountChanged: ((Int) -> Unit)? = null
    var onFatalError: ((String) -> Unit)? = null

    init {
        val raw = hexToBytes(secret)
        if (raw.isNotEmpty() && raw[0].toInt() and 0xFF == 0xEE) {
            sni = if (raw.size > 17) raw.sliceArray(17 until raw.size) else null
            hasSni = sni != null && sni.isNotEmpty()
            secretBytes = raw.sliceArray(1 until 17)
        } else {
            sni = null
            hasSni = false
            secretBytes = raw
        }
    }

    fun start() {
        if (running) return
        Thread({
            try {
                serverSocket = ServerSocket()
                serverSocket!!.reuseAddress = true
                serverSocket!!.bind(java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), listenPort), 50)
                running = true
                isRunning = true
                Log.i(TAG, "Mtpipe is up on port $listenPort")
                onStatusChanged?.invoke("LISTENING:$listenPort")
                while (running) {
                    try {
                        val client = serverSocket!!.accept()
                        _clientCount++
                        onClientCountChanged?.invoke(clientCount)
                        Log.i(TAG, "New client: ${client.remoteSocketAddress}")
                        Thread({ handleClient(client) }, "Client-${clientCount}").start()
                    } catch (e: IOException) {
                        if (running) Log.e(TAG, "Accept error: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Server error: ${e.message}")
                onStatusChanged?.invoke("Error: ${e.message}")
            } finally {
                isRunning = false
                running = false
                try { serverSocket?.close() } catch (_: Exception) {}
            }
        }, "Mtpipe-Server").start()
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        isRunning = false
        _clientCount = 0
        onStatusChanged?.invoke("Disconnected")
        onClientCountChanged?.invoke(0)
        Log.i(TAG, "Mtpipe stopped")
    }

    private fun handleClient(clientSocket: Socket) {
        var proxySocket: Socket? = null
        try {
            val clientIn = clientSocket.inputStream
            val clientOut = clientSocket.outputStream

            Log.d(TAG, "Waiting for ClientHello from Telegram...")
            val orig = clientIn.recvExact(5)
            if (orig[0].toInt() and 0xFF != 0x16 || orig[1].toInt() and 0xFF != 0x03 || orig[2].toInt() and 0xFF != 0x01) {
                Log.w(TAG, "Not a TLS ClientHello")
                return
            }
            val len = ((orig[3].toInt() and 0xFF) shl 8) or (orig[4].toInt() and 0xFF)
            val origFull = orig + clientIn.recvExact(len)
            Log.d(TAG, "Received ClientHello from Telegram (${origFull.size} bytes)")

            val origz = origFull.copyOf(11) + ByteArray(32) + origFull.copyOfRange(11 + 32, origFull.size)
            val digest1 = origFull.copyOfRange(11, 43)
            val digest2 = hmacSha256(secretBytes, origz)
            val computed = ByteArray(32) { (digest1[it].toInt() xor digest2[it].toInt()).toByte() }
            val first28Zero = (0..27).all { computed[it].toInt() == 0 }
            if (!first28Zero) {
                Log.w(TAG, "Bad client HMAC")
                return
            }
            Log.d(TAG, "Client HMAC verified")

            val sessionIdLen = origFull[43].toInt() and 0xFF
            val sessionId = origFull.copyOfRange(44, 44 + sessionIdLen)

            var connected = false
            while (running && !connected) {
                try {
                    Log.d(TAG, "Connecting to $serverAddr:$serverPort...")
                    proxySocket = Socket(serverAddr, serverPort)
                    connected = true
                    Log.d(TAG, "Connected to proxy server")
                } catch (e: Exception) {
                    Log.w(TAG, "Connection failed: ${e.message}, retrying in 500ms...")
                    try { proxySocket?.close() } catch (_: Exception) {}
                    proxySocket = null
                    Thread.sleep(500)
                }
            }
            if (!connected) return

            val proxyIn = proxySocket!!.inputStream
            val proxyOut = proxySocket.outputStream

            val ch = clientHelloBuilder(sessionId, secretBytes, if (hasSni) sni else null)
            val digest3 = ch.copyOfRange(11, 43)
            Log.d(TAG, "Sending patched ClientHello to server (${ch.size} bytes)")
            proxyOut.write(ch)
            proxyOut.flush()
            Log.d(TAG, "ClientHello sent")

            Log.d(TAG, "Waiting for ServerHello from server...")
            val sh5 = proxyIn.recvExact(5)
            if (sh5[0].toInt() and 0xFF != 0x16 || sh5[1].toInt() and 0xFF != 0x03 || sh5[2].toInt() and 0xFF != 0x03) {
                Log.w(TAG, "Unknown server response: first bytes = ${sh5.take(3).joinToString(" ") { "%02X".format(it) }}")
                return
            }
            val shLen = ((sh5[3].toInt() and 0xFF) shl 8) or (sh5[4].toInt() and 0xFF)
            val shBody = proxyIn.recvExact(shLen)
            Log.d(TAG, "Received ServerHello header (${shLen} bytes)")
            val ccs = proxyIn.recvExact(6)
            Log.d(TAG, "Received ChangeCipherSpec")
            val ad5 = proxyIn.recvExact(5)
            val adLen = ((ad5[3].toInt() and 0xFF) shl 8) or (ad5[4].toInt() and 0xFF)
            val adBody = proxyIn.recvExact(adLen)
            Log.d(TAG, "Received ApplicationData (${adLen} bytes)")

            val sh = sh5 + shBody + ccs + ad5 + adBody

            val digest4 = sh.copyOfRange(11, 43)
            val shz = sh.copyOf(11) + ByteArray(32) + sh.copyOfRange(11 + 32, sh.size)
            val digest5 = hmacSha256(secretBytes, digest3 + shz)
            if (!digest4.contentEquals(digest5)) {
                Log.w(TAG, "Bad server HMAC")
                return
            }
            Log.d(TAG, "Server HMAC verified")

            val digest6 = hmacSha256(secretBytes, digest1 + shz)
            val shp = shz.copyOf(11) + digest6 + shz.copyOfRange(11 + 32, shz.size)
            Log.d(TAG, "Sending patched ServerHello to Telegram (${shp.size} bytes)")
            clientOut.write(shp)
            clientOut.flush()
            Log.d(TAG, "ServerHello sent to Telegram — handshake complete, piping data")

            Thread({ pipe(clientIn, proxyOut, "client->proxy") }, "Pipe-c2p").start()
            pipe(proxyIn, clientOut, "proxy->client")
        } catch (e: Exception) {
            Log.e(TAG, "Client handler error: ${e.message}", e)
        } finally {
            try { proxySocket?.close() } catch (_: Exception) {}
            try { clientSocket.close() } catch (_: Exception) {}
            _clientCount = maxOf(0, _clientCount - 1)
            onClientCountChanged?.invoke(clientCount)
        }
    }

    private fun pipe(src: java.io.InputStream, dst: java.io.OutputStream, tag: String) {
        try {
            val buf = ByteArray(4096)
            while (running) {
                val n = src.read(buf)
                if (n == -1) break
                dst.write(buf, 0, n)
                dst.flush()
            }
        } catch (_: Exception) {}
    }

    companion object {
        private const val TAG = "MtpipeProxy"

        fun hexToBytes(hex: String): ByteArray {
            val clean = hex.replace(" ", "")
            return ByteArray(clean.length / 2) { i ->
                clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        }
    }
}
