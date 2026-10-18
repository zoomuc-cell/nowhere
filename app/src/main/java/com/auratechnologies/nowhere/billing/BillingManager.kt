package com.auratechnologies.nowhere.billing

import android.app.Activity
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingClient.ProductType
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import java.util.concurrent.CopyOnWriteArrayList

class BillingManager private constructor(app: Application) {

    interface Listener {
        fun onProChanged(isPro: Boolean)
        fun onPurchasePending()
        fun onPurchaseError(result: BillingResult)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val store = ProStore(app)
    private val listeners = CopyOnWriteArrayList<Listener>()

    @Volatile
    private var currentIsPro: Boolean =
        ProStatus.decide(QueryOutcome.NotYetQueried, store.load(), System.currentTimeMillis()).isPro

    /** 현재 Pro 판정. 블로킹 없이 즉시 반환. */
    val isPro: Boolean
        get() = currentIsPro

    // 아래 연결 상태 필드는 메인 스레드에서만 접근
    private var connecting = false
    private var retryScheduled = false
    private var retryDelayMs = INITIAL_RETRY_DELAY_MS

    private val purchasesUpdatedListener = PurchasesUpdatedListener { result, purchases ->
        mainHandler.post { handlePurchasesUpdated(result, purchases.orEmpty()) }
    }

    private val billingClient: BillingClient = BillingClient.newBuilder(app)
        .setListener(purchasesUpdatedListener)
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder().enableOneTimeProducts().build()
        )
        .enableAutoServiceReconnection()
        .build()

    init {
        mainHandler.post { startConnection() }
    }

    fun addListener(l: Listener) {
        listeners.addIfAbsent(l)
    }

    fun removeListener(l: Listener) {
        listeners.remove(l)
    }

    /** Play에서 구독을 조회해 판정·캐시 저장·승인. onDone은 메인 스레드에서 호출. */
    fun refresh(onDone: ((Boolean) -> Unit)? = null) {
        if (!billingClient.isReady) {
            mainHandler.post {
                applyDecision(QueryOutcome.Failure)
                onDone?.invoke(currentIsPro)
            }
            return
        }
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(ProductType.SUBS)
            .build()
        billingClient.queryPurchasesAsync(params) { result, purchases ->
            mainHandler.post {
                val outcome = if (result.responseCode == BillingResponseCode.OK) {
                    QueryOutcome.Success(purchases.map(::toSnapshot))
                } else {
                    Log.w(TAG, "queryPurchases failed: ${result.responseCode} ${result.debugMessage}")
                    QueryOutcome.Failure
                }
                applyDecision(outcome)
                onDone?.invoke(currentIsPro)
            }
        }
    }

    /** nowhere_pro 상품 정보 조회. onResult는 메인 스레드에서 호출. */
    fun queryPlans(onResult: (BillingResult, ProductDetails?) -> Unit) {
        if (!billingClient.isReady) {
            val result = BillingResult.newBuilder()
                .setResponseCode(BillingResponseCode.SERVICE_DISCONNECTED)
                .setDebugMessage("BillingClient is not connected")
                .build()
            mainHandler.post { onResult(result, null) }
            return
        }
        val product = QueryProductDetailsParams.Product.newBuilder()
            .setProductId(BillingConstants.PRODUCT_ID)
            .setProductType(ProductType.SUBS)
            .build()
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(listOf(product))
            .build()
        billingClient.queryProductDetailsAsync(params) { result, queryResult ->
            val details = queryResult.productDetailsList
                .firstOrNull { it.productId == BillingConstants.PRODUCT_ID }
            mainHandler.post { onResult(result, details) }
        }
    }

