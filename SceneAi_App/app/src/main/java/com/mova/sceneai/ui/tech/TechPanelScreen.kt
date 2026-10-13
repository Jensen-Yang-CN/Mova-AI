package com.mova.sceneai.ui.tech

import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.TextButton
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.mova.sceneai.ui.components.Dot
import com.mova.sceneai.ui.components.MovaInk
import com.mova.sceneai.ui.components.MovaMint
import com.mova.sceneai.ui.components.MovaPeach
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import com.mova.sceneai.data.edge.EdgeObservation
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
import kotlinx.coroutines.launch

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
    var detailsOpen by rememberSaveable { mutableStateOf(false) }
    var tryoutOpen by rememberSaveable { mutableStateOf(false) }
    var allRequests by rememberSaveable { mutableStateOf(false) }

    MovaScreen(title = "运行与技术", subtitle = "模型状态与真实请求") {
        RuntimeHero(edge)
        MovaCard(contentPadding = 18) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("一条回复，怎样来到你面前", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PipelineStep("01", "手机理解", "场景 / 意图", Modifier.weight(1f))
                    PipelineStep("02", "云端组织", "候选 / 解释", Modifier.weight(1f))
                    PipelineStep("03", "由你决定", "选择 / 修改", Modifier.weight(1f))
                }
                Text("手机先判断需求，回复正文由云端生成",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        SectionHeader("本机已记录的请求", "下面是调用记录，不是模型准确率")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            RuntimeMetric(stats.total.toString(), "请求总数", Modifier.weight(1f))
            RuntimeMetric(stats.cloudCount.toString(), "云端完成", Modifier.weight(1f))
            RuntimeMetric(Fmt.duration(stats.avgMs?.toLong()), "成功平均耗时", Modifier.weight(1f))
        }
        if (latest != null && latest.stages.isNotEmpty()) {
            MovaCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("最近一次 · 耗时拆解", style = MaterialTheme.typography.titleSmall)
                    val total = latest.stages.values.sum().coerceAtLeast(1L)
                    latest.stages.forEach { (stage, ms) ->
                        MeterRow(stageLabel(stage), ms, total, MaterialTheme.colorScheme.primary)
                    }
                    Text("端到端 " + Fmt.duration(latest.totalMs) + " · 含网络往返",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        // 参数属于可展开的证据层，普通用户先看到用途和真实状态。
        MovaCard(contentPadding = 18) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("模型与统计详情", style = MaterialTheme.typography.titleSmall)
                        Text("量化、结果来源与统计口径", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { detailsOpen = !detailsOpen }) { Text(if (detailsOpen) "收起" else "展开") }
                }
                if (detailsOpen) {
                    KeyValueRow("端侧完成", stats.edgeCount.toString())
                    KeyValueRow("请求失败", stats.failureCount.toString())
                    KeyValueRow("云端调用占比", Fmt.percent(stats.cloudRatio))
                    Text("「端侧完成」只统计完整结果；本地预判不计入",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (detailsOpen) EdgeModelCard(edge, onOpenSettings)
        MovaCard(contentPadding = 18) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("试一次本机理解", style = MaterialTheme.typography.titleSmall)
                    Text("直接验证量化模型，无需云端", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { tryoutOpen = !tryoutOpen }) { Text(if (tryoutOpen) "收起" else "打开") }
            }
        }
        if (tryoutOpen) EdgeTryout(edge, container.repository::inspectEdge)
        SectionHeader("最近的请求", "执行方式、耗时与实际路由原因")
        if (traces.isEmpty()) {
            EmptyState("📊", "还没有运行记录", "用一次功能，这里就会出现处理记录")
        } else {
            traces.take(if (allRequests) 30 else 5).forEach { TraceRow(it) }
            if (traces.size > 5) TextButton(onClick = { allRequests = !allRequests }) {
                Text(if (allRequests) "收起较早记录" else "查看更多记录")
            }
        }
        OutlinedActionButton("导出运行日志", Modifier.fillMaxWidth(), Icons.Filled.Share) {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "Mova-AI 运行日志")
                putExtra(Intent.EXTRA_TEXT, buildLog(stats, edge, traces))
            }
            context.startActivity(Intent.createChooser(intent, "导出日志"))
        }
    }
}

