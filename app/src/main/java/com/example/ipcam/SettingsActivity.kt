package com.example.ipcam

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportFragmentManager
            .beginTransaction()
            .replace(android.R.id.content, SettingsFragment())
            .commit()
    }


}

class SettingsFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.prefs, rootKey)

        // Validatie: alleen integer >= 0
        val minPref = findPreference<EditTextPreference>("min_interval_ms")
        minPref?.setOnPreferenceChangeListener { _: Preference, newValue: Any ->
            val text = newValue.toString().trim()
            val v = text.toLongOrNull()
            if (v == null || v < 0) {
                // ongeldige input blokkeren
                false
            } else {
                true
            }
        }
    }
}
