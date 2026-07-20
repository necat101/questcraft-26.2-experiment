package org.lwjgl.glfw;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.NativeType;

import java.nio.IntBuffer;

import javax.annotation.Nullable;

public class GLFWNativeOSMesa {
    @NativeType("int")
    public static boolean glfwGetOSMesaColorBuffer(@NativeType("GLFWwindow *") long window, @Nullable @NativeType("int *") IntBuffer width, @Nullable @NativeType("int *") IntBuffer height, @Nullable @NativeType("int *") IntBuffer format, @Nullable @NativeType("void **") PointerBuffer buffer) {
        return false;
    }

    public static int glfwGetOSMesaDepthBuffer(@NativeType("GLFWwindow *") long window, @Nullable @NativeType("int *") IntBuffer width, @Nullable @NativeType("int *") IntBuffer height, @Nullable @NativeType("int *") IntBuffer bytesPerValue, @Nullable @NativeType("void **") PointerBuffer buffer) {
        return 0;
    }

    @NativeType("OSMesaContext")
    public static long glfwGetOSMesaContext(@NativeType("GLFWwindow *") long window) {
        return 0L;
    }

    @NativeType("int")
    public static boolean glfwGetOSMesaColorBuffer(@NativeType("GLFWwindow *") long window, @Nullable @NativeType("int *") int[] width, @Nullable @NativeType("int *") int[] height, @Nullable @NativeType("int *") int[] format, @Nullable @NativeType("void **") PointerBuffer buffer) {
        return false;
    }

    public static int glfwGetOSMesaDepthBuffer(@NativeType("GLFWwindow *") long window, @Nullable @NativeType("int *") int[] width, @Nullable @NativeType("int *") int[] height, @Nullable @NativeType("int *") int[] bytesPerValue, @Nullable @NativeType("void **") PointerBuffer buffer) {
        return 0;
    }
}
