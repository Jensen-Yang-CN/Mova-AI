package com.mova.sceneai.ui.theme

import androidx.compose.ui.graphics.Color

// ============================================================
// Mova-AI 调色板
// 主色走紫蓝系（AI 产品的视觉母语），语义色与文档 03 的规范一一对应：
//   成功 = 绿   降级/提示 = 橙   错误 = 红   端侧 = 蓝
// ============================================================

// ---------- Light ----------
val MovaPrimaryLight = Color(0xFF5B4BE0)
val MovaOnPrimaryLight = Color(0xFFFFFFFF)
val MovaPrimaryContainerLight = Color(0xFFE4DFFF)
val MovaOnPrimaryContainerLight = Color(0xFF160067)

val MovaSecondaryLight = Color(0xFF5D5C71)
val MovaOnSecondaryLight = Color(0xFFFFFFFF)
val MovaSecondaryContainerLight = Color(0xFFE3E0F9)
val MovaOnSecondaryContainerLight = Color(0xFF1A1A2C)

val MovaTertiaryLight = Color(0xFF00696E)
val MovaTertiaryContainerLight = Color(0xFF9CF1F7)

val MovaSurfaceLight = Color(0xFFFCF8FF)
val MovaOnSurfaceLight = Color(0xFF1B1B21)
val MovaSurfaceVariantLight = Color(0xFFE5E0EC)
val MovaOnSurfaceVariantLight = Color(0xFF47464F)
val MovaOutlineLight = Color(0xFF787680)

val MovaSurfaceContainerLight = Color(0xFFF2EDF7)
val MovaSurfaceContainerHighLight = Color(0xFFECE6F1)

val MovaErrorLight = Color(0xFFBA1A1A)
val MovaOnErrorLight = Color(0xFFFFFFFF)
val MovaErrorContainerLight = Color(0xFFFFDAD6)

// ---------- Dark ----------
val MovaPrimaryDark = Color(0xFFC6BFFF)
val MovaOnPrimaryDark = Color(0xFF2B00A4)
val MovaPrimaryContainerDark = Color(0xFF4331C7)
val MovaOnPrimaryContainerDark = Color(0xFFE4DFFF)

val MovaSecondaryDark = Color(0xFFC7C4DD)
val MovaOnSecondaryDark = Color(0xFF2F2F42)
val MovaSecondaryContainerDark = Color(0xFF454559)
val MovaOnSecondaryContainerDark = Color(0xFFE3E0F9)

val MovaTertiaryDark = Color(0xFF80D4DA)
val MovaTertiaryContainerDark = Color(0xFF004F53)

val MovaSurfaceDark = Color(0xFF121318)
val MovaOnSurfaceDark = Color(0xFFE5E1E9)
val MovaSurfaceVariantDark = Color(0xFF47464F)
val MovaOnSurfaceVariantDark = Color(0xFFC8C5D0)
val MovaOutlineDark = Color(0xFF928F9A)

val MovaSurfaceContainerDark = Color(0xFF1E1F25)
val MovaSurfaceContainerHighDark = Color(0xFF282A30)

val MovaErrorDark = Color(0xFFFFB4AB)
val MovaOnErrorDark = Color(0xFF690005)
val MovaErrorContainerDark = Color(0xFF93000A)

// ============================================================
// 语义色：状态卡 / 徽标 / 路由原因共用，保证"颜色含义"全局唯一
// 浅色与深色各一套，避免深色模式下彩色块刺眼
// ============================================================
data class MovaSemanticColors(
    val success: Color,
    val onSuccessContainer: Color,
    val successContainer: Color,
    val warning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    val edge: Color,
    val edgeContainer: Color,
    val onEdgeContainer: Color,
    val cloud: Color,
    val cloudContainer: Color,
    val onCloudContainer: Color,
)

val LightSemanticColors = MovaSemanticColors(
    success = Color(0xFF1B7A3E),
    successContainer = Color(0xFFC7F0D4),
    onSuccessContainer = Color(0xFF00210F),
    warning = Color(0xFF8A5100),
    warningContainer = Color(0xFFFFDDB6),
    onWarningContainer = Color(0xFF2C1700),
    edge = Color(0xFF1A5FB4),
    edgeContainer = Color(0xFFD6E4FF),
    onEdgeContainer = Color(0xFF001B3D),
    cloud = Color(0xFF6A4FBE),
    cloudContainer = Color(0xFFE8DEFF),
    onCloudContainer = Color(0xFF21005D),
)

val DarkSemanticColors = MovaSemanticColors(
    success = Color(0xFF8EDBA6),
    successContainer = Color(0xFF00522A),
    onSuccessContainer = Color(0xFFC7F0D4),
    warning = Color(0xFFFFB95C),
    warningContainer = Color(0xFF5C3A00),
    onWarningContainer = Color(0xFFFFDDB6),
    edge = Color(0xFFA8C8FF),
    edgeContainer = Color(0xFF00458F),
    onEdgeContainer = Color(0xFFD6E4FF),
    cloud = Color(0xFFCFBCFF),
    cloudContainer = Color(0xFF4B3499),
    onCloudContainer = Color(0xFFE8DEFF),
)
