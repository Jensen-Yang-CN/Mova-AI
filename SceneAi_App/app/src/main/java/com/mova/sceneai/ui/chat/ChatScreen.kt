package com.mova.sceneai.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.mova.sceneai.core.AppContainer
import com.mova.sceneai.core.LoadState
import com.mova.sceneai.core.ShareSource
import com.mova.sceneai.core.movaViewModel
import com.mova.sceneai.core.toUiError
import com.mova.sceneai.data.model.ChatDto
import com.mova.sceneai.data.model.ChatMessage
import com.mova.sceneai.ui.components.CopyIconButton
import com.mova.sceneai.ui.components.ErrorCard
import com.mova.sceneai.ui.components.InfoBanner
import com.mova.sceneai.ui.components.LoadingStages
import com.mova.sceneai.ui.components.MovaCard
import com.mova.sceneai.ui.components.MovaChipRow
import com.mova.sceneai.ui.components.MovaScreen
import com.mova.sceneai.ui.components.OutlinedActionButton
import com.mova.sceneai.ui.components.SolidActionButton
import com.mova.sceneai.ui.theme.LocalMovaSemanticColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 聊天辅助。
 *
 * 产品立场：**给人选择，而不是替人做决定。** 因此结果固定是三段式 ——
 * 推荐回复 / 备选方案 / 为什么这样回。解释区让 AI 从"代笔"变成"协作方"。
 *
 * 三个可控维度（场景 + 目标 + 风格）都是显式的 chips，用户能看见自己在控制什么。
 *
 * ## 两个入口
 * ① **手动输入**：用户自己把对方的话粘进来。
 * ② **系统分享**：在微信里长按对方的消息 → 分享 → Mova-AI。
 *    内容经 `MainActivity` → [com.mova.sceneai.core.SharedInbox] → 本页 ViewModel，
 *    自动填入并立刻生成建议。
 *
 * 刻意**不做**无障碍读屏自动抓取：那虽然能自动感知"你正在用微信"，但违反微信
 * 服务协议、在 Android 13+ 上需要用户手动解除「受限设置」，且涉及第三方个人信息
 * 合规。完整的三方案对比见 `docs/03-UI-UX设计规范.md` §3.5。
 */
