package org.vivecraft.client_vr.provider.openxr;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.util.profiling.Profiler;
import org.apache.commons.lang3.tuple.Pair;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Quaternionf;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.openxr.*;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.vivecraft.api.client.data.RenderPass;
import org.vivecraft.client.VivecraftVRMod;
import org.vivecraft.client_vr.ClientDataHolderVR;
import org.vivecraft.client_vr.VRState;
import org.vivecraft.client_vr.gameplay.screenhandlers.GuiHandler;
import org.vivecraft.client_vr.gameplay.screenhandlers.KeyboardHandler;
import org.vivecraft.client_vr.gameplay.screenhandlers.RadialHandler;
import org.vivecraft.client_vr.provider.ControllerType;
import org.vivecraft.client_vr.provider.InputSimulator;
import org.vivecraft.client_vr.provider.MCVR;
import org.vivecraft.client_vr.provider.VRRenderer;
import org.vivecraft.client_vr.provider.openvr_lwjgl.VRInputAction;
import org.vivecraft.client_vr.provider.openvr_lwjgl.control.TrackpadSwipeSampler;
import org.vivecraft.client_vr.provider.openvr_lwjgl.control.VRInputActionSet;
import org.vivecraft.client_vr.render.RenderConfigException;
import org.vivecraft.client_vr.render.helpers.graphics.GraphicsHelper;
import org.vivecraft.client_vr.render.helpers.graphics.OpenGLHelper;
import org.vivecraft.client_vr.settings.VRSettings;
import org.vivecraft.common.utils.MathUtils;

import javax.annotation.Nullable;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.*;

import static org.lwjgl.opengl.GL30.GL_FRAMEBUFFER_SRGB;
import static org.lwjgl.system.MemoryStack.*;
import static org.lwjgl.system.MemoryUtil.*;

public class MCOpenXR extends MCVR {

    private static MCOpenXR OME;
    public XrInstance instance;
    public XrSession session;
    public XrSpace xrAppSpace;
    public XrSpace xrViewSpace;
    public XrSwapchain[] swapchain;
    public long swapchainFormat;
    public final XrEventDataBuffer eventDataBuffer = XrEventDataBuffer.calloc();
    public long time;
    private boolean tried;
    private long systemID;
    public XrView.Buffer viewBuffer;
    public int width;
    public int height;
    // TODO either move to MCVR, Or make special for OpenXR holding the instance itself.
    private final Map<VRInputActionSet, Long> actionSetHandles = new EnumMap<>(VRInputActionSet.class);
    // TODO Move to MCVR
    private XrActiveActionSet.Buffer activeActionSetsBuffer;
    private final Map<VRInputActionSet, Set<VRInputAction>> unpressedSetKeys = new EnumMap<>(VRInputActionSet.class);
    private List<VRInputActionSet> activeActionSets = new ArrayList<>();
    private final Map<String, TrackpadSwipeSampler> trackpadSwipeSamplers = new HashMap<>();
    private final Map<Long, long[]> actionOrigins = new HashMap<>();
    private boolean isActive;
    private boolean sessionRunning;
    private int sessionState = XR10.XR_SESSION_STATE_UNKNOWN;
    private final HashMap<String, Long> paths = new HashMap<>();
    private final long[] grip = new long[2];
    private final long[] aim = new long[2];
    private final XrSpace[] gripSpace = new XrSpace[2];
    private final XrSpace[] aimSpace = new XrSpace[2];
    public static final XrPosef POSE_IDENTITY = XrPosef.calloc().set(
        XrQuaternionf.calloc().set(0, 0, 0, 1),
        XrVector3f.calloc()
    );
    public boolean shouldRender = true;
    public long[] haptics = new long[2];
    public String systemName;
    public boolean frameBegun;
    private boolean inputInitialized;
    private final Set<String> enabledInstanceExtensions = new HashSet<>();
    private int appSpaceType = XR10.XR_REFERENCE_SPACE_TYPE_STAGE;
    protected static final DeviceCompat device = DeviceCompat.detectDevice();

    public MCOpenXR(Minecraft mc, ClientDataHolderVR dh) {
        super(mc, dh, VivecraftVRMod.INSTANCE);
        OME = this;
        this.hapticScheduler = new OpenXRHapticScheduler();
        for (VRInputActionSet set : VRInputActionSet.values()) {
            this.unpressedSetKeys.put(set, new HashSet<>());
        }
        if (GraphicsHelper.INSTANCE instanceof OpenGLHelper) {
            GL11.glDisable(GL_FRAMEBUFFER_SRGB);
        }
    }

    @Override
    public String getName() {
        return "OpenXR";
    }

    @Override
    public void processInputs() {
        if (this.dh.vrSettings.seated || this.dh.viewOnly || !this.inputInitialized || !this.sessionRunning) {
            this.ignorePressesNextFrame = false;
            return;
        }

        for (VRInputAction action : this.inputActions.values()) {
            if (action.isHanded()) {
                for (ControllerType controller : ControllerType.values()) {
                    action.setCurrentHand(controller);
                    this.processInputAction(action);
                }
            } else {
                this.processInputAction(action);
            }
        }

        this.processScrollInput(GuiHandler.KEY_SCROLL_AXIS,
            () -> InputSimulator.scrollMouse(0.0D, 1.0D),
            () -> InputSimulator.scrollMouse(0.0D, -1.0D));
        this.processScrollInput(VivecraftVRMod.INSTANCE.keyHotbarScroll,
            () -> this.changeHotbar(-1),
            () -> this.changeHotbar(1));
        this.processSwipeInput(VivecraftVRMod.INSTANCE.keyHotbarSwipeX,
            () -> this.changeHotbar(1),
            () -> this.changeHotbar(-1), null, null);
        this.processSwipeInput(VivecraftVRMod.INSTANCE.keyHotbarSwipeY, null, null,
            () -> this.changeHotbar(-1),
            () -> this.changeHotbar(1));

        this.ignorePressesNextFrame = false;
    }

    private void processInputAction(VRInputAction action) {
        if (action.isActive() && action.isEnabledRaw() &&
            (!ClientDataHolderVR.getInstance().vrSettings.ingameBindingsInGui ||
                !(action.actionSet == VRInputActionSet.INGAME &&
                    action.keyBinding.key == InputConstants.Type.MOUSE.getOrCreate(GLFW.GLFW_MOUSE_BUTTON_LEFT) &&
                    this.mc.gui.screen() != null)))
        {
            if (action.isButtonChanged()) {
                if (action.isButtonPressed() && action.isEnabled()) {
                    if (!this.ignorePressesNextFrame || this.canActionBeRepressed(action)) {
                        this.pressAction(action);
                    }
                } else {
                    this.unpressAction(action);
                }
            } else if (action.isButtonPressed() && action.isEnabled() && !action.keyBinding.isDown() &&
                this.canActionBeRepressed(action))
            {
                this.pressAction(action);
            }
        } else if (this.checkIfNotMovement(action)) {
            this.unpressAction(action);
        }
    }

    private boolean canActionBeRepressed(VRInputAction action) {
        return action.actionSet == VRInputActionSet.INGAME && this.unpressedSetKeys.get(action.actionSet).contains(action);
    }

    private void pressAction(VRInputAction action) {
        action.pressBinding();
        this.unpressedSetKeys.get(action.actionSet).remove(action);
    }

    private void unpressAction(VRInputAction action) {
        if (!this.activeActionSets.contains(action.actionSet) && action.isButtonChanged()) {
            this.unpressedSetKeys.get(action.actionSet).add(action);
        }
        action.unpressBinding();
    }

    private boolean checkIfNotMovement(VRInputAction action) {
        return action.keyBinding != this.mc.options.keyLeft &&
            action.keyBinding != this.mc.options.keyRight &&
            action.keyBinding != this.mc.options.keyUp &&
            action.keyBinding != this.mc.options.keyDown || !this.isMovement;
    }

    private void processScrollInput(KeyMapping keyMapping, Runnable upCallback, Runnable downCallback) {
        VRInputAction action = this.getInputAction(keyMapping);
        if (action.isEnabled() && action.getLastOrigin() != XR10.XR_NULL_PATH) {
            float value = action.getAxis2D(false).y();
            if (value > 0.0F) {
                upCallback.run();
            } else if (value < 0.0F) {
                downCallback.run();
            }
        }
    }

    private void processSwipeInput(
        KeyMapping keyMapping, Runnable leftCallback, Runnable rightCallback, Runnable upCallback, Runnable downCallback)
    {
        VRInputAction action = this.getInputAction(keyMapping);
        if (action.isEnabled() && action.getLastOrigin() != XR10.XR_NULL_PATH) {
            ControllerType controller = this.findActiveBindingControllerType(keyMapping);
            if (controller != null) {
                TrackpadSwipeSampler sampler = this.trackpadSwipeSamplers.computeIfAbsent(
                    keyMapping.getName(), ignored -> new TrackpadSwipeSampler());
                sampler.update(controller, action.getAxis2D(false));

                if (sampler.isSwipedUp() && upCallback != null) {
                    this.triggerHapticPulse(controller, 0.001F, 400.0F, 0.5F);
                    upCallback.run();
                }
                if (sampler.isSwipedDown() && downCallback != null) {
                    this.triggerHapticPulse(controller, 0.001F, 400.0F, 0.5F);
                    downCallback.run();
                }
                if (sampler.isSwipedLeft() && leftCallback != null) {
                    this.triggerHapticPulse(controller, 0.001F, 400.0F, 0.5F);
                    leftCallback.run();
                }
                if (sampler.isSwipedRight() && rightCallback != null) {
                    this.triggerHapticPulse(controller, 0.001F, 400.0F, 0.5F);
                    rightCallback.run();
                }
            }
        }

    }

