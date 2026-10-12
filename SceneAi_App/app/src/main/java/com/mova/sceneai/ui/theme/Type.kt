package com.mova.sceneai.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp
import com.mova.sceneai.R

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

/** 固定使用开源无衬线中文字体，避免手机主题字体改变比赛演示中的版式。 */
@OptIn(ExperimentalTextApi::class)
private val MovaSans = FontFamily(
    Font(R.font.mova_sans_sc, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.mova_sans_sc, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.mova_sans_sc, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    Font(R.font.mova_sans_sc, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
)

private fun base(
    size: Int,
    lineHeight: Int,
    weight: FontWeight = FontWeight.Normal,
    letterSpacing: Double = 0.0,
) = TextStyle(
    fontFamily = MovaSans,
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
    titleLarge = base(22, 30, FontWeight.SemiBold),
    titleMedium = base(17, 25, FontWeight.SemiBold),
    titleSmall = base(14, 20, FontWeight.Medium),
    bodyLarge = base(16, 26),
    bodyMedium = base(14, 23),
    bodySmall = base(12, 19),
    labelLarge = base(14, 20, FontWeight.Medium),
    labelMedium = base(12, 17, FontWeight.Medium),
    labelSmall = base(11, 16, FontWeight.Medium),
)
