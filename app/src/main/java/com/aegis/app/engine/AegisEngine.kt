package com.aegis.app.engine

import android.content.Context
import com.aegis.app.data.AegisStore
import com.aegis.app.notify.PartnerNotifier
import com.aegis.app.service.CriticalPackages
import com.aegis.app.update.AvailableUpdate
import com.aegis.app.update.UpdateCheck
import com.aegis.app.update.UpdateChecker
import com.aegis.core.budget.BudgetKeys
import com.aegis.core.budget.BudgetTracker
import com.aegis.core.budget.GraceClaimResult
import com.aegis.core.budget.GraceRequestResult
import com.aegis.core.budget.UsageState
import com.aegis.core.classifier.FeedbackLearner
import com.aegis.core.classifier.LexicalClassifier
import com.aegis.core.classifier.TermWeightOverrides
import com.aegis.core.lockdown.ChangeOutcome
import com.aegis.core.lockdown.CoolingOff
import com.aegis.core.lockdown.PendingChange
import com.aegis.core.log.Correction
import com.aegis.core.log.LogEntry
import com.aegis.core.log.TransparencyLog
import com.aegis.core.model.Category
import com.aegis.core.model.Classification
import com.aegis.core.model.ContentInput
import com.aegis.core.model.RouteContext
import com.aegis.core.rules.Decision
import com.aegis.core.rules.RuleSet
import com.aegis.core.rules.RulesEngine
import com.aegis.core.util.Clock
import com.aegis.core.util.Urls
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The application-scoped wiring of everything in `:core`.
 *
 * There is exactly one of these. The VPN service, the accessibility service, the browser
 * and the UI all consult it, and it is the single place that owns "what the rules are
 * right now" — which matters because those callers run on different threads and a
 * disagreement between them would show up as a filter that blocks a page in one place and
 * lets it through in another.
 *
 * State is exposed as [StateFlow]s so the UI can observe it and the services can read
 * `.value` synchronously on whatever thread they happen to be on. Writes funnel through a
 * mutex, because every one of them is a read-modify-write of persisted state.
 */
