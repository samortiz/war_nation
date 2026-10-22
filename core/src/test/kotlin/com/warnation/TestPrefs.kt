package com.warnation

import com.badlogic.gdx.Preferences
import java.lang.reflect.Proxy

/** Map-backed Preferences, standing in for the platform's storage. */
fun memoryPrefs(store: MutableMap<String, Any?> = mutableMapOf()): Preferences {
    lateinit var prefs: Preferences
    prefs = Proxy.newProxyInstance(Preferences::class.java.classLoader, arrayOf(Preferences::class.java)) { _, method, args ->
        when {
            method.name.startsWith("put") -> prefs.also { store[args[0] as String] = args[1] }
            method.name.startsWith("get") && args?.size == 2 -> store[args[0] as String] ?: args[1]
            method.name == "contains" -> store.containsKey(args[0] as String)
            else -> null
        }
    } as Preferences
    return prefs
}
