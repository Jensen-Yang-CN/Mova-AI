package com.mova.sceneai.ui.reading

import android.content.Intent
import android.provider.CalendarContract
import java.time.OffsetDateTime
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.mova.sceneai.MovaApp
import com.mova.sceneai.core.AppContainer
import com.mova.sceneai.core.ImageCompressor
import com.mova.sceneai.core.LoadState
import com.mova.sceneai.core.PickedMedia
import com.mova.sceneai.core.movaViewModel
import com.mova.sceneai.core.rememberPhotoCapture
import com.mova.sceneai.core.toUiError
import com.mova.sceneai.data.model.ActionCardDto
import com.mova.sceneai.data.model.ActionTaskDto
import com.mova.sceneai.data.model.ReadingDto
import com.mova.sceneai.ui.components.BulletList
import com.mova.sceneai.ui.components.CopyIconButton
import com.mova.sceneai.ui.components.ErrorCard
import com.mova.sceneai.ui.components.InfoBanner
import com.mova.sceneai.ui.components.LoadingStages
import com.mova.sceneai.ui.components.MovaCard
import com.mova.sceneai.ui.components.MovaScreen
import com.mova.sceneai.ui.components.OutlinedActionButton
import com.mova.sceneai.ui.components.ResultHeader
import com.mova.sceneai.ui.components.SolidActionButton
import com.mova.sceneai.ui.components.Tag
import com.mova.sceneai.ui.theme.LocalMovaSemanticColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 阅读场景。
 *
 * 三种输入（文本 / 截图 / PDF）走**同一套输出契约**，因此结果卡只写一份。
 * 将来接网页剪藏、OCR 等新入口时，UI 完全不用改 —— 这是契约一致性的直接收益。
 */
@Composable
fun ReadingScreen(onBack: () -> Unit, onOpenSettings: () -> Unit) {
    val vm = movaViewModel { ReadingViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    var tab by remember { mutableIntStateOf(0) }

    val actionCapture = rememberPhotoCapture(onImage = { vm.actionImage(it) })
    val pendingImage by MovaApp.instance.container.actionShareInbox.pending.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(pendingImage) {
        if (pendingImage != null) {
            tab = 1
            vm.consumeSharedImage()
        }
    }
    val capture = rememberPhotoCapture(
        onImage = { vm.readImage(it) },
        onPdf = { vm.readPdf(it) },
    )

    MovaScreen(title = "读懂一页", subtitle = "先找到重要的部分", onBack = onBack) {
        com.mova.sceneai.ui.components.StoryIntro("02", "内容太长？\n先看重点", "支持文字、截图与 PDF\n重要信息记得对照原文")

        MovaCard(contentPadding = 18) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                com.mova.sceneai.ui.components.TaskStep("1", "选择内容来源")

                com.mova.sceneai.ui.components.MovaSegmentedTabs(
                    listOf("粘贴文本", "截图识别", "PDF 文档"), tab, { tab = it })

                when (tab) {
                    0 -> Column {
                        OutlinedTextField(
                            value = state.text,
                            onValueChange = vm::onTextChange,
                            label = { Text("粘贴要总结的内容") },
                            placeholder = { Text("把文章、通知、会议纪要粘贴进来…") },
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(17.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 160.dp),
                            supportingText = {
                                Text("${state.text.length} 字", style = MaterialTheme.typography.bodySmall)
                            },
                        )
                        Spacer(Modifier.height(10.dp))
                        SolidActionButton(
                            text = "生成总结",
                            modifier = Modifier.fillMaxWidth(),
                            enabled = state.text.isNotBlank() && !state.load.isLoading,
                        ) { vm.summarizeText() }
                        Spacer(Modifier.height(8.dp))
                        OutlinedActionButton(
                            text = "从通知提取待办",
                            modifier = Modifier.fillMaxWidth(),
                            enabled = state.text.isNotBlank() && !state.actionLoad.isLoading,
                        ) { vm.actionText() }
                        Spacer(Modifier.height(8.dp))
                        OutlinedActionButton(
                            text = "试试示例",
                            modifier = Modifier.fillMaxWidth(),
                            icon = Icons.Filled.AutoAwesome,
                        ) { vm.useTextSample() }
                    }

                    1 -> MovaCard {
                        Column {
                            Text("拍一张文章截图", style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "网页文章、群通知，拍下来就能读",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(14.dp))
                            SolidActionButton(
                                text = "拍照识别",
                                icon = Icons.Filled.PhotoCamera,
                                modifier = Modifier.fillMaxWidth(),
                            ) { capture.capturePhoto() }
                            Spacer(Modifier.height(8.dp))
                            OutlinedActionButton(
                                text = "从相册选择",
                                icon = Icons.Filled.PhotoLibrary,
                                modifier = Modifier.fillMaxWidth(),
                            ) { capture.pickFromGallery() }
                            state.sharedImage?.let {
                                Spacer(Modifier.height(8.dp))
                                InfoBanner("已收到分享截图；确认后才会发送给云端识别")
                                SolidActionButton(text = "提取分享截图的待办",
                                    modifier = Modifier.fillMaxWidth()) { vm.actionImage(it) }
                            }
                            Spacer(Modifier.height(8.dp))
                            OutlinedActionButton(text = "从通知截图提取待办",
                                modifier = Modifier.fillMaxWidth()) { actionCapture.pickFromGallery() }
                        }
                    }

                    else -> MovaCard {
                        Column {
                            Text("选择一个 PDF 文件", style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "当前只读前几页，适合通知、摘要和说明书",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(14.dp))
                            SolidActionButton(
                                text = "选择 PDF",
                                icon = Icons.Filled.PictureAsPdf,
                                modifier = Modifier.fillMaxWidth(),
                            ) { capture.pickPdf() }
                        }
                    }
                }
            }
        }

        if (state.load.isLoading) {
            LoadingStages(
                stages = listOf("正在读取内容…", "正在提炼要点…"),
                activeIndex = if (state.load is LoadState.Loading) 1 else 0,
            )
        }

        (state.load as? LoadState.Failed)?.let { failed ->
            ErrorCard(error = failed.error, onRetry = vm::retry, onOpenSettings = onOpenSettings)
        }

        (state.load as? LoadState.Success)?.let { ReadingResultCard(it.data) }

        if (state.actionLoad.isLoading) LoadingStages(
            stages = listOf("正在读取通知…", "正在核对待办与原文…"), activeIndex = 1)
        (state.actionLoad as? LoadState.Failed)?.let { failed ->
            ErrorCard(error = failed.error, onRetry = vm::retryAction, onOpenSettings = onOpenSettings)
        }
        (state.actionLoad as? LoadState.Success)?.let { ActionCardResult(it.data) }
    }
}

