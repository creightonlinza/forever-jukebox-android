package com.foreverjukebox.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.os.Build
import android.view.View
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cast
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.mediarouter.app.MediaRouteButton
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener

@Composable
fun CastRouteButton(
    modifier: Modifier = Modifier,
    enabled: Boolean,
    onSessionStarted: () -> Unit,
    onDisabledClick: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val onSessionStartedState = rememberUpdatedState(onSessionStarted)
    val enabledTint = MaterialTheme.colorScheme.onSurface
    val disabledTint = enabledTint.copy(alpha = 0.4f)
    if (!enabled) {
        SquareIconButton(
            onClick = { onDisabledClick?.invoke() },
            modifier = modifier
        ) {
            Icon(
                imageVector = Icons.Outlined.Cast,
                contentDescription = "Cast unavailable",
                tint = disabledTint
            )
        }
        return
    }
    // On API 37+ no Cast device is found until the user grants local network access.
    val localNetwork = Manifest.permission.ACCESS_LOCAL_NETWORK
    var canDiscover by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < Build.VERSION_CODES.CINNAMON_BUN ||
                context.checkSelfPermission(localNetwork) == PackageManager.PERMISSION_GRANTED
        )
    }
    var openChooser by remember { mutableStateOf(false) }
    val requestLocalNetwork = rememberLauncherForActivityResult(RequestPermission()) { granted ->
        canDiscover = granted
        openChooser = granted
        if (!granted) {
            val message = "To cast, allow Nearby devices in the app's system settings."
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }
    if (!canDiscover) {
        SquareIconButton(onClick = { requestLocalNetwork.launch(localNetwork) }, modifier = modifier) {
            Icon(imageVector = Icons.Outlined.Cast, contentDescription = "Cast", tint = enabledTint)
        }
        return
    }
    val castContext = remember {
        runCatching { CastContext.getSharedInstance(context) }.getOrNull()
    }
    if (castContext == null) {
        return
    }
    val sessionManager = castContext.sessionManager

    DisposableEffect(sessionManager) {
        val listener = object : SessionManagerListener<CastSession> {
            override fun onSessionStarted(session: CastSession, sessionId: String) {
                onSessionStartedState.value()
            }

            override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
                onSessionStartedState.value()
            }

            override fun onSessionStarting(session: CastSession) = Unit
            override fun onSessionStartFailed(session: CastSession, error: Int) = Unit
            override fun onSessionEnding(session: CastSession) = Unit
            override fun onSessionEnded(session: CastSession, error: Int) = Unit
            override fun onSessionResuming(session: CastSession, sessionId: String) = Unit
            override fun onSessionResumeFailed(session: CastSession, error: Int) = Unit
            override fun onSessionSuspended(session: CastSession, reason: Int) = Unit
        }
        sessionManager.addSessionManagerListener(listener, CastSession::class.java)
        onDispose {
            sessionManager.removeSessionManagerListener(listener, CastSession::class.java)
        }
    }

    // MediaRouteButton owns its remote indicator drawable and exposes no getter
    // for it, so the icon is recolored by compositing the whole view through a
    // layer paint. SRC_IN keeps the drawable's alpha, which leaves the connected
    // and connecting frame animations intact.
    val tintPaint = remember(enabledTint) {
        Paint().apply {
            colorFilter = PorterDuffColorFilter(enabledTint.toArgb(), PorterDuff.Mode.SRC_IN)
        }
    }

    AndroidView(
        factory = { ctx ->
            MediaRouteButton(ctx).apply {
                CastButtonFactory.setUpMediaRouteButton(ctx, this)
            }
        },
        modifier = modifier,
        update = { button ->
            button.isEnabled = enabled
            if (openChooser) {
                openChooser = false
                button.post { button.showDialog() }
            }
            button.setLayerType(View.LAYER_TYPE_HARDWARE, tintPaint)
        }
    )
}
