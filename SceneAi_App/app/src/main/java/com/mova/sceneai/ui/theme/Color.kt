package com.mova.sceneai.ui.theme

import androidx.compose.ui.graphics.Color

// ============================================================
// Mova-AI 调色板
// 「照应」使用珍珠白、深海青与暖珊瑚。大面积保持克制，色彩聚焦主要行动。
//   成功 = 绿   降级/提示 = 橙   错误 = 红   端侧 = 蓝
// ============================================================

// ---------- Light ----------
val MovaPrimaryLight = Color(0xFF195A63)
val MovaOnPrimaryLight = Color(0xFFFFFFFF)
val MovaPrimaryContainerLight = Color(0xFFDCEDEA)
val MovaOnPrimaryContainerLight = Color(0xFF123A40)

val MovaSecondaryLight = Color(0xFFAD573E)
val MovaOnSecondaryLight = Color(0xFFFFFFFF)
val MovaSecondaryContainerLight = Color(0xFFF9E5DA)
val MovaOnSecondaryContainerLight = Color(0xFF59301F)

val MovaTertiaryLight = Color(0xFF00696E)
val MovaTertiaryContainerLight = Color(0xFF9CF1F7)

val MovaSurfaceLight = Color(0xFFF1F5F4)
val MovaOnSurfaceLight = Color(0xFF163239)
val MovaSurfaceVariantLight = Color(0xFFE5ECEA)
val MovaOnSurfaceVariantLight = Color(0xFF5C7478)
val MovaOutlineLight = Color(0xFF80989B)

val MovaSurfaceContainerLight = Color(0xFFFCFEFD)
val MovaSurfaceContainerHighLight = Color(0xFFE9F0EE)

val MovaErrorLight = Color(0xFFBA1A1A)
val MovaOnErrorLight = Color(0xFFFFFFFF)
val MovaErrorContainerLight = Color(0xFFFFDAD6)

// ---------- Dark ----------
val MovaPrimaryDark = Color(0xFFABDAD8)
val MovaOnPrimaryDark = Color(0xFF123A40)
val MovaPrimaryContainerDark = Color(0xFF224A51)
val MovaOnPrimaryContainerDark = Color(0xFFD8EEEA)

val MovaSecondaryDark = Color(0xFFFFBEA6)
val MovaOnSecondaryDark = Color(0xFF59301F)
val MovaSecondaryContainerDark = Color(0xFF593E32)
val MovaOnSecondaryContainerDark = Color(0xFFFFDFCF)

val MovaTertiaryDark = Color(0xFF80D4DA)
val MovaTertiaryContainerDark = Color(0xFF004F53)

val MovaSurfaceDark = Color(0xFF10252B)
val MovaOnSurfaceDark = Color(0xFFE5EAE4)
val MovaSurfaceVariantDark = Color(0xFF3C4942)
val MovaOnSurfaceVariantDark = Color(0xFFB5C0B9)
val MovaOutlineDark = Color(0xFF819089)

val MovaSurfaceContainerDark = Color(0xFF183239)
val MovaSurfaceContainerHighDark = Color(0xFF24424A)

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
