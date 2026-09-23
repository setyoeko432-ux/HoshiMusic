package com.naomi.hoshimusic

import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PrivacyIntegrityTest {

    @Test
    fun `manifest - internet permission must not exist`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val packageInfo = context.packageManager.getPackageInfo(
            context.packageName, 
            PackageManager.GET_PERMISSIONS
        )
        
        val permissions = packageInfo.requestedPermissions ?: emptyArray()
        
        val hasInternet = permissions.contains("android.permission.INTERNET")
        assertFalse("ERROR: android.permission.INTERNET detected in manifest!", hasInternet)
    }

    @Test
    fun `manifest - access network state permission must not exist`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val packageInfo = context.packageManager.getPackageInfo(
            context.packageName, 
            PackageManager.GET_PERMISSIONS
        )
        
        val permissions = packageInfo.requestedPermissions ?: emptyArray()
        
        val hasNetworkState = permissions.contains("android.permission.ACCESS_NETWORK_STATE")
        assertFalse("ERROR: android.permission.ACCESS_NETWORK_STATE detected in manifest!", hasNetworkState)
    }
}
