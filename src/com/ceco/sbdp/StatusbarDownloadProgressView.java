/*
 * Copyright (C) 2014 Peter Gregus (C3C076@xda)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.ceco.sbdp;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import android.app.Notification;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.os.Build;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.RemoteViews;

public class StatusbarDownloadProgressView extends View {
    public static final List<String> SUPPORTED_PACKAGES = new ArrayList<String>(Arrays.asList(
            "com.android.providers.downloads",
            "com.android.bluetooth",
            "com.mediatek.bluetooth"
    ));

    private enum Mode { OFF, TOP, BOTTOM };
    private Mode mMode;
    private int mEdgeMarginPx;
    private String mId;

    private BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(Settings.ACTION_SETTINGS_CHANGED)) {
                if (intent.hasExtra(Settings.EXTRA_MODE)) {
                    mMode = Mode.valueOf(intent.getStringExtra(Settings.EXTRA_MODE));
                    if (mMode == Mode.OFF) {
                        mId = null;
                        updateProgress(null);
                    } else {
                        updatePosition();
                    }
                }
                if (intent.hasExtra(Settings.EXTRA_EDGE_MARGIN)) {
                    mEdgeMarginPx = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                            intent.getIntExtra(Settings.EXTRA_EDGE_MARGIN, 0),
                            getResources().getDisplayMetrics());
                    updatePosition();
                }
                if (intent.hasExtra(Settings.EXTRA_COLOR)) {
                    setBackgroundColor(intent.getIntExtra(Settings.EXTRA_COLOR, 
                            Build.VERSION.SDK_INT >= 19 ? Color.WHITE : 
                                getResources().getColor(android.R.color.holo_blue_dark)));
                }
            }
        }
    };

    public StatusbarDownloadProgressView(Context context) {
        super(context);

        XSharedPreferences prefs = new XSharedPreferences(ModSbdp.PACKAGE_NAME_MODULE);
        mMode = Mode.valueOf(prefs.getString(Settings.PREF_KEY_MODE, "TOP"));
        mEdgeMarginPx = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                Integer.valueOf(prefs.getString(Settings.PREF_KEY_EDGE_MARGIN, "0")),
                getResources().getDisplayMetrics());

        int heightPx = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1,
                getResources().getDisplayMetrics());
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(0, heightPx);
        setLayoutParams(lp);
        setBackgroundColor(prefs.getInt(Settings.PREF_KEY_COLOR, 
                Build.VERSION.SDK_INT >= 19 ? Color.WHITE : 
                    getResources().getColor(android.R.color.holo_blue_dark)));
        setVisibility(View.GONE);
        updatePosition();

        context.registerReceiver(mBroadcastReceiver, new IntentFilter(Settings.ACTION_SETTINGS_CHANGED));
    }

    public void onNotificationAdded(Object statusBarNotif) {
        if (mMode == Mode.OFF) return;

        if (!verifyNotification(statusBarNotif)) {
            if (ModSbdp.DEBUG) ModSbdp.log("onNotificationAdded: ignoring unsupported notification");
            return;
        }

        if (mId != null) {
            if (ModSbdp.DEBUG) ModSbdp.log("onNotificationAdded: another download already registered");
            return;
        }

        mId = getIdentifier(statusBarNotif);
        if (mId != null) {
            if (ModSbdp.DEBUG) ModSbdp.log("starting progress for " + mId);
            updateProgress(statusBarNotif);
        }
    }

    public void onNotificationUpdated(Object statusBarNotif) {
        if (mMode == Mode.OFF) return;

        if (mId == null) {
            // treat it as if it was added, e.g. to show progress in case
            // feature has been enabled during already ongoing download
            onNotificationAdded(statusBarNotif);
            return;
        }

        if (!verifyNotification(statusBarNotif)) {
            if (ModSbdp.DEBUG) ModSbdp.log("onNotificationUpdated: ignoring unsupported notification");
            return;
        }

        if (mId.equals(getIdentifier(statusBarNotif))) {
            if (ModSbdp.DEBUG) ModSbdp.log("updating progress for " + mId);
            updateProgress(statusBarNotif);
        }
    }

    public void onNotificationRemoved(Object statusBarNotif) {
        if (mMode == Mode.OFF) return;

        if (!verifyNotification(statusBarNotif)) {
            if (ModSbdp.DEBUG) ModSbdp.log("onNotificationRemoved: ignoring unsupported notification");
            return;
        }

        if (mId == null) {
            if (ModSbdp.DEBUG) ModSbdp.log("onNotificationRemoved: no download registered");
            return;
        } else if (mId.equals(getIdentifier(statusBarNotif))) {
            if (ModSbdp.DEBUG) ModSbdp.log("finishing progress for " + mId);
            mId = null;
            updateProgress(null);
        }
    }

    private boolean verifyNotification(Object statusBarNotif) {
        if (statusBarNotif == null) return false;
        String pkgName = (String) XposedHelpers.getObjectField(statusBarNotif, "pkg");
        if (ModSbdp.DEBUG) ModSbdp.log("verifyNotification: " + pkgName);
        if (SUPPORTED_PACKAGES.contains(pkgName)) {
            return (Boolean) XposedHelpers.callMethod(statusBarNotif, "isOngoing");
        }
        return false;
    }

    protected String getIdentifier(Object statusBarNotif) {
        String pkgName = (String) XposedHelpers.getObjectField(statusBarNotif, "pkg");
        if (Build.VERSION.SDK_INT > 17 && SUPPORTED_PACKAGES.get(0).equals(pkgName)) {
            String tag = (String) XposedHelpers.getObjectField(statusBarNotif, "tag");
            if (tag != null && tag.contains(":")) {
                String id = pkgName + ":" + tag.substring(tag.indexOf(":")+1);
                if (ModSbdp.DEBUG) ModSbdp.log("getIdentifier: " + id);
                return id;
            }
            if (ModSbdp.DEBUG) ModSbdp.log("getIdentifier: Unexpected notification tag: " + tag);
            return null;
        } else {
            String id = pkgName + ":" +
                    String.valueOf(XposedHelpers.getIntField(statusBarNotif, "id"));
            if (ModSbdp.DEBUG) ModSbdp.log("getIdentifier: " + id);
            return id;
        }
    }

    private void updateProgress(Object statusBarNotif) {
        int maxWidth = ((View) getParent()).getWidth();
        int newWidth = 0;
        if (statusBarNotif != null) {
            Notification n = (Notification) XposedHelpers.getObjectField(statusBarNotif, "notification");
            newWidth = (int) ((float)maxWidth * getProgress(n));
        }
        if (ModSbdp.DEBUG) ModSbdp.log("updateProgress: maxWidth=" + maxWidth + "; newWidth=" + newWidth);
        ViewGroup.LayoutParams lp = (ViewGroup.LayoutParams) getLayoutParams();
        lp.width = newWidth;
        setLayoutParams(lp);
        setVisibility(newWidth > 0 ? View.VISIBLE : View.GONE);
    }

    private float getProgress(Notification n) {
        int total = 0;
        int current = 0;

        // We have to extract the information from the content view
        RemoteViews views = n.bigContentView;
        if (views == null) views = n.contentView;
        if (views == null) return 0f;

        try {
            @SuppressWarnings("unchecked")
            ArrayList<Parcelable> actions = (ArrayList<Parcelable>) 
                XposedHelpers.getObjectField(views, "mActions");
            if (actions == null) return 0f;

            for (Parcelable p : actions) {
                Parcel parcel = Parcel.obtain();
                p.writeToParcel(parcel, 0);
                parcel.setDataPosition(0);

                // The tag tells which type of action it is (2 is ReflectionAction)
                int tag = parcel.readInt();
                if (tag != 2)  {
                    parcel.recycle();
                    continue;
                }

                // Check whether View ID is a progress bar
                int id = parcel.readInt();
                if (id == android.R.id.progress) {
                    String methodName = parcel.readString();
                    if ("setMax".equals(methodName)) {
                        parcel.readInt(); // skip type value
                        total = parcel.readInt();
                        if (ModSbdp.DEBUG) ModSbdp.log("getProgress: total=" + total);
                    } else if ("setProgress".equals(methodName)) {
                        parcel.readInt(); // skip type value
                        current = parcel.readInt();
                        if (ModSbdp.DEBUG) ModSbdp.log("getProgress: current=" + current);
                    }
                }

                parcel.recycle();
            }
        } catch (Throwable  t) {
            XposedBridge.log(t);
        }

        return (total > 0 ? ((float)current/(float)total) : 0f);
    }

    private void updatePosition() {
        if (mMode == Mode.OFF) return;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) getLayoutParams();
        lp.gravity = mMode == Mode.TOP ? (Gravity.TOP | Gravity.START) :
            (Gravity.BOTTOM | Gravity.START);
        lp.setMargins(0, mMode == Mode.TOP ? mEdgeMarginPx : 0, 
                      0, mMode == Mode.BOTTOM ? mEdgeMarginPx : 0);
        setLayoutParams(lp);
    }
}
