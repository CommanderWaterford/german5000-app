package com.vocab5000.core.billing

import android.app.Activity
import com.vocab5000.core.database.EntitlementCacheEntity
import com.vocab5000.core.database.EntitlementDao
import com.vocab5000.core.model.AppClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Spec §12.2 entitlement states. */
enum class EntitlementState(val wire: String) {
    FREE("free"),
    PURCHASE_PENDING("purchase_pending"),
    PREMIUM_VERIFIED("premium_verified"),
    VERIFICATION_UNAVAILABLE("verification_unavailable"),
    REVOKED("revoked");

    companion object {
        fun fromWire(value: String?) = entries.firstOrNull { it.wire == value } ?: FREE
    }
}

data class EntitlementStatus(
    /** Persisted state; premium stays verified offline until an authoritative change. */
    val state: EntitlementState,
    /** True when the last attempt to reach Play failed; informational only. */
    val playUnavailable: Boolean,
    val message: String? = null,
) {
    val isPremium: Boolean get() = state == EntitlementState.PREMIUM_VERIFIED
}

/** Schedules durable acknowledgement retries (implemented with WorkManager in the app). */
fun interface AcknowledgementScheduler {
    fun scheduleRetry()
}

/**
 * Source of truth for premium access. Cached verified premium never expires because the
 * device is offline or a query fails; it is revoked only by an authoritative, successful
 * ownership result or refund signal (spec §12.2).
 */
class EntitlementRepository(
    private val gateway: BillingGateway,
    private val dao: EntitlementDao,
    private val verifier: PurchaseVerifier,
    private val clock: AppClock,
    private val scope: CoroutineScope,
    private val ackScheduler: AcknowledgementScheduler,
) {
    private val mutex = Mutex()
    private val transient = MutableStateFlow(Transient())

    private data class Transient(val playUnavailable: Boolean = false, val message: String? = null)

    private val cache = dao.observe(PRODUCT_ID)

    val status: StateFlow<EntitlementStatus> = kotlinx.coroutines.flow.combine(cache, transient) { entity, t ->
        EntitlementStatus(EntitlementState.fromWire(entity?.state), t.playUnavailable, t.message)
    }.stateIn(scope, SharingStarted.Eagerly, EntitlementStatus(EntitlementState.FREE, false))

    val isPremium: StateFlow<Boolean> = status.map { it.isPremium }.stateIn(scope, SharingStarted.Eagerly, false)

    private val _offer = MutableStateFlow<ProductOffer?>(null)
    val offer: StateFlow<ProductOffer?> = _offer.asStateFlow()

    init {
        scope.launch {
            gateway.events.collect { event ->
                when (event) {
                    is PurchaseEvent.Updated -> process(event.purchases, authoritative = false)
                    PurchaseEvent.UserCanceled -> transient.value = Transient(message = null) // no state change
                    is PurchaseEvent.Failed -> transient.value = Transient(message = event.message)
                }
            }
        }
    }

    suspend fun loadOffer(): ProductOffer? {
        val offer = runCatching { gateway.productOffer(PRODUCT_ID) }.getOrNull()
        _offer.value = offer
        transient.value = transient.value.copy(playUnavailable = offer == null)
        return offer
    }

    suspend fun purchase(activity: Activity): LaunchResult {
        val result = gateway.launchPurchase(activity, PRODUCT_ID)
        if (result == LaunchResult.ALREADY_OWNED) restore()
        if (result == LaunchResult.UNAVAILABLE) transient.value = Transient(playUnavailable = true, message = "Google Play purchases are not available on this device right now.")
        return result
    }

    /** Called on app start/resume and from "Restore purchases". */
    suspend fun restore(): OwnedQuery {
        val result = runCatching { gateway.queryOwned() }.getOrElse { OwnedQuery.Unavailable(it.message ?: "error") }
        when (result) {
            is OwnedQuery.Success -> {
                transient.value = Transient()
                process(result.purchases, authoritative = true)
            }
            is OwnedQuery.Unavailable -> transient.value = Transient(playUnavailable = true, message = result.message)
        }
        return result
    }

    /** Retries pending acknowledgements; returns true when nothing is left to acknowledge. */
    suspend fun retryAcknowledgement(): Boolean {
        val cached = dao.get(PRODUCT_ID) ?: return true
        if (cached.state != EntitlementState.PREMIUM_VERIFIED.wire || cached.acknowledged) return true
        val token = cached.purchaseToken ?: return true
        val ok = runCatching { gateway.acknowledge(token) }.getOrDefault(false)
        if (ok) dao.upsert(cached.copy(acknowledged = true, checkedAt = clock.nowMillis()))
        return ok
    }

    internal suspend fun process(purchases: List<PurchaseRecord>, authoritative: Boolean) = mutex.withLock {
        val ours = purchases.filter { PRODUCT_ID in it.productIds }
        val current = dao.get(PRODUCT_ID)
        val currentState = EntitlementState.fromWire(current?.state)
        val purchased = ours.firstOrNull { it.state == PlayPurchaseState.PURCHASED }
        val pending = ours.firstOrNull { it.state == PlayPurchaseState.PENDING }
        val now = clock.nowMillis()

        when {
            purchased != null -> when (val v = verifier.verify(purchased)) {
                is VerificationResult.Verified -> {
                    // Grant idempotently and persist before acknowledging.
                    dao.upsert(
                        EntitlementCacheEntity(
                            productId = PRODUCT_ID,
                            state = EntitlementState.PREMIUM_VERIFIED.wire,
                            verificationSource = v.source.name,
                            checkedAt = now,
                            purchaseToken = purchased.purchaseToken,
                            acknowledged = purchased.acknowledged || current?.acknowledged == true && current.purchaseToken == purchased.purchaseToken,
                        ),
                    )
                    if (!purchased.acknowledged) {
                        val ok = runCatching { gateway.acknowledge(purchased.purchaseToken) }.getOrDefault(false)
                        if (ok) {
                            dao.get(PRODUCT_ID)?.let { dao.upsert(it.copy(acknowledged = true)) }
                        } else {
                            ackScheduler.scheduleRetry()
                        }
                    }
                }
                VerificationResult.Invalid -> transient.value = Transient(message = "The purchase could not be verified. Please contact support.")
            }
            // Never unlock or acknowledge a pending purchase.
            pending != null -> if (currentState != EntitlementState.PREMIUM_VERIFIED) {
                dao.upsert(EntitlementCacheEntity(PRODUCT_ID, EntitlementState.PURCHASE_PENDING.wire, "BILLING_RESPONSE", now, pending.purchaseToken, false))
            }
            authoritative -> when (currentState) {
                // A successful reconciled ownership result without the product is authoritative.
                EntitlementState.PREMIUM_VERIFIED ->
                    dao.upsert(EntitlementCacheEntity(PRODUCT_ID, EntitlementState.REVOKED.wire, "BILLING_RESPONSE", now, null, false))
                EntitlementState.PURCHASE_PENDING ->
                    dao.upsert(EntitlementCacheEntity(PRODUCT_ID, EntitlementState.FREE.wire, "BILLING_RESPONSE", now, null, false))
                else -> Unit
            }
            else -> Unit
        }
    }

    companion object {
        /** Provisional Play product ID (spec §12.1); confirm in Play Console before release. */
        const val PRODUCT_ID = "german5000_premium_lifetime"
    }
}