/** 状态使用实际模型连接值；背景线路为装饰，不播放虚构的推理动画。 */
@Composable
private fun RuntimeHero(edge: EdgeRuntimeState) {
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp))
        .background(Brush.linearGradient(listOf(MovaInk, Color(0xFF28535A))))) {
        Canvas(Modifier.matchParentSize()) {
            repeat(5) { row ->
                val y = size.height * (.18f + row * .17f)
                drawLine(Color.White.copy(.07f), Offset(size.width * .55f, y), Offset(size.width, y), 1.dp.toPx())
                drawCircle(MovaMint.copy(.30f), 3.dp.toPx(), Offset(size.width * (.68f + (row % 2) * .18f), y))
            }
        }
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Dot(if (edge.ready) MovaMint else MovaPeach, 6)
                Text(if (edge.ready) "ON DEVICE / 本机模型已就绪" else "ON DEVICE / 等待导入模型",
                    style = MaterialTheme.typography.labelSmall, color = MovaMint)
            }
            Text("这次请求\n怎么完成", style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold, color = Color.White)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(edge.quantization ?: "GGUF", Modifier.background(Color.White.copy(.10f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 9.dp, vertical = 5.dp), color = Color.White, style = MaterialTheme.typography.labelSmall)
                Text("端云协同", Modifier.background(Color.White.copy(.10f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 9.dp, vertical = 5.dp), color = Color.White, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun PipelineStep(number: String, title: String, detail: String, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.primaryContainer.copy(.55f))
        .padding(horizontal = 10.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(number, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun RuntimeMetric(value: String, label: String, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceContainer)
        .padding(13.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(value, style = MaterialTheme.typography.titleMedium, maxLines = 1)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 本机直接试运行分类器，便于在没有云端服务时核验 GGUF 是否真正参与推理。 */
@Composable
private fun EdgeTryout(
    edge: EdgeRuntimeState,
    inspect: suspend (String) -> EdgeObservation,
) {
    var input by remember { mutableStateOf("") }
    var output by remember { mutableStateOf<EdgeObservation?>(null) }
    var running by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    MovaCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("端侧模型试运行", style = MaterialTheme.typography.titleSmall)
            Text(
                "只做场景、意图和槽位判断；不生成短信，也不请求云端。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = input,
                onValueChange = { input = it; output = null },
                label = { Text("输入一句话") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 4,
            )
            OutlinedButton(
                enabled = edge.ready && !running && input.isNotBlank() && input.length <= 1000,
                onClick = {
                    val utterance = input.trim()
                    running = true
                    scope.launch {
                        try {
                            output = inspect(utterance)
                        } finally {
                            running = false
                        }
                    }
                },
            ) {
                if (running) CircularProgressIndicator(modifier = Modifier.width(16.dp).height(16.dp))
                else Text("在本机判断")
            }
            if (input.length > 1000) Text("请输入不超过 1000 字的短文本")
            output?.let { result ->
                if (result.decision == null) {
                    Text(result.failure ?: "端侧输出未通过契约校验", color = MaterialTheme.colorScheme.error)
                } else {
                    val decision = result.decision
                    KeyValueRow("场景 / 意图", "${decision.scene} / ${decision.intent}")
                    KeyValueRow("是否建议上云", if (decision.needCloud) "是" else "否")
                    KeyValueRow("耗时", Fmt.duration(result.latencyMs))
                    Text("槽位：${decision.slots}", style = MaterialTheme.typography.bodySmall)
                }
            }
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
                        ?: "端侧小模型尚未接入；接入后可在本机判断场景与意图。",
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
    "edge_decision" -> "端侧判断"
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
    appendLine("== 本机已记录请求统计 ==")
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
