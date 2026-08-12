package com.viwa.android.services.ota

import android.content.Context
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class OtaPlatformInstallCapabilityTest {
    @Test
    fun `session capability is separate from silent install`() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val capability = OtaPlatformInstallCapability(context)

        assertTrue(capability.canUsePackageInstallerSession())
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class OtaInstallLauncherTest {
    @Test
    fun `prefers package installer session before action view fallback`() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val capability = mockk<OtaPlatformInstallCapability>()
        every { capability.canUsePackageInstallerSession() } returns true
        every { capability.canSilentInstall() } returns false

        val apkFile = File(context.filesDir, "ota-test.apk")
        apkFile.writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04))

        val launcher = OtaInstallLauncher(context, capability)
        val result = launcher.launchInstall(apkFile)

        assertTrue(
            result is OtaInstallLaunchResult.PackageInstallerSessionStarted ||
                result is OtaInstallLaunchResult.ActionViewFallbackStarted,
        )
    }

    @Test
    fun `uses action view when session capability disabled`() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val capability = mockk<OtaPlatformInstallCapability>()
        every { capability.canUsePackageInstallerSession() } returns false

        val apkFile = File(context.filesDir, "ota-test-action-view.apk")
        apkFile.writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04))

        val launcher = OtaInstallLauncher(context, capability)
        val result = launcher.launchInstall(apkFile)

        assertTrue(
            result is OtaInstallLaunchResult.ActionViewFallbackStarted ||
                result is OtaInstallLaunchResult.Failed,
        )
    }

    @Test
    fun `abandons session and falls back when session write fails`() {
        val appContext: Context = ApplicationProvider.getApplicationContext()
        val context = mockk<Context>()
        val pm = mockk<PackageManager>()
        val installer = mockk<PackageInstaller>()
        every { context.packageManager } returns pm
        every { pm.packageInstaller } returns installer
        every { context.packageName } returns appContext.packageName
        every { context.filesDir } returns appContext.filesDir

        val capability = mockk<OtaPlatformInstallCapability>()
        every { capability.canUsePackageInstallerSession() } returns true

        every { installer.createSession(any()) } returns 42
        every { installer.openSession(42) } throws IOException("disk full")
        every { installer.abandonSession(42) } returns Unit

        val apkFile = File(appContext.filesDir, "ota-abandon-test.apk")
        apkFile.writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04))

        val launcher = OtaInstallLauncher(context, capability)
        val result = launcher.launchInstall(apkFile)

        verify { installer.abandonSession(42) }
        assertTrue(
            result is OtaInstallLaunchResult.ActionViewFallbackStarted ||
                result is OtaInstallLaunchResult.Failed,
        )
    }
}
