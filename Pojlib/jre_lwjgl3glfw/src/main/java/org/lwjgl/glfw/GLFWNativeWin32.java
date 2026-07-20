package org.lwjgl.glfw;

import org.lwjgl.system.NativeType;

import javax.annotation.Nullable;

public class GLFWNativeWin32 {
    @Nullable
    @NativeType("char const *")
    public static String glfwGetWin32Adapter(@NativeType("GLFWmonitor *") long monitor) {
        return "QuestCraft";
    }

    @Nullable
    @NativeType("char const *")
    public static String glfwGetWin32Monitor(@NativeType("GLFWmonitor *") long monitor) {
        return "QuestCraft";
    }

    @NativeType("HWND")
    public static long glfwGetWin32Window(@NativeType("GLFWwindow *") long window) {
        return window;
    }

    @NativeType("GLFWwindow *")
    public static long glfwAttachWin32Window(@NativeType("HWND") long handle, @NativeType("GLFWwindow *") long share) {
        return handle != 0L ? handle : 1L;
    }
}
