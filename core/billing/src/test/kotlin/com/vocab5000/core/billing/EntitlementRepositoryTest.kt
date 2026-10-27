package com.vocab5000.core.billing

import android.app.Activity
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.vocab5000.core.database.UserDatabase
import com.vocab5000.core.model.FixedClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private class FakeGateway : BillingGateway {
    override val events = MutableSharedFlow<PurchaseEvent>()
    var owned: OwnedQuery = OwnedQuery.Success(emptyList())
    var ackSucceeds = true
    val acknowledged = mutableListOf<String>()
    override suspend fun connect() = true
    override suspend fun productOffer(productId: String) = ProductOffer(productId, "Premium", "", "€9.99")
    override suspend fun launchPurchase(activity: Activity, productId: String) = LaunchResult.LAUNCHED
    override suspend fun queryOwned() = owned
    override suspend fun acknowledge(purchaseToken: String): Boolean {
        if (ackSucceeds) acknowledged += purchaseToken
        return ackSucceeds
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EntitlementRepositoryTest {
    private lateinit var db: UserDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var gateway: FakeGateway
    private lateinit var repo: EntitlementRepository
    private var retries = 0

    private fun purchase(state: PlayPurchaseState, acknowledged: Boolean = false) = PurchaseRecord(
        listOf(EntitlementRepository.PRODUCT_ID), "token-1", state, acknowledged, "{}", "",
    )

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), UserDatabase::class.java).allowMainThreadQueries().build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        gateway = FakeGateway()
        repo = EntitlementRepository(gateway, db.entitlementDao(), PurchaseVerifier(""), FixedClock(1_800_000_000_000L), scope) { retries++ }
    }

    @After fun tearDown() {
        scope.cancel()
        db.close()
    }

    private suspend fun state() = withTimeout(5_000) { db.entitlementDao().get(EntitlementRepository.PRODUCT_ID)?.state }

    /** AT-08: a pending purchase never unlocks or acknowledges. */
    @Test fun pendingDoesNotUnlock() = runBlocking {
        repo.process(listOf(purchase(PlayPurchaseState.PENDING)), authoritative = false)
        assertEquals(EntitlementState.PURCHASE_PENDING.wire, state())
        assertTrue(gateway.acknowledged.isEmpty())
        repo.process(listOf(purchase(PlayPurchaseState.PURCHASED)), authoritative = false)
        assertEquals(EntitlementState.PREMIUM_VERIFIED.wire, state())
        assertEquals(listOf("token-1"), gateway.acknowledged)
    }

    @Test fun pendingThatDisappearsReturnsToFree() = runBlocking {
        repo.process(listOf(purchase(PlayPurchaseState.PENDING)), authoritative = false)
        repo.restore()
        assertEquals(EntitlementState.FREE.wire, state())
    }

    /** AT-02: offline or failing queries never expire cached premium. */
    @Test fun offlineNeverRevokes() = runBlocking {
        repo.process(listOf(purchase(PlayPurchaseState.PURCHASED, acknowledged = true)), authoritative = false)
        gateway.owned = OwnedQuery.Unavailable("offline")
        repo.restore()
        assertEquals(EntitlementState.PREMIUM_VERIFIED.wire, state())
        repo.process(emptyList(), authoritative = false) // e.g. canceled purchase UI
        assertEquals(EntitlementState.PREMIUM_VERIFIED.wire, state())
    }

    /** AT-10: an authoritative reconciled result without the product revokes. */
    @Test fun authoritativeRefundRevokes() = runBlocking {
        repo.process(listOf(purchase(PlayPurchaseState.PURCHASED, acknowledged = true)), authoritative = false)
        gateway.owned = OwnedQuery.Success(emptyList())
        repo.restore()
        assertEquals(EntitlementState.REVOKED.wire, state())
        // Re-purchase restores premium.
        repo.process(listOf(purchase(PlayPurchaseState.PURCHASED)), authoritative = false)
        assertEquals(EntitlementState.PREMIUM_VERIFIED.wire, state())
    }

    @Test fun failedAcknowledgementSchedulesRetryButKeepsPremium() = runBlocking {
        gateway.ackSucceeds = false
        repo.process(listOf(purchase(PlayPurchaseState.PURCHASED)), authoritative = false)
        assertEquals(EntitlementState.PREMIUM_VERIFIED.wire, state())
        assertEquals(1, retries)
        assertFalse(repo.retryAcknowledgement())
        gateway.ackSucceeds = true
        assertTrue(repo.retryAcknowledgement())
        assertTrue(db.entitlementDao().get(EntitlementRepository.PRODUCT_ID)!!.acknowledged)
    }

    /** AT-09: restore after reinstall grants premium from Play ownership alone. */
    @Test fun restoreFromPlayOwnership() = runBlocking {
        gateway.owned = OwnedQuery.Success(listOf(purchase(PlayPurchaseState.PURCHASED, acknowledged = true)))
        repo.restore()
        assertEquals(EntitlementState.PREMIUM_VERIFIED.wire, state())
        assertTrue(withTimeout(5_000) { repo.isPremium.first { it } })
    }
}
