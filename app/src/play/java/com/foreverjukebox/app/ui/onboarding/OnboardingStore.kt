package com.foreverjukebox.app.ui.onboarding

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.IOException

private val COMPLETED_TOURS = stringSetPreferencesKey("completed_tours")
private val SKIPPED = booleanPreferencesKey("skipped")
private val BASELINE_VERSION = intPreferencesKey("baseline_version")

// Writes outlive the screen that asks for them, so a rotation right after a tap still saves it.
private val OnboardingWriteScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

// An unreadable record counts as skipped: onboarding is optional, and a lost record must not
// replay tours the user may already have finished.
private val Context.onboardingDataStore by preferencesDataStore(
    name = "fj_onboarding",
    corruptionHandler = ReplaceFileCorruptionHandler { mutablePreferencesOf(SKIPPED to true) }
)

/** The onboarding record, kept apart from the main preferences. Only ever adds to it outside debug. */
class OnboardingStore(context: Context) {
    private val dataStore = context.applicationContext.onboardingDataStore

    val record: Flow<OnboardingRecord> = dataStore.data
        .map { prefs ->
            val keys = prefs[COMPLETED_TOURS].orEmpty()
            OnboardingRecord(
                completed = OnboardingTourId.entries.filter { it.storageKey in keys }.toSet(),
                skipped = prefs[SKIPPED] == true,
                baselineVersion = prefs[BASELINE_VERSION] ?: ONBOARDING_VERSION
            )
        }
        .catch { error ->
            if (error !is IOException) throw error
            emit(OnboardingRecord(emptySet(), skipped = true, baselineVersion = ONBOARDING_VERSION))
        }
        .distinctUntilChanged()

    /** Records the onboarding version of the first run; later runs keep the first value. */
    suspend fun recordBaseline() = write { prefs ->
        if (prefs[BASELINE_VERSION] == null) prefs[BASELINE_VERSION] = ONBOARDING_VERSION
    }

    fun markCompleted(tour: OnboardingTourId) = writeLater { prefs ->
        prefs[COMPLETED_TOURS] = prefs[COMPLETED_TOURS].orEmpty() + tour.storageKey
    }

    fun markSkipped() = writeLater { prefs -> prefs[SKIPPED] = true }

    /** Debug only: forgets everything, as a fresh install of this build would. */
    fun reset() = writeLater { prefs ->
        prefs.clear()
        prefs[BASELINE_VERSION] = ONBOARDING_VERSION
    }

    private fun writeLater(change: (MutablePreferences) -> Unit) {
        OnboardingWriteScope.launch { write(change) }
    }

    /** A failed write leaves the session's in-memory record in force; the next launch may repeat it. */
    private suspend fun write(change: (MutablePreferences) -> Unit) {
        try {
            dataStore.edit(change)
        } catch (_: IOException) {
            // Nothing to recover: the tour state for this session already reflects the change.
        }
    }
}
