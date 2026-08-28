package org.vivecraft.client_vr.provider.openxr;

import com.mojang.blaze3d.platform.Window;
import com.sun.jna.Platform;
import net.minecraft.client.Minecraft;
import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFWNativeGLX;
import org.lwjgl.glfw.GLFWNativeWGL;
import org.lwjgl.glfw.GLFWNativeWin32;
import org.lwjgl.glfw.GLFWNativeX11;
import org.lwjgl.openxr.*;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.Struct;
import org.lwjgl.system.linux.X11;
import org.lwjgl.system.windows.User32;
import org.lwjgl.vulkan.VK10;
import org.vivecraft.client_vr.ClientDataHolderVR;
import org.vivecraft.client_vr.render.RenderConfigException;
import org.vivecraft.client_vr.render.helpers.graphics.GraphicsHelper;
import org.vivecraft.client_vr.render.helpers.graphics.VulkanHelper;
import org.vivecraft.client_vr.settings.VRSettings;
import org.vivecraft.util.VLoader;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import static org.lwjgl.opengl.GL11.GL_RGB10_A2;
import static org.lwjgl.opengl.GL11.GL_RGBA8;
import static org.lwjgl.opengl.GL21.GL_SRGB8;
import static org.lwjgl.opengl.GL21.GL_SRGB8_ALPHA8;
import static org.lwjgl.opengl.GL30.GL_RGB16F;
import static org.lwjgl.opengl.GL30.GL_RGBA16F;
import static org.lwjgl.opengl.GL31.GL_RGBA8_SNORM;
import static org.lwjgl.opengl.GLX13.*;
import static org.lwjgl.system.MemoryStack.stackInts;
import static org.lwjgl.system.MemoryUtil.NULL;

public interface DeviceCompat {
    long getPlatformInfo(MemoryStack stack);

    void initOpenXRLoader(MemoryStack stack);

    String getGraphicsExtension();

    default List<String> getRequiredInstanceExtensions() {
        return List.of(getGraphicsExtension());
    }

    long[] enumerateSwapchainImages(XrSwapchain swapchain, int imageCount, MemoryStack stack);

    long[] getPreferredSwapchainFormats();

    default long getSwapchainUsageFlags() {
        return XR10.XR_SWAPCHAIN_USAGE_COLOR_ATTACHMENT_BIT;
    }

    default boolean isVulkan() {
        return false;
    }

    Struct checkGraphics(MemoryStack stack, XrInstance instance, long systemID) throws RenderConfigException;

    static DeviceCompat detectDevice() {
        boolean android = System.getProperty("os.version", "").contains("Android") ||
            System.getProperty("java.runtime.name", "").contains("Android");
        boolean vulkan = GraphicsHelper.INSTANCE instanceof VulkanHelper;
        if (android) {
            return vulkan ? new MobileVulkan() : new Mobile();
        }
        return vulkan ? new DesktopVulkan() : new Desktop();
    }

    static long[] enumerateOpenGLImages(
        XrSwapchain swapchain, int imageCount, MemoryStack stack, int imageType)
    {
        XrSwapchainImageOpenGLKHR.Buffer images = XrSwapchainImageOpenGLKHR.calloc(imageCount, stack);
        for (XrSwapchainImageOpenGLKHR image : images) {
            image.type(imageType);
        }
        int result = XR10.xrEnumerateSwapchainImages(swapchain, stack.ints(imageCount),
            XrSwapchainImageBaseHeader.create(images.address(), images.capacity()));
        checkResult(result, "xrEnumerateSwapchainImages");
        long[] handles = new long[imageCount];
        for (int i = 0; i < imageCount; i++) {
            handles[i] = images.get(i).image();
        }
        return handles;
    }

    static long[] enumerateVulkanImages(XrSwapchain swapchain, int imageCount, MemoryStack stack) {
        XrSwapchainImageVulkanKHR.Buffer images = XrSwapchainImageVulkanKHR.calloc(imageCount, stack);
        for (XrSwapchainImageVulkanKHR image : images) {
            image.type(KHRVulkanEnable.XR_TYPE_SWAPCHAIN_IMAGE_VULKAN_KHR);
        }
        int result = XR10.xrEnumerateSwapchainImages(swapchain, stack.ints(imageCount),
            XrSwapchainImageBaseHeader.create(images.address(), images.capacity()));
        checkResult(result, "xrEnumerateSwapchainImages");
        long[] handles = new long[imageCount];
        for (int i = 0; i < imageCount; i++) {
            handles[i] = images.get(i).image();
        }
        return handles;
    }

