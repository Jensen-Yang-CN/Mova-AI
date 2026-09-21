package com.mova.sceneai.ui.tech

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mova.sceneai.MovaApp
import com.mova.sceneai.core.Fmt
import com.mova.sceneai.data.model.ExecutorKind
import com.mova.sceneai.data.model.RequestTrace
import com.mova.sceneai.data.repo.EdgeRuntimeState
import com.mova.sceneai.data.repo.SessionStats
import com.mova.sceneai.ui.components.EmptyState
import com.mova.sceneai.ui.components.ExecutorBadge
import com.mova.sceneai.ui.components.InfoBanner
import com.mova.sceneai.ui.components.KeyValueRow
import com.mova.sceneai.ui.components.MeterRow
import com.mova.sceneai.ui.components.MovaCard
import com.mova.sceneai.ui.components.MovaScreen
import com.mova.sceneai.ui.components.OutlinedActionButton
import com.mova.sceneai.ui.components.SectionHeader
import com.mova.sceneai.ui.theme.LocalMovaSemanticColors

/**
 * 技术面板。
 *
 * 它同时服务两类读者：
 *  ① 用户 —— "AI 刚才做了什么决定"
 *  ② 面试官 / 评委 —— 端云路由、延迟拆解、模型状态，一页看完
 *
 * 因此它被放在底部导航的一级入口，而不是藏进设置深处。
 */
