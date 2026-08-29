package org.vivecraft.client_vr.provider.openxr;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.network.chat.Component;
import org.joml.Matrix4f;
import org.joml.Vector2i;
import org.joml.Vector2ic;
import org.lwjgl.PointerBuffer;
import org.lwjgl.openxr.*;
import org.lwjgl.system.MemoryStack;
import org.vivecraft.client_vr.ClientDataHolderVR;
import org.vivecraft.client_vr.VRTextureTarget;
import org.vivecraft.client_vr.provider.VRRenderer;
import org.vivecraft.client_vr.render.RenderConfigException;
import org.vivecraft.client_vr.render.helpers.graphics.GraphicsHelper;
import org.vivecraft.client_vr.render.helpers.graphics.VulkanHelper;
import org.vivecraft.client_vr.settings.VRSettings;

import java.io.IOException;
import java.nio.IntBuffer;

public class OpenXRStereoRenderer extends VRRenderer {
    private final MCOpenXR openxr;
    private final int[] swapIndex = new int[]{0, 0};
    private final boolean[] imageAcquired = new boolean[2];
    private final XrCompositionLayerProjectionView.Buffer projectionLayerViews;
    private long[][] swapchainImages = new long[2][];
    private boolean recalculateProjectionMatrix = true;

    public OpenXRStereoRenderer(MCOpenXR vr) {
        super(vr);
        this.openxr = vr;
        this.projectionLayerViews = XrCompositionLayerProjectionView.calloc(2);
    }

    @Override
    public void checkCapabilities() throws RenderConfigException {
        super.checkCapabilities();
        if (GraphicsHelper.INSTANCE instanceof VulkanHelper vulkan) {
            VRSettings settings = ClientDataHolderVR.getInstance().vrSettings;
            vulkan.checkExtensionSupport(
                DeviceCompat.splitExtensions(settings.requiredVulkanInstanceExtensions),
                DeviceCompat.splitExtensions(settings.requiredVulkanDeviceExtensions));
        }
    }

    @Override
    public void createRenderTexture(int width, int height) {
        for (int eye = 0; eye < 2; eye++) {
            this.framebufferEye[eye] = VRTextureTarget.builder((eye == 0 ? "L" : "R") + " Eye")
                .withSize(width, height)
                .withFormat(GpuFormat.RGBA8_UNORM)
                .build();
            VRSettings.LOGGER.info("Vivecraft: {}", this.framebufferEye[eye]);
            GraphicsHelper.INSTANCE.checkError((eye == 0 ? "Left" : "Right") + " Eye framebuffer setup");
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            for (int eye = 0; eye < 2; eye++) {
                IntBuffer count = stack.callocInt(1);
                int result = XR10.xrEnumerateSwapchainImages(this.openxr.swapchain[eye], count, null);
                DeviceCompat.checkResult(result, "xrEnumerateSwapchainImages");
                this.swapchainImages[eye] = MCOpenXR.device.enumerateSwapchainImages(
                    this.openxr.swapchain[eye], count.get(0), stack);
            }
        }
        this.lastError = GraphicsHelper.INSTANCE.checkError("create OpenXR textures");
    }

    @Override
    public void setupRenderConfiguration() throws IOException, RenderConfigException {
        super.setupRenderConfiguration();
        if (this.openxr.frameBegun && this.openxr.shouldRender && !this.imageAcquired[0]) {
            this.acquireSwapchainImages();
        }
    }