    @Override
    public void destroy() {
        int error;
        // Not sure if we need the action sets one here, as we are shutting down
        for (Long inputActionSet : this.actionSetHandles.values()) {
            error = XR10.xrDestroyActionSet(new XrActionSet(inputActionSet, this.instance));
            logError(error, "xrDestroyActionSet", "");
        }
        if (this.swapchain != null) {
            for (XrSwapchain xrSwapchain : this.swapchain) {
                if (xrSwapchain != null) {
                    error = XR10.xrDestroySwapchain(xrSwapchain);
                    logError(error, "xrDestroySwapchain", "");
                }
            }
        }
        if (this.viewBuffer != null) {
            this.viewBuffer.close();
        }
        if (this.xrAppSpace != null) {
            error = XR10.xrDestroySpace(this.xrAppSpace);
            logError(error, "xrDestroySpace", "xrAppSpace");
        }
        if (this.xrViewSpace != null) {
            error = XR10.xrDestroySpace(this.xrViewSpace);
            logError(error, "xrDestroySpace", "xrViewSpace");
        }
        if (this.session != null) {
            error = XR10.xrDestroySession(this.session);
            logError(error, "xrDestroySession", "");
        }
        if (this.instance != null) {
            error = XR10.xrDestroyInstance(this.instance);
            logError(error, "xrDestroyInstance", "");
        }

        if (GraphicsHelper.INSTANCE instanceof OpenGLHelper) {
            GL11.glEnable(GL_FRAMEBUFFER_SRGB);
        }
        this.eventDataBuffer.close();
    }

    @Override
    protected ControllerType findActiveBindingControllerType(KeyMapping keyMapping) {
        if (!this.inputInitialized) {
            return null;
        }
        return this.getOriginControllerType(this.getInputAction(keyMapping).getLastOrigin());
    }

    @Override
    public void poll(long frameIndex) {
        if (this.initialized) {
            Profiler.get().push("events");
            this.pollVREvents();
            Profiler.get().pop();
            Profiler.get().popPush("updatePose/Vsync");
            this.updatePose();
            Profiler.get().popPush("processInputs");
            this.processInputs();
            Profiler.get().popPush("hmdSampling");
            this.hmdSampling();
            Profiler.get().pop();
        }
    }

    private void updatePose() {
        if (this.mc == null || !this.sessionRunning) {
            return;
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            XrFrameState frameState = XrFrameState.calloc(stack).type(XR10.XR_TYPE_FRAME_STATE);

            int error = XR10.xrWaitFrame(
                this.session,
                XrFrameWaitInfo.calloc(stack).type(XR10.XR_TYPE_FRAME_WAIT_INFO),
                frameState);
            logError(error, "xrWaitFrame", "");
            if (error < XR10.XR_SUCCESS) {
                this.frameBegun = false;
                this.headIsTracking = false;
                this.controllerTracking[RIGHT_CONTROLLER] = false;
                this.controllerTracking[LEFT_CONTROLLER] = false;
                if (error == XR10.XR_ERROR_SESSION_NOT_RUNNING) {
                    this.sessionRunning = false;
                    this.isActive = false;
                }
                return;
            }

            this.time = frameState.predictedDisplayTime();
            this.shouldRender = frameState.shouldRender();

            error = XR10.xrBeginFrame(
                this.session,
                XrFrameBeginInfo.calloc(stack).type(XR10.XR_TYPE_FRAME_BEGIN_INFO));
            logError(error, "xrBeginFrame", "");
            this.frameBegun = error >= XR10.XR_SUCCESS;
            if (!this.frameBegun) {
                this.headIsTracking = false;
                this.controllerTracking[RIGHT_CONTROLLER] = false;
                this.controllerTracking[LEFT_CONTROLLER] = false;
                return;
            }


            XrViewState viewState = XrViewState.calloc(stack).type(XR10.XR_TYPE_VIEW_STATE);
            IntBuffer intBuf = stack.callocInt(1);

            XrViewLocateInfo viewLocateInfo = XrViewLocateInfo.calloc(stack);
            viewLocateInfo.set(XR10.XR_TYPE_VIEW_LOCATE_INFO,
                0,
                XR10.XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO,
                frameState.predictedDisplayTime(),
                this.xrAppSpace
            );

            error = XR10.xrLocateViews(this.session, viewLocateInfo, viewState, intBuf, this.viewBuffer);
            logError(error, "xrLocateViews", "");
            if (error < XR10.XR_SUCCESS) {
                this.headIsTracking = false;
                this.controllerTracking[RIGHT_CONTROLLER] = false;
                this.controllerTracking[LEFT_CONTROLLER] = false;
                return;
            }

            XrSpaceLocation space_location = XrSpaceLocation.calloc(stack).type(XR10.XR_TYPE_SPACE_LOCATION);
            long validPoseFlags = XR10.XR_SPACE_LOCATION_ORIENTATION_VALID_BIT |
                XR10.XR_SPACE_LOCATION_POSITION_VALID_BIT;

            // HMD pose
            error = XR10.xrLocateSpace(this.xrViewSpace, this.xrAppSpace, this.time, space_location);
            logError(error, "xrLocateSpace", "xrViewSpace");
            if (error >= XR10.XR_SUCCESS && (space_location.locationFlags() & validPoseFlags) == validPoseFlags) {
                OpenXRUtil.openXRPoseToMarix(space_location.pose(), this.hmdPose);
                this.headIsTracking = true;
            } else {
                this.headIsTracking = false;
                this.hmdPose.identity();
                this.hmdPose.m31(1.6F);
            }

            // xrLocateViews returns eye poses in xrAppSpace, while Vivecraft expects eye matrices
            // relative to the HMD. Storing the absolute poses here makes head motion get applied
            // twice and produces the "swimming"/over-rotating view seen on Quest.
            long viewStateFlags = viewState.viewStateFlags();
            if ((viewStateFlags & validPoseFlags) == validPoseFlags && intBuf.get(0) >= 2) {
                this.updateHeadAndEyePosesFromViews();
                this.headIsTracking = true;
            }

            if (this.inputInitialized) {
                Profiler.get().push("updateActionState");

                if (this.updateActiveActionSets()) {
                    XrActionsSyncInfo syncInfo = XrActionsSyncInfo.calloc(stack)
                        .type(XR10.XR_TYPE_ACTIONS_SYNC_INFO)
                        .activeActionSets(this.activeActionSetsBuffer);
                    error = XR10.xrSyncActions(this.session, syncInfo);
                    logError(error, "xrSyncActions", "");
                }

                this.inputActions.values().forEach(this::readNewData);

                //TODO Not needed it seems? Poses come from the action space
                XrActionSet actionSet = new XrActionSet(this.actionSetHandles.get(VRInputActionSet.GLOBAL),
                    this.instance);
                boolean rightGripPoseActive = this.readPoseData(
                    this.grip[RIGHT_CONTROLLER], actionSet, ControllerType.RIGHT);
                boolean leftGripPoseActive = this.readPoseData(
                    this.grip[LEFT_CONTROLLER], actionSet, ControllerType.LEFT);
                boolean rightPoseActive = this.readPoseData(
                    this.aim[RIGHT_CONTROLLER], actionSet, ControllerType.RIGHT);
                boolean leftPoseActive = this.readPoseData(
                    this.aim[LEFT_CONTROLLER], actionSet, ControllerType.LEFT);

                Profiler.get().pop();

                boolean reverseHands = this.dh.vrSettings.reverseHands;
                XrSpace rightGripSpace = this.gripSpace[reverseHands ? LEFT_CONTROLLER : RIGHT_CONTROLLER];
                XrSpace leftGripSpace = this.gripSpace[reverseHands ? RIGHT_CONTROLLER : LEFT_CONTROLLER];
                XrSpace rightAimSpace = this.aimSpace[reverseHands ? LEFT_CONTROLLER : RIGHT_CONTROLLER];
                XrSpace leftAimSpace = this.aimSpace[reverseHands ? RIGHT_CONTROLLER : LEFT_CONTROLLER];
                boolean rightControllerGripActive = reverseHands ? leftGripPoseActive : rightGripPoseActive;
                boolean leftControllerGripActive = reverseHands ? rightGripPoseActive : leftGripPoseActive;
                boolean rightControllerPoseActive = reverseHands ? leftPoseActive : rightPoseActive;
                boolean leftControllerPoseActive = reverseHands ? rightPoseActive : leftPoseActive;

                // Controller aim and grip poses
                error = XR10.xrLocateSpace(rightGripSpace, this.xrAppSpace, this.time,
                    space_location);
                logError(error, "xrLocateSpace", "gripSpace[0]");
                if (rightControllerGripActive && error >= XR10.XR_SUCCESS &&
                    (space_location.locationFlags() & XR10.XR_SPACE_LOCATION_ORIENTATION_VALID_BIT) != 0)
                {
                    OpenXRUtil.openXRPoseToMarix(space_location.pose().orientation(),
                        this.handRotation[RIGHT_CONTROLLER]);
                }

                error = XR10.xrLocateSpace(leftGripSpace, this.xrAppSpace, this.time, space_location);
                logError(error, "xrLocateSpace", "gripSpace[1]");
                if (leftControllerGripActive && error >= XR10.XR_SUCCESS &&
                    (space_location.locationFlags() & XR10.XR_SPACE_LOCATION_ORIENTATION_VALID_BIT) != 0)
                {
                    OpenXRUtil.openXRPoseToMarix(space_location.pose().orientation(),
                        this.handRotation[LEFT_CONTROLLER]);
                }

                error = XR10.xrLocateSpace(rightAimSpace, this.xrAppSpace, this.time, space_location);
                logError(error, "xrLocateSpace", "aimSpace[0]");
                if (rightControllerPoseActive && error >= XR10.XR_SUCCESS &&
                    (space_location.locationFlags() & validPoseFlags) == validPoseFlags)
                {
                    OpenXRUtil.openXRPoseToMarix(space_location.pose(), this.controllerPose[RIGHT_CONTROLLER]);
                    OpenXRUtil.openXRPoseToMarix(space_location.pose().orientation(),
                        this.controllerRotation[RIGHT_CONTROLLER]);
                    this.controllerTracking[RIGHT_CONTROLLER] = true;
                } else {
                    this.controllerTracking[RIGHT_CONTROLLER] = false;
                }

                error = XR10.xrLocateSpace(leftAimSpace, this.xrAppSpace, this.time, space_location);
                logError(error, "xrLocateSpace", "aimSpace[1]");
                if (leftControllerPoseActive && error >= XR10.XR_SUCCESS &&
                    (space_location.locationFlags() & validPoseFlags) == validPoseFlags)
                {
                    OpenXRUtil.openXRPoseToMarix(space_location.pose(), this.controllerPose[LEFT_CONTROLLER]);
                    OpenXRUtil.openXRPoseToMarix(space_location.pose().orientation(),
                        this.controllerRotation[LEFT_CONTROLLER]);
                    this.controllerTracking[LEFT_CONTROLLER] = true;
                } else {
                    this.controllerTracking[LEFT_CONTROLLER] = false;
                }
            }

            if(this.controllerTracking[RIGHT_CONTROLLER]) {
                Matrix4fc tip = this.controllerRotation[RIGHT_CONTROLLER];
                Matrix4fc hand = this.handRotation[RIGHT_CONTROLLER];

                Vector3f tipVec = tip.transformDirection(MathUtils.BACK, new Vector3f());
                Vector3f handVec = hand.transformDirection(MathUtils.BACK, new Vector3f());

                float dot = Math.abs(tipVec.dot(handVec));

                float angleRad = (float) Math.acos(dot);
                float angleDeg = Mth.RAD_TO_DEG * angleRad;

                this.gunStyle = angleDeg > 10.0F;
                this.gunAngle = angleDeg;
            }

            this.updateAim();
        }
    }

