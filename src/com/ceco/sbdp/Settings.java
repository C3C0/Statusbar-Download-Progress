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

import com.ceco.sbdp.billing.IabHelper;
import com.ceco.sbdp.billing.IabResult;
import com.ceco.sbdp.billing.Inventory;
import com.ceco.sbdp.billing.Purchase;
import com.ceco.sbdp.billing.SkuDetails;

import android.app.Activity;
import android.app.Fragment;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.net.Uri;
import android.os.Bundle;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceFragment;
import android.preference.PreferenceScreen;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

public class Settings extends Activity {
    public static final String PREF_CAT_KEY_OPTIONS = "pref_cat_options";
    public static final String PREF_KEY_MODE = "pref_mode";
    public static final String PREF_KEY_EDGE_MARGIN = "pref_edge_margin";
    public static final String PREF_KEY_COLOR = "pref_color";
    public static final String PREF_KEY_GOD_MODE = "pref_god_mode";
    public static final String PREF_KEY_ANIMATED = "pref_animated";
    public static final String PREF_KEY_CENTERED = "pref_centered";
    public static final String PREF_KEY_THICKNESS = "pref_thickness";

    public static final String PREF_KEY_ABOUT = "pref_about";
    public static final String PREF_KEY_ABOUT_DONATE = "pref_about_donate";
    public static final String PREF_KEY_HIDE_LAUNCHER_ICON = "pref_hide_launcher_icon";

    public static final String ACTION_SETTINGS_CHANGED = "sbdp.intent.action.SETTINGS_CHANGED";
    public static final String EXTRA_MODE = "mode";
    public static final String EXTRA_EDGE_MARGIN = "edgeMargin";
    public static final String EXTRA_COLOR = "color";
    public static final String EXTRA_GOD_MODE = "godMode";
    public static final String EXTRA_ANIMATED = "animated";
    public static final String EXTRA_CENTERED = "centered";
    public static final String EXTRA_THICKNESS = "thickness";

    private static SettingsFragment sSettingsFragment;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        if (savedInstanceState == null) {
            getFragmentManager().beginTransaction()
                    .add(R.id.container, new PlaceholderFragment()).commit();
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (!sSettingsFragment.handlePurchaseResult(requestCode, resultCode, data)) {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    public static class PlaceholderFragment extends Fragment {
        private TextView mInfoTextView;

        public PlaceholderFragment() { }

        @Override
        public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
            View rootView = inflater.inflate(R.layout.fragment_settings, container, false);

            mInfoTextView = (TextView) rootView.findViewById(R.id.infoText);
            sSettingsFragment = (SettingsFragment) getFragmentManager().findFragmentById(R.id.settingsFragment);

            return rootView;
        }

        @Override
        public void onResume() {
            super.onResume();

            if (!isModuleActive()) {
                mInfoTextView.setText(R.string.module_not_active);
                mInfoTextView.setVisibility(View.VISIBLE);
            } else {
                mInfoTextView.setVisibility(View.GONE);
            }

            sSettingsFragment.setOptionsEnabled(mInfoTextView.getVisibility() == View.GONE);
        }

        private boolean isModuleActive() {
            return false;
        }
    }

    public static class SettingsFragment extends PreferenceFragment 
                                         implements OnSharedPreferenceChangeListener,
                                                    Preference.OnPreferenceChangeListener,
                                                    IabHelper.OnIabSetupFinishedListener,
                                                    IabHelper.QueryInventoryFinishedListener,
                                                    IabHelper.OnIabPurchaseFinishedListener,
                                                    IabHelper.OnConsumeFinishedListener,
                                                    IabHelper.OnConsumeMultiFinishedListener {
        private SharedPreferences mPrefs;
        private PreferenceCategory mPrefCatOptions;
        private ListPreference mPrefMode;
        private ListPreference mPrefEdgeMargin;
        private Preference mPrefAbout;
        private ListPreference mPrefAboutDonate;
        private IabHelper mIabHelper; 

        private static List<String> sSkuList = new ArrayList<String>(Arrays.asList(
                "sbdp_001", "sbdp_002", "sbdp_003", "sbdp_004", "sbdp_005"));

        @SuppressWarnings("deprecation")
        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);

            getPreferenceManager().setSharedPreferencesMode(Context.MODE_WORLD_READABLE);
            addPreferencesFromResource(R.xml.settings);

            mPrefs = getPreferenceScreen().getSharedPreferences();

            mPrefCatOptions = (PreferenceCategory) findPreference(PREF_CAT_KEY_OPTIONS);
            mPrefMode = (ListPreference) findPreference(PREF_KEY_MODE);
            mPrefEdgeMargin = (ListPreference) findPreference(PREF_KEY_EDGE_MARGIN);

            mPrefAbout = findPreference(PREF_KEY_ABOUT);
            String version = "";
            try {
                PackageInfo pInfo = getActivity().getPackageManager().getPackageInfo(getActivity().getPackageName(), 0);
                version = " v" + pInfo.versionName;
            } catch (NameNotFoundException e) {
                e.printStackTrace();
            } finally {
                mPrefAbout.setTitle(getActivity().getTitle() + version);
            }

            mPrefAboutDonate = (ListPreference) findPreference(PREF_KEY_ABOUT_DONATE);
            mPrefAboutDonate.setOnPreferenceChangeListener(this);
        }