@Composable
fun ChatScreen(onBack: () -> Unit, onOpenSettings: () -> Unit) {
    val vm = movaViewModel { ChatViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val semantic = LocalMovaSemanticColors.current

    MovaScreen(title = "聊天辅助", subtitle = "帮你把话说好，但决定权在你", onBack = onBack) {

        // 从别的 App 分享进来时的提示条：让用户明白这句话是从哪来的、正在发生什么
        state.fromShare?.let { source ->
            InfoBanner(
                text = "${source.label}：内容已填入下方，正在生成回复建议。" +
                    "这是你主动分享的，App 不会读取其它应用的界面。",
                icon = Icons.Filled.Share,
                container = semantic.successContainer,
                contentColor = semantic.onSuccessContainer,
            )
        }

        MovaChipRow(
            options = listOf("回复对方", "润色我的话"),
            selected = if (state.mode == ChatMode.REPLY) "回复对方" else "润色我的话",
            onSelect = { vm.setMode(if (it == "回复对方") ChatMode.REPLY else ChatMode.REWRITE) },
        )

        OutlinedTextField(
            value = state.input,
            onValueChange = vm::onInputChange,
            label = {
                Text(if (state.mode == ChatMode.REPLY) "对方说了什么？" else "我想改写哪句话？")
            },
            placeholder = {
                Text(
                    if (state.mode == ChatMode.REPLY) "把对方的消息粘贴进来…"
                    else "把你想说的话写进来…"
                )
            },
            modifier = Modifier.fillMaxWidth().heightIn(min = 110.dp),
        )

        Text("风格", style = MaterialTheme.typography.titleSmall)
        MovaChipRow(
            options = ChatViewModel.STYLES,
            selected = state.style,
            onSelect = vm::setStyle,
        )

        if (state.mode == ChatMode.REPLY) {
            Text("沟通目标", style = MaterialTheme.typography.titleSmall)
            Text(
                "设定目标后，AI 会据此选择策略，而不只是换词",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            MovaChipRow(
                options = ChatViewModel.GOALS,
                selected = state.goal,
                onSelect = vm::setGoal,
            )
        }

        // ---------- 多轮上下文（可展开） ----------
        MovaCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("带入多轮上下文", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "对话历史越长，回复越贴合真实语境",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { vm.toggleContext() }) {
                    Text(if (state.contextExpanded) "收起" else "展开")
                }
            }
            if (state.contextExpanded) {
                Spacer(Modifier.height(8.dp))
                state.messages.forEachIndexed { index, message ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (message.role == "user") "我" else "对方",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.width(36.dp),
                        )
                        Text(
                            message.content,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { vm.removeMessage(index) }) { Text("删除") }
                    }
                }
                OutlinedActionButton(
                    text = "添加一条历史消息",
                    modifier = Modifier.fillMaxWidth(),
                ) { vm.addMessage() }
                Spacer(Modifier.height(6.dp))
                Text(
                    "新加的消息默认是「对方」，点一下角色即可切换",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                state.messages.forEachIndexed { index, _ -> }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SolidActionButton(
                text = "生成回复",
                modifier = Modifier.weight(1f),
                enabled = state.input.isNotBlank() && !state.load.isLoading,
            ) { vm.generate() }
            OutlinedActionButton(
                text = "试试示例",
                icon = Icons.Filled.AutoAwesome,
                modifier = Modifier.weight(1f),
            ) { vm.useSample() }
        }

        if (state.load.isLoading) {
            LoadingStages(
                stages = listOf("正在理解这句话…", "正在生成候选与解释…"),
                activeIndex = 1,
            )
        }

        (state.load as? LoadState.Failed)?.let {
            ErrorCard(error = it.error, onRetry = vm::generate, onOpenSettings = onOpenSettings)
        }

        (state.load as? LoadState.Success)?.let { success ->
            ChatResultCard(success.data, onRegenerate = vm::generate)
        }
    }
}

