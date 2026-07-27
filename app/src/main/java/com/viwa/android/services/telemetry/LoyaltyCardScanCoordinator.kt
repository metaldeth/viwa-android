package com.viwa.android.services.telemetry

import com.viwa.android.di.AppIoScope
import com.viwa.android.hardware.scanner.ViwaScannerTrafficLogger
import com.viwa.android.data.telemetry.loyalty.LoyaltyWsCodec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber

private const val TAG = "LoyaltyCardScan"

/**
 * Глобальная подписка на строки сканера для карт `CLIENT_<uuid>`.
 * После валидного скана → [ViwaTelemetryService.sendStatusGet] + levels.list.
 */
@Singleton
class LoyaltyCardScanCoordinator
    @Inject
    constructor(
        private val scannerTrafficLogger: ViwaScannerTrafficLogger,
        private val telemetryService: ViwaTelemetryService,
        @AppIoScope private val appScope: CoroutineScope,
    ) {
        init {
            appScope.launch {
                var lastSeenId: Int? = null
                scannerTrafficLogger.entries.collect { entries ->
                    val last = entries.lastOrNull() ?: return@collect
                    if (lastSeenId == last.id) return@collect
                    lastSeenId = last.id
                    val parsed = LoyaltyWsCodec.parseClientIdFromScan(last.rawLine)
                    when {
                        parsed.isSuccess -> {
                            val clientUuid = parsed.getOrThrow()
                            Timber.tag(TAG).d("loyalty scan → status.get (uuid=%s)", clientUuid)
                            telemetryService.onLoyaltyCardScanned(clientUuid)
                        }
                        last.rawLine.trim().startsWith("CLIENT_") -> {
                            Timber.tag(TAG).w("invalid loyalty scan: %s", last.rawLine)
                            telemetryService.onInvalidLoyaltyCardScan()
                        }
                    }
                }
            }
        }
    }
