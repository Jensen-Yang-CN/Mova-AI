package com.mova.sceneai.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val MovaInk = Color(0xFF172D2C)
val MovaPeach = Color(0xFFFFC8A8)
val MovaMint = Color(0xFFB8DCC9)

/** 自绘品牌光球：没有外部图片依赖，适配不同屏幕，也便于披露原创视觉工作。 */
@Composable
fun MovaOrb(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val radius = size.minDimension * .27f
        val center = Offset(size.width * .51f, size.height * .48f)
        drawCircle(Brush.radialGradient(listOf(MovaPeach.copy(.19f), Color.Transparent), center, radius * 1.9f), radius * 1.9f, center)
        rotate(-30f, center) {
            drawOval(MovaMint.copy(.45f), center - Offset(radius * 1.62f, radius * .65f), Size(radius * 3.24f, radius * 1.3f), style = Stroke(1.6.dp.toPx()))
        }
        drawCircle(Brush.linearGradient(listOf(Color(0xFFFFF0D7), MovaPeach, Color(0xFFDA7957)), center - Offset(radius, radius), center + Offset(radius, radius)), radius, center)
        drawCircle(Brush.radialGradient(listOf(Color.White.copy(.75f), Color.Transparent), center - Offset(radius * .38f, radius * .4f), radius * .86f), radius * .86f, center - Offset(radius * .2f, radius * .22f))
        rotate(-30f, center) {
            drawArc(MovaMint.copy(.9f), 10f, 152f, false, center - Offset(radius * 1.63f, radius * .65f), Size(radius * 3.26f, radius * 1.3f), style = Stroke(2.dp.toPx()))
            drawCircle(MovaMint, radius * .085f, center + Offset(radius * 1.57f, radius * .12f))
        }
        drawCircle(Color.White.copy(.8f), 2.dp.toPx(), center + Offset(radius * 1.1f, -radius * 1.3f))
        drawCircle(MovaPeach.copy(.5f), 1.5.dp.toPx(), center - Offset(radius * 1.3f, radius * 1.45f))
    }
}

/** 场景页的叙事入口。只说明用户要完成的动作，不展示模型参数。 */
@Composable
fun StoryIntro(index: String, title: String, description: String) {
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = .5f), RoundedCornerShape(24.dp)).padding(20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(index, color = MaterialTheme.colorScheme.primary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
