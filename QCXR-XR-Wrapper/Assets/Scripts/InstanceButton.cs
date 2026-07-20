using System.Collections;
using System.Collections.Generic;
using System.IO;
using System.Threading;
using Newtonsoft.Json;
using UnityEngine;
using UnityEngine.XR;

public class InstanceButton : MonoBehaviour
{
    public static string currInstName;
    private bool hasDefaulted;
    public ConfigHandler configHandler;
    public UIHandler uiHandler;
    public CanvasGroup ScreenFade;
    private volatile bool launchPrepared;
    private volatile bool prelaunchFailed;
    private bool launchInProgress;
    private bool waitingForOpenXRHandoff;
    private bool openXRHandoffInProgress;
    private PojlibInstance preparedInstance;

    public void Update()
    {
        if (Application.platform != RuntimePlatform.Android)
            return;
        currInstName = JNIStorage.instance.instancesDropdown.options[JNIStorage.instance.instancesDropdown.value].text;

        if (prelaunchFailed)
        {
            prelaunchFailed = false;
            launchInProgress = false;
            uiHandler.SetAndShowError("Prelaunch checks failed!");
        }

        if (launchPrepared)
        {
            launchPrepared = false;
            BeginJavaLaunch(preparedInstance);
        }

        if (waitingForOpenXRHandoff && !openXRHandoffInProgress &&
            JNIStorage.apiClass.GetStatic<bool>("gameReady"))
        {
            openXRHandoffInProgress = true;
            StartCoroutine(CompleteOpenXRHandoff());
        }
    }

    public void SelectInstance()
    {
        if (Application.platform != RuntimePlatform.Android) return;
        currInstName = JNIStorage.instance.instancesDropdown.options[JNIStorage.instance.instancesDropdown.value].text;
        configHandler.SetLastSelectedInstance(JNIStorage.instance.instancesDropdown.value);
    }

    private static void CreateDefaultInstance(string name)
    {
        JNIStorage.apiClass.CallStatic<AndroidJavaObject>("createNewInstance", JNIStorage.activity, JNIStorage.instancesObj, name, true, name, "Fabric", null);
        JNIStorage.instance.uiHandler.SetAndShowError(currInstName + " is now installing.");
        JNIStorage.instance.UpdateInstances();
    }

    public void LaunchCurrentInstance()
    {
        if (launchInProgress)
            return;

        if (JNIStorage.GetInstance(currInstName) == null)
        {
            Debug.Log("Instance is null!");
            
            CreateDefaultInstance(currInstName);
            return;
        }
        
        
        PojlibInstance instance = JNIStorage.GetInstance(currInstName);
        launchInProgress = true;
        uiHandler.PlaySetter();
        ProgressBarManager.started = true;
        new Thread(() =>
        {
            AndroidJNI.AttachCurrentThread();
            try
            {
                if (!JNIStorage.apiClass.CallStatic<bool>("prelaunch", JNIStorage.activity,
                        JNIStorage.instancesObj, instance.raw))
                {
                    prelaunchFailed = true;
                    return;
                }

                preparedInstance = instance;
                launchPrepared = true;
            }
            catch
            {
                prelaunchFailed = true;
            }
            finally
            {
                AndroidJNI.DetachCurrentThread();
            }
        }).Start();
    }

    private void BeginJavaLaunch(PojlibInstance instance)
    {
        waitingForOpenXRHandoff = true;
        new Thread(() =>
        {
            AndroidJNI.AttachCurrentThread();
            try
            {
                JNIStorage.apiClass.CallStatic("launchInstance", JNIStorage.activity, JNIStorage.accountObj,
                    instance.raw);
            }
            finally
            {
                AndroidJNI.DetachCurrentThread();
            }
        }).Start();
    }

    private IEnumerator CompleteOpenXRHandoff()
    {
        bool fadeComplete = false;
        LeanTween.value(ScreenFade.gameObject, 0, 1, 1)
            .setOnUpdate(alpha => ScreenFade.alpha = alpha)
            .setOnComplete(() => fadeComplete = true);

        while (!fadeComplete)
            yield return null;

        JNIStorage.CloseXR();

        // Give Unity's render loop two synchronization points to release the runtime session.
        yield return new WaitForEndOfFrame();
        yield return null;

        JNIStorage.apiClass.CallStatic("releaseOpenXRHandoff");
        waitingForOpenXRHandoff = false;
    }

    public void KillInstance()
    {
        JNIStorage.apiClass.CallStatic("restartLauncher", JNIStorage.activity);
    }

    public async void MirrorNativesForInstance()
    {
        PojlibInstance instance = JNIStorage.GetInstance(currInstName);
        JNIStorage.apiClass.CallStatic("mirrorNativesInFolder", JNIStorage.activity, JNIStorage.instancesObj, instance.raw, "/sdcard/Android/data/com.qcxr.qcxr/files/natives");
    }

    public async void ImportSaves()
    {
        PojlibInstance instance = JNIStorage.GetInstance(currInstName);
        JNIStorage.apiClass.CallStatic("unzipSavesFromFolder", instance.raw, "/sdcard/Android/data/com.qcxr.qcxr/files/import-saves");
    }
}
