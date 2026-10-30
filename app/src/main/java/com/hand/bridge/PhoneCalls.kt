package com.hand.bridge

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telecom.TelecomManager

/**
 * Regular cellular calls only. This is not Android Auto screen control and
 * not WhatsApp/Signal or other self-managed VoIP call control.
 *
 * API 26/28 TelecomManager methods are deprecated, but are the public
 * ANSWER_PHONE_CALLS APIs that do not require changing the user's default
 * phone app. Wearable InCallService requires more elaborate call-role access.
 */
object PhoneCalls {
    @Volatile var lastStatus: String = "Not tested"
        private set

    fun permitted(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED

    @Suppress("DEPRECATION")
    fun answer(ctx: Context): Boolean {
        if (!permitted(ctx)) {
            lastStatus = "Grant Phone calls permission on Devices first"
            Trace.add("phone calls: answer blocked, permission missing")
            return false
        }
        return try {
            val tm = ctx.getSystemService(TelecomManager::class.java)
            if (tm == null) {
                lastStatus = "Android Telecom service is unavailable"
                false
            } else {
                tm.acceptRingingCall()
                // This API returns void; a completed invocation does not
                // prove there was a ringing call or that it was answered.
                lastStatus = "Answer requested; confirm on phone/car display"
                Trace.add("phone calls: acceptRingingCall requested (not verified)")
                true
            }
        } catch (e: Exception) {
            lastStatus = "Answer failed: ${e.javaClass.simpleName}"
            Trace.add("phone calls: answer error ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    @Suppress("DEPRECATION")
    fun end(ctx: Context): Boolean {
        if (!permitted(ctx)) {
            lastStatus = "Grant Phone calls permission on Devices first"
            Trace.add("phone calls: end blocked, permission missing")
            return false
        }
        return try {
            val tm = ctx.getSystemService(TelecomManager::class.java)
            val ended = tm?.endCall() == true
            lastStatus = if (ended) "Android accepted the end/reject request" else
                "No supported active/ringing call to end"
            Trace.add("phone calls: endCall result=$ended")
            ended
        } catch (e: Exception) {
            lastStatus = "End failed: ${e.javaClass.simpleName}"
            Trace.add("phone calls: end error ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }
}
