package pojlib.util.json;

import android.content.Context;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import pojlib.account.MinecraftAccount;
import pojlib.API;
import pojlib.APIHandler;
import pojlib.InstanceHandler;
import pojlib.util.Constants;
import pojlib.util.FileUtil;
import pojlib.util.download.DownloadManager;
import pojlib.util.download.DownloadUtils;
import pojlib.util.GsonUtils;
import pojlib.util.Logger;

public class MinecraftInstances {
    public Instance[] instances;

    public Instance load(String name) {
        for(Instance instance : instances) {
            if(instance.instanceName.equals(name)) {
                return instance;
            }
        }

        return null;
    }

    public Instance[] toArray() {
        if(instances == null) {
            return new Instance[0];
        }
        return instances;
    }

    public static void CheckVivecraftConfig(MinecraftInstances.Instance instance) {
        File config = new File(instance.gameDir + "/config/vivecraft-client-config.json");
        if (!config.exists()) {
            Logger.getInstance().appendToLog("Vivecraft config not found, skipping modification");
            return;
        }

        try {
            Logger.getInstance().appendToLog("Modifying Vivecraft config for QuestCraft");
            JsonObject obj = GsonUtils.jsonFileToObject(config.getAbsolutePath(), JsonObject.class);

            obj.addProperty("stereoProviderPluginID", "OPENXR");
            obj.addProperty("alwaysShowUpdates", "false");
            obj.addProperty("vrEnabled", "true");
            obj.addProperty("vrToggleButtonEnabled", "false");
            obj.addProperty("disableGarbageCollectorMessage", "true");
            obj.addProperty("vrHotswitchingEnabled", "false");
            obj.addProperty("seated", "false");

            GsonUtils.objectToJsonFile(config.getAbsolutePath(), obj);
        } catch (Exception e) {
            Logger.getInstance().appendToLog("Failed to modify Vivecraft config");
            e.printStackTrace();
        }
    }

    public static class Instance {
        public String instanceName;
        public String instanceImageURL;
        public String versionName;
        public String versionType;
        public String classpath;
        public String gameDir;
        public String assetIndex;
        public String assetsDir;
        public String mainClass;
        public String extraNatives;
        public ProjectInfo[] extProjects;
        public boolean defaultMods;

        public List<String> generateLaunchArgs(MinecraftAccount account) {
            String[] mcArgs = {"--username", account.username, "--version", versionName, "--gameDir", gameDir,
                    "--assetsDir", assetsDir, "--assetIndex", assetIndex, "--uuid", account.uuid.replace("-", ""),
                    "--accessToken", account.accessToken, "--userType", account.userType, "--versionType", "release"};

            List<String> allArgs = new ArrayList<>();
            allArgs.add("-cp");
            allArgs.add(classpath);
            allArgs.add(mainClass);
            allArgs.addAll(Arrays.asList(mcArgs));
            if ("26.2".equals(versionName)) {
                allArgs.add("--graphicsBackend");
                allArgs.add("vulkan");
            }
            if (account.isDemoMode) {
                allArgs.add("--demo");
            }

            return allArgs;
        }

        public ProjectInfo[] toArray() {
            if(extProjects == null) {
                return new ProjectInfo[0];
            }
            return extProjects;
        }

        public ModsJson parseModsJson(String jsonPath) {
            return GsonUtils.jsonFileToObject(jsonPath, ModsJson.class);
        }

        private ModsJson downloadCurrentModsJson(Context ctx, String userHome) throws Exception {
            File mods = new File(userHome + "/new_mods.json");
            String bundledManifest = API.developerMods ? "devmods.json" : "mods.json";
            if(APIHandler.copyBundledManifest(ctx, bundledManifest, mods)) {
                Logger.getInstance().appendToLog("Using bundled " + bundledManifest + ".");
                return parseModsJson(mods.getAbsolutePath());
            }

            if(API.developerMods) {
                DownloadUtils.downloadFile(InstanceHandler.DEV_MODS, mods);
            } else {
                DownloadUtils.downloadFile(InstanceHandler.MODS, mods);
            }

            return parseModsJson(mods.getAbsolutePath());
        }

        private void removeModByType(List<ProjectInfo> oldMods, List<ProjectInfo> newMods) {
            ArrayList<ProjectInfo> removedMods = new ArrayList<>();

            for(ProjectInfo oldMod : oldMods) {
                boolean exists = false;
                for(ProjectInfo newMod : newMods) {
                    if(!oldMod.slug.equals(newMod.slug)) {
                        continue;
                    }
                    exists = true;
                    break;
                }

                if(!exists) {
                    removedMods.add(oldMod);
                }
            }

            ArrayList<ProjectInfo> newExtProjects = new ArrayList<>();
            for(ProjectInfo extProject : extProjects) {
                boolean remove = false;
                for(ProjectInfo removedMod : removedMods) {
                    if (extProject.slug.equals(removedMod.slug)) {
                        remove = true;
                        break;
                    }
                }
                if(!remove) {
                    newExtProjects.add(extProject);
                } else {
                    boolean legacyMod = extProject.fileName == null;
                    File mod = new File(
                            gameDir + (extProject.type.equals("mod") ? "/mods" : "/resourcepacks"),
                            (legacyMod ? extProject.slug : extProject.fileName) + (extProject.type.equals("resourcepack") ? ".zip" : ".jar")
                    );
                    if(mod.exists()) {
                        mod.delete();
                    }
                }
            }
            extProjects = newExtProjects.toArray(new ProjectInfo[0]);
        }

