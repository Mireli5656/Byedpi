package com.byedpi.app.core

/**
 * JNI class of hev-socks5-tunnel. The package and class name are baked into the native
 * library through -DPKGNAME=com/byedpi/app/core -DCLSNAME=TProxyService (jni/Application.mk),
 * so this class must not be renamed or moved.
 */
object TProxyService {

    val isLoaded: Boolean = try {
        System.loadLibrary("hev-socks5-tunnel")
        true
    } catch (t: Throwable) {
        false
    }

    fun start(configPath: String, tunFd: Int): Boolean =
        isLoaded && TProxyStartService(configPath, tunFd)

    fun stop(): Boolean = isLoaded && TProxyStopService()

    fun isRunning(): Boolean = isLoaded && TProxyIsRunning()

    /** [txPackets, txBytes, rxPackets, rxBytes], or an empty array. */
    fun stats(): LongArray = if (isLoaded) (TProxyGetStats() ?: LongArray(0)) else LongArray(0)

    private external fun TProxyStartService(configPath: String, fd: Int): Boolean

    private external fun TProxyStopService(): Boolean

    private external fun TProxyIsRunning(): Boolean

    private external fun TProxyGetStats(): LongArray?
}
