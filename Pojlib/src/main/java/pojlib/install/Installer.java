package pojlib.install;

import android.app.Activity;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import org.apache.commons.io.FileUtils;

import pojlib.APIHandler;
import pojlib.util.download.DownloadManager;
import pojlib.util.download.DownloadUtils;
import pojlib.util.json.MinecraftInstances;
import pojlib.util.*;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

//This class reads data from a game version json and downloads its contents.
//This works for the base game as well as mod loaders
public class Installer {

    private static final int DEFAULT_JAVA_MAJOR = 22;
    private static final int JAVA_25_MAJOR = 25;
    private static final String DEFAULT_JRE_URL = "https://github.com/QuestCraftPlusPlus/android-openjdk-build-multiarch/releases/latest/download/JRE.zip";
    private static final String JAVA_25_ASSET = "runtimes/JRE-25.zip";
    private static final String JAVA_MAJOR_MARKER = ".questcraft-java-major";
    private static final String OSHI_ANDROID_SHIM = Constants.USER_HOME + "/hacks/OshiAndroidShim.jar";
    private static final String FABRIC_LOADER_26_2 = "0.19.3";
    private static final String FABRIC_LOADER_26_2_JAR = Constants.USER_HOME +
        "/libraries/net/fabricmc/fabric-loader/" + FABRIC_LOADER_26_2 +
        "/fabric-loader-" + FABRIC_LOADER_26_2 + ".jar";

    public static void installJVM(Activity activity, boolean force) {
        installJVM(activity, force, DEFAULT_JAVA_MAJOR);
    }

    public static void installJVM(Activity activity, boolean force, VersionInfo versionInfo) {
        installJVM(activity, force, getRequiredJavaMajor(versionInfo));
    }

