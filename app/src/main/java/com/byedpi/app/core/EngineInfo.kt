package com.byedpi.app.core

import android.content.Context
import java.io.IOException

/** Reads the exact upstream versions the CI workflow wrote into assets/upstream-versions.txt. */
object EngineInfo {

    data class Component(val name: String, val ref: String, val commit: String)

    fun readUpstream(context: Context): List<Component> {
        return try {
            context.assets.open("upstream-versions.txt").bufferedReader().useLines { lines ->
                lines.mapNotNull { parse(it) }.toList()
            }
        } catch (e: IOException) {
            emptyList()
        }
    }

    private fun parse(line: String): Component? {
        val name = line.substringBefore('=', "").trim()
        val rest = line.substringAfter('=', "").trim()
        if (name.isEmpty() || rest.isEmpty()) return null
        val parts = rest.split(' ', limit = 2)
        return Component(name, parts[0], parts.getOrElse(1) { "" }.take(10))
    }
}
