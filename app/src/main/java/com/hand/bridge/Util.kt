package com.hand.bridge

import android.content.Context

inline fun <reified T : Any> Context.svc(): T = getSystemService(T::class.java)!!
