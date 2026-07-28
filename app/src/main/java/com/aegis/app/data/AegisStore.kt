package com.aegis.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aegis.core.budget.UsageState
import com.aegis.core.classifier.TermWeightOverrides
import com.aegis.core.lockdown.PendingChange
import com.aegis.core.log.TransparencyLog
import com.aegis.core.rules.RuleSet
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "aegis")

/**
 * On-device persistence. All of it.
 *
 * Everything is stored as JSON in a single preferences file: the rule set, what is queued
 * behind a cooling-off period, today's usage, what the classifier has learned from
 * corrections, and the transparency log. There is no server, no account, and no sync —
 * that is the §2.2 promise, and the simplest way to keep a promise like that is to have
 * nowhere to send anything.
 *
 * Reads are lenient by design. A parse failure falls back to the safe default rather than
 * crashing, because a corrupt preferences file must never be a way to end up with no
 * rules in force.
 */
class AegisStore(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    val rules: Flow<RuleSet> = context.dataStore.data.map { preferences ->
        decode(preferences[KEY_RULES], RuleSet.defaults())
    }

    val pendingChanges: Flow<List<PendingChange>> = context.dataStore.data.map { preferences ->
        decode(preferences[KEY_PENDING], emptyList())
    }

    val usage: Flow<UsageState> = context.dataStore.data.map { preferences ->
        decode(preferences[KEY_USAGE], UsageState())
    }

    val overrides: Flow<TermWeightOverrides> = context.dataStore.data.map { preferences ->
        decode(preferences[KEY_OVERRIDES], TermWeightOverrides.NONE)
    }

    val log: Flow<TransparencyLog> = context.dataStore.data.map { preferences ->
        decode(preferences[KEY_LOG], TransparencyLog())
    }

    val onboardingComplete: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[KEY_ONBOARDING] == "done"
    }

    /** Opt-out for the one network request Aegis makes that the user did not ask for. */
    val updateChecksEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[KEY_UPDATE_CHECKS] != "off"
    }

    val lastUpdateCheckMillis: Flow<Long> = context.dataStore.data.map { preferences ->
        preferences[KEY_LAST_UPDATE_CHECK]?.toLongOrNull() ?: 0L
    }

    suspend fun setUpdateChecksEnabled(enabled: Boolean) =
        write(KEY_UPDATE_CHECKS, if (enabled) "on" else "off")

    suspend fun setLastUpdateCheck(millis: Long) =
        write(KEY_LAST_UPDATE_CHECK, millis.toString())

    /**
     * What the guard's state was the last time this app version ran.
     *
     * Exists to answer one question a user cannot answer for themselves: *did the update
     * switch the guard off, or was it already off?* Android gives no notice either way, so
     * without this the failure is completely silent — the app looks fine, the switch on
     * the Home screen looks like something you forgot to do weeks ago, and nothing has
     * been filtered since Tuesday.
     */
    val lastRunVersionCode: Flow<Long> = context.dataStore.data.map { preferences ->
        preferences[KEY_LAST_RUN_VERSION]?.toLongOrNull() ?: 0L
    }

    val guardOnAtLastRun: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[KEY_GUARD_WAS_ON] == "yes"
    }

    /**
     * What happened to the guard across the last version change.
     *
     * Kept so the question "does updating switch it off on *this* phone?" stops being
     * something anyone has to remember. Android's behaviour here differs by version and
     * by manufacturer, and one recorded observation beats an argument about it.
     */
    val lastUpdateGuardOutcome: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[KEY_UPDATE_GUARD_OUTCOME].orEmpty()
    }

    suspend fun recordRun(versionCode: Long, guardEnabled: Boolean) {
        write(KEY_LAST_RUN_VERSION, versionCode.toString())
        write(KEY_GUARD_WAS_ON, if (guardEnabled) "yes" else "no")
    }

    suspend fun recordUpdateGuardOutcome(outcome: String) =
        write(KEY_UPDATE_GUARD_OUTCOME, outcome)

    suspend fun saveRules(value: RuleSet) = write(KEY_RULES, json.encodeToString(RuleSet.serializer(), value))

    suspend fun savePending(value: List<PendingChange>) =
        write(KEY_PENDING, json.encodeToString(ListSerializer(PendingChange.serializer()), value))

    suspend fun saveUsage(value: UsageState) = write(KEY_USAGE, json.encodeToString(UsageState.serializer(), value))

    suspend fun saveOverrides(value: TermWeightOverrides) =
        write(KEY_OVERRIDES, json.encodeToString(TermWeightOverrides.serializer(), value))

    suspend fun saveLog(value: TransparencyLog) =
        write(KEY_LOG, json.encodeToString(TransparencyLog.serializer(), value))

    suspend fun markOnboardingComplete() = write(KEY_ONBOARDING, "done")

    private suspend fun write(key: Preferences.Key<String>, value: String) {
        context.dataStore.edit { it[key] = value }
    }

    private inline fun <reified T> decode(raw: String?, fallback: T): T {
        if (raw.isNullOrBlank()) return fallback
        return try {
            json.decodeFromString<T>(raw)
        } catch (error: Exception) {
            fallback
        }
    }

    private companion object {
        val KEY_RULES = stringPreferencesKey("rules")
        val KEY_PENDING = stringPreferencesKey("pending_changes")
        val KEY_USAGE = stringPreferencesKey("usage")
        val KEY_OVERRIDES = stringPreferencesKey("term_overrides")
        val KEY_LOG = stringPreferencesKey("transparency_log")
        val KEY_ONBOARDING = stringPreferencesKey("onboarding")
        val KEY_UPDATE_CHECKS = stringPreferencesKey("update_checks")
        val KEY_LAST_UPDATE_CHECK = stringPreferencesKey("last_update_check")
        val KEY_LAST_RUN_VERSION = stringPreferencesKey("last_run_version")
        val KEY_GUARD_WAS_ON = stringPreferencesKey("guard_was_on")
        val KEY_UPDATE_GUARD_OUTCOME = stringPreferencesKey("update_guard_outcome")
    }
}
