/*
 * Android-safe OSHI entry point for QuestCraft.
 *
 * Minecraft and mods use OSHI for system reports and driver workarounds. The
 * stock Linux backend assumes desktop Linux paths, commands, and hardware buses
 * that do not exist on Quest. This class is placed before oshi-core on the
 * classpath and provides lightweight proxy answers instead of constructing the
 * Linux backend.
 */
package oshi;

import java.lang.reflect.Array;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import oshi.hardware.CentralProcessor;
import oshi.hardware.GlobalMemory;
import oshi.hardware.HardwareAbstractionLayer;
import oshi.hardware.VirtualMemory;
import oshi.software.os.OperatingSystem;

public class SystemInfo {

    private final OperatingSystem os = proxy(OperatingSystem.class);
    private final HardwareAbstractionLayer hardware = proxy(HardwareAbstractionLayer.class);

    public SystemInfo() {
    }

    public static PlatformEnum getCurrentPlatform() {
        return PlatformEnum.ANDROID;
    }

    public OperatingSystem getOperatingSystem() {
        return os;
    }

    public HardwareAbstractionLayer getHardware() {
        return hardware;
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, new AndroidOshiHandler(type));
    }

    private static final class AndroidOshiHandler implements InvocationHandler {
        private final Class<?> type;

        private AndroidOshiHandler(Class<?> type) {
            this.type = type;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            String name = method.getName();
            if ("toString".equals(name)) {
                return type.getSimpleName() + "[QuestCraft Android shim]";
            }
            if ("hashCode".equals(name)) {
                return System.identityHashCode(proxy);
            }
            if ("equals".equals(name)) {
                return proxy == args[0];
            }

            if (type == OperatingSystem.class) {
                return operatingSystemValue(method);
            }
            if (type == HardwareAbstractionLayer.class) {
                return hardwareValue(method);
            }
            if (type == CentralProcessor.class) {
                return processorValue(method, args);
            }
            if (type == GlobalMemory.class) {
                return memoryValue(method);
            }
            if (type == VirtualMemory.class) {
                return virtualMemoryValue(method);
            }
            return defaultValue(method.getReturnType());
        }

        private Object operatingSystemValue(Method method) {
            String name = method.getName();
            if ("getFamily".equals(name) || "getManufacturer".equals(name)) {
                return "Android";
            }
            if ("getVersionInfo".equals(name)) {
                return new OperatingSystem.OSVersionInfo(System.getProperty("os.version", "Android"), "Quest", "");
            }
            if ("getBitness".equals(name)) {
                return 64;
            }
            if ("getProcessId".equals(name) || "getProcessCount".equals(name)) {
                return 1;
            }
            if ("getThreadId".equals(name)) {
                return (int) Thread.currentThread().getId();
            }
            if ("getThreadCount".equals(name)) {
                return Math.max(1, Thread.activeCount());
            }
            if ("getSystemUptime".equals(name)) {
                return Math.max(1L, System.nanoTime() / 1000000000L);
            }
            if ("getSystemBootTime".equals(name)) {
                long uptime = Math.max(1L, System.nanoTime() / 1000000000L);
                return System.currentTimeMillis() / 1000L - uptime;
            }
            return defaultValue(method.getReturnType());
        }

        private Object hardwareValue(Method method) {
            String name = method.getName();
            if ("getProcessor".equals(name)) {
                return proxy(CentralProcessor.class);
            }
            if ("getMemory".equals(name)) {
                return proxy(GlobalMemory.class);
            }
            return defaultValue(method.getReturnType());
        }

        private Object processorValue(Method method, Object[] args) {
            String name = method.getName();
            int cpuCount = Math.max(1, Runtime.getRuntime().availableProcessors());
            if ("getProcessorIdentifier".equals(name)) {
                return new CentralProcessor.ProcessorIdentifier(
                        "Qualcomm",
                        "Quest ARM64",
                        "ARM",
                        "0",
                        "0",
                        "QuestCraft",
                        true,
                        0L
                );
            }
            if ("getLogicalProcessorCount".equals(name) || "getPhysicalProcessorCount".equals(name)) {
                return cpuCount;
            }
            if ("getPhysicalPackageCount".equals(name)) {
                return 1;
            }
            if ("getCurrentFreq".equals(name)) {
                return new long[cpuCount];
            }
            if ("getSystemCpuLoadTicks".equals(name)) {
                return new long[CentralProcessor.TickType.values().length];
            }
            if ("getProcessorCpuLoadTicks".equals(name)) {
                return new long[cpuCount][CentralProcessor.TickType.values().length];
            }
            if ("getSystemLoadAverage".equals(name)) {
                int length = args != null && args.length > 0 && args[0] instanceof Integer ? (Integer) args[0] : 1;
                double[] load = new double[Math.max(0, length)];
                Arrays.fill(load, -1.0D);
                return load;
            }
            if ("getProcessorCpuLoadBetweenTicks".equals(name)) {
                return new double[cpuCount];
            }
            return defaultValue(method.getReturnType());
        }

        private Object memoryValue(Method method) {
            String name = method.getName();
            long maxMemory = Math.max(Runtime.getRuntime().maxMemory(), 1024L * 1024L * 1024L);
            if ("getTotal".equals(name)) {
                return maxMemory;
            }
            if ("getAvailable".equals(name)) {
                return Math.max(Runtime.getRuntime().freeMemory(), 64L * 1024L * 1024L);
            }
            if ("getPageSize".equals(name)) {
                return 4096L;
            }
            if ("getVirtualMemory".equals(name)) {
                return proxy(VirtualMemory.class);
            }
            return defaultValue(method.getReturnType());
        }

        private Object virtualMemoryValue(Method method) {
            String name = method.getName();
            if ("getVirtualMax".equals(name)) {
                return Math.max(Runtime.getRuntime().maxMemory(), 1024L * 1024L * 1024L);
            }
            if ("getVirtualInUse".equals(name)) {
                Runtime runtime = Runtime.getRuntime();
                return Math.max(0L, runtime.totalMemory() - runtime.freeMemory());
            }
            return defaultValue(method.getReturnType());
        }

        private Object defaultValue(Class<?> returnType) {
            if (returnType == Void.TYPE) {
                return null;
            }
            if (returnType == Boolean.TYPE) {
                return false;
            }
            if (returnType == Byte.TYPE) {
                return (byte) 0;
            }
            if (returnType == Short.TYPE) {
                return (short) 0;
            }
            if (returnType == Integer.TYPE) {
                return 0;
            }
            if (returnType == Long.TYPE) {
                return 0L;
            }
            if (returnType == Float.TYPE) {
                return 0.0F;
            }
            if (returnType == Double.TYPE) {
                return 0.0D;
            }
            if (returnType == Character.TYPE) {
                return '\0';
            }
            if (returnType == String.class) {
                return "";
            }
            if (List.class.isAssignableFrom(returnType)) {
                return Collections.emptyList();
            }
            if (returnType.isArray()) {
                return Array.newInstance(returnType.getComponentType(), 0);
            }
            if (returnType.isEnum()) {
                Object[] constants = returnType.getEnumConstants();
                return constants.length > 0 ? constants[0] : null;
            }
            if (returnType.isInterface() && returnType.getName().toLowerCase(Locale.ROOT).startsWith("oshi.")) {
                return proxy(returnType);
            }
            return null;
        }
    }
}
