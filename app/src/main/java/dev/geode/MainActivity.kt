package dev.geode

import android.app.SearchManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import dagger.hilt.android.AndroidEntryPoint
import dev.geode.data.MotionPrefs
import dev.geode.nav.DeepLink
import dev.geode.nav.NavSaver
import dev.geode.nav.Navigator
import dev.geode.nav.connect.MotionPolicy
import dev.geode.nav.connect.NavConnectors
import dev.geode.nav.platform.SensorGravitySource
import dev.geode.nav.platform.bindBack
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The app's one activity and the host the design system attaches to. It owns the [Navigator]
 * and the [NavConnectors], wires back and deep links into them, and draws nothing: there is no
 * `setContent` here yet. The design system adds one that takes [navigator] and [connectors].
 *
 * Kept a Hilt entry point so the screens' view models can be injected once they exist.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    lateinit var navigator: Navigator
        private set

    lateinit var connectors: NavConnectors
        private set

    /** The motion policy in force. A settings screen writes here when the person changes it. */
    val motion = MutableStateFlow(MotionPolicy())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        motion.value = MotionPolicy(reducedMotion = MotionPrefs.reducedMotion(geodeContainer.prefsFiles.general))
        navigator = Navigator(NavSaver.decode(savedInstanceState?.getString(KEY_NAV)))
        connectors = NavConnectors(motion = motion, gravity = SensorGravitySource(this))
        navigator.bindBack(onBackPressedDispatcher, this)
        route(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        route(intent)
    }

    override fun onStart() {
        super.onStart()
        connectors.gravity.start()
    }

    override fun onStop() {
        connectors.gravity.stop()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_NAV, NavSaver.encode(navigator.state.value))
    }

    /** Hands a launch or re-launch intent to the navigator, then blanks it so a resume cannot replay it. */
    private fun route(intent: Intent?) {
        val link =
            DeepLink.parse(intent?.action, intent?.dataString, intent?.getStringExtra(SearchManager.QUERY)) ?: return
        navigator.handle(link)
        intent?.action = null
        intent?.data = null
    }

    private companion object {
        const val KEY_NAV = "nav"
    }
}
