package com.mova.sceneai.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.mova.sceneai.core.AppContainer
import com.mova.sceneai.core.Fmt
import com.mova.sceneai.core.movaViewModel
import com.mova.sceneai.data.model.ExecutorKind
import com.mova.sceneai.data.repo.EdgeRuntimeState
import com.mova.sceneai.data.repo.SessionStats
import com.mova.sceneai.ui.components.Dot
import com.mova.sceneai.ui.components.MovaCard
import com.mova.sceneai.ui.components.MovaScreen
import com.mova.sceneai.ui.components.SectionHeader
import com.mova.sceneai.ui.components.SolidActionButton
import com.mova.sceneai.ui.nav.Routes
import com.mova.sceneai.ui.theme.LocalMovaSemanticColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

/**
 * 首页。
 *
 * 信息优先级刻意排成：
 *   ① 现在能不能用（连接状态）→ ② 要不要它主动（总开关）
 *   → ③ 能做什么（场景卡，每个都有"试试看"）→ ④ 它今天做了什么（端云占比）
 *
 * 新用户不需要读任何说明就能走完这条线。
 */
@Composable
fun HomeScreen(
    onOpenScene: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenTech: () -> Unit,
) {
    val vm = movaViewModel { HomeViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { vm.refresh() }

    MovaScreen(title = "Mova-AI", subtitle = "不是等你开口，而是在你需要的那一刻，刚好出现") {
        ConnectionCard(
            status = state.status,
            detail = state.statusDetail,
            onOpenSettings = onOpenSettings,
            onRetry = vm::refresh,
        )

        MovaCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("场景主动提醒", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "在合适的时机主动帮忙，而不是等你开口",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = state.proactiveEnabled, onCheckedChange = vm::setProactive)
            }
        }

        SectionHeader("试试这些能力", "每个都准备了一份示例，点一下就能看到结果")

        SceneGrid(onOpenScene = onOpenScene)

        SectionHeader("今日概览", "端云协同的实际分布")

        MovaCard(onClick = onOpenTech) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Insights,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("今日：${state.stats.total} 次请求", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.weight(1f))
                    Text(
                        "查看详情 ›",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    StatPill("端侧", "${state.stats.edgeCount}", LocalMovaSemanticColors.current.edge)
                    StatPill("云端", "${state.stats.cloudCount}", LocalMovaSemanticColors.current.cloud)
                    StatPill(
                        "平均耗时",
                        Fmt.duration(state.stats.avgMs?.toLong()),
                        MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!state.edge.ready) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "端侧模型尚未接入，当前全部由云端完成。接入后这里会开始出现端侧调用。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatPill(label: String, value: String, color: androidx.compose.ui.graphics.Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Dot(color, size = 8)
        Spacer(Modifier.width(6.dp))
        Column {
            Text(value, style = MaterialTheme.typography.titleSmall, color = color)
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 连接状态卡：三种形态，颜色语义与设计文档严格一致。 */
@Composable
private fun ConnectionCard(
    status: HomeStatus,
    detail: String,
    onOpenSettings: () -> Unit,
    onRetry: () -> Unit,
) {
    val semantic = LocalMovaSemanticColors.current
    val (dot, container) = when (status) {
        HomeStatus.CLOUD_OK -> semantic.success to MaterialTheme.colorScheme.surfaceContainer
        HomeStatus.EDGE_ONLY -> semantic.edge to semantic.edgeContainer
        HomeStatus.UNAVAILABLE -> semantic.warning to semantic.warningContainer
        HomeStatus.CHECKING -> MaterialTheme.colorScheme.outline to MaterialTheme.colorScheme.surfaceContainer
    }
    MovaCard(containerColor = container) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dot(dot, size = 10)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    when (status) {
                        HomeStatus.CLOUD_OK -> "云端已连接"
                        HomeStatus.EDGE_ONLY -> "端侧模型运行中"
                        HomeStatus.UNAVAILABLE -> "无法连接服务"
                        HomeStatus.CHECKING -> "正在检测服务…"
                    },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            when (status) {
                HomeStatus.UNAVAILABLE -> TextButton(onClick = onOpenSettings) { Text("去设置") }
                HomeStatus.CHECKING -> Unit
                else -> TextButton(onClick = onRetry) { Text("刷新") }
            }
        }
    }
}

@Composable
private fun SceneGrid(onOpenScene: (String) -> Unit) {
    val scenes = remember {
        listOf(
            SceneItem(Routes.FOOD, "🍜", "做饭助手", "拍食材，出菜谱", true),
            SceneItem(Routes.READING, "📄", "阅读总结", "文本 / 图片 / PDF", true),
            SceneItem(Routes.CHAT, "💬", "聊天辅助", "帮你把话说好", true),
            SceneItem(null, "📍", "位置提示", "到了超市提醒你", false),
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        scenes.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { item ->
                    SceneCard(item, Modifier.weight(1f), onOpenScene)
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

private data class SceneItem(
    val route: String?,
    val emoji: String,
    val name: String,
    val desc: String,
    val available: Boolean,
)

@Composable
private fun SceneCard(item: SceneItem, modifier: Modifier, onOpenScene: (String) -> Unit) {
    val semantic = LocalMovaSemanticColors.current
    MovaCard(
        modifier = modifier,
        containerColor = if (item.available) MaterialTheme.colorScheme.surfaceContainer
        else MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item.emoji, fontSize = 26.sp)
                Spacer(Modifier.weight(1f))
                if (!item.available) {
                    Text(
                        "规划中",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(item.name, style = MaterialTheme.typography.titleSmall)
            Text(
                item.desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                minLines = 2,
            )
            Spacer(Modifier.height(12.dp))
            if (item.available && item.route != null) {
                SolidActionButton(
                    text = "试试看",
                    modifier = Modifier.fillMaxWidth(),
                    icon = Icons.Filled.Bolt,
                ) { onOpenScene(item.route) }
            } else {
                Box(Modifier.fillMaxWidth().height(40.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "敬请期待",
                        style = MaterialTheme.typography.labelMedium,
                        color = semantic.warning,
                    )
                }
            }
        }
    }
}

// ============================================================
// ViewModel
// ============================================================

enum class HomeStatus { CHECKING, CLOUD_OK, EDGE_ONLY, UNAVAILABLE }

data class HomeUiState(
    val status: HomeStatus = HomeStatus.CHECKING,
    val statusDetail: String = "正在检测服务",
    val proactiveEnabled: Boolean = true,
    val edge: EdgeRuntimeState = EdgeRuntimeState(),
    val stats: SessionStats = SessionStats(0, 0, 0, 0, null, true),
)

class HomeViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            container.settings.settings.collect { s ->
                _state.value = _state.value.copy(proactiveEnabled = s.proactiveEnabled)
            }
        }
        viewModelScope.launch {
            container.repository.edgeState.collect { edge ->
                _state.value = _state.value.copy(edge = edge)
            }
        }
        viewModelScope.launch {
            container.repository.traces.collect {
                _state.value = _state.value.copy(stats = todayStats(container))
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(status = HomeStatus.CHECKING, statusDetail = "正在检测服务")
            runCatching { container.repository.health() }
                .onSuccess { health ->
                    val model = health.models["llm"].orEmpty()
                    _state.value = _state.value.copy(
                        status = HomeStatus.CLOUD_OK,
                        statusDetail = buildString {
                            append(model.ifBlank { health.provider.ifBlank { "服务正常" } })
                            append(" · ")
                            append(container.settings.current().normalizedBaseUrl)
                        },
                    )
                }
                .onFailure {
                    val edgeReady = _state.value.edge.ready
                    _state.value = _state.value.copy(
                        status = if (edgeReady) HomeStatus.EDGE_ONLY else HomeStatus.UNAVAILABLE,
                        statusDetail = if (edgeReady) {
                            "${_state.value.edge.name ?: "端侧模型"} · 离线可用"
                        } else {
                            "服务没响应，端侧模型也尚未接入"
                        },
                    )
                }
        }
    }

    fun setProactive(value: Boolean) {
        viewModelScope.launch { container.settings.setProactiveEnabled(value) }
    }

    /** 只统计今天的调用，避免"今日概览"其实是从历史第一天开始算。 */
    private fun todayStats(container: AppContainer): SessionStats {
        val today = LocalDate.now(ZoneId.systemDefault())
        val traces = container.repository.traces.value.filter {
            java.time.Instant.ofEpochMilli(it.timestamp).atZone(ZoneId.systemDefault()).toLocalDate() == today
        }
        return SessionStats(
            total = traces.size,
            edgeCount = traces.count { it.executorKind == ExecutorKind.EDGE },
            cloudCount = traces.count { it.executorKind == ExecutorKind.CLOUD },
            failureCount = traces.count { !it.success },
            avgMs = traces.filter { it.success }.map { it.totalMs }.average().takeIf { !it.isNaN() },
            preferEdge = true,
        )
    }
}
