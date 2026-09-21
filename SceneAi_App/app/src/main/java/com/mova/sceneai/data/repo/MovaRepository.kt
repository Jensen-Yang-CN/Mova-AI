package com.mova.sceneai.data.repo

import android.content.Context
import com.mova.sceneai.core.Fmt
import com.mova.sceneai.core.MovaSettings
import com.mova.sceneai.core.SettingsRepository
import com.mova.sceneai.data.local.LocalStore
import com.mova.sceneai.data.model.CapabilitiesDto
import com.mova.sceneai.data.model.ChatContextRequest
import com.mova.sceneai.data.model.ChatDto
import com.mova.sceneai.data.model.ChatMessage
import com.mova.sceneai.data.model.ChatReplyRequest
import com.mova.sceneai.data.model.ChatRewriteRequest
import com.mova.sceneai.data.model.ExecutorKind
import com.mova.sceneai.data.model.FoodDto
import com.mova.sceneai.data.model.HealthDto
import com.mova.sceneai.data.model.HistoryEntry
import com.mova.sceneai.data.model.MetaDto
import com.mova.sceneai.data.model.ReadingDto
import com.mova.sceneai.data.model.ReadingTextRequest
import com.mova.sceneai.data.model.RequestTrace
import com.mova.sceneai.data.model.Scene
import com.mova.sceneai.data.remote.ApiFactory
import com.mova.sceneai.data.remote.MovaApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import java.net.SocketTimeoutException
import java.util.UUID

/**
 * 唯一的业务入口。
 *
 * 三件事集中在这里，View 层不再重复：
 *   ① 按用户设置施加超时（所以"超时时间"这个设置项真的有效）
 *   ② 每次调用都记录 [RequestTrace] —— 技术面板的数据全部由此而来
 *   ③ 每次成功的结果落一条历史记录
 */