    private void acquireSwapchainImages() throws RenderConfigException {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            for (int eye = 0; eye < 2; eye++) {
                IntBuffer index = stack.callocInt(1);
                int result = XR10.xrAcquireSwapchainImage(
                    this.openxr.swapchain[eye],
                    XrSwapchainImageAcquireInfo.calloc(stack).type(XR10.XR_TYPE_SWAPCHAIN_IMAGE_ACQUIRE_INFO),
                    index);
                requireSuccess(result, "xrAcquireSwapchainImage");

                result = XR10.xrWaitSwapchainImage(
                    this.openxr.swapchain[eye],
                    XrSwapchainImageWaitInfo.calloc(stack)
                        .type(XR10.XR_TYPE_SWAPCHAIN_IMAGE_WAIT_INFO)
                        .timeout(XR10.XR_INFINITE_DURATION));
                requireSuccess(result, "xrWaitSwapchainImage");

                this.swapIndex[eye] = index.get(0);
                this.imageAcquired[eye] = true;
                XrCompositionLayerProjectionView projectionView = this.projectionLayerViews.get(eye)
                    .type(XR10.XR_TYPE_COMPOSITION_LAYER_PROJECTION_VIEW)
                    .pose(this.openxr.viewBuffer.get(eye).pose())
                    .fov(this.openxr.viewBuffer.get(eye).fov());
                projectionView.subImage()
                    .swapchain(this.openxr.swapchain[eye])
                    .imageArrayIndex(0);
                projectionView.subImage().imageRect().offset().set(0, 0);
                projectionView.subImage().imageRect().extent().set(this.openxr.width, this.openxr.height);
            }
            this.recalculateProjectionMatrix = true;
        }
    }

    @Override
    public Matrix4f getCachedProjectionMatrix(int eyeType, float nearClip, float farClip) {
        if (this.recalculateProjectionMatrix || this.lastFarClip != farClip) {
            this.eyeProj[0] = this.getProjectionMatrix(0, nearClip, farClip);
            this.eyeProj[1] = this.getProjectionMatrix(1, nearClip, farClip);
            this.lastFarClip = farClip;
            this.recalculateProjectionMatrix = false;
        }
        return this.eyeProj[eyeType];
    }

    @Override
    protected Matrix4f getProjectionMatrix(int eyeType, float nearClip, float farClip) {
        XrFovf fov = this.openxr.viewBuffer.get(eyeType).fov();
        return new Matrix4f().frustum(
            (float) Math.tan(fov.angleLeft()) * nearClip,
            (float) Math.tan(fov.angleRight()) * nearClip,
            (float) Math.tan(fov.angleDown()) * nearClip,
            (float) Math.tan(fov.angleUp()) * nearClip,
            nearClip,
            farClip,
            RenderSystem.getDevice().getDeviceInfo().isZZeroToOne());
    }

    @Override
    public void endFrame() throws RenderConfigException {
        if (!this.openxr.frameBegun) {
            return;
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer layers = null;
            if (this.openxr.shouldRender && this.imageAcquired[0] && this.imageAcquired[1]) {
                for (int eye = 0; eye < 2; eye++) {
                    if (GraphicsHelper.INSTANCE instanceof VulkanHelper vulkanHelper) {
                        vulkanHelper.copyToExternalImage(
                            this.framebufferEye[eye].getColorTexture(),
                            this.swapchainImages[eye][this.swapIndex[eye]],
                            this.openxr.width,
                            this.openxr.height,
                            this.openxr.swapchainFormat);
                    } else {
                        GraphicsHelper.INSTANCE.copyToExternalImage(
                            this.framebufferEye[eye].getColorTexture(),
                            this.swapchainImages[eye][this.swapIndex[eye]],
                            this.openxr.width,
                            this.openxr.height);
                    }
                }
                GraphicsHelper.INSTANCE.flush();

                for (int eye = 0; eye < 2; eye++) {
                    int result = XR10.xrReleaseSwapchainImage(
                        this.openxr.swapchain[eye],
                        XrSwapchainImageReleaseInfo.calloc(stack)
                            .type(XR10.XR_TYPE_SWAPCHAIN_IMAGE_RELEASE_INFO));
                    requireSuccess(result, "xrReleaseSwapchainImage");
                    this.imageAcquired[eye] = false;
                }

                XrCompositionLayerProjection projectionLayer = XrCompositionLayerProjection.calloc(stack)
                    .type(XR10.XR_TYPE_COMPOSITION_LAYER_PROJECTION)
                    .space(this.openxr.xrAppSpace)
                    .views(this.projectionLayerViews);
                layers = stack.callocPointer(1).put(0, projectionLayer.address());
            }

            int result = XR10.xrEndFrame(
                this.openxr.session,
                XrFrameEndInfo.calloc(stack)
                    .type(XR10.XR_TYPE_FRAME_END_INFO)
                    .displayTime(this.openxr.time)
                    .environmentBlendMode(XR10.XR_ENVIRONMENT_BLEND_MODE_OPAQUE)
                    .layers(layers));
            requireSuccess(result, "xrEndFrame");
        } finally {
            this.openxr.frameBegun = false;
            this.imageAcquired[0] = false;
            this.imageAcquired[1] = false;
        }
    }

    private static void requireSuccess(int result, String operation) throws RenderConfigException {
        if (result < XR10.XR_SUCCESS) {
            throw new RenderConfigException(
                Component.literal("OpenXR error"),
                Component.literal(operation + " failed with result " + result));
        }
    }

    @Override
    public boolean providesStencilMask() {
        return false;
    }

    @Override
    public String getName() {
        return MCOpenXR.device.isVulkan() ? "OpenXR Vulkan" : "OpenXR OpenGL ES";
    }

    @Override
    public Vector2ic getRenderTextureSizes() {
        return new Vector2i(this.openxr.width, this.openxr.height);
    }

    @Override
    public void destroy() {
        super.destroy();
        this.projectionLayerViews.close();
        this.swapchainImages = new long[2][];
    }
}
