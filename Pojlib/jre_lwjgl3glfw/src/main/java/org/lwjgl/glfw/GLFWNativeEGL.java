package org.lwjgl.glfw;

import org.lwjgl.system.NativeType;

public class GLFWNativeEGL {
    @NativeType("EGLDisplay")
    public static long glfwGetEGLDisplay() {
        return 1L;
    }

    @NativeType("EGLContext")
    public static long glfwGetEGLContext(@NativeType("GLFWwindow *") long window) {
        return window != 0L ? window : 1L;
    }

    @NativeType("EGLSurface")
    public static long glfwGetEGLSurface(@NativeType("GLFWwindow *") long window) {
        return 0L;
    }

    @NativeType("EGLConfig")
    public static long glfwGetEGLConfig(@NativeType("GLFWwindow *") long window) {
        return 1L;
    }
}
