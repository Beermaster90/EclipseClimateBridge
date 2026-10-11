package fi.mitsukanny.phevbridge

import android.net.Network
import java.net.InetSocketAddress
import java.net.Socket

/**
 * The actual proven-working protocol for this car, physically confirmed multiple
 * times including a full 20-minute heat run. See
 * `docs/MitsubishiEclipseCrossReverseEngineerWitifProtocol.md` for the
 * full protocol writeup this implements.
 *
 * Model: every command opens its own fresh TCP connection, derives that session's
 * one-time RC4 table from the car's "hello", sends the mandatory plaintext
 * "start session" message, then the XOR-encoded command. No persistent session, no
 * ping loop, no VIN wait -- all of which turned out to be unnecessary complexity
 * from chasing the wrong protocol.
 */
object Rc4CarClient {
    enum class Mode(val byte: Int) { COOL(0x01), HEAT(0x02), WINDSCREEN(0x03) }
    enum class Duration(val code: Int) { MIN10(0x00), MIN20(0x01), MIN30(0x02) }

    class Result(val log: String, val success: Boolean)

    /** Register 0x1b climate-control payload: `02 [mode] [duration] 00`. */
    fun climateCommand(mode: Mode, duration: Duration): ByteArray =
        buildMessage(0xf6, 0x1b, byteArrayOf(0x02, mode.byte.toByte(), duration.code.toByte(), 0x00))

    /** Register 0x1b cancel payload: `01 00 00 00` -- stops heat or cool. */
    fun cancelCommand(): ByteArray =
        buildMessage(0xf6, 0x1b, byteArrayOf(0x01, 0x00, 0x00, 0x00))

    private fun buildMessage(cmd: Int, reg: Int, data: ByteArray): ByteArray {
        val len = data.size + 3 // type + reg + data, matching the car's own "len = total-2" framing
        val body = byteArrayOf(cmd.toByte(), len.toByte(), 0x00, reg.toByte()) + data
        var checksum = 0
        for (b in body) checksum = (checksum + (b.toInt() and 0xFF)) and 0xFF
        return body + checksum.toByte()
    }

    private fun computeSeed(helloData: ByteArray): Int {
        var seed = 0
        for (k in 0 until minOf(8, helloData.size)) {
            val bit = (helloData[k].toInt() shr 3) and 1
            seed = seed or (bit shl k)
        }
        return seed
    }

    private fun rc4Ksa(seed: Int): IntArray {
        val s = IntArray(256) { it }
        var j = 0
        for (i in 0 until 256) {
            j = (j + s[i] + seed) and 0xFF
            val tmp = s[i]; s[i] = s[j]; s[j] = tmp
        }
        return s
    }

    private fun xorMessage(message: ByteArray, key: Int): ByteArray =
        ByteArray(message.size) { (message[it].toInt() xor key).toByte() }

