package com.foreverjukebox.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import android.os.Bundle
import androidx.core.content.IntentCompat
import androidx.activity.compose.setContent
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.foreverjukebox.app.playback.ForegroundPlaybackService
import com.foreverjukebox.app.ui.ForeverJukeboxApp
import com.foreverjukebox.app.ui.MainViewModel
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import kotlinx.coroutines.launch

class MainActivity : FragmentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private var lastBackPressMs: Long = 0
    private val requestNotifications = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }
    private var sessionListener: SessionManagerListener<CastSession>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    // Register the Cast session listener whenever the current mode can cast — Server
                    // with a resolved receiver, or Local with the relay configured. (Previously this
                    // was Server-only, which left Local/Play-flavor casting with no session driver.)
                    syncCastSessionListener(state.castEnabled)
                }
            }
        }
        viewModel.handleDeepLink(intent?.data)
        if (intent.getBooleanExtra(EXTRA_OPEN_LISTEN_TAB, false)) {
            viewModel.openListenTab()
        }
        // A configuration change replays onCreate with the launching intent, so a share is only
        // read on a genuinely new instance.
        if (savedInstanceState == null) {
            handleShareIntent(intent)
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
        handleShareIntent(intent)
    }

    /**
     * Hands share-sheet content to the view model. The ACTION_SEND filters ship only in the
     * server-mode flavor; the build-config guard keeps a local-only build inert even against an
     * explicit send aimed at it, which no manifest filter can prevent.
     */
    private fun handleShareIntent(intent: Intent?) {
        if (!BuildConfig.SERVER_MODE_AVAILABLE) return
        if (intent?.action != Intent.ACTION_SEND) return
        val audioUri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
        if (audioUri != null) {
            viewModel.handleSharedAudio(audioUri)
            return
        }
        viewModel.handleSharedText(
            sharedText = intent.getStringExtra(Intent.EXTRA_TEXT),
            sharedSubject = intent.getStringExtra(Intent.EXTRA_SUBJECT)
        )
    }

    override fun onStart() {
        super.onStart()
        viewModel.onHostStarted()
    }

    override fun onDestroy() {
        syncCastSessionListener(
            enable = false,
            clearCastState = !isChangingConfigurations
        )
        if (isFinishing) {
            ForegroundPlaybackService.stop(this)
        }
        super.onDestroy()
    }

    private fun syncCastSessionListener(enable: Boolean, clearCastState: Boolean = true) {
        if (!enable) {
            sessionListener?.let { listener ->
                runCatching {
                    CastContext.getSharedInstance(this)
                        .sessionManager
                        .removeSessionManagerListener(listener, CastSession::class.java)
                }
            }
            sessionListener = null
            if (clearCastState) {
                viewModel.setCastingConnected(false)
            }
            return
        }

        if (sessionListener != null) {
            return
        }

        val castContext = runCatching { CastContext.getSharedInstance(this) }.getOrNull()
            ?: return
        val listener = object : SessionManagerListener<CastSession> {
            override fun onSessionStarted(session: CastSession, sessionId: String) {
                viewModel.setCastingConnected(true, session.castDevice?.friendlyName)
                viewModel.requestCastStatus()
            }

            override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
                viewModel.setCastingConnected(true, session.castDevice?.friendlyName)
                viewModel.requestCastStatus()
            }

            override fun onSessionEnded(session: CastSession, error: Int) {
                viewModel.setCastingConnected(false)
            }

            override fun onSessionStarting(session: CastSession) = Unit
            override fun onSessionStartFailed(session: CastSession, error: Int) = Unit
            override fun onSessionEnding(session: CastSession) = Unit
            override fun onSessionResuming(session: CastSession, sessionId: String) = Unit
            override fun onSessionResumeFailed(session: CastSession, error: Int) = Unit
            override fun onSessionSuspended(session: CastSession, reason: Int) = Unit
        }
        castContext.sessionManager.addSessionManagerListener(listener, CastSession::class.java)
        sessionListener = listener

        castContext.sessionManager.currentCastSession?.let { session ->
            viewModel.setCastingConnected(true, session.castDevice?.friendlyName)
            viewModel.requestCastStatus()
        }
    }

    companion object {
        const val EXTRA_OPEN_LISTEN_TAB = "com.foreverjukebox.app.open_listen_tab"
        private const val EXIT_CONFIRM_WINDOW_MS = 2000L
    }
}
