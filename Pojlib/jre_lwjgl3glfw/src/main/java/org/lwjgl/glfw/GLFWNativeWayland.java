package org.lwjgl.glfw;

import org.lwjgl.system.NativeType;

public class GLFWNativeWayland {
    @NativeType("struct wl_display *")
    public static long glfwGetWaylandDisplay() {
        return 1L;
    }

    @NativeType("struct wl_output *")
    public static long glfwGetWaylandMonitor(@NativeType("GLFWmonitor *") long monitor) {
        return monitor != 0L ? monitor : 1L;
    }

    @NativeType("struct wl_surface *")
    public static long glfwGetWaylandWindow(@NativeType("GLFWwindow *") long window) {
        return window != 0L ? window : 1L;
    }
}
