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

@Serializable
data class CityGuide(
    @SerialName("city_code") val code: String,
    @SerialName("city_name") val name: String,
    val subtitle: String = "", val foods: List<String> = emptyList(),
    val sights: List<String> = emptyList(), val sources: List<CitySource> = emptyList(),
    @SerialName("updated_on") val updatedOn: String = "",
    val curated: Boolean = false, val notice: String = "",
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
)
