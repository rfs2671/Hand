package com.hand.bridge

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Short in-memory event log, included in the diagnostic report so problems can be understood without a computer. */
object Trace {
    private val lines = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Synchronized
    fun add(msg: String) {
        lines.addLast("${fmt.format(Date())} $msg")
        while (lines.size > 150) lines.removeFirst()
        android.util.Log.i("HandBridge", msg)
    }

    @Synchronized
    fun dump(): String = lines.joinToString("\n")
}
