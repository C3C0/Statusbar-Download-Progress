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

import com.ceco.sbdp.R;

import android.content.Context;
import android.content.res.TypedArray;
import android.os.Handler;
import android.preference.Preference;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.SeekBar;
import android.widget.SeekBar.OnSeekBarChangeListener;
import android.widget.TextView;

public class SeekBarPreference extends Preference 
                               implements OnSeekBarChangeListener, View.OnClickListener {

    private static final int RAPID_PRESS_TIMEOUT = 600;

    private int mMinimum = 0;
    private int mMaximum = 100;
    private int mInterval = 1;
    private int mDefaultValue = mMinimum;
    private boolean mMonitorBoxEnabled = false;
    private String mMonitorBoxUnit = null;

    private TextView mMonitorBox;
    private SeekBar mBar;
    private ImageButton mBtnPlus;
    private ImageButton mBtnMinus;

    private int mValue;
    private int mTmpValue;
    private boolean mTracking = false;
    private boolean mRapidlyPressing = false;
    private Handler mHandler;

    private Runnable mRapidPressTimeout = new Runnable() {
        @Override
        public void run() {
            mRapidlyPressing = false;
            setValue(mTmpValue);
        }
    };

    public SeekBarPreference(Context context, AttributeSet attrs) {
        super(context, attrs);

        if (attrs != null) {
            mMinimum = attrs.getAttributeIntValue(null, "minimum", 0);
            mMaximum = attrs.getAttributeIntValue(null, "maximum", 100);
            mInterval = attrs.getAttributeIntValue(null, "interval", 1);
            mDefaultValue = mMinimum;
            mMonitorBoxEnabled = attrs.getAttributeBooleanValue(null, "monitorBoxEnabled", false);
            mMonitorBoxUnit = attrs.getAttributeValue(null, "monitorBoxUnit");
        }

        mHandler = new Handler();
    }

    @Override
    protected View onCreateView(ViewGroup parent) {

        View layout = View.inflate(getContext(), R.layout.slider_preference, null);

        mMonitorBox = (TextView) layout.findViewById(R.id.monitor_box);
        mMonitorBox.setVisibility(mMonitorBoxEnabled ? View.VISIBLE : View.GONE);
        mBar = (SeekBar) layout.findViewById(R.id.seek_bar);
        mBar.setMax(mMaximum - mMinimum);
        mBar.setOnSeekBarChangeListener(this);
        mBar.setProgress(mValue - mMinimum);
        mBtnPlus = (ImageButton) layout.findViewById(R.id.btnPlus);
        mBtnPlus.setOnClickListener(this);
        mBtnMinus = (ImageButton) layout.findViewById(R.id.btnMinus);
        mBtnMinus.setOnClickListener(this);
        setMonitorBoxText();
        return layout;
    }

    @Override
    protected Object onGetDefaultValue(TypedArray a, int index) {
        return a.getInt(index, mDefaultValue);
    }

    @Override
    protected void onSetInitialValue(boolean restoreValue, Object defaultValue) {
        setValue(restoreValue ? getPersistedInt(mValue) : (Integer) defaultValue);
    }

    @Override
    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
        if (!fromUser) return;

        progress = Math.round(((float) progress) / mInterval) * mInterval + mMinimum;
        if (mTracking) {
            setMonitorBoxText(progress);
        } else {
            setValue(progress);
        }
    }

    public void setMinimum(int minimum) {
        mMinimum = minimum >= mMaximum ? mMaximum - 1 : minimum;
    }

    public int getValue() {
        return mValue;
    }

    public void setValue(int progress){
        mValue = progress;
        if (isPersistent()) {
            persistInt(mValue);
        }
        if (mBar != null)
        {
            mBar.setProgress(mValue - mMinimum);
            setMonitorBoxText();
        }
    }

    private void setMonitorBoxText() {
        setMonitorBoxText(mValue);
    }

    private void setMonitorBoxText(int value) {
        if (mMonitorBoxEnabled) {
            String text = String.valueOf(value);
            if (mMonitorBoxUnit != null) text += mMonitorBoxUnit;
            mMonitorBox.setText(text);
        }
    }

    @Override
    public void onStartTrackingTouch(SeekBar seekBar) {
        mTracking = true;
    }

    @Override
    public void onStopTrackingTouch(SeekBar seekBar) {
        mTracking = false;
        onProgressChanged(seekBar, seekBar.getProgress(), true);
    }

    @Override
    public void onClick(View v) {
        if (mRapidlyPressing) {
            mHandler.removeCallbacks(mRapidPressTimeout);
        } else {
            mRapidlyPressing = true;
            mTmpValue = mValue;
        }
        mHandler.postDelayed(mRapidPressTimeout, RAPID_PRESS_TIMEOUT);

        if (v == mBtnPlus && ((mTmpValue+mInterval) <= mMaximum)) {
            mTmpValue += mInterval;
        } else if (v == mBtnMinus && ((mTmpValue-mInterval) >= mMinimum)) {
            mTmpValue -= mInterval;
        }

        mBar.setProgress(mTmpValue - mMinimum);
        setMonitorBoxText(mTmpValue);
    }
}