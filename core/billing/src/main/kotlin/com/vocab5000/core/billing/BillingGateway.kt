package com.vocab5000.core.billing

import android.app.Activity
import kotlinx.coroutines.flow.Flow

enum class PlayPurchaseState { PURCHASED, PENDING, UNSPECIFIED }

/** Billing-library-neutral view of a Play purchase. */
data class PurchaseRecord(
    val productIds: List<String>,
    val purchaseToken: String,
    val state: PlayPurchaseState,
    val acknowledged: Boolean,
    val originalJson: String,
    val signature: String,
)

data class ProductOffer(
    val productId: String,
    val name: String,
    val description: String,
    /** Localised price from Play; never hard-coded in the app. */
    val formattedPrice: String,
)

sealed interface PurchaseEvent {
    data class Updated(val purchases: List<PurchaseRecord>) : PurchaseEvent
    data object UserCanceled : PurchaseEvent
    data class Failed(val message: String) : PurchaseEvent
}

sealed interface OwnedQuery {
    /** Authoritative, successful ownership result. */
    data class Success(val purchases: List<PurchaseRecord>) : OwnedQuery
    data class Unavailable(val message: String) : OwnedQuery
}

enum class LaunchResult { LAUNCHED, UNAVAILABLE, ALREADY_OWNED, ERROR }

/** Adapter boundary so purchase flows can be tested without Play (spec §16.1). */
interface BillingGateway {
    val events: Flow<PurchaseEvent>
    suspend fun connect(): Boolean
    suspend fun productOffer(productId: String): ProductOffer?
    suspend fun launchPurchase(activity: Activity, productId: String): LaunchResult
    suspend fun queryOwned(): OwnedQuery
    suspend fun acknowledge(purchaseToken: String): Boolean
}
