package pojlib.util;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.os.Build;
import android.system.ErrnoException;
import android.system.Os;
import android.util.ArrayMap;
import android.util.Log;

import com.oracle.dalvik.VMLauncher;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

import pojlib.API;

import pojlib.UnityPlayerActivity;
import pojlib.install.Installer;
import pojlib.install.MinecraftMeta;
import pojlib.install.VersionInfo;
import pojlib.util.json.MinecraftInstances;

public class JREUtils {
    private JREUtils() {}

    public static String LD_LIBRARY_PATH;
    public static String jvmLibraryPath;
    private static String sNativeLibDir;
    private static String runtimeDir;

    public static String findInLdLibPath(String libName) {
        if(Os.getenv("LD_LIBRARY_PATH")==null) {
            try {
                if (LD_LIBRARY_PATH != null) {
                    Os.setenv("LD_LIBRARY_PATH", LD_LIBRARY_PATH, true);
                }
            }catch (ErrnoException e) {
                e.printStackTrace();
            }
            return libName;
        }
        for (String libPath : Os.getenv("LD_LIBRARY_PATH").split(":")) {
            File f = new File(libPath, libName);
            if (f.exists() && f.isFile()) {
                return f.getAbsolutePath();
            }
        }
        return libName;
    }

    public static boolean initJavaRuntime() {
        boolean requiredLibrariesLoaded =
                dlopen(findInLdLibPath("libjli.so")) &
                dlopen(findInLdLibPath("server/libjvm.so")) &
                dlopen(findInLdLibPath("libverify.so")) &
                dlopen(findInLdLibPath("libjava.so")) &
                dlopen(findInLdLibPath("libnet.so")) &
                dlopen(findInLdLibPath("libnio.so")) &
                dlopen(findInLdLibPath("libawt.so")) &
                dlopen(findInLdLibPath("libawt_headless.so")) &
                dlopen(findInLdLibPath("libfreetype.so")) &
                dlopen(findInLdLibPath("libfontmanager.so"));

        String dlerr = dlerror();
        if(!requiredLibrariesLoaded && dlerr != null && dlerr.contains(runtimeDir)) {
            Logger.getInstance().appendToLog("ERROR! Could not dlopen libraries! " + dlerr);
            return false;
        }

        dlopenRuntimeLibraries(new File(runtimeDir));
        return true;
    }

    private static void dlopenRuntimeLibraries(File dir) {
        File[] files = dir.listFiles();
        if(files == null) {
            return;
        }

        for(File file : files) {
            if(file.isDirectory()) {
                dlopenRuntimeLibraries(file);
            } else if(file.getName().endsWith(".so")) {
                dlopen(file.getAbsolutePath());
            }
        }
    }

    public static boolean initializeExtraNatives(MinecraftInstances.Instance instance) {
        if(instance.extraNatives == null) {
            return true;
        }

        for(String nativeLib : instance.extraNatives.split(File.pathSeparator)) {
            dlopen(nativeLib);
        }

        String dlerr = dlerror();
        if(dlerr != null && dlerr.contains(runtimeDir)) {
            Logger.getInstance().appendToLog("ERROR! Could not dlopen extra natives! " + dlerr);
            return false;
        }

        return true;
    }

