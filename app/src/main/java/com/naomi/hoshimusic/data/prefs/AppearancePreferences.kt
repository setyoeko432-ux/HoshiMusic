package com.naomi.hoshimusic.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

internal class AppearancePreferences(private val dataStore: DataStore<Preferences>) {
    val dynamicColorEnabled: Flow<Boolean> = dataStore.data.map { it[PreferenceKeys.DynamicColorEnabled] ?: true }
    val appTheme: Flow<Int> = dataStore.data.map { it[PreferenceKeys.AppTheme] ?: 0 }
    val romanizationEnabled: Flow<Boolean> = dataStore.data.map { it[PreferenceKeys.RomanizationEnabled] ?: true }
    val translationMode: Flow<Boolean> = dataStore.data.map { it[PreferenceKeys.TranslationMode] ?: false }
    val landscapeDualPaneEnabled: Flow<Boolean> = dataStore.data.map { it[PreferenceKeys.LandscapeDualPaneEnabled] ?: false }

    suspend fun setDynamicColorEnabled(enabled: Boolean) = dataStore.edit { it[PreferenceKeys.DynamicColorEnabled] = enabled }
    suspend fun setAppTheme(theme: Int) = dataStore.edit { it[PreferenceKeys.AppTheme] = theme }
    suspend fun setRomanizationEnabled(enabled: Boolean) = dataStore.edit { it[PreferenceKeys.RomanizationEnabled] = enabled }
    suspend fun setTranslationMode(enabled: Boolean) = dataStore.edit { it[PreferenceKeys.TranslationMode] = enabled }
    suspend fun setLandscapeDualPaneEnabled(enabled: Boolean) = dataStore.edit { it[PreferenceKeys.LandscapeDualPaneEnabled] = enabled }
}
