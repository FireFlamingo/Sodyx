package io.sodyx.app

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import io.sodyx.app.data.CoreMessengerRepository
import io.sodyx.app.security.SensitiveWindowPolicy
import io.sodyx.app.ui.CoreMessengerApp
import io.sodyx.app.ui.SodyxApp

class MainActivity : ComponentActivity() {
    companion object {
        internal var repositoryOverride: io.sodyx.app.data.SodyxRepository? = null

        // The repository constructor retains only applicationContext; this is a test fixture hook.
        @SuppressLint("StaticFieldLeak")
        internal var coreRepositoryOverride: CoreMessengerRepository? = null
    }
    private val resumeGeneration = mutableIntStateOf(0)
    private val foreground = mutableStateOf(false)
    override fun onStart() {
        super.onStart()
        foreground.value = true
    }
    override fun onStop() {
        foreground.value = false
        super.onStop()
    }
    override fun onResume() {
        super.onResume()
        resumeGeneration.intValue += 1
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SensitiveWindowPolicy.apply(window)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        setContent {
            val legacyFixture = repositoryOverride
            if (legacyFixture != null) {
                SodyxApp(legacyFixture, resumeGeneration.intValue)
            } else {
                CoreMessengerApp(
                    coreRepositoryOverride ?: CoreAppRepository.instance(applicationContext),
                    resumeGeneration.intValue,
                    foreground.value
                )
            }
        }
    }
}

// The repository retains applicationContext only, for the lifetime of the application process.
@SuppressLint("StaticFieldLeak")
private object CoreAppRepository {
    @Volatile private var repository: CoreMessengerRepository? = null
    fun instance(context: android.content.Context): CoreMessengerRepository = repository
        ?: synchronized(this) {
            repository
                ?: CoreMessengerRepository(context).also { repository = it }
        }
}
