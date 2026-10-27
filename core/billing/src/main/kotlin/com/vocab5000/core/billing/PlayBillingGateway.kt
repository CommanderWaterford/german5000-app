package com.vocab5000.core.billing

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.acknowledgePurchase
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume

/** Google Play Billing adapter. One non-consumable, one-time product (spec §12). */
class PlayBillingGateway(context: Context) : BillingGateway {
    private val _events = MutableSharedFlow<PurchaseEvent>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val events: Flow<PurchaseEvent> = _events

    private val connectMutex = Mutex()
    private val details = mutableMapOf<String, ProductDetails>()

    private val client: BillingClient = BillingClient.newBuilder(context.applicationContext)
        .setListener { result, purchases ->
            when (result.responseCode) {
                BillingClient.BillingResponseCode.OK -> _events.tryEmit(PurchaseEvent.Updated(purchases.orEmpty().map(::toRecord)))
                BillingClient.BillingResponseCode.USER_CANCELED -> _events.tryEmit(PurchaseEvent.UserCanceled)
                BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> _events.tryEmit(PurchaseEvent.Updated(emptyList()))
                else -> _events.tryEmit(PurchaseEvent.Failed(describe(result)))
            }
        }
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection()
        .build()

    override suspend fun connect(): Boolean = connectMutex.withLock {
        if (client.isReady) return true
        suspendCancellableCoroutine { cont ->
            client.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) {
                    if (cont.isActive) cont.resume(result.responseCode == BillingClient.BillingResponseCode.OK)
                }

                override fun onBillingServiceDisconnected() {
                    if (cont.isActive) cont.resume(false)
                }
            })
        }
    }

    override suspend fun productOffer(productId: String): ProductOffer? {
        if (!connect()) return null
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(productId)
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build(),
                ),
            )
            .build()
        val result = client.queryProductDetails(params)
        if (result.billingResult.responseCode != BillingClient.BillingResponseCode.OK) return null
        val product = result.productDetailsList?.firstOrNull { it.productId == productId } ?: return null
        details[productId] = product
        val price = product.oneTimePurchaseOfferDetails?.formattedPrice ?: return null
        return ProductOffer(product.productId, product.name, product.description, price)
    }

    override suspend fun launchPurchase(activity: Activity, productId: String): LaunchResult {
        if (!connect()) return LaunchResult.UNAVAILABLE
        val product = details[productId] ?: productOffer(productId)?.let { details[productId] } ?: return LaunchResult.UNAVAILABLE
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(product).build()),
            )
            .build()
        val result = client.launchBillingFlow(activity, params)
        return when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> LaunchResult.LAUNCHED
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> LaunchResult.ALREADY_OWNED
            BillingClient.BillingResponseCode.BILLING_UNAVAILABLE,
            BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE,
            BillingClient.BillingResponseCode.FEATURE_NOT_SUPPORTED -> LaunchResult.UNAVAILABLE
            else -> LaunchResult.ERROR
        }
    }

    override suspend fun queryOwned(): OwnedQuery {
        if (!connect()) return OwnedQuery.Unavailable("Google Play Billing is not available")
        val result = client.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build(),
        )
        return if (result.billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
            OwnedQuery.Success(result.purchasesList.map(::toRecord))
        } else {
            OwnedQuery.Unavailable(describe(result.billingResult))
        }
    }

    override suspend fun acknowledge(purchaseToken: String): Boolean {
        if (!connect()) return false
        val result = client.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchaseToken).build())
        return result.responseCode == BillingClient.BillingResponseCode.OK
    }

    private fun toRecord(purchase: Purchase) = PurchaseRecord(
        productIds = purchase.products,
        purchaseToken = purchase.purchaseToken,
        state = when (purchase.purchaseState) {
            Purchase.PurchaseState.PURCHASED -> PlayPurchaseState.PURCHASED
            Purchase.PurchaseState.PENDING -> PlayPurchaseState.PENDING
            else -> PlayPurchaseState.UNSPECIFIED
        },
        acknowledged = purchase.isAcknowledged,
        originalJson = purchase.originalJson,
        signature = purchase.signature,
    )

    private fun describe(result: BillingResult) = "Billing response ${result.responseCode}: ${result.debugMessage}"
}
