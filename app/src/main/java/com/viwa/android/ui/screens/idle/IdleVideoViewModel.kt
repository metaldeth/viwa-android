package com.viwa.android.ui.screens.idle

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.viwa.android.data.local.db.JsonStoreKeys
import com.viwa.android.data.repository.ConfigRepository
import com.viwa.android.ui.screens.customer.IDLE_VIDEO_IDS_ALL
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject

enum class IdlePhase {
    Hidden,
    Prewarm,
    Visible,
}

@HiltViewModel
class IdleVideoViewModel @Inject constructor(
    private val configRepository: ConfigRepository,
) : ViewModel() {

    companion object {
        const val IDLE_TIMEOUT_MS = 60_000L
        const val IDLE_PREWARM_LEAD_MS = 3_000L
        const val PREWARM_READY_TIMEOUT_MS = 5_000L
    }

    private val _phase = MutableStateFlow(IdlePhase.Hidden)
    val phase: StateFlow<IdlePhase> = _phase.asStateFlow()

    /** Список id включённых видео скринсейвера. Пустой список = ожидание отключено полностью. */
    private val _enabledVideoIds = MutableStateFlow(IDLE_VIDEO_IDS_ALL)
    val enabledVideoIds: StateFlow<List<String>> = _enabledVideoIds.asStateFlow()

    private var screenActive = false
    private var customerFlowBlocked = false

    private fun isIdleAllowed(): Boolean = screenActive && !customerFlowBlocked

    private val phaseScheduler =
        IdleVideoPhaseScheduler(
            scope = viewModelScope,
            isScreenActive = { isIdleAllowed() },
            enabledVideoIds = { _enabledVideoIds.value },
            onPhaseChanged = { _phase.value = it },
        )

    init {
        viewModelScope.launch { loadSettings() }
    }

    private suspend fun loadSettings() {
        val saved = configRepository.get(JsonStoreKeys.IDLE_ENABLED_VIDEOS) ?: return
        runCatching {
            val ids = Json.decodeFromString<List<String>>(saved)
            _enabledVideoIds.value = ids
            if (ids.isEmpty()) {
                phaseScheduler.cancelAndHide()
            }
        }
    }

    private fun saveSettings() {
        viewModelScope.launch {
            configRepository.set(
                JsonStoreKeys.IDLE_ENABLED_VIDEOS,
                Json.encodeToString(_enabledVideoIds.value),
            )
        }
    }

    /** Вызывается при навигации. [active] = true только для Routes.Home. */
    fun setActive(active: Boolean) {
        screenActive = active
        if (!isIdleAllowed()) {
            phaseScheduler.cancelAndHide()
        } else {
            phaseScheduler.scheduleIdle()
        }
    }

    /**
     * Блокирует idle, пока на Home открыт платёж, подписка, чек, налив и т.п.
     * При снятии блокировки запускает новый отсчёт без показа оверлея.
     */
    fun setCustomerFlowBlocked(blocked: Boolean) {
        if (customerFlowBlocked == blocked) return
        customerFlowBlocked = blocked
        if (blocked) {
            phaseScheduler.cancelAndHide()
        } else if (screenActive) {
            phaseScheduler.scheduleIdle()
        }
    }

    /** Любое касание экрана сбрасывает таймер и скрывает оверлей. */
    fun resetTimer() {
        phaseScheduler.cancelAndHide()
        if (isIdleAllowed()) phaseScheduler.scheduleIdle()
    }

    /** Вызывается хостом, когда playerA дошёл до STATE_READY в фазе Prewarm. */
    fun onPrewarmReady() {
        phaseScheduler.onPrewarmReady()
    }

    fun toggleVideo(id: String, enabled: Boolean) {
        val current = _enabledVideoIds.value.toMutableList()
        if (enabled && id !in current) current.add(id)
        else if (!enabled) current.remove(id)
        _enabledVideoIds.value = current
        saveSettings()
        phaseScheduler.scheduleIdle()
    }

    fun enableAllVideos() {
        _enabledVideoIds.value = IDLE_VIDEO_IDS_ALL
        saveSettings()
        phaseScheduler.scheduleIdle()
    }

    /** Пустой список — idle не запускается совсем. */
    fun disableAllVideos() {
        _enabledVideoIds.value = emptyList()
        saveSettings()
        phaseScheduler.cancelAndHide()
    }

    override fun onCleared() {
        super.onCleared()
        phaseScheduler.clear()
    }
}