        protected void setOptionsEnabled(boolean enabled) {
            mPrefCatOptions.setEnabled(enabled);
        }

        protected void updateSummaries() {
            mPrefMode.setSummary(mPrefMode.getEntry());
            mPrefEdgeMargin.setSummary(mPrefEdgeMargin.getEntry());
        }

        @Override
        public void onStart() {
            super.onStart();
            mPrefs.registerOnSharedPreferenceChangeListener(this);
            mIabHelper = new IabHelper(getActivity());
            mIabHelper.startSetup(this);
        }

        @Override
        public void onStop() {
            super.onStop();
            if (mIabHelper != null) {
                mIabHelper.dispose();
                mIabHelper = null;
            }
            mPrefs.unregisterOnSharedPreferenceChangeListener(this);
        }

        @Override
        public void onPause() {
            super.onPause();
            if (mPrefAboutDonate.getDialog() != null &&
                    mPrefAboutDonate.getDialog().isShowing()) {
                mPrefAboutDonate.getDialog().dismiss();
            }
        }

        @Override
        public void onResume() {
            super.onResume();
            updateSummaries();
        }

        @Override
        public boolean onPreferenceTreeClick(PreferenceScreen prefScreen, Preference pref) {
            if (pref == mPrefAbout) {
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.url_xda)));
                try {
                    startActivity(intent);
                } catch (ActivityNotFoundException e) {
                    e.printStackTrace();
                }
                return true;
            }

            return super.onPreferenceTreeClick(prefScreen, pref);
        }

        @Override
        public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
            updateSummaries();
            Intent intent = new Intent();

            if (key.equals(PREF_KEY_MODE)) {
                intent.setAction(ACTION_SETTINGS_CHANGED);
                intent.putExtra(EXTRA_MODE, prefs.getString(key, "TOP"));
            } else if (key.equals(PREF_KEY_EDGE_MARGIN)) {
                intent.setAction(ACTION_SETTINGS_CHANGED);
                intent.putExtra(EXTRA_EDGE_MARGIN,Integer.valueOf(prefs.getString(key, "0")));
            } else if (key.equals(PREF_KEY_COLOR)) {
                intent.setAction(ACTION_SETTINGS_CHANGED);
                intent.putExtra(EXTRA_COLOR, prefs.getInt(key,
                        getResources().getInteger(R.integer.color_default)));
            } else if (key.equals(PREF_KEY_GOD_MODE)) {
                intent.setAction(ACTION_SETTINGS_CHANGED);
                intent.putExtra(EXTRA_GOD_MODE, prefs.getBoolean(key, false));
            } else if (key.equals(PREF_KEY_HIDE_LAUNCHER_ICON)) {
                int mode = prefs.getBoolean(key, false) ?
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED :
                            PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
                getActivity().getPackageManager().setComponentEnabledSetting(
                        new ComponentName(getActivity(), "com.ceco.sbdp.SettingsAlias"),
                        mode, PackageManager.DONT_KILL_APP);
            } else if (key.equals(PREF_KEY_ANIMATED)) {
                intent.setAction(ACTION_SETTINGS_CHANGED);
                intent.putExtra(EXTRA_ANIMATED, prefs.getBoolean(key, true));
            } else if (key.equals(PREF_KEY_CENTERED)) {
                intent.setAction(ACTION_SETTINGS_CHANGED);
                intent.putExtra(EXTRA_CENTERED, prefs.getBoolean(key, false));
            } else if (key.equals(PREF_KEY_THICKNESS)) {
                intent.setAction(ACTION_SETTINGS_CHANGED);
                intent.putExtra(EXTRA_THICKNESS, prefs.getInt(key, 2));
            }

            if (ACTION_SETTINGS_CHANGED.equals(intent.getAction())) {
                prefs.edit().commit();
                getActivity().sendBroadcast(intent);
            }
        }

        @Override
        public boolean onPreferenceChange(Preference preference, Object newValue) {
            if (preference == mPrefAboutDonate) {
                String sku = String.valueOf(newValue);
                mPrefAboutDonate.setEnabled(false);
                mIabHelper.launchPurchaseFlow(getActivity(), sku, 10001, this);
                return false;
            }
            return true;
        }

        @Override
        public void onIabSetupFinished(IabResult result) {
            if (result.isSuccess()) {
                mIabHelper.queryInventoryAsync(true, sSkuList, this);
            } else {
                mPrefAboutDonate.setSummary(R.string.pref_about_donate_error);
            }
        }

        @Override
        public void onQueryInventoryFinished(IabResult result, Inventory inv) {
            if (result.isFailure()) {
                mPrefAboutDonate.setSummary(R.string.pref_about_donate_error);
                if (result.getMessage() != null) {
                    Toast.makeText(getActivity(), result.getMessage(), Toast.LENGTH_SHORT).show();
                }
                return;
            }

            List<CharSequence> entries = new ArrayList<CharSequence>();
            List<CharSequence> entryValues = new ArrayList<CharSequence>();
            List<Purchase> purchasesToConsume = new ArrayList<Purchase>();
            for (String sku : sSkuList) {
                if (inv.hasDetails(sku)) {
                    SkuDetails skuDet = inv.getSkuDetails(sku);
                    entries.add(String.format("%s (%s)", skuDet.getDescription(),
                            skuDet.getPrice()));
                    entryValues.add(sku);
                    if (inv.hasPurchase(sku)) {
                        purchasesToConsume.add(inv.getPurchase(sku));
                    }
                }
            }
            if (entries.size() > 0) {
                mPrefAboutDonate.setEntries(entries.toArray(new CharSequence[entries.size()]));
                mPrefAboutDonate.setEntryValues(entryValues.toArray(new CharSequence[entryValues.size()]));
                mPrefAboutDonate.setSummary(R.string.pref_about_donate_summary);
                if (purchasesToConsume.size() > 0) {
                    mIabHelper.consumeAsync(purchasesToConsume, this);
                } else {
                    mPrefAboutDonate.setEnabled(true);
                }
            } else {
                mPrefAboutDonate.setSummary(R.string.pref_about_donate_no_items);
            }
        }

        protected boolean handlePurchaseResult(int requestCode, int resultCode, Intent data) {
            return mIabHelper.handleActivityResult(requestCode, resultCode, data);
        }

        @Override
        public void onIabPurchaseFinished(IabResult result, Purchase info) {
            if (result.isFailure()) {
                Toast.makeText(getActivity(), R.string.donate_transaction_error,
                        Toast.LENGTH_SHORT).show();
                mPrefAboutDonate.setEnabled(true);
                return;
            }

            Toast.makeText(getActivity(), R.string.donate_transaction_ok, Toast.LENGTH_LONG).show();
            mIabHelper.consumeAsync(info, this);
        }

        @Override
        public void onConsumeFinished(Purchase purchase, IabResult result) {
            mPrefAboutDonate.setEnabled(true);
        }

        @Override
        public void onConsumeMultiFinished(List<Purchase> purchases, List<IabResult> results) {
            mPrefAboutDonate.setEnabled(true);
        }
    }
}
