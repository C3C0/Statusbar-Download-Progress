/*
 * Copyright (C) 2015 Peter Gregus (C3C076@xda)
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.ceco.sbdp;

import android.os.Build;
import android.view.ViewGroup;
import android.widget.TextView;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class ModSbdp implements IXposedHookZygoteInit, IXposedHookLoadPackage {
    public static final String TAG = "SBDP";
    public static final String PACKAGE_NAME_MODULE = ModSbdp.class.getPackage().getName();
    public static final String PACKAGE_NAME_SYSTEMUI = "com.android.systemui";
    public static final String CLASS_PHONE_STATUSBAR_VIEW = "com.android.systemui.statusbar.phone.PhoneStatusBarView";
    public static final String CLASS_PHONE_STATUSBAR = "com.android.systemui.statusbar.phone.PhoneStatusBar";
    public static final String CLASS_BASE_STATUSBAR = "com.android.systemui.statusbar.BaseStatusBar";
    public static final String CLASS_NOTIF_DATA_ENTRY = "com.android.systemui.statusbar.NotificationData$Entry";
    public static final String CLASS_CLOCK = "com.android.systemui.statusbar.policy.Clock";
    public static final boolean DEBUG = false;

    public static void log(String message) {
        XposedBridge.log(TAG + ": " + message);
    }

    private StatusbarDownloadProgressView mDownloadProgressView;

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) throws Throwable {
        // module package
        if (lpparam.packageName.equals(PACKAGE_NAME_MODULE)) {
            try {
                if (DEBUG) log("Hooking isModuleActive method");
                XposedHelpers.findAndHookMethod(Settings.PlaceholderFragment.class.getName(), 
                        lpparam.classLoader, "isModuleActive",
                        XC_MethodReplacement.returnConstant(Boolean.valueOf(true)));
            } catch (Throwable t) {
                XposedBridge.log(t);
            }
        }

        // SystemUI package
        if (lpparam.packageName.equals(PACKAGE_NAME_SYSTEMUI)) {
            try {
                if (DEBUG) log("Creating status bar hooks");
                Class<?> classPhoneStatusbarView = XposedHelpers.findClass(CLASS_PHONE_STATUSBAR_VIEW,
                        lpparam.classLoader);
                Class<?> classPhoneStatusbar = XposedHelpers.findClass(CLASS_PHONE_STATUSBAR,
                        lpparam.classLoader);
                Class<?> classBaseStatusbar = XposedHelpers.findClass(CLASS_BASE_STATUSBAR,
                        lpparam.classLoader);

                XposedBridge.hookAllConstructors(classPhoneStatusbarView, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        ViewGroup sbVg = (ViewGroup) param.thisObject;
                        mDownloadProgressView = new StatusbarDownloadProgressView(sbVg.getContext());
                        sbVg.addView(mDownloadProgressView);
                        if (DEBUG) log("Download progress view injected");
                    }
                });

                try {
                    XposedBridge.hookAllConstructors(
                            XposedHelpers.findClass(CLASS_CLOCK, lpparam.classLoader),
                            new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            if (DEBUG) log("Clock constructed: " + param.thisObject);
                            if (mDownloadProgressView != null &&
                                    (param.thisObject instanceof TextView)) {
                                mDownloadProgressView.addClock((TextView)param.thisObject);
                            }
                        }
                    });
                } catch (Throwable t) {
                    log("Error hooking clock: clock based coloring won't work");
                }

                // new notification
                XposedBridge.hookAllMethods(classPhoneStatusbar, "addNotification", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (mDownloadProgressView != null) {
                            Object sbNotif = getSbNotificationFromArgs(param.args);
                            if (sbNotif != null) {
                                mDownloadProgressView.onNotificationAdded(sbNotif);
                            } else {
                                log("addNotification: Couldn't find StatusbarNotification in params");
                            }
                        }
                    }
                });

                // notification update
                XposedBridge.hookAllMethods(classBaseStatusbar, "updateNotification", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (mDownloadProgressView != null) {
                            Object sbNotif = getSbNotificationFromArgs(param.args);
                            if (sbNotif != null) {
                                mDownloadProgressView.onNotificationUpdated(sbNotif);
                            } else {
                                log("updateNotification: Couldn't find StatusbarNotification in params");
                            }
                        }
                    }
                });

                // notification removal
                XposedBridge.hookAllMethods(classBaseStatusbar, "removeNotificationViews", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (mDownloadProgressView != null) {
                            try {
                                Object result = param.getResult();
                                if (result != null) {
                                    Object statusBarNotif = CLASS_NOTIF_DATA_ENTRY.equals(result.getClass().getName()) ?
                                                XposedHelpers.getObjectField(result, "notification") : result;
                                    mDownloadProgressView.onNotificationRemoved(statusBarNotif);
                                }
                            } catch (Throwable t) {
                                XposedBridge.log(t);
                            }
                        }
                    }
                });
            } catch (Throwable t) {
                XposedBridge.log(t);
            }
            
        }
    }

    private Object getSbNotificationFromArgs(Object[] args) {
        for (Object o : args) {
            if (hasNotificationField(o))
                return o;
        }
        return null;
    }

    private boolean hasNotificationField(Object o) {
        try {
            XposedHelpers.getObjectField(o, "notification");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public void initZygote(StartupParam startupParam) throws Throwable { 
        new XSharedPreferences(PACKAGE_NAME_MODULE).makeWorldReadable();
        if (DEBUG) {
            XposedBridge.log("SBDP:Hardware: " + Build.HARDWARE);
            XposedBridge.log("SBDP:Product: " + Build.PRODUCT);
            XposedBridge.log("SBDP:Device manufacturer: " + Build.MANUFACTURER);
            XposedBridge.log("SBDP:Device brand: " + Build.BRAND);
            XposedBridge.log("SBDP:Device model: " + Build.MODEL);
            XposedBridge.log("SBDP:Android SDK: " + Build.VERSION.SDK_INT);
            XposedBridge.log("SBDP:Android Release: " + Build.VERSION.RELEASE);
            XposedBridge.log("SBDP:ROM: " + Build.DISPLAY);
        }
    }
}