        private void removeOldMods(ModsJson oldMods, ModsJson newMods) {
            for(ModsJson.Version oldVersion : oldMods.versions) {
                if(!versionName.equals(oldVersion.name)) {
                    continue;
                }

                for(ModsJson.Version newVersion : newMods.versions) {
                    if(!versionName.equals(newVersion.name)) {
                        continue;
                    }

                    ArrayList<ProjectInfo> mergedOldMods = new ArrayList<>();
                    mergedOldMods.addAll(Arrays.asList(oldVersion.coreMods));
                    mergedOldMods.addAll(Arrays.asList(oldVersion.defaultMods));

                    ArrayList<ProjectInfo> mergedNewMods = new ArrayList<>();
                    mergedNewMods.addAll(Arrays.asList(newVersion.coreMods));
                    mergedNewMods.addAll(Arrays.asList(newVersion.defaultMods));

                    if(defaultMods) {
                        removeModByType(mergedOldMods, mergedNewMods);
                    } else {
                        removeModByType(Arrays.asList(oldVersion.coreMods), Arrays.asList(newVersion.coreMods));
                    }
                    break;
                }
            }
        }

        private void installProject(Context ctx, ProjectInfo project, File destination, boolean unmanagedDownload) throws IOException {
            if(project.download_link.startsWith("asset://")) {
                File parent = destination.getParentFile();
                if(parent != null) {
                    parent.mkdirs();
                }
                Files.write(destination.toPath(), FileUtil.loadFromAssetToByte(
                        ctx, project.download_link.substring("asset://".length())));
                return;
            }

            if(unmanagedDownload) {
                DownloadUtils.downloadFile(project.download_link, destination, 0);
            } else {
                DownloadUtils.downloadFile(project.download_link, destination);
            }
        }

        private void updateModByType(Context ctx, List<ProjectInfo> newMods) throws IOException {
            ArrayList<ProjectInfo> newExtMods = new ArrayList<>();
            for(ProjectInfo extMod : extProjects) {
                boolean manual = true;
                for(ProjectInfo newMod : newMods) {
                    if(!extMod.slug.equals(newMod.slug)) {
                        continue;
                    }
                    manual = false;
                    boolean legacyMod = newMod.fileName == null;
                    File mod = new File(
                            gameDir + (newMod.type.equals("mod") ? "/mods" : "/resourcepacks"),
                            (legacyMod ? newMod.slug : newMod.fileName) + (newMod.type.equals("resourcepack") ? ".zip" : ".jar")
                    );
                    // Bundled projects are part of the APK itself. During local QuestCraft
                    // development their bytes can change without the public mod version
                    // changing, so a version-only check leaves the previously extracted JAR
                    // in Android app storage forever. Always refresh asset:// projects from
                    // the currently installed APK; remote projects keep the normal version
                    // check so they are not downloaded unnecessarily.
                    boolean bundledProject = newMod.download_link.startsWith("asset://");
                    if(bundledProject || !mod.exists() || !extMod.version.equals(newMod.version)) {
                        installProject(ctx, newMod, mod, false);
                        extMod = newMod;
                        break;
                    }
                }
                if(manual) {
                    boolean legacyMod = extMod.fileName == null;
                    File mod = new File(
                            gameDir + (extMod.type.equals("mod") ? "/mods" : "/resourcepacks"),
                            (legacyMod ? extMod.slug : extMod.fileName) + (extMod.type.equals("resourcepack") ? ".zip" : ".jar")
                    );
                    if(!mod.exists()) {
                        installProject(ctx, extMod, mod, true);
                    }
                }
                newExtMods.add(extMod);
            }

            extProjects = newExtMods.toArray(new ProjectInfo[0]);
        }

        private void downloadAllMods(Context ctx, List<ProjectInfo> newMods) throws IOException {
            for(ProjectInfo newMod : newMods) {
                boolean legacyMod = newMod.fileName == null;
                File mod = new File(
                        gameDir + (newMod.type.equals("mod") ? "/mods" : "/resourcepacks"),
                        (legacyMod ? newMod.slug : newMod.fileName) + (newMod.type.equals("resourcepack") ? ".zip" : ".jar")
                );
                installProject(ctx, newMod, mod, true);
            }

            extProjects = newMods.toArray(new ProjectInfo[0]);
        }

        public void updateMods(Context ctx, MinecraftInstances instances) {
            if(extProjects == null) {
                extProjects = new ProjectInfo[0];
            }
            try {
                ModsJson newMods = downloadCurrentModsJson(ctx, Constants.USER_HOME);
                ModsJson oldMods = parseModsJson(Constants.USER_HOME + "/mods.json");

                if(oldMods != null) {
                    removeOldMods(oldMods, newMods);
                }

                File modsFolder = new File(gameDir + "/mods");
                for (ModsJson.Version newVersion : newMods.versions) {
                    if (!versionName.equals(newVersion.name)) {
                        continue;
                    }

                    ArrayList<ProjectInfo> mergedNewMods = new ArrayList<>(Arrays.asList(newVersion.coreMods));
                    if(defaultMods)
                        mergedNewMods.addAll(Arrays.asList(newVersion.defaultMods));

                    if(extProjects.length == 0 || !modsFolder.exists())
                        downloadAllMods(ctx, mergedNewMods);
                    else
                        updateModByType(ctx, mergedNewMods);
                    break;
                }

                GsonUtils.objectToJsonFile(Constants.USER_HOME + "/instances.json", instances);
                File newModsFile = new File(Constants.USER_HOME + "/new_mods.json");
                File modsFile = new File(Constants.USER_HOME + "/mods.json");

                modsFile.delete();
                Files.copy(newModsFile.toPath(), modsFile.toPath());
                newModsFile.delete();
            } catch (Exception e) {
                Logger.getInstance().appendToLog("Mods failed to download! Are you offline?\n" + e);
            }
        }
    }
}
