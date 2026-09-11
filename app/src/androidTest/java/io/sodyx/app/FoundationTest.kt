package io.sodyx.app

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.security.NetworkSecurityPolicy
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FoundationTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun blankSurfaceSurvivesActivityRecreation() {
        compose.onRoot().assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.onRoot().assertIsDisplayed()
    }

    @Suppress("DEPRECATION")
    @Test
    fun installedAppRetainsFoundationPrivacyDefaults() {
        val context = compose.activity
        val info = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS
        )
        // AndroidX may contribute an app-private signature permission; no Internet access is granted.
        assertFalse(info.requestedPermissions.orEmpty().contains("android.permission.INTERNET"))
        assertEquals(0, context.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
        assertFalse(NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted)
    }
}