    private void updateHeadAndEyePosesFromViews() {
        XrPosef leftPose = this.viewBuffer.get(0).pose();
        XrPosef rightPose = this.viewBuffer.get(1).pose();

        XrQuaternionf orientation = leftPose.orientation();
        this.hmdPose
            .set(new Quaternionf(orientation.x(), orientation.y(), orientation.z(), orientation.w()))
            .setTranslation(
                (leftPose.position$().x() + rightPose.position$().x()) * 0.5F,
                (leftPose.position$().y() + rightPose.position$().y()) * 0.5F,
                (leftPose.position$().z() + rightPose.position$().z()) * 0.5F)
            .m33(1.0F);

        Matrix4f inverseHead = new Matrix4f(this.hmdPose).invert();
        Matrix4f absoluteEye = new Matrix4f();
        OpenXRUtil.openXRPoseToMarix(leftPose, absoluteEye);
        inverseHead.mul(absoluteEye, this.hmdPoseLeftEye);
        OpenXRUtil.openXRPoseToMarix(rightPose, absoluteEye);
        inverseHead.mul(absoluteEye, this.hmdPoseRightEye);
    }

    public void readNewData(VRInputAction action) {
        switch (action.type) {
            case "boolean" -> {
                if (action.isHanded()) {
                    for (ControllerType controllertype1 : ControllerType.values()) {
                        this.readBoolean(action, controllertype1);
                    }
                } else {
                    this.readBoolean(action, null);
                }
            }

            case "vector1" -> {
                if (action.isHanded()) {
                    for (ControllerType controllertype : ControllerType.values()) {
                        this.readFloat(action, controllertype);
                    }
                } else {
                    this.readFloat(action, null);
                }
            }

            case "vector2" -> {
                if (action.isHanded()) {
                    for (ControllerType controllertype : ControllerType.values()) {
                        this.readVecData(action, controllertype);
                    }
                } else {
                    this.readVecData(action, null);
                }
            }
        }
    }

    private void readBoolean(VRInputAction action, ControllerType hand) {
        int i = 0;

        if (hand != null) {
            i = hand.ordinal();
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            XrActionStateGetInfo info = XrActionStateGetInfo.calloc(stack);
            info.type(XR10.XR_TYPE_ACTION_STATE_GET_INFO);
            info.action(new XrAction(action.handle,
                new XrActionSet(this.actionSetHandles.get(action.actionSet), this.instance)));
            info.subactionPath(this.getSubactionPath(hand));
            XrActionStateBoolean state = XrActionStateBoolean.calloc(stack).type(XR10.XR_TYPE_ACTION_STATE_BOOLEAN);
            int error = XR10.xrGetActionStateBoolean(this.session, info, state);
            logError(error, "xrGetActionStateBoolean", action.name);

            if (error < XR10.XR_SUCCESS) {
                action.digitalData[i].state = false;
                action.digitalData[i].isActive = false;
                action.digitalData[i].isChanged = false;
                action.digitalData[i].activeOrigin = XR10.XR_NULL_PATH;
                return;
            }

            action.digitalData[i].state = state.currentState();
            action.digitalData[i].isActive = state.isActive();
            action.digitalData[i].isChanged = state.changedSinceLastSync();
            action.digitalData[i].activeOrigin = this.resolveActionOrigin(action, hand, state.isActive());
        }
    }

    private void readFloat(VRInputAction action, ControllerType hand) {
        int i = 0;

        if (hand != null) {
            i = hand.ordinal();
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            XrActionStateGetInfo info = XrActionStateGetInfo.calloc(stack);
            info.type(XR10.XR_TYPE_ACTION_STATE_GET_INFO);
            info.action(new XrAction(action.handle,
                new XrActionSet(this.actionSetHandles.get(action.actionSet), this.instance)));
            info.subactionPath(this.getSubactionPath(hand));
            XrActionStateFloat state = XrActionStateFloat.calloc(stack).type(XR10.XR_TYPE_ACTION_STATE_FLOAT);
            int error = XR10.xrGetActionStateFloat(this.session, info, state);
            logError(error, "xrGetActionStateFloat", action.name);

            if (error < XR10.XR_SUCCESS) {
                action.analogData[i].deltaX = 0.0F;
                action.analogData[i].x = 0.0F;
                action.analogData[i].activeOrigin = XR10.XR_NULL_PATH;
                action.analogData[i].isActive = false;
                action.analogData[i].isChanged = false;
                return;
            }

            action.analogData[i].deltaX = state.currentState() - action.analogData[i].x;
            action.analogData[i].x = state.currentState();
            action.analogData[i].activeOrigin = this.resolveActionOrigin(action, hand, state.isActive());
            action.analogData[i].isActive = state.isActive();
            action.analogData[i].isChanged = state.changedSinceLastSync();
        }
    }

    private void readVecData(VRInputAction action, ControllerType hand) {
        int i = 0;

        if (hand != null) {
            i = hand.ordinal();
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            XrActionStateGetInfo info = XrActionStateGetInfo.calloc(stack);
            info.type(XR10.XR_TYPE_ACTION_STATE_GET_INFO);
            info.action(new XrAction(action.handle,
                new XrActionSet(this.actionSetHandles.get(action.actionSet), this.instance)));
            info.subactionPath(this.getSubactionPath(hand));
            XrActionStateVector2f state = XrActionStateVector2f.calloc(stack).type(XR10.XR_TYPE_ACTION_STATE_VECTOR2F);
            int error = XR10.xrGetActionStateVector2f(this.session, info, state);
            logError(error, "xrGetActionStateVector2f", action.name);

            if (error < XR10.XR_SUCCESS) {
                action.analogData[i].deltaX = 0.0F;
                action.analogData[i].deltaY = 0.0F;
                action.analogData[i].x = 0.0F;
                action.analogData[i].y = 0.0F;
                action.analogData[i].activeOrigin = XR10.XR_NULL_PATH;
                action.analogData[i].isActive = false;
                action.analogData[i].isChanged = false;
                return;
            }

            action.analogData[i].deltaX = state.currentState().x() - action.analogData[i].x;
            action.analogData[i].deltaY = state.currentState().y() - action.analogData[i].y;
            action.analogData[i].x = state.currentState().x();
            action.analogData[i].y = state.currentState().y();
            action.analogData[i].activeOrigin = this.resolveActionOrigin(action, hand, state.isActive());
            action.analogData[i].isActive = state.isActive();
            action.analogData[i].isChanged = state.changedSinceLastSync();
        }
    }

