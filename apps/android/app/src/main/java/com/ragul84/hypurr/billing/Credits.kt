package com.ragul84.hypurr.billing

import android.app.Activity
import com.ragul84.hypurr.BuildConfig
import com.revenuecat.purchases.ProductType
import com.revenuecat.purchases.PurchaseParams
import com.revenuecat.purchases.Purchases
import com.revenuecat.purchases.PurchasesConfiguration
import com.revenuecat.purchases.PurchasesTransactionException
import com.revenuecat.purchases.awaitGetProducts
import com.revenuecat.purchases.awaitLogIn
import com.revenuecat.purchases.awaitPurchase

/**
 * Hypurr credits through RevenueCat over Google Play Billing. The RevenueCat app user id is the
 * computer's gateway subject, so the gateway's RevenueCat webhook credits that computer
 * (cloud/src/gateway/revenuecat.ts). Nothing is charged here without the user confirming in
 * Google Play's own sheet.
 */
object Credits {
    sealed interface Outcome {
        data object Bought : Outcome
        data object Cancelled : Outcome
        data class Failed(val message: String) : Outcome
    }

    /** A RevenueCat key was built in (see app/build.gradle.kts). */
    val configured: Boolean get() = BuildConfig.REVENUECAT_KEY.isNotBlank()

    fun canBuy(appUserId: String?): Boolean = configured && !appUserId.isNullOrBlank()

    private fun ensure(activity: Activity, appUserId: String) {
        if (!Purchases.isConfigured) {
            Purchases.configure(
                PurchasesConfiguration.Builder(activity.applicationContext, BuildConfig.REVENUECAT_KEY)
                    .appUserID(appUserId)
                    .build(),
            )
        }
    }

    suspend fun buy(activity: Activity, appUserId: String, productId: String): Outcome {
        if (!canBuy(appUserId)) return Outcome.Failed("Buying credits isn't available in this build yet.")
        return try {
            ensure(activity, appUserId)
            val rc = Purchases.sharedInstance
            if (rc.appUserID != appUserId) rc.awaitLogIn(appUserId)
            val product = rc.awaitGetProducts(listOf(productId), ProductType.INAPP).firstOrNull()
                ?: return Outcome.Failed("This credit pack isn't on Google Play yet.")
            rc.awaitPurchase(PurchaseParams.Builder(activity, product).build())
            Outcome.Bought
        } catch (e: PurchasesTransactionException) {
            if (e.userCancelled) Outcome.Cancelled else Outcome.Failed(e.error.message)
        } catch (e: Exception) {
            Outcome.Failed(e.message ?: "Couldn't reach Google Play.")
        }
    }
}
