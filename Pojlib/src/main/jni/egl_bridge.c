#include <jni.h>
#include <assert.h>
#include <dlfcn.h>

#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/types.h>
#include <unistd.h>

#include <EGL/egl.h>

#ifdef GLES_TEST
#include <GLES2/gl2.h>
#endif

#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <android/rect.h>
#include <string.h>
#include <pthread.h>
#include "utils.h"
#include "environ/environ.h"
#include "GL/gl.h"

typedef EGLDisplay eglGetDisplay_t (EGLNativeDisplayType display_id);
typedef EGLBoolean eglInitialize_t (EGLDisplay dpy, EGLint *major, EGLint *minor);
typedef EGLBoolean eglChooseConfig_t (EGLDisplay dpy, const EGLint *attrib_list, EGLConfig *configs, EGLint config_size, EGLint *num_config);
typedef EGLBoolean eglGetConfigAttrib_t (EGLDisplay dpy, EGLConfig config, EGLint attribute, EGLint *value);
typedef EGLBoolean eglBindAPI_t (EGLenum api);
typedef EGLContext eglCreateContext_t (EGLDisplay dpy, EGLConfig config, EGLContext share_context, const EGLint *attrib_list);
typedef EGLBoolean eglMakeCurrent_t (EGLDisplay dpy, EGLSurface draw, EGLSurface read, EGLContext ctx);
typedef EGLint eglGetError_t (void);
typedef EGLBoolean eglSwapInterval_t (EGLDisplay dpy, EGLint interval);
typedef __eglMustCastToProperFunctionPointerType eglGetProcAddress_t (const char *procname);

eglGetDisplay_t* eglGetDisplay_p;
eglInitialize_t* eglInitialize_p;
eglChooseConfig_t* eglChooseConfig_p;
eglGetConfigAttrib_t* eglGetConfigAttrib_p;
eglBindAPI_t* eglBindAPI_p;
eglCreateContext_t* eglCreateContext_p;
eglMakeCurrent_t* eglMakeCurrent_p;
eglGetError_t* eglGetError_p;
eglSwapInterval_t* eglSwapInterval_p;
eglGetProcAddress_t* eglGetProcAddress_p;

EGLContext xrEglContext;
EGLDisplay xrEglDisplay;
EGLConfig xrConfig;

void* gbuffer;
static ANativeWindow* minecraftWindow;
static pthread_mutex_t minecraftWindowMutex = PTHREAD_MUTEX_INITIALIZER;
static void* vulkanDriverHandle;
static int vulkanFpsCounter;

typedef void (*PojavVkVoidFunction)(void);
typedef PojavVkVoidFunction (*PojavVkGetInstanceProcAddr)(void* instance, const char* name);
static PojavVkGetInstanceProcAddr vulkanGetInstanceProcAddr;

typedef void (*FCLInjectorCallback)(void);
static FCLInjectorCallback fclInjectorCallback;

#define POJAV_EXPORTED __attribute__((visibility("default"), used))

/*
 * The FoldCraft GLFW shim resolves these two entry points while GLFW is being
 * initialized, even when QuestCraft does not configure an FCL injector. Keep
 * the callback for ABI compatibility; QuestCraft has no FCL bridge that needs
 * to invoke it.
 */
POJAV_EXPORTED FCLInjectorCallback pojavSetInjectorCallback(FCLInjectorCallback callback) {
    FCLInjectorCallback previous = fclInjectorCallback;
    fclInjectorCallback = callback;
    return previous;
}

POJAV_EXPORTED void pojavSetHitResultType(int type) {
    (void) type;
}

static void* getVulkanDriverHandle(void) {
    if (vulkanDriverHandle == NULL) {
        vulkanDriverHandle = dlopen("/system/lib64/libvulkan.so", RTLD_NOW | RTLD_LOCAL);
        if (vulkanDriverHandle == NULL) {
            vulkanDriverHandle = dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL);
        }
    }
    return vulkanDriverHandle;
}

static PojavVkGetInstanceProcAddr getVulkanInstanceProcAddr(void) {
    if (vulkanGetInstanceProcAddr == NULL) {
        void* driver = getVulkanDriverHandle();
        if (driver != NULL) {
            vulkanGetInstanceProcAddr = (PojavVkGetInstanceProcAddr)
                    dlsym(driver, "vkGetInstanceProcAddr");
        }
    }
    return vulkanGetInstanceProcAddr;
}

