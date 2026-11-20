LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE := jadx_openjdk
LOCAL_SRC_FILES := openjdk.cpp
LOCAL_LDLIBS := -ldl -llog
include $(BUILD_SHARED_LIBRARY)