class MovaRepository(
    @Suppress("unused") private val context: Context,
    private val api: MovaApi,
    private val settingsRepo: SettingsRepository,
    private val store: LocalStore,
    private val json: Json,
) {

    private val _edgeState = MutableStateFlow(EdgeRuntimeState())
    val edgeState: StateFlow<EdgeRuntimeState> = _edgeState.asStateFlow()

    private val _traces = MutableStateFlow<List<RequestTrace>>(emptyList())
    val traces: StateFlow<List<RequestTrace>> = _traces.asStateFlow()

    val history: StateFlow<List<HistoryEntry>> get() = store.entries

    suspend fun loadLocalData() = store.load()

    // ---------------------------------------------------------------
    // 连通性 / 能力清单
    // ---------------------------------------------------------------

    suspend fun health(): HealthDto {
        val s = settingsRepo.current()
        return call(s, timeoutMs = 15_000) { api.health(s.endpoint("health")) }
            .also { dto ->
                _edgeState.value = EdgeRuntimeState(
                    deployed = dto.edge?.deployed ?: false,
                    loaded = dto.edge?.deployed ?: false,
                    name = dto.edge?.name,
                    sizeMb = dto.edge?.sizeMb,
                    quantization = dto.edge?.quantization,
                )
            }
    }

    suspend fun capabilities(): CapabilitiesDto {
        val s = settingsRepo.current()
        return call(s, timeoutMs = 15_000) { api.capabilities(s.endpoint("capabilities")) }
    }

    // ---------------------------------------------------------------
    // 做饭
    // ---------------------------------------------------------------

    suspend fun analyzeFood(imageBytes: ByteArray, filename: String = "food.jpg"): FoodDto {
        val s = settingsRepo.current()
        val decision = Router.decide(
            scene = Scene.FOOD,
            edgeReady = _edgeState.value.ready,
            edgePreferred = s.preferEdge,
        )
        return tracked(
            settings = s,
            scene = Scene.FOOD,
            decision = decision,
            titleOf = { it.dish.ifBlank { "食材识别" } },
            subtitleOf = { it.ingredients.take(4).joinToString("、").ifBlank { "已识别" } },
            metaOf = { it.meta },
            payloadOf = { json.encodeToString(FoodDto.serializer(), it) },
        ) {
            api.analyzeImage(s.endpoint("analyze_image"), ApiFactory.imagePart(imageBytes, filename))
        }
    }

    // ---------------------------------------------------------------
    // 阅读
    // ---------------------------------------------------------------

    suspend fun readingText(content: String): ReadingDto = tracked(
        settings = settingsRepo.current(),
        scene = Scene.READING_TEXT,
        decision = cloudDecision(settingsRepo.current()),
        titleOf = { it.summary.take(24).ifBlank { "阅读总结" } },
        subtitleOf = { "文本 · ${content.length} 字" },
        metaOf = { it.meta },
        payloadOf = { json.encodeToString(ReadingDto.serializer(), it) },
    ) {
        val s = settingsRepo.current()
        api.readingText(s.endpoint("reading/text"), ReadingTextRequest(content))
    }

    suspend fun readingImage(imageBytes: ByteArray, filename: String = "reading.jpg"): ReadingDto = tracked(
        settings = settingsRepo.current(),
        scene = Scene.READING_IMAGE,
        decision = cloudDecision(settingsRepo.current()),
        titleOf = { it.summary.take(24).ifBlank { "图片阅读" } },
        subtitleOf = { "图片" },
        metaOf = { it.meta },
        payloadOf = { json.encodeToString(ReadingDto.serializer(), it) },
    ) {
        val s = settingsRepo.current()
        api.readingImage(s.endpoint("reading/image"), ApiFactory.imagePart(imageBytes, filename))
    }

    suspend fun readingPdf(bytes: ByteArray, filename: String = "document.pdf"): ReadingDto = tracked(
        settings = settingsRepo.current(),
        scene = Scene.READING_PDF,
        decision = cloudDecision(settingsRepo.current()),
        titleOf = { it.summary.take(24).ifBlank { "PDF 阅读" } },
        subtitleOf = { "PDF · ${it.pageCount ?: "?"} 页" },
        metaOf = { it.meta },
        payloadOf = { json.encodeToString(ReadingDto.serializer(), it) },
    ) {
        val s = settingsRepo.current()
        api.readingPdf(s.endpoint("reading/pdf"), ApiFactory.pdfPart(bytes, filename))
    }

    // ---------------------------------------------------------------
    // 聊天
    // ---------------------------------------------------------------

    suspend fun chatReply(context: String, style: String): ChatDto = tracked(
        settings = settingsRepo.current(),
        scene = Scene.CHAT,
        decision = cloudDecision(settingsRepo.current()),
        titleOf = { it.reply.take(24).ifBlank { "聊天回复" } },
        subtitleOf = { "风格：$style" },
        metaOf = { it.meta },
        payloadOf = { json.encodeToString(ChatDto.serializer(), it) },
    ) {
        val s = settingsRepo.current()
        api.chatReply(s.endpoint("chat/reply"), ChatReplyRequest(context, style))
    }

    suspend fun chatRewrite(text: String, style: String): ChatDto = tracked(
        settings = settingsRepo.current(),
        scene = Scene.CHAT_REWRITE,
        decision = cloudDecision(settingsRepo.current()),
        titleOf = { it.reply.take(24).ifBlank { "语气改写" } },
        subtitleOf = { "改写为「$style」" },
        metaOf = { it.meta },
        payloadOf = { json.encodeToString(ChatDto.serializer(), it) },
    ) {
        val s = settingsRepo.current()
        api.chatRewrite(s.endpoint("chat/rewrite"), ChatRewriteRequest(text, style))
    }

    suspend fun chatContext(messages: List<ChatMessage>, goal: String, style: String): ChatDto = tracked(
        settings = settingsRepo.current(),
        scene = Scene.CHAT_CONTEXT,
        decision = cloudDecision(settingsRepo.current()),
        titleOf = { it.reply.take(24).ifBlank { "多轮回复" } },
        subtitleOf = { "${messages.size} 条上下文" + if (goal.isBlank()) "" else " · $goal" },
        metaOf = { it.meta },
        payloadOf = { json.encodeToString(ChatDto.serializer(), it) },
    ) {
        val s = settingsRepo.current()
        api.chatContext(s.endpoint("chat/context"), ChatContextRequest(messages, goal, style))
    }

    suspend fun clearHistory() {
        store.clear()
        _traces.value = emptyList()
    }

    fun dismissTrace(id: String) {
        _traces.value = _traces.value.filterNot { it.id == id }
    }

    // ---------------------------------------------------------------
    // 内部：统一的超时 + 追踪 + 历史
    // ---------------------------------------------------------------

    private fun cloudDecision(settings: MovaSettings): Router.Decision = Router.decide(
        scene = Scene.UNKNOWN,
        edgeReady = _edgeState.value.ready,
        edgePreferred = settings.preferEdge,
    )

    private suspend fun <T> call(
        settings: MovaSettings,
        timeoutMs: Long,
        block: suspend () -> T,
    ): T = try {
        withTimeout(timeoutMs) { block() }
    } catch (e: TimeoutCancellationException) {
        // 转成标准 IO 异常语义，让 UiError 能统一翻译成"服务响应太慢"
        throw SocketTimeoutException("请求超过 ${Fmt.duration(timeoutMs)}")
    }

    private suspend fun <T> tracked(
        settings: MovaSettings,
        scene: Scene,
        decision: Router.Decision,
        titleOf: (T) -> String,
        subtitleOf: (T) -> String,
        metaOf: (T) -> MetaDto?,
        payloadOf: (T) -> String,
        block: suspend () -> T,
    ): T {
        val started = System.currentTimeMillis()
        return try {
            val result = call(settings, settings.timeoutSeconds * 1000L, block)
            val elapsed = System.currentTimeMillis() - started
            val meta = metaOf(result)
            val trace = RequestTrace(
                id = UUID.randomUUID().toString(),
                timestamp = started,
                sceneId = scene.id,
                executor = meta?.executor ?: decision.kind.id,
                model = meta?.model,
                totalMs = elapsed,
                serverMs = meta?.latencyMs,
                stages = meta?.stages.orEmpty(),
                confidence = meta?.confidence,
                routeReason = meta?.routeReason ?: decision.reason,
                success = true,
            )
            pushTrace(trace)
            store.add(
                HistoryEntry(
                    id = trace.id,
                    timestamp = started,
                    sceneId = scene.id,
                    title = titleOf(result),
                    subtitle = subtitleOf(result),
                    trace = trace,
                    payloadJson = payloadOf(result),
                )
            )
            result
        } catch (e: Throwable) {
            pushTrace(
                RequestTrace(
                    id = UUID.randomUUID().toString(),
                    timestamp = started,
                    sceneId = scene.id,
                    executor = decision.kind.id,
                    totalMs = System.currentTimeMillis() - started,
                    routeReason = decision.reason,
                    success = false,
                    errorTitle = e.message,
                )
            )
            throw e
        }
    }

    private fun pushTrace(trace: RequestTrace) {
        _traces.value = (listOf(trace) + _traces.value).take(MAX_TRACES)
    }

    /** 便于未来统计"云端调用占比"这类端云协同指标。 */
    suspend fun sessionStats(): SessionStats {
        val traces = _traces.value
        val s = settingsRepo.current()
        return SessionStats(
            total = traces.size,
            edgeCount = traces.count { it.executorKind == ExecutorKind.EDGE },
            cloudCount = traces.count { it.executorKind == ExecutorKind.CLOUD },
            failureCount = traces.count { !it.success },
            avgMs = traces.filter { it.success }.map { it.totalMs }.average().takeIf { !it.isNaN() },
            preferEdge = s.preferEdge,
        )
    }

    /** 直接读取当前设置（UI 需要时不必再注入 settingsRepo）。 */
    suspend fun currentSettings(): MovaSettings = settingsRepo.settings.first()

    companion object {
        const val MAX_TRACES = 120
    }
}

data class SessionStats(
    val total: Int,
    val edgeCount: Int,
    val cloudCount: Int,
    val failureCount: Int,
    val avgMs: Double?,
    val preferEdge: Boolean,
) {
    /** 云端调用占比。端云协同的核心指标之一。 */
    val cloudRatio: Double get() = if (total == 0) 0.0 else cloudCount.toDouble() / total
}