/*
 * The bundled GLFWVulkan implementation performs these operations in Java,
 * while FoldCraft/FCL-compatible LWJGL variants may resolve the equivalent
 * Pojav entry points from libpojavexec. Export the Android Vulkan ABI here so
 * either path can initialize against the same native library.
 */
POJAV_EXPORTED void pojavInitVulkanLoader(PojavVkGetInstanceProcAddr loader) {
    if (loader != NULL) {
        vulkanGetInstanceProcAddr = loader;
    }
}

POJAV_EXPORTED int pojavVulkanSupported(void) {
    return getVulkanInstanceProcAddr() != NULL;
}

POJAV_EXPORTED const char** pojavGetRequiredInstanceExtensions(uint32_t* count) {
    static const char* extensions[] = {
            "VK_KHR_surface",
            "VK_KHR_android_surface"
    };
    if (count != NULL) {
        *count = sizeof(extensions) / sizeof(extensions[0]);
    }
    return extensions;
}

POJAV_EXPORTED PojavVkVoidFunction pojavGetInstanceProcAddress(
        void* instance, const char* name) {
    PojavVkGetInstanceProcAddr loader = getVulkanInstanceProcAddr();
    return loader == NULL || name == NULL ? NULL : loader(instance, name);
}

POJAV_EXPORTED int pojavGetPhysicalDevicePresentationSupport(
        void* instance, void* device, uint32_t queueFamily) {
    (void) instance;
    (void) device;
    (void) queueFamily;
    /* Android presentation support is established by creating the surface. */
    return 1;
}

typedef struct PojavVkAndroidSurfaceCreateInfo {
    int32_t sType;
    const void* pNext;
    uint32_t flags;
    ANativeWindow* window;
} PojavVkAndroidSurfaceCreateInfo;

typedef int32_t (*PojavVkCreateAndroidSurface)(
        void* instance,
        const PojavVkAndroidSurfaceCreateInfo* createInfo,
        const void* allocator,
        uint64_t* surface);

POJAV_EXPORTED int32_t pojavCreateWindowSurface(
        void* instance, void* window, const void* allocator, uint64_t* surface) {
    const int32_t VK_ERROR_INITIALIZATION_FAILED = -3;
    const int32_t VK_ERROR_EXTENSION_NOT_PRESENT = -7;
    PojavVkCreateAndroidSurface createSurface = (PojavVkCreateAndroidSurface)
            pojavGetInstanceProcAddress(instance, "vkCreateAndroidSurfaceKHR");
    if (createSurface == NULL) {
        return VK_ERROR_EXTENSION_NOT_PRESENT;
    }
    if (window == NULL || surface == NULL) {
        return VK_ERROR_INITIALIZATION_FAILED;
    }

    PojavVkAndroidSurfaceCreateInfo createInfo = {
            .sType = 1000008000, /* VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR */
            .pNext = NULL,
            .flags = 0,
            .window = (ANativeWindow*) window
    };
    return createSurface(instance, &createInfo, allocator, surface);
}

JNIEXPORT jlong JNICALL
Java_org_lwjgl_vulkan_VK_getVulkanDriverHandle(JNIEnv* env, jclass clazz) {
    return (jlong) (uintptr_t) getVulkanDriverHandle();
}

JNIEXPORT jlong JNICALL
Java_org_lwjgl_vulkan_VK_getFpsAddress(JNIEnv* env, jclass clazz) {
    return (jlong) (uintptr_t) &vulkanFpsCounter;
}

