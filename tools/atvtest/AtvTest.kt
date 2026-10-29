import com.hand.bridge.atv.*
import com.hand.bridge.Action
import com.hand.bridge.Actions
import com.hand.bridge.GestureTests
import com.hand.bridge.GestureTests.Status
import java.io.File
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

private var failures = 0
private fun check(name: String, ok: Boolean, detail: String = "") {
    println((if (ok) "PASS " else "FAIL ") + name + (if (detail.isNotEmpty()) "  [$detail]" else ""))
    if (!ok) failures++
}

private inline fun <reified T : Throwable> expectThrows(name: String, block: () -> Unit) {
    try { block(); check(name, false, "no exception") }
    catch (e: Throwable) { check(name, e is T, "${e.javaClass.simpleName}: ${e.message}") }
}

private fun waitFor(ms: Long = 3000, cond: () -> Boolean): Boolean {
    val end = System.currentTimeMillis() + ms
    while (System.currentTimeMillis() < end) { if (cond()) return true; Thread.sleep(25) }
    return cond()
}

fun main(args: Array<String>) {
    val work = File(args[0]); val pairPort = args[2].toInt(); val remotePort = args[3].toInt()
    when (args[1]) {
        "gen" -> {
            val id = Identity.generate()
            File(work, "id.cert").writeText(id.certBase64()); File(work, "id.key").writeText(id.keyBase64())
            val pem = "-----BEGIN CERTIFICATE-----\n" + Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(id.cert.encoded) + "\n-----END CERTIFICATE-----\n"
            File(work, "client.pem").writeText(pem)
            id.cert.verify(id.cert.publicKey)  // self-signature is valid
            check("generated certificate parses and self-verifies", true, id.cert.subjectX500Principal.name + " until " + id.cert.notAfter)
            check("certificate is v3 with basicConstraints CA", id.cert.version == 3 && id.cert.basicConstraints >= 0)
            check("identity survives save/load", Identity.fromBase64(id.certBase64(), id.keyBase64()).cert == id.cert)
            System.exit(if (failures == 0) 0 else 1)
        }
    }

    val id = Identity.fromBase64(File(work, "id.cert").readText(), File(work, "id.key").readText())
    val pin = File(work, "server.sha256").readText().trim()
    val log = File(work, "mock.log")
    fun logHas(s: String) = log.exists() && log.readText().contains(s)
    val listener = object : AtvRemote.Listener {}

    // 1. A phone the TV hasn't paired with must be told to pair.
    expectThrows<NeedsPairing>("unpaired phone gets NeedsPairing") {
        AtvRemote(id, "127.0.0.1", pin, listener, remotePort).connect(4000)
    }

    // 2. Pairing: wrong codes are caught locally, the right code is accepted.
    AtvPairing(id, "127.0.0.1", "Hand Bridge test", pairPort).use { p ->
        p.start()
        val code = File(work, "code.txt").readText().trim()
        check("TV code looks like 6 hex chars", Polo.isValidCode(code), code)
        check("pairing session exposes the TV certificate", p.serverCert != null && PinTrust.fingerprint(p.serverCert!!) == pin)
        expectThrows<WrongCode>("too short code rejected") { p.finish("12345") }
        expectThrows<WrongCode>("non-hex code rejected") { p.finish("12345Z") }
        var rejected = 0
        for (prefix in listOf("00", "01", "02", "03", "04", "05")) {
            if (prefix == code.take(2)) continue
            try { p.finish(prefix + code.drop(2)) } catch (e: WrongCode) { rejected++ }
        }
        check("mistyped codes are rejected before anything is sent", rejected >= 4, "rejected=$rejected")
        p.finish(code)
        check("correct code accepted by the TV", waitFor { logHas("\"ev\": \"secret\", \"ok\": true") })
        val info = TvInfo.parse(p.serverCert!!)
        check("TV name and MAC read from its certificate", info.name == "Mock Projector" && info.mac == "AA:BB:CC:DD:EE:FF", "${info.name} / ${info.mac}")
    }

    // 3. A paired phone connects, completes the handshake and can send keys.
    val power = AtomicInteger(-1); val vol = AtomicInteger(-1); val closedReason = arrayOfNulls<String>(1)
    val remote = AtvRemote(id, "127.0.0.1", pin, object : AtvRemote.Listener {
        override fun onPower(on: Boolean) { power.set(if (on) 1 else 0) }
        override fun onVolume(level: Int, max: Int, muted: Boolean) { vol.set(level * 1000 + max) }
        override fun onClosed(reason: String) { closedReason[0] = reason }
    }, remotePort)
    remote.connect(5000)
    check("remote ready after handshake", remote.ready)
    check("TV reported power on", waitFor { power.get() == 1 })
    check("TV volume report decoded (33 of 100)", waitFor { vol.get() == 33100 }, "got ${vol.get()}")
    check("client features = ping|key|power|volume|app_link (611)", waitFor { logHas("\"code1\": 611") })
    check("client device info sent", logHas("\"package\": \"atvremote\"") && logHas("\"version\": \"1.0.0\""))
    check("set_active echoes our features (611)", waitFor { logHas("\"ev\": \"client_set_active\", \"active\": 611") })
    check("ping answered with the same value", waitFor { logHas("\"ev\": \"ping_response\", \"val1\": 7") })
    // Regression: on Android, keys are sent from the main thread, where network writes throw
    // NetworkOnMainThreadException. The write must happen on the sender thread, never the caller's.
    val caller = Thread.currentThread().name
    check("DPAD_UP key", remote.key(Keys.UP) && waitFor { logHas("\"code\": 19, \"direction\": 3") })
    check("keys are written on a background thread, not the caller's", remote.lastWriteThread == "atv-send" && remote.lastWriteThread != caller, remote.lastWriteThread ?: "null")
    val fromOther = java.util.concurrent.atomic.AtomicReference<Boolean?>(null)
    Thread({ fromOther.set(remote.key(Keys.INFO)) }, "pretend-main").start()
    check("key() from another thread returns immediately and arrives", waitFor { fromOther.get() == true } && waitFor { logHas("\"code\": 165, \"direction\": 3") })
    check("key order is preserved", (1..30).all { remote.key(Keys.PAGE_UP) } && remote.key(Keys.PAGE_DOWN) &&
        waitFor(4000) { log.readText().let { t -> t.lastIndexOf("\"code\": 93") > t.lastIndexOf("\"code\": 92") && t.split("\"code\": 92").size - 1 == 30 } })
    check("VOLUME_UP long press start/end", remote.key(Keys.VOLUME_UP, Remote.DIR_START_LONG) && remote.key(Keys.VOLUME_UP, Remote.DIR_END_LONG) &&
        waitFor { logHas("\"code\": 24, \"direction\": 1") && logHas("\"code\": 24, \"direction\": 2") })
    check("HOME, BACK, POWER, WAKEUP keys", listOf(Keys.HOME, Keys.BACK, Keys.POWER, Keys.WAKEUP).all { remote.key(it) } &&
        waitFor { listOf(3, 4, 26, 224).all { logHas("\"code\": $it, \"direction\": 3") } })
    check("app link launch", remote.launch("https://www.youtube.com") && waitFor { logHas("\"link\": \"https://www.youtube.com\"") })
    val burst = (1..200).all { remote.key(Keys.DOWN) }
    check("200 rapid keys written", burst)
    check("all 200 keys reach the TV", waitFor(5000) { log.readText().split("\"code\": 20, \"direction\": 3").size - 1 == 200 })
    remote.close()
    check("TV sees the connection close", waitFor { logHas("remote_closed") })
    check("closing it yourself isn't reported as a lost link", closedReason[0] == null, closedReason[0] ?: "ok")

    // Finding the TV without mDNS: scan the subnet for the pairing port, then read the certificate.
    check("subnet /24 has 253 other hosts", Scan.hosts("192.168.1.77", 24).size == 253 && "192.168.1.77" !in Scan.hosts("192.168.1.77", 24))
    check("subnet is capped at /22 (1021 hosts)", Scan.hosts("10.1.2.3", 8).size == 1021)
    check("subnet range is correct", Scan.hosts("192.168.5.9", 24).let { it.first() == "192.168.5.1" && it.last() == "192.168.5.254" })
    val hits = java.util.concurrent.CopyOnWriteArrayList<String>()
    Scan.openPort(listOf("127.0.0.1", "127.0.0.2", "127.0.0.3"), pairPort, 500) { hits.add(it) }
    check("port scan finds only the host with the port open", hits.toList() == listOf("127.0.0.1"), hits.toString())
    // Regression: the finder is stopped (thread interrupted) while a scan is running. This used to kill the app.
    val escaped = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
    val scanner = Thread { Scan.openPort(Scan.hosts("192.0.2.9", 24), 9, 800) {} }
    scanner.setUncaughtExceptionHandler { _, e -> escaped.set(e) }
    scanner.start(); Thread.sleep(150); scanner.interrupt(); scanner.join(5000)
    check("interrupting a running scan ends it quietly", !scanner.isAlive && escaped.get() == null, escaped.get()?.toString() ?: "ok")
    val early = Thread { Thread.currentThread().interrupt(); Scan.openPort(listOf("127.0.0.1"), pairPort, 500) {} }
    early.setUncaughtExceptionHandler { _, e -> escaped.set(e) }
    early.start(); early.join(5000)
    check("a scan started already-interrupted doesn't throw", escaped.get() == null, escaped.get()?.toString() ?: "ok")
    val probe = TvProbe.identify(id, "127.0.0.1", pairPort)
    check("probe reads the TV's name, MAC and fingerprint", probe != null && probe.info.name == "Mock Projector" &&
        probe.info.mac == "AA:BB:CC:DD:EE:FF" && probe.sha256 == pin, probe?.info?.name ?: "null")
    check("probe of a closed port returns null", TvProbe.identify(id, "127.0.0.1", 1, 1000) == null)

    // Gesture names Air Gestures sends -> default actions. Slides ("scroll") send signed steps.
    val defaults = Actions.defaults
    fun act(key: String) = Actions.resolve(defaults, key)
    val tapsAndSwipes = listOf("swipe_up", "swipe_down", "swipe_left", "swipe_right", "index_tap", "index_double", "index_triple",
        "index_hold", "middle_tap", "middle_double") + listOf("left", "right", "up", "down", "toward", "away").map { "index_drag_$it" }
    val unmapped = tapsAndSwipes.filter { act("EVT_" + it.uppercase()) == Action.NONE }
    check("every tap, swipe and index drag has a default action", unmapped.isEmpty(), unmapped.toString())
    check("slide key naming", Actions.stepKey("EVT_INDEX_LEFT_RIGHT", 3) == "EVT_INDEX_LEFT_RIGHT_UP" && Actions.stepKey("EVT_INDEX_LEFT_RIGHT", -2) == "EVT_INDEX_LEFT_RIGHT_DOWN")
    check("index slide right/left", act("EVT_INDEX_LEFT_RIGHT_UP") == Action.RIGHT && act("EVT_INDEX_LEFT_RIGHT_DOWN") == Action.LEFT)
    check("index slide up/down", act("EVT_INDEX_UP_DOWN_UP") == Action.UP && act("EVT_INDEX_UP_DOWN_DOWN") == Action.DOWN)
    check("middle slides mapped too", act("EVT_MIDDLE_LEFT_RIGHT_UP") == Action.RIGHT && act("EVT_MIDDLE_UP_DOWN_DOWN") == Action.DOWN)
    check("unlisted slider is understood from its name", act("EVT_THUMB_LEFT_RIGHT_UP") == Action.RIGHT && act("EVT_THUMB_UP_DOWN_DOWN") == Action.DOWN &&
        act("EVT_X_RIGHT_LEFT_UP") == Action.LEFT && act("EVT_X_DOWN_UP_UP") == Action.DOWN)
    check("dial still volume", act("EVT_DIAL_UP") == Action.VOL_UP && act("EVT_DIAL_DOWN") == Action.VOL_DOWN)
    check("a user's 'nothing' beats the built-in meaning", Actions.resolve(mapOf("EVT_INDEX_LEFT_RIGHT_UP" to "NONE"), "EVT_INDEX_LEFT_RIGHT_UP") == Action.NONE)
    check("a user's choice beats the name", Actions.resolve(mapOf("EVT_INDEX_LEFT_RIGHT_UP" to "VOL_UP"), "EVT_INDEX_LEFT_RIGHT_UP") == Action.VOL_UP)
    check("unknown gesture has no action", act("EVT_SOMETHING_NEW") == Action.NONE)
    check("fast slides send few keys, volume more", Actions.stepCap(Action.RIGHT) == 6 && Actions.stepCap(Action.SCROLL_DOWN) == 6 && Actions.stepCap(Action.VOL_UP) == 20)
    check("readable names for steps", Actions.pretty("EVT_INDEX_LEFT_RIGHT_UP") == "Index left right (+)" && Actions.pretty("EVT_DIAL_DOWN") == "Dial (−)" && Actions.pretty("EVT_SWIPE_UP") == "Swipe up")

    // Smoothness and reliability.
    val speedRemote = AtvRemote(id, "127.0.0.1", pin, listener, remotePort)
    speedRemote.connect(5000)
    log.writeText(log.readText().lines().filter { !it.contains("\"code\": 22,") }.joinToString("\n")) // forget earlier RIGHT keys
    check("spaced key burst accepted", speedRemote.keys(Keys.RIGHT, 5, 40))
    check("all 5 paced keys arrive", waitFor(3000) { log.readLines().count { it.contains("\"code\": 22,") } == 5 })
    val stamps = log.readLines().filter { it.contains("\"code\": 22,") }.map { Regex("\"t\": ([0-9.]+)").find(it)!!.groupValues[1].toDouble() }
    val gaps = stamps.zipWithNext { a, b -> (b - a) * 1000 }
    check("paced keys are ~40 ms apart (not a single instant)", gaps.all { it in 30.0..400.0 }, gaps.joinToString { "%.0f".format(it) })
    check("a single key is written within a few ms", speedRemote.key(Keys.INFO) && waitFor { speedRemote.lastSendDelayMs >= 0 && speedRemote.lastSendDelayMs < 50 }, "%.2f ms".format(speedRemote.lastSendDelayMs))
    speedRemote.close()

    // A silent (dead) link must be noticed instead of swallowing keys.
    val deadClosed = arrayOfNulls<String>(1)
    val quiet = AtvRemote(id, "127.0.0.1", pin, object : AtvRemote.Listener { override fun onClosed(reason: String) { deadClosed[0] = reason } }, remotePort, staleMs = 700)
    quiet.connect(5000)
    check("fresh link accepts keys", quiet.key(Keys.MENU))
    Thread.sleep(1100) // the fake TV sends only one ping at start, so nothing arrives after this
    check("a link silent for longer than the limit refuses keys", !quiet.key(Keys.MENU) && !quiet.ready)
    check("and the owner is told it closed", waitFor { deadClosed[0] != null }, deadClosed[0] ?: "no")
    quiet.close()

    // Keys queued during a reconnect.
    val pend = PendingKeys(max = 3, ttlMs = 1000)
    pend.add(Keys.UP, now = 1000); pend.add(Keys.DOWN, now = 1100); pend.add(Keys.LEFT, now = 1200); pend.add(Keys.RIGHT, now = 1300)
    check("pending queue keeps the newest few", pend.drain(now = 1400).map { it.first } == listOf(Keys.DOWN, Keys.LEFT, Keys.RIGHT))
    check("drain empties the queue", pend.drain(now = 1400).isEmpty())
    pend.add(Keys.UP, now = 1000); pend.add(Keys.DOWN, now = 1900)
    check("stale pending keys are dropped", pend.drain(now = 2500).map { it.first } == listOf(Keys.DOWN))

    // Network round-trip measurement.
    val rtt = Latency.tcpRtt("127.0.0.1", remotePort, 8, 1000, 5)
    check("round-trip probe on loopback", rtt.ok == 8 && rtt.avgMs < 50 && rtt.minMs <= rtt.avgMs && rtt.avgMs <= rtt.maxMs, "avg %.2f ms max %.2f ms".format(rtt.avgMs, rtt.maxMs))
    check("round-trip probe to a closed port reports losses", Latency.tcpRtt("127.0.0.1", 1, 3, 300, 0).ok == 0)

    // Gesture tester bookkeeping.
    GestureTests.clear()
    check("untried gesture", GestureTests.status("EVT_INDEX_TAP", Action.SELECT) == Status.NOT_TRIED)
    GestureTests.note("EVT_INDEX_TAP", Action.SELECT, sent = true, fromBand = true)
    check("band + sent = works", GestureTests.status("EVT_INDEX_TAP", Action.SELECT) == Status.WORKS)
    GestureTests.note("EVT_SWIPE_UP", Action.UP, sent = null, fromBand = true)
    check("band only (test mode) is not claimed to work", GestureTests.status("EVT_SWIPE_UP", Action.UP) == Status.BAND_ONLY)
    GestureTests.note("EVT_SWIPE_DOWN", Action.DOWN, sent = false, fromBand = true)
    check("band but nothing could send it", GestureTests.status("EVT_SWIPE_DOWN", Action.DOWN) == Status.BAND_NOT_SENT)
    GestureTests.note("EVT_INDEX_HOLD", Action.NONE, sent = false, fromBand = true)
    check("band but no action assigned", GestureTests.status("EVT_INDEX_HOLD", Action.NONE) == Status.NO_ACTION)
    GestureTests.note("EVT_SWIPE_LEFT", Action.LEFT, sent = true, fromBand = false)
    check("tried by hand only: device works, band unconfirmed", GestureTests.status("EVT_SWIPE_LEFT", Action.LEFT) == Status.DEVICE_ONLY && GestureTests.get("EVT_SWIPE_LEFT")!!.bandCount == 0)
    GestureTests.note("EVT_SWIPE_RIGHT", Action.RIGHT, sent = false, fromBand = false)
    check("tried by hand and failed", GestureTests.status("EVT_SWIPE_RIGHT", Action.RIGHT) == Status.DEVICE_FAILED)
    GestureTests.note("EVT_SWIPE_LEFT", Action.LEFT, sent = null, fromBand = true)
    check("then the band gesture arrives: both confirmed", GestureTests.status("EVT_SWIPE_LEFT", Action.LEFT) == Status.WORKS)
    GestureTests.note("EVT_INDEX_TAP", Action.SELECT, sent = null, fromBand = true)
    check("a later test-only receipt keeps the earlier sent result", GestureTests.status("EVT_INDEX_TAP", Action.SELECT) == Status.WORKS && GestureTests.get("EVT_INDEX_TAP")!!.bandCount == 2)
    check("count of gestures seen from the band", GestureTests.seenFromBand(listOf("EVT_INDEX_TAP", "EVT_SWIPE_UP", "EVT_SWIPE_RIGHT", "EVT_NEVER")) == 2)
    val before = GestureTests.signature(); GestureTests.note("EVT_INDEX_TAP", Action.SELECT, true, true, now = System.currentTimeMillis() + 5)
    check("signature changes when results change", GestureTests.signature() != before)
    GestureTests.clear()
    check("reset clears results", GestureTests.keys().isEmpty())

    // Wake-on-LAN packet: 6 x FF, then the MAC 16 times.
    val magic = WakeOnLan.packet("aa:bb:cc:dd:ee:ff")
    check("magic packet is 102 bytes with the MAC repeated 16 times", magic.size == 102 &&
        magic.take(6).all { it == 0xFF.toByte() } &&
        (0 until 16).all { r -> (0 until 6).all { i -> magic[6 + r * 6 + i] == byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte(), 0xEE.toByte(), 0xFF.toByte())[i] } })
    check("magic packet sends on loopback", WakeOnLan.send("AA:BB:CC:DD:EE:FF", listOf(java.net.InetAddress.getByName("127.0.0.1"))) >= 1)
    expectThrows<IllegalArgumentException>("bad MAC rejected") { WakeOnLan.packet("not-a-mac") }
    check("non-MAC certificate subject yields no MAC", TvInfo.parse(id.cert).mac == null)

    // 4. Reconnect works and the pin is enforced.
    val again = AtvRemote(id, "127.0.0.1", pin, listener, remotePort)
    again.connect(5000)
    check("reconnect after close", again.ready && again.key(Keys.MENU))
    again.close()
    expectThrows<NeedsPairing>("a TV with a different certificate is refused (pin mismatch)") {
        AtvRemote(id, "127.0.0.1", "00".repeat(32), listener, remotePort).connect(4000)
    }
    expectThrows<java.io.IOException>("connection to a closed port fails fast") {
        AtvRemote(id, "127.0.0.1", pin, listener, 1).connect(3000)
    }
    println(if (failures == 0) "ALL PASSED" else "$failures FAILED")
    System.exit(if (failures == 0) 0 else 1)
}
