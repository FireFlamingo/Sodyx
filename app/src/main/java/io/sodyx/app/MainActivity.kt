package io.sodyx.app

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableIntStateOf
import io.sodyx.app.data.SQLiteSodyxRepository
import io.sodyx.app.ui.SodyxApp

class MainActivity : ComponentActivity() {
    companion object {
        internal var repositoryOverride: io.sodyx.app.data.SodyxRepository? = null
    }
    private val resumeGeneration = mutableIntStateOf(0)
    override fun onResume() {
        super.onResume()
        resumeGeneration.intValue += 1
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        setContent {
            SodyxApp(
                repositoryOverride ?: AppRepository.instance(applicationContext),
                resumeGeneration.intValue
            )
        }
    }
}

private object AppRepository {
    @Volatile private var repository: SQLiteSodyxRepository? = null
    fun instance(context: android.content.Context): SQLiteSodyxRepository = repository
        ?: synchronized(this) {
            repository
                ?: SQLiteSodyxRepository(context).also { repository = it }
        }
}