    public static void redirectAndPrintJRELog() {
        Log.v("jrelog","Log starts here");
        JREUtils.logToLogger(Logger.getInstance());
        new Thread(new Runnable(){
            int failTime = 0;
            ProcessBuilder logcatPb;
            @Override
            public void run() {
                try {
                    if (logcatPb == null) {
                        logcatPb = new ProcessBuilder().command("logcat", "-v", "brief", "-s", "jrelog:I", "LIBGL:I").redirectErrorStream(true);
                    }
                            Log.i("jrelog-logcat","Clearing logcat");
                    new ProcessBuilder().command("logcat", "-c").redirectErrorStream(true).start();
                    Log.i("jrelog-logcat","Starting logcat");
                    java.lang.Process p = logcatPb.start();

                    byte[] buf = new byte[1024];
                    int len;
                    while ((len = p.getInputStream().read(buf)) != -1) {
                        String currStr = new String(buf, 0, len);
                        Logger.getInstance().appendToLog(currStr);
                    }
                            if (p.waitFor() != 0) {
                        Log.e("jrelog-logcat", "Logcat exited with code " + p.exitValue());
                        failTime++;
                        Log.i("jrelog-logcat", (failTime <= 10 ? "Restarting logcat" : "Too many restart fails") + " (attempt " + failTime + "/10");
                        if (failTime <= 10) {
                            run();
                        } else {
                            Logger.getInstance().appendToLog("ERROR: Unable to get more log.");
                        }
                            }
                } catch (Throwable e) {
                    Log.e("jrelog-logcat", "Exception on logging thread", e);
                    Logger.getInstance().appendToLog("Exception on logging thread:\n" + Log.getStackTraceString(e));
                }
            }
        }).start();
        Log.i("jrelog-logcat","Logcat thread started");
    }

    public static void relocateLibPath(final Context ctx, MinecraftInstances.Instance instance) {
        sNativeLibDir = ctx.getApplicationInfo().nativeLibraryDir;

        String javaHome = ctx.getFilesDir() + "/runtimes/JRE";
        LD_LIBRARY_PATH = javaHome + "/bin:" + javaHome + "/lib:" + javaHome + "/lib/jli:" +
                "/system/lib64:/system_ext/lib64:/vendor/lib64:/vendor/lib64/hw:" + ctx.getDataDir().toPath().resolve(instance.instanceName) + ":" +
                sNativeLibDir;
    }

    public static void setJavaEnvironment(Activity activity, MinecraftInstances.Instance instance) throws Throwable {
        File mg = new File(activity.getFilesDir() + "/mg");
        mg.mkdirs();
        File openAlConfig = new File(mg, "alsoft.conf");
        ensureOpenAlConfig(openAlConfig);
        ensureOshiLinuxPaths(activity, new File(mg, "proc"), new File(mg, "sys"), new File(mg, "dev"));

        Map<String, String> envMap = new ArrayMap<>();
        envMap.put("POJLIB_NATIVEDIR", activity.getApplicationInfo().nativeLibraryDir);
        envMap.put("JAVA_HOME", activity.getFilesDir() + "/runtimes/JRE");
        envMap.put("HOME", instance.gameDir);
        //envMap.put("APP_HOME", Constants.USER_HOME);
        envMap.put("TMPDIR", activity.getCacheDir().getAbsolutePath());
        envMap.put("VR_MODEL", API.model);
        envMap.put("POJLIB_RENDERER", "MobileGLUES");
        envMap.put("MG_DIR_PATH", mg.getAbsolutePath());
        envMap.put("POJAV_EMUI_ITERATOR_MITIGATE", "1");
        envMap.put("ALSOFT_CONF", openAlConfig.getAbsolutePath());
        envMap.put("ALSOFT_DRIVERS", "opensl,null");
        envMap.put("ALSOFT_LOGLEVEL", "0");

        envMap.put("LD_LIBRARY_PATH", LD_LIBRARY_PATH);
        envMap.put("PATH", activity.getFilesDir() + "/runtimes/JRE/bin:" + Os.getenv("PATH"));

        File customEnvFile = new File(Constants.USER_HOME, "custom_env.txt");
        if (customEnvFile.exists() && customEnvFile.isFile()) {
            BufferedReader reader = new BufferedReader(new FileReader(customEnvFile));
            String line;
            while ((line = reader.readLine()) != null) {
                // Not use split() as only split first one
                int index = line.indexOf("=");
                envMap.put(line.substring(0, index), line.substring(index + 1));
            }
            reader.close();
        }
        envMap.put("LIBGL_ES", "2");
        for (Map.Entry<String, String> env : envMap.entrySet()) {
            Logger.getInstance().appendToLog("Added custom env: " + env.getKey() + "=" + env.getValue());
            Os.setenv(env.getKey(), env.getValue(), true);
        }

        File serverFile = new File(activity.getFilesDir() + "/runtimes/JRE/lib/server/libjvm.so");
        jvmLibraryPath = activity.getFilesDir() + "/runtimes/JRE/lib/" + (serverFile.exists() ? "server" : "client");
        Log.d("DynamicLoader","Base LD_LIBRARY_PATH: "+LD_LIBRARY_PATH);
        Log.d("DynamicLoader","Internal LD_LIBRARY_PATH: "+jvmLibraryPath+":"+LD_LIBRARY_PATH);
        setLdLibraryPath(jvmLibraryPath+":"+LD_LIBRARY_PATH);
    }

