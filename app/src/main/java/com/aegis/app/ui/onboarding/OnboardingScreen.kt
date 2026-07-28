package com.aegis.app.ui.onboarding

import android.content.Intent
import android.net.VpnService
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.aegis.app.engine.AegisEngine
import com.aegis.app.service.AegisAccessibilityService
import com.aegis.app.service.AegisVpnService
import com.aegis.app.ui.components.Ledger
import com.aegis.app.ui.components.Panel
import com.aegis.app.ui.components.Seal
import com.aegis.app.ui.components.StatRow
import com.aegis.app.ui.theme.Ash
import com.aegis.app.ui.theme.Brass
import com.aegis.core.model.Category
import com.aegis.core.rules.CategoryRule
import com.aegis.core.rules.RuleMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * First run.
 *
 * There was none before, and it was the single largest usability failure: the app opened
 * on a screen of unexplained switches, with rules already in force and a cooling-off lock
 * already armed, so the first thing a new user did was get stuck.
 *
 * Four steps, in the order the decisions actually depend on each other: understand what
 * this is, choose what it holds, give it the reach to hold it, then decide whether to be
 * held. Nothing here is a tour — every step changes real state, and the last one is the
 * only irreversible decision in the app, so it comes last and says so.
 */
@Composable
fun OnboardingScreen(onFinished: () -> Unit) {
    val context = LocalContext.current
    val engine = remember { AegisEngine.get(context) }
    val scope = rememberCoroutineScope()
    val rules by engine.rules.collectAsState()

    var step by remember { mutableStateOf(0) }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 8.dp),
        ) {
            Spacer(Modifier.height(32.dp))

            // Progress as four marks rather than a bar: four decisions, not a loading
            // process, and you can see how few are left.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                repeat(4) { index ->
                    Box(
                        Modifier
                            .weight(1f)
                            .height(2.dp)
                            .clip(MaterialTheme.shapes.extraSmall)
                            .then(
                                if (index <= step) {
                                    Modifier.background(Brass)
                                } else {
                                    Modifier.background(Ash.copy(alpha = 0.25f))
                                },
                            ),
                    )
                }
            }

            Spacer(Modifier.height(28.dp))

            when (step) {
                0 -> WhatThisIs(onNext = { step = 1 })
                1 -> ChooseWhatItHolds(
                    currentRules = rules,
                    onSet = { category, mode ->
                        scope.launch {
                            engine.submitRuleChange(
                                rules.withCategoryRule(rules.ruleFor(category).copy(mode = mode)),
                            )
                        }
                    },
                    onNext = { step = 2 },
                )
                2 -> GiveItReach(onNext = { step = 3 })
                3 -> DecideToBeHeld(
                    coolingOffHours = rules.coolingOffHours,
                    onSeal = {
                        scope.launch {
                            engine.submitRuleChange(rules.copy(armed = true))
                            engine.completeOnboarding()
                            onFinished()
                        }
                    },
                    onSkip = {
                        scope.launch {
                            engine.completeOnboarding()
                            onFinished()
                        }
                    },
                )
            }

            Spacer(Modifier.height(48.dp))
        }
    }
}

@Composable
private fun WhatThisIs(onNext: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
    ) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Seal(sealed = false, size = 148.dp)
        }

        Spacer(Modifier.height(32.dp))

        Text("Aegis", style = MaterialTheme.typography.displayLarge)
        Spacer(Modifier.height(12.dp))
        Text(
            text = "It judges what a page contains rather than matching a list of " +
                "addresses, so a site it has never seen still gets caught.",
            style = MaterialTheme.typography.bodyLarge,
        )

        Spacer(Modifier.height(24.dp))
        Text(
            text = "What it cannot do",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(10.dp))
        // Said first, not buried in a help page. A blocker that oversells its coverage is
        // worse than one that does less and is honest, because it gets trusted wrongly.
        Ledger(
            listOf(
                "read inside other apps' feeds",
                "see traffic from apps with their own encrypted DNS",
                "stop you uninstalling it",
            ),
        )

        Spacer(Modifier.height(32.dp))
        Button(onClick = onNext, modifier = Modifier.fillMaxWidth()) {
            Text("Start")
        }
    }
}

@Composable
private fun ChooseWhatItHolds(
    currentRules: com.aegis.core.rules.RuleSet,
    onSet: (Category, RuleMode) -> Unit,
    onNext: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 24.dp)) {
            Text("What should it hold?", style = MaterialTheme.typography.displayMedium)
            Spacer(Modifier.height(10.dp))
            Text(
                text = "Sensible defaults are already chosen. Change anything now — while " +
                    "nothing is locked, every change takes effect immediately.",
                style = MaterialTheme.typography.bodyMedium,
                color = Ash,
            )
        }

        Spacer(Modifier.height(20.dp))

        for (category in Category.entries) {
            val rule = currentRules.ruleFor(category)
            Panel {
                Text(category.label, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = rule.mode.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = Ash,
                )
                Spacer(Modifier.height(14.dp))
                ModePicker(selected = rule.mode, onSelect = { onSet(category, it) })
            }
        }

        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onNext,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
        ) {
            Text("Next")
        }
    }
}

