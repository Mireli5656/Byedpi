package com.byedpi.app.core

/**
 * Runs the native ByeDPI proxy on a dedicated thread. ByeDpiNative.start() blocks until the
 * proxy stops, so it must never run on the main thread.
 */
class ProxyEngine {

    @Volatile
    private var thread: Thread? = null

    /** Null while the engine is running; the native return code once it has exited. */
    @Volatile
    var exitCode: Int? = null
        private set

    val isRunning: Boolean
        get() = thread?.isAlive == true

    fun start(args: List<String>) {
        exitCode = null
        val worker = Thread(null, {
            val code = try {
                ByeDpiNative.start(args.toTypedArray())
            } catch (t: Throwable) {
                DiagnosticsLog.e("Engine failed: ${t.javaClass.simpleName}")
                -1000
            }
            DiagnosticsLog.i("Engine stopped (code $code)")
            exitCode = code
        }, "byedpi-engine", STACK_BYTES)
        thread = worker
        worker.start()
    }

    /** Requests a stop and waits briefly. Returns true if the engine thread has ended. */
    fun stop(): Boolean {
        val t = thread ?: return true
        if (!t.isAlive) return true
        try {
            ByeDpiNative.stop()
        } catch (e: Throwable) {
            DiagnosticsLog.e("Engine stop request failed: ${e.javaClass.simpleName}")
        }
        t.join(STOP_WAIT_MS)
        val ended = !t.isAlive
        if (!ended) DiagnosticsLog.w("Engine thread did not stop within ${STOP_WAIT_MS} ms")
        return ended
    }

    private companion object {
        const val STACK_BYTES = 2L * 1024 * 1024
        const val STOP_WAIT_MS = 4000L
    }
}
