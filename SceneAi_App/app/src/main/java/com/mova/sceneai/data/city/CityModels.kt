package com.mova.sceneai.data.city

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CityFixRequest(val longitude: Double, val latitude: Double, val coordsys: String)

@Serializable
data class ResolvedCity(
    @SerialName("city_code") val code: String,
    @SerialName("city_name") val name: String,
    val province: String = "", val district: String = "", val resolver: String = "",
)

@Serializable
data class CitySource(val title: String, val url: String)

/** 同一块资料既用于城市卡片，也用于问答引用，链接由资料库提供。 */
@Serializable
data class CityKnowledgeItem(
    val id: String, val name: String, val kind: String, val text: String,
    @SerialName("source_title") val sourceTitle: String,
    @SerialName("source_url") val sourceUrl: String,
    @SerialName("verified_on") val verifiedOn: String,
)

@Serializable
data class CityQuestionRequest(@SerialName("city_code") val code: String, val question: String)

@Serializable
data class CityAnswer(
    val answer: String, val citations: List<CityKnowledgeItem> = emptyList(),
    val grounded: Boolean = false, val mode: String = "insufficient",
    @SerialName("kb_revision") val kbRevision: String = "",
    val question: String = "",
)

@Serializable
data class CityGuide(
    @SerialName("city_code") val code: String,
    @SerialName("city_name") val name: String,
    val subtitle: String = "", val foods: List<String> = emptyList(),
    val sights: List<String> = emptyList(), val sources: List<CitySource> = emptyList(),
    @SerialName("updated_on") val updatedOn: String = "",
    val curated: Boolean = false, val notice: String = "",
    val version: Int = 1,
    @SerialName("food_kind") val foodKind: String = "dishes",
    val generation: String = "",
    val knowledge: List<CityKnowledgeItem> = emptyList(),
    @SerialName("knowledge_status") val knowledgeStatus: String = "",
    @SerialName("kb_revision") val kbRevision: String = "",
)

@Serializable
data class CityArrival(val guide: CityGuide, val simulated: Boolean, val timestamp: Long)

/** 只存最近城市卡片，不保存经纬度轨迹；真实和模拟记录有明确标记。 */
data class CityTripState(
    val active: Boolean = false, val simulated: Boolean = false, val replaying: Boolean = false,
    val resolving: Boolean = false,
    val currentCity: String = "尚未识别", val status: String = "开启一次出行，认识一座新城市",
    val arrivals: List<CityArrival> = emptyList(), val events: List<String> = emptyList(),
    val error: String? = null,
    val refreshingGuides: Set<String> = emptySet(),
    val guideErrors: Map<String, String> = emptyMap(),
    val askingCities: Set<String> = emptySet(),
    val answers: Map<String, CityAnswer> = emptyMap(),
    val answerErrors: Map<String, String> = emptyMap(),
)
