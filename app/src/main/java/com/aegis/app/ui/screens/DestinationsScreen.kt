package com.aegis.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.aegis.app.engine.AegisEngine
import com.aegis.app.ui.components.Note
import com.aegis.app.ui.components.Panel
import com.aegis.app.ui.components.SectionHeader
import com.aegis.app.ui.theme.Ash
import com.aegis.app.ui.theme.Evidence
import com.aegis.core.rules.DestinationRule
import com.aegis.core.util.Urls
import kotlinx.coroutines.launch

/**
 * "No side doors" (§3.5).
 *
 * The Messenger→Facebook case, generalised into a list. Each entry means the destination
 * is refused by every route Aegis can see: the Aegis browser, another browser's address
 * bar, an in-app webview, a link preview, and any app's DNS lookup.
 */
@Composable
fun DestinationsScreen() {
    val context = LocalContext.current
    val engine = remember { AegisEngine.get(context) }
    val scope = rememberCoroutineScope()
    val rules by engine.rules.collectAsState()

    var entry by remember { mutableStateOf("") }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            SectionHeader(
                eyebrow = "Destinations",
                title = "Never reach",
                subtitle = "Set once. Enforced by every route — not just the app you deleted.",
            )
        }

        item {
            Panel {
                OutlinedTextField(
                    value = entry,
                    onValueChange = { entry = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Address") },
                    placeholder = { Text("facebook.com") },
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = entry.isNotBlank(),
                    onClick = {
                        val host = Urls.host(entry).ifBlank { entry.trim().lowercase() }
                        if (host.isNotBlank()) {
                            scope.launch {
                                engine.submitRuleChange(
                                    rules.withDestinationRule(DestinationRule(host = host)),
                                    note = "Never reach $host",
                                )
                                entry = ""
                            }
                        }
                    },
                ) {
                    Text("Add")
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Subdomains are included. Adding one takes effect immediately; " +
                        "removing one waits out the cooling-off period.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (rules.destinationRules.isEmpty()) {
            item { Note("Nothing on the list yet.") }
        }

        items(rules.destinationRules, key = { it.host }) { rule ->
            Panel {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = rule.host,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = Evidence,
                        )
                        Text(
                            text = rule.routes.joinToString(", ") { it.label },
                            style = MaterialTheme.typography.bodySmall,
                            color = Ash,
                        )
                    }
                    TextButton(onClick = {
                        scope.launch {
                            engine.submitRuleChange(
                                rules.withoutDestination(rule.host),
                                note = "Allow ${rule.host} again",
                            )
                        }
                    }) {
                        Text("Remove")
                    }
                }
            }
        }

        item {
            Note(
                "Requests to an address typed directly as an IP, or made by an app using its " +
                    "own encrypted resolver, are not visible to the network filter. The Aegis " +
                    "browser and the app guard still cover those.",
            )
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}