void* pojavGetNativeWindow() {
    pthread_mutex_lock(&minecraftWindowMutex);
    if (minecraftWindow != NULL) {
        pthread_mutex_unlock(&minecraftWindowMutex);
        return minecraftWindow;
    }

    if (pojav_environ->dalvikJavaVMPtr == NULL || pojav_environ->activity == NULL) {
        pthread_mutex_unlock(&minecraftWindowMutex);
        return NULL;
    }

    JNIEnv* dalvikEnv = NULL;
    bool detachThread = false;
    jint envStatus = (*pojav_environ->dalvikJavaVMPtr)->GetEnv(
            pojav_environ->dalvikJavaVMPtr, (void**) &dalvikEnv, JNI_VERSION_1_6);
    if (envStatus == JNI_EDETACHED) {
        if ((*pojav_environ->dalvikJavaVMPtr)->AttachCurrentThread(
                pojav_environ->dalvikJavaVMPtr, &dalvikEnv, NULL) != JNI_OK) {
            pthread_mutex_unlock(&minecraftWindowMutex);
            return NULL;
        }
        detachThread = true;
    } else if (envStatus != JNI_OK) {
        pthread_mutex_unlock(&minecraftWindowMutex);
        return NULL;
    }

    jclass activityClass = (*dalvikEnv)->GetObjectClass(dalvikEnv, pojav_environ->activity);
    jmethodID getSurface = activityClass == NULL ? NULL : (*dalvikEnv)->GetMethodID(
            dalvikEnv, activityClass, "getMinecraftSurface", "()Landroid/view/Surface;");
    jobject surface = getSurface == NULL ? NULL : (*dalvikEnv)->CallObjectMethod(
            dalvikEnv, pojav_environ->activity, getSurface);

    if ((*dalvikEnv)->ExceptionCheck(dalvikEnv)) {
        (*dalvikEnv)->ExceptionClear(dalvikEnv);
        surface = NULL;
    }
    if (surface != NULL) {
        minecraftWindow = ANativeWindow_fromSurface(dalvikEnv, surface);
        (*dalvikEnv)->DeleteLocalRef(dalvikEnv, surface);
    }
    if (activityClass != NULL) {
        (*dalvikEnv)->DeleteLocalRef(dalvikEnv, activityClass);
    }
    if (detachThread) {
        (*pojav_environ->dalvikJavaVMPtr)->DetachCurrentThread(pojav_environ->dalvikJavaVMPtr);
    }

    ANativeWindow* result = minecraftWindow;
    pthread_mutex_unlock(&minecraftWindowMutex);
    return result;
}

void pojavReleaseNativeWindow(void* window) {
    pthread_mutex_lock(&minecraftWindowMutex);
    if (minecraftWindow != NULL && (window == NULL || window == minecraftWindow)) {
        ANativeWindow_release(minecraftWindow);
        minecraftWindow = NULL;
    }
    pthread_mutex_unlock(&minecraftWindowMutex);
}

void pojav_openGLOnLoad() {
}
void pojav_openGLOnUnload() {

}

void pojavTerminate() {
    pojavReleaseNativeWindow(NULL);
}

void dlsym_egl() {
    void* handle = dlopen("libmobileglues.so", RTLD_NOW);
    eglGetProcAddress_p = (eglGetProcAddress_t*) dlsym(handle, "eglGetProcAddress");
    eglGetDisplay_p = (eglGetDisplay_t*) eglGetProcAddress_p("eglGetDisplay");
    eglInitialize_p = (eglInitialize_t*) eglGetProcAddress_p("eglInitialize");
    eglChooseConfig_p = (eglChooseConfig_t*) eglGetProcAddress_p("eglChooseConfig");
    eglGetConfigAttrib_p = (eglGetConfigAttrib_t*) eglGetProcAddress_p("eglGetConfigAttrib");
    eglBindAPI_p = (eglBindAPI_t*) eglGetProcAddress_p("eglBindAPI");
    eglCreateContext_p = (eglCreateContext_t*) eglGetProcAddress_p("eglCreateContext");
    eglMakeCurrent_p = (eglMakeCurrent_t*) eglGetProcAddress_p("eglMakeCurrent");
    eglGetError_p = (eglGetError_t*) eglGetProcAddress_p("eglGetError");
    eglSwapInterval_p = (eglSwapInterval_t*) eglGetProcAddress_p("eglSwapInterval");
}

void* pojavGetCurrentContext() {
    return xrEglContext;
}