class AegisEngine private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val store = AegisStore(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeLock = Mutex()

    // Not the core's JVM default: on a device, monotonic time has to include deep sleep.
    // See [AndroidClock].
    private val clock: Clock = AndroidClock
    private val tracker = BudgetTracker(clock)
    private val coolingOff = CoolingOff(clock)
    private val rulesEngine = RulesEngine(clock, tracker)
    private val learner = FeedbackLearner()

    private val _rules = MutableStateFlow(RuleSet.defaults())
    val rules: StateFlow<RuleSet> = _rules.asStateFlow()

    private val _usage = MutableStateFlow(UsageState())
    val usage: StateFlow<UsageState> = _usage.asStateFlow()

    private val _pending = MutableStateFlow<List<PendingChange>>(emptyList())
    val pending: StateFlow<List<PendingChange>> = _pending.asStateFlow()

    private val _overrides = MutableStateFlow(TermWeightOverrides.NONE)
    val overrides: StateFlow<TermWeightOverrides> = _overrides.asStateFlow()

    private val _log = MutableStateFlow(TransparencyLog())
    val log: StateFlow<TransparencyLog> = _log.asStateFlow()

    private val _onboardingComplete = MutableStateFlow(false)
    val onboardingComplete: StateFlow<Boolean> = _onboardingComplete.asStateFlow()

    /**
     * One-shot messages for the UI: what just happened to a change the user made.
     *
     * `extraBufferCapacity` so `tryEmit` from inside the write lock never suspends or
     * drops — this must not be able to deadlock the thing it is reporting on.
     */
    private val _notices = MutableSharedFlow<ChangeNotice>(extraBufferCapacity = 8)
    val notices: SharedFlow<ChangeNotice> = _notices.asSharedFlow()

    /** True while the user is still setting up and edits apply instantly. */
    val isArmed: Boolean get() = _rules.value.armed

    /** What the accessibility guard last observed. See [GuardDiagnostics]. */
    val diagnostics = GuardDiagnostics()

    /** Queued changes waiting on a particular control, for inline display. */
    fun pendingFor(targetKey: String): PendingChange? =
        _pending.value.firstOrNull { targetKey in it.targetKeys }

    /** Rebuilt whenever corrections change, so learning takes effect without a restart. */
    @Volatile
    private var classifier: LexicalClassifier = LexicalClassifier(clock = clock)

    private var logSequence = 0L

    init {
        scope.launch { store.rules.collect { _rules.value = it } }
        scope.launch { store.usage.collect { _usage.value = it } }
        scope.launch { store.pendingChanges.collect { _pending.value = it } }
        scope.launch { store.log.collect { _log.value = it } }
        scope.launch { store.onboardingComplete.collect { _onboardingComplete.value = it } }
        scope.launch { store.updateChecksEnabled.collect { _updateChecksEnabled.value = it } }
        scope.launch { store.lastUpdateCheckMillis.collect { lastUpdateCheck = it } }
        scope.launch {
            store.overrides.collect {
                _overrides.value = it
                classifier = LexicalClassifier(overrides = it, clock = clock)
            }
        }
    }

    // ---------------------------------------------------------------- classification

    fun classify(input: ContentInput): Classification = classifier.classify(input)

    /**
     * The full path for a page we rendered ourselves: classify, decide, log.
     *
     * Logging happens here rather than at each call site so that "why was this blocked?"
     * can never be answered with "there is no record of that".
     */
    fun evaluatePage(input: ContentInput): EvaluatedPage {
        val classification = classify(input)
        val decision = rulesEngine.evaluateContent(input, classification, _rules.value, _usage.value)
        val entryId = record(Urls.host(input.url), decision)
        return EvaluatedPage(entryId, classification, decision)
    }

    /**
     * A page rendered by somebody else's browser, read off the accessibility tree.
     *
     * This is what closes the gap between Chrome and the built-in browser. Judging a
     * foreign page on its hostname alone means anything with a neutral name gets through,
     * which is most of what people actually need blocked — the name of a site is chosen by
     * the people who run it, and choosing an innocuous one is free.
     *
     * The view is less complete than what the built-in browser gets: rendered text and
     * whatever the browser exposes as a window title, with no page metadata and no images.
     * That matters more than it sounds. A title counts for more than body text, so a page
     * with no title attached scores lower for the same content — which would mean Chrome
     * quietly under-blocking relative to the built-in browser. Passing the window title
     * through is not a thumb on the scale; it is restoring a signal the classifier is
     * already tuned to expect.
     *
     * What stays missing is images, so this can catch a page but cannot blur one picture
     * on an otherwise fine one. That remains the honest difference between the two.
     */
    fun evaluateForeignPage(
        url: String,
        title: String,
        text: String,
        route: RouteContext,
    ): EvaluatedPage {
        val input = ContentInput(url = url, title = title, text = text, route = route)
        val classification = classify(input)
        val decision = rulesEngine.evaluateContent(input, classification, _rules.value, _usage.value)
        val entryId = if (decision.isAllowed) null else record(Urls.host(url), decision)
        return EvaluatedPage(entryId, classification, decision)
    }

    /**
     * The path for a hostname with no page content — a DNS question, or an address bar
     * read out of somebody else's browser.
     */
    fun evaluateHost(host: String, route: RouteContext): EvaluatedPage {
        val classification = classify(ContentInput(url = "https://$host", route = route))
        val decision = rulesEngine.evaluateHost(host, route, _rules.value, _usage.value, classification)
        val entryId = if (decision.isAllowed) null else record(host, decision)
        return EvaluatedPage(entryId, classification, decision)
    }

    /**
     * Apps that can never be blocked — the launcher, Settings, the dialer, Aegis itself.
     * Resolved from the platform and refreshed when the guard connects, since the user
     * can install a new launcher at any time.
     */
    @Volatile
    private var criticalPackages: Set<String> = setOf(appContext.packageName)

    fun refreshCriticalPackages() {
        criticalPackages = CriticalPackages.resolve(appContext)
    }

    fun isCritical(packageName: String): Boolean = packageName in criticalPackages

    fun evaluateApp(packageName: String): Decision =
        rulesEngine.evaluateApp(packageName, _rules.value, _usage.value, criticalPackages)

    fun effectiveMode(category: Category) = rulesEngine.effectiveMode(category, _rules.value)

    // ------------------------------------------------------------------------ usage

    /** Fold foreground time into the budgets. Called by the accessibility service. */
    fun recordUsage(seconds: Int, packageName: String?, categories: Set<Category> = emptySet()) {
        if (seconds <= 0) return
        scope.launch {
            writeLock.withLock {
                val updated = tracker.record(_usage.value, _rules.value, seconds, packageName, categories)
                _usage.value = updated
                store.saveUsage(updated)
            }
        }
    }

    fun budgetKeyForApp(packageName: String): String = BudgetKeys.forPackage(packageName)

    fun budgetKeyForCategory(category: Category): String = BudgetKeys.forCategory(category)

    suspend fun requestGraceTap(key: String): GraceRequestResult = writeLock.withLock {
        val result = tracker.requestGraceTap(_usage.value, _rules.value, key)
        _usage.value = result.state
        store.saveUsage(result.state)
        result
    }

    suspend fun claimGraceTap(key: String): GraceClaimResult = writeLock.withLock {
        val result = tracker.claimGraceTap(_usage.value, _rules.value, key)
        _usage.value = result.state
        store.saveUsage(result.state)
        result
    }

    fun graceSecondsRemaining(key: String): Int = tracker.graceSecondsRemaining(_usage.value, key)

    fun graceTapsRemaining(): Int = tracker.graceTapsRemaining(_usage.value, _rules.value)

    // ------------------------------------------------------------------ rule changes

    /**
     * The only way rules ever change.
     *
     * Nothing else in the app writes to the rule set — every edit, from every screen,
     * comes through here and is therefore subject to §3.6. If a future screen needs a
     * shortcut, it does not get one.
     */
    suspend fun submitRuleChange(proposed: RuleSet, note: String = ""): ChangeOutcome = writeLock.withLock {
        val current = _rules.value
        val outcome = coolingOff.submit(current, proposed, idSeed = newId("change"), note = note)

        _rules.value = outcome.appliedNow
        store.saveRules(outcome.appliedNow)

        outcome.queued?.let { queued ->
            // enqueue, not plain append: one control, one queued change.
            val updated = coolingOff.enqueue(_pending.value, queued)
            _pending.value = updated
            store.savePending(updated)

            // The tap appeared to do nothing. Say why, immediately — a control that
            // silently refuses to move is the single fastest way to lose a user's trust
            // in a tool whose whole job is refusing things.
            _notices.tryEmit(
                ChangeNotice.Deferred(
                    summary = queued.summary,
                    minutesRemaining = coolingOff.minutesRemaining(queued),
                ),
            )

            // §3.7: weakening a rule is exactly the moment a partner should hear about it.
            current.partner?.let { partner ->
                PartnerNotifier.notifyWeakening(appContext, partner, queued)
            }
        }
        outcome
    }

    /** Apply anything whose delay has run out. Called on launch, on boot, and on resume. */
    suspend fun applyDueChanges(): List<PendingChange> = writeLock.withLock {
        val result = coolingOff.applyDue(_rules.value, _pending.value)
        if (result.applied.isEmpty()) return@withLock emptyList()

        _rules.value = result.rules
        _pending.value = result.stillPending
        store.saveRules(result.rules)
        store.savePending(result.stillPending)

        for (change in result.applied) {
            _notices.tryEmit(ChangeNotice.Applied(change.summary))
        }
        result.applied
    }

    suspend fun cancelPending(id: String) = writeLock.withLock {
        val updated = coolingOff.cancel(_pending.value, id)
        _pending.value = updated
        store.savePending(updated)
    }

    fun minutesRemaining(change: PendingChange): Int = coolingOff.minutesRemaining(change)

    fun previewChange(proposed: RuleSet) = coolingOff.diff(_rules.value, proposed)

    // ------------------------------------------------------------ log and corrections

    private fun record(host: String, decision: Decision): String {
        val id = newId("log")
        val entry = TransparencyLog.entryFor(id, clock.nowMillis(), host, decision)
        scope.launch {
            writeLock.withLock {
                val updated = _log.value.record(entry)
                _log.value = updated
                store.saveLog(updated)
            }
        }
        return id
    }

    /**
     * Put a scroll interruption in the Record alongside everything else.
     *
     * It belongs there for the same reason blocks do: the Record is the honest account of
     * what Aegis did to you, and an interruption you cannot look back at afterwards is
     * just an app that shouted at you once. Seeing "23 minutes, 40 minutes, 31 minutes"
     * three evenings running is the whole value of the feature.
     */
    fun recordFeedInterruption(appLabel: String, decision: Decision): String =
        record(appLabel, decision)

    fun noteProceededPastWarning(entryId: String) {
        scope.launch {
            writeLock.withLock {
                val updated = _log.value.markProceeded(entryId)
                _log.value = updated
                store.saveLog(updated)
            }
        }
    }

    /**
     * "This was wrong" (§3.10): record the correction and actually retrain.
     */
    suspend fun correct(entry: LogEntry, correction: Correction) = writeLock.withLock {
        val category = entry.category
        if (category != null) {
            val updated = when (correction) {
                Correction.FALSE_POSITIVE ->
                    learner.correctFalsePositive(_overrides.value, category, entry.evidence)
                Correction.MISSED ->
                    learner.correctMiss(_overrides.value, category, text = entry.host, url = entry.host)
            }
            _overrides.value = updated
            classifier = LexicalClassifier(overrides = updated, clock = clock)
            store.saveOverrides(updated)
        }

        val log = _log.value.withCorrection(entry.id, correction)
        _log.value = log
        store.saveLog(log)
    }

    fun learnedAdjustments() = learner.describe(_overrides.value)

    suspend fun resetLearning(category: Category) = writeLock.withLock {
        val updated = learner.resetCategory(_overrides.value, category)
        _overrides.value = updated
        classifier = LexicalClassifier(overrides = updated, clock = clock)
        store.saveOverrides(updated)
    }

    suspend fun completeOnboarding() = store.markOnboardingComplete()

    // ----------------------------------------------------------------------- updates

    private val _updateAvailable = MutableStateFlow<AvailableUpdate?>(null)
    val updateAvailable: StateFlow<AvailableUpdate?> = _updateAvailable.asStateFlow()

    private val _updateChecksEnabled = MutableStateFlow(true)
    val updateChecksEnabled: StateFlow<Boolean> = _updateChecksEnabled.asStateFlow()

    suspend fun setUpdateChecksEnabled(enabled: Boolean) {
        _updateChecksEnabled.value = enabled
        store.setUpdateChecksEnabled(enabled)
        if (!enabled) _updateAvailable.value = null
    }

    /**
     * Look for a newer build.
     *
     * [force] bypasses both the opt-out and the once-a-day throttle, for the "Check now"
     * button — an explicit tap is a request, not background chatter.
     */
    suspend fun checkForUpdate(force: Boolean = false): UpdateCheck {
        if (!force && !_updateChecksEnabled.value) return UpdateCheck.UpToDate
        if (!force) {
            val last = lastUpdateCheck
            if (last > 0 && clock.nowMillis() - last < UPDATE_CHECK_INTERVAL_MILLIS) {
                return _updateAvailable.value?.let { UpdateCheck.Available(it) } ?: UpdateCheck.UpToDate
            }
        }
        val result = withContext(Dispatchers.IO) { UpdateChecker.check(appContext) }
        lastUpdateCheck = clock.nowMillis()
        store.setLastUpdateCheck(lastUpdateCheck)
        _updateAvailable.value = (result as? UpdateCheck.Available)?.update
        return result
    }

    @Volatile
    private var lastUpdateCheck: Long = 0L

    private fun newId(prefix: String): String {
        logSequence += 1
        return "$prefix-${clock.nowMillis()}-$logSequence"
    }

    companion object {
        /** Once a day is plenty for a project that ships when it ships. */
        private const val UPDATE_CHECK_INTERVAL_MILLIS = 24 * 60 * 60 * 1000L

        @Volatile
        private var instance: AegisEngine? = null

        fun get(context: Context): AegisEngine =
            instance ?: synchronized(this) {
                instance ?: AegisEngine(context).also { instance = it }
            }
    }
}

data class EvaluatedPage(
    /** Null when nothing was logged, which is the case for an ordinary allowed request. */
    val logEntryId: String?,
    val classification: Classification,
    val decision: Decision,
)

/** Something the user did that needs saying out loud. */
sealed interface ChangeNotice {
    /** The edit was held back by the cooling-off period. */
    data class Deferred(val summary: String, val minutesRemaining: Int) : ChangeNotice

    /** A change that had been waiting has now landed. */
    data class Applied(val summary: String) : ChangeNotice
}
