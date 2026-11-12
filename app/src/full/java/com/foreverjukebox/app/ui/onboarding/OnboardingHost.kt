package com.foreverjukebox.app.ui.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.ui.window.Dialog
import com.foreverjukebox.app.ui.TabId

/** The full flavor has no onboarding tours, so every target modifier stays a no-op. */
@Composable
@Suppress("UnusedParameter")
fun OnboardingHost(
    activeTab: TabId,
    suppressed: Boolean,
    content: @Composable () -> Unit
) = content()

@Composable
@Suppress("UnusedParameter")
fun OnboardingDialog(
    tour: OnboardingTourId,
    onDismissRequest: () -> Unit,
    content: @Composable () -> Unit
) = Dialog(onDismissRequest = onDismissRequest, content = content)
