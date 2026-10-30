package com.hand.bridge.atv

import java.math.BigInteger
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey

/** Pairing channel (port 6467): "polo" OuterMessage. */
object Polo {
    const val STATUS_OK = 200
    const val BAD_SECRET = 402

    const val PAIRING_REQUEST_ACK = 11
    const val OPTIONS = 20
    const val CONFIGURATION_ACK = 31
    const val SECRET_ACK = 41

    private fun outer(bodyField: Int, body: PbWriter) =
        PbWriter().int(1, 2).int(2, STATUS_OK).message(bodyField, body).toByteArray()

    private fun encoding() = PbWriter().int(1, 3 /* HEXADECIMAL */).int(2, 6)

    fun pairingRequest(clientName: String) =
        outer(10, PbWriter().string(1, "atvremote").string(2, clientName))

    fun options() = outer(20, PbWriter().message(1, encoding()).int(3, 1 /* ROLE_TYPE_INPUT */))

    fun configuration() = outer(30, PbWriter().message(1, encoding()).int(2, 1))

    fun secret(hash: ByteArray) = outer(40, PbWriter().bytes(1, hash))

    class Incoming(val status: Int, val kind: Int)

    /** kind = field number of the body (11 request ack, 20 options, 31 config ack, 41 secret ack). */
    fun parse(raw: ByteArray): Incoming {
        val m = PbMessage.parse(raw)
        val kind = m.fields.firstOrNull { it.number >= 10 }?.number ?: 0
        return Incoming(m.int(2, 0), kind)
    }

    private fun unsigned(v: BigInteger): ByteArray {
        val b = v.toByteArray()
        return if (b.size > 1 && b[0] == 0.toByte()) b.copyOfRange(1, b.size) else b
    }

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    fun isValidCode(code: String) = code.length == 6 && code.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

    /**
     * SHA-256 over both RSA public keys (modulus, exponent) and the last 4 hex digits of the code.
     * The first byte of the result must equal the first two digits, which lets us reject a mistyped
     * code locally.
     */
    fun secretHash(client: X509Certificate, server: X509Certificate, code: String): ByteArray {
        val c = client.publicKey as RSAPublicKey
        val s = server.publicKey as RSAPublicKey
        return MessageDigest.getInstance("SHA-256").run {
            update(unsigned(c.modulus)); update(unsigned(c.publicExponent))
            update(unsigned(s.modulus)); update(unsigned(s.publicExponent))
            update(hex(code.substring(2)))
            digest()
        }
    }

    fun codeMatches(hash: ByteArray, code: String) = (hash[0].toInt() and 0xFF) == code.substring(0, 2).toInt(16)
}

/** Remote channel (port 6466): RemoteMessage. */
object Remote {
    const val CONFIGURE = 1
    const val SET_ACTIVE = 2
    const val ERROR = 3
    const val PING_REQUEST = 8
    const val START = 40
    const val SET_VOLUME = 50

    /** Feature bits the TV reports and we request. */
    const val F_PING = 1
    const val F_KEY = 2
    const val F_POWER = 32
    const val F_VOLUME = 64
    const val F_APP_LINK = 512
    const val WANTED = F_PING or F_KEY or F_POWER or F_VOLUME or F_APP_LINK

    const val DIR_START_LONG = 1
    const val DIR_END_LONG = 2
    const val DIR_SHORT = 3

    fun configure(features: Int) = PbWriter().message(
        1, PbWriter().int(1, features).message(
            2, PbWriter().int(3, 1).string(4, "1").string(5, "atvremote").string(6, "1.0.0")
        )
    ).toByteArray()

    fun setActive(features: Int) = PbWriter().message(2, PbWriter().int(1, features)).toByteArray()
    fun pingResponse(val1: Int) = PbWriter().message(9, PbWriter().int(1, val1)).toByteArray()
    fun key(code: Int, direction: Int = DIR_SHORT) =
        PbWriter().message(10, PbWriter().int(1, code).int(2, direction)).toByteArray()

    fun launchApp(link: String) = PbWriter().message(90, PbWriter().string(1, link)).toByteArray()

    fun parse(raw: ByteArray): PbMessage = PbMessage.parse(raw)
}

/** Android KeyEvent codes understood by the TV. */
object Keys {
    const val HOME = 3
    const val BACK = 4
    const val UP = 19
    const val DOWN = 20
    const val LEFT = 21
    const val RIGHT = 22
    const val CENTER = 23
    const val VOLUME_UP = 24
    const val VOLUME_DOWN = 25
    const val POWER = 26
    const val ENTER = 66
    const val MENU = 82
    const val SEARCH = 84
    const val PLAY_PAUSE = 85
    const val NEXT = 87
    const val PREVIOUS = 88
    const val PAGE_UP = 92
    const val PAGE_DOWN = 93
    const val ZOOM_IN = 168
    const val ZOOM_OUT = 169
    const val VOLUME_MUTE = 164
    const val INFO = 165
    const val SLEEP = 223
    const val WAKEUP = 224
}
