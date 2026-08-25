package com.example.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.example.BuildConfig
import com.example.MainActivity
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AuthDeepLinkContractTest {

    @Test
    fun configuredOAuthCallbackResolvesMainActivity() {
        val context =
            ApplicationProvider.getApplicationContext<Context>()
        val callback = Uri.parse(
            BuildConfig.MINEHOST_AUTH_REDIRECT_URI
        )
        val intent = Intent(Intent.ACTION_VIEW, callback).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
            addCategory(Intent.CATEGORY_BROWSABLE)
        }

        val matches = context.packageManager
            .queryIntentActivities(intent, 0)

        assertTrue(
            "Configured OAuth callback is not routed to MainActivity",
            matches.any { resolveInfo ->
                resolveInfo.activityInfo.name ==
                    MainActivity::class.java.name
            },
        )
    }
}
