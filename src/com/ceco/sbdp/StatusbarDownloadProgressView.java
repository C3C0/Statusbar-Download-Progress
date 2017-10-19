/*
 * Copyright (C) 2015 Peter Gregus (C3C076@xda)
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.media.AudioManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.PowerManager;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.RemoteViews;
import android.widget.TextView;

public class StatusbarDownloadProgressView extends View {
    public static final List<String> SUPPORTED_PACKAGES = new ArrayList<String>(Arrays.asList(
            "com.android.providers.downloads",
            "com.android.bluetooth",
            "com.mediatek.bluetooth",
            "com.android.chrome",
            "org.mozilla.firefox",
            "org.mozilla.firefox_beta",
            "org.mozilla.fennec_aurora",
            "org.mozilla.fennec"
    ));

    private static final int ANIM_DURATION = 400; // ms
    private static final int INDEX_CYCLER_FREQUENCY = 5000; // ms
    private static final long MAX_IDLE_TIME = 10000; // ms
    private static final String EXTRA_PROGRESS = "android.progress";
    private static final String EXTRA_PROGRESS_MAX = "android.progressMax";

    private static class ProgressInfo {
        String id;
        int progress;
        int max;
        long lastUpdatedMs;

        ProgressInfo(String id, int progress, int max) {
            this.id = id;
            this.progress = progress;
            this.max = max;
            this.lastUpdatedMs = System.currentTimeMillis();
        }

        float getFraction() {
            return (max > 0 ? ((float)progress/(float)max) : 0f);
        }

        boolean isIdle() {
            long idleTime = (System.currentTimeMillis() - this.lastUpdatedMs);
            if (ModSbdp.DEBUG) ModSbdp.log("ProgressInfo: '" + this.id + 
                    "' is idle for " + idleTime + "ms");
            return (idleTime > MAX_IDLE_TIME);
        }
    }

    private enum Mode { OFF, TOP, BOTTOM };
    private Mode mMode;
    private int mEdgeMarginPx;
    private boolean mGodMode;
    private boolean mAnimated;
    private ObjectAnimator mAnimator;
    private boolean mCentered;
    private int mHeightPx;
    private Demo mDemo;
    private TextView mClock;
    private int mColor;
    private boolean mFollowClockColor;
    private boolean mSoundEnabled;
    private String mSoundUri;
    private boolean mSoundWhenScreenOffOnly;
    private PowerManager mPowerManager;
    private Map<String, ProgressInfo> mProgressList = new LinkedHashMap<String, ProgressInfo>();
    private int mCurrentIndex = 0;

    private BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @SuppressWarnings("deprecation")
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(Settings.ACTION_SETTINGS_CHANGED)) {
                if (intent.hasExtra(Settings.EXTRA_MODE)) {
                    mMode = Mode.valueOf(intent.getStringExtra(Settings.EXTRA_MODE));
                    if (mMode == Mode.OFF) {
                        removeProgress(null, false);
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
                    mColor = intent.getIntExtra(Settings.EXTRA_COLOR, 
                            Build.VERSION.SDK_INT >= 19 ? Color.WHITE : 
                                getResources().getColor(android.R.color.holo_blue_dark));
                    updateColor();
                }
                if (intent.hasExtra(Settings.EXTRA_COLOR_FOLLOW_CLOCK)) {
                    mFollowClockColor = intent.getBooleanExtra(
                            Settings.EXTRA_COLOR_FOLLOW_CLOCK, false);
                    updateColor();
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
                            intent.getIntExtra(Settings.EXTRA_THICKNESS, 1),
                            getResources().getDisplayMetrics());
                    updatePosition();
                }
                if (intent.hasExtra(Settings.EXTRA_SOUND_ENABLE)) {
                    mSoundEnabled = intent.getBooleanExtra(Settings.EXTRA_SOUND_ENABLE, false);
                }
                if (intent.hasExtra(Settings.EXTRA_SOUND_URI)) {
                    mSoundUri = intent.getStringExtra(Settings.EXTRA_SOUND_URI);
                }
                if (intent.hasExtra(Settings.EXTRA_SOUND_SCREEN_OFF)) {
                    mSoundWhenScreenOffOnly = intent.getBooleanExtra(Settings.EXTRA_SOUND_SCREEN_OFF, false);
                }
            } else if (intent.getAction().equals(Settings.ACTION_RUN_DEMO)) {
                mDemo.start();
            }
        }
    };

    @SuppressWarnings("deprecation")
    public StatusbarDownloadProgressView(Context context) {
        super(context);

        XSharedPreferences prefs = new XSharedPreferences(ModSbdp.PACKAGE_NAME_MODULE);
        mMode = Mode.valueOf(prefs.getString(Settings.PREF_KEY_MODE, "TOP"));
        mEdgeMarginPx = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                prefs.getInt(Settings.PREF_KEY_EDGE_MARGIN, 0),
                getResources().getDisplayMetrics());
        mGodMode = prefs.getBoolean(Settings.PREF_KEY_GOD_MODE, false);
        mAnimated = prefs.getBoolean(Settings.PREF_KEY_ANIMATED, true);
        mCentered = prefs.getBoolean(Settings.PREF_KEY_CENTERED, false);
        mHeightPx = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                prefs.getInt(Settings.PREF_KEY_THICKNESS, 1),
                getResources().getDisplayMetrics());
        mColor = prefs.getInt(Settings.PREF_KEY_COLOR, 
                Build.VERSION.SDK_INT >= 19 ? Color.WHITE : 
                    getResources().getColor(android.R.color.holo_blue_dark));
        mFollowClockColor = prefs.getBoolean(Settings.PREF_KEY_COLOR_FOLLOW_CLOCK, false);
        mSoundEnabled = prefs.getBoolean(Settings.PREF_KEY_SOUND_ENABLE, false);
        mSoundUri = prefs.getString(Settings.PREF_KEY_SOUND,
                "content://settings/system/notification_sound");
        mSoundWhenScreenOffOnly = prefs.getBoolean(Settings.PREF_KEY_SOUND_SCREEN_OFF, false);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, mHeightPx);
        setLayoutParams(lp);
        updateColor();
        setScaleX(0f);
        setVisibility(View.GONE);
        updatePosition();

        mAnimator = new ObjectAnimator();
        mAnimator.setTarget(this);
        mAnimator.setInterpolator(new DecelerateInterpolator());
        mAnimator.setDuration(ANIM_DURATION);
        mAnimator.setRepeatCount(0);

        mDemo = new Demo();

        mPowerManager = (PowerManager) getContext().getSystemService(Context.POWER_SERVICE);

        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Settings.ACTION_SETTINGS_CHANGED);
        intentFilter.addAction(Settings.ACTION_RUN_DEMO);
        context.registerReceiver(mBroadcastReceiver, intentFilter);
    }

    public void setClock(TextView clock) {
        mClock = clock;
        updateColor();
    }

    private void updateColor() {
        int color = mColor;
        if (mFollowClockColor && mClock != null) {
            color = mClock.getCurrentTextColor();
        }
        setBackgroundColor(color);
    }

    @SuppressWarnings("deprecation")
    private void maybePlaySound() {
        if (mSoundEnabled &&
                (!mPowerManager.isScreenOn() || !mSoundWhenScreenOffOnly)) {
            try {
                final Ringtone sfx = RingtoneManager.getRingtone(getContext(),
                        Uri.parse(mSoundUri));
                if (sfx != null) {
                    sfx.setStreamType(AudioManager.STREAM_NOTIFICATION);
                    sfx.play();
                }
            } catch (Throwable t) {
                XposedBridge.log(t);
            }
        }
    }

    @Override
    public void onSizeChanged(int w, int h, int oldw, int oldh) {
        if (ModSbdp.DEBUG) ModSbdp.log("w=" + w + "; h=" + h);
        setPivotX(mCentered ? w/2f : 0f);
    }

    private void addProgress(ProgressInfo pi) {
        synchronized (mProgressList) {
            if (!mProgressList.containsKey(pi.id)) {
                mProgressList.put(pi.id, pi);
                if (ModSbdp.DEBUG) ModSbdp.log("addProgress: added progress for '" + pi.id + "'");
                resetIndexCycler(mProgressList.size()-1);
                updateProgressView(true);
            } else if (ModSbdp.DEBUG) {
                ModSbdp.log("addProgress: progress for '" + pi.id + "' already exists");
            }
        }
    }

    private void removeProgress(String id, boolean allowSound) {
        synchronized (mProgressList) {
            if (id == null) {
                mProgressList.clear();
                if (ModSbdp.DEBUG) ModSbdp.log("removeProgress: all cleared");
            } else if (mProgressList.containsKey(id)) {
                mProgressList.remove(id);
                if (ModSbdp.DEBUG) ModSbdp.log("removeProgress: removed progress for '" + id + "'");
                if (allowSound) maybePlaySound();
            }
        }
        resetIndexCycler(0);
        updateProgressView(true);
    }

    private void updateProgress(String id, int max, int progress) {
        ProgressInfo pi = mProgressList.get(id);
        if (pi != null) {
            pi.max = max;
            pi.progress = progress;
            pi.lastUpdatedMs = System.currentTimeMillis();
            if (ModSbdp.DEBUG) {
                ModSbdp.log("updateProgress: updated progress for '" + id + "': " +
                        "max=" + max + "; progress=" + progress);
            }
            updateProgressView(false);
        }
    }

    private Runnable mIndexCyclerRunnable = new Runnable() {
        @Override
        public void run() {
            boolean shouldUpdateView = false;

            // clear idle first
            synchronized (mProgressList) {
                List<String> toRemove = new ArrayList<>();
                for (ProgressInfo pi : mProgressList.values())
                    if (pi.isIdle()) toRemove.add(pi.id);
                for (String id : toRemove)
                    mProgressList.remove(id);
                shouldUpdateView |= !toRemove.isEmpty();
            }

            // cycle index
            final int oldIndex = mCurrentIndex++;
            if (mCurrentIndex >= mProgressList.size()) mCurrentIndex = 0;
            if (ModSbdp.DEBUG) ModSbdp.log("IndexCycler: oldIndex=" + oldIndex + "; " +
                    "mCurrentIndex=" + mCurrentIndex);
            shouldUpdateView |= (mCurrentIndex != oldIndex);

            if (shouldUpdateView) {
                updateProgressView(mCurrentIndex != oldIndex);
            }

            if (mProgressList.size() > 0) {
                StatusbarDownloadProgressView.this.postDelayed(this, INDEX_CYCLER_FREQUENCY);
            }
        }
    };

    private void resetIndexCycler(int toIndex) {
        removeCallbacks(mIndexCyclerRunnable);
        mCurrentIndex = toIndex;
        if (mProgressList.size() > 0) {
            postDelayed(mIndexCyclerRunnable, INDEX_CYCLER_FREQUENCY);
        }
    }

    public void onNotificationAdded(Object statusBarNotif) {
        if (mMode == Mode.OFF) return;

        ProgressInfo pi = verifyNotification(statusBarNotif);
        if (pi == null) {
            if (ModSbdp.DEBUG) ModSbdp.log("onNotificationAdded: ignoring unsupported notification");
            return;
        }

        addProgress(pi);
    }

    public void onNotificationUpdated(Object statusBarNotif) {
        if (mMode == Mode.OFF) return;

        ProgressInfo pi = verifyNotification(statusBarNotif);
        if (pi == null) {
            String id = getIdentifier(statusBarNotif);
            if (id != null && mProgressList.containsKey(id)) {
                removeProgress(id, true);
                if (ModSbdp.DEBUG) ModSbdp.log("onNotificationUpdated: removing no longer " +
                        "supported notification for '" + id + "'");
            } else if (ModSbdp.DEBUG) {
                ModSbdp.log("onNotificationUpdated: ignoring unsupported notification");
            }
            return;
        }

        if (!mProgressList.containsKey(pi.id)) {
            // treat it as if it was added, e.g. to show progress in case
            // feature has been enabled during already ongoing download
            addProgress(pi);
        } else {
            updateProgress(pi.id, pi.max, pi.progress);
        }
    }

    public void onNotificationRemoved(Object statusBarNotif) {
        if (mMode == Mode.OFF) return;

        String id = getIdentifier(statusBarNotif);
        if (id != null && mProgressList.containsKey(id)) {
            removeProgress(id, true);
        }
    }

    private ProgressInfo verifyNotification(Object statusBarNotif) {
        if (statusBarNotif == null) {
            return null;
        }

        String id = getIdentifier(statusBarNotif);
        if (id == null)
            return null;

        String pkgName = (String) XposedHelpers.getObjectField(statusBarNotif, "pkg");
        Notification n = (Notification) XposedHelpers.getObjectField(statusBarNotif, "notification");
        if (n != null && (SUPPORTED_PACKAGES.contains(pkgName) || mGodMode)) {
            return getProgressInfo(id, n);
        }
        return null;
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

    private void updateProgressView(boolean fadeOutAndIn) {
        if (!mProgressList.isEmpty()) {
            ProgressInfo pi = (ProgressInfo) mProgressList.values().toArray()[mCurrentIndex];
            float newScaleX = pi.getFraction();
            if (ModSbdp.DEBUG) ModSbdp.log("updateProgressView: id='" + 
                    pi.id + "'; newScaleX=" + newScaleX);
            updateColor();
            if (getVisibility() != View.VISIBLE) {
                clearAnimation();
                fadeIn(newScaleX);
            } else if (fadeOutAndIn) {
                fadeOutAndIn(newScaleX);
            } else if (mAnimated) {
                animateScaleTo(newScaleX);
            } else {
                setScaleX(newScaleX);
            }
        } else {
            postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (mAnimator.isStarted()) {
                        mAnimator.end();
                    }
                    fadeOut();
                }
            }, ANIM_DURATION + 100);
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

    private void fadeOutAndIn(final float newScaleX) {
        animate()
            .alpha(0f)
            .setDuration(ANIM_DURATION / 2)
            .setListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    StatusbarDownloadProgressView.this.setScaleX(newScaleX);
                    StatusbarDownloadProgressView.this.animate()
                        .alpha(1f)
                        .setDuration(ANIM_DURATION / 2)
                        .setListener(null);
                }
            });
    }

    private void fadeIn(final float newScaleX) {
        setAlpha(0f);
        setScaleX(newScaleX);
        setVisibility(View.VISIBLE);
        animate()
            .alpha(1f)
            .setDuration(ANIM_DURATION)
            .setListener(null);
    }

    private void fadeOut() {
        animate()
            .alpha(0f)
            .setDuration(ANIM_DURATION)
            .setListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    StatusbarDownloadProgressView.this.setScaleX(0f);
                    StatusbarDownloadProgressView.this.setVisibility(View.GONE);
                    StatusbarDownloadProgressView.this.setAlpha(1f);
                }
            });
    }

    @SuppressLint("NewApi")
    private ProgressInfo getProgressInfo(String id, Notification n) {
        if (id == null || n == null) return null;

        if (Build.VERSION.SDK_INT > 18 &&
            n.extras.containsKey(EXTRA_PROGRESS) &&
            n.extras.containsKey(EXTRA_PROGRESS_MAX) &&
            n.extras.getInt(EXTRA_PROGRESS_MAX) > 0) {
            return new ProgressInfo(id,
                    n.extras.getInt(EXTRA_PROGRESS),
                    n.extras.getInt(EXTRA_PROGRESS_MAX));
        } else if (n.bigContentView != null) {
            return getProgressInfoFromRemoteView(id, n.bigContentView);
        } else if (n.contentView != null) {
            return getProgressInfoFromRemoteView(id, n.contentView);
        }

        return null;
    }

    private ProgressInfo getProgressInfoFromRemoteView(String id, RemoteViews view) {
        int max = -1;
        int progress = -1;

        try {
            @SuppressWarnings("unchecked")
            List<Parcelable> actions = (List<Parcelable>) 
                XposedHelpers.getObjectField(view, "mActions");
            if (actions == null) return null;

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
                    max = parcel.readInt();
                    if (ModSbdp.DEBUG) ModSbdp.log("getProgressInfoFromRemoteView: max=" + max);
                } else if ("setProgress".equals(methodName)) {
                    parcel.readInt(); // skip type value
                    progress = parcel.readInt();
                    if (ModSbdp.DEBUG) ModSbdp.log("getProgressInfoFromRemoteView: progress=" + progress);
                }

                parcel.recycle();
            }
        } catch (Throwable  t) {
            XposedBridge.log(t);
        }

        return (max != -1 && progress != -1) ?
                new ProgressInfo(id, progress, max) : null;
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

    private class Demo implements Runnable {
        private boolean mDemoRunning;

        public void start() {
            if (!mProgressList.isEmpty() || mDemoRunning) {
                return;
            }
            mDemoRunning = true;
            fadeIn(0f);
            run();
        }

        @Override
        public void run() {
            if (!mProgressList.isEmpty()) {
                mDemoRunning = false;
            }
            if (!mDemoRunning) {
                return;
            }
            updateColor();
            final View v = StatusbarDownloadProgressView.this;
            float newScale = Math.min(v.getScaleX() + 0.2f, 1f);
            if (mAnimated) {
                animateScaleTo(newScale);
            } else {
                v.setScaleX(newScale);
            }
            if (newScale < 1f) {
                v.postDelayed(this, ANIM_DURATION + 300);
            } else {
                maybePlaySound();
                updateProgressView(false);
                mDemoRunning = false;
            }
        }
    };
}
