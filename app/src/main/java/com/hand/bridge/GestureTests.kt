package com.hand.bridge

import java.util.concurrent.ConcurrentHashMap

/**
 * Results for the Test tab: for each gesture, did it arrive from the band, and did its action get
 * sent to the device? Kept apart from Android code so the logic can be tested on a plain JVM.
 */
object GestureTests {
    class Result {
        @Volatile var bandCount = 0
        @Volatile var bandAt = 0L
        /** Outcome of the latest attempt to perform the action: true sent, false couldn't, null not attempted. */
        @Volatile var sent: Boolean? = null
        @Volatile var sentAt = 0L
        @Volatile var action = Action.NONE
    }

    enum class Status {
        NOT_TRIED,
        /** Arrived from the band and its action was sent to the device. */
        WORKS,
        /** Arrived from the band; sending was off (test only). */
        BAND_ONLY,
        /** Arrived from the band, but nothing connected could carry out its action. */
        BAND_NOT_SENT,
        /** Arrived from the band but has no action assigned. */
        NO_ACTION,
        /** Not seen from the band yet; the action was tried by hand and worked. */
        DEVICE_ONLY,
        /** Not seen from the band yet; the action was tried by hand and couldn't be sent. */
        DEVICE_FAILED
    }

    private val results = ConcurrentHashMap<String, Result>()

    fun note(key: String, action: Action, sent: Boolean?, fromBand: Boolean, now: Long = System.currentTimeMillis()) {
        val r = results.getOrPut(key) { Result() }
        synchronized(r) {
            r.action = action
            if (fromBand) { r.bandCount++; r.bandAt = now }
            if (sent != null) { r.sent = sent; r.sentAt = now }
        }
    }

    fun get(key: String): Result? = results[key]
    fun keys(): Set<String> = results.keys.toSet()
    fun clear() = results.clear()

    fun status(key: String, action: Action): Status {
        val r = results[key] ?: return Status.NOT_TRIED
        return when {
            r.bandCount > 0 && action == Action.NONE -> Status.NO_ACTION
            r.bandCount > 0 && r.sent == true -> Status.WORKS
            r.bandCount > 0 && r.sent == false -> Status.BAND_NOT_SENT
            r.bandCount > 0 -> Status.BAND_ONLY
            r.sent == true -> Status.DEVICE_ONLY
            r.sent == false -> Status.DEVICE_FAILED
            else -> Status.NOT_TRIED
        }
    }

    /** How many of [keys] have arrived from the band at least once. */
    fun seenFromBand(keys: Collection<String>) = keys.count { (results[it]?.bandCount ?: 0) > 0 }

    /** Changes whenever any result changes (cheap way for the screen to know it must redraw). */
    fun signature(): Long = results.values.sumOf { it.bandCount.toLong() * 31 + it.bandAt + it.sentAt + (if (it.sent == true) 1 else 0) }
}
