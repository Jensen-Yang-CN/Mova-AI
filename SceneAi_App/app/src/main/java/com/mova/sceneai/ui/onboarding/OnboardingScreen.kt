package com.mova.sceneai.ui.onboarding

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.mova.sceneai.core.AppContainer
import com.mova.sceneai.core.movaViewModel
import com.mova.sceneai.core.toUiError
import com.mova.sceneai.data.model.HealthDto
import com.mova.sceneai.ui.components.MovaCard
import com.mova.sceneai.ui.components.SolidActionButton
import com.mova.sceneai.ui.theme.LocalMovaSemanticColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 首启向导：价值说明 → 权限 → 连接配置，三屏。
 *
 * 每一步都允许跳过 —— **绝不把用户卡在配置里**。没配好也能进主界面，
 * 只是能力受限，进去以后首页会用状态卡明确告诉他缺什么、点哪里补。
 */
@Composable
fun OnboardingScreen(onFinished: () -> Unit) {
    val vm = movaViewModel { OnboardingViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(pageCount = { 3 })

    var cameraGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var notifGranted by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        cameraGranted = it
    }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notifGranted = it
    }

    LaunchedEffect(Unit) { vm.loadCurrentBaseUrl() }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .imePadding()
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { vm.finish(onFinished) }) { Text("跳过") }
        }

        HorizontalPager(state = pagerState, modifier = Modifier.weight(1f)) { page ->
            when (page) {
                0 -> WelcomePage()
                1 -> PermissionPage(
                    cameraGranted = cameraGranted,
                    notifGranted = notifGranted,
                    onRequestCamera = { cameraLauncher.launch(Manifest.permission.CAMERA) },
                    onRequestNotif = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    },
                )

                else -> ConnectPage(
                    state = state,
                    onUrlChange = vm::onUrlChange,
                    onTest = vm::testConnection,
                )
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            repeat(3) { index ->
                Box(
                    Modifier
                        .padding(horizontal = 4.dp)
                        .size(if (index == pagerState.currentPage) 9.dp else 7.dp)
                        .background(
                            if (index == pagerState.currentPage) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outlineVariant,
                            CircleShape,
                        )
                )
            }
        }

        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            if (pagerState.currentPage < 2) {
                SolidActionButton(text = "下一步", modifier = Modifier.fillMaxWidth()) {
                    scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                }
            } else {
                SolidActionButton(text = "开始使用", modifier = Modifier.fillMaxWidth()) {
                    vm.finish(onFinished)
                }
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = { vm.finish(onFinished) }, modifier = Modifier.fillMaxWidth()) {
                    Text("先用演示模式（稍后可在设置里配置）")
                }
            }
        }
    }
}

// ============================================================
// 第 1 屏：价值说明
// ============================================================