    private static void installJVM(Activity activity, boolean force, int requiredJavaMajor) {
        Logger.getInstance().appendToLog("Checking JRE");
        File jre = new File(activity.getFilesDir(), "runtimes/JRE");
        File marker = new File(jre, JAVA_MAJOR_MARKER);

        try {
            if (!jre.exists() || force || !isInstalledJavaMajor(marker, requiredJavaMajor)) {
                Logger.getInstance().appendToLog("Installing Java " + requiredJavaMajor + " JRE");
                File runtimeRoot = new File(activity.getFilesDir(), "runtimes");
                FileUtil.ensureDirectory(runtimeRoot);

                if (jre.exists()) {
                    if (jre.isDirectory()) {
                        FileUtils.deleteDirectory(jre);
                    } else {
                        FileUtils.deleteQuietly(jre);
                    }
                }

                File jreZip = new File(runtimeRoot, requiredJavaMajor >= JAVA_25_MAJOR ? "JRE-25.zip" : "JRE.zip");
                if (requiredJavaMajor >= JAVA_25_MAJOR) {
                    byte[] java25Runtime = FileUtil.loadFromAssetToByte(activity, JAVA_25_ASSET);
                    if (java25Runtime == null) {
                        Logger.getInstance().appendToLog("Bundled Java 25 runtime is missing from APK assets!");
                        return;
                    }
                    FileUtils.writeByteArrayToFile(jreZip, java25Runtime);
                } else {
                    DownloadUtils.downloadFile(DEFAULT_JRE_URL, jreZip);
                }

                DownloadManager.reset();
                FileUtil.unzipArchive(jreZip.getPath(), jre.getPath());
                Files.copy(
                        Paths.get(activity.getApplicationInfo().nativeLibraryDir + "/libawt_xawt.so"),
                        Paths.get(activity.getFilesDir() + "/runtimes/JRE/lib/libawt_xawt.so"),
                        StandardCopyOption.REPLACE_EXISTING
                );
                FileUtils.writeStringToFile(marker, String.valueOf(requiredJavaMajor), StandardCharsets.UTF_8);
                jreZip.delete();
            }

            Logger.getInstance().appendToLog("JRE installed");
        } catch (IOException e) {
            Logger.getInstance().appendToLog("Failed to install JRE: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static int getRequiredJavaMajor(VersionInfo versionInfo) {
        if (versionInfo != null && versionInfo.javaVersion != null && versionInfo.javaVersion.majorVersion >= JAVA_25_MAJOR) {
            return JAVA_25_MAJOR;
        }
        return DEFAULT_JAVA_MAJOR;
    }

    private static boolean isInstalledJavaMajor(File marker, int requiredJavaMajor) {
        try {
            return marker.exists() && FileUtils.readFileToString(marker, StandardCharsets.UTF_8).trim().equals(String.valueOf(requiredJavaMajor));
        } catch (IOException e) {
            return false;
        }
    }

    // Will only download client if it is missing, however it will overwrite if sha1 does not match the downloaded client
    // Returns client classpath
    public static CompletableFuture<String> installClient(VersionInfo minecraftVersionInfo, String gameDir) throws IOException {
        return CompletableFuture.supplyAsync(() -> {
            Logger.getInstance().appendToLog("Checking Client");

            File clientFile = new File(gameDir + "/versions/" + minecraftVersionInfo.id + "/client.jar");

            try {
                for (int i = 0; i < 5; i++) {
                    if (i == 4)
                        throw new RuntimeException("Client download failed after 5 retries");

                    if (!clientFile.exists()) {
                        DownloadUtils.downloadFile(minecraftVersionInfo.downloads.client.url, clientFile);
                    } else if (DownloadUtils.compareSHA1(clientFile, minecraftVersionInfo.downloads.client.sha1)) {
                        clientFile.delete();
                        DownloadUtils.downloadFile(minecraftVersionInfo.downloads.client.url, clientFile);
                    }

                    // Check if the downloaded client matches the expected SHA1 hash
                    if (DownloadUtils.compareSHA1(clientFile, minecraftVersionInfo.downloads.client.sha1)) {
                        Logger.getInstance().appendToLog("Client downloaded");
                        return clientFile.getAbsolutePath();
                    }
                }
            } catch (IOException e) {
                Logger.getInstance().appendToLog("Failed to download client: " + e.getMessage());
                e.printStackTrace();
            }
            return null;
        });
    }

    // Will only download library if it is missing, however it will overwrite if sha1 does not match the downloaded library
    // Returns the classpath of the downloaded libraries
    public static CompletableFuture<String> installLibraries(VersionInfo versionInfo, String gameDir) throws IOException {
        return CompletableFuture.supplyAsync(() -> {
            Logger.getInstance().appendToLog("Checking Libraries for: " + versionInfo.id);
            StringJoiner classpath = new StringJoiner(File.pathSeparator);
            classpath.add(OSHI_ANDROID_SHIM);

            for (VersionInfo.Library library : versionInfo.libraries) {
                if (library.name.contains("lwjgl") || (library.name.contains("org.ow2.asm")) & !versionInfo.id.contains("fabric")) {
                    continue;
                }
                for (int i = 0; i < 5; i++) {
                    if (i == 4)
                        throw new RuntimeException(String.format("Library download of %s failed after 5 retries", library.name));

                    File libraryFile;
                    String sha1;

                    //Null means mod lib, otherwise vanilla lib
                    try {
                        if (library.downloads == null) {
                            String path = parseLibraryNameToPath(library.name);
                            libraryFile = new File(gameDir + "/libraries/", path);
                            sha1 = APIHandler.getRaw(library.url + path + ".sha1");
                            if (!libraryFile.exists()) {
                                Logger.getInstance().appendToLog("Downloading: " + library.name);
                                DownloadUtils.downloadFile(library.url + path, libraryFile);
                            }
                        } else {
                            VersionInfo.Library.Artifact artifact = library.downloads.artifact;
                            libraryFile = new File(gameDir + "/libraries/", artifact.path);
                            sha1 = artifact.sha1;
                            if (!libraryFile.exists()) {
                                Logger.getInstance().appendToLog("Downloading: " + library.name);
                                DownloadUtils.downloadFile(artifact.url, libraryFile, artifact.size);
                            }
                        }
                        if (DownloadUtils.compareSHA1(libraryFile, sha1)) {
                            classpath.add(libraryFile.getAbsolutePath());
                            break;
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }

            // Add our GLFW
            classpath.add(Constants.USER_HOME + "/lwjgl3/lwjgl-glfw-classes.jar");
            // DNS SRV Resolver fix
            classpath.add(Constants.USER_HOME + "/hacks/ResConfHack.jar");

            Logger.getInstance().appendToLog("Libraries installed");
            return classpath.toString();
        });
    }

    //Only works on minecraft, not fabric, quilt, etc...
    //Will only download asset if it is missing
    public static CompletableFuture<String> installAssets(VersionInfo minecraftVersionInfo, String gameDir) throws IOException {
        return CompletableFuture.supplyAsync(() -> {
            Logger.getInstance().appendToLog("Checking assets");
            JsonObject assets = APIHandler.getFullUrl(minecraftVersionInfo.assetIndex.url, JsonObject.class);

            for (Map.Entry<String, JsonElement> entry : assets.getAsJsonObject("objects").entrySet()) {
                VersionInfo.Asset asset = new Gson().fromJson(entry.getValue(), VersionInfo.Asset.class);
                DownloadManager.addTotalBytes(asset.size);
            }

            ThreadPoolExecutor tp = new ThreadPoolExecutor(8, 8, 100, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());

            for (Map.Entry<String, JsonElement> entry : assets.getAsJsonObject("objects").entrySet()) {
                AsyncDownload thread = new AsyncDownload(entry, gameDir);
                tp.execute(thread);
            }

            tp.shutdown();
            try {
                while (!tp.awaitTermination(100, TimeUnit.MILLISECONDS)) ;
            } catch (InterruptedException e) {
                Logger.getInstance().appendToLog("Download thread interrupted" + e.getMessage());
            }

            File indexJson = new File(gameDir + "/assets/indexes/" + minecraftVersionInfo.assets + ".json");
            if (!indexJson.exists()) {
                try {
                    DownloadUtils.downloadFile(minecraftVersionInfo.assetIndex.url, indexJson);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }

            return new File(gameDir + "/assets").getAbsolutePath();
        });
    }

    public static void moveLocalAssets(Activity activity, MinecraftInstances.Instance instance) throws IOException {
        try {
            FileUtils.writeByteArrayToFile(new File(instance.gameDir + "/config/sodium-options.json"), FileUtil.loadFromAssetToByte(activity, "sodium-options.json"));
            FileUtils.writeByteArrayToFile(new File(instance.gameDir + "/config/sodium-mixins.properties"), FileUtil.loadFromAssetToByte(activity, "sodium-mixins.properties"));
            FileUtils.writeByteArrayToFile(new File(instance.gameDir + "/config/smoothboot.json"), FileUtil.loadFromAssetToByte(activity, "smoothboot.json"));
            FileUtils.writeByteArrayToFile(new File(instance.gameDir + "/config/immediatelyfast.json"), FileUtil.loadFromAssetToByte(activity, "immediatelyfast.json"));
            FileUtils.writeByteArrayToFile(new File(instance.gameDir + "/config/moreculling.toml"), FileUtil.loadFromAssetToByte(activity,"moreculling.toml"));
            FileUtils.writeByteArrayToFile(new File(instance.gameDir + "/config/modernfix-mixins.properties"), FileUtil.loadFromAssetToByte(activity,"modernfix-mixins.properties"));
            FileUtils.writeByteArrayToFile(new File(instance.gameDir + "/options.txt"), FileUtil.loadFromAssetToByte(activity, "options.txt"));
            FileUtils.writeByteArrayToFile(new File(instance.gameDir + "/servers.dat"), FileUtil.loadFromAssetToByte(activity, "servers.dat"));
            FileUtils.writeByteArrayToFile(new File(instance.gameDir + "/config/vivecraft-client-config.json"), FileUtil.loadFromAssetToByte(activity, "vivecraft-client-config.json"));
            FileUtils.writeByteArrayToFile(new File(Constants.USER_HOME + "/hacks/ResConfHack.jar"), FileUtil.loadFromAssetToByte(activity, "hacks/ResConfHack.jar"));
            FileUtils.writeByteArrayToFile(new File(Constants.USER_HOME + "/hacks/OshiAndroidShim.jar"), FileUtil.loadFromAssetToByte(activity, "hacks/OshiAndroidShim.jar"));
            FileUtils.writeByteArrayToFile(new File(Constants.USER_HOME + "/hacks/resolv.conf"), FileUtil.loadFromAssetToByte(activity, "hacks/resolv.conf"));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void refreshLaunchCompatibilityAssets(Activity activity, MinecraftInstances.Instance instance) {
        if(!"26.2".equals(instance.versionName)) {
            return;
        }

        try {
            FileUtils.writeByteArrayToFile(new File(instance.gameDir + "/config/sodium-options.json"), FileUtil.loadFromAssetToByte(activity, "sodium-options.json"));
            FileUtils.writeByteArrayToFile(new File(instance.gameDir + "/config/sodium-mixins.properties"), FileUtil.loadFromAssetToByte(activity, "sodium-mixins.properties"));
            FileUtils.writeByteArrayToFile(new File(instance.gameDir + "/options.txt"), FileUtil.loadFromAssetToByte(activity, "options.txt"));
            FileUtils.writeByteArrayToFile(new File(Constants.USER_HOME + "/hacks/OshiAndroidShim.jar"), FileUtil.loadFromAssetToByte(activity, "hacks/OshiAndroidShim.jar"));
            refreshFabricLoaderForCurrentLaunch(activity, instance);
            ensureClasspathEntryFirst(instance, OSHI_ANDROID_SHIM);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void refreshFabricLoaderForCurrentLaunch(Activity activity, MinecraftInstances.Instance instance) {
        File installedLoader = new File(FABRIC_LOADER_26_2_JAR);
        if(installedLoader.isFile()) {
            ensureClasspathEntryFirst(instance, installedLoader.getAbsolutePath());
        }

        try {
            FabricMeta.FabricVersion fabricVersion = FabricMeta.getVersion(FABRIC_LOADER_26_2);
            if(fabricVersion == null) {
                return;
            }

            VersionInfo fabricVersionInfo = FabricMeta.getVersionInfo(fabricVersion, instance.versionName);
            if(fabricVersionInfo == null) {
                return;
            }

            instance.mainClass = fabricVersionInfo.mainClass;
            String fabricClasspath = installLibraries(fabricVersionInfo, Constants.USER_HOME).get();
            ensureClasspathEntriesFirst(instance, fabricClasspath);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void ensureClasspathEntriesFirst(MinecraftInstances.Instance instance, String classpath) {
        if(classpath == null || classpath.isEmpty()) {
            return;
        }

        String[] entries = classpath.split(java.util.regex.Pattern.quote(File.pathSeparator));
        for(int i = entries.length - 1; i >= 0; i--) {
            ensureClasspathEntryFirst(instance, entries[i]);
        }
    }

    private static void ensureClasspathEntryFirst(MinecraftInstances.Instance instance, String entry) {
        if(entry == null || entry.isEmpty()) {
            return;
        }

        if(instance.classpath == null || instance.classpath.isEmpty()) {
            instance.classpath = entry;
            return;
        }

        String[] entries = instance.classpath.split(java.util.regex.Pattern.quote(File.pathSeparator));
        List<String> repaired = new ArrayList<>();
        repaired.add(entry);
        for(String current : entries) {
            if(current == null || current.isEmpty() || entry.equals(current) || replacesClasspathEntry(entry, current)) {
                continue;
            }
            repaired.add(current);
        }
        instance.classpath = String.join(File.pathSeparator, repaired);
    }

    private static boolean replacesClasspathEntry(String entry, String current) {
        if(isFabricLoaderJar(entry) && isFabricLoaderJar(current)) {
            return true;
        }

        String entryArtifact = mavenArtifactKey(entry);
        String currentArtifact = mavenArtifactKey(current);
        return entryArtifact != null && entryArtifact.equals(currentArtifact);
    }

    private static String mavenArtifactKey(String path) {
        if(path == null || path.isEmpty()) {
            return null;
        }

        String normalized = path.replace('\\', '/');
        String marker = "/libraries/";
        int markerIndex = normalized.lastIndexOf(marker);
        if(markerIndex < 0) {
            return null;
        }

        String[] parts = normalized.substring(markerIndex + marker.length()).split("/");
        if(parts.length < 4) {
            return null;
        }

        StringBuilder key = new StringBuilder();
        for(int i = 0; i < parts.length - 2; i++) {
            if(i > 0) {
                key.append('/');
            }
            key.append(parts[i]);
        }
        return key.toString().toLowerCase(java.util.Locale.ROOT);
    }

    private static boolean isFabricLoaderJar(String path) {
        if(path == null || path.isEmpty()) {
            return false;
        }

        String fileName = new File(path).getName().toLowerCase();
        return fileName.startsWith("fabric-loader-") && fileName.endsWith(".jar");
    }

    public static class AsyncDownload implements Runnable {
        private final Map.Entry<String, JsonElement> entry;
        private final String gameDir;
        private final String fileName;

        public AsyncDownload(Map.Entry<String, JsonElement> entry, String gameDir) {
            this.entry = entry;
            this.gameDir = gameDir;
            this.fileName = entry.getKey();
        }

        @Override
        public void run() {
            VersionInfo.Asset asset = new Gson().fromJson(entry.getValue(), VersionInfo.Asset.class);
            String path = asset.hash.substring(0, 2) + "/" + asset.hash;
            File assetFile = new File(gameDir + "/assets/objects/", path);

            for (int i = 0; i < 5; i++) {
                if (i == 4) throw new RuntimeException(String.format("Asset download of %s failed after 5 retries", fileName));

                if (!assetFile.exists()) {
                    Logger.getInstance().appendToLog("Downloading: " + fileName);
                    try {
                        DownloadUtils.downloadFile(Constants.MOJANG_RESOURCES_URL + "/" + path, assetFile, 0);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }

                if (DownloadUtils.compareSHA1(assetFile, asset.hash)) {
                    break;
                } else {
                    assetFile.delete();
                }
            }
        }
    }


    //Used for mod libraries, vanilla is handled a different (tbh better) way
    private static String parseLibraryNameToPath(String libraryName) {
        String[] parts = libraryName.split(":");
        String location = parts[0].replace(".", "/");
        String name = parts[1];
        String version = parts[2];

        return String.format("%s/%s/%s/%s", location, name, version, name + "-" + version + ".jar");
    }
}
