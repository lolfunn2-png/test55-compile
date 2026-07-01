package com.timefix.xposed;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;
import java.io.BufferedReader;
import java.io.InputStreamReader;

public class Main implements IXposedHookLoadPackage {

    @Override
    public void handleLoadPackage(final LoadPackageParam lpparam) throws Throwable {
        // Run only for system server ("android" package)
        if (!"android".equals(lpparam.packageName)) {
            return;
        }

        XposedBridge.log("AUTO_TIME_FIX: Initializing module for package 'android'...");

        try {
            // Hook ActivityManagerService.systemReady(Runnable goingCallback)
            XposedBridge.log("AUTO_TIME_FIX: Hooking com.android.server.am.ActivityManagerService.systemReady()");
            XposedHelpers.findAndHookMethod(
                "com.android.server.am.ActivityManagerService",
                lpparam.classLoader,
                "systemReady",
                Runnable.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        XposedBridge.log("AUTO_TIME_FIX: systemReady called! Spawning background sync thread...");

                        Thread timeFixThread = new Thread(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    // 1. Boot stabilization: wait 30 seconds
                                    XposedBridge.log("AUTO_TIME_FIX: Boot stabilization - waiting 30 seconds...");
                                    Thread.sleep(30000);

                                    // 2. Wait until internet is available (max retry 30 times, 5s interval)
                                    boolean internetAvailable = false;
                                    for (int i = 1; i <= 30; i++) {
                                        XposedBridge.log("AUTO_TIME_FIX: Checking internet connectivity (Attempt " + i + "/30)...");
                                        if (checkInternetConnection()) {
                                            XposedBridge.log("AUTO_TIME_FIX: Internet connection is available!");
                                            internetAvailable = true;
                                            break;
                                        }
                                        XposedBridge.log("AUTO_TIME_FIX: No internet. Waiting 5 seconds before retry...");
                                        Thread.sleep(5000);
                                    }

                                    if (!internetAvailable) {
                                        XposedBridge.log("AUTO_TIME_FIX: Warning: Internet connectivity check failed 30 times. Proceeding with NTP sync attempt anyway.");
                                    }

                                    // 3. NTP sync logic (retry 10 times, 5s delay)
                                    for (int i = 1; i <= 10; i++) {
                                        XposedBridge.log("AUTO_TIME_FIX: Performing NTP sync attempt " + i + "/10...");
                                        boolean success = runNtpSync();
                                        if (success) {
                                            XposedBridge.log("AUTO_TIME_FIX: NTP time synchronization completed successfully!");
                                            break;
                                        } else {
                                            XposedBridge.log("AUTO_TIME_FIX: NTP sync attempt " + i + "/10 failed.");
                                        }
                                        if (i < 10) {
                                            XposedBridge.log("AUTO_TIME_FIX: Waiting 5 seconds before next NTP sync attempt...");
                                            Thread.sleep(5000);
                                        }
                                    }

                                } catch (InterruptedException e) {
                                    XposedBridge.log("AUTO_TIME_FIX: Thread interrupted: " + e.getMessage());
                                } catch (Exception e) {
                                    XposedBridge.log("AUTO_TIME_FIX: Unexpected error in background thread: " + e.getMessage());
                                }
                            }
                        });

                        timeFixThread.setName("AutoTimeFixThread");
                        timeFixThread.setDaemon(true);
                        timeFixThread.start();
                    }
                }
            );
        } catch (Throwable t) {
            XposedBridge.log("AUTO_TIME_FIX: Failed to hook systemReady: " + t.getMessage());
        }
    }

    private boolean checkInternetConnection() {
        try {
            // Run ping command: -c 1 (1 packet), -W 3 (3 seconds timeout)
            Process process = Runtime.getRuntime().exec(new String[]{"ping", "-c", "1", "-W", "3", "8.8.8.8"});
            int exitCode = process.waitFor();
            XposedBridge.log("AUTO_TIME_FIX: Ping test returned exit code: " + exitCode);
            return (exitCode == 0);
        } catch (Exception e) {
            XposedBridge.log("AUTO_TIME_FIX: Error during ping test: " + e.getMessage());
            return false;
        }
    }

    private boolean runNtpSync() {
        try {
            String command = "busybox ntpd -p time.google.com";
            XposedBridge.log("AUTO_TIME_FIX: Executing command: " + command);
            Process process = Runtime.getRuntime().exec(command);

            // Read output streams to prevent process from blocking and to capture details
            BufferedReader stdInput = new BufferedReader(new InputStreamReader(process.getInputStream()));
            BufferedReader stdError = new BufferedReader(new InputStreamReader(process.getErrorStream()));

            String s;
            while ((s = stdInput.readLine()) != null) {
                XposedBridge.log("AUTO_TIME_FIX: NTP_OUT: " + s);
            }
            while ((s = stdError.readLine()) != null) {
                XposedBridge.log("AUTO_TIME_FIX: NTP_ERR: " + s);
            }

            int exitCode = process.waitFor();
            XposedBridge.log("AUTO_TIME_FIX: NTP command exited with code: " + exitCode);

            return (exitCode == 0);
        } catch (Exception e) {
            XposedBridge.log("AUTO_TIME_FIX: Error during NTP sync execution: " + e.getMessage());
            return false;
        }
    }
}
