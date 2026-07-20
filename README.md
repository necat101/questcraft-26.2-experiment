# Welcome to the QuestCraft GitHub!

### **[Support QuestCraft on Patreon](https://patreon.com/QuestCraftXR)**

# QuestCraft

![QuestCraft](/QCSimple3.jpg)

QuestCraft is a standalone port of Minecraft: Java Edition for Meta Quest, Pico, and other Android-based VR headsets.

QuestCraft combines a customized version of [Vivecraft](https://github.com/Vivecraft/VivecraftMod), Pojlib, Android-native rendering libraries, and a Unity launcher to bring room-scale Minecraft VR to standalone hardware.

This repository contains an experimental development branch targeting **Minecraft 26.2**, including updated Vivecraft integration and an experimental **Vulkan-capable rendering path**.

> [!WARNING]
> Minecraft 26.2 and Vulkan support are currently experimental. Crashes, visual errors, mod incompatibilities, regressions, and device-specific issues should be expected while development continues.

# Features

- **Room-scale Minecraft VR**
- **Native Meta Quest 2, Quest 3, and Quest 3S support**
- **Pico Neo 3, Pico 4, Pico 4 Ultra, and other compatible standalone headsets**
- **Full multiplayer support**
- **Immersive Vivecraft controls**
- **Standalone operation without a PC after installation**
- **Experimental Minecraft 26.2 support**
- **Experimental Vulkan-capable renderer**
- **OpenGL compatibility through the Light Thin Wrapper renderer**
- **Instance management**
- **Automatic installation and updating of required Minecraft components**
- **Built-in mod, modpack, and resource-pack management**
- **Per-instance renderer and mod configuration**

Supported Minecraft versions vary between stable QuestCraft releases. This development branch specifically focuses on bringing the QuestCraft stack to **Minecraft 26.2**.

# Minecraft 26.2 Support

The 26.2 port updates the complete QuestCraft software stack rather than only changing the Minecraft version number.

The port includes work across:

- `Vivecraft-26.2-Quest/` — the Quest-specific Vivecraft 26.2 fork.
- `Pojlib/` — the Android launcher and Java runtime integration layer.
- `QCXR-XR-Wrapper/` — the Unity-based standalone launcher and XR environment.
- OpenXR input, rendering, haptics, and stereo-rendering integration.
- Updated Fabric, Fabric API, Minecraft, and optimization-mod compatibility.
- Java 25 runtime packaging for Minecraft 26.2.
- Updated native LWJGL and Android compatibility components.
- Vulkan and OpenGL rendering-path selection.

Because 26.2 requires substantial changes to rendering, runtime handling, native libraries, and Vivecraft internals, mods intended for older Minecraft versions will not automatically be compatible.

## Current 26.2 Limitations

The following areas may still be incomplete or unstable:

- Some optimization mods and rendering mixins.
- Shader-pack compatibility.
- Vulkan behavior on different Android GPU drivers.
- Mods that directly modify Minecraft's OpenGL state.
- Controller bindings not yet updated for 26.2.
- OpenXR extensions that are unavailable on certain headsets.
- Runtime memory use on lower-memory devices.
- First-launch setup and dependency downloading.
- Version-specific server and mod compatibility.

When reporting a 26.2 issue, include:

- Headset model.
- QuestCraft commit or build number.
- Selected renderer.
- Minecraft version.
- Enabled mods.
- `latestlog.txt`.
- Android logcat output, when available.
- Exact steps required to reproduce the problem.

# Installation Instructions

> [!IMPORTANT]
> QuestCraft requires an internet connection during initial setup. You must also own a legal copy of Minecraft: Java Edition.

Stable builds can be downloaded from the official **[QuestCraft releases page](https://github.com/QuestCraftPlusPlus/QuestCraft/releases/latest)**.

Experimental 26.2 builds may be published separately and should not be treated as stable releases.

## Installing the APK

1. Download the desired QuestCraft APK.
2. Install **[SideQuest](https://sidequestvr.com/setup-howto)**.
3. Enable developer mode on your headset.
4. Connect the headset to your computer or Android device.
5. Use SideQuest to install the APK.
6. Open QuestCraft from the headset's **Unknown Sources** application list.
7. Grant the requested storage and network permissions.
8. Sign in with the Microsoft account that owns Minecraft: Java Edition.
9. Create or select an instance.
10. Press **Play** and allow the required runtime, libraries, mods, and Minecraft files to download.

Initial setup can take approximately ten minutes depending on network speed and headset performance. Do not close QuestCraft or remove the headset while the initial files are being installed.

After installation, press **Play** and wait for Minecraft to launch. Startup normally takes between one and four minutes with the default mod configuration, although experimental 26.2 builds may take longer.

# Supported Renderers

QuestCraft provides multiple renderer paths because Android VR devices do not expose the same desktop OpenGL environment expected by Minecraft Java Edition.

## Light Thin Wrapper

Light Thin Wrapper is the established compatibility renderer used by QuestCraft and Pojlib.

It provides an OpenGL-compatible translation layer for running Java Edition on Android devices.

Use Light Thin Wrapper when:

- Vulkan produces a black screen or immediate crash.
- A mod requires traditional OpenGL behavior.
- The device has incomplete Vulkan driver support.
- Stability is more important than testing the experimental renderer.

Light Thin Wrapper remains the recommended fallback renderer.

## Experimental Vulkan Support

The 26.2 development branch introduces an experimental Vulkan-capable rendering path.

Depending on the selected backend and device, OpenGL calls may be bridged or translated through Vulkan-compatible native components. This does not mean Minecraft's renderer has been completely rewritten in Vulkan.

Potential advantages include:

- Reduced dependency on Android OpenGL compatibility behavior.
- Improved compatibility with newer rendering paths.
- Better access to modern GPU functionality.
- Potential performance improvements on supported hardware.
- A foundation for future Vulkan-native QuestCraft work.

Potential issues include:

- Black screens.
- Missing geometry.
- Incorrect textures.
- Broken transparency.
- Shader compilation failures.
- Graphical corruption.
- Device-specific crashes.
- Incompatibility with shader packs.
- Incompatibility with mods that directly manipulate OpenGL state.
- Different behavior between Adreno and other mobile GPUs.

## Selecting Vulkan

When a build exposes renderer selection:

1. Open the QuestCraft launcher.
2. Select or create a Minecraft instance.
3. Open the instance's renderer or advanced graphics settings.
4. Select the Vulkan or Vulkan-backed renderer.
5. Save the instance configuration.
6. Fully restart the instance before launching Minecraft.

The exact renderer label may change while development continues.

If the game crashes or displays a black screen:

1. Return to the launcher.
2. Switch the instance back to Light Thin Wrapper.
3. Disable shader packs and renderer-related mods.
4. Restart QuestCraft.
5. Collect the game log before reporting the Vulkan issue.

# Included Mods

QuestCraft includes a curated set of optimization and compatibility mods.

The active list is stored in:

```text
Pojlib/src/main/assets/mods.json
```

Some builds may also use:

```text
Pojlib/mods.json
Pojlib/src/main/assets/devmods.json
```

The included mod set can differ between Minecraft versions because mods must explicitly support the selected Minecraft and Fabric versions.

# Mod Issues

Did Minecraft crash after installing or enabling a mod? Use one of the following methods to identify the cause.

## Easy Method

Join the QuestCraft **[Discord server](https://discord.gg/questcraft)** and open the bot-command or support area.

Use the launcher's **Need Help?** option to upload the log, then provide the resulting log link when requesting assistance.

## Manual Method

Locate `latestlog.txt` using an Android file manager.

QuestCraft data is normally stored under:

```text
Android/data/com.qcxr.qcxr/
```

Open the log and inspect the final error messages. Fabric often reports the incompatible or crashing mod near the bottom of the file.

The **Need Help?** button may also generate an `mclo.gs` link that can be shared with support staff.

Preinstalled mods can generally be toggled, except for components required to start QuestCraft, such as Vivecraft and Fabric API.

# Recommended Settings and Tips

1. Open **VR Settings → Stereo Rendering** and set the render resolution to approximately 80%. This is separate from camera resolution.
2. Start with a render distance of four to six chunks.
3. Avoid render distances above nine chunks on standalone headsets.
4. Disable shader packs while troubleshooting.
5. Use the Light Thin Wrapper renderer when testing mod compatibility.
6. Test Vulkan without additional rendering mods before enabling a full mod set.
7. Skyblock and Oneblock worlds generally perform well because they require fewer visible chunks and entities.
8. Keep sufficient free storage available for the Java runtime, Minecraft assets, mods, and logs.

# Compiling QuestCraft

These instructions describe the current Windows development workflow for the experimental 26.2 branch.

QuestCraft consists of three primary source trees:

```text
QuestCraft/
├── Pojlib/
├── QCXR-XR-Wrapper/
└── Vivecraft-26.2-Quest/
```

The normal build order is:

1. Build the Vivecraft 26.2 mod.
2. Package the resulting mod into Pojlib.
3. Build the Pojlib Android AAR.
4. Copy the AAR into the Unity project.
5. Build the QuestCraft APK from Unity.

## Prerequisites

Install the following software:

- Git for Windows.
- Git LFS.
- Unity Hub.
- Unity **2022.3.62f3**.
- Unity Android Build Support.
- Android SDK.
- Android NDK.
- OpenJDK supplied with Unity Android Build Support.
- JDK 25 for the Minecraft 26.2 and Vivecraft build.
- PowerShell 5.1 or PowerShell 7.

When installing Android support through Unity Hub, include:

- Android SDK and NDK Tools.
- OpenJDK.
- Android Build Support.

## Clone the Repository

```powershell
git lfs install

git clone https://github.com/necat101/questcraft-26.2-experiment.git
cd questcraft-26.2-experiment

git lfs pull
git submodule update --init --recursive
```

Git LFS is required because some runtime, native-library, model, and Unity asset files are stored outside normal Git object storage.

## Verify the Source Trees

```powershell
Get-ChildItem Pojlib
Get-ChildItem QCXR-XR-Wrapper
Get-ChildItem Vivecraft-26.2-Quest
```

Each directory should contain its full source tree rather than only a Git submodule pointer.

## Configure Java and Android Tools

For the Android and Unity stages, the Unity-bundled OpenJDK can be selected with:

```powershell
$env:JAVA_HOME = "C:\Program Files\Unity\Hub\Editor\2022.3.62f3\Editor\Data\PlaybackEngines\AndroidPlayer\OpenJDK"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
```

Configure the Android SDK location:

```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
```

Confirm the tools:

```powershell
java -version
adb version
```

The Vivecraft 26.2 build may require JDK 25 rather than Unity's bundled JDK. Set `JAVA_HOME` to JDK 25 before running the Vivecraft Gradle build.

Example:

```powershell
$env:JAVA_HOME = "C:\Path\To\jdk-25"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"

java -version
```

Do not assume that one Java version will work for every build stage.

# Building Vivecraft 26.2

From the repository root:

```powershell
Push-Location ".\Vivecraft-26.2-Quest"

.\gradlew.bat --no-daemon clean build

Pop-Location
```

Build artifacts are normally created under one or more of these directories:

```text
Vivecraft-26.2-Quest/fabric/build/libs/
Vivecraft-26.2-Quest/common/build/libs/
Vivecraft-26.2-Quest/build/libs/
```

List the resulting JAR files:

```powershell
Get-ChildItem ".\Vivecraft-26.2-Quest" -Recurse -Filter "*.jar" |
    Where-Object {
        $_.FullName -match "\\build\\libs\\" -and
        $_.Name -notmatch "sources|javadoc|dev"
    }
```

Use the Fabric release artifact intended for QuestCraft.

## Packaging Vivecraft into Pojlib

Inspect the launcher mod manifest:

```powershell
Get-Content ".\Pojlib\src\main\assets\mods.json"
```

Replace the previously bundled Vivecraft artifact with the newly built 26.2 artifact.

The currently bundled file may use a `.jar`, `.bin`, or version-specific filename. Keep the filename expected by `mods.json`, or update the manifest to match the new filename.

The packaged artifact normally belongs under:

```text
Pojlib/src/main/assets/mods/
```

After replacing it, verify that the manifest and bundled file agree.

# Building Pojlib

Switch back to the Java version required by the Android Gradle project. For the current Unity Android environment:

```powershell
$env:JAVA_HOME = "C:\Program Files\Unity\Hub\Editor\2022.3.62f3\Editor\Data\PlaybackEngines\AndroidPlayer\OpenJDK"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
```

Build the release AAR:

```powershell
Push-Location ".\Pojlib"

.\gradlew.bat --no-daemon clean
.\gradlew.bat --no-daemon assembleRelease

Pop-Location
```

The resulting AAR should normally appear under:

```text
Pojlib/build/outputs/aar/
```

Locate it with:

```powershell
Get-ChildItem ".\Pojlib\build\outputs\aar" -Filter "*release*.aar"
```

## Copy Pojlib into the Unity Project

```powershell
$PojlibAar = Get-ChildItem `
    ".\Pojlib\build\outputs\aar" `
    -Filter "*release*.aar" |
    Select-Object -First 1

if (-not $PojlibAar) {
    throw "No release AAR was found."
}

Copy-Item `
    $PojlibAar.FullName `
    ".\QCXR-XR-Wrapper\Assets\Plugins\Android\Pojlib-release.aar" `
    -Force
```

Do not remove the corresponding Unity `.meta` file.

# Building the Unity Launcher

## Unity Editor Method

1. Open Unity Hub.
2. Add the `QCXR-XR-Wrapper` directory as an existing project.
3. Open it using Unity `2022.3.62f3`.
4. Allow Unity to import all assets and packages.
5. Open **File → Build Settings**.
6. Select **Android**.
7. Press **Switch Platform** if Android is not already selected.
8. Confirm the required scenes are included.
9. Confirm ARM64 is enabled.
10. Build the APK.

The first Unity import can take a significant amount of time.

## Headless Unity Build

The repository includes a command-line build script under:

```text
QCXR-XR-Wrapper/Assets/Editor/QuestCraftCliBuild.cs
```

A typical headless build command is:

```powershell
$Unity = "C:\Program Files\Unity\Hub\Editor\2022.3.62f3\Editor\Unity.exe"
$Project = Join-Path $PWD "QCXR-XR-Wrapper"
$Log = Join-Path $PWD "unity-build.log"

& $Unity `
    -batchmode `
    -nographics `
    -quit `
    -projectPath $Project `
    -executeMethod QuestCraftCliBuild.BuildAndroid `
    -logFile $Log

if ($LASTEXITCODE -ne 0) {
    throw "Unity build failed. Check $Log"
}
```

If the method name changes, inspect:

```text
QCXR-XR-Wrapper/Assets/Editor/QuestCraftCliBuild.cs
```

and pass the public static build method declared in that file to `-executeMethod`.

Monitor the log:

```powershell
Get-Content ".\unity-build.log" -Wait
```

The output APK path is controlled by the Unity build script or the output selected in Unity's Build Settings.

# Vulkan Build Notes

The Vulkan path depends on native binaries and runtime assets included by Pojlib and the Unity project.

Before building, verify that the required native libraries have not been removed:

```text
Pojlib/src/main/jni/
Pojlib/src/main/jniLibs/arm64-v8a/
QCXR-XR-Wrapper/Assets/Plugins/Android/
```

Depending on the current branch revision, relevant files may include:

- OpenXR loader libraries.
- LWJGL native libraries.
- Vulkan memory-allocation libraries.
- Shader compiler libraries.
- SPIR-V translation libraries.
- Vulkan-backed renderer components.

The Vulkan implementation is built as part of the normal Pojlib and Unity build process. It does not normally require a separate final APK build command.

When changing native renderer code, perform a clean Pojlib build before rebuilding Unity:

```powershell
Push-Location ".\Pojlib"

.\gradlew.bat --no-daemon clean
.\gradlew.bat --no-daemon assembleRelease

Pop-Location
```

Then copy the regenerated AAR into the Unity project again.

# Installing a Development Build

Connect the headset and confirm that ADB can detect it:

```powershell
adb devices
```

Install or replace the APK:

```powershell
adb install -r ".\path\to\QuestCraft.apk"
```

To remove the existing installation before a clean test:

```powershell
adb uninstall com.qcxr.qcxr
adb install ".\path\to\QuestCraft.apk"
```

Uninstalling QuestCraft may remove locally stored application data. Back up important instances, worlds, and logs first.

# Build Troubleshooting

## Gradle Appears Frozen

Run Gradle with additional output:

```powershell
.\gradlew.bat --no-daemon assembleRelease --info --stacktrace
```

The first build can spend considerable time downloading dependencies and creating native build caches.

## Wrong Java Version

Check:

```powershell
java -version
$env:JAVA_HOME
```

Use JDK 25 for the 26.2/Vivecraft stage when required, then switch back to Unity's bundled OpenJDK for the Android build.

## Android SDK Not Found

Set:

```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
```

A `local.properties` file may also be required by the Android project:

```properties
sdk.dir=C\:\\Users\\YourName\\AppData\\Local\\Android\\Sdk
```

Do not commit `local.properties`.

## Unity Cannot Find the Android Toolchain

Open Unity Hub and confirm that Android Build Support, the Android SDK/NDK, and OpenJDK are installed for Unity `2022.3.62f3`.

## Git LFS Files Are Missing

Run:

```powershell
git lfs install
git lfs pull
git lfs ls-files
```

Files containing only text similar to the following are LFS pointers rather than downloaded assets:

```text
version https://git-lfs.github.com/spec/v1
oid sha256:...
size ...
```

## Black Screen with Vulkan

- Switch back to Light Thin Wrapper.
- Disable shader packs.
- Disable renderer-related mods.
- Delete renderer-specific configuration files.
- Restart the headset.
- Collect `latestlog.txt` and logcat output.

# Repository Layout

```text
Pojlib/
```

Android launcher library, runtime management, Minecraft installation logic, native bridges, bundled mods, and Java runtime integration.

```text
QCXR-XR-Wrapper/
```

Unity XR launcher, Android application packaging, launcher interface, resources, OpenXR configuration, and headset integration.

```text
Vivecraft-26.2-Quest/
```

QuestCraft's modified Vivecraft fork targeting Minecraft 26.2.

Other directories may contain research branches, native dependencies, build tools, or renderer experiments.

# Notes

- QuestCraft does not operate an official TikTok account.
- QuestCraft is developed and maintained by the QCXR team.
- QuestCraft contributes to and builds upon open-source projects including Vivecraft, PojavLauncher, LWJGL, Fabric, and OpenXR.
- All support questions should be asked in the **[QuestCraft Discord](https://discord.gg/questcraft)**.
- `questcraft.net` is not maintained or owned by the QuestCraft team.
- The official website is **[questcraft.org](https://questcraft.org/)**.
- Do not download QuestCraft from unofficial websites.
- Third-party mods may cause crashes, visual errors, or unexpected behavior.
- Check existing issues before creating a new **[bug report](https://github.com/QuestCraftPlusPlus/QuestCraft/issues)**.

# This Project Is Open Source

This development repository contains the modified source used for the experimental QuestCraft 26.2 build:

- `Pojlib/`
- `QCXR-XR-Wrapper/`
- `Vivecraft-26.2-Quest/`

The corresponding upstream projects can be found here:

- **[Pojlib](https://github.com/QuestCraftPlusPlus/Pojlib)**
- **[QuestCraft Unity Wrapper](https://github.com/QuestCraftPlusPlus/QCXR-XR-Wrapper)**
- **[Vivecraft](https://github.com/Vivecraft/VivecraftMod)**
- **[PojavLauncher](https://github.com/PojavLauncherTeam/PojavLauncher)**

Changes made in this repository may differ substantially from their upstream versions.

Review the licenses included in each source directory before redistributing modified builds.

# Controls

![Diagram of the controls below](/QC_Controls.png)

<details>
  <summary>Show controls table</summary>

  <table>
    <thead>
      <tr>
        <th scope="col">Button</th>
        <th scope="col">Function</th>
      </tr>
    </thead>
    <tbody>
      <tr>
        <th scope="row" style="text-align: left;">Left Thumbstick</th>
        <td>Move; press to sprint</td>
      </tr>
      <tr>
        <th scope="row" style="text-align: left;">Right Thumbstick</th>
        <td>Turn left or right; press to crouch</td>
      </tr>
      <tr>
        <th scope="row" style="text-align: left;">Left Trigger</th>
        <td>Place or use</td>
      </tr>
      <tr>
        <th scope="row" style="text-align: left;">Right Trigger</th>
        <td>Break or grab</td>
      </tr>
      <tr>
        <th scope="row" style="text-align: left;">Left Grab</th>
        <td>Move left through the hotbar</td>
      </tr>
      <tr>
        <th scope="row" style="text-align: left;">Right Grab</th>
        <td>Move right through the hotbar</td>
      </tr>
      <tr>
        <th scope="row" style="text-align: left;">X</th>
        <td>Inventory</td>
      </tr>
      <tr>
        <th scope="row" style="text-align: left;">Y</th>
        <td>Teleport</td>
      </tr>
      <tr>
        <th scope="row" style="text-align: left;">A</th>
        <td>Radial menu</td>
      </tr>
      <tr>
        <th scope="row" style="text-align: left;">B</th>
        <td>Jump</td>
      </tr>
      <tr>
        <th scope="row" style="text-align: left;">Menu Button</th>
        <td>Pause or Escape</td>
      </tr>
      <tr>
        <th scope="row" style="text-align: left;">Meta Button</th>
        <td>Meta Home</td>
      </tr>
    </tbody>
  </table>
</details>
