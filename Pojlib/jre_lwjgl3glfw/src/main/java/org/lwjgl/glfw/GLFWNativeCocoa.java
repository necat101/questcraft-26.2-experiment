package org.lwjgl.glfw;

import org.lwjgl.system.NativeType;

public class GLFWNativeCocoa {
    @NativeType("CGDirectDisplayID")
    public static int glfwGetCocoaMonitor(@NativeType("GLFWmonitor *") long monitor) {
        return (int) monitor;
    }
    @NativeType("id")
    public static long glfwGetCocoaWindow(@NativeType("GLFWwindow *") long window) {
        return window;
    }
}
