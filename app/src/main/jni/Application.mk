APP_OPTIM := release

# hev-socks5-tunnel registers its JNI methods on this class:
#   com.byedpi.app.core.TProxyService
APP_CFLAGS := -O3 -DPKGNAME=com/byedpi/app/core -DCLSNAME=TProxyService

# 16 KB page size compatibility (Android 15+), supported by NDK r27+.
APP_SUPPORT_FLEXIBLE_PAGE_SIZES := true
