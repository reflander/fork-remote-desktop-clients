package com.iiordanov.bVNC;

import android.os.Bundle;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import com.undatech.remoteClientUi.R;

public class GlobalPreferencesFragment extends PreferenceFragmentCompat {
    @Override
    public void onCreatePreferences(Bundle bundle, String s) {
        getPreferenceManager().setSharedPreferencesName(Constants.generalSettingsTag);
        setPreferencesFromResource(R.xml.global_preferences, s);
        if (Utils.isVnc(getContext())) {
            addPreferencesFromResource(R.xml.global_preferences_vnc);
        } else if (Utils.isRdp(getContext())) {
            addPreferencesFromResource(R.xml.global_preferences_rdp);
        } else if (Utils.isSpice(getContext())) {
            addPreferencesFromResource(R.xml.global_preferences_spice);
        }

        // FanRDP: apply the theme change immediately (not only after app restart).
        ListPreference themePref = findPreference(Constants.appThemeTag);
        if (themePref != null) {
            themePref.setOnPreferenceChangeListener((pref, newValue) -> {
                String value = newValue == null ? "follow_system" : newValue.toString();
                switch (value) {
                    case "light":
                        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
                        break;
                    case "dark":
                        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
                        break;
                    default:
                        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
                        break;
                }
                // Recreate the current screen so the new theme is applied right away.
                if (getActivity() != null) {
                    getActivity().recreate();
                }
                return true;
            });
        }
    }

    @Override
    public boolean onPreferenceTreeClick(Preference preference) {
        if ("openDefaultConnectionSettings".equals(preference.getKey())) {
            Utils.openDefaultConnectionSettings(requireContext());
            return true;
        }
        return super.onPreferenceTreeClick(preference);
    }
}