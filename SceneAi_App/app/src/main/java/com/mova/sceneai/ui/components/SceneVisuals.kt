package com.mova.sceneai.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mova.sceneai.R

/** 场景色保持辨识度；夜间模式使用低亮度容器，不把浅色卡片直接搬到夜间。 */
fun sceneAccent(index: String) = when (index) {
    "02" -> Color(0xFF5871A2)
    "03" -> Color(0xFFAC633D)
    "04" -> Color(0xFF258281)
    else -> Color(0xFF367F82)
}

@Composable
fun sceneSurface(index: String): Color =
    if (MaterialTheme.colorScheme.background.luminance() < .3f) MaterialTheme.colorScheme.surfaceContainerHigh
    else when (index) {
        "02" -> Color(0xFFE8EDF7)
        "03" -> Color(0xFFF6EADF)
        "04" -> Color(0xFFDFEEEB)
        else -> Color(0xFFE4EFEE)
    }

/**
 * 原生绘制的场景装饰：对话气泡、纸页、餐盘和城市线条。
 * 全部为装饰元素，不作为按钮，也不加入读屏顺序；真实操作始终有文字标签。
 */
@Composable
fun SceneGlyph(index: String, modifier: Modifier = Modifier) {
    val accent = sceneAccent(index)
    Canvas(modifier) {
        val u = size.minDimension
        val center = Offset(size.width * .51f, size.height * .52f)
        drawCircle(Brush.radialGradient(listOf(accent.copy(.17f), Color.Transparent), center, u * .52f), u * .52f, center)
        when (index) {
            "02" -> rotate(-12f, center) {
                drawRoundRect(accent.copy(.13f), Offset(u * .29f, u * .2f), Size(u * .54f, u * .64f), CornerRadius(u * .06f))
                drawRoundRect(Brush.linearGradient(listOf(Color.White, Color(0xFFD3DFED))),
                    Offset(u * .2f, u * .13f), Size(u * .54f, u * .64f), CornerRadius(u * .06f))
                repeat(3) { line ->
                    val y = u * (.35f + line * .11f)
                    drawLine(accent.copy(if (line == 0) .8f else .35f),
                        Offset(u * .3f, y), Offset(u * (if (line == 2) .5f else .63f), y), u * .028f)
                }
                drawCircle(Color(0xFFD79C7D), u * .1f, Offset(u * .73f, u * .66f))
            }
            "03" -> {
                drawOval(accent.copy(.14f), Offset(u * .15f, u * .68f), Size(u * .72f, u * .15f))
                drawCircle(Brush.linearGradient(listOf(Color.White, Color(0xFFE0D6CC))), u * .34f, center)
                drawCircle(Color(0xFFF5F2EB), u * .26f, center)
                drawCircle(accent.copy(.18f), u * .26f, center, style = Stroke(u * .012f))
                drawCircle(Brush.linearGradient(listOf(Color(0xFFEAB589), Color(0xFFCC6F46))), u * .115f, center - Offset(u * .055f, u * .045f))
                rotate(-38f, center) {
                    drawOval(Color(0xFF599C8E), center + Offset(u * .01f, u * .02f), Size(u * .22f, u * .09f))
                    drawOval(Color(0xFF9DC0A0), center - Offset(u * .17f, -u * .08f), Size(u * .18f, u * .08f))
                }
                drawCircle(Color(0xFFE8BF66), u * .06f, center + Offset(u * .12f, -u * .11f))
            }
            "04" -> {
                drawCircle(Color(0xFFE4AF8D), u * .12f, Offset(u * .71f, u * .25f))
                val blocks = listOf(Triple(.17f, .4f, .31f), Triple(.38f, .23f, .49f), Triple(.62f, .37f, .34f))
                blocks.forEach { (x, y, h) ->
                    drawRoundRect(Brush.linearGradient(listOf(Color.White, accent.copy(.5f))),
                        Offset(u * x, u * y), Size(u * .17f, u * h), CornerRadius(u * .03f))
                }
                drawArc(accent.copy(.35f), 8f, 166f, false, Offset(u * .07f, u * .48f),
                    Size(u * .86f, u * .42f), style = Stroke(u * .025f))
            }
            else -> rotate(-10f, center) {
                drawRoundRect(Brush.linearGradient(listOf(accent.copy(.6f), accent)),
                    Offset(u * .34f, u * .38f), Size(u * .5f, u * .33f), CornerRadius(u * .1f))
                drawRoundRect(Brush.linearGradient(listOf(Color.White, Color(0xFFCADFDA))),
                    Offset(u * .12f, u * .2f), Size(u * .57f, u * .36f), CornerRadius(u * .11f))
                repeat(3) { dot ->
                    drawCircle(accent.copy(.72f), u * .022f, Offset(u * (.27f + dot * .085f), u * .38f))
                }
                drawCircle(Color(0xFFDCA58B), u * .09f, Offset(u * .79f, u * .23f))
            }
        }
    }
}

/** 场景头图把氛围与说明分开，文字使用主题色，图片中不烘焙任何文字。 */
@Composable
fun SceneIntro(index: String, title: String, description: String) {
    val shape = RoundedCornerShape(26.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(sceneSurface(index))) {
        Box(Modifier.fillMaxWidth().heightIn(min = 142.dp)) {
            if (index == "04") {
                Image(painterResource(R.drawable.mova_city_v3), null,
                    Modifier.matchParentSize(), contentScale = ContentScale.Crop, alignment = Alignment.CenterEnd)
                Box(Modifier.matchParentSize().background(Brush.horizontalGradient(
                    0f to sceneSurface(index), .58f to sceneSurface(index).copy(.92f), 1f to Color.Transparent)))
            } else {
                SceneGlyph(index, Modifier.align(Alignment.CenterEnd).padding(end = 5.dp).size(132.dp))
            }
            Column(Modifier.padding(20.dp).fillMaxWidth(.70f), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("$index / " + when (index) {
                    "02" -> "阅读"
                    "03" -> "做饭"
                    "04" -> "出行"
                    else -> "沟通"
                }, fontSize = 9.sp, letterSpacing = 1.sp, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }
        }
        Text(description, Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer.copy(.82f))
            .padding(horizontal = 20.dp, vertical = 14.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 输入步骤标题用于建立任务节奏，不用参数名称替代用户语言。 */
@Composable
fun TaskStep(number: String, title: String, hint: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(28.dp).border(1.dp, MaterialTheme.colorScheme.primary.copy(.2f), RoundedCornerShape(9.dp)),
            contentAlignment = Alignment.Center) {
            Text(number, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall)
            hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

/** 等宽分段入口适合两至三个内容来源，和可换行的偏好 Chips 区分开。 */
@Composable
fun MovaSegmentedTabs(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp))
        .background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEachIndexed { index, text ->
            Box(Modifier.weight(1f).clip(RoundedCornerShape(11.dp))
                .background(if (index == selected) MaterialTheme.colorScheme.surfaceContainer else Color.Transparent)
                .selectable(index == selected, role = Role.Tab, onClick = { onSelect(index) })
                .heightIn(min = 48.dp).padding(horizontal = 3.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center) {
                Text(text, style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (index == selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (index == selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