@Composable
private fun WelcomePage() {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
        Spacer(Modifier.height(16.dp))
        Text("✨", fontSize = 44.sp)
        Spacer(Modifier.height(16.dp))
        Text("不用开口，它自己知道", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(10.dp))
        Text(
            "Mova-AI 感知你正在做什么，在最合适的时刻主动帮忙 —— 不用先想好怎么问，也不用先找到 App。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        ValueCard("🍜", "在厨房举起手机", "识别食材，直接告诉你能做哪道菜、怎么做")
        ValueCard("📄", "看到一篇长文章", "自动给出摘要、要点和一个值得继续追问的问题")
        ValueCard("💬", "不知道怎么回这条消息", "给推荐话术、备选方案，还说明为什么这样回")
        Spacer(Modifier.height(8.dp))
        Text(
            "复杂推理交给云端大模型，快速判断与离线可用的部分放在手机本地（端侧能力接入后自动启用）。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ValueCard(emoji: String, title: String, desc: String) {
    MovaCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(emoji, fontSize = 26.sp)
            Spacer(Modifier.width(14.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    Spacer(Modifier.height(10.dp))
}

// ============================================================
// 第 2 屏：权限
// ============================================================

@Composable
private fun PermissionPage(
    cameraGranted: Boolean,
    notifGranted: Boolean,
    onRequestCamera: () -> Unit,
    onRequestNotif: () -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
        Spacer(Modifier.height(16.dp))
        Text("需要两个权限", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(10.dp))
        Text(
            "都只在你主动使用对应功能时才会用到。现在不给也可以，之后随时能补。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        PermissionRow("📷", "相机", "拍食材、拍文章，用来识别内容", cameraGranted, onRequestCamera)
        Spacer(Modifier.height(10.dp))
        PermissionRow("🔔", "通知", "场景触发时提醒你，可随时关闭", notifGranted, onRequestNotif)
    }
}

@Composable
private fun PermissionRow(
    emoji: String,
    title: String,
    desc: String,
    granted: Boolean,
    onRequest: () -> Unit,
) {
    MovaCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(emoji, fontSize = 24.sp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (granted) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = "已授权",
                    tint = LocalMovaSemanticColors.current.success,
                    modifier = Modifier.size(20.dp),
                )
            } else {
                OutlinedButton(onClick = onRequest) { Text("授权") }
            }
        }
    }
}

// ============================================================
// 第 3 屏：连接配置
// ============================================================

@Composable
private fun ConnectPage(
    state: OnboardingUiState,
    onUrlChange: (String) -> Unit,
    onTest: () -> Unit,
) {
    val semantic = LocalMovaSemanticColors.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
        Spacer(Modifier.height(16.dp))
        Text("连接你的 AI 服务", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(10.dp))
        Text(
            "复杂推理要交给云端的大模型。填一次就行，之后可以在设置里随时改。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))

        OutlinedTextField(
            value = state.baseUrl,
            onValueChange = onUrlChange,
            label = { Text("服务地址") },
            placeholder = { Text("http://192.168.1.10:8000/") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            supportingText = {
                Text(
                    "模拟器用 10.0.2.2；真机填运行服务那台电脑的局域网 IP",
                    style = MaterialTheme.typography.bodySmall,
                )
            },
        )

        Spacer(Modifier.height(12.dp))
        SolidActionButton(
            text = if (state.testing) "正在检测…" else "检测连接",
            enabled = !state.testing,
            modifier = Modifier.fillMaxWidth(),
            onClick = onTest,
        )

        Spacer(Modifier.height(12.dp))
        when {
            state.testing -> Unit

            state.health != null -> MovaCard(containerColor = semantic.successContainer) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Wifi,
                        contentDescription = null,
                        tint = semantic.onSuccessContainer,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(
                            "已连接 · " + (state.health.models["llm"] ?: state.health.provider.ifBlank { "服务正常" }),
                            style = MaterialTheme.typography.titleSmall,
                            color = semantic.onSuccessContainer,
                        )
                        Text(
                            "服务版本 " + state.health.version.ifBlank { "未知" },
                            style = MaterialTheme.typography.bodySmall,
                            color = semantic.onSuccessContainer,
                        )
                    }
                }
            }

            state.error != null -> MovaCard(containerColor = MaterialTheme.colorScheme.errorContainer) {
                Column {
                    Text(
                        "无法连接",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        state.error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "排查：① 地址是否形如 http://IP:8000/ ② 电脑上的服务是否已启动 ③ 手机与电脑是否在同一 WiFi",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.85f),
                    )
                }
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

// ============================================================
// ViewModel
// ============================================================

data class OnboardingUiState(
    val baseUrl: String = "",
    val testing: Boolean = false,
    val health: HealthDto? = null,
    val error: String? = null,
)

class OnboardingViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(OnboardingUiState())
    val state: StateFlow<OnboardingUiState> = _state.asStateFlow()

    fun loadCurrentBaseUrl() {
        viewModelScope.launch {
            _state.value = _state.value.copy(baseUrl = container.settings.current().baseUrl)
        }
    }

    fun onUrlChange(value: String) {
        _state.value = _state.value.copy(baseUrl = value, health = null, error = null)
    }

    fun testConnection() {
        viewModelScope.launch {
            _state.value = _state.value.copy(testing = true, health = null, error = null)
            // 先落盘，让检测用的就是刚填的地址
            runCatching { container.settings.setBaseUrl(_state.value.baseUrl) }
            runCatching { container.repository.health() }
                .onSuccess { _state.value = _state.value.copy(testing = false, health = it) }
                .onFailure { throwable ->
                    val ui = throwable.toUiError()
                    _state.value = _state.value.copy(
                        testing = false,
                        error = "${ui.title}。${ui.detail}",
                    )
                }
        }
    }

    fun finish(onFinished: () -> Unit) {
        viewModelScope.launch {
            runCatching { container.settings.setBaseUrl(_state.value.baseUrl) }
            container.settings.setOnboardingDone(true)
            onFinished()
        }
    }
}
