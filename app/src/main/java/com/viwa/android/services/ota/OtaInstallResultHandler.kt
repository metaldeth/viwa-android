package com.viwa.android.services.ota

import com.viwa.android.domain.ota.AppUpdateCoordinator
import com.viwa.android.di.AppIoScope
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Singleton
class OtaInstallResultHandler
@Inject
constructor(
    private val appUpdateCoordinatorProvider: Provider<AppUpdateCoordinator>,
    @AppIoScope private val scope: CoroutineScope,
) {
    fun onPackageInstallerResult(status: Int, message: String?) {
        scope.launch {
            appUpdateCoordinatorProvider.get().onInstallResult(status, message)
        }
    }
}
