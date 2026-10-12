package com.mova.sceneai.data.city

import android.content.Context
import com.mova.sceneai.core.SettingsRepository
import com.mova.sceneai.data.remote.MovaApi
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.json.JSONObject
import retrofit2.HttpException
import java.time.LocalTime

/**
 * 真实位置与模拟位置汇合于 submit。模拟只注入本 App，不修改手机系统定位。
 * 当前行程状态在内存；最近 20 张城市卡片持久化，不保存完整轨迹或坐标。
 */
class CityTripManager(context: Context, private val settings: SettingsRepository,
                      private val api: MovaApi, private val json: Json) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val notifications = CityNotifications(context)
    private val preferences = context.getSharedPreferences("city_cards", Context.MODE_PRIVATE)
    private val saved = runCatching { json.decodeFromString<List<CityArrival>>(
        preferences.getString("arrivals", "[]") ?: "[]") }.getOrDefault(emptyList())
    private val _state = MutableStateFlow(CityTripState(arrivals = saved))
    val state = _state.asStateFlow()
    private var detector = CityChangeDetector()
    private var simulationJob: Job? = null
    private val mutex = Mutex()
    @Volatile private var sessionId = 0L
    private var mockClock = 0L

    fun begin(simulated: Boolean, destination: String = "") {
        simulationJob?.cancel()
        sessionId++
        detector = CityChangeDetector(destination)
        mockClock = 0
        _state.value = CityTripState(active = true, simulated = simulated,
            status = if (simulated) "模拟行程：坐标与时间经过压缩" else "等待有效定位，首次识别建立出发基线",
            arrivals = _state.value.arrivals)
    }

    fun end() {
        simulationJob?.cancel()
        sessionId++
        _state.value = _state.value.copy(active = false, replaying = false, status = "本次出行已结束")
    }

    fun report(message: String) { _state.value = _state.value.copy(error = message) }

    /** 公共城市点位。它们是离散测试输入，既非用户当前位置，也非真实列车轨迹。 */
    fun simulateRoute(destination: String = "") {
        begin(true, destination)
        _state.value = _state.value.copy(replaying = true)
        simulationJob = scope.launch {
            val points = listOf(
                Triple(114.30, 30.60, 0L), Triple(114.31, 30.61, 60_000L),
                Triple(113.13, 29.37, 720_000L), Triple(113.14, 29.38, 780_000L),
                Triple(113.13, 29.37, 840_000L),
            )
            for ((longitude, latitude, logicalTime) in points) {
                submit(CityFixRequest(longitude, latitude, "gcj02"), logicalTime)
                delay(2_000)
            }
            _state.value = _state.value.copy(active = false, replaying = false,
                status = "模拟回放完成；城市识别来自高德，位置输入为模拟")
        }
    }

    /** 手动输入时每次按一分钟推进逻辑时间，同样要求连续两次新城市确认。 */
    fun manualPoint(longitude: Double, latitude: Double, coordsys: String, destination: String = "") {
        if (state.value.replaying) return
        if (!state.value.active || !state.value.simulated) begin(true, destination)
        val logicalTime = mockClock
        mockClock += 60_000
        scope.launch { submit(CityFixRequest(longitude, latitude, coordsys), logicalTime) }
    }

    suspend fun submit(fix: CityFixRequest, observedMs: Long) {
        val currentSession = sessionId
        mutex.withLock {
            if (!state.value.active || currentSession != sessionId) return
            try {
                val config = settings.current()
                val city = withTimeout(25_000) { api.resolveCity(config.endpoint("city/resolve"), fix) }
                if (currentSession != sessionId) return
                val result = detector.accept(city.code, observedMs)
                _state.value = _state.value.copy(currentCity = city.name, status = result.status, error = null,
                    events = (listOf("${city.name} · ${result.status}") + _state.value.events).take(20))
                if (!result.entered) return
                // 城市确认不依赖介绍接口成功；介绍失败也保留到达卡片，不丢掉事件。
                val guide = try {
                    withTimeout(15_000) { api.cityGuide(config.endpoint("city/guide"), city.code, city.name) }
                } catch (e: Exception) {
                    if (e is CancellationException && e !is TimeoutCancellationException) throw e
                    CityGuide(city.code, city.name, notice = "介绍暂未加载，请在有网络时再次查看")
                }
                if (currentSession != sessionId) return
                val arrival = CityArrival(guide, state.value.simulated, System.currentTimeMillis())
                val cards = (listOf(arrival) + state.value.arrivals).take(20)
                preferences.edit().putString("arrivals", json.encodeToString(cards)).apply()
                val minute = LocalTime.now().let { it.hour * 60 + it.minute }
                val silent = config.dndEnabled && when {
                    config.dndStartMinutes < config.dndEndMinutes -> minute in config.dndStartMinutes until config.dndEndMinutes
                    config.dndStartMinutes > config.dndEndMinutes -> minute >= config.dndStartMinutes || minute < config.dndEndMinutes
                    else -> true
                }
                val posted = notifications.arrival(arrival, silent)
                _state.value = _state.value.copy(arrivals = cards,
                    status = if (posted) "欢迎通知已发出，点击可查看城市卡片" else "城市卡片已保存；系统通知未授权或已关闭")
            } catch (e: Exception) {
                if (e is CancellationException && e !is TimeoutCancellationException) throw e
                if (currentSession == sessionId) report(if (e is HttpException) {
                    runCatching { JSONObject(e.response()?.errorBody()?.string() ?: "{}").optString("detail") }
                        .getOrDefault("").ifBlank { "城市服务返回 ${e.code()}，请检查连接与配置" }
                } else "暂时无法识别城市，请检查服务连接后重试")
            }
        }
    }
}