    // Called before game launch to ensure all files are present and correct
    public static boolean prelaunchCheck(Activity activity, MinecraftInstances.Instance instance) throws Throwable {
        runtimeDir = activity.getFilesDir() + "/runtimes/JRE";
        JREUtils.relocateLibPath(activity, instance);
        VersionInfo versionInfo = MinecraftMeta.getVersionInfo(instance.versionName);
        Installer.installJVM(activity, false, versionInfo);
        setJavaEnvironment(activity, instance);

        UnityPlayerActivity.installLWJGL(activity);
        Installer.installClient(versionInfo, Constants.USER_HOME).get();
        Installer.installLibraries(versionInfo, Constants.USER_HOME).get();
        Installer.installAssets(versionInfo, Constants.USER_HOME).get();
        Installer.refreshLaunchCompatibilityAssets(activity, instance);

        if(!initJavaRuntime()) {
            Installer.installJVM(activity, true, versionInfo);
            setJavaEnvironment(activity, instance);
            return initJavaRuntime();
        }

        return true;
    }

    public static int launchJavaVM(final Activity activity, final List<String> JVMArgs, MinecraftInstances.Instance instance) throws Throwable {
        final String graphicsLib = loadGraphicsLibrary();
        List<String> userArgs = getJavaArgs(activity, instance);
        int javaMajor = getInstalledJavaMajor(activity);

        //Add automatically generated args
        if (API.customRAMValue) {
            Logger.getInstance().appendToLog("Setting JVM memory to " + API.memoryValue + "MB (Custom)");
            userArgs.add("-Xms" + API.memoryValue + "M");
            userArgs.add("-Xmx" + API.memoryValue + "M");
        } else {
            ActivityManager manager = (ActivityManager) activity.getSystemService(Activity.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo ami = new ActivityManager.MemoryInfo();
            manager.getMemoryInfo(ami);
            long availMem = (ami.availMem-ami.threshold)/(1024*1024);
            long allocatedRam = Math.max(availMem, 1536);

            Logger.getInstance().appendToLog("Setting JVM memory to " + allocatedRam + "MB");

            userArgs.add("-Xms" + 1024 + "M");
            userArgs.add("-Xmx" + allocatedRam + "M");
        }

        initializeExtraNatives(instance);
        preloadLaunchLibraries(activity, graphicsLib);

        if(javaMajor >= 25) {
            userArgs.add("-XX:+IgnoreUnrecognizedVMOptions");
            userArgs.add("-XX:+DisableExplicitGC");
            userArgs.add("-XX:-UsePerfData");
            userArgs.add("-XX:-UseContainerSupport");
            userArgs.add("-XX:-CreateCoredumpOnCrash");
            userArgs.add("-XX:ErrorFile=" + new File(instance.gameDir, "hs_err_pid%p.log").getAbsolutePath());
            userArgs.add("-Djdk.lang.Process.launchMechanism=FORK");
            addJava25CompatibilityArgs(userArgs);
        } else {
            userArgs.add("-XX:+UnlockExperimentalVMOptions");
            userArgs.add("-XX:+UseZGC");
            userArgs.add("-XX:+ZGenerational");
            userArgs.add("-XX:-ZProactive");
            userArgs.add("-XX:+UnlockDiagnosticVMOptions");
            userArgs.add("-XX:+DisableExplicitGC");
        }

        addLwjglCompatibilityArgs(activity, userArgs, graphicsLib);

        userArgs.addAll(JVMArgs);
        System.out.println(JVMArgs);

        if (API.currentAcc != null && !API.currentAcc.uuid.isEmpty()) {
            System.out.println("UUID: " + API.currentAcc.uuid);
        } else {
            System.out.println("UUID is null! Make sure to log in!");
        }

        chdir(instance.gameDir);
        userArgs.add(0,"java"); //argv[0] is the program name according to C standard.

        final String[] launchArgs = userArgs.toArray(new String[0]);
        Thread jvmThread = new Thread(() -> {
            int exitCode = VMLauncher.launchJVM(launchArgs);
            Logger.getInstance().appendToLog("Java Exit code: " + exitCode);
        }, "QuestCraft-JVM");
        jvmThread.start();
        return 0;
    }

    private static void preloadLaunchLibraries(Activity activity, String graphicsLib) {
        String nativeDir = activity.getApplicationInfo().nativeLibraryDir;
        dlopen(new File(nativeDir, "liblwjgl.so").getAbsolutePath());
        dlopen(new File(nativeDir, "libshaderc.so").getAbsolutePath());
        dlopen(new File(nativeDir, "libspirv-cross-c-shared.so").getAbsolutePath());
        dlopen(new File(nativeDir, "liblwjgl_vma.so").getAbsolutePath());
        dlopen(new File(nativeDir, "libopenal.so").getAbsolutePath());
        dlopen(new File(nativeDir, graphicsLib).getAbsolutePath());
        dlopen("/system/lib64/libvulkan.so");
    }

    private static int getInstalledJavaMajor(Activity activity) {
        File majorMarker = new File(activity.getFilesDir(), "runtimes/JRE/.questcraft-java-major");
        if(!majorMarker.isFile()) {
            return 0;
        }

        try (BufferedReader reader = new BufferedReader(new FileReader(majorMarker))) {
            return Integer.parseInt(reader.readLine().trim());
        } catch (IOException | NumberFormatException | NullPointerException e) {
            return 0;
        }
    }

    private static void writeDNS(Context ctx, File out) throws IOException {
        FileWriter writer = new FileWriter(out);

        if(!API.hasConnection(ctx)) {
            writer.write("nameserver 8.8.8.8\n");
            writer.write("nameserver 8.8.4.4");
            writer.flush();
            writer.close();
            return;
        }

        ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        Network activeNetwork = cm.getActiveNetwork();
        LinkProperties lp = cm.getLinkProperties(activeNetwork);
        if(lp == null) {
            throw new IOException("Link properties are null!");
        }

        List<InetAddress> dnsServers = lp.getDnsServers();
        for (InetAddress dns : dnsServers) {
            writer.write(String.format("nameserver %s\n", dns.getHostAddress()));
        }
        writer.flush();
        writer.close();
    }

    /**
     *  Gives an argument list filled with both the user args
     *  and the auto-generated ones (eg. the window resolution).
     * @param ctx The application context
     * @return A list filled with args.
     */
    public static List<String> getJavaArgs(Context ctx, MinecraftInstances.Instance instance) {
        File resConfFile = new File(Constants.USER_HOME + "/hacks/resolv.conf");
        File mgDir = new File(ctx.getFilesDir(), "mg");
        File oshiProcDir = new File(mgDir, "proc");
        File oshiSysDir = new File(mgDir, "sys");
        File oshiDevDir = new File(mgDir, "dev");
        try {
            if(!resConfFile.exists()) {
                resConfFile.createNewFile();
            }
            writeDNS(ctx, resConfFile);
        } catch (IOException e) {
            Logger.getInstance().appendToLog("Couldn't write DNS servers! " + e.getMessage());
        }
        return new ArrayList<>(Arrays.asList(
                "-Djava.home=" + new File(ctx.getFilesDir(), "runtimes/JRE"),
                "-Djava.io.tmpdir=" + ctx.getCacheDir().getAbsolutePath(),
                "-Duser.dir=" + instance.gameDir,
                "-Duser.home=" + instance.gameDir,
                "-Duser.language=" + System.getProperty("user.language"),
                "-Duser.country=" + Locale.getDefault().getCountry(),
                "-Duser.timezone=" + TimeZone.getDefault().getID(),
                "-Dstdout.encoding=UTF-8",
                "-Dstderr.encoding=UTF-8",
                "-Djava.net.preferIPv4Stack=true",
                "-Djava.net.preferIPv6Addresses=false",
                "-Dos.name=Linux",
                "-Dos.version=Android-" + Build.VERSION.RELEASE,
                "-Doshi.util.proc.path=" + oshiProcDir.getAbsolutePath(),
                "-Doshi.util.sys.path=" + oshiSysDir.getAbsolutePath(),
                "-Doshi.util.dev.path=" + oshiDevDir.getAbsolutePath(),
                "-Doshi.os.linux.allowudev=false",
                "-Doshi.os.linux.procfs.logwarning=false",
                "-Doshi.os.unix.whoCommand=true",
                "-Dorg.lwjgl.librarypath=" + ctx.getApplicationInfo().nativeLibraryDir,
                "-Djna.boot.library.path=" + ctx.getApplicationInfo().nativeLibraryDir,
                "-Djna.nosys=true",
                "-Djna.nounpack=true",
                "-Djna.tmpdir=" + ctx.getCacheDir().getAbsolutePath(),
                "-Djava.library.path=" + ctx.getApplicationInfo().nativeLibraryDir,
                "-Dglfwstub.windowWidth=" + 1280,
                "-Dglfwstub.windowHeight=" + 720,
                "-Dglfwstub.initEgl=false",
                "-Dlog4j2.formatMsgNoLookups=true", //Log4j RCE mitigation
                "-Dnet.minecraft.clientmodname=" + "QuestCraft",
                "-Dext.net.resolvPath=" + resConfFile,
                "-Dsodium.checks.issue899=false",
                "-Dsodium.checks.issue1486=false",
                "-Dsodium.checks.issue2048=false",
                "-Dsodium.checks.issue2561=false",
                "-Dsodium.checks.issue2637=false",
                "-Dorg.sqlite.lib.path=" + ctx.getApplicationInfo().nativeLibraryDir
        ));
    }

    private static void ensureOpenAlConfig(File openAlConfig) {
        try (FileWriter writer = new FileWriter(openAlConfig, false)) {
            writer.write("[general]\n");
            writer.write("drivers = opensl,null\n");
        } catch (IOException ignored) {
        }
    }

    private static void ensureOshiLinuxPaths(Activity activity, File procDir, File sysDir, File devDir) {
        try {
            procDir.mkdirs();
            sysDir.mkdirs();
            devDir.mkdirs();

            int cpuCount = Math.max(1, Runtime.getRuntime().availableProcessors());
            ActivityManager manager = (ActivityManager) activity.getSystemService(Activity.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
            manager.getMemoryInfo(memoryInfo);

            long totalKb = Math.max(memoryInfo.totalMem / 1024L, 1024L * 1024L);
            long availableKb = Math.max(memoryInfo.availMem / 1024L, 256L * 1024L);
            long usedKb = Math.max(0L, totalKb - availableKb);
            long pid = android.os.Process.myPid();

            File selfDir = new File(procDir, "self");
            File pidDir = new File(procDir, String.valueOf(pid));
            new File(selfDir, "fd").mkdirs();
            new File(selfDir, "task").mkdirs();
            new File(pidDir, "fd").mkdirs();
            new File(pidDir, "task").mkdirs();
            new File(procDir, "net").mkdirs();
            new File(procDir, "sys/fs").mkdirs();
            new File(sysDir, "devices/system/cpu").mkdirs();
            new File(sysDir, "class/net").mkdirs();

            writeTextFile(new File(procDir, "cpuinfo"), buildFakeCpuInfo(cpuCount));
            writeTextFile(new File(procDir, "meminfo"), buildFakeMemInfo(totalKb, availableKb, usedKb));
            writeTextFile(new File(procDir, "stat"), buildFakeProcStat(cpuCount));
            writeTextFile(new File(procDir, "uptime"), buildFakeUptime(cpuCount));
            writeTextFile(new File(procDir, "version"), "Linux version " + Build.VERSION.RELEASE + " (QuestCraft) #1 SMP PREEMPT\n");
            writeTextFile(new File(procDir, "vmstat"), "pgpgin 0\npgpgout 0\npswpin 0\npswpout 0\n");
            writeTextFile(new File(procDir, "diskstats"), "");
            writeTextFile(new File(procDir, "mounts"), "tmpfs / tmpfs rw,nosuid,nodev 0 0\n");
            writeTextFile(new File(procDir, "cgroups"), "");
            writeTextFile(new File(procDir, "sys/fs/file-nr"), "0\t0\t1048576\n");
            writeTextFile(new File(procDir, "sys/fs/file-max"), "1048576\n");
            writeTextFile(new File(procDir, "net/snmp"), "");
            writeTextFile(new File(procDir, "net/snmp6"), "");
            writeTextFile(new File(procDir, "net/tcp"), "");
            writeTextFile(new File(procDir, "net/tcp6"), "");
            writeTextFile(new File(procDir, "net/udp"), "");
            writeTextFile(new File(procDir, "net/udp6"), "");

            writeProcessFiles(selfDir, pid, totalKb, usedKb);
            writeProcessFiles(pidDir, pid, totalKb, usedKb);
        } catch (IOException | RuntimeException ignored) {
        }
    }

    private static void writeProcessFiles(File processDir, long pid, long totalKb, long usedKb) throws IOException {
        writeTextFile(new File(processDir, "stat"), buildFakeProcessStat(pid));
        writeTextFile(new File(processDir, "statm"), "0 0 0 0 0 0 0\n");
        writeTextFile(new File(processDir, "status"), buildFakeProcessStatus(pid, totalKb, usedKb));
        writeTextFile(new File(processDir, "cmdline"), "java\0");
        writeTextFile(new File(processDir, "comm"), "java\n");
        writeTextFile(new File(processDir, "cgroup"), "0::/\n");
        writeTextFile(new File(processDir, "mountinfo"), "");
        writeTextFile(new File(processDir, "maps"), "");
        writeTextFile(new File(processDir, "smaps"), "");
        writeTextFile(new File(processDir, "smaps_rollup"), "");
    }

    private static String buildFakeCpuInfo(int cpuCount) {
        String hardware = Build.HARDWARE == null || Build.HARDWARE.isEmpty() ? "Android ARM64" : Build.HARDWARE;
        StringBuilder builder = new StringBuilder();
        for(int i = 0; i < cpuCount; i++) {
            builder.append("processor\t: ").append(i).append('\n');
            builder.append("model name\t: ").append(hardware).append('\n');
            builder.append("BogoMIPS\t: 38.40\n");
            builder.append("Features\t: fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics\n");
            builder.append("CPU implementer\t: 0x51\n");
            builder.append("CPU architecture: 8\n");
            builder.append("CPU variant\t: 0x0\n");
            builder.append("CPU part\t: 0x000\n");
            builder.append("CPU revision\t: 0\n\n");
        }
        return builder.toString();
    }

    private static String buildFakeMemInfo(long totalKb, long availableKb, long usedKb) {
        long freeKb = Math.max(availableKb / 2L, 64L * 1024L);
        long cachedKb = Math.max(availableKb - freeKb, 0L);
        return "MemTotal:       " + totalKb + " kB\n" +
                "MemFree:        " + freeKb + " kB\n" +
                "MemAvailable:   " + availableKb + " kB\n" +
                "Buffers:        0 kB\n" +
                "Cached:         " + cachedKb + " kB\n" +
                "SwapCached:     0 kB\n" +
                "Active:         " + usedKb + " kB\n" +
                "Inactive:       " + cachedKb + " kB\n" +
                "SwapTotal:      0 kB\n" +
                "SwapFree:       0 kB\n";
    }

    private static String buildFakeProcStat(int cpuCount) {
        long uptimeSeconds = Math.max(1L, android.os.SystemClock.elapsedRealtime() / 1000L);
        long idleTicks = uptimeSeconds * 100L;
        StringBuilder builder = new StringBuilder();
        builder.append("cpu  100 0 100 ").append(idleTicks * cpuCount).append(" 0 0 0 0 0 0\n");
        for(int i = 0; i < cpuCount; i++) {
            builder.append("cpu").append(i).append(" 100 0 100 ").append(idleTicks).append(" 0 0 0 0 0 0\n");
        }
        builder.append("intr 0\n");
        builder.append("ctxt 0\n");
        builder.append("btime ").append(Math.max(1L, System.currentTimeMillis() / 1000L - uptimeSeconds)).append('\n');
        builder.append("processes 1\n");
        builder.append("procs_running 1\n");
        builder.append("procs_blocked 0\n");
        builder.append("softirq 0 0 0 0 0 0 0 0 0 0 0\n");
        return builder.toString();
    }

    private static String buildFakeUptime(int cpuCount) {
        long uptimeSeconds = Math.max(1L, android.os.SystemClock.elapsedRealtime() / 1000L);
        long idleSeconds = uptimeSeconds * Math.max(1, cpuCount);
        return uptimeSeconds + ".00 " + idleSeconds + ".00\n";
    }

    private static String buildFakeProcessStat(long pid) {
        return pid + " (java) S 0 0 0 0 0 0 0 0 0 0 0 0 0 0 20 0 1 0 1 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0\n";
    }

    private static String buildFakeProcessStatus(long pid, long totalKb, long usedKb) {
        return "Name:\tjava\n" +
                "Umask:\t0022\n" +
                "State:\tS (sleeping)\n" +
                "Tgid:\t" + pid + '\n' +
                "Pid:\t" + pid + '\n' +
                "PPid:\t0\n" +
                "Threads:\t1\n" +
                "VmPeak:\t" + totalKb + " kB\n" +
                "VmSize:\t" + totalKb + " kB\n" +
                "VmRSS:\t" + usedKb + " kB\n";
    }

    private static void writeTextFile(File file, String content) throws IOException {
        File parent = file.getParentFile();
        if(parent != null) {
            parent.mkdirs();
        }
        try (FileWriter writer = new FileWriter(file, false)) {
            writer.write(content);
        }
    }

    private static void addLwjglCompatibilityArgs(Activity activity, List<String> userArgs, String graphicsLib) {
        String nativeDir = activity.getApplicationInfo().nativeLibraryDir;
        String lwjglExtractDir = new File(activity.getCacheDir(), "lwjgl-natives").getAbsolutePath();

        userArgs.add("-Dorg.lwjgl.system.allocator=system");
        userArgs.add("-Dorg.lwjgl.glfw.checkThread0=false");
        userArgs.add("-Dorg.lwjgl.glfw.libname=libpojavexec.so");
        userArgs.add("-Dorg.lwjgl.opengl.libname=" + graphicsLib);
        userArgs.add("-Dorg.lwjgl.opengles.libname=/system/lib64/libGLESv3.so");
        userArgs.add("-Dorg.lwjgl.egl.libname=/system/lib64/libEGL.so");
        userArgs.add("-Dorg.lwjgl.vulkan.libname=/system/lib64/libvulkan.so");
        userArgs.add("-Dorg.lwjgl.shaderc.libname=libshaderc.so");
        userArgs.add("-Dorg.lwjgl.spvc.libname=spirv-cross-c-shared");
        userArgs.add("-Dorg.lwjgl.openxr.libname=libopenxr_loader.so");
        userArgs.add("-Dorg.lwjgl.openal.libname=libopenal.so");
        userArgs.add("-Dorg.lwjgl.stb.libname=liblwjgl_stb.so");
        userArgs.add("-Dorg.lwjgl.nanovg.libname=liblwjgl_nanovg.so");
        userArgs.add("-Dorg.lwjgl.tinyfd.libname=liblwjgl_tinyfd.so");
        userArgs.add("-Dorg.lwjgl.lmdb.libname=liblwjgl_lmdb.so");
        userArgs.add("-Dorg.lwjgl.openvr.libname=liblwjgl_openvr.so");
        userArgs.add("-Dorg.lwjgl.system.SharedLibraryExtractPath=" + lwjglExtractDir);
    }

    private static void addJava25CompatibilityArgs(List<String> userArgs) {
        userArgs.add("--enable-native-access=ALL-UNNAMED");
        userArgs.add("--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED");
        userArgs.add("--add-exports=java.base/sun.nio.ch=ALL-UNNAMED");
        userArgs.add("--add-exports=java.desktop/sun.awt=ALL-UNNAMED");
        userArgs.add("--add-exports=java.desktop/sun.awt.image=ALL-UNNAMED");
        userArgs.add("--add-exports=java.desktop/sun.font=ALL-UNNAMED");
        userArgs.add("--add-exports=java.desktop/sun.java2d=ALL-UNNAMED");
        userArgs.add("--add-exports=java.management/sun.management=ALL-UNNAMED");
        userArgs.add("--add-opens=java.base/java.io=ALL-UNNAMED");
        userArgs.add("--add-opens=java.base/java.lang=ALL-UNNAMED");
        userArgs.add("--add-opens=java.base/java.lang.invoke=ALL-UNNAMED");
        userArgs.add("--add-opens=java.base/java.lang.reflect=ALL-UNNAMED");
        userArgs.add("--add-opens=java.base/java.net=ALL-UNNAMED");
        userArgs.add("--add-opens=java.base/java.nio=ALL-UNNAMED");
        userArgs.add("--add-opens=java.base/java.util=ALL-UNNAMED");
        userArgs.add("--add-opens=java.base/java.util.concurrent=ALL-UNNAMED");
        userArgs.add("--add-opens=java.base/jdk.internal.loader=ALL-UNNAMED");
        userArgs.add("--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED");
        userArgs.add("--add-opens=java.desktop/java.awt=ALL-UNNAMED");
        userArgs.add("--add-opens=java.desktop/sun.awt=ALL-UNNAMED");
        userArgs.add("--add-opens=java.desktop/sun.font=ALL-UNNAMED");
        userArgs.add("--add-opens=java.desktop/sun.java2d=ALL-UNNAMED");
        userArgs.add("--add-opens=java.management/sun.management=ALL-UNNAMED");
        userArgs.add("--add-opens=jdk.unsupported/sun.misc=ALL-UNNAMED");
    }

    /**
     * Open the render library in accordance to the settings.
     * It will fallback if it fails to load the library.
     * @return The name of the loaded library
     */
    public static String loadGraphicsLibrary(){
        return "libmobileglues.so";
    }

    public static native long getEGLContextPtr();
    public static native long getEGLDisplayPtr();
    public static native long getEGLConfigPtr();
    public static native int chdir(String path);
    public static native void logToLogger(final Logger logger);
    public static native boolean dlopen(String libPath);
    public static native String dlerror();
    public static native void setLdLibraryPath(String ldLibraryPath);

    static {
        System.loadLibrary("pojavexec");
        System.loadLibrary("istdio");
    }
}