@Composable
fun TechPanelScreen(onOpenSettings: () -> Unit) {
    val container = MovaApp.instance.container
    val traces by container.repository.traces.collectAsStateWithLifecycle()
    val edge by container.repository.edgeState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val stats = remember(traces) { computeStats(traces) }
    val latest = traces.firstOrNull { it.success }

    MovaScreen(title = "技术面板", subtitle = "AI 刚才做了什么决定，这里如实记录") {

        InfoBanner(
            text = "这一页不面向日常使用，而是把端云路由、耗时与模型状态摊开给你看 —— " +
                "包括端侧模型尚未接入这件事。",
        )

        MovaCard {
            Column {
                Text("本次会话", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                KeyValueRow("总请求数", "${stats.total}")
                KeyValueRow("端侧完成", "${stats.edgeCount}", valueColor = LocalMovaSemanticColors.current.edge)
                KeyValueRow("云端完成", "${stats.cloudCount}", valueColor = LocalMovaSemanticColors.current.cloud)
                KeyValueRow("请求失败", "${stats.failureCount}")
                KeyValueRow("云端调用占比", Fmt.percent(stats.cloudRatio))
                KeyValueRow("成功请求平均耗时", Fmt.duration(stats.avgMs?.toLong()))
            }
        }

        EdgeModelCard(edge = edge, onOpenSettings = onOpenSettings)

        if (latest != null && latest.stages.isNotEmpty()) {
            MovaCard {
                Column {
                    Text("最近一次延迟拆解", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    val total = latest.stages.values.sum().coerceAtLeast(1L)
                    latest.stages.forEach { (stage, ms) ->
                        MeterRow(
                            label = stageLabel(stage),
                            value = ms,
                            total = total,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "端到端 ${Fmt.duration(latest.totalMs)}（含网络往返）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        SectionHeader("最近的请求", "每一行的「路由原因」都来自实际的决策函数，不是装饰文案")

        if (traces.isEmpty()) {
            EmptyState(
                emoji = "📊",
                title = "还没有数据",
                desc = "用过任意一个功能之后，这里会开始记录每次请求的执行方式、耗时与路由原因。",
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                traces.take(30).forEach { TraceRow(it) }
            }
        }

        Spacer(Modifier.height(8.dp))
        OutlinedActionButton(
            text = "导出日志",
            icon = Icons.Filled.Share,
            modifier = Modifier.fillMaxWidth(),
        ) {
            val payload = buildLog(stats, edge, traces)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "Mova-AI 运行日志")
                putExtra(Intent.EXTRA_TEXT, payload)
            }
            context.startActivity(Intent.createChooser(intent, "导出日志"))
        }
    }
}

@Composable
private fun EdgeModelCard(edge: EdgeRuntimeState, onOpenSettings: () -> Unit) {
    val semantic = LocalMovaSemanticColors.current
    MovaCard {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("端侧模型", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.weight(1f))
                Text(
                    edge.statusLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (edge.ready) semantic.success else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            KeyValueRow("模型", edge.name ?: "未配置")
            KeyValueRow("体积", edge.sizeMb?.let { String.format(java.util.Locale.CHINA, "%.0f MB", it) } ?: "—")
            KeyValueRow("量化位宽", edge.quantization ?: "—")
            if (!edge.ready) {
                Spacer(Modifier.height(8.dp))
                InfoBanner(
                    text = edge.unavailableReason
                        ?: "端侧小模型尚未接入；接入后离线也能使用部分能力。",
                    container = semantic.warningContainer,
                    contentColor = semantic.onWarningContainer,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedActionButton(text = "查看端侧设置", modifier = Modifier.fillMaxWidth(), onClick = onOpenSettings)
            }
        }
    }
}

@Composable
private fun TraceRow(trace: RequestTrace) {
    MovaCard(contentPadding = 14) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(trace.scene.emoji, fontSize = 18.sp)
                Spacer(Modifier.width(8.dp))
                Text(trace.scene.label, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.weight(1f))
                ExecutorBadge(trace.executorKind)
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    Fmt.relativeTime(trace.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(10.dp))
                Text(Fmt.duration(trace.totalMs), style = MaterialTheme.typography.labelSmall)
                trace.confidence?.let {
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "置信度 ${String.format(java.util.Locale.CHINA, "%.2f", it)}",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                if (!trace.success) {
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "失败",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                trace.routeReason.ifBlank { "—" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            trace.model?.let {
                Spacer(Modifier.height(2.dp))
                Text(
                    "模型：$it",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ---------- 工具 ----------

private fun computeStats(traces: List<RequestTrace>): SessionStats = SessionStats(
    total = traces.size,
    edgeCount = traces.count { it.executorKind == ExecutorKind.EDGE },
    cloudCount = traces.count { it.executorKind == ExecutorKind.CLOUD },
    failureCount = traces.count { !it.success },
    avgMs = traces.filter { it.success }.map { it.totalMs }.average().takeIf { !it.isNaN() },
    preferEdge = true,
)

/** 阶段键 → 中文标签。服务端返回英文 key，展示层统一翻译。 */
private fun stageLabel(key: String): String = when (key) {
    "recognize" -> "食材识别"
    "generate" -> "动作生成"
    "preprocess" -> "预处理"
    "inference" -> "模型推理"
    "postprocess" -> "后处理"
    else -> key
}

private fun buildLog(
    stats: SessionStats,
    edge: EdgeRuntimeState,
    traces: List<RequestTrace>,
): String = buildString {
    appendLine("Mova-AI 运行日志")
    appendLine("生成时间：${java.time.LocalDateTime.now()}")
    appendLine()
    appendLine("== 端侧模型 ==")
    appendLine("状态：${edge.statusLabel}")
    appendLine("模型：${edge.name ?: "未配置"}")
    appendLine("量化：${edge.quantization ?: "—"}")
    appendLine()
    appendLine("== 会话统计 ==")
    appendLine("总请求 ${stats.total} / 端侧 ${stats.edgeCount} / 云端 ${stats.cloudCount} / 失败 ${stats.failureCount}")
    appendLine("云端调用占比 ${Fmt.percent(stats.cloudRatio)}")
    appendLine("平均耗时 ${Fmt.duration(stats.avgMs?.toLong())}")
    appendLine()
    appendLine("== 最近请求 ==")
    traces.take(50).forEach { t ->
        appendLine(
            "${java.time.Instant.ofEpochMilli(t.timestamp)} | ${t.scene.id} | ${t.executor} | " +
                "${t.totalMs}ms | ${t.routeReason} | ${if (t.success) "OK" else "FAIL"}"
        )
    }
}
