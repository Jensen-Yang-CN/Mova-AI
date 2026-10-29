package com.mova.sceneai.data.city

import android.content.Context
import com.mova.sceneai.core.SettingsRepository
import com.mova.sceneai.data.remote.MovaApi
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
    private val persistenceMutex = Mutex()
    @Volatile private var sessionId = 0L
    private var mockClock = 0L

    fun begin(simulated: Boolean, destination: String = "") {
        simulationJob?.cancel()
        sessionId++
        detector = CityChangeDetector(destination)
        mockClock = 0
        _state.update { current -> CityTripState(active = true, simulated = simulated,
            status = if (simulated) "模拟行程：坐标与时间经过压缩" else "等待有效定位，首次识别建立出发基线",
            arrivals = current.arrivals, refreshingGuides = current.refreshingGuides,
            guideErrors = current.guideErrors) }
    }

    fun end() {
        simulationJob?.cancel()
        sessionId++
        _state.update { it.copy(active = false, replaying = false, resolving = false, status = "本次出行已结束") }
    }

    fun report(message: String) {
        _state.update { it.copy(status = "本次操作未完成，请查看提示", error = message) }
    }

    /** 公共城市点位。它们是离散测试输入，既非用户当前位置，也非真实列车轨迹。 */
    fun simulateRoute(destination: String = "") {
        begin(true, destination)
        _state.update { it.copy(replaying = true) }
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
            _state.update { it.copy(active = false, replaying = false,
                status = "模拟回放完成；城市识别来自高德，位置输入为模拟") }
        }
    }

    /** 手动输入时每次按一分钟推进逻辑时间，同样要求连续两次新城市确认。 */
    fun manualPoint(longitude: Double, latitude: Double, coordsys: String, destination: String = "") {
        // 一次手动提交只对应一次请求；禁止重复点击造成串行积压，误以为一直在加载。
        if (state.value.replaying || state.value.resolving) return
        if (!state.value.active || !state.value.simulated) begin(true, destination)
        val logicalTime = mockClock
        mockClock += 60_000
        _state.update { it.copy(resolving = true, status = "正在识别这个点位…", error = null) }
        scope.launch { submit(CityFixRequest(longitude, latitude, coordsys), logicalTime) }
    }

    suspend fun submit(fix: CityFixRequest, observedMs: Long) {
        val currentSession = sessionId
        mutex.withLock {
            if (!state.value.active || currentSession != sessionId) return
            _state.update { it.copy(resolving = true, status = "正在识别城市（最多 25 秒）…", error = null) }
            try {
                val config = settings.current()
                val city = withTimeout(25_000) { api.resolveCity(config.endpoint("city/resolve"), fix) }
                if (currentSession != sessionId) return
                val result = detector.accept(city.code, observedMs)
                _state.update { it.copy(currentCity = city.name, status = result.status, error = null,
                    events = (listOf("${city.name} · ${result.status}") + it.events).take(20)) }
                if (!result.entered) return
                _state.update { it.copy(status = "已确认新城市，正在读取介绍…") }
                // 城市确认不依赖介绍接口成功；介绍失败也保留到达卡片，不丢掉事件。
                val guide = try {
                    withTimeout(45_000) { api.cityGuide(config.endpoint("city/guide"), city.code, city.name) }
                } catch (e: Exception) {
                    if (e is CancellationException && e !is TimeoutCancellationException) throw e
                    CityGuide(city.code, city.name, notice = "介绍暂未加载，请在有网络时再次查看")
                }
                if (currentSession != sessionId) return
                val arrival = CityArrival(guide, state.value.simulated, System.currentTimeMillis())
                _state.update { it.copy(arrivals = (listOf(arrival) + it.arrivals).take(20)) }
                persistArrivals()
                val minute = LocalTime.now().let { it.hour * 60 + it.minute }
                val silent = config.dndEnabled && when {
                    config.dndStartMinutes < config.dndEndMinutes -> minute in config.dndStartMinutes until config.dndEndMinutes
                    config.dndStartMinutes > config.dndEndMinutes -> minute >= config.dndStartMinutes || minute < config.dndEndMinutes
                    else -> true
                }
                val posted = notifications.arrival(arrival, silent)
                _state.update { it.copy(
                    status = if (posted) "欢迎通知已发出，点击可查看城市卡片" else "城市卡片已保存；系统通知未授权或已关闭") }
            } catch (e: Exception) {
                if (e is CancellationException && e !is TimeoutCancellationException) throw e
                if (currentSession == sessionId) report(if (e is HttpException) {
                    runCatching { JSONObject(e.response()?.errorBody()?.string() ?: "{}").optString("detail") }
                        .getOrDefault("").ifBlank { "城市服务返回 ${e.code()}，请检查连接与配置" }
                } else "暂时无法识别城市，请检查服务连接后重试")
            } finally {
                // 失败、超时、候选城市及成功通知都必须结束本次加载；行程仍可继续。
                if (currentSession == sessionId) _state.update { it.copy(resolving = false) }
            }
        }
    }

    /** 旧版本的空卡片自动补加载；无需重新模拟跨城，也不重复发送欢迎通知。 */
    fun refreshIncompleteGuides() {
        // v4 的 missing 是已确认资料缺口，不能每次打开页面都重复请求。
        state.value.arrivals.filter { it.guide.version < 4 ||
            (it.guide.knowledgeStatus != "missing" && (it.guide.foods.isEmpty() || it.guide.sights.isEmpty())) }
            .distinctBy { it.guide.code }.forEach { refreshGuide(it.guide.code, it.guide.name) }
    }

    @Synchronized
    fun refreshGuide(code: String, name: String) {
        if (code in state.value.refreshingGuides) return
        _state.update { it.copy(refreshingGuides = it.refreshingGuides + code,
            guideErrors = it.guideErrors - code) }
        scope.launch {
            try {
                val config = settings.current()
                val guide = withTimeout(45_000) { api.cityGuide(config.endpoint("city/guide"), code, name) }
                _state.update { current -> current.copy(arrivals = current.arrivals.map { entry ->
                    if (entry.guide.code == code) entry.copy(guide = guide) else entry
                }) }
                persistArrivals()
            } catch (e: Exception) {
                if (e is CancellationException && e !is TimeoutCancellationException) throw e
                _state.update { it.copy(guideErrors = it.guideErrors + (code to "城市推荐暂时加载失败，请检查连接后重试")) }
            } finally {
                _state.update { it.copy(refreshingGuides = it.refreshingGuides - code) }
            }
        }
    }

    /** 单轮城市资料问答；仅发送城市编码与本次问题，不上传位置和行程记录。 */
    @Synchronized
    fun askCity(code: String, question: String) {
        val clean = question.trim().take(300)
        if (clean.length < 2 || code in state.value.askingCities) return
        _state.update { it.copy(askingCities = it.askingCities + code,
            answers = it.answers - code, answerErrors = it.answerErrors - code) }
        scope.launch {
            try {
                val config = settings.current()
                val answer = withTimeout(20_000) {
                    api.askCity(config.endpoint("city/ask"), CityQuestionRequest(code, clean))
                }.copy(question = clean)
                _state.update { it.copy(answers = it.answers + (code to answer)) }
            } catch (e: Exception) {
                if (e is CancellationException && e !is TimeoutCancellationException) throw e
                _state.update { it.copy(answerErrors = it.answerErrors + (code to "资料查询暂时未完成，请检查连接后重试")) }
            } finally {
                _state.update { it.copy(askingCities = it.askingCities - code) }
            }
        }
    }

    /** 多座城市可同时刷新；串行保存最新快照，避免旧写入覆盖已更新的城市卡片。 */
    private suspend fun persistArrivals() = persistenceMutex.withLock {
        preferences.edit().putString("arrivals", json.encodeToString(state.value.arrivals)).commit()
    }
}
