package com.viwa.android.domain.technician

import com.viwa.android.domain.offline.BoundedTelemetryClock
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class TechnicianSession(
    val technicianKeyId: String,
    val scopes: List<String>,
    val sessionToken: String,
    val expiresAtMs: Long,
)

/** In-memory technician session — cleared on TTL, revocation epoch bump, or app restart. */
@Singleton
class TechnicianSessionStore
@Inject
constructor() {
    private val lock = Any()
    private var session: TechnicianSession? = null
    private var trackedRevocationEpoch: Int = -1

    private val _activeSession = MutableStateFlow<TechnicianSession?>(null)
    val activeSession: StateFlow<TechnicianSession?> = _activeSession.asStateFlow()

    fun establish(
        technicianKeyId: String,
        scopes: List<String>,
        sessionToken: String,
        expiresAtMs: Long,
    ) {
        synchronized(lock) {
            session =
                TechnicianSession(
                    technicianKeyId = technicianKeyId,
                    scopes = scopes,
                    sessionToken = sessionToken,
                    expiresAtMs = expiresAtMs,
                )
            _activeSession.value = session
        }
    }

    fun clear() {
        synchronized(lock) {
            session = null
            _activeSession.value = null
        }
    }

    fun clearIfExpired(nowMs: Long) {
        synchronized(lock) {
            val current = session ?: return
            if (nowMs >= current.expiresAtMs) {
                session = null
                _activeSession.value = null
            }
        }
    }

    fun clearOnRevocationEpochChange(revocationEpoch: Int) {
        synchronized(lock) {
            if (trackedRevocationEpoch >= 0 && revocationEpoch > trackedRevocationEpoch) {
                session = null
                _activeSession.value = null
            }
            trackedRevocationEpoch = revocationEpoch
        }
    }

    fun currentSession(): TechnicianSession? =
        synchronized(lock) {
            session
        }

    fun hasScope(scope: String): Boolean {
        val current = synchronized(lock) { session } ?: return false
        return current.scopes.contains(scope)
    }
}

/** Read-only access layer for service menu — no UI layout changes required. */
@Singleton
class TechnicianServiceMenuAccess
@Inject
constructor(
    private val sessionStore: TechnicianSessionStore,
    private val clock: BoundedTelemetryClock,
) {
    val session: StateFlow<TechnicianSession?> = sessionStore.activeSession

    fun isAuthorized(nowMs: Long = clock.trustedNowMs()): Boolean {
        sessionStore.clearIfExpired(nowMs)
        val current = sessionStore.currentSession() ?: return false
        return current.scopes.contains(TechnicianKeyConstants.SCOPE_SERVICE_MENU)
    }

    fun scopes(nowMs: Long = clock.trustedNowMs()): List<String> {
        sessionStore.clearIfExpired(nowMs)
        return sessionStore.currentSession()?.scopes.orEmpty()
    }

    fun hasScope(scope: String, nowMs: Long = clock.trustedNowMs()): Boolean {
        sessionStore.clearIfExpired(nowMs)
        return sessionStore.hasScope(scope)
    }
}
