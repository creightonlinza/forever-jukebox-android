package com.foreverjukebox.app

import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.fragment.app.FragmentActivity
import com.foreverjukebox.app.ui.ForeverJukeboxApp
import com.foreverjukebox.app.ui.MainViewModel
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastState

class MainActivity : FragmentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private var lastBackPressMs: Long = 0
    private val requestNotifications = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            val castContext = CastContext.getSharedInstance(this)
            castContext.addCastStateListener { state ->
                if (state == CastState.CONNECTED) {
                    val session = castContext.sessionManager.currentCastSession
                    val name = session?.castDevice?.friendlyName
                    viewModel.setCastingConnected(true, name)
                } else {
                    viewModel.setCastingConnected(false)
                }
            }
        } catch (_: Exception) {
            // Ignore cast init failures; app still works without it.
        }
        viewModel.handleDeepLink(intent?.data)
        if (intent.getBooleanExtra(EXTRA_OPEN_LISTEN_TAB, false)) {
            viewModel.openListenTab()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (!viewModel.navigateBack()) {
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastBackPressMs < EXIT_CONFIRM_WINDOW_MS) {
                            viewModel.prepareForExit()
                            finishAffinity()
                        } else {
                            lastBackPressMs = now
                            Toast.makeText(
                                this@MainActivity,
                                "Tap back again to exit",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            }
        )
        setContent {
            ForeverJukeboxApp(viewModel)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        viewModel.handleDeepLink(intent.data)
        if (intent.getBooleanExtra(EXTRA_OPEN_LISTEN_TAB, false)) {
            viewModel.openListenTab()
        }
    }

    companion object {
        const val EXTRA_OPEN_LISTEN_TAB = "com.foreverjukebox.app.open_listen_tab"
        private const val EXIT_CONFIRM_WINDOW_MS = 2000L
    }
}
