package com.mova.sceneai.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp

/**
 * 排版规范。
 *
 * 中文字体的行高比英文更需要留白，否则密集的正文会显得拥挤；
 * 因此统一放大 lineHeight 并开启 trim，让中文段落的视觉节奏稳定。
 */
private val TrimStyle = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None,
)

private fun base(
    size: Int,
    lineHeight: Int,
    weight: FontWeight = FontWeight.Normal,
    letterSpacing: Double = 0.0,
) = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = letterSpacing.sp,
    lineHeightStyle = TrimStyle,
)

val MovaTypography = Typography(
    displaySmall = base(32, 40, FontWeight.SemiBold, -0.2),
    headlineMedium = base(26, 34, FontWeight.SemiBold, -0.2),
    headlineSmall = base(22, 30, FontWeight.SemiBold),
    titleLarge = base(20, 28, FontWeight.SemiBold),
    titleMedium = base(16, 24, FontWeight.Medium),
    titleSmall = base(14, 20, FontWeight.Medium),
    bodyLarge = base(16, 26),
    bodyMedium = base(14, 23),
    bodySmall = base(12, 19),
    labelLarge = base(14, 20, FontWeight.Medium),
    labelMedium = base(12, 17, FontWeight.Medium),
    labelSmall = base(11, 16, FontWeight.Medium),
)
