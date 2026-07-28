package com.viwa.android.services.ota

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import timber.log.Timber

@AndroidEntryPoint
class OtaInstallResultReceiver : BroadcastReceiver() {
    @Inject
    lateinit var resultHandler: OtaInstallResultHandler

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        resultHandler.onPackageInstallerResult(status, message)
        Timber.tag(TAG).i("PackageInstaller result status=%d message=%s", status, message)
    }

    companion object {
        private const val TAG = "OtaInstallResult"
    }
}
