//
// Created by Judge on 12/23/2021.
//
#include <thread>
#include <string>
#include <cerrno>
#include <android/hardware_buffer.h>
#include <fcntl.h>
#include <unistd.h>
#include <jni.h>
#include <vulkan/vulkan.h>
#include <vulkan/vulkan_android.h>
#include <environ/environ.h>
#include <GLES3/gl32.h>
#include <EGL/egl.h>
#include <openxr/openxr.h>
#include "log.h"

static void throwIllegalState(JNIEnv* env, const char* message) {
    jclass exceptionClass = env->FindClass("java/lang/IllegalStateException");
    if (exceptionClass != nullptr) {
        env->ThrowNew(exceptionClass, message);
    }
}

extern "C"
JNIEXPORT jlong JNICALL
Java_org_vivecraft_util_VLoader_getEGLDisplay(JNIEnv* env, jclass clazz) {
    return reinterpret_cast<jlong>(eglGetCurrentDisplay());
}

extern "C"
JNIEXPORT jlong JNICALL
Java_org_vivecraft_util_VLoader_getEGLContext(JNIEnv* env, jclass clazz) {
    return reinterpret_cast<jlong>(eglGetCurrentContext());
}

extern "C"
JNIEXPORT jlong JNICALL
Java_org_vivecraft_util_VLoader_getEGLConfig(JNIEnv* env, jclass clazz) {
    EGLConfig cfg;
    EGLint num_configs;

    static const EGLint attribs[] = {
            EGL_RED_SIZE, 8,
            EGL_GREEN_SIZE, 8,
            EGL_BLUE_SIZE, 8,
            EGL_ALPHA_SIZE, 8,
            // Minecraft required on initial 24
            EGL_DEPTH_SIZE, 24,
            EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
            EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
            EGL_NONE
    };

    eglChooseConfig(eglGetCurrentDisplay(), attribs, &cfg, 1, &num_configs);
    return reinterpret_cast<jlong>(cfg);
}

extern "C"
JNIEXPORT jlong JNICALL
Java_org_vivecraft_util_VLoader_getDalvikVM(JNIEnv* env, jclass clazz) {
    return reinterpret_cast<jlong>(pojav_environ->dalvikJavaVMPtr);
}

extern "C"
JNIEXPORT jlong JNICALL
Java_org_vivecraft_util_VLoader_getDalvikActivity(JNIEnv* env, jclass clazz) {
    return reinterpret_cast<jlong>(pojav_environ->activity);
}

extern "C"
JNIEXPORT void JNICALL
Java_org_vivecraft_util_VLoader_setupAndroid(JNIEnv* env, jclass clazz) {
    JNIEnv* newEnv = nullptr;
    jint envStatus = pojav_environ->dalvikJavaVMPtr->GetEnv(
            reinterpret_cast<void**>(&newEnv), JNI_VERSION_1_6);
    if (envStatus == JNI_EDETACHED) {
        envStatus = pojav_environ->dalvikJavaVMPtr->AttachCurrentThread(&newEnv, nullptr);
    }
    if (envStatus != JNI_OK || newEnv == nullptr) {
        throwIllegalState(env, "Unable to attach the Vivecraft OpenXR handoff thread to Android");
        return;
    }

    jclass apiClass = pojav_environ->apiClass;
    jfieldID gameReadyField = newEnv->GetStaticFieldID(apiClass, "gameReady", "Z");
    jfieldID releasedField = newEnv->GetStaticFieldID(apiClass, "openXRHandoffReleased", "Z");
    if (newEnv->ExceptionCheck() || gameReadyField == nullptr || releasedField == nullptr) {
        newEnv->ExceptionClear();
        throwIllegalState(env, "QuestCraft OpenXR handoff fields are unavailable");
        return;
    }

    newEnv->SetStaticBooleanField(apiClass, gameReadyField, JNI_TRUE);

    // Unity keeps the active headset session while Minecraft initializes. Pause here, immediately
    // before xrInitializeLoaderKHR, until Unity has stopped and deinitialized its OpenXR loader.
    for (int attempt = 0; attempt < 15000; ++attempt) {
        if (newEnv->GetStaticBooleanField(apiClass, releasedField) == JNI_TRUE) {
            return;
        }
        usleep(1000);
    }

    throwIllegalState(env, "Timed out waiting for Unity to release OpenXR ownership");
}

extern "C"
JNIEXPORT void JNICALL
Java_pojlib_util_VLoader_setAndroidInitInfo(JNIEnv *env, jclass clazz, jobject ctx) {
    pojav_environ->activity = env->NewGlobalRef(ctx);
}
