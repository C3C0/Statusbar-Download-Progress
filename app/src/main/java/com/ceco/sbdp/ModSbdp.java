/*
 * Copyright (C) 2020 Peter Gregus (C3C076@xda)
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

import java.io.File;

import android.os.Build;
import android.service.notification.NotificationListenerService;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextClock;
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
    private static final String TAG = "SBDP";
    static final boolean DEBUG = false;

    private static final String PACKAGE_NAME_MODULE = ModSbdp.class.getPackage().getName();
    private static final String PACKAGE_NAME_SYSTEMUI = "com.android.systemui";
    private static final String CLASS_PHONE_STATUSBAR_VIEW = "com.android.systemui.statusbar.phone.PhoneStatusBarView";
    private static final String CLASS_PHONE_STATUSBAR = Build.VERSION.SDK_INT >= 26 ?
            "com.android.systemui.statusbar.phone.StatusBar" :
            "com.android.systemui.statusbar.phone.PhoneStatusBar";
    private static final String CLASS_BASE_STATUSBAR = Build.VERSION.SDK_INT >= 26 ?
            "com.android.systemui.statusbar.phone.StatusBar" :
            "com.android.systemui.statusbar.BaseStatusBar";
    private static final String CLASS_NOTIF_DATA_ENTRY = "com.android.systemui.statusbar.NotificationData$Entry";
    private static final String CLASS_CLOCK = "com.android.systemui.statusbar.policy.Clock";
    private static final String CLASS_NOTIF_ENTRY_MANAGER = Build.VERSION.SDK_INT >= 29 ?
            "com.android.systemui.statusbar.notification.NotificationEntryManager" :
            "com.android.systemui.statusbar.NotificationEntryManager";

    static void log(String message) {
        XposedBridge.log(TAG + ": " + message);
    }

    static XSharedPreferences getXSharedPreferences() {
        if (Utils.USE_DEVICE_PROTECTED_STORAGE) {
            String prefsPath = "/data/user_de/0/" + PACKAGE_NAME_MODULE + 
                    "/shared_prefs/" +
                    PACKAGE_NAME_MODULE + "_preferences.xml";
            if (DEBUG) log("Preferences: " + prefsPath);
            return new XSharedPreferences(new File(prefsPath));
        } else {
            if (DEBUG) log("Preferences: using default storage");
            return new XSharedPreferences(ModSbdp.PACKAGE_NAME_MODULE);
        }
    }

    private StatusbarDownloadProgressView mDownloadProgressView;

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) {
        // module package
        if (lpparam.packageName.equals(PACKAGE_NAME_MODULE)) {
            try {
                if (DEBUG) log("Hooking isModuleActive method");
                XposedHelpers.findAndHookMethod(Settings.PlaceholderFragment.class.getName(), 
                        lpparam.classLoader, "isModuleActive",
                        XC_MethodReplacement.returnConstant(Boolean.TRUE));
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

                Class<?> classNotifEntryManager = null;
                if (Build.VERSION.SDK_INT >= 28) {
                    classNotifEntryManager = XposedHelpers.findClass(CLASS_NOTIF_ENTRY_MANAGER,
                            lpparam.classLoader);
                }

                XposedBridge.hookAllConstructors(classPhoneStatusbarView, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        ViewGroup sbVg = (ViewGroup) param.thisObject;
                        mDownloadProgressView = new StatusbarDownloadProgressView(sbVg.getContext());
                        sbVg.addView(mDownloadProgressView);
                        if (DEBUG) log("Download progress view injected");
                    }
                });

                try {
                    XposedHelpers.findAndHookMethod(classPhoneStatusbarView, "onFinishInflate", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (mDownloadProgressView != null) {
                                mDownloadProgressView.setClock(
                                        findClockIn((ViewGroup) param.thisObject));
                            }
                        }
                    });
                } catch (Throwable t) {
                    log("Error hooking onFinishInflate: clock based coloring won't work");
                }

                // new notification
                XposedBridge.hookAllMethods(Build.VERSION.SDK_INT >=28 ?
                                classNotifEntryManager : classPhoneStatusbar,
                        "addNotification", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
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
                XposedBridge.hookAllMethods(Build.VERSION.SDK_INT >= 28 ?
                                classNotifEntryManager : classBaseStatusbar,
                        "updateNotification", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
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
                if (Build.VERSION.SDK_INT >= 28) {
                    XC_MethodHook removeNotificationHook =
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                if (mDownloadProgressView != null) {
                                    Object notifData = XposedHelpers.getObjectField(param.thisObject, "mNotificationData");
                                    Object entry = XposedHelpers.callMethod(notifData, "get", param.args[0]);
                                    if (entry != null) {
                                        mDownloadProgressView.onNotificationRemoved(
                                                XposedHelpers.getObjectField(entry, "notification"));
                                    }
                                }
                            }
                    };
                    if (Build.VERSION.SDK_INT >= 29) {
                        XposedHelpers.findAndHookMethod(classNotifEntryManager, "removeNotification",
                                String.class, NotificationListenerService.RankingMap.class, int.class, removeNotificationHook);
                    } else {
                        XposedHelpers.findAndHookMethod(classNotifEntryManager, "removeNotification",
                                String.class, NotificationListenerService.RankingMap.class, removeNotificationHook);
                    }
                } else {
                    XposedBridge.hookAllMethods(classBaseStatusbar, "removeNotificationViews", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
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
                }
            } catch (Throwable t) {
                XposedBridge.log(t);
            }
            
        }
    }

    private Object getSbNotificationFromArgs(Object[] args) {
        for (Object o : args) {
            if (hasRequiredFields(o))
                return o;
        }
        return null;
    }

    private boolean hasRequiredFields(Object o) {
        try {
            XposedHelpers.getObjectField(o, "notification");
            XposedHelpers.getObjectField(o, "pkg");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private TextView findClockIn(ViewGroup vg) {
        if (DEBUG) log("findClockIn: " + vg.getClass().getName());
        TextView clock = null;
        int childCount = vg.getChildCount();
        for (int i = 0; i < childCount; i++) {
            View childView = vg.getChildAt(i);
            if (childView instanceof ViewGroup) {
                clock =  findClockIn((ViewGroup) childView);
            } else if (childView.getClass().getName().equals(CLASS_CLOCK) ||
                    (Build.VERSION.SDK_INT >= 17 && childView instanceof TextClock)) {
                clock = (TextView) childView;
            }
            if (clock != null) {
                if (DEBUG) log("findClockIn: clock found in " + vg.getClass().getName() + " as " +
                        clock.getClass().getName());
                break;
            }
        }
        return clock;
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