@Composable
private fun ChatResultCard(dto: ChatDto, onRegenerate: () -> Unit) {
    val semantic = LocalMovaSemanticColors.current
    var explainOpen by remember { mutableStateOf(true) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // ① 推荐回复
        MovaCard(containerColor = MaterialTheme.colorScheme.primaryContainer) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "推荐回复",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Spacer(Modifier.weight(1f))
                    CopyIconButton(dto.reply, toast = "已复制这条回复")
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    dto.reply,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.Medium,
                )
                if (dto.style.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "风格：${dto.style}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                    )
                }
            }
        }

        // ② 备选方案
        if (dto.alternatives.isNotEmpty()) {
            MovaCard {
                Column {
                    Text("备选方案", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(6.dp))
                    dto.alternatives.forEach { alt ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                alt,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f).padding(vertical = 4.dp),
                            )
                            CopyIconButton(alt, toast = "已复制备选")
                        }
                    }
                }
            }
        }

        // ③ 可解释
        if (dto.explain.isNotBlank()) {
            MovaCard {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("为什么这样回", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { explainOpen = !explainOpen }) {
                            Text(if (explainOpen) "收起" else "展开")
                        }
                    }
                    if (explainOpen) {
                        Text(
                            dto.explain,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        InfoBanner(
            text = "AI 给的是选项，不是结论。挑一条最像你的，或者按自己的话改一改。",
            container = semantic.successContainer,
            contentColor = semantic.onSuccessContainer,
        )

        OutlinedActionButton(
            text = "换一批",
            icon = Icons.Filled.Refresh,
            modifier = Modifier.fillMaxWidth(),
            onClick = onRegenerate,
        )
    }
}

// ============================================================
// ViewModel
// ============================================================

enum class ChatMode { REPLY, REWRITE }

data class ChatUiState(
    val mode: ChatMode = ChatMode.REPLY,
    val input: String = "",
    val style: String = "自然",
    val goal: String = "不指定",
    val contextExpanded: Boolean = false,
    val messages: List<ChatMessage> = emptyList(),
    val load: LoadState<ChatDto> = LoadState.Idle,
    /** 非空表示这次的内容是从其它应用分享进来的（详见 SharedInbox） */
    val fromShare: ShareSource? = null,
)

/**
 * 聊天页的状态机。
 *
 * 除了常规的输入/风格/目标管理，它还承担一件特殊的事：
 * **在页面创建时消费一次 [com.mova.sceneai.core.SharedInbox] 里的分享内容**，
 * 填进输入框并自动生成。这让"从微信分享进来"变成一个零操作的动作。
 *
 * 用 `XxxUiState` + `StateFlow` 表达状态，Composable 只负责渲染 ——
 * 因此"加载中 / 成功 / 失败 / 来源是分享"这些分支在界面上是显式的，
 * 不会出现"某个状态下界面不知道该显示什么"。
 */
class ChatViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    init {
        // 页面被创建时先看一眼收件箱：如果是"分享进来"的，直接填好并开始生成。
        // 放在 init 里而不是 Composable 的 LaunchedEffect 里，是为了让
        // "分享 → 生成"这条链路只被触发一次，即使页面因配置变化重组也不会重复请求。
        consumeSharedInput()
    }

    private fun consumeSharedInput() {
        val shared = container.sharedInbox.take() ?: return
        _state.value = _state.value.copy(
            mode = ChatMode.REPLY,
            input = shared.text,
            fromShare = shared.source,
        )
        generate()
    }

    fun setMode(mode: ChatMode) {
        _state.value = _state.value.copy(mode = mode, load = LoadState.Idle)
    }

    fun onInputChange(value: String) {
        // 用户一旦手动改动内容，就不再强调"来自分享"
        _state.value = _state.value.copy(input = value, fromShare = null)
    }

    fun setStyle(value: String) {
        _state.value = _state.value.copy(style = value)
    }

    fun setGoal(value: String) {
        _state.value = _state.value.copy(goal = value)
    }

    fun toggleContext() {
        _state.value = _state.value.copy(contextExpanded = !_state.value.contextExpanded)
    }

    fun addMessage() {
        _state.value = _state.value.copy(
            messages = _state.value.messages + ChatMessage("assistant", ""),
        )
    }

    fun removeMessage(index: Int) {
        val next = _state.value.messages.toMutableList().also { it.removeAt(index) }
        _state.value = _state.value.copy(messages = next)
    }

    fun useSample() {
        _state.value = _state.value.copy(
            mode = ChatMode.REPLY,
            input = "这周六我们部门团建，去郊区烧烤，你能来吗？",
            style = "委婉",
            goal = "拒绝",
        )
    }

    fun generate() {
        val s = _state.value
        if (s.input.isBlank()) return
        viewModelScope.launch {
            _state.value = s.copy(load = LoadState.Loading)
            val result = runCatching {
                when (s.mode) {
                    ChatMode.REWRITE -> container.repository.chatRewrite(s.input, s.style)

                    ChatMode.REPLY -> {
                        val validHistory = s.messages.filter { it.content.isNotBlank() }
                        if (validHistory.isEmpty()) {
                            container.repository.chatReply(s.input, s.style)
                        } else {
                            // 把"对方刚说的这句"作为最后一条，历史在前
                            val conversation = validHistory + ChatMessage("assistant", s.input)
                            container.repository.chatContext(
                                messages = conversation,
                                goal = if (s.goal == "不指定") "" else s.goal,
                                style = s.style,
                            )
                        }
                    }
                }
            }
            result
                .onSuccess { _state.value = _state.value.copy(load = LoadState.Success(it)) }
                .onFailure { _state.value = _state.value.copy(load = LoadState.Failed(it.toUiError())) }
        }
    }

    companion object {
        val STYLES = listOf("自然", "礼貌", "委婉", "幽默", "职场", "亲密", "冷处理")
        val GOALS = listOf("不指定", "接受", "拒绝", "推迟", "转移话题", "降低冲突")
    }
}
