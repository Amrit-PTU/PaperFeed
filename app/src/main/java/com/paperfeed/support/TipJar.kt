package com.paperfeed.support

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.consumePurchase
import com.android.billingclient.api.queryProductDetails
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Optional tips through Google Play Billing. Create consumable in-app products with the IDs in
 * [PRODUCT_IDS] in the Play Console; until then (or when installed outside Play) the tip
 * buttons simply don't appear.
 */
class TipJar(context: Context) : PurchasesUpdatedListener {
    companion object {
        val PRODUCT_IDS = listOf("tip_small", "tip_medium", "tip_large")
    }

    sealed interface State {
        data object Loading : State
        data object Unavailable : State
        data class Ready(val products: List<ProductDetails>) : State
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = _state
    private val _thanks = MutableStateFlow(false)
    val thanks: StateFlow<Boolean> = _thanks

    private val client = BillingClient.newBuilder(context.applicationContext)
        .setListener(this)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .build()

    fun start() {
        if (client.isReady) {
            scope.launch { loadProducts() }
            return
        }
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    scope.launch { loadProducts() }
                } else {
                    _state.value = State.Unavailable
                }
            }

            override fun onBillingServiceDisconnected() {
                _state.value = State.Unavailable
            }
        })
    }

    private suspend fun loadProducts() {
        val params = QueryProductDetailsParams.newBuilder().setProductList(
            PRODUCT_IDS.map {
                QueryProductDetailsParams.Product.newBuilder()
                    .setProductId(it)
                    .setProductType(BillingClient.ProductType.INAPP)
                    .build()
            }
        ).build()
        val result = client.queryProductDetails(params)
        val products = result.productDetailsList.orEmpty()
            .sortedBy { it.oneTimePurchaseOfferDetails?.priceAmountMicros ?: 0L }
        _state.value = if (products.isEmpty()) State.Unavailable else State.Ready(products)
    }

    fun buy(activity: Activity, product: ProductDetails) {
        val params = BillingFlowParams.newBuilder().setProductDetailsParamsList(
            listOf(BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(product).build())
        ).build()
        client.launchBillingFlow(activity, params)
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        if (result.responseCode != BillingClient.BillingResponseCode.OK || purchases == null) return
        purchases.filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }.forEach { p ->
            // Tips are consumable so they can be given again.
            scope.launch {
                client.consumePurchase(ConsumeParams.newBuilder().setPurchaseToken(p.purchaseToken).build())
                _thanks.value = true
            }
        }
    }

    fun end() {
        if (client.isReady) client.endConnection()
    }
}
