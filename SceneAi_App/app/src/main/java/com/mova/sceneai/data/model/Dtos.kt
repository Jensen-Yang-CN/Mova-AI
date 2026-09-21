package com.mova.sceneai.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ============================================================
// 与云端服务的 JSON 契约。
// 所有字段都带默认值 —— 服务端加字段、少字段都不会让 App 崩溃，
// 这是"契约演进时不炸客户端"的底线做法。
// ============================================================

/**
 * 每次请求的执行元信息。
 * 它是技术面板（Tech Panel）唯一的数据来源：路由决策、耗时拆解、模型来源。
 *
 * 端侧模型接入后，[executor] 会是 "edge"，[routeReason] 会记录升级原因，
 * UI 不需要改动就能展示端云协同的真实数据。
 */
@Serializable
data class MetaDto(
    /** "cloud" | "edge" */
    val executor: String = "cloud",
    val model: String? = null,
    @SerialName("latency_ms") val latencyMs: Long = 0,
    /** 路由原因，人类可读，例如"端侧未部署，直连云端" */
    @SerialName("route_reason") val routeReason: String? = null,
    /** 阶段耗时，例如 {"recognize": 420, "generate": 820} */
    val stages: Map<String, Long> = emptyMap(),
    /** 模型自报置信度（未校准时仅作参考） */
    val confidence: Double? = null,
)

@Serializable
data class FoodDto(
    val scene: String = "food",
    val ingredients: List<String> = emptyList(),
    val dish: String = "",
    val steps: List<String> = emptyList(),
    val tips: String = "",
    val meta: MetaDto? = null,
)

@Serializable
data class ReadingDto(
    val scene: String = "reading",
    val summary: String = "",
    @SerialName("key_points") val keyPoints: List<String> = emptyList(),
    /** 简单 / 中等 / 偏难 */
    val difficulty: String? = null,
    @SerialName("qa_suggestion") val qaSuggestion: String? = null,
    @SerialName("page_count") val pageCount: Int? = null,
    val meta: MetaDto? = null,
)

@Serializable
data class ChatDto(
    val scene: String = "chat",
    val reply: String = "",
    val style: String = "",
    val alternatives: List<String> = emptyList(),
    val explain: String = "",
    val meta: MetaDto? = null,
)

/** 端侧模型状态。端侧未部署时 [deployed] = false，界面据此如实展示。 */
@Serializable
data class EdgeStatusDto(
    val deployed: Boolean = false,
    val name: String? = null,
    @SerialName("size_mb") val sizeMb: Double? = null,
    val quantization: String? = null,
)

@Serializable
data class HealthDto(
    val status: String = "unknown",
    val version: String = "",
    val provider: String = "",
    val models: Map<String, String> = emptyMap(),
    val edge: EdgeStatusDto? = null,
)

/** 首页能力网格的数据来源。服务端可动态增减场景，App 不需要发版。 */
@Serializable
data class CapabilityDto(
    val id: String = "",
    val name: String = "",
    val desc: String = "",
    val emoji: String = "✨",
    val available: Boolean = true,
    /** 是否已由端侧模型承担 */
    val edgeReady: Boolean = false,
    /** 未上线能力的提示文案，例如"规划中" */
    @SerialName("unavailable_hint") val unavailableHint: String? = null,
)

@Serializable
data class CapabilitiesDto(
    val scenes: List<CapabilityDto> = emptyList(),
)

// ---------- 请求体 ----------

@Serializable
data class ReadingTextRequest(val content: String)

@Serializable
data class ChatReplyRequest(val context: String, val style: String = "自然")

@Serializable
data class ChatRewriteRequest(val text: String, val style: String = "礼貌")

@Serializable
data class ChatMessage(val role: String, val content: String)

@Serializable
data class ChatContextRequest(
    val messages: List<ChatMessage>,
    val goal: String = "",
    val style: String = "自然",
)
