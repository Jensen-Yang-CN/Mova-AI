package com.mova.sceneai.ui.theme

import androidx.compose.ui.graphics.Color

// ============================================================
// Mova-AI 调色板
// 「照应」使用暖纸白、陶土橙与深墨绿；颜色服务于日常场景和清晰行动。
//   成功 = 绿   降级/提示 = 橙   错误 = 红   端侧 = 蓝
// ============================================================

// ---------- Light ----------
val MovaPrimaryLight = Color(0xFFAE4E32)
val MovaOnPrimaryLight = Color(0xFFFFFFFF)
val MovaPrimaryContainerLight = Color(0xFFFCE4D6)
val MovaOnPrimaryContainerLight = Color(0xFF4A251C)

val MovaSecondaryLight = Color(0xFF42635B)
val MovaOnSecondaryLight = Color(0xFFFFFFFF)
val MovaSecondaryContainerLight = Color(0xFFE0EDE4)
val MovaOnSecondaryContainerLight = Color(0xFF183C32)

val MovaTertiaryLight = Color(0xFF00696E)
val MovaTertiaryContainerLight = Color(0xFF9CF1F7)

val MovaSurfaceLight = Color(0xFFF6F4EE)
val MovaOnSurfaceLight = Color(0xFF20322F)
val MovaSurfaceVariantLight = Color(0xFFE9E7DF)
val MovaOnSurfaceVariantLight = Color(0xFF67746E)
val MovaOutlineLight = Color(0xFF87928B)

val MovaSurfaceContainerLight = Color(0xFFFFFEFA)
val MovaSurfaceContainerHighLight = Color(0xFFEEECE4)

val MovaErrorLight = Color(0xFFBA1A1A)
val MovaOnErrorLight = Color(0xFFFFFFFF)
val MovaErrorContainerLight = Color(0xFFFFDAD6)

// ---------- Dark ----------
val MovaPrimaryDark = Color(0xFFFFB392)
val MovaOnPrimaryDark = Color(0xFF522D22)
val MovaPrimaryContainerDark = Color(0xFF5E3A2D)
val MovaOnPrimaryContainerDark = Color(0xFFFFE0CF)

val MovaSecondaryDark = Color(0xFFB4D2C3)
val MovaOnSecondaryDark = Color(0xFF253F35)
val MovaSecondaryContainerDark = Color(0xFF304B40)
val MovaOnSecondaryContainerDark = Color(0xFFE1EEE5)

val MovaTertiaryDark = Color(0xFF80D4DA)
val MovaTertiaryContainerDark = Color(0xFF004F53)

val MovaSurfaceDark = Color(0xFF111D1C)
val MovaOnSurfaceDark = Color(0xFFE5EAE4)
val MovaSurfaceVariantDark = Color(0xFF3C4942)
val MovaOnSurfaceVariantDark = Color(0xFFB5C0B9)
val MovaOutlineDark = Color(0xFF819089)

val MovaSurfaceContainerDark = Color(0xFF1A2A27)
val MovaSurfaceContainerHighDark = Color(0xFF243631)

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