    static Struct checkVulkanGraphics(
        MemoryStack stack, XrInstance instance, long systemID) throws RenderConfigException
    {
        VulkanHelper vulkan = (VulkanHelper) GraphicsHelper.INSTANCE;
        XrGraphicsRequirementsVulkanKHR requirements = XrGraphicsRequirementsVulkanKHR.calloc(stack)
            .type(KHRVulkanEnable.XR_TYPE_GRAPHICS_REQUIREMENTS_VULKAN_KHR);
        checkResult(KHRVulkanEnable.xrGetVulkanGraphicsRequirementsKHR(instance, systemID, requirements),
            "xrGetVulkanGraphicsRequirementsKHR");

        String instanceExtensions = queryVulkanExtensions(stack, instance, systemID, true);
        String deviceExtensions = queryVulkanExtensions(stack, instance, systemID, false);
        VRSettings settings = ClientDataHolderVR.getInstance().vrSettings;
        if (!instanceExtensions.equals(settings.requiredVulkanInstanceExtensions) ||
            !deviceExtensions.equals(settings.requiredVulkanDeviceExtensions))
        {
            settings.requiredVulkanInstanceExtensions = instanceExtensions;
            settings.requiredVulkanDeviceExtensions = deviceExtensions;
            settings.saveOptions();
        }

        // OpenXR requires these to be enabled before xrCreateSession, not discovered afterward.
        // If this is the first launch, saving them above lets the Vulkan mixins enable them next time.
        vulkan.checkExtensionSupport(splitExtensions(instanceExtensions), splitExtensions(deviceExtensions));

        PointerBuffer runtimePhysicalDevice = stack.callocPointer(1);
        checkResult(KHRVulkanEnable.xrGetVulkanGraphicsDeviceKHR(
            instance, systemID, vulkan.getInstance(), runtimePhysicalDevice), "xrGetVulkanGraphicsDeviceKHR");
        if (runtimePhysicalDevice.get(0) != vulkan.getPhysicalDevice().address()) {
            throw new IllegalStateException("OpenXR and Minecraft selected different Vulkan physical devices");
        }

        return XrGraphicsBindingVulkanKHR.calloc(stack).set(
            KHRVulkanEnable.XR_TYPE_GRAPHICS_BINDING_VULKAN_KHR,
            NULL,
            vulkan.getInstance(),
            vulkan.getPhysicalDevice(),
            vulkan.getDevice(),
            vulkan.getQueueFamilyIndex(),
            0
        );
    }

    static String queryVulkanExtensions(
        MemoryStack stack, XrInstance instance, long systemID, boolean instanceExtensions)
    {
        IntBuffer length = stack.callocInt(1);
        int result = instanceExtensions
            ? KHRVulkanEnable.xrGetVulkanInstanceExtensionsKHR(instance, systemID, length, null)
            : KHRVulkanEnable.xrGetVulkanDeviceExtensionsKHR(instance, systemID, length, null);
        checkResult(result, instanceExtensions
            ? "xrGetVulkanInstanceExtensionsKHR"
            : "xrGetVulkanDeviceExtensionsKHR");
        if (length.get(0) <= 1) {
            return "";
        }

        ByteBuffer extensions = stack.calloc(length.get(0));
        result = instanceExtensions
            ? KHRVulkanEnable.xrGetVulkanInstanceExtensionsKHR(instance, systemID, length, extensions)
            : KHRVulkanEnable.xrGetVulkanDeviceExtensionsKHR(instance, systemID, length, extensions);
        checkResult(result, instanceExtensions
            ? "xrGetVulkanInstanceExtensionsKHR"
            : "xrGetVulkanDeviceExtensionsKHR");

        int byteCount = 0;
        while (byteCount < extensions.capacity() && extensions.get(byteCount) != 0) {
            byteCount++;
        }
        byte[] bytes = new byte[byteCount];
        extensions.get(0, bytes);
        return new String(bytes, StandardCharsets.UTF_8).trim();
    }

    static List<String> splitExtensions(String extensions) {
        if (extensions.isBlank()) {
            return List.of();
        }
        return Arrays.stream(extensions.trim().split("\\s+"))
            .filter(extension -> !extension.isBlank())
            .toList();
    }

    static void checkResult(int result, String operation) {
        if (result < XR10.XR_SUCCESS) {
            throw new IllegalStateException(operation + " failed with OpenXR result " + result);
        }
    }

    class Desktop implements DeviceCompat {
        @Override
        public long getPlatformInfo(MemoryStack stack) {
            return NULL;
        }

        @Override
        public void initOpenXRLoader(MemoryStack stack) {
            VRSettings.LOGGER.info("Platform: {}", System.getProperty("os.version"));
        }

        @Override
        public String getGraphicsExtension() {
            return KHROpenGLEnable.XR_KHR_OPENGL_ENABLE_EXTENSION_NAME;
        }

        @Override
        public long[] enumerateSwapchainImages(XrSwapchain swapchain, int imageCount, MemoryStack stack) {
            return enumerateOpenGLImages(
                swapchain, imageCount, stack, KHROpenGLEnable.XR_TYPE_SWAPCHAIN_IMAGE_OPENGL_KHR);
        }

