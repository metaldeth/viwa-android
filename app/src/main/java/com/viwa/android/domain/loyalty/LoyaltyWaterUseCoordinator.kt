package com.viwa.android.domain.loyalty

import java.util.concurrent.ConcurrentHashMap

/**
 * Client-side idempotency for [loyalty.water.use]: duplicate pours with the same
 * [requestUuid] must not emit a second WS uplink.
 */
class LoyaltyWaterUseCoordinator {
    private val sentRequestUuids: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun shouldSend(requestUuid: String): Boolean = sentRequestUuids.add(requestUuid)

    fun markSent(requestUuid: String) {
        sentRequestUuids.add(requestUuid)
    }

    fun clear() {
        sentRequestUuids.clear()
    }
}