int xrEglInit() {
    dlsym_egl();

    if (xrEglDisplay == NULL || xrEglDisplay == EGL_NO_DISPLAY) {
        xrEglDisplay = eglGetDisplay_p(EGL_DEFAULT_DISPLAY);
        if (xrEglDisplay == EGL_NO_DISPLAY) {
            printf("EGLBridge: Error eglGetDefaultDisplay() failed: %p\n", eglGetError_p());
            return 0;
        }
    }

    printf("EGLBridge: Initializing\n");
    // printf("EGLBridge: ANativeWindow pointer = %p\n", androidWindow);
    //(*env)->ThrowNew(env,(*env)->FindClass(env,"java/lang/Exception"),"Trace exception");
    if (!eglInitialize_p(xrEglDisplay, NULL, NULL)) {
        printf("EGLBridge: Error eglInitialize() failed: %d\n", eglGetError_p());
        return 0;
    }

    EGLint attribs[] = {
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

    EGLint num_configs;
    EGLint vid;

    if (!eglChooseConfig_p(xrEglDisplay, attribs, &xrConfig, 1, &num_configs)) {
        printf("EGLBridge: Error couldn't get an EGL visual config: %d\n", eglGetError_p());
        return 0;
    }

    assert(xrConfig);
    assert(num_configs > 0);

    if (!eglGetConfigAttrib_p(xrEglDisplay, xrConfig, EGL_NATIVE_VISUAL_ID, &vid)) {
        printf("EGLBridge: Error eglGetConfigAttrib() failed: %d\n", eglGetError_p());
        return 0;
    }

    eglBindAPI_p(EGL_OPENGL_ES_API);

    printf("XREGLBridge: Initialized!\n");
    printf("XREGLBridge: ThreadID=%d\n", gettid());
    printf("XREGLBridge: XREGLDisplay=%p\n",
/* window==0 ? EGL_NO_CONTEXT : */
           xrEglDisplay
    );

    return 1;
}

int pojavInit() {
    savedWidth = 1080;
    savedHeight = 720;
    printf("XREGLBridge: Thread name is %d\n", gettid());
    return 1;
}

void pojavSetWindowHint(int hint, int value) {
    // Stub
}


int32_t stride;
void pojavSwapBuffers() {
    //eglSwapBuffers_p(xrEglDisplay, xrEglSurface);
}

bool locked = false;
void pojavMakeCurrent(void* window) {
    EGLBoolean success = eglMakeCurrent_p(
            xrEglDisplay,
            EGL_NO_SURFACE,
            EGL_NO_SURFACE,
            window
    );

    xrEglContext = window;

    if (success == EGL_FALSE) {
        printf("XREGLBridge: Error: eglMakeCurrent() failed: %p\n", eglGetError());
    } else {
        printf("XREGLBridge: eglMakeCurrent() succeed!\n");
    }
}

JNIEXPORT JNICALL jlong
Java_pojlib_util_JREUtils_getEGLDisplayPtr(JNIEnv *env, jclass clazz) {
    return (jlong) &xrEglDisplay;
}

JNIEXPORT JNICALL jlong
Java_pojlib_util_JREUtils_getEGLContextPtr(JNIEnv *env, jclass clazz) {
    return (jlong) &xrEglContext;
}

JNIEXPORT JNICALL jlong
Java_pojlib_util_JREUtils_getEGLConfigPtr(JNIEnv *env, jclass clazz) {
    return (jlong) &xrConfig;
}

void* pojavCreateContext(void* contextSrc) {
    if ((xrEglDisplay == NULL || xrEglDisplay == EGL_NO_DISPLAY) && !xrEglInit()) {
        return NULL;
    }

    const EGLint ctx_attribs[] = {
            EGL_CONTEXT_CLIENT_VERSION, 3,
            EGL_NONE
    };
    EGLContext* ctx = eglCreateContext_p(xrEglDisplay, xrConfig, contextSrc, ctx_attribs);

    printf("XREGLBridge: %p\n", ctx);
    return ctx;
}

JNIEXPORT JNICALL jlong
Java_org_lwjgl_opengl_GL_getGraphicsBufferAddr(JNIEnv *env, jobject thiz) {
    return (jlong) &gbuffer;
}
JNIEXPORT JNICALL jintArray
Java_org_lwjgl_opengl_GL_getNativeWidthHeight(JNIEnv *env, jobject thiz) {
    jintArray ret = (*env)->NewIntArray(env,2);
    jint arr[] = {savedWidth, savedHeight};
    (*env)->SetIntArrayRegion(env,ret,0,2,arr);
    return ret;
}
void pojavSwapInterval(int interval) {
    eglSwapInterval_p(xrEglDisplay, interval);
}
