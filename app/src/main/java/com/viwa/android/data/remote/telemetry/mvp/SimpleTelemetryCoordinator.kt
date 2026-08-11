package com.viwa.android.data.remote.telemetry.mvp

import android.os.Build
import com.viwa.android.BuildConfig
import com.viwa.android.data.local.db.JsonStoreKeys
import com.viwa.android.data.local.security.MachineSecretStore
import com.viwa.android.data.remote.telemetry.ConnectionState
import com.viwa.android.data.repository.ConfigRepository
import com.viwa.android.data.remote.telemetry.v3.TelemetryDispenseSyncCoordinator
import com.viwa.android.di.AppIoScope
import com.viwa.android.domain.model.MachineRegistration
import com.viwa.android.domain.model.TelemetryConfig
import com.viwa.android.hardware.controller.FlowTemperatureStore
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber

/** Координатор Simple Telemetry MVP: REG register + JWT WS + enroll fallback. */
@Singleton
class SimpleTelemetryCoordinator
@Inject
constructor(
    private val apiClient: MvpTelemetryApiClient,
    private val wsManager: MvpTelemetryWebSocketManager,
    private val cellsSyncCoordinator: TelemetryCellsSyncCoordinator,
    private val dispenseSyncCoordinator: TelemetryDispenseSyncCoordinator,
    private val configRepository: ConfigRepository,
    private val machineSecretStore: MachineSecretStore,
    private val jwtCache: MachineJwtCache,
    private val flowTemperatureStore: FlowTemperatureStore,
    private val networkObserver: TelemetryNetworkObserver,
    private val offlineEntitlementCoordinator: com.viwa.android.data.remote.telemetry.mvp.offline.OfflineEntitlementSessionCoordinator,
    private val technicianKeySessionCoordinator: com.viwa.android.data.remote.telemetry.mvp.offline.TechnicianKeySessionCoordinator,
    private val networkValidatedSideEffects: TelemetryNetworkValidatedSideEffectsCoordinator,
    @AppIoScope private val appScope: CoroutineScope,
) {
    init {
        offlineEntitlementCoordinator.onApplicationStart()
        technicianKeySessionCoordinator.onApplicationStart()
        networkObserver.onValidatedAvailable = {
            appScope.launch {
                if (isUserPaused()) return@launch
                wsManager.notifyNetworkValidated()
                networkValidatedSideEffects.scheduleDebounced()
                if (!wsManager.shouldInitiateConnectOnNetworkValidated()) return@launch
                scheduleConnect("network validated")
            }
        }
        networkObserver.onValidatedLost = {
            wsManager.notifyNetworkDegraded()
        }
        networkObserver.start()

        cellsSyncCoordinator.startRecipeDownlinkOverflowHandling(appScope) { reason ->
            scheduleConnect(reason)
        }

        wsManager.cellsSyncHandler =
            object : MvpTelemetryCellsSyncHandler {
                override suspend fun onWebSocketHello(hello: MvpHelloPayloadDto) {
                    dispenseSyncCoordinator.onWebSocketHello()
                    cellsSyncCoordinator.onWebSocketHello(hello)
                }

                override suspend fun onCellsSnapshot(payloadJson: String) {
                    cellsSyncCoordinator.onCellsSnapshot(payloadJson)
                }

                override suspend fun onSchemaAck(payload: kotlinx.serialization.json.JsonObject) {
                    cellsSyncCoordinator.onSchemaAck(payload)
                }

                override suspend fun onRecipeSyncControl(payloadJson: String) {
                    cellsSyncCoordinator.onRecipeSyncControl(payloadJson)
                }

                override suspend fun onRecipeCommand(payloadJson: String) {
                    cellsSyncCoordinator.onRecipeCommand(payloadJson)
                }

                override suspend fun onRecipeDisconnect() {
                    cellsSyncCoordinator.onRecipeDisconnect()
                }
            }
    }

    private val json =
        Json {
            ignoreUnknownKeys = true
        }

    val connectionState: StateFlow<ConnectionState> = wsManager.connectionState

    private val lifecycleMutex = Mutex()
    private var connectJob: Job? = null

    private suspend fun isUserPaused(): Boolean =
        configRepository.get(JsonStoreKeys.TELEMETRY_PAUSED_BY_USER) == "true"

    private suspend fun setUserPaused(paused: Boolean) {
        configRepository.set(
            JsonStoreKeys.TELEMETRY_PAUSED_BY_USER,
            if (paused) "true" else "false",
        )
    }

    suspend fun loadTelemetryConfig(): TelemetryConfig {
        val raw =
            configRepository.getJson(JsonStoreKeys.TELEMETRY_CONFIG)
                ?: return TelemetryConfig.normalize(TelemetryConfig())
        return runCatching { json.decodeFromString<TelemetryConfig>(raw) }
            .getOrDefault(TelemetryConfig())
            .let { TelemetryConfig.normalize(it) }
    }

    suspend fun saveTelemetryConfig(config: TelemetryConfig) {
        val normalized = TelemetryConfig.normalize(config)
        configRepository.setJson(
            JsonStoreKeys.TELEMETRY_CONFIG,
            json.encodeToString(TelemetryConfig.serializer(), normalized),
        )
    }

    suspend fun loadMachineRegistration(): MachineRegistration {
        val raw =
            configRepository.getJson(JsonStoreKeys.MACHINE_REGISTRATION)
                ?: return MachineRegistration.migrateLegacy(MachineRegistration())
        return runCatching { json.decodeFromString<MachineRegistration>(raw) }
            .getOrDefault(MachineRegistration())
            .let { MachineRegistration.migrateLegacy(it) }
    }

    suspend fun saveMachineRegistration(reg: MachineRegistration) {
        val sanitized =
            reg.copy(
                machineCredential = "",
                machineKey = "",
            )
        configRepository.setJson(
            JsonStoreKeys.MACHINE_REGISTRATION,
            json.encodeToString(MachineRegistration.serializer(), sanitized),
        )
    }

    private suspend fun persistRegistrationMetadata(reg: MachineRegistration) {
        configRepository.setJson(
            JsonStoreKeys.MACHINE_REGISTRATION,
            json.encodeToString(MachineRegistration.serializer(), reg),
        )
    }

    /**
     * Repairs enrollment metadata when [MachineSecretStore] still has a secret but JsonStore flags
     * were cleared or migrated incompletely (e.g. partial cloud backup restore).
     */
    suspend fun reconcilePersistedRegistration(): MachineRegistration {
        var reg = loadMachineRegistration().let { MachineRegistration.migrateLegacy(it) }
        val serial = SerialNumberUtils.normalize(reg.serialNumber)
        if (serial.isNotBlank() && machineSecretStore.hasSecret(serial) && !MachineRegistration.isEnrolled(reg)) {
            reg =
                reg.copy(
                    authScheme = MachineRegistration.AUTH_SCHEME_STABLE_SECRET,
                    machineCredential = "",
                    machineKey = "",
                    isRegistered = true,
                    enrolled = true,
                )
            persistRegistrationMetadata(reg)
            Timber.i("SimpleTelemetry: reconciled enrollment from stored machineSecret serial=$serial")
        }
        return ensureIdentity(reg)
    }

    suspend fun canReconnectWithPersistedCredentials(): Boolean {
        val reg = reconcilePersistedRegistration()
        return MachineRegistration.isEnrolled(reg) && hasAuthMaterial(reg)
    }

    suspend fun ensureIdentity(reg: MachineRegistration): MachineRegistration {
        var updated = reg
        if (updated.installationId.isBlank()) {
            updated = updated.copy(installationId = UUID.randomUUID().toString())
        }
        if (updated.authScheme != MachineRegistration.AUTH_SCHEME_STABLE_SECRET &&
            updated.machineCredential.isBlank() &&
            !machineSecretStore.hasSecret(updated.serialNumber)
        ) {
            updated =
                updated.copy(
                    machineCredential =
                        if (updated.machineKey.startsWith("mch_")) {
                            updated.machineKey
                        } else {
                            MachineCredentialGenerator.generate()
                        },
                    authScheme = MachineRegistration.AUTH_SCHEME_LEGACY_CREDENTIAL,
                    isRegistered = true,
                    enrolled = true,
                )
        } else if (
            updated.authScheme == MachineRegistration.AUTH_SCHEME_LEGACY_CREDENTIAL &&
            updated.machineCredential.isNotBlank() &&
            (!updated.isRegistered || !updated.enrolled)
        ) {
            updated = updated.copy(isRegistered = true, enrolled = true)
        }
        if (updated != reg) {
            configRepository.setJson(
                JsonStoreKeys.MACHINE_REGISTRATION,
                json.encodeToString(MachineRegistration.serializer(), updated),
            )
        }
        return updated
    }

    suspend fun hasStableSecret(serialNumber: String): Boolean =
        machineSecretStore.hasSecret(SerialNumberUtils.normalize(serialNumber))

    suspend fun registerMachine(
        registrationKey: String,
        serialNumber: String,
    ): Result<Unit> {
        val normalizedKey = RegistrationKeyUtils.normalize(registrationKey)
        RegistrationKeyUtils.validationMessage(normalizedKey)?.let { msg ->
            return Result.failure(IllegalArgumentException(msg))
        }
        val normalizedSerial = SerialNumberUtils.normalize(serialNumber)
        SerialNumberUtils.validationMessage(normalizedSerial)?.let { msg ->
            return Result.failure(IllegalArgumentException(msg))
        }
        val config = loadTelemetryConfig()
        val reg = ensureIdentity(loadMachineRegistration())
        val previousSerial = SerialNumberUtils.normalize(reg.serialNumber)
        val request =
            RegisterRequestDto(
                registrationKey = normalizedKey,
                serialNumber = normalizedSerial,
                installationId = reg.installationId,
                device =
                    EnrollDeviceDto(
                        manufacturer = Build.MANUFACTURER.orEmpty(),
                        model = Build.MODEL.orEmpty(),
                        androidVersion = Build.VERSION.RELEASE.orEmpty(),
                    ),
                app =
                    EnrollAppDto(
                        versionName = BuildConfig.VERSION_NAME,
                        versionCode = BuildConfig.VERSION_CODE,
                        packageName = BuildConfig.APPLICATION_ID,
                    ),
            )
        return apiClient
            .register(config.apiUrl, request)
            .map { response ->
                val enrolledSerial =
                    SerialNumberUtils.normalize(
                        response.serialNumber.ifBlank { normalizedSerial },
                    )
                if (previousSerial.isNotBlank() && previousSerial != enrolledSerial) {
                    machineSecretStore.clearSecret(previousSerial)
                }
                machineSecretStore.saveSecret(enrolledSerial, response.machineSecret)
                val wsUrl =
                    MvpTelemetryUrlResolver.resolveWsUrl(
                        apiBaseUrl = config.apiUrl,
                        enrolledWsUrl = response.wsUrl,
                        configuredWsUrl = config.wsUrl,
                    )
                val updated =
                    reg.copy(
                        serialNumber = enrolledSerial,
                        machineId = response.machineId.ifBlank { response.id },
                        installationId = response.installationId.ifBlank { reg.installationId },
                        wsProtocolUrl = wsUrl,
                        tokenEndpoint = response.tokenEndpoint,
                        regKey = normalizedKey,
                        authScheme = MachineRegistration.AUTH_SCHEME_STABLE_SECRET,
                        machineCredential = "",
                        machineKey = "",
                        isRegistered = true,
                        enrolled = true,
                        reservationToken = "",
                        reservationExpiresAt = "",
                    )
                jwtCache.invalidate()
                persistRegistrationMetadata(updated)
                Timber.i("SimpleTelemetry: registered serial=${updated.serialNumber}, auth=stable_secret")
            }
    }

    suspend fun provisionMachine(): Result<Unit> {
        val config = loadTelemetryConfig()
        val regLoaded = loadMachineRegistration()
        if (MachineRegistration.isEnrolled(regLoaded)) {
            return Result.success(Unit)
        }
        val reg = ensureIdentity(regLoaded)
        val previousSerial = SerialNumberUtils.normalize(reg.serialNumber)
        val request =
            ProvisionRequestDto(
                installationId = reg.installationId,
                device =
                    EnrollDeviceDto(
                        manufacturer = Build.MANUFACTURER.orEmpty(),
                        model = Build.MODEL.orEmpty(),
                        androidVersion = Build.VERSION.RELEASE.orEmpty(),
                    ),
                app =
                    EnrollAppDto(
                        versionName = BuildConfig.VERSION_NAME,
                        versionCode = BuildConfig.VERSION_CODE,
                        packageName = BuildConfig.APPLICATION_ID,
                    ),
            )
        return apiClient
            .provision(config.apiUrl, request)
            .map { response ->
                val enrolledSerial =
                    SerialNumberUtils.normalize(
                        response.serialNumber.ifBlank { previousSerial },
                    )
                if (previousSerial.isNotBlank() && previousSerial != enrolledSerial) {
                    machineSecretStore.clearSecret(previousSerial)
                }
                machineSecretStore.saveSecret(enrolledSerial, response.machineSecret)
                val wsUrl =
                    MvpTelemetryUrlResolver.resolveWsUrl(
                        apiBaseUrl = config.apiUrl,
                        enrolledWsUrl = response.wsUrl,
                        configuredWsUrl = config.wsUrl,
                    )
                val regKey =
                    response.registrationKey
                        ?.let { RegistrationKeyUtils.normalize(it) }
                        .orEmpty()
                val updated =
                    reg.copy(
                        serialNumber = enrolledSerial,
                        machineId = response.machineId.ifBlank { response.id },
                        installationId = response.installationId.ifBlank { reg.installationId },
                        wsProtocolUrl = wsUrl,
                        tokenEndpoint = response.tokenEndpoint,
                        regKey = regKey,
                        authScheme = MachineRegistration.AUTH_SCHEME_STABLE_SECRET,
                        machineCredential = "",
                        machineKey = "",
                        isRegistered = true,
                        enrolled = true,
                        reservationToken = "",
                        reservationExpiresAt = "",
                    )
                jwtCache.invalidate()
                persistRegistrationMetadata(updated)
                Timber.i("SimpleTelemetry: provisioned serial=${updated.serialNumber}, auth=stable_secret")
            }
    }

    suspend fun applyUiSerialChange(uiSerial: String): Boolean {
        val reg = loadMachineRegistration()
        val normUi = SerialNumberUtils.normalize(uiSerial)
        val normPersisted = SerialNumberUtils.normalize(reg.serialNumber)
        if (normUi == normPersisted) {
            return false
        }
        disconnectInternal(persistPause = false)
        if (normPersisted.isNotBlank() && machineSecretStore.hasSecret(normPersisted)) {
            machineSecretStore.clearSecret(normPersisted)
        }
        val serialInStore = normUi.ifBlank { normPersisted }
        saveMachineRegistration(
            MachineRegistration.resetAfterSerialChange(
                reg = reg,
                serialInStore = serialInStore,
                newInstallationId = UUID.randomUUID().toString(),
            ),
        )
        Timber.i(
            "SimpleTelemetry: serial field changed ($normPersisted → $normUi), enrollment reset",
        )
        return true
    }

    suspend fun connectWithGuard(uiSerial: String): Result<Unit> {
        SerialNumberUtils.validationMessage(uiSerial)?.let { msg ->
            return Result.failure(IllegalArgumentException(msg))
        }
        val normUi = SerialNumberUtils.normalize(uiSerial)
        val reg = loadMachineRegistration()
        val normPersisted = SerialNumberUtils.normalize(reg.serialNumber)
        if (normUi != normPersisted) {
            return Result.failure(
                IllegalStateException(
                    "Серийный номер в поле ($normUi) не совпадает с сохранённым ($normPersisted). " +
                        "Сначала выполните регистрацию.",
                ),
            )
        }
        if (!MachineRegistration.isEnrolled(reg)) {
            return Result.failure(
                IllegalStateException("Машина не зарегистрирована. Нажмите «Регистрация»."),
            )
        }
        if (!hasAuthMaterial(reg)) {
            return Result.failure(
                IllegalStateException(
                    "Нет секрета для серийного номера $normUi. Выполните регистрацию.",
                ),
            )
        }
        setUserPaused(false)
        scheduleConnect("явное подключение")
        return Result.success(Unit)
    }

    private suspend fun hasAuthMaterial(reg: MachineRegistration): Boolean {
        val serial = SerialNumberUtils.normalize(reg.serialNumber)
        if (machineSecretStore.hasSecret(serial)) {
            return true
        }
        val legacy =
            reg.machineCredential.ifBlank {
                if (reg.machineKey.startsWith("mch_")) reg.machineKey else ""
            }
        return legacy.isNotBlank()
    }

    fun connectAuto() {
        appScope.launch {
            if (isUserPaused()) {
                Timber.d("SimpleTelemetry: connectAuto пропущен — пауза пользователя")
                return@launch
            }
            setUserPaused(false)
            scheduleConnect("автоподключение")
        }
    }

    fun connect() {
        connectAuto()
    }

    suspend fun reserveFreeSerial(): Result<String> {
        val config = loadTelemetryConfig()
        val reg = ensureIdentity(loadMachineRegistration())
        return apiClient
            .reserveSerial(config.apiUrl, reg.installationId)
            .map { response ->
                val normalized = SerialNumberUtils.normalize(response.serialNumber)
                configRepository.setJson(
                    JsonStoreKeys.MACHINE_REGISTRATION,
                    json.encodeToString(
                        MachineRegistration.serializer(),
                        reg.copy(
                            serialNumber = normalized,
                            reservationToken = response.reservationToken,
                            reservationExpiresAt = response.expiresAt,
                        ),
                    ),
                )
                normalized
            }
    }

    suspend fun enrollMachine(serialNumber: String, rebind: Boolean): Result<Unit> {
        val normalized = SerialNumberUtils.normalize(serialNumber)
        SerialNumberUtils.validationMessage(normalized)?.let { msg ->
            return Result.failure(IllegalArgumentException(msg))
        }
        val config = loadTelemetryConfig()
        var reg = ensureIdentity(loadMachineRegistration())
        val reservationToken = reg.reservationToken.takeIf { it.isNotBlank() }
        val request =
            EnrollRequestDto(
                installationId = reg.installationId,
                serialNumber = normalized,
                reservationToken = reservationToken,
                credential = reg.machineCredential,
                rebind = rebind,
                device =
                    EnrollDeviceDto(
                        manufacturer = Build.MANUFACTURER.orEmpty(),
                        model = Build.MODEL.orEmpty(),
                        androidVersion = Build.VERSION.RELEASE.orEmpty(),
                    ),
                app =
                    EnrollAppDto(
                        versionName = BuildConfig.VERSION_NAME,
                        versionCode = BuildConfig.VERSION_CODE,
                        packageName = BuildConfig.APPLICATION_ID,
                    ),
            )
        return apiClient
            .enroll(config.apiUrl, request)
            .map { response ->
                val wsUrl =
                    MvpTelemetryUrlResolver.resolveWsUrl(
                        apiBaseUrl = config.apiUrl,
                        enrolledWsUrl = response.wsProtocolUrl ?: response.wsUrl,
                        configuredWsUrl = config.wsUrl,
                    )
                reg =
                    reg.copy(
                        serialNumber = response.serialNumber.ifBlank { normalized },
                        machineId = response.machineId.ifBlank { reg.machineId },
                        machineKey = reg.machineCredential,
                        machineCredential = reg.machineCredential,
                        authScheme = MachineRegistration.AUTH_SCHEME_LEGACY_CREDENTIAL,
                        wsProtocolUrl = wsUrl,
                        isRegistered = true,
                        enrolled = true,
                        reservationToken = "",
                        reservationExpiresAt = "",
                    )
                configRepository.setJson(
                    JsonStoreKeys.MACHINE_REGISTRATION,
                    json.encodeToString(MachineRegistration.serializer(), reg),
                )
                Timber.i("SimpleTelemetry: enrolled serial=${reg.serialNumber}")
            }
    }

    fun disconnect() {
        appScope.launch {
            disconnectInternal(persistPause = true)
        }
    }

    fun disconnectWithoutPause() {
        appScope.launch {
            disconnectInternal(persistPause = false)
        }
    }

    private suspend fun disconnectInternal(persistPause: Boolean) {
        if (persistPause) {
            setUserPaused(true)
        }
        jwtCache.invalidate()
        connectJob?.cancel()
        connectJob = null
        wsManager.disconnect()
    }

    fun reconnect() {
        appScope.launch {
            if (isUserPaused()) {
                Timber.d("SimpleTelemetry: reconnect пропущен — пауза пользователя")
                return@launch
            }
            setUserPaused(false)
            scheduleConnect("reconnect")
        }
    }

    private fun scheduleConnect(reason: String) {
        connectJob?.cancel()
        wsManager.disconnect()
        connectJob =
            appScope.launch {
                lifecycleMutex.withLock {
                    if (isUserPaused()) return@launch
                    connectInternal(reason)
                }
            }
    }

    private suspend fun connectInternal(reason: String) {
        if (isUserPaused()) return
        val config = loadTelemetryConfig()
        val reg = reconcilePersistedRegistration()
        if (!MachineRegistration.isEnrolled(reg)) {
            Timber.w("SimpleTelemetry: registration required ($reason)")
            return
        }
        if (!hasAuthMaterial(reg)) {
            val serial = SerialNumberUtils.normalize(reg.serialNumber)
            Timber.w(
                "SimpleTelemetry: enrolled but no auth material for serial=$serial ($reason) — " +
                    "re-register or restore machineSecret",
            )
            return
        }
        val wsUrl =
            MvpTelemetryUrlResolver.resolveWsUrl(
                apiBaseUrl = config.apiUrl,
                enrolledWsUrl = reg.wsProtocolUrl,
                configuredWsUrl = config.wsUrl,
            )
        Timber.i("SimpleTelemetry: WS ($reason) url=$wsUrl")
        cellsSyncCoordinator.warmUp()
        wsManager.connect(
            wsUrl = wsUrl,
            tokenProvider = { resolveWsBearerToken(config, reg) },
            temperatureProvider = { flowTemperatureStore.temperatureCForTelemetry() },
            onAuthFailure = { jwtCache.invalidate() },
        )
    }

    private suspend fun resolveWsBearerToken(
        config: TelemetryConfig,
        reg: MachineRegistration,
    ): String? {
        val serial = reg.serialNumber
        val stableSecret = machineSecretStore.getSecret(serial)
        if (!stableSecret.isNullOrBlank()) {
            return jwtCache
                .getAccessToken(
                    serialNumber = serial,
                    machineSecret = stableSecret,
                ) {
                    apiClient.fetchToken(
                        baseUrl = config.apiUrl,
                        tokenEndpoint = reg.tokenEndpoint,
                        requestBody =
                            TokenRequestDto(
                                serialNumber = serial,
                                machineSecret = stableSecret,
                            ),
                    )
                }.getOrElse { error ->
                    Timber.w(error, "SimpleTelemetry: token fetch failed")
                    if (error is TokenAuthException) {
                        wsManager.reportAuthFailure("Ошибка авторизации JWT")
                    }
                    return null
                }
        }
        val legacyCredential =
            reg.machineCredential.ifBlank {
                if (reg.machineKey.startsWith("mch_")) reg.machineKey else ""
            }
        if (legacyCredential.isNotBlank()) {
            return legacyCredential
        }
        Timber.w("SimpleTelemetry: no auth material for WS")
        return null
    }

    internal suspend fun obtainWsBearerTokenForTests(
        config: TelemetryConfig,
        reg: MachineRegistration,
    ): String? = resolveWsBearerToken(config, reg)
}
