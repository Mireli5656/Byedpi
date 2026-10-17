LOCAL_PATH := $(call my-dir)

# Upstream ByeDPI sources are cloned by the CI workflow into ../byedpi
BYEDPI_SRC_DIR := $(LOCAL_PATH)/../byedpi
BYEDPI_C_FILES := $(filter-out %/win_service.c,$(wildcard $(BYEDPI_SRC_DIR)/*.c))

ifeq ($(strip $(BYEDPI_C_FILES)),)
$(error ByeDPI sources not found in $(BYEDPI_SRC_DIR). The CI workflow must clone hufrea/byedpi into app/src/main/jni/byedpi first)
endif

include $(CLEAR_VARS)

LOCAL_MODULE := byedpi
LOCAL_SRC_FILES := byedpi_jni.c $(patsubst $(LOCAL_PATH)/%,%,$(BYEDPI_C_FILES))
LOCAL_C_INCLUDES := $(BYEDPI_SRC_DIR)

# -Dmain=ciadpi_main : the upstream main() becomes a normal function we can call after fork().
# -D_GNU_SOURCE / -D_DEFAULT_SOURCE : upstream expects these (e.g. lockf is hidden otherwise).
LOCAL_CFLAGS := -std=gnu11 -O2 -w -D_GNU_SOURCE -D_DEFAULT_SOURCE -Dmain=ciadpi_main
LOCAL_LDLIBS := -llog

include $(BUILD_SHARED_LIBRARY)
