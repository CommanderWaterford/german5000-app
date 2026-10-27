package com.vocab5000.feature.premium

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vocab5000.core.billing.EntitlementRepository
import com.vocab5000.core.billing.EntitlementState
import com.vocab5000.core.billing.LaunchResult
import com.vocab5000.core.billing.OwnedQuery
import com.vocab5000.core.designsystem.ErrorMessage
import com.vocab5000.core.designsystem.InfoCard
import com.vocab5000.core.designsystem.PrimaryAction
import com.vocab5000.core.designsystem.ScreenTitle
import com.vocab5000.core.designsystem.SecondaryAction
import com.vocab5000.core.designsystem.SectionTitle
import kotlinx.coroutines.launch

/**
 * Paywall (spec §12). Shows the localised Play price and plain terms before purchase.
 * Learning never depends on this screen; purchase and restore need a connection.
 */
@Composable
fun PremiumScreen(entitlements: EntitlementRepository, freeEntryLimit: Int, totalEntries: Int, onBack: () -> Unit) {
    val status by entitlements.status.collectAsStateWithLifecycle()
    val offer by entitlements.offer.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val activity = LocalContext.current as? Activity
    var message by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        entitlements.loadOffer()
        loading = false
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenTitle("German5000 Premium — one-time purchase")
        when (status.state) {
            EntitlementState.PREMIUM_VERIFIED -> InfoCard {
                Text("Premium is active on this device.", style = MaterialTheme.typography.titleMedium)
                Text("All $totalEntries words, their audio and advanced practice filters work offline.")
            }
            EntitlementState.PURCHASE_PENDING -> InfoCard {
                Text("Your purchase is pending.", style = MaterialTheme.typography.titleMedium)
                Text("Premium unlocks once Google Play confirms payment. You can keep studying the free words meanwhile.")
            }
            EntitlementState.REVOKED -> InfoCard {
                Text("Premium is no longer active (for example after a refund). Your history is kept, and free words keep working. Buying again resumes premium words where you left off.")
            }
            else -> Unit
        }
        SectionTitle("What's included")
        Text("• Words ranked ${freeEntryLimit + 1}–$totalEntries with meanings, grammar, examples and audio")
        Text("• Spaced review and practice for all $totalEntries words")
        Text("• Combined practice filters (word type, grammar, your mistakes)")
        SectionTitle("Always free")
        Text("• Words ranked 1–$freeEntryLimit with all learning tools, the full assessment, progress, reminders, backup and accessibility features")
        SectionTitle("Terms")
        Text(
            "A single non-consumable purchase through Google Play, tied to this app and your Google Play account. " +
                "“One-time” means access to this app's offline premium word list; it does not include future cloud services or other language apps. " +
                "No subscription, no ads, no app account. Buying and restoring need an internet connection.",
            style = MaterialTheme.typography.bodyMedium,
        )
        if (status.state != EntitlementState.PREMIUM_VERIFIED) {
            val price = offer?.formattedPrice
            when {
                loading -> Text("Checking Google Play…")
                price != null -> PrimaryAction(
                    "Buy for $price",
                    enabled = status.state != EntitlementState.PURCHASE_PENDING && activity != null,
                    onClick = {
                        scope.launch {
                            message = when (entitlements.purchase(activity!!)) {
                                LaunchResult.LAUNCHED, LaunchResult.ALREADY_OWNED -> null
                                LaunchResult.UNAVAILABLE -> "Google Play purchases aren't available right now. Free learning is unaffected."
                                LaunchResult.ERROR -> "The purchase could not be started. Please try again."
                            }
                        }
                    },
                )
                else -> InfoCard {
                    Text("Google Play isn't reachable on this device right now, so the price can't be shown. Connect to the internet, or use a device with Google Play to purchase. Free learning works offline.")
                }
            }
        }
        SecondaryAction("Restore purchases", onClick = {
            scope.launch {
                message = when (val r = entitlements.restore()) {
                    is OwnedQuery.Success -> if (r.purchases.any { EntitlementRepository.PRODUCT_ID in it.productIds }) "Purchase restored." else "No purchase found for this Google Play account. If you bought Premium with a different account, switch accounts in Google Play and try again."
                    is OwnedQuery.Unavailable -> "Couldn't reach Google Play. Your existing access is unchanged; try again when online."
                }
            }
        })
        message?.let { Text(it) }
        status.message?.let { ErrorMessage(it) }
        SecondaryAction("Back", onClick = onBack)
    }
}
