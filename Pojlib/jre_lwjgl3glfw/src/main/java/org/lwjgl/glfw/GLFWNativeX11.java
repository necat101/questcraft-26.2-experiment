package org.lwjgl.glfw;

import org.lwjgl.system.NativeType;

import java.nio.ByteBuffer;

import javax.annotation.Nullable;

public class GLFWNativeX11 {
    @NativeType("Display *")
    public static long glfwGetX11Display() {
        return 1L;
    }

    @NativeType("RRCrtc")
    public static long glfwGetX11Adapter(@NativeType("GLFWmonitor *") long monitor) {
        return monitor != 0L ? monitor : 1L;
    }

    @NativeType("RROutput")
    public static long glfwGetX11Monitor(@NativeType("GLFWmonitor *") long monitor) {
        return monitor != 0L ? monitor : 1L;
    }

    @NativeType("Window")
    public static long glfwGetX11Window(@NativeType("GLFWwindow *") long window) {
        return window != 0L ? window : 1L;
    }

    public static void glfwSetX11SelectionString(@NativeType("char const *") ByteBuffer string) {
    }

    public static void glfwSetX11SelectionString(@NativeType("char const *") CharSequence string) {
    }
    @Nullable
    @NativeType("char const *")
    public static String glfwGetX11SelectionString() {
        return "";
    }
}