@Composable
private fun ReadingResultCard(dto: ReadingDto) {
    val semantic = LocalMovaSemanticColors.current
    MovaCard {
        Column {
            ResultHeader(
                title = "阅读总结",
                subtitle = buildString {
                    dto.pageCount?.let { append("前 $it 页 · ") }
                    append(com.mova.sceneai.core.Fmt.duration(dto.meta?.latencyMs))
                },
                executor = com.mova.sceneai.data.model.ExecutorKind.from(dto.meta?.executor),
                copyText = buildString {
                    append(dto.summary).append("\n\n要点：\n")
                    dto.keyPoints.forEach { append("· $it\n") }
                    if (!dto.qaSuggestion.isNullOrBlank()) append("\n可以追问：${dto.qaSuggestion}")
                },
            )

            Spacer(Modifier.height(14.dp))
            Text("摘要", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            Text(dto.summary, style = MaterialTheme.typography.bodyMedium)

            if (dto.keyPoints.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("要点", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.weight(1f))
                    dto.difficulty?.let { difficulty ->
                        val (container, content) = when (difficulty) {
                            "简单" -> semantic.successContainer to semantic.onSuccessContainer
                            "偏难" -> MaterialTheme.colorScheme.errorContainer to
                                MaterialTheme.colorScheme.onErrorContainer
                            else -> semantic.warningContainer to semantic.onWarningContainer
                        }
                        Tag(difficulty, container, content)
                    }
                }
                Spacer(Modifier.height(6.dp))
                BulletList(dto.keyPoints)
            }

            if (dto.qaSuggestion.isNullOrBlank().not()) {
                Spacer(Modifier.height(14.dp))
                InfoBanner("可以继续追问：${dto.qaSuggestion}")
            }
        }
    }
}

