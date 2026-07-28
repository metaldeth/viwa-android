package com.viwa.android.domain.offline

/** Subscription pour linkage for durable water.use + sale.report. */
data class SubscriptionPourContext(
    val clientId: String,
    val requestUuid: String,
    val saleId: String,
    val machineId: String,
    val offlineMode: Boolean,
)
