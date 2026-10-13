package com.mova.sceneai.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.mova.sceneai.R
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

/** 首页突出四个具体任务。状态点来自真实检测，装饰不使用虚构的运行数据。 */
@Composable
fun HomeScreen(onOpenScene: (String) -> Unit, onOpenSettings: () -> Unit, onOpenTech: () -> Unit) {
    val vm = movaViewModel { HomeViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refresh() }
    MovaScreen(title = "Mova · 照应", subtitle = "先处理眼前的事",
        verticalArrangement = Arrangement.spacedBy(12.dp), actions = {
        Text(LocalDate.now().format(DateTimeFormatter.ofPattern("MM / dd")),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 20.dp))
    }) {
        HomeHero { onOpenScene(Routes.CHAT) }
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onOpenSettings)
            .background(MaterialTheme.colorScheme.surfaceContainer).padding(horizontal = 14.dp, vertical = 11.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Readiness(if (state.edge.ready) "手机理解已就绪" else "端侧模型待导入", state.edge.ready, Modifier.weight(1f))
            Readiness(if (state.status == HomeStatus.CLOUD_OK) "回复服务已连接" else "回复服务待连接",
                state.status == HomeStatus.CLOUD_OK, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("今天想做什么", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text("01 — 04", fontSize = 9.sp, letterSpacing = 1.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SceneTile("01", "把话说好", "回消息 · 改措辞", Modifier.weight(1f)) { onOpenScene(Routes.CHAT) }
            SceneTile("02", "读懂一页", "找重点 · 看摘要", Modifier.weight(1f)) { onOpenScene(Routes.READING) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SceneTile("03", "好好吃饭", "拍食材 · 找做法", Modifier.weight(1f)) { onOpenScene(Routes.FOOD) }
            SceneTile("04", "城市初见", "新城市 · 先逛逛", Modifier.weight(1f)) { onOpenScene(Routes.CITY) }
        }
        MovaCard(onClick = onOpenTech, contentPadding = 17) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Filled.Insights, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                Column(Modifier.weight(1f)) {
                    Text("查看运行记录", style = MaterialTheme.typography.titleSmall)
                    Text("模型状态 · 请求耗时 · 处理过程", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.AutoMirrored.Filled.ArrowForward, null, modifier = Modifier.size(18.dp))
            }
        }
        Text("不知道从哪开始？每个功能都能先试示例", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(vertical = 6.dp))
    }
}

/** 头图只提供氛围；文字、状态与按钮均为原生组件，缩放字体后仍可布局。 */
@Composable
private fun HomeHero(onChat: () -> Unit) {
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(MovaInk)) {
        Image(painterResource(R.drawable.mova_hero_v3), null, Modifier.matchParentSize(),
            contentScale = ContentScale.Crop, alignment = Alignment.CenterEnd)
        Box(Modifier.matchParentSize().background(Brush.horizontalGradient(
            0f to MovaInk.copy(.80f), .50f to MovaInk.copy(.36f), 1f to Color.Transparent)))
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(
            0f to Color.Transparent, .65f to Color.Transparent, 1f to MovaInk.copy(.92f))))
        Column(Modifier.fillMaxWidth().padding(22.dp)) {
            Text("MOVA / EVERYDAY", style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 9.sp, letterSpacing = 1.2.sp), color = MovaMint)
            Spacer(Modifier.height(17.dp))
            Text("今天先处理\n哪件事", style = MaterialTheme.typography.displaySmall.copy(
                fontSize = 34.sp, lineHeight = 45.sp, fontWeight = FontWeight.Bold), color = Color.White)
            Spacer(Modifier.height(11.dp))
            Text("回一条消息，读一份通知\n或者看看晚饭做什么",
                style = MaterialTheme.typography.bodySmall, color = Color(0xFFD7E9E6))
            Spacer(Modifier.height(17.dp))
            Button(onClick = onChat, modifier = Modifier.fillMaxWidth().height(51.dp),
                shape = RoundedCornerShape(15.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MovaPeach, contentColor = MovaInk)) {
                Text("先把话说好", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                Icon(Icons.AutoMirrored.Filled.ArrowForward, null, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun Readiness(text: String, ready: Boolean, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Dot(if (ready) Color(0xFF3D9585) else MaterialTheme.colorScheme.outline, 5)
        Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** 四张任务卡具有相同尺寸与交互；图形负责辨识场景，文字负责解释行动。 */
@Composable
private fun SceneTile(index: String, title: String, description: String, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier.clip(RoundedCornerShape(23.dp)).background(sceneSurface(index)).clickable(onClick = onClick)) {
        SceneGlyph(index, Modifier.align(Alignment.TopEnd).padding(top = 1.dp, end = 1.dp).size(91.dp))
        Column(Modifier.fillMaxWidth().padding(17.dp)) {
            Text(index, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(35.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(4.dp))
            Text(description, style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