    /** 기본 요금제(offerId == null)로 결제 화면 시작. 메인 스레드에서 호출. */
    fun launchPurchase(activity: Activity, details: ProductDetails, basePlanId: String): BillingResult {
        val offerToken = details.subscriptionOfferDetails
            ?.firstOrNull { it.basePlanId == basePlanId && it.offerId == null }
            ?.offerToken
            ?: return BillingResult.newBuilder()
                .setResponseCode(BillingResponseCode.DEVELOPER_ERROR)
                .setDebugMessage("No base plan offer for '$basePlanId' in ${details.productId}")
                .build()
        val productParams = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(details)
            .setOfferToken(offerToken)
            .build()
        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(productParams))
            .build()
        return billingClient.launchBillingFlow(activity, flowParams)
    }

    private fun startConnection() {
        if (billingClient.isReady || connecting) return
        connecting = true
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                mainHandler.post {
                    connecting = false
                    if (result.responseCode == BillingResponseCode.OK) {
                        retryDelayMs = INITIAL_RETRY_DELAY_MS
                        refresh()
                    } else {
                        Log.w(TAG, "Billing setup failed: ${result.responseCode} ${result.debugMessage}")
                        scheduleReconnect()
                    }
                }
            }

            override fun onBillingServiceDisconnected() {
                mainHandler.post {
                    connecting = false
                    scheduleReconnect()
                }
            }
        })
    }

    private fun scheduleReconnect() {
        if (retryScheduled) return
        retryScheduled = true
        val delay = retryDelayMs
        retryDelayMs = (retryDelayMs * 2).coerceAtMost(MAX_RETRY_DELAY_MS)
        mainHandler.postDelayed({
            retryScheduled = false
            startConnection()
        }, delay)
    }

    private fun handlePurchasesUpdated(result: BillingResult, purchases: List<Purchase>) {
        when (result.responseCode) {
            BillingResponseCode.OK -> {
                val snapshots = purchases.map(::toSnapshot)
                if (snapshots.any { it.state == PurchaseState.PURCHASED }) {
                    applyDecision(QueryOutcome.Success(snapshots))
                } else {
                    // 이번 흐름의 구매만 담긴 목록이므로, 확정 구매가 없으면
                    // 전체 조회로 판정해 기존 Pro를 잘못 해제하지 않도록 함
                    refresh()
                }
                if (snapshots.isNotEmpty() && snapshots.all { it.state == PurchaseState.PENDING }) {
                    listeners.forEach { it.onPurchasePending() }
                }
            }
            BillingResponseCode.ITEM_ALREADY_OWNED -> refresh()
            BillingResponseCode.USER_CANCELED -> Unit
            else -> {
                Log.w(TAG, "Purchase failed: ${result.responseCode} ${result.debugMessage}")
                listeners.forEach { it.onPurchaseError(result) }
            }
        }
    }

    // 메인 스레드에서 호출
    private fun applyDecision(outcome: QueryOutcome) {
        val decision = ProStatus.decide(outcome, store.load(), System.currentTimeMillis())
        decision.cacheToSave?.let { store.save(it) }
        decision.tokensToAcknowledge.forEach { acknowledge(it) }
        setPro(decision.isPro)
    }

    private fun acknowledge(token: String) {
        val params = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(token)
            .build()
        billingClient.acknowledgePurchase(params) { result ->
            if (result.responseCode != BillingResponseCode.OK) {
                Log.w(TAG, "acknowledgePurchase failed: ${result.responseCode} ${result.debugMessage}")
            }
        }
    }

    private fun setPro(value: Boolean) {
        if (value == currentIsPro) return
        currentIsPro = value
        listeners.forEach { it.onProChanged(value) }
    }

    private fun toSnapshot(purchase: Purchase): PurchaseSnapshot = PurchaseSnapshot(
        productIds = purchase.products,
        state = when (purchase.purchaseState) {
            Purchase.PurchaseState.PURCHASED -> PurchaseState.PURCHASED
            Purchase.PurchaseState.PENDING -> PurchaseState.PENDING
            else -> PurchaseState.OTHER
        },
        acknowledged = purchase.isAcknowledged,
        purchaseToken = purchase.purchaseToken
    )

    companion object {
        private const val TAG = "BillingManager"
        private const val INITIAL_RETRY_DELAY_MS = 1_000L
        private const val MAX_RETRY_DELAY_MS = 30_000L

        @Volatile
        private var instance: BillingManager? = null

        fun init(app: Application): BillingManager =
            instance ?: synchronized(this) {
                instance ?: BillingManager(app).also { instance = it }
            }

        fun get(): BillingManager =
            instance ?: throw IllegalStateException("BillingManager.init() must be called first")
    }
}