/**
 * The four modes as one control.
 *
 * A row of equal chips gave no sense that these are a scale from permissive to absolute.
 * Here they read left to right in that order, and the selected one is the only thing in
 * brass, so the current setting is visible without reading the labels.
 */
@Composable
fun ModePicker(
    selected: RuleMode,
    onSelect: (RuleMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        for (mode in RuleMode.entries) {
            val isSelected = mode == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(MaterialTheme.shapes.small)
                    .then(
                        if (isSelected) Modifier.background(Brass.copy(alpha = 0.16f)) else Modifier,
                    )
                    .clickable { onSelect(mode) }
                    .padding(vertical = 11.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = mode.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isSelected) Brass else Ash,
                )
            }
        }
    }
}

@Composable
private fun GiveItReach(onNext: () -> Unit) {
    val context = LocalContext.current
    var filterOn by remember { mutableStateOf(AegisVpnService.isRunning) }
    var guardOn by remember { mutableStateOf(AegisAccessibilityService.isEnabled(context)) }

    val vpnConsent = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) AegisVpnService.start(context)
    }

    LaunchedEffect(Unit) {
        while (true) {
            filterOn = AegisVpnService.isRunning
            guardOn = AegisAccessibilityService.isEnabled(context)
            delay(1_000)
        }
    }

    Column(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 24.dp)) {
            Text("Give it reach", style = MaterialTheme.typography.displayMedium)
            Spacer(Modifier.height(10.dp))
            Text(
                text = "Two permissions, both granted in Android's own settings. Aegis works " +
                    "without them, but only inside its own browser.",
                style = MaterialTheme.typography.bodyMedium,
                color = Ash,
            )
        }

        Spacer(Modifier.height(20.dp))

        Panel {
            StatRow("Network filter", if (filterOn) "ON" else "OFF")
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Blocks named destinations for every app on the phone. It is a local " +
                    "VPN — nothing is sent anywhere for analysis.",
                style = MaterialTheme.typography.bodySmall,
                color = Ash,
            )
            if (!filterOn) {
                Spacer(Modifier.height(14.dp))
                Button(
                    onClick = {
                        val consent = VpnService.prepare(context)
                        if (consent == null) AegisVpnService.start(context) else vpnConsent.launch(consent)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Turn on the filter")
                }
            }
        }

        Panel {
            StatRow("App & route guard", if (guardOn) "ON" else "OFF")
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Enforces app blocks and time budgets, and catches a blocked site " +
                    "opened through another browser.",
                style = MaterialTheme.typography.bodySmall,
                color = Ash,
            )
            if (!guardOn) {
                Spacer(Modifier.height(12.dp))
                // The exact trap that stops people, spelled out in order, because Android
                // greys the toggle out with no explanation for sideloaded apps.
                Ledger(
                    listOf(
                        "1  Settings > Apps > Aegis",
                        "2  three-dot menu > Allow restricted settings",
                        "3  Settings > Accessibility > Aegis > On",
                    ),
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Open Accessibility settings")
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        Column(Modifier.padding(horizontal = 24.dp)) {
            Button(onClick = onNext, modifier = Modifier.fillMaxWidth()) {
                Text("Next")
            }
            TextButton(onClick = onNext, modifier = Modifier.fillMaxWidth()) {
                Text("Skip for now")
            }
        }
    }
}

@Composable
private fun DecideToBeHeld(
    coolingOffHours: Int,
    onSeal: () -> Unit,
    onSkip: () -> Unit,
) {
    var confirming by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
    ) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Seal(sealed = confirming, centreLabel = "${coolingOffHours}h", size = 148.dp)
        }

        Spacer(Modifier.height(32.dp))
        Text("Seal it?", style = MaterialTheme.typography.displayMedium)
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Sealing is what makes this more than a settings screen. Afterwards you " +
                "can still tighten anything instantly — but loosening a rule, or unsealing, " +
                "takes ${coolingOffHours} hours.",
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(14.dp))
        Text(
            text = "The delay is the whole mechanism. It is there to outlast a bad ten " +
                "minutes, not to trap you — you can uninstall Aegis at any time.",
            style = MaterialTheme.typography.bodyMedium,
            color = Ash,
        )

        Spacer(Modifier.height(28.dp))

        AnimatedVisibility(visible = !confirming, enter = fadeIn()) {
            Column {
                Button(
                    onClick = { confirming = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Seal my rules")
                }
                TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) {
                    Text("Not yet — leave everything editable")
                }
            }
        }

        AnimatedVisibility(visible = confirming, enter = fadeIn()) {
            Column {
                Text(
                    text = "Last check. From the moment you tap, loosening anything costs " +
                        "$coolingOffHours hours of waiting.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Brass,
                )
                Spacer(Modifier.height(14.dp))
                Button(onClick = onSeal, modifier = Modifier.fillMaxWidth()) {
                    Text("Seal it")
                }
                TextButton(
                    onClick = { confirming = false },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Back")
                }
            }
        }
    }
}
