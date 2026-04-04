package com.foreverjukebox.app.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.foreverjukebox.app.R

val SmallButtonPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
val SmallButtonHeight = 32.dp
val SmallFieldMinHeight = 40.dp
val SurfaceCornerRadius = 8.dp
val SurfaceShape = RoundedCornerShape(SurfaceCornerRadius)
val PillShape = SurfaceShape
val appFontFamily = FontFamily(
    Font(resId = R.font.barlow_regular, weight = FontWeight.Normal),
    Font(resId = R.font.barlow_semi_bold, weight = FontWeight.SemiBold)
)
val neonFontFamily = FontFamily(Font(resId = R.font.tilt_neon_regular))
