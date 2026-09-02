package com.viwa.android.logging.diagnostics

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ViwaForegroundState
@Inject
constructor() {
    @Volatile
    var activityForegroundCount: Int = 0

    fun isInForeground(): Boolean = activityForegroundCount > 0
}