        @Override
        public long[] getPreferredSwapchainFormats() {
            return new long[]{GL_SRGB8_ALPHA8, GL_SRGB8, GL_RGB10_A2, GL_RGBA16F, GL_RGB16F, GL_RGBA8, GL_RGBA8_SNORM};
        }

        @Override
        public Struct checkGraphics(
            MemoryStack stack, XrInstance instance, long systemID) throws RenderConfigException
        {
            XrGraphicsRequirementsOpenGLKHR graphicsRequirements = XrGraphicsRequirementsOpenGLKHR.calloc(stack)
                .type(KHROpenGLEnable.XR_TYPE_GRAPHICS_REQUIREMENTS_OPENGL_KHR);
            checkResult(KHROpenGLEnable.xrGetOpenGLGraphicsRequirementsKHR(
                instance, systemID, graphicsRequirements), "xrGetOpenGLGraphicsRequirementsKHR");
            Window window = Minecraft.getInstance().getWindow();
            long windowHandle = window.handle();
            if (Platform.getOSType() == Platform.WINDOWS) {
                return XrGraphicsBindingOpenGLWin32KHR.calloc(stack).set(
                    KHROpenGLEnable.XR_TYPE_GRAPHICS_BINDING_OPENGL_WIN32_KHR,
                    NULL,
                    User32.GetDC(GLFWNativeWin32.glfwGetWin32Window(windowHandle)),
                    GLFWNativeWGL.glfwGetWGLContext(windowHandle)
                );
            } else if (Platform.getOSType() == Platform.LINUX) {
                long xDisplay = GLFWNativeX11.glfwGetX11Display();
                long glXContext = GLFWNativeGLX.glfwGetGLXContext(windowHandle);
                long glXWindowHandle = GLFWNativeGLX.glfwGetGLXWindow(windowHandle);
                int fbXID = glXQueryDrawable(xDisplay, glXWindowHandle, GLX_FBCONFIG_ID);
                PointerBuffer fbConfigBuf = glXChooseFBConfig(xDisplay, X11.XDefaultScreen(xDisplay),
                    stackInts(GLX_FBCONFIG_ID, fbXID, 0));
                if (fbConfigBuf == null) {
                    throw new IllegalStateException("OpenXR framebuffer config was null");
                }
                long fbConfig = fbConfigBuf.get();
                return XrGraphicsBindingOpenGLXlibKHR.calloc(stack).set(
                    KHROpenGLEnable.XR_TYPE_GRAPHICS_BINDING_OPENGL_XLIB_KHR,
                    NULL,
                    xDisplay,
                    (int) Objects.requireNonNull(glXGetVisualFromFBConfig(xDisplay, fbConfig)).visualid(),
                    fbConfig,
                    glXWindowHandle,
                    glXContext
                );
            }
            throw new IllegalStateException("OpenXR desktop graphics binding is unsupported on this OS");
        }
    }

    class Mobile implements DeviceCompat {
        @Override
        public long getPlatformInfo(MemoryStack stack) {
            return XrInstanceCreateInfoAndroidKHR.calloc(stack).set(
                KHRAndroidCreateInstance.XR_TYPE_INSTANCE_CREATE_INFO_ANDROID_KHR,
                NULL,
                VLoader.getDalvikVM(),
                VLoader.getDalvikActivity()
            ).address();
        }

        @Override
        public void initOpenXRLoader(MemoryStack stack) {
            VLoader.setupAndroid();
            XrLoaderInitInfoAndroidKHR initInfo = XrLoaderInitInfoAndroidKHR.calloc(stack).set(
                KHRLoaderInitAndroid.XR_TYPE_LOADER_INIT_INFO_ANDROID_KHR,
                NULL,
                VLoader.getDalvikVM(),
                VLoader.getDalvikActivity()
            );
            checkResult(KHRLoaderInit.xrInitializeLoaderKHR(
                XrLoaderInitInfoBaseHeaderKHR.create(initInfo.address())), "xrInitializeLoaderKHR");
        }

        @Override
        public String getGraphicsExtension() {
            return KHROpenGLESEnable.XR_KHR_OPENGL_ES_ENABLE_EXTENSION_NAME;
        }

        @Override
        public List<String> getRequiredInstanceExtensions() {
            return List.of(
                getGraphicsExtension(),
                KHRAndroidCreateInstance.XR_KHR_ANDROID_CREATE_INSTANCE_EXTENSION_NAME
            );
        }

        @Override
        public long[] enumerateSwapchainImages(XrSwapchain swapchain, int imageCount, MemoryStack stack) {
            return enumerateOpenGLImages(
                swapchain, imageCount, stack, KHROpenGLESEnable.XR_TYPE_SWAPCHAIN_IMAGE_OPENGL_ES_KHR);
        }

