package com.viwa.android.ui.screens.idle

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.viwa.android.data.local.db.JsonStoreKeys
import com.viwa.android.data.repository.ConfigRepository
import com.viwa.android.ui.screens.customer.IDLE_VIDEO_IDS_ALL
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject

private const val IDLE_TIMEOUT_MS = 60_000L

/**
 * Attract idle overlay (полноэкранное видео ожидания).
 * На k3568 после ExoPlayer/TextureView киоск залипал на белом экране без UI —
 * пока выключено жёстко. Настройки idle в сервисном меню сохраняются, но оверлей не показывается.
 */
private const val IDLE_OVERLAY_ENABLED = false

@HiltViewModel
class IdleVideoViewModel @Inject constructor(
    private val configRepository: ConfigRepository,
) : ViewModel() {

    private val _isVisible = MutableStateFlow(false)
    val isVisible: StateFlow<Boolean> = _isVisible.asStateFlow()

 /**
 * Список id включённых видео скринсейвера.
 * Пустой список = ожидание отключено полностью (.length === 0).
 */
    private val _enabledVideoIds = MutableStateFlow(IDLE_VIDEO_IDS_ALL)
    val enabledVideoIds: StateFlow<List<String>> = _enabledVideoIds.asStateFlow()

    private var idleJob: Job? = null

 /**
 * true только когда приложение на экране выбора напитков (DrinkListScreen / Home).
 * На PreparingScreen, ServiceScreen и других экранах — false.
 */
    private var screenActive = false

    init {
        viewModelScope.launch { loadSettings() }
    }

    private suspend fun loadSettings() {
        val saved = configRepository.get(JsonStoreKeys.IDLE_ENABLED_VIDEOS) ?: return
        runCatching {
            val ids = Json.decodeFromString<List<String>>(saved)
            _enabledVideoIds.value = ids
            if (ids.isEmpty()) {
                idleJob?.cancel()
                _isVisible.value = false
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

 /**
 * Вызывается при навигации.
 * [active] = true только для Routes.Home (DrinkListScreen).
 * На остальных экранах таймер отменяется и idle не показывается.
 */
    fun setActive(active: Boolean) {
        screenActive = active
        if (!active) {
            idleJob?.cancel()
            _isVisible.value = false
        } else {
            scheduleIdle()
        }
    }

 /** Любое касание экрана сбрасывает таймер и скрывает оверлей. */
    fun resetTimer() {
        _isVisible.value = false
        if (screenActive) scheduleIdle()
    }

    private fun scheduleIdle() {
        idleJob?.cancel()
        _isVisible.value = false
        if (!IDLE_OVERLAY_ENABLED) return
        if (!screenActive || _enabledVideoIds.value.isEmpty()) return
        idleJob = viewModelScope.launch {
            delay(IDLE_TIMEOUT_MS)
            if (screenActive && _enabledVideoIds.value.isNotEmpty()) {
                _isVisible.value = true
            }
        }
    }

 /** Включить / выключить конкретное видео. */
    fun toggleVideo(id: String, enabled: Boolean) {
        val current = _enabledVideoIds.value.toMutableList()
        if (enabled && id !in current) current.add(id)
        else if (!enabled) current.remove(id)
        _enabledVideoIds.value = current
        saveSettings()
        scheduleIdle()
    }

 /** Включить все 14 видео. */
    fun enableAllVideos() {
        _enabledVideoIds.value = IDLE_VIDEO_IDS_ALL
        saveSettings()
        scheduleIdle()
    }

 /**
 * Полностью отключить видео ожидания.
 * Пустой список — idle не запускается совсем (.length === 0).
 */
    fun disableAllVideos() {
        _enabledVideoIds.value = emptyList()
        saveSettings()
        idleJob?.cancel()
        _isVisible.value = false
    }

    override fun onCleared() {
        super.onCleared()
        idleJob?.cancel()
    }
}
