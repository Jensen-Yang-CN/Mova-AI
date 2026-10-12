package com.mova.sceneai.ui.settings

import android.app.TimePickerDialog
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.mova.sceneai.core.AppContainer
import com.mova.sceneai.core.InterruptSensitivity
import com.mova.sceneai.core.ThemeMode
import com.mova.sceneai.core.movaViewModel
import com.mova.sceneai.core.toUiError
import com.mova.sceneai.data.repo.EdgeRuntimeState
import com.mova.sceneai.ui.components.InfoBanner
import com.mova.sceneai.ui.components.KeyValueRow
import com.mova.sceneai.ui.components.MovaCard
import com.mova.sceneai.ui.components.MovaChipRow
import com.mova.sceneai.ui.components.MovaScreen
import com.mova.sceneai.ui.components.OutlinedActionButton
import com.mova.sceneai.ui.components.SectionHeader
import com.mova.sceneai.ui.components.SolidActionButton
import com.mova.sceneai.ui.theme.LocalMovaSemanticColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * 设置页。
 *
 * 关于端侧的三条原则（对应设计文档 §7）：
 *  · 端侧未就绪时，"优先使用端侧"开关**禁用并说明原因**，不做假
 *  · 所有会改变行为的开关都带一句"它到底影响什么"
 *  · 破坏性操作（清除记录）二次确认
 */
