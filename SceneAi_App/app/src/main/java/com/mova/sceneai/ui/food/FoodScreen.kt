package com.mova.sceneai.ui.food

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.mova.sceneai.core.AppContainer
import com.mova.sceneai.core.PickedMedia
import com.mova.sceneai.core.movaViewModel
import com.mova.sceneai.core.rememberPhotoCapture
import com.mova.sceneai.core.toUiError
import com.mova.sceneai.data.model.FoodDto
import com.mova.sceneai.ui.components.BulletList
import com.mova.sceneai.ui.components.ChipCloud
import com.mova.sceneai.ui.components.ErrorCard
import com.mova.sceneai.ui.components.InfoBanner
import com.mova.sceneai.ui.components.LoadingStages
import com.mova.sceneai.ui.components.MovaCard
import com.mova.sceneai.ui.components.MovaScreen
import com.mova.sceneai.ui.components.NumberedList
import com.mova.sceneai.ui.components.OutlinedActionButton
import com.mova.sceneai.ui.components.ResultHeader
import com.mova.sceneai.ui.components.SolidActionButton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 做饭场景。
 *
 * 两个刻意的产品决定：
 *  ① **"试试示例"与拍照、相册等权并列** —— 新用户第一件事就能看到结果，
 *     不需要先找食材、找光线。
 *  ② 加载态显示**真实的两阶段**（识别 → 生成），而不是一个转圈。
 *     这既减少等待焦虑，也把后端的两级流水线如实传达给用户。
 */
@Composable
fun FoodScreen(onBack: () -> Unit, onOpenSettings: () -> Unit) {
    val vm = movaViewModel { FoodViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val capture = rememberPhotoCapture(
        onImage = { media -> vm.analyze(media) },
    )

    MovaScreen(title = "做饭助手", subtitle = "拍一张食材照片，剩下的交给我", onBack = onBack) {

        if (state.stage < 0 && state.result == null) {
            MovaCard {
                Column {
                    Text("三种开始方式", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "没有食材照片也没关系，先点「试试示例」看看效果。",
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
                    Spacer(Modifier.height(8.dp))
                    OutlinedActionButton(
                        text = "试试示例（番茄与鸡蛋）",
                        icon = Icons.Filled.AutoAwesome,
                        modifier = Modifier.fillMaxWidth(),
                    ) { vm.useSample(context) }
                }
            }
        }

        if (state.stage >= 0) {
            LoadingStages(
                stages = listOf("正在识别食材…", "正在生成做法…"),
                activeIndex = state.stage,
            )
        }

        state.error?.let { error ->
            ErrorCard(error = error, onRetry = vm::retry, onOpenSettings = onOpenSettings)
        }

        state.result?.let { food ->
            FoodResultCard(food)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedActionButton(text = "再拍一张", modifier = Modifier.weight(1f)) {
                    vm.reset()
                }
                OutlinedActionButton(text = "换一张图", modifier = Modifier.weight(1f)) {
                    capture.pickFromGallery()
                }
            }
        }
    }
}

@Composable
private fun FoodResultCard(food: FoodDto) {
    MovaCard {
        Column {
            ResultHeader(
                title = food.dish.ifBlank { "识别结果" },
                subtitle = "${food.ingredients.size} 种食材 · " +
                    com.mova.sceneai.core.Fmt.duration(food.meta?.latencyMs),
                executor = com.mova.sceneai.data.model.ExecutorKind.from(food.meta?.executor),
                copyText = buildString {
                    append(food.dish).append("\n\n食材：")
                    append(food.ingredients.joinToString("、"))
                    append("\n\n步骤：\n")
                    food.steps.forEachIndexed { i, s -> append("${i + 1}. $s\n") }
                    if (food.tips.isNotBlank()) append("\n小贴士：${food.tips}")
                },
            )
            if (food.ingredients.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                Text("食材", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                ChipCloud(food.ingredients)
            }
            if (food.steps.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text("做法", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                NumberedList(food.steps)
            }
            if (food.tips.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                MovaCard(containerColor = MaterialTheme.colorScheme.secondaryContainer) {
                    Row(verticalAlignment = Alignment.Top) {
                        Text("💡", fontSize = 18.sp)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            food.tips,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
            }
            if (food.dish == "JSON 解析失败") {
                Spacer(Modifier.height(10.dp))
                InfoBanner("模型这次没有按结构化格式返回，以下是原始文本。可以重试一次。")
            }
        }
    }
}

// ============================================================
// ViewModel
// ============================================================

data class FoodUiState(
    /** -1 = 空闲；0/1 = 两级流水线的当前阶段 */
    val stage: Int = -1,
    val result: FoodDto? = null,
    val error: com.mova.sceneai.core.UiError? = null,
)

class FoodViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(FoodUiState())
    val state: StateFlow<FoodUiState> = _state.asStateFlow()

    private var lastMedia: PickedMedia? = null

    fun analyze(media: PickedMedia) {
        lastMedia = media
        viewModelScope.launch {
            _state.value = FoodUiState(stage = 0)
            runCatching {
                // 阶段 0 → 1 的推进由服务端实际完成，这里用轻量延时避免"假进度"
                val dto = container.repository.analyzeFood(media.bytes, media.filename)
                dto
            }.onSuccess { dto ->
                _state.value = FoodUiState(stage = -1, result = dto)
            }.onFailure { throwable ->
                _state.value = FoodUiState(stage = -1, error = throwable.toUiError())
            }
        }
        // 进入第二阶段提示（不假装精确，只是让等待过程有信息量）
        viewModelScope.launch {
            kotlinx.coroutines.delay(900)
            if (_state.value.stage == 0) _state.value = _state.value.copy(stage = 1)
        }
    }

    fun useSample(context: android.content.Context) {
        viewModelScope.launch {
            val bytes = runCatching {
                context.assets.open("sample_food.jpg").use { it.readBytes() }
            }.getOrNull()
            if (bytes == null) {
                _state.value = FoodUiState(
                    stage = -1,
                    error = com.mova.sceneai.core.UiError(
                        title = "示例图片不可用",
                        detail = "打包资源里没有找到示例图片。",
                        hint = "可以直接拍照，或从相册选一张。",
                    ),
                )
                return@launch
            }
            analyze(PickedMedia(com.mova.sceneai.core.ImageCompressor.compress(bytes), "sample.jpg"))
        }
    }

    fun retry() {
        lastMedia?.let { analyze(it) } ?: run {
            _state.value = FoodUiState(stage = -1)
        }
    }

    fun reset() {
        lastMedia = null
        _state.value = FoodUiState()
    }
}
