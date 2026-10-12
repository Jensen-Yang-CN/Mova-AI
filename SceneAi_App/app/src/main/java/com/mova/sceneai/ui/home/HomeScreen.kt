package com.mova.sceneai.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.RestaurantMenu
import androidx.compose.material.icons.filled.CheckCircleOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.mova.sceneai.core.AppContainer
import com.mova.sceneai.core.movaViewModel
import com.mova.sceneai.data.model.ExecutorKind
import com.mova.sceneai.data.repo.EdgeRuntimeState
import com.mova.sceneai.data.repo.SessionStats
import com.mova.sceneai.ui.components.*
import com.mova.sceneai.ui.nav.Routes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 首页用具体的小求助组织入口，运行状态来自真实模型与服务检测。 */
@Composable
fun HomeScreen(onOpenScene: (String) -> Unit, onOpenSettings: () -> Unit, onOpenTech: () -> Unit) {
    val vm = movaViewModel { HomeViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refresh() }
    MovaScreen(title = "Mova / 照应", subtitle = "日常的小求助，从这里开始", actions = {
        Text(LocalDate.now().format(DateTimeFormatter.ofPattern("MM.dd")), style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 20.dp))
    }) {
        HomeHero { onOpenScene(Routes.CHAT) }
        MovaCard(onClick = { onOpenScene(Routes.CITY) }, contentPadding = 20) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("城市初见 · 一份旅途中的照应", style = MaterialTheme.typography.titleMedium)
                Text("原地播放模拟行程，或开启本次真实出行 →", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        MovaCard(onClick = onOpenSettings, contentPadding = 15) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Readiness(if (state.edge.ready) "手机理解已就绪" else "端侧模型待导入", state.edge.ready, Modifier.weight(1f))
                Readiness(if (state.status == HomeStatus.CLOUD_OK) "回复服务已连接" else "回复服务待连接", state.status == HomeStatus.CLOUD_OK, Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(2.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("给日常一点余地", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            Text("02 / DAILY", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            DailyCard("读懂一页", "把长材料，变成清楚的下一步", Icons.Filled.AutoStories, Modifier.weight(1f)) { onOpenScene(Routes.READING) }
            DailyCard("好好吃饭", "从手边的食材，开始一顿饭", Icons.Filled.RestaurantMenu, Modifier.weight(1f)) { onOpenScene(Routes.FOOD) }
        }
        MovaCard(onClick = { onOpenScene(Routes.CHAT) }, contentPadding = 22) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("一个日常的停顿", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text("想拒绝，\n也想把关系照顾好。", style = MaterialTheme.typography.headlineSmall)
                Text("带来一句消息，补充你的处境。先明确自己的目标，再选择适合的表达。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("试试沟通辅助", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                }
            }
        }
        MovaCard(onClick = onOpenTech, contentPadding = 18) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.CheckCircleOutline, null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("理解在手机，表达有选择", style = MaterialTheme.typography.titleSmall)
                    Text(if (state.stats.total > 0) "今日 ${state.stats.total} 次请求 · 查看实际运行记录" else "查看本机模型与端云协同的运行证据", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.AutoMirrored.Filled.ArrowForward, null, modifier = Modifier.size(18.dp))
            }
        }
        Text("小事有人接住，生活慢慢上手。", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.CenterHorizontally).padding(vertical = 10.dp))
    }
}

@Composable
private fun HomeHero(onChat: () -> Unit) {
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(32.dp)).background(Brush.linearGradient(listOf(MovaInk, Color(0xFF294A40))))) {
        MovaOrb(Modifier.align(Alignment.TopEnd).offset(x = 34.dp, y = 14.dp).size(210.dp))
        Column(Modifier.fillMaxWidth().padding(24.dp)) {
            Text("A LITTLE HELP, RIGHT HERE", color = MovaMint, fontSize = 10.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(22.dp))
            Text("先照顾好\n这一刻。", color = Color(0xFFFFF6E9), fontSize = 36.sp, lineHeight = 46.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            Text("离家后的日常，\n也值得被认真照应。", color = Color(0xFFD4DED3), fontSize = 14.sp, lineHeight = 23.sp)
            Spacer(Modifier.height(24.dp))
            Button(onClick = onChat, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MovaPeach, contentColor = MovaInk)) {
                Text("把这句话说好", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Icon(Icons.AutoMirrored.Filled.ArrowForward, null, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun Readiness(text: String, ready: Boolean, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Dot(if (ready) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outline, 6)
        Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DailyCard(title: String, desc: String, icon: ImageVector, modifier: Modifier, onClick: () -> Unit) {
    MovaCard(modifier, onClick = onClick, contentPadding = 18) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(46.dp).background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(25.dp))
            }
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(desc, minLines = 2, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

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
                            "端侧判断可用，但回复正文仍需云端服务"
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