        @Override
        public long[] getPreferredSwapchainFormats() {
            return new long[]{GL_SRGB8_ALPHA8, GL_RGB10_A2, GL_RGBA16F, GL_RGBA8};
        }

        @Override
        public Struct checkGraphics(
            MemoryStack stack, XrInstance instance, long systemID) throws RenderConfigException
        {
            XrGraphicsRequirementsOpenGLESKHR graphicsRequirements = XrGraphicsRequirementsOpenGLESKHR.calloc(stack)
                .type(KHROpenGLESEnable.XR_TYPE_GRAPHICS_REQUIREMENTS_OPENGL_ES_KHR);
            checkResult(KHROpenGLESEnable.xrGetOpenGLESGraphicsRequirementsKHR(
                instance, systemID, graphicsRequirements), "xrGetOpenGLESGraphicsRequirementsKHR");
            return XrGraphicsBindingOpenGLESAndroidKHR.calloc(stack).set(
                KHROpenGLESEnable.XR_TYPE_GRAPHICS_BINDING_OPENGL_ES_ANDROID_KHR,
                NULL,
                VLoader.getEGLDisplay(),
                VLoader.getEGLConfig(),
                VLoader.getEGLContext()
            );
        }
    }

    class DesktopVulkan extends Desktop {
        @Override
        public String getGraphicsExtension() {
            return KHRVulkanEnable.XR_KHR_VULKAN_ENABLE_EXTENSION_NAME;
        }

        @Override
        public long[] enumerateSwapchainImages(XrSwapchain swapchain, int imageCount, MemoryStack stack) {
            return enumerateVulkanImages(swapchain, imageCount, stack);
        }

        @Override
        public long[] getPreferredSwapchainFormats() {
            return vulkanFormats();
        }

        @Override
        public long getSwapchainUsageFlags() {
            return XR10.XR_SWAPCHAIN_USAGE_COLOR_ATTACHMENT_BIT | XR10.XR_SWAPCHAIN_USAGE_TRANSFER_DST_BIT;
        }

        @Override
        public boolean isVulkan() {
            return true;
        }

        @Override
        public Struct checkGraphics(
            MemoryStack stack, XrInstance instance, long systemID) throws RenderConfigException
        {
            return checkVulkanGraphics(stack, instance, systemID);
        }
    }

    class MobileVulkan extends Mobile {
        @Override
        public String getGraphicsExtension() {
            return KHRVulkanEnable.XR_KHR_VULKAN_ENABLE_EXTENSION_NAME;
        }

        @Override
        public long[] enumerateSwapchainImages(XrSwapchain swapchain, int imageCount, MemoryStack stack) {
            return enumerateVulkanImages(swapchain, imageCount, stack);
        }

        @Override
        public long[] getPreferredSwapchainFormats() {
            return mobileVulkanFormats();
        }

        @Override
        public long getSwapchainUsageFlags() {
            return XR10.XR_SWAPCHAIN_USAGE_COLOR_ATTACHMENT_BIT | XR10.XR_SWAPCHAIN_USAGE_TRANSFER_DST_BIT;
        }

        @Override
        public boolean isVulkan() {
            return true;
        }

        @Override
        public Struct checkGraphics(
            MemoryStack stack, XrInstance instance, long systemID) throws RenderConfigException
        {
            return checkVulkanGraphics(stack, instance, systemID);
        }
    }

    static long[] vulkanFormats() {
        return new long[]{
            VK10.VK_FORMAT_R8G8B8A8_SRGB,
            VK10.VK_FORMAT_B8G8R8A8_SRGB,
            VK10.VK_FORMAT_R8G8B8A8_UNORM,
            VK10.VK_FORMAT_B8G8R8A8_UNORM
        };
    }

    /**
     * Android/Quest receives the Minecraft eye image from an RGBA8_UNORM render target and copies it into the
     * OpenXR swapchain with a Vulkan blit. Selecting an sRGB destination for that blit makes Vulkan interpret the
     * UNORM source values as linear and sRGB-encode them on write, even though Minecraft's final eye image is
     * already display/gamma encoded. The extra encode lifts midtones and produces the characteristic washed-out
     * Quest image.
     *
     * Prefer a matching UNORM swapchain on mobile so the compositor receives the same encoded values Minecraft
     * produced. Keep sRGB formats as fallbacks for runtimes that do not expose UNORM swapchains.
     */
    static long[] mobileVulkanFormats() {
        return new long[]{
            VK10.VK_FORMAT_R8G8B8A8_UNORM,
            VK10.VK_FORMAT_B8G8R8A8_UNORM,
            VK10.VK_FORMAT_R8G8B8A8_SRGB,
            VK10.VK_FORMAT_B8G8R8A8_SRGB
        };
    }
}
