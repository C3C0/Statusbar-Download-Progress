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
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
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
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.RemoteViews;

public class StatusbarDownloadProgressView extends View {
    public static final List<String> SUPPORTED_PACKAGES = new ArrayList<String>(Arrays.asList(
            "com.android.providers.downloads",
            "com.android.bluetooth",
            "com.mediatek.bluetooth",
            "com.android.chrome",
            "org.mozilla.firefox"
    ));

    private static final int ANIM_DURATION = 400;

    class ProgressInfo {
        boolean hasProgressBar;
        int progress;
        int max;

        public ProgressInfo(boolean hasProgressBar, int progress, int max) {
            this.hasProgressBar = hasProgressBar;
            this.progress = progress;
            this.max = max;
        }

        public float getFraction() {
            return (max > 0 ? ((float)progress/(float)max) : 0f);
        }
    }

    private enum Mode { OFF, TOP, BOTTOM };
    private Mode mMode;
    private int mEdgeMarginPx;
    private String mId;
    private boolean mGodMode;
    private boolean mAnimated;
    private ObjectAnimator mAnimator;
    private boolean mCentered;
    private int mHeightPx;

    private BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(Settings.ACTION_SETTINGS_CHANGED)) {
                if (intent.hasExtra(Settings.EXTRA_MODE)) {
                    mMode = Mode.valueOf(intent.getStringExtra(Settings.EXTRA_MODE));
                    if (mMode == Mode.OFF) {
                        stopTracking();
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
                if (intent.hasExtra(Settings.EXTRA_GOD_MODE)) {
                    mGodMode = intent.getBooleanExtra(Settings.EXTRA_GOD_MODE, false);
                }
                if (intent.hasExtra(Settings.EXTRA_ANIMATED)) {
                    mAnimated = intent.getBooleanExtra(Settings.EXTRA_ANIMATED, true);
                }
                if (intent.hasExtra(Settings.EXTRA_CENTERED)) {
                    mCentered = intent.getBooleanExtra(Settings.EXTRA_CENTERED, false);
                    setPivotX(mCentered ? getWidth()/2f : 0f);
                }
                if (intent.hasExtra(Settings.EXTRA_THICKNESS)) {
                    mHeightPx = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                            intent.getIntExtra(Settings.EXTRA_THICKNESS, 2),
                            getResources().getDisplayMetrics());
                    updatePosition();
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
        mGodMode = prefs.getBoolean(Settings.PREF_KEY_GOD_MODE, false);
        mAnimated = prefs.getBoolean(Settings.PREF_KEY_ANIMATED, true);
        mCentered = prefs.getBoolean(Settings.PREF_KEY_CENTERED, false);
        mHeightPx = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                prefs.getInt(Settings.PREF_KEY_THICKNESS, 2),
                getResources().getDisplayMetrics());

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, mHeightPx);
        setLayoutParams(lp);
        setBackgroundColor(prefs.getInt(Settings.PREF_KEY_COLOR, 
                Build.VERSION.SDK_INT >= 19 ? Color.WHITE : 
                    getResources().getColor(android.R.color.holo_blue_dark)));
        setScaleX(0f);
        setVisibility(View.GONE);
        updatePosition();

        mAnimator = new ObjectAnimator();
        mAnimator.setTarget(this);
        mAnimator.setInterpolator(new DecelerateInterpolator());
        mAnimator.setDuration(ANIM_DURATION);
        mAnimator.setRepeatCount(0);

        context.registerReceiver(mBroadcastReceiver, new IntentFilter(Settings.ACTION_SETTINGS_CHANGED));
    }

    @Override
    public void onSizeChanged(int w, int h, int oldw, int oldh) {
        if (ModSbdp.DEBUG) ModSbdp.log("w=" + w + "; h=" + h);
        setPivotX(mCentered ? w/2f : 0f);
    }

    private void stopTracking() {
        mId = null;
        updateProgress(null);
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

        if (mId.equals(getIdentifier(statusBarNotif))) {
            // if notification became clearable, stop tracking immediately
            if ((Boolean) XposedHelpers.callMethod(statusBarNotif, "isClearable")) {
                if (ModSbdp.DEBUG) ModSbdp.log("onNotificationUpdated: notification became clearable - stopping tracking");
                stopTracking();
            } else {
                if (ModSbdp.DEBUG) ModSbdp.log("updating progress for " + mId);
                updateProgress(statusBarNotif);
            }
        }
    }

    public void onNotificationRemoved(Object statusBarNotif) {
        if (mMode == Mode.OFF) return;

        if (mId == null) {
            if (ModSbdp.DEBUG) ModSbdp.log("onNotificationRemoved: no download registered");
            return;
        } else if (mId.equals(getIdentifier(statusBarNotif))) {
            if (ModSbdp.DEBUG) ModSbdp.log("finishing progress for " + mId);
            stopTracking();
        }
    }

    private boolean verifyNotification(Object statusBarNotif) {
        if (statusBarNotif == null || (Boolean) XposedHelpers.callMethod(statusBarNotif, "isClearable")) {
            return false;
        }

        String pkgName = (String) XposedHelpers.getObjectField(statusBarNotif, "pkg");
        Notification n = (Notification) XposedHelpers.getObjectField(statusBarNotif, "notification");
        return (n != null && 
               (SUPPORTED_PACKAGES.contains(pkgName) || mGodMode) &&
                getProgressInfo(n).hasProgressBar);
    }

    protected String getIdentifier(Object statusBarNotif) {
        if (statusBarNotif == null) return null;
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
        if (statusBarNotif != null) {
            Notification n = (Notification) XposedHelpers.getObjectField(statusBarNotif, "notification");
            float newScaleX = getProgressInfo(n).getFraction();
            if (ModSbdp.DEBUG) ModSbdp.log("updateProgress: newScaleX=" + newScaleX);
            setVisibility(View.VISIBLE);
            if (mAnimated) {
                animateScaleTo(newScaleX);
            } else {
                setScaleX(newScaleX);
            }
        } else {
            if (mAnimator.isStarted()) {
                mAnimator.end();
            }
            postDelayed(new Runnable() {
                @Override
                public void run() {
                    setScaleX(0f);
                    setVisibility(View.GONE);
                }
            }, 500);
        }
    }

    private void animateScaleTo(float newScaleX) {
        if (mAnimator.isStarted()) {
            mAnimator.cancel();
        }
        mAnimator.setValues(PropertyValuesHolder.ofFloat("scaleX", getScaleX(), newScaleX));
        mAnimator.start();
        if (ModSbdp.DEBUG) ModSbdp.log("Animating to new scaleX: " + newScaleX);
    }

    private ProgressInfo getProgressInfo(Notification n) {
        ProgressInfo pInfo = new ProgressInfo(false, 0, 0);
        if (n == null) return pInfo;

        // We have to extract the information from the content view
        RemoteViews views = n.bigContentView;
        if (views == null) views = n.contentView;
        if (views == null) return pInfo;

        try {
            @SuppressWarnings("unchecked")
            ArrayList<Parcelable> actions = (ArrayList<Parcelable>) 
                XposedHelpers.getObjectField(views, "mActions");
            if (actions == null) return pInfo;

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

                parcel.readInt(); // skip View ID
                String methodName = parcel.readString();
                if ("setMax".equals(methodName)) {
                    parcel.readInt(); // skip type value
                    pInfo.max = parcel.readInt();
                    if (ModSbdp.DEBUG) ModSbdp.log("getProgress: total=" + pInfo.max);
                } else if ("setProgress".equals(methodName)) {
                    parcel.readInt(); // skip type value
                    pInfo.progress = parcel.readInt();
                    pInfo.hasProgressBar = true;
                    if (ModSbdp.DEBUG) ModSbdp.log("getProgress: current=" + pInfo.progress);
                }

                parcel.recycle();
            }
        } catch (Throwable  t) {
            XposedBridge.log(t);
        }

        return pInfo;
    }

    private void updatePosition() {
        if (mMode == Mode.OFF) return;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) getLayoutParams();
        lp.height = mHeightPx;
        lp.gravity = mMode == Mode.TOP ? (Gravity.TOP | Gravity.START) :
            (Gravity.BOTTOM | Gravity.START);
        lp.setMargins(0, mMode == Mode.TOP ? mEdgeMarginPx : 0, 
                      0, mMode == Mode.BOTTOM ? mEdgeMarginPx : 0);
        setLayoutParams(lp);
    }
}