@Composable
fun SettingsScreen(onOpenAbout: () -> Unit, onRerunOnboarding: () -> Unit) {
    val vm = movaViewModel { SettingsViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showClearConfirm by remember { mutableStateOf(false) }
    val modelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.installEdgeModel(uri)
    }

    LaunchedEffect(Unit) { vm.load() }

    MovaScreen(title = "设置", subtitle = "连接、模型与外观") {

        // ---------- 服务连接 ----------
        SectionHeader("服务连接")
        MovaCard {
            Column {
                OutlinedTextField(
                    value = state.baseUrl,
                    onValueChange = vm::onBaseUrlChange,
                    label = { Text("服务地址") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    supportingText = {
                        Text(
                            "模拟器用 10.0.2.2；真机填电脑的局域网 IP",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SolidActionButton(
                        text = if (state.testing) "检测中…" else "保存并检测",
                        enabled = !state.testing,
                        modifier = Modifier.weight(1f),
                    ) { vm.saveAndTest() }
                }
                state.healthMessage?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state.healthOk) LocalMovaSemanticColors.current.success
                        else MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.height(14.dp))
                Text("云端请求超时", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                MovaChipRow(
                    options = listOf("30 秒", "60 秒", "120 秒"),
                    selected = when (state.timeoutSeconds) {
                        30 -> "30 秒"
                        120 -> "120 秒"
                        else -> "60 秒"
                    },
                    onSelect = { label ->
                        vm.setTimeout(label.filter { it.isDigit() }.toIntOrNull() ?: 60)
                    },
                )
            }
        }

        // ---------- 主动智能 ----------
        SectionHeader("提醒偏好 · 实验功能", "当前版本以用户主动提交的内容为入口")
        MovaCard {
            Column {
                SwitchRow(
                    title = "保存场景提醒偏好",
                    desc = "实验设置；当前版本尚不自动触发提醒",
                    checked = state.proactiveEnabled,
                    onCheckedChange = vm::setProactive,
                )
                Spacer(Modifier.height(12.dp))
                SwitchRow(
                    title = "免打扰时段",
                    desc = "为后续提醒功能保存安静时段",
                    checked = state.dndEnabled,
                    onCheckedChange = vm::setDndEnabled,
                )
                if (state.dndEnabled) {
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedActionButton(
                            text = "开始 ${formatMinutes(state.dndStart)}",
                            modifier = Modifier.weight(1f),
                        ) {
                            TimePickerDialog(
                                context,
                                { _, h, m -> vm.setDndStart(h * 60 + m) },
                                state.dndStart / 60,
                                state.dndStart % 60,
                                true,
                            ).show()
                        }
                        OutlinedActionButton(
                            text = "结束 ${formatMinutes(state.dndEnd)}",
                            modifier = Modifier.weight(1f),
                        ) {
                            TimePickerDialog(
                                context,
                                { _, h, m -> vm.setDndEnd(h * 60 + m) },
                                state.dndEnd / 60,
                                state.dndEnd % 60,
                                true,
                            ).show()
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text("打扰敏感度", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                MovaChipRow(
                    options = InterruptSensitivity.entries.map { it.label },
                    selected = state.sensitivity.label,
                    onSelect = { label ->
                        InterruptSensitivity.entries.firstOrNull { it.label == label }?.let(vm::setSensitivity)
                    },
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "这些偏好目前仅保存在本机；位置与后台自动触发仍在规划中。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ---------- 端侧模型 ----------
        SectionHeader("端侧模型", "本机进行场景与意图判断")
        MovaCard {
            Column {
                KeyValueRow("状态", state.edge.statusLabel)
                KeyValueRow("模型", state.edge.name ?: "未配置")
                KeyValueRow("量化位宽", state.edge.quantization ?: "—")
                Spacer(Modifier.height(12.dp))
                OutlinedActionButton(
                    text = if (state.importingEdge) "正在校验并加载模型…" else "导入 Q4_K_M 模型",
                    onClick = { if (!state.importingEdge) modelPicker.launch(arrayOf("*/*")) },
                )
                Text(
                    "从手机存储选择本项目的 GGUF（约 397 MB）；App 会核对 SHA-256 后加载。模型不上传服务器。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                SwitchRow(
                    title = "优先使用端侧",
                    desc = if (state.edge.ready) {
                        "聊天意图先在本地判断；回复正文仍由云端生成"
                    } else {
                        "端侧模型接入后可用"
                    },
                    checked = state.preferEdge,
                    enabled = state.edge.ready,
                    onCheckedChange = vm::setPreferEdge,
                )
                if (!state.edge.ready || state.edge.loadingError != null) {
                    Spacer(Modifier.height(10.dp))
                    InfoBanner(
                        text = state.edge.unavailableReason
                            ?: "端侧小模型尚未接入；接入后这里会自动变为可用。",
                        container = LocalMovaSemanticColors.current.warningContainer,
                        contentColor = LocalMovaSemanticColors.current.onWarningContainer,
                    )
                }
            }
        }

        // ---------- 外观 ----------
        SectionHeader("外观")
        MovaCard {
            MovaChipRow(
                options = listOf("跟随系统", "浅色", "深色"),
                selected = when (state.themeMode) {
                    ThemeMode.SYSTEM -> "跟随系统"
                    ThemeMode.LIGHT -> "浅色"
                    ThemeMode.DARK -> "深色"
                },
                onSelect = { label ->
                    vm.setThemeMode(
                        when (label) {
                            "浅色" -> ThemeMode.LIGHT
                            "深色" -> ThemeMode.DARK
                            else -> ThemeMode.SYSTEM
                        }
                    )
                },
            )
        }

        // ---------- 数据 ----------
        SectionHeader("数据")
        MovaCard {
            Column {
                OutlinedActionButton(
                    text = "清除全部记录（${state.historyCount} 条）",
                    modifier = Modifier.fillMaxWidth(),
                ) { showClearConfirm = true }
                Spacer(Modifier.height(8.dp))
                OutlinedActionButton(
                    text = "重新运行首启向导",
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onRerunOnboarding,
                )
            }
        }

        // ---------- 关于 ----------
        SectionHeader("关于")
        MovaCard(onClick = onOpenAbout) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("关于 Mova-AI", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "版本、技术方案与端云协同说明",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.outline)
            }
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("清除全部记录？") },
            text = { Text("会删除本地保存的 ${state.historyCount} 条结果与技术面板日志，此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    vm.clearHistory()
                    showClearConfirm = false
                }) { Text("清除") }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun SwitchRow(
    title: String,
    desc: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
    }
}

private fun formatMinutes(minutes: Int): String =
    String.format(Locale.CHINA, "%02d:%02d", minutes / 60, minutes % 60)

// ============================================================
// ViewModel
// ============================================================

data class SettingsUiState(
    val baseUrl: String = "",
    val timeoutSeconds: Int = 60,
    val proactiveEnabled: Boolean = true,
    val dndEnabled: Boolean = false,
    val dndStart: Int = 23 * 60,
    val dndEnd: Int = 7 * 60,
    val sensitivity: InterruptSensitivity = InterruptSensitivity.MEDIUM,
    val preferEdge: Boolean = true,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val edge: EdgeRuntimeState = EdgeRuntimeState(),
    val importingEdge: Boolean = false,
    val historyCount: Int = 0,
    val testing: Boolean = false,
    val healthOk: Boolean = false,
    val healthMessage: String? = null,
)

class SettingsViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    fun load() {
        viewModelScope.launch {
            val s = container.settings.current()
            _state.value = _state.value.copy(
                baseUrl = s.baseUrl,
                timeoutSeconds = s.timeoutSeconds,
                proactiveEnabled = s.proactiveEnabled,
                dndEnabled = s.dndEnabled,
                dndStart = s.dndStartMinutes,
                dndEnd = s.dndEndMinutes,
                sensitivity = s.sensitivity,
                preferEdge = s.preferEdge,
                themeMode = s.themeMode,
            )
        }
        viewModelScope.launch {
            container.repository.edgeState.collect { edge ->
                _state.value = _state.value.copy(edge = edge)
            }
        }
        viewModelScope.launch {
            container.repository.history.collect { list ->
                _state.value = _state.value.copy(historyCount = list.size)
            }
        }
    }

    fun onBaseUrlChange(value: String) {
        _state.value = _state.value.copy(baseUrl = value, healthMessage = null)
    }

    fun installEdgeModel(uri: Uri) {
        if (_state.value.importingEdge) return
        viewModelScope.launch {
            _state.value = _state.value.copy(importingEdge = true)
            try {
                container.repository.installEdgeModel(uri)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // EdgeModelManager 已在 edgeState.loadingError 中写入具体原因。
            } finally {
                _state.value = _state.value.copy(importingEdge = false)
            }
        }
    }

    fun saveAndTest() {
        viewModelScope.launch {
            _state.value = _state.value.copy(testing = true, healthMessage = null)
            container.settings.setBaseUrl(_state.value.baseUrl)
            runCatching { container.repository.health() }
                .onSuccess { health ->
                    _state.value = _state.value.copy(
                        testing = false,
                        healthOk = true,
                        healthMessage = "连接正常 · " +
                            (health.models["llm"] ?: health.provider.ifBlank { "服务已响应" }),
                    )
                }
                .onFailure { throwable ->
                    val ui = throwable.toUiError()
                    _state.value = _state.value.copy(
                        testing = false,
                        healthOk = false,
                        healthMessage = "${ui.title}。${ui.hint ?: ui.detail}",
                    )
                }
        }
    }

    fun setTimeout(seconds: Int) {
        viewModelScope.launch {
            container.settings.setTimeoutSeconds(seconds)
            _state.value = _state.value.copy(timeoutSeconds = seconds)
        }
    }

    fun setProactive(value: Boolean) {
        viewModelScope.launch {
            container.settings.setProactiveEnabled(value)
            _state.value = _state.value.copy(proactiveEnabled = value)
        }
    }

    fun setDndEnabled(value: Boolean) {
        viewModelScope.launch {
            container.settings.setDndEnabled(value)
            _state.value = _state.value.copy(dndEnabled = value)
        }
    }

    fun setDndStart(minutes: Int) {
        viewModelScope.launch {
            container.settings.setDndStart(minutes)
            _state.value = _state.value.copy(dndStart = minutes)
        }
    }

    fun setDndEnd(minutes: Int) {
        viewModelScope.launch {
            container.settings.setDndEnd(minutes)
            _state.value = _state.value.copy(dndEnd = minutes)
        }
    }

    fun setSensitivity(value: InterruptSensitivity) {
        viewModelScope.launch {
            container.settings.setSensitivity(value)
            _state.value = _state.value.copy(sensitivity = value)
        }
    }

    fun setPreferEdge(value: Boolean) {
        viewModelScope.launch {
            container.settings.setPreferEdge(value)
            _state.value = _state.value.copy(preferEdge = value)
        }
    }

    fun setThemeMode(value: ThemeMode) {
        viewModelScope.launch {
            container.settings.setThemeMode(value)
            _state.value = _state.value.copy(themeMode = value)
        }
    }

    fun clearHistory() {
        viewModelScope.launch { container.repository.clearHistory() }
    }
}
