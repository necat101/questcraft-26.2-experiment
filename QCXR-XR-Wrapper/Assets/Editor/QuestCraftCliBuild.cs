using System;
using System.IO;
using System.Linq;
using UnityEditor;
using UnityEditor.Build.Reporting;

public static class QuestCraftCliBuild
{
    public static void BuildAndroid()
    {
        var outputPath = GetArgument("-outputPath") ?? "Builds/QuestCraft.apk";
        var releaseBuild = HasArgument("-release");
        var outputDirectory = Path.GetDirectoryName(outputPath);
        if (!string.IsNullOrEmpty(outputDirectory))
        {
            Directory.CreateDirectory(outputDirectory);
        }

        EditorUserBuildSettings.SwitchActiveBuildTarget(BuildTargetGroup.Android, BuildTarget.Android);
        var previousAndroidBuildType = EditorUserBuildSettings.androidBuildType;
        var previousAndroidBuildSystem = EditorUserBuildSettings.androidBuildSystem;
        var previousBuildAppBundle = EditorUserBuildSettings.buildAppBundle;
        var previousUseCustomKeystore = PlayerSettings.Android.useCustomKeystore;
        var previousKeystoreName = PlayerSettings.Android.keystoreName;
        var previousKeystorePass = PlayerSettings.Android.keystorePass;
        var previousKeyaliasName = PlayerSettings.Android.keyaliasName;
        var previousKeyaliasPass = PlayerSettings.Android.keyaliasPass;

        EditorUserBuildSettings.androidBuildSystem = AndroidBuildSystem.Gradle;
        EditorUserBuildSettings.buildAppBundle = false;

        if (releaseBuild)
        {
            var keystorePath = GetArgument("-keystorePath") ?? Environment.GetEnvironmentVariable("QCXR_KEYSTORE_PATH");
            var keystorePass = GetArgument("-keystorePass") ?? Environment.GetEnvironmentVariable("QCXR_KEYSTORE_PASS");
            var keyaliasName = GetArgument("-keyaliasName")
                ?? Environment.GetEnvironmentVariable("QCXR_KEYALIAS_NAME")
                ?? "qcxr";
            var keyaliasPass = GetArgument("-keyaliasPass")
                ?? Environment.GetEnvironmentVariable("QCXR_KEYALIAS_PASS")
                ?? keystorePass;

            RequireValue(keystorePath, "Release build requires -keystorePath or QCXR_KEYSTORE_PATH.");
            RequireValue(keystorePass, "Release build requires -keystorePass or QCXR_KEYSTORE_PASS.");
            RequireValue(keyaliasName, "Release build requires -keyaliasName or QCXR_KEYALIAS_NAME.");
            RequireValue(keyaliasPass, "Release build requires -keyaliasPass or QCXR_KEYALIAS_PASS.");

            EditorUserBuildSettings.androidBuildType = AndroidBuildType.Release;
            PlayerSettings.Android.useCustomKeystore = true;
            PlayerSettings.Android.keystoreName = keystorePath;
            PlayerSettings.Android.keystorePass = keystorePass;
            PlayerSettings.Android.keyaliasName = keyaliasName;
            PlayerSettings.Android.keyaliasPass = keyaliasPass;
        }
        else
        {
            EditorUserBuildSettings.androidBuildType = AndroidBuildType.Debug;
            PlayerSettings.Android.useCustomKeystore = false;
            PlayerSettings.Android.keystoreName = string.Empty;
            PlayerSettings.Android.keystorePass = string.Empty;
            PlayerSettings.Android.keyaliasName = string.Empty;
            PlayerSettings.Android.keyaliasPass = string.Empty;
        }

        var scenes = EditorBuildSettings.scenes
            .Where(scene => scene.enabled)
            .Select(scene => scene.path)
            .ToArray();

        if (scenes.Length == 0)
        {
            throw new InvalidOperationException("No enabled scenes found in EditorBuildSettings.");
        }

        var options = new BuildPlayerOptions
        {
            scenes = scenes,
            locationPathName = outputPath,
            target = BuildTarget.Android,
            targetGroup = BuildTargetGroup.Android,
            options = BuildOptions.None
        };

        try
        {
            var report = BuildPipeline.BuildPlayer(options);
            if (report.summary.result != BuildResult.Succeeded)
            {
                throw new InvalidOperationException(
                    $"Android build failed: {report.summary.result} with {report.summary.totalErrors} errors.");
            }
        }
        finally
        {
            EditorUserBuildSettings.androidBuildType = previousAndroidBuildType;
            EditorUserBuildSettings.androidBuildSystem = previousAndroidBuildSystem;
            EditorUserBuildSettings.buildAppBundle = previousBuildAppBundle;
            PlayerSettings.Android.useCustomKeystore = previousUseCustomKeystore;
            PlayerSettings.Android.keystoreName = previousKeystoreName;
            PlayerSettings.Android.keystorePass = previousKeystorePass;
            PlayerSettings.Android.keyaliasName = previousKeyaliasName;
            PlayerSettings.Android.keyaliasPass = previousKeyaliasPass;
        }
    }

    private static string GetArgument(string name)
    {
        var args = Environment.GetCommandLineArgs();
        for (var i = 0; i < args.Length - 1; i++)
        {
            if (args[i].Equals(name, StringComparison.OrdinalIgnoreCase))
            {
                return args[i + 1];
            }
        }

        return null;
    }

    private static bool HasArgument(string name)
    {
        return Environment.GetCommandLineArgs()
            .Any(arg => arg.Equals(name, StringComparison.OrdinalIgnoreCase));
    }

    private static void RequireValue(string value, string message)
    {
        if (string.IsNullOrWhiteSpace(value))
        {
            throw new InvalidOperationException(message);
        }
    }
}
