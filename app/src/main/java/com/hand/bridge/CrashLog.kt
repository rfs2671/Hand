package com.hand.bridge

import android.content.Context

/**
 * Remembers the last crash so the next launch can show it. Without a computer there's no other way
 * to see why the app closed.
 */
object CrashLog {
    private fun sp(c: Context) = c.applicationContext.getSharedPreferences("crash", Context.MODE_PRIVATE)

    fun install(app: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                val text = "Thread ${thread.name} · ${java.util.Date()}\n" + android.util.Log.getStackTraceString(e).take(4000)
                sp(app).edit().putString("last", text).commit() // commit: the process is about to die
            }
            previous?.uncaughtException(thread, e)
        }
    }

    fun last(c: Context): String? = sp(c).getString("last", null)
    fun clear(c: Context) = sp(c).edit().remove("last").apply()
}