@Composable
private fun ActionCardResult(card: ActionCardDto) {
    val context = LocalContext.current
    MovaCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(card.title, style = MaterialTheme.typography.titleMedium)
            Text("请对照原文确认行动和日期，保存日历前仍需你确认。",
                style = MaterialTheme.typography.bodySmall)
            if (card.tasks.isEmpty()) InfoBanner("没找到可用原文核对的待办，请检查通知内容。")
            card.tasks.forEach { task ->
                MovaCard(containerColor = MaterialTheme.colorScheme.secondaryContainer) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(task.title, style = MaterialTheme.typography.titleSmall)
                        Text("原文：${task.evidence}", style = MaterialTheme.typography.bodySmall)
                        Text("时间：${task.dueAt ?: "原文未给出完整时间"}",
                            style = MaterialTheme.typography.bodySmall)
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text("复制待办", style = MaterialTheme.typography.bodySmall)
                            CopyIconButton(task.title)
                            Spacer(Modifier.weight(1f))
                            if (actionMillis(task) != null) {
                                OutlinedActionButton(text = "加入日历") {
                                    val millis = actionMillis(task) ?: return@OutlinedActionButton
                                    val intent = Intent(Intent.ACTION_INSERT).apply {
                                        data = CalendarContract.Events.CONTENT_URI
                                        putExtra(CalendarContract.Events.TITLE, task.title)
                                        putExtra(CalendarContract.Events.DESCRIPTION, task.evidence)
                                        putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, millis)
                                        putExtra(CalendarContract.EXTRA_EVENT_END_TIME, millis + 30 * 60_000L)
                                    }
                                    runCatching { context.startActivity(intent) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun actionMillis(task: ActionTaskDto): Long? =
    task.dueAt?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }

// ============================================================
// ViewModel
// ============================================================

data class ReadingUiState(
    val text: String = "",
    val load: LoadState<ReadingDto> = LoadState.Idle,
    val actionLoad: LoadState<ActionCardDto> = LoadState.Idle,
    val sharedImage: PickedMedia? = null,
)

class ReadingViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(ReadingUiState())
    val state: StateFlow<ReadingUiState> = _state.asStateFlow()

    private var lastAction: (suspend () -> ReadingDto)? = null
    private var lastCardAction: (suspend () -> ActionCardDto)? = null

    fun onTextChange(value: String) {
        _state.value = _state.value.copy(text = value)
    }

    fun summarizeText() {
        val content = _state.value.text
        if (content.isBlank()) return
        run(lastAction = { container.repository.readingText(content) })
    }

    fun readImage(media: PickedMedia) {
        run(lastAction = { container.repository.readingImage(media.bytes, media.filename) })
    }

    fun readPdf(media: PickedMedia) {
        run(lastAction = { container.repository.readingPdf(media.bytes, media.filename) })
    }

    fun consumeSharedImage() {
        val uri = container.actionShareInbox.take() ?: return
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val bytes = MovaApp.instance.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: error("无法读取分享的截图")
                    PickedMedia(ImageCompressor.compress(bytes), "shared-notice.jpg")
                }
            }.onSuccess { _state.value = _state.value.copy(sharedImage = it) }
                .onFailure { _state.value = _state.value.copy(actionLoad = LoadState.Failed(it.toUiError())) }
        }
    }

    fun actionText() {
        val content = _state.value.text
        if (content.isNotBlank()) runCard { container.repository.readingActionText(content) }
    }

    fun actionImage(media: PickedMedia) {
        runCard { container.repository.readingActionImage(media.bytes, media.filename) }
    }

    fun retryAction() { lastCardAction?.let(::runCard) }

    private fun runCard(action: suspend () -> ActionCardDto) {
        lastCardAction = action
        viewModelScope.launch {
            _state.value = _state.value.copy(actionLoad = LoadState.Loading)
            runCatching { action() }
                .onSuccess { _state.value = _state.value.copy(actionLoad = LoadState.Success(it)) }
                .onFailure { _state.value = _state.value.copy(actionLoad = LoadState.Failed(it.toUiError())) }
        }
    }

    fun useTextSample() {
        _state.value = _state.value.copy(text = SAMPLE_TEXT)
    }

    fun retry() {
        val action = lastAction ?: return
        run(action)
    }

    private fun run(lastAction: suspend () -> ReadingDto) {
        this.lastAction = lastAction
        viewModelScope.launch {
            _state.value = _state.value.copy(load = LoadState.Loading)
            runCatching { lastAction() }
                .onSuccess { _state.value = _state.value.copy(load = LoadState.Success(it)) }
                .onFailure { _state.value = _state.value.copy(load = LoadState.Failed(it.toUiError())) }
        }
    }
}

private val SAMPLE_TEXT = """
    关于端侧部署的一点体会

    把大模型放进手机，难点从来不是"把模型变小"。真正的约束来自三处：内存、延迟与热。
    内存决定了模型与 KV cache 能否同时在驻；延迟决定了首 token 时间是否可接受；
    而热则会在一段连续推理之后，把看似漂亮的吞吐数字打回原形。

    因此端侧模型不该试图复刻云端大模型的全部能力。更务实的做法是先划定一条能力边界：
    把高频、短输出、可校验的判断交给端侧，把长生成与复杂推理留给云端，再用一套统一的
    路由规则在两者之间切换。这条边界的划线方式，往往比模型本身更能决定体验好坏。
""".trimIndent()
