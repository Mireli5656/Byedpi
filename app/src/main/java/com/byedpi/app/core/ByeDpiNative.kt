package com.byedpi.app.core

/**
 * Kotlin side of libbyedpi.so (jni/engine/byedpi_jni.c).
 *
 * start() return values: 0 = clean stop, > 0 = engine exit code (128 + n = killed by signal n),
 * -1 = library not loaded, -2 = already running, -3 = out of memory, -4 = fork failed,
 * -5 = waitpid failed.
 */
object ByeDpiNative {

    val isLoaded: Boolean = try {
        System.loadLibrary("byedpi")
        true
    } catch (t: Throwable) {
        false
    }

    /** [args] excludes argv[0]. Blocks until the engine stops; call it from a dedicated thread. */
    fun start(args: Array<String>): Int = if (isLoaded) nativeStart(args) else -1

    /** Requests a stop and waits briefly for the engine process to end. */
    fun stop(): Boolean = if (isLoaded) nativeStop() else true

    private external fun nativeStart(args: Array<String>): Int

    private external fun nativeStop(): Boolean
}