    private boolean readPoseData(long action, XrActionSet set, ControllerType hand) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            XrActionStateGetInfo info = XrActionStateGetInfo.calloc(stack);
            info.type(XR10.XR_TYPE_ACTION_STATE_GET_INFO);
            info.action(new XrAction(action, set));
            info.subactionPath(this.getSubactionPath(hand));
            XrActionStatePose state = XrActionStatePose.calloc(stack).type(XR10.XR_TYPE_ACTION_STATE_POSE);
            int error = XR10.xrGetActionStatePose(this.session, info, state);
            logError(error, "xrGetActionStatePose", "");
            return error >= XR10.XR_SUCCESS && state.isActive();
        }
    }

    private long resolveActionOrigin(VRInputAction action, @Nullable ControllerType hand, boolean active) {
        if (!active) {
            return XR10.XR_NULL_PATH;
        }

        // Handed action state is queried with an explicit OpenXR subaction path, so the
        // controller is already known here. Do not round-trip opaque bound-source handles
        // through xrPathToString every frame: Meta's runtime can return source handles that
        // are not valid XrPath values, which caused thousands of XR_ERROR_PATH_INVALID
        // messages on the render thread and substantial per-frame allocation/logging work.
        if (hand != null) {
            return this.getSubactionPath(hand);
        }

        int index = ControllerType.values().length;
        long[] cachedOrigins = this.actionOrigins.computeIfAbsent(
            action.handle, ignored -> new long[ControllerType.values().length + 1]);
        if (cachedOrigins[index] != XR10.XR_NULL_PATH) {
            return cachedOrigins[index];
        }

        for (long origin : this.getOrigins(action)) {
            if (origin != XR10.XR_NULL_PATH) {
                cachedOrigins[index] = origin;
                return origin;
            }
        }
        return cachedOrigins[index];
    }

    private long getSubactionPath(@Nullable ControllerType hand) {
        if (hand == null) {
            return XR10.XR_NULL_PATH;
        }
        return this.getPath(hand == ControllerType.RIGHT ? "/user/hand/right" : "/user/hand/left");
    }

    private boolean updateActiveActionSets() {
        ArrayList<VRInputActionSet> arraylist = new ArrayList<>();
        arraylist.add(VRInputActionSet.GLOBAL);

        // we are always modded
        arraylist.add(VRInputActionSet.MOD);

        arraylist.add(VRInputActionSet.MIXED_REALITY);
        arraylist.add(VRInputActionSet.TECHNICAL);

        if (this.mc.gui.screen() == null) {
            arraylist.add(VRInputActionSet.INGAME);
            arraylist.add(VRInputActionSet.CONTEXTUAL);
        } else {
            arraylist.add(VRInputActionSet.GUI);
            if (ClientDataHolderVR.getInstance().vrSettings.ingameBindingsInGui) {
                arraylist.add(VRInputActionSet.INGAME);
            }
        }

        if (KeyboardHandler.SHOWING || RadialHandler.isShowing()) {
            arraylist.add(VRInputActionSet.KEYBOARD);
        }

        if (this.activeActionSetsBuffer == null) {
            this.activeActionSetsBuffer = XrActiveActionSet.calloc(arraylist.size());
        } else if (this.activeActionSetsBuffer.capacity() != arraylist.size()) {
            this.activeActionSetsBuffer.close();
            this.activeActionSetsBuffer = XrActiveActionSet.calloc(arraylist.size());
        }

        for (int i = 0; i < arraylist.size(); ++i) {
            VRInputActionSet vrinputactionset = arraylist.get(i);
            this.activeActionSetsBuffer.get(i)
                .set(new XrActionSet(this.getActionSetHandle(vrinputactionset), this.instance), NULL);
        }

        this.activeActionSets.removeAll(arraylist);
        for (VRInputActionSet set : this.activeActionSets) {
            this.unpressedSetKeys.get(set).clear();
        }
        this.activeActionSets = arraylist;

        return !arraylist.isEmpty();
    }

    long getActionSetHandle(VRInputActionSet actionSet) {
        return this.actionSetHandles.get(actionSet);
    }

    private void pollVREvents() {
        while (true) {
            this.eventDataBuffer.clear();
            this.eventDataBuffer.type(XR10.XR_TYPE_EVENT_DATA_BUFFER);
            int error = XR10.xrPollEvent(this.instance, this.eventDataBuffer);
            logError(error, "xrPollEvent", "");
            if (error != XR10.XR_SUCCESS) {
                break;
            }
            XrEventDataBaseHeader event = XrEventDataBaseHeader.create(this.eventDataBuffer.address());

            switch (event.type()) {
                case XR10.XR_TYPE_EVENT_DATA_INSTANCE_LOSS_PENDING -> {
                    XrEventDataInstanceLossPending instanceLossPending = XrEventDataInstanceLossPending.create(
                        event.address());
                }
                case XR10.XR_TYPE_EVENT_DATA_SESSION_STATE_CHANGED -> {
                    this.sessionChanged(XrEventDataSessionStateChanged.create(event.address()));
                }
                case XR10.XR_TYPE_EVENT_DATA_INTERACTION_PROFILE_CHANGED -> {
                    this.actionOrigins.clear();
                }
                case XR10.XR_TYPE_EVENT_DATA_REFERENCE_SPACE_CHANGE_PENDING -> {
                }
                default -> {
                }
            }
        }
    }

    private void sessionChanged(XrEventDataSessionStateChanged xrEventDataSessionStateChanged) {
        int state = xrEventDataSessionStateChanged.state();
        this.sessionState = state;
        VRSettings.LOGGER.info("Vivecraft: OpenXR session state changed to {}", sessionStateName(state));

        switch (state) {
            case XR10.XR_SESSION_STATE_READY: {
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    XrSessionBeginInfo sessionBeginInfo = XrSessionBeginInfo.calloc(stack);
                    sessionBeginInfo.type(XR10.XR_TYPE_SESSION_BEGIN_INFO);
                    sessionBeginInfo.next(NULL);
                    sessionBeginInfo.primaryViewConfigurationType(XR10.XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO);

                    int error = XR10.xrBeginSession(this.session, sessionBeginInfo);
                    logError(error, "xrBeginSession", "XR_SESSION_STATE_READY");
                    if (error < XR10.XR_SUCCESS) {
                        this.sessionRunning = false;
                        this.isActive = false;
                        throw new IllegalStateException("xrBeginSession failed with " + getResultName(error));
                    }
                }
                this.sessionRunning = true;
                this.isActive = true;
                this.initDisplayRefreshRate();
                break;
            }
            case XR10.XR_SESSION_STATE_STOPPING: {
                this.isActive = false;
                this.sessionRunning = false;
                int error = XR10.xrEndSession(this.session);
                logError(error, "xrEndSession", "XR_SESSION_STATE_STOPPING");
                break;
            }
            case XR10.XR_SESSION_STATE_VISIBLE, XR10.XR_SESSION_STATE_FOCUSED: {
                this.isActive = true;
                break;
            }
            case XR10.XR_SESSION_STATE_EXITING: {
                this.sessionRunning = false;
                this.isActive = false;
                if (ClientDataHolderVR.getInstance().vrSettings.closeWithRuntime) {
                    VRSettings.LOGGER.info("Vivecraft: OpenXR stopped, closing the game with it");
                    this.mc.stop();
                } else {
                    VRSettings.LOGGER.info("Vivecraft: OpenXR stopped, disabling VR");
                    VRState.VR_ENABLED = !VRState.VR_ENABLED;
                    ClientDataHolderVR.getInstance().vrSettings.vrEnabled = VRState.VR_ENABLED;
                    ClientDataHolderVR.getInstance().vrSettings.saveOptions();
                }
                break;
            }
            case XR10.XR_SESSION_STATE_SYNCHRONIZED: {
                // SYNCHRONIZED is still a running session. Keep the frame loop alive so the
                // runtime can advance to VISIBLE/FOCUSED and provide current tracking/input.
                this.isActive = true;
                break;
            }
            case XR10.XR_SESSION_STATE_IDLE: {
                this.isActive = false;
                this.sessionRunning = false;
                break;
            }
            case XR10.XR_SESSION_STATE_LOSS_PENDING: {
                this.sessionRunning = false;
                this.isActive = false;
                break;
            }
            default:
                break;
        }
    }

    @Override
    public Vector2f getPlayAreaSize() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            XrExtent2Df vec = XrExtent2Df.calloc(stack);
            int error = XR10.xrGetReferenceSpaceBoundsRect(this.session, this.appSpaceType, vec);
            logError(error, "xrGetReferenceSpaceBoundsRect", "");
            return new Vector2f(vec.width(), vec.height());
        }
    }

    @Override
    public void refreshControllerTransforms() {

    }

    @Override
    public boolean init() throws RenderConfigException {
        if (this.initialized) {
            return true;
        } else if (this.tried) {
            return this.initialized;
        } else {
            this.tried = true;
            this.mc = Minecraft.getInstance();
            try {
                this.initializeOpenXRInstance();
                this.initializeOpenXRSession();
                this.initializeOpenXRSpace();
                this.initializeOpenXRSwapChain();
                this.initInputAndApplication();
            } catch (RenderConfigException e) {
                throw e;
            } catch (Exception e) {
                VRSettings.LOGGER.error("Vivecraft: OpenXR init failed", e);
                this.initSuccess = false;
                this.initStatus = e.getLocalizedMessage();
                return false;
            }

            // TODO Seated when no controllers

            VRSettings.LOGGER.info("Vivecraft: OpenXR initialized & VR connected.");
            this.deviceVelocity = new Vector3f[64];

            for (int i = 0; i < this.poseMatrices.length; ++i) {
                this.poseMatrices[i] = new Matrix4f();
                this.deviceVelocity[i] = new Vector3f();
            }

            this.initialized = true;
            return true;
        }
    }

    private void initializeOpenXRInstance() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            this.device.initOpenXRLoader(stack);

            // Check extensions
            IntBuffer numExtensions = stack.callocInt(1);
            int error = XR10.xrEnumerateInstanceExtensionProperties((ByteBuffer) null, numExtensions, null);
            DeviceCompat.checkResult(error, "xrEnumerateInstanceExtensionProperties(count)");

            XrExtensionProperties.Buffer properties = new XrExtensionProperties.Buffer(
                bufferStack(numExtensions.get(0), XrExtensionProperties.SIZEOF, XR10.XR_TYPE_EXTENSION_PROPERTIES)
            );

            // Load extensions
            error = XR10.xrEnumerateInstanceExtensionProperties((ByteBuffer) null, numExtensions, properties);
            DeviceCompat.checkResult(error, "xrEnumerateInstanceExtensionProperties(list)");

            Set<String> availableExtensions = new HashSet<>();
            while (properties.hasRemaining()) {
                availableExtensions.add(properties.get().extensionNameString());
            }

            LinkedHashSet<String> requestedExtensions = new LinkedHashSet<>();
            for (String requiredExtension : this.device.getRequiredInstanceExtensions()) {
                if (!availableExtensions.contains(requiredExtension)) {
                    throw new RuntimeException("OpenXR runtime is missing required extension " + requiredExtension);
                }
                requestedExtensions.add(requiredExtension);
            }

            for (String optionalExtension : List.of(
                EXTHPMixedRealityController.XR_EXT_HP_MIXED_REALITY_CONTROLLER_EXTENSION_NAME,
                HTCViveCosmosControllerInteraction.XR_HTC_VIVE_COSMOS_CONTROLLER_INTERACTION_EXTENSION_NAME,
                FBDisplayRefreshRate.XR_FB_DISPLAY_REFRESH_RATE_EXTENSION_NAME,
                BDControllerInteraction.XR_BD_CONTROLLER_INTERACTION_EXTENSION_NAME))
            {
                if (availableExtensions.contains(optionalExtension)) {
                    requestedExtensions.add(optionalExtension);
                }
            }

            this.enabledInstanceExtensions.clear();
            this.enabledInstanceExtensions.addAll(requestedExtensions);
            PointerBuffer extensions = stack.callocPointer(requestedExtensions.size());
            requestedExtensions.forEach(extension -> extensions.put(memAddress(stackUTF8(extension))));

            // Create APP info
            XrApplicationInfo applicationInfo = XrApplicationInfo.calloc(stack);
            applicationInfo.apiVersion(XR10.XR_MAKE_VERSION(1, 0, 40));
            applicationInfo.applicationName(stack.UTF8("Vivecraft"));
            applicationInfo.applicationVersion(1);

            // Create instance info
            XrInstanceCreateInfo createInfo = XrInstanceCreateInfo.calloc(stack);
            createInfo.type(XR10.XR_TYPE_INSTANCE_CREATE_INFO);
            createInfo.next(this.device.getPlatformInfo(stack));
            createInfo.createFlags(0);
            createInfo.applicationInfo(applicationInfo);
            createInfo.enabledApiLayerNames(null);
            createInfo.enabledExtensionNames(extensions.flip());

            // Create XR instance
            PointerBuffer instancePtr = stack.callocPointer(1);
            int xrResult = XR10.xrCreateInstance(createInfo, instancePtr);
            if (xrResult == XR10.XR_ERROR_RUNTIME_FAILURE) {
                throw new RuntimeException("Failed to create xrInstance, are you sure your headset is plugged in?");
            } else if (xrResult == XR10.XR_ERROR_INSTANCE_LOST) {
                throw new RuntimeException("Failed to create xrInstance due to runtime updating");
            } else if (xrResult < 0) {
                throw new RuntimeException("XR method returned " + xrResult);
            }
            this.instance = new XrInstance(instancePtr.get(0), createInfo);

            this.poseMatrices = new Matrix4f[64];

            for (int i = 0; i < this.poseMatrices.length; ++i) {
                this.poseMatrices[i] = new Matrix4f();
            }

            this.initSuccess = true;
        }
    }

    public static MCOpenXR get() {
        return OME;
    }

    private void initializeOpenXRSession() throws RenderConfigException {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // Create system
            XrSystemGetInfo system = XrSystemGetInfo.calloc(stack);
            system.type(XR10.XR_TYPE_SYSTEM_GET_INFO);
            system.next(NULL);
            system.formFactor(XR10.XR_FORM_FACTOR_HEAD_MOUNTED_DISPLAY);

            LongBuffer longBuffer = stack.callocLong(1);
            int error = XR10.xrGetSystem(this.instance, system, longBuffer);
            DeviceCompat.checkResult(error, "xrGetSystem");
            this.systemID = longBuffer.get(0);

            if (this.systemID == 0) {
                throw new RuntimeException("No compatible headset detected");
            }

            XrSystemProperties systemProperties = XrSystemProperties.calloc(stack).type(XR10.XR_TYPE_SYSTEM_PROPERTIES);
            error = XR10.xrGetSystemProperties(this.instance, this.systemID, systemProperties);
            DeviceCompat.checkResult(error, "xrGetSystemProperties");
            XrSystemTrackingProperties trackingProperties = systemProperties.trackingProperties();
            XrSystemGraphicsProperties graphicsProperties = systemProperties.graphicsProperties();

            MCOpenXR.get().systemName = memUTF8(memAddress(systemProperties.systemName()));
            int vendor = systemProperties.vendorId();
            boolean orientationTracking = trackingProperties.orientationTracking();
            boolean positionTracking = trackingProperties.positionTracking();
            int maxWidth = graphicsProperties.maxSwapchainImageWidth();
            int maxHeight = graphicsProperties.maxSwapchainImageHeight();
            int maxLayerCount = graphicsProperties.maxLayerCount();

            VRSettings.LOGGER.info("Found device with id:  {}", this.systemID);
            VRSettings.LOGGER.info("Headset Name: {}, Vendor: {}", MCOpenXR.get().systemName, vendor);
            VRSettings.LOGGER.info("Headset Orientation Tracking: {}, Position Tracking: {}", orientationTracking,
                positionTracking);
            VRSettings.LOGGER.info("Headset Max Width: {}, Max Height: {}, Max Layer Count: {}", maxWidth, maxHeight,
                maxLayerCount);

            // Create session
            XrSessionCreateInfo info = XrSessionCreateInfo.calloc(stack);
            info.type(XR10.XR_TYPE_SESSION_CREATE_INFO);
            info.next(this.device.checkGraphics(stack, this.instance, this.systemID).address());
            info.createFlags(0);
            info.systemId(this.systemID);

            PointerBuffer sessionPtr = stack.callocPointer(1);
            error = XR10.xrCreateSession(this.instance, info, sessionPtr);
            DeviceCompat.checkResult(error, "xrCreateSession");

            this.session = new XrSession(sessionPtr.get(0), this.instance);
        }
    }

    private void initializeOpenXRSpace() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            XrPosef identityPose = XrPosef.calloc(stack);
            identityPose.set(
                XrQuaternionf.calloc(stack).set(0, 0, 0, 1),
                XrVector3f.calloc(stack)
            );

            XrReferenceSpaceCreateInfo referenceSpaceCreateInfo = XrReferenceSpaceCreateInfo.calloc(stack);
            referenceSpaceCreateInfo.type(XR10.XR_TYPE_REFERENCE_SPACE_CREATE_INFO);
            referenceSpaceCreateInfo.next(NULL);
            referenceSpaceCreateInfo.referenceSpaceType(XR10.XR_REFERENCE_SPACE_TYPE_STAGE);
            referenceSpaceCreateInfo.poseInReferenceSpace(identityPose);

            PointerBuffer pp = stack.callocPointer(1);
            int error = XR10.xrCreateReferenceSpace(this.session, referenceSpaceCreateInfo, pp);
            if (error == XR10.XR_ERROR_REFERENCE_SPACE_UNSUPPORTED) {
                this.appSpaceType = XR10.XR_REFERENCE_SPACE_TYPE_LOCAL;
                referenceSpaceCreateInfo.referenceSpaceType(this.appSpaceType);
                pp.put(0, NULL);
                error = XR10.xrCreateReferenceSpace(this.session, referenceSpaceCreateInfo, pp);
            }
            DeviceCompat.checkResult(error, "xrCreateReferenceSpace(STAGE/LOCAL)");
            this.xrAppSpace = new XrSpace(pp.get(0), this.session);

            referenceSpaceCreateInfo.referenceSpaceType(XR10.XR_REFERENCE_SPACE_TYPE_VIEW);
            error = XR10.xrCreateReferenceSpace(this.session, referenceSpaceCreateInfo, pp);
            DeviceCompat.checkResult(error, "xrCreateReferenceSpace(VIEW)");
            this.xrViewSpace = new XrSpace(pp.get(0), this.session);
        }
    }

    private void initializeOpenXRSwapChain() {
        try (MemoryStack stack = stackPush()) {
            // Check amount of views
            IntBuffer intBuf = stack.callocInt(1);
            int error = XR10.xrEnumerateViewConfigurationViews(this.instance, this.systemID,
                XR10.XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO, intBuf, null);
            DeviceCompat.checkResult(error, "xrEnumerateViewConfigurationViews(count)");

            // Get all views
            ByteBuffer viewConfBuffer = bufferStack(intBuf.get(0), XrViewConfigurationView.SIZEOF,
                XR10.XR_TYPE_VIEW_CONFIGURATION_VIEW);
            XrViewConfigurationView.Buffer views = new XrViewConfigurationView.Buffer(viewConfBuffer);
            error = XR10.xrEnumerateViewConfigurationViews(this.instance, this.systemID,
                XR10.XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO, intBuf, views);
            DeviceCompat.checkResult(error, "xrEnumerateViewConfigurationViews(list)");
            int viewCountNumber = intBuf.get(0);
            if (viewCountNumber < 2) {
                throw new IllegalStateException("OpenXR runtime did not provide two stereo views");
            }

            this.viewBuffer = new XrView.Buffer(
                bufferHeap(viewCountNumber, XrView.SIZEOF, XR10.XR_TYPE_VIEW)
            );
            // Check swapchain formats
            error = XR10.xrEnumerateSwapchainFormats(this.session, intBuf, null);
            DeviceCompat.checkResult(error, "xrEnumerateSwapchainFormats(count)");

            // Get swapchain formats
            LongBuffer swapchainFormats = stack.callocLong(intBuf.get(0));
            error = XR10.xrEnumerateSwapchainFormats(this.session, intBuf, swapchainFormats);
            DeviceCompat.checkResult(error, "xrEnumerateSwapchainFormats(list)");

            long[] desiredSwapchainFormats = this.device.getPreferredSwapchainFormats();

            // Choose format
            long chosenFormat = 0;
            for (long glFormatIter : desiredSwapchainFormats) {
                swapchainFormats.rewind();
                while (swapchainFormats.hasRemaining()) {
                    if (glFormatIter == swapchainFormats.get()) {
                        chosenFormat = glFormatIter;
                        break;
                    }
                }
                if (chosenFormat != 0) {
                    break;
                }
            }

            if (chosenFormat == 0) {
                var formats = new ArrayList<Long>();
                swapchainFormats.rewind();
                while (swapchainFormats.hasRemaining()) {
                    formats.add(swapchainFormats.get());
                }
                throw new RuntimeException("No compatible swapchain / framebuffer format available: " + formats);
            }

            this.swapchainFormat = chosenFormat;
            VRSettings.LOGGER.info("Vivecraft: selected OpenXR swapchain format {}", chosenFormat);

            this.swapchain = new XrSwapchain[2];
            // Make swapchain
            for(int i = 0; i < 2; i++) {
                XrViewConfigurationView viewConfig = views.get(i);
                XrSwapchainCreateInfo swapchainCreateInfo = XrSwapchainCreateInfo.calloc(stack);
                swapchainCreateInfo.type(XR10.XR_TYPE_SWAPCHAIN_CREATE_INFO);
                swapchainCreateInfo.next(NULL);
                swapchainCreateInfo.createFlags(0);
                swapchainCreateInfo.usageFlags(this.device.getSwapchainUsageFlags());
                swapchainCreateInfo.format(chosenFormat);
                swapchainCreateInfo.sampleCount(1);
                swapchainCreateInfo.width(viewConfig.recommendedImageRectWidth());
                swapchainCreateInfo.height(viewConfig.recommendedImageRectHeight());
                swapchainCreateInfo.faceCount(1);
                swapchainCreateInfo.arraySize(1);
                swapchainCreateInfo.mipCount(1);

                PointerBuffer handlePointer = stack.callocPointer(1);
                error = XR10.xrCreateSwapchain(this.session, swapchainCreateInfo, handlePointer);
                DeviceCompat.checkResult(error, "xrCreateSwapchain(format=" + chosenFormat + ")");
                this.swapchain[i] = new XrSwapchain(handlePointer.get(0), this.session);
                this.width = swapchainCreateInfo.width();
                this.height = swapchainCreateInfo.height();
            }
        }
    }

    public long getSystemID() {
        return this.systemID;
    }

    private void initDisplayRefreshRate() {
        if (!this.enabledInstanceExtensions.contains(FBDisplayRefreshRate.XR_FB_DISPLAY_REFRESH_RATE_EXTENSION_NAME)) {
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer refreshRateCount = stack.callocInt(1);
            DeviceCompat.checkResult(
                FBDisplayRefreshRate.xrEnumerateDisplayRefreshRatesFB(session, refreshRateCount, null),
                "xrEnumerateDisplayRefreshRatesFB(count)");
            if (refreshRateCount.get(0) == 0) {
                return;
            }
            FloatBuffer refreshRateBuffer = stack.callocFloat(refreshRateCount.get(0));
            DeviceCompat.checkResult(
                FBDisplayRefreshRate.xrEnumerateDisplayRefreshRatesFB(session, refreshRateCount, refreshRateBuffer),
                "xrEnumerateDisplayRefreshRatesFB(list)");
            refreshRateBuffer.rewind();
            DeviceCompat.checkResult(
                FBDisplayRefreshRate.xrRequestDisplayRefreshRateFB(
                    session, refreshRateBuffer.get(refreshRateCount.get(0) - 1)),
                "xrRequestDisplayRefreshRateFB");
        }
    }

    /**
     * Creates an array of XrStructs with their types preset to {@code type}
     */
    static ByteBuffer bufferStack(int capacity, int sizeof, int type) {
        ByteBuffer b = stackCalloc(capacity * sizeof);

        for (int i = 0; i < capacity; i++) {
            b.position(i * sizeof);
            b.putInt(type);
        }
        b.rewind();
        return b;
    }

    private void initInputAndApplication() {
        this.populateInputActions();

        //this.generateActionManifest();
        //this.loadActionManifest();
        this.loadActionHandles();
        this.loadDefaultBindings();
        //this.installApplicationManifest(false);
        this.inputInitialized = true;
    }

    @Override
    public Matrix4f getControllerComponentTransform(int controllerIndex, String componentName) {
        return new Matrix4f();
    }

    @Override
    public boolean hasCameraTracker() {
        return false;
    }

    @Override
    public List<Long> getOrigins(VRInputAction action) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            XrBoundSourcesForActionEnumerateInfo info = XrBoundSourcesForActionEnumerateInfo.calloc(stack);
            info.type(XR10.XR_TYPE_BOUND_SOURCES_FOR_ACTION_ENUMERATE_INFO);
            info.next(NULL);
            info.action(new XrAction(action.handle,
                new XrActionSet(this.actionSetHandles.get(action.actionSet), this.instance)));
            IntBuffer buf = stack.callocInt(1);
            int error = XR10.xrEnumerateBoundSourcesForAction(this.session, info, buf, null);
            logError(error, "xrEnumerateBoundSourcesForAction", action.name);

            int size = buf.get();
            if (size <= 0) {
                return List.of(0L);
            }

            buf = stack.callocInt(size);
            LongBuffer longbuf = stack.callocLong(size);
            error = XR10.xrEnumerateBoundSourcesForAction(this.session, info, buf, longbuf);
            logError(error, "xrEnumerateBoundSourcesForAction", action.name);
            longbuf.rewind();
            ArrayList<Long> origins = new ArrayList<>(longbuf.remaining() + ControllerType.values().length);
            while (longbuf.hasRemaining()) {
                origins.add(longbuf.get());
            }

            // For handed OpenXR actions, Vivecraft uses the queried hand as the stable
            // activeOrigin. Include that same root path in the origin set while the hand is
            // active so VRInputAction's priority arbitration still matches competing actions.
            if (action.isHanded()) {
                for (ControllerType hand : ControllerType.values()) {
                    int index = hand.ordinal();
                    boolean active = switch (action.type) {
                        case "boolean" -> action.digitalData[index].isActive;
                        case "vector1", "vector2", "vector3" -> action.analogData[index].isActive;
                        default -> false;
                    };
                    if (active) {
                        origins.add(this.getSubactionPath(hand));
                    }
                }
            }
            return origins;
        }
    }

    @Override
    public String getOriginName(long origin) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            XrInputSourceLocalizedNameGetInfo info = XrInputSourceLocalizedNameGetInfo.calloc(stack);
            info.type(XR10.XR_TYPE_INPUT_SOURCE_LOCALIZED_NAME_GET_INFO);
            info.next(0);
            info.sourcePath(origin);
            info.whichComponents(XR10.XR_INPUT_SOURCE_LOCALIZED_NAME_COMPONENT_BIT);

            IntBuffer buf = stack.callocInt(1);
            int error = XR10.xrGetInputSourceLocalizedName(this.session, info, buf, null);
            logError(error, "xrGetInputSourceLocalizedName", "get length");

            int size = buf.get();
            if (size <= 0) {
                return "";
            }

            buf = stack.callocInt(size);
            ByteBuffer byteBuffer = stack.calloc(size);
            error = XR10.xrGetInputSourceLocalizedName(this.session, info, buf, byteBuffer);
            logError(error, "xrGetInputSourceLocalizedName", "get String");
            return MemoryUtil.memUTF8(MemoryUtil.memAddress(byteBuffer));
        }
    }

    @Override
    public VRRenderer createVRRenderer() {
        return new OpenXRStereoRenderer(this);
    }

    @Override
    public boolean isActive() {
        // Do not begin the OpenXR session during init(): VRState.initializeVR() still has
        // substantial renderer/menu/shader setup to do after MCOpenXR.init() returns. Starting
        // the runtime there can leave Quest without xrWaitFrame/xrEndFrame calls long enough for
        // the session to stop before controller action spaces are ever sampled. This query is
        // made immediately after VR initialization and also while hot-switching, so it is the
        // earliest safe place to consume READY and begin the session just before the frame loop.
        if (this.initialized && !this.sessionRunning && this.session != null) {
            this.pollVREvents();
        }
        return this.isActive;
    }

    @Override
    public ControllerType getOriginControllerType(long inputValueHandle) {
        if (inputValueHandle == XR10.XR_NULL_PATH) {
            return null;
        }

        // resolveActionOrigin() deliberately exposes only these stable root paths for
        // handed actions. Comparing handles is constant-time and avoids asking OpenXR to
        // stringify runtime-private bound-source values on every isEnabled() call.
        if (inputValueHandle == this.getSubactionPath(ControllerType.RIGHT)) {
            return ControllerType.RIGHT;
        }
        if (inputValueHandle == this.getSubactionPath(ControllerType.LEFT)) {
            return ControllerType.LEFT;
        }
        return null;
    }

    @Override
    public float getIPD() {
        return this.getEyePosition(RenderPass.RIGHT).x - this.getEyePosition(RenderPass.LEFT).x;
    }

    @Override
    public String getRuntimeName() {
        return "OpenXR";
    }

    private static final String[] BOTH_HANDS = new String[]{"/user/hand/left", "/user/hand/right"};

    //TODO Collect and register all actions
    private void loadActionHandles() {
        for (VRInputActionSet vrinputactionset : VRInputActionSet.values()) {
            long actionSet = makeActionSet(this.instance, vrinputactionset.name, vrinputactionset.localizedName, 0);
            this.actionSetHandles.put(vrinputactionset, actionSet);
        }

        for (VRInputAction vrinputaction : this.inputActions.values()) {
            long action = createAction(vrinputaction.name, vrinputaction.name, vrinputaction.type,
                new XrActionSet(this.actionSetHandles.get(vrinputaction.actionSet), this.instance), BOTH_HANDS);
            vrinputaction.setHandle(action);
        }

        setupControllers();

        XrActionSet actionSet = new XrActionSet(this.actionSetHandles.get(VRInputActionSet.GLOBAL), this.instance);
        this.haptics[RIGHT_CONTROLLER] = createAction("/actions/global/out/righthaptic",
            "/actions/global/out/righthaptic", "haptic", actionSet, BOTH_HANDS);
        this.haptics[LEFT_CONTROLLER] = createAction("/actions/global/out/lefthaptic", "/actions/global/out/lefthaptic",
            "haptic", actionSet, BOTH_HANDS);
    }

    private void setupControllers() {
        XrActionSet actionSet = new XrActionSet(this.actionSetHandles.get(VRInputActionSet.GLOBAL), this.instance);
        this.grip[RIGHT_CONTROLLER] = createAction("/actions/global/in/righthand", "/actions/global/in/righthand",
            "pose", actionSet, BOTH_HANDS);
        this.grip[LEFT_CONTROLLER] = createAction("/actions/global/in/lefthand", "/actions/global/in/lefthand", "pose",
            actionSet, BOTH_HANDS);
        this.aim[RIGHT_CONTROLLER] = createAction("/actions/global/in/righthandaim", "/actions/global/in/righthandaim",
            "pose", actionSet, BOTH_HANDS);
        this.aim[LEFT_CONTROLLER] = createAction("/actions/global/in/lefthandaim", "/actions/global/in/lefthandaim",
            "pose", actionSet, BOTH_HANDS);
    }

    private void loadDefaultBindings() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            int error;
            for (String headset : XRBindings.supportedHeadsets()) {
                VRSettings.LOGGER.info("loading defaults for {}", headset);
                Pair<String, String>[] defaultBindings = XRBindings.getBinding(headset).toArray(new Pair[0]);
                XrActionSuggestedBinding.Buffer bindings = XrActionSuggestedBinding.calloc(defaultBindings.length + 6,
                    stack); //TODO different way of adding controller poses

                for (int i = 0; i < defaultBindings.length; i++) {
                    Pair<String, String> pair = defaultBindings[i];
                    VRInputAction binding = this.getInputActionByName(pair.getLeft());
                    if (binding.handle == 0L) {
                        VRSettings.LOGGER.error("Handle for '{}'/'{}' is null", pair.getLeft(), pair.getRight());
                        continue;
                    }
                    bindings.get(i).set(
                        new XrAction(binding.handle,
                            new XrActionSet(this.actionSetHandles.get(binding.actionSet), this.instance)),
                        getPath(pair.getRight())
                    );
                }

                //TODO make this also changeable?
                XrActionSet actionSet = new XrActionSet(this.actionSetHandles.get(VRInputActionSet.GLOBAL),
                    this.instance);
                bindings.get(defaultBindings.length).set(
                    new XrAction(this.grip[RIGHT_CONTROLLER], actionSet),
                    getPath("/user/hand/right/input/grip/pose")
                );
                bindings.get(defaultBindings.length + 1).set(
                    new XrAction(this.grip[LEFT_CONTROLLER], actionSet),
                    getPath("/user/hand/left/input/grip/pose")
                );
                bindings.get(defaultBindings.length + 2).set(
                    new XrAction(this.aim[RIGHT_CONTROLLER], actionSet),
                    getPath("/user/hand/right/input/aim/pose")
                );
                bindings.get(defaultBindings.length + 3).set(
                    new XrAction(this.aim[LEFT_CONTROLLER], actionSet),
                    getPath("/user/hand/left/input/aim/pose")
                );

                bindings.get(defaultBindings.length + 4).set(
                    new XrAction(this.haptics[RIGHT_CONTROLLER], actionSet),
                    getPath("/user/hand/right/output/haptic")
                );

                bindings.get(defaultBindings.length + 5).set(
                    new XrAction(this.haptics[LEFT_CONTROLLER], actionSet),
                    getPath("/user/hand/left/output/haptic")
                );

                XrInteractionProfileSuggestedBinding suggested_binds = XrInteractionProfileSuggestedBinding.calloc(
                    stack);
                suggested_binds.type(XR10.XR_TYPE_INTERACTION_PROFILE_SUGGESTED_BINDING);
                suggested_binds.next(NULL);
                suggested_binds.interactionProfile(getPath(headset));
                suggested_binds.suggestedBindings(bindings);

                error = XR10.xrSuggestInteractionProfileBindings(this.instance, suggested_binds);
                logError(error, "xrSuggestInteractionProfileBindings", headset);
            }


            XrSessionActionSetsAttachInfo attach_info = XrSessionActionSetsAttachInfo.calloc(stack);
            attach_info.type(XR10.XR_TYPE_SESSION_ACTION_SETS_ATTACH_INFO);
            attach_info.next(NULL);
            attach_info.actionSets(
                stackPointers(this.actionSetHandles.values().stream().mapToLong(value -> value).toArray()));

            error = XR10.xrAttachSessionActionSets(this.session, attach_info);
            DeviceCompat.checkResult(error, "xrAttachSessionActionSets");

            XrActionSet actionSet = new XrActionSet(this.actionSetHandles.get(VRInputActionSet.GLOBAL), this.instance);
            XrActionSpaceCreateInfo actionSpace = XrActionSpaceCreateInfo.calloc(stack);
            actionSpace.type(XR10.XR_TYPE_ACTION_SPACE_CREATE_INFO);
            actionSpace.next(NULL);
            actionSpace.action(new XrAction(this.grip[RIGHT_CONTROLLER], actionSet));
            actionSpace.subactionPath(getPath("/user/hand/right"));
            actionSpace.poseInActionSpace(POSE_IDENTITY);
            PointerBuffer pp = stackCallocPointer(1);
            error = XR10.xrCreateActionSpace(this.session, actionSpace, pp);
            DeviceCompat.checkResult(error, "xrCreateActionSpace(grip: /user/hand/right)");
            this.gripSpace[RIGHT_CONTROLLER] = new XrSpace(pp.get(0), this.session);

            actionSpace.action(new XrAction(this.grip[LEFT_CONTROLLER], actionSet));
            actionSpace.subactionPath(getPath("/user/hand/left"));
            pp.put(0, NULL);
            error = XR10.xrCreateActionSpace(this.session, actionSpace, pp);
            DeviceCompat.checkResult(error, "xrCreateActionSpace(grip: /user/hand/left)");
            this.gripSpace[LEFT_CONTROLLER] = new XrSpace(pp.get(0), this.session);

            actionSpace.action(new XrAction(this.aim[RIGHT_CONTROLLER], actionSet));
            actionSpace.subactionPath(getPath("/user/hand/right"));
            pp.put(0, NULL);
            error = XR10.xrCreateActionSpace(session, actionSpace, pp);
            DeviceCompat.checkResult(error, "xrCreateActionSpace(aim: /user/hand/right)");
            this.aimSpace[RIGHT_CONTROLLER] = new XrSpace(pp.get(0), this.session);

            actionSpace.action(new XrAction(this.aim[LEFT_CONTROLLER], actionSet));
            actionSpace.subactionPath(getPath("/user/hand/left"));
            pp.put(0, NULL);
            error = XR10.xrCreateActionSpace(this.session, actionSpace, pp);
            DeviceCompat.checkResult(error, "xrCreateActionSpace(aim: /user/hand/left)");
            this.aimSpace[LEFT_CONTROLLER] = new XrSpace(pp.get(0), this.session);
        }
    }

    public long getPath(String pathString) {
        return this.paths.computeIfAbsent(pathString, s -> {
            try (MemoryStack ignored = stackPush()) {
                LongBuffer buf = stackCallocLong(1);
                int error = XR10.xrStringToPath(this.instance, pathString, buf);
                logError(error, "getPath", pathString);
                return buf.get();
            }
        });
    }

    private String processActionName(String name) {
        String s = name.substring(name.lastIndexOf('/') + 1)
            .toLowerCase()
            .replaceAll(" ", "-")
            .replaceAll("[^-_./a-z0-9]", "");

        if (s.length() > 64) {s = s.substring(0, 60) + "...";}
        return s;
    }

    private long createAction(
        String name, String localisedName, String type, XrActionSet actionSet, @Nullable String[] subactionPaths)
    {
        try (MemoryStack stack = MemoryStack.stackPush()) {

            String s = processActionName(name);
            XrActionCreateInfo hands = XrActionCreateInfo.calloc(stack);
            hands.type(XR10.XR_TYPE_ACTION_CREATE_INFO);
            hands.next(NULL);
            hands.actionName(memUTF8(s));
            switch (type) {
                case "boolean" -> hands.actionType(XR10.XR_ACTION_TYPE_BOOLEAN_INPUT);
                case "vector1" -> hands.actionType(XR10.XR_ACTION_TYPE_FLOAT_INPUT);
                case "vector2" -> hands.actionType(XR10.XR_ACTION_TYPE_VECTOR2F_INPUT);
                case "pose" -> hands.actionType(XR10.XR_ACTION_TYPE_POSE_INPUT);
                case "haptic" -> hands.actionType(XR10.XR_ACTION_TYPE_VIBRATION_OUTPUT);
            }
            if (subactionPaths != null) {
                LongBuffer buffer = stackCallocLong(subactionPaths.length);
                for (String path : subactionPaths) {
                    buffer.put(getPath(path));
                }
                hands.countSubactionPaths(subactionPaths.length);
                hands.subactionPaths(buffer.rewind());
            } else {
                hands.countSubactionPaths(0);
                hands.subactionPaths(null);
            }
            hands.localizedActionName(memUTF8(s));
            PointerBuffer buffer = stackCallocPointer(1);

            int error = XR10.xrCreateAction(actionSet, hands, buffer);
            logError(error, "xrCreateAction", "name:", name, "type:", type);
            return buffer.get(0);
        }
    }

    private long makeActionSet(XrInstance instance, String name, String localisedName, int priority) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            XrActionSetCreateInfo info = XrActionSetCreateInfo.calloc(stack);
            info.type(XR10.XR_TYPE_ACTION_SET_CREATE_INFO);
            info.next(NULL);
            info.actionSetName(memUTF8(localisedName.toLowerCase()));
            info.localizedActionSetName(memUTF8(localisedName.toLowerCase()));
            info.priority(priority);
            PointerBuffer buffer = stack.callocPointer(1);

            int error = XR10.xrCreateActionSet(instance, info, buffer);
            logError(error, "makeActionSet", localisedName.toLowerCase());
            return buffer.get(0);
        }
    }

    static ByteBuffer bufferHeap(int capacity, int sizeof, int type) {
        ByteBuffer b = memCalloc(capacity * sizeof);

        for (int i = 0; i < capacity; i++) {
            b.position(i * sizeof);
            b.putInt(type);
        }
        b.rewind();
        return b;
    }

    /**
     * gets the String for the given xrResult
     */
    private String getResultName(int xrResult) {
        String resultString = switch (xrResult) {
            case 1 -> "XR_TIMEOUT_EXPIRED";
            case 3 -> "XR_SESSION_LOSS_PENDING";
            case 4 -> "XR_EVENT_UNAVAILABLE";
            case 7 -> "XR_SPACE_BOUNDS_UNAVAILABLE";
            case 8 -> "XR_SESSION_NOT_FOCUSED";
            case 9 -> "XR_FRAME_DISCARDED";
            case -1 -> "XR_ERROR_VALIDATION_FAILURE";
            case -2 -> "XR_ERROR_RUNTIME_FAILURE";
            case -3 -> "XR_ERROR_OUT_OF_MEMORY";
            case -4 -> "XR_ERROR_API_VERSION_UNSUPPORTED";
            case -6 -> "XR_ERROR_INITIALIZATION_FAILED";
            case -7 -> "XR_ERROR_FUNCTION_UNSUPPORTED";
            case -8 -> "XR_ERROR_FEATURE_UNSUPPORTED";
            case -9 -> "XR_ERROR_EXTENSION_NOT_PRESENT";
            case -10 -> "XR_ERROR_LIMIT_REACHED";
            case -11 -> "XR_ERROR_SIZE_INSUFFICIENT";
            case -12 -> "XR_ERROR_HANDLE_INVALID";
            case -13 -> "XR_ERROR_INSTANCE_LOST";
            case -14 -> "XR_ERROR_SESSION_RUNNING";
            case -16 -> "XR_ERROR_SESSION_NOT_RUNNING";
            case -17 -> "XR_ERROR_SESSION_LOST";
            case -18 -> "XR_ERROR_SYSTEM_INVALID";
            case -19 -> "XR_ERROR_PATH_INVALID";
            case -20 -> "XR_ERROR_PATH_COUNT_EXCEEDED";
            case -21 -> "XR_ERROR_PATH_FORMAT_INVALID";
            case -22 -> "XR_ERROR_PATH_UNSUPPORTED";
            case -23 -> "XR_ERROR_LAYER_INVALID";
            case -24 -> "XR_ERROR_LAYER_LIMIT_EXCEEDED";
            case -25 -> "XR_ERROR_SWAPCHAIN_RECT_INVALID";
            case -26 -> "XR_ERROR_SWAPCHAIN_FORMAT_UNSUPPORTED";
            case -27 -> "XR_ERROR_ACTION_TYPE_MISMATCH";
            case -28 -> "XR_ERROR_SESSION_NOT_READY";
            case -29 -> "XR_ERROR_SESSION_NOT_STOPPING";
            case -30 -> "XR_ERROR_TIME_INVALID";
            case -31 -> "XR_ERROR_REFERENCE_SPACE_UNSUPPORTED";
            case -32 -> "XR_ERROR_FILE_ACCESS_ERROR";
            case -33 -> "XR_ERROR_FILE_CONTENTS_INVALID";
            case -34 -> "XR_ERROR_FORM_FACTOR_UNSUPPORTED";
            case -35 -> "XR_ERROR_FORM_FACTOR_UNAVAILABLE";
            case -36 -> "XR_ERROR_API_LAYER_NOT_PRESENT";
            case -37 -> "XR_ERROR_CALL_ORDER_INVALID";
            case -38 -> "XR_ERROR_GRAPHICS_DEVICE_INVALID";
            case -39 -> "XR_ERROR_POSE_INVALID";
            case -40 -> "XR_ERROR_INDEX_OUT_OF_RANGE";
            case -41 -> "XR_ERROR_VIEW_CONFIGURATION_TYPE_UNSUPPORTED";
            case -42 -> "XR_ERROR_ENVIRONMENT_BLEND_MODE_UNSUPPORTED";
            case -44 -> "XR_ERROR_NAME_DUPLICATED";
            case -45 -> "XR_ERROR_NAME_INVALID";
            case -46 -> "XR_ERROR_ACTIONSET_NOT_ATTACHED";
            case -47 -> "XR_ERROR_ACTIONSETS_ALREADY_ATTACHED";
            case -48 -> "XR_ERROR_LOCALIZED_NAME_DUPLICATED";
            case -49 -> "XR_ERROR_LOCALIZED_NAME_INVALID";
            case -50 -> "XR_ERROR_GRAPHICS_REQUIREMENTS_CALL_MISSING";
            case -51 -> "XR_ERROR_RUNTIME_UNAVAILABLE";
            default -> null;
        };
        if (resultString == null) {
            // ask the runtime for the xrResult name
            try (MemoryStack stack = MemoryStack.stackPush()) {
                ByteBuffer str = stack.calloc(XR10.XR_MAX_RESULT_STRING_SIZE);

                if (XR10.xrResultToString(this.instance, xrResult, str) == XR10.XR_SUCCESS) {
                    resultString = (memUTF8(memAddress(str)));
                } else {
                    resultString = "Unknown Error: " + xrResult;
                }
            }
        }
        return resultString;
    }

    private static String sessionStateName(int state) {
        return switch (state) {
            case XR10.XR_SESSION_STATE_IDLE -> "IDLE";
            case XR10.XR_SESSION_STATE_READY -> "READY";
            case XR10.XR_SESSION_STATE_SYNCHRONIZED -> "SYNCHRONIZED";
            case XR10.XR_SESSION_STATE_VISIBLE -> "VISIBLE";
            case XR10.XR_SESSION_STATE_FOCUSED -> "FOCUSED";
            case XR10.XR_SESSION_STATE_STOPPING -> "STOPPING";
            case XR10.XR_SESSION_STATE_LOSS_PENDING -> "LOSS_PENDING";
            case XR10.XR_SESSION_STATE_EXITING -> "EXITING";
            default -> "UNKNOWN(" + state + ")";
        };
    }

    /**
     * logs only errors
     *
     * @param xrResult result to check
     * @param caller   where the xrResult came from
     * @param args     arguments may be helpful in locating the error
     */
    protected void logError(int xrResult, String caller, String... args) {
        if (xrResult < 0) {
            VRSettings.LOGGER.error("{} for {} errored: {}", caller, String.join(" ", args), getResultName(xrResult));
        }
    }
}