    /** Scans a plaintext buffer for the car's cmd=0x6e reg=0x01 "hello" and returns its data bytes. */
    private fun findHelloData(buffer: ByteArray, len: Int): ByteArray? {
        var i = 0
        while (i + 5 <= len) {
            if ((buffer[i].toInt() and 0xFF) == 0x6e) {
                val lengthField = buffer[i + 1].toInt() and 0xFF
                val totalLen = lengthField + 2
                if (totalLen >= 5 && i + totalLen <= len &&
                    (buffer[i + 2].toInt() and 0xFF) == 0x00 && (buffer[i + 3].toInt() and 0xFF) == 0x01
                ) {
                    var sum = 0
                    for (k in 0 until totalLen - 1) sum += (buffer[i + k].toInt() and 0xFF)
                    if ((sum and 0xFF) == (buffer[i + totalLen - 1].toInt() and 0xFF)) {
                        return buffer.copyOfRange(i + 4, i + totalLen - 1)
                    }
                }
            }
            i++
        }
        return null
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString(" ") { "%02x".format(it) }

    /**
     * Opens a fresh connection on [network] to `host:port`, derives the session key
     * from the car's hello, sends the mandatory plaintext start-session message, then
     * the XOR-encoded [plaintextMessage]. Runs synchronously -- call from a background
     * thread. `success` only reflects "got a same-length-ish response back"; the only
     * real confirmation is physically checking the car (per the proven reference).
     */
    fun sendAuthenticatedCommand(
        network: Network,
        host: String,
        port: Int,
        plaintextMessage: ByteArray,
    ): Result {
        val log = StringBuilder()
        try {
            val socket = Socket()
            network.bindSocket(socket)
            socket.use {
                it.connect(InetSocketAddress(host, port), 5000)
                it.soTimeout = 1000

                // Nudge the car the same way the real app's startup poll does.
                it.getOutputStream().write(hexBytes("f304000000f7"))
                it.getOutputStream().flush()

                val buffer = ByteArray(4096)
                var total = 0
                var helloData: ByteArray? = null
                val deadline = System.currentTimeMillis() + 4000
                while (helloData == null && System.currentTimeMillis() < deadline && total < buffer.size) {
                    try {
                        val n = it.getInputStream().read(buffer, total, buffer.size - total)
                        if (n <= 0) break
                        total += n
                        helloData = findHelloData(buffer, total)
                    } catch (timeout: java.net.SocketTimeoutException) {
                        // keep waiting until the deadline
                    }
                }
                log.append("collected $total bytes while waiting for hello\n")

                if (helloData == null) {
                    log.append("FAILED: never saw the car's cmd=0x6e reg=0x01 hello message.\n")
                    return Result(log.toString(), false)
                }

                val seed = computeSeed(helloData)
                val table = rc4Ksa(seed)
                log.append("hello=${hex(helloData)} seed=%02x key(table[0])=%02x\n".format(seed, table[0]))

                // Always plaintext, even after the session key is established -- without
                // this the car acks writes but never actuates them.
                val startSession = hexBytes("e604010100ec")
                log.append("-> (start session, plaintext) ${hex(startSession)}\n")
                it.getOutputStream().write(startSession)
                it.getOutputStream().flush()

                it.soTimeout = 2000
                val startAck = readAvailable(it, 512)
                log.append("<- (start session ack) ${if (startAck.isNotEmpty()) hex(startAck) + " (${startAck.size} bytes)" else "(no response)"}\n")

                val encoded = xorMessage(plaintextMessage, table[0])
                log.append("-> ${hex(encoded)} (plaintext ${hex(plaintextMessage)})\n")
                it.getOutputStream().write(encoded)
                it.getOutputStream().flush()

                it.soTimeout = 4000
                val ack = readAvailable(it, 1024)
                // Per PROTOCOL.md, the key index advances the moment we send a 0xf6 --
                // so the car's reply to this write is encoded with table[1], not
                // table[0]. The proven reference never decoded write-acks for this
                // reason and relied on physically checking the car instead; matching
                // that here rather than reporting a false "failed" for a command that
                // was likely sent correctly. "Success" just means the socket
                // round-tripped some data.
                if (ack.isNotEmpty()) {
                    log.append("<- ${hex(ack)} (${ack.size} bytes)\n")
                } else {
                    log.append("<- (no response)\n")
                }
                return Result(log.toString(), ack.isNotEmpty())
            }
        } catch (error: Exception) {
            log.append("EXCEPTION: $error\n")
            return Result(log.toString(), false)
        }
    }

    private fun readAvailable(socket: Socket, max: Int): ByteArray {
        val buffer = ByteArray(max)
        var total = 0
        try {
            while (true) {
                val n = socket.getInputStream().read(buffer, total, buffer.size - total)
                if (n <= 0) break
                total += n
                if (total >= buffer.size) break
            }
        } catch (timeout: java.net.SocketTimeoutException) {
            // fall through with whatever arrived
        }
        return buffer.copyOf(total)
    }

    private fun hexBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { ((Character.digit(hex[it * 2], 16) shl 4) + Character.digit(hex[it * 2 + 1], 16)).toByte() }
}
