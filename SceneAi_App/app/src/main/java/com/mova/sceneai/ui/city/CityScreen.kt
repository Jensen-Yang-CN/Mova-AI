package com.mova.sceneai.ui.city

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mova.sceneai.MovaApp
import com.mova.sceneai.data.city.CityLocationService
import com.mova.sceneai.data.city.CityArrival
import com.mova.sceneai.ui.components.*
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * 同一页明确区分真实位置与 App 内模拟；模拟无需开发者选项，也不修改系统定位。
 * Key 不进入客户端；位置解析交给用户配置的 FastAPI 服务转发高德。
 */
@Composable
fun CityScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val trip = MovaApp.instance.container.cityTrip
    val state by trip.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { trip.refreshIncompleteGuides() }
    var reminder by rememberSaveable { mutableStateOf("沿途城市") }
    var longitude by rememberSaveable { mutableStateOf("114.30") }
    var latitude by rememberSaveable { mutableStateOf("30.60") }
    var coordsys by rememberSaveable { mutableStateOf("高德 GCJ-02") }
    var experience by rememberSaveable { mutableStateOf("出行提醒") }
    var manualOpen by rememberSaveable { mutableStateOf(false) }
    var eventsOpen by rememberSaveable { mutableStateOf(false) }
    var pendingStart by remember { mutableStateOf("") }
    val destination = when (reminder) { "仅岳阳" -> "430600"; "仅武汉" -> "420100"; else -> "" }

    fun executeStart(mode: String) {
        context.stopService(Intent(context, CityLocationService::class.java))
        if (mode == "mock") trip.simulateRoute(destination)
        else try {
            ContextCompat.startForegroundService(context,
                Intent(context, CityLocationService::class.java).putExtra("destination", destination))
        } catch (_: Exception) { trip.report("无法启动出行服务，请在页面可见时授权定位后重试") }
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val mode = pendingStart
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (mode == "mock" || granted) executeStart(mode)
        else trip.report("未授权定位；仍可使用模拟行程或手动经纬度")
        pendingStart = ""
    }
    fun start(mode: String) {
        pendingStart = mode
        val permissions = buildList {
            if (mode == "real") { add(Manifest.permission.ACCESS_COARSE_LOCATION); add(Manifest.permission.ACCESS_FINE_LOCATION) }
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
        if (permissions.isEmpty()) { executeStart(mode); pendingStart = "" }
        else permissionLauncher.launch(permissions.toTypedArray())
    }

    MovaScreen("城市初见", subtitle = "到一座城，看看有什么", onBack = onBack) {
        StoryIntro("04", "到了新城市\n先从这里逛起", "开启出行，换城后收到欢迎卡片\n美食和景点可以跳转地图")
        MovaCard(contentPadding = 18) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TaskStep("1", "这次怎样提醒")
        MovaChipRow(listOf("沿途城市", "仅岳阳", "仅武汉"), reminder, { reminder = it })
        Text("首次定位记作出发地；换城确认两次，同城只提醒一次", style = MaterialTheme.typography.bodySmall)
        }
        }

        MovaCard(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .55f)) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(when {
                    state.resolving -> "正在处理点位"
                    state.replaying -> "模拟行程回放中"
                    state.active && state.simulated -> "手动模拟 · 等待下一次提交"
                    state.active -> "真实出行运行中"
                    else -> "本次出行状态"
                },
                    style = MaterialTheme.typography.titleMedium)
                KeyValueRow("最近识别", state.currentCity)
                Text(state.status, style = MaterialTheme.typography.bodyMedium)
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (state.active) OutlinedActionButton("结束本次出行", onClick = {
                    context.stopService(Intent(context, CityLocationService::class.java)); trip.end()
                })
            }
        }

        MovaChipRow(listOf("出行提醒", "原地体验"), experience, { experience = it })
        if (experience == "原地体验") {
        SectionHeader("在这里，体验一次抵达", "不需要坐车，也不修改系统位置")
        MovaCard {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("武汉 → 岳阳", style = MaterialTheme.typography.titleLarge)
                Text("用公开点位模拟换城，经高德解析后触发欢迎通知\n点位和时间仅供演示，不代表真实列车轨迹", style = MaterialTheme.typography.bodySmall)
                SolidActionButton(if (state.replaying) "模拟回放中…" else "播放模拟行程", enabled = !state.active,
                    modifier = Modifier.fillMaxWidth(), onClick = { start("mock") })
            }
        }
        } else {

        SectionHeader("真实出行", "仅在你开启的这次出行中定位")
        MovaCard {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("运行时可从通知栏停止\n坐标会发往所配置的服务及高德，不保存完整轨迹", style = MaterialTheme.typography.bodySmall)
                SolidActionButton("开始真实出行", modifier = Modifier.fillMaxWidth(), enabled = !state.active, onClick = { start("real") })
            }
        }
        }

        TextButton(onClick = { manualOpen = !manualOpen }) {
            Text(if (manualOpen) "收起手动模拟经纬度" else "手动模拟经纬度 →")
        }
        if (manualOpen) {
        SectionHeader("手动模拟经纬度", "默认是武汉公共示例点，不是你的当前位置")
        MovaCard {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(longitude, { longitude = it }, label = { Text("经度") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(latitude, { latitude = it }, label = { Text("纬度") }, singleLine = true, modifier = Modifier.weight(1f))
                }
                MovaChipRow(listOf("高德 GCJ-02", "GPS WGS-84"), coordsys, { coordsys = it })
                OutlinedActionButton(if (state.resolving) "正在识别点位…" else "提交模拟点位",
                    enabled = !state.resolving && (!state.active || (state.simulated && !state.replaying)), onClick = {
                    val lon = longitude.toDoubleOrNull(); val lat = latitude.toDoubleOrNull()
                    if (lon == null || lat == null || !lon.isFinite() || !lat.isFinite() || lon !in -180.0..180.0 || lat !in -90.0..90.0) {
                        trip.report("请输入有效经纬度，经度在前、纬度在后")
                    } else trip.manualPoint(lon, lat, if (coordsys == "GPS WGS-84") "wgs84" else "gcj02", destination)
                })
                // 结果靠近提交按钮，避免用户必须滚回页面顶部才能知道失败或已建立基线。
                if (state.simulated && !state.replaying) {
                    Text("最近识别：${state.currentCity}", style = MaterialTheme.typography.titleSmall)
                    Text(state.status, style = MaterialTheme.typography.bodySmall)
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
                Text("① 首次提交设定出发城市，不发到达通知。\n② 换成新城市，连续提交两次确认；不用等待真实一分钟。\n手动模式只处理你点击提交的点位，不会自动重复定位。",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        }

        if (state.arrivals.isNotEmpty()) {
            SectionHeader("收到的城市照应", "保存在本机，模拟记录有独立标记")
            state.arrivals.take(5).forEach { CityArrivalCard(it, it.guide.code in state.refreshingGuides,
                state.guideErrors[it.guide.code]) { trip.refreshGuide(it.guide.code, it.guide.name) } }
        }
        if (state.events.isNotEmpty()) {
            TextButton(onClick = { eventsOpen = !eventsOpen }) { Text(if (eventsOpen) "收起识别过程" else "查看本次识别过程") }
            if (eventsOpen) {
            SectionHeader("本次识别过程")
            MovaCard { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.events.take(8).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            } }
            }
        }
    }
}

@Composable
private fun CityArrivalCard(entry: CityArrival, refreshing: Boolean, error: String?, onRefresh: () -> Unit) {
    val guide = entry.guide
    val uri = LocalUriHandler.current
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    fun open(url: String) {
        if (runCatching { uri.openUri(url) }.isFailure) {
            scope.launch { snackbar.showSnackbar("没有可打开链接的地图或浏览器应用") }
        }
    }
    fun search(term: String) {
        // 仅在用户点击后打开地图页面；不自动开启导航，不发送用户当前位置。
        val query = URLEncoder.encode(guide.name + " " + term, "UTF-8")
        open("https://uri.amap.com/search?keyword=$query&city=${guide.code}&src=mova&callnative=0")
    }
    MovaCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (entry.simulated) "模拟行程 · 城市卡片" else "真实出行 · 城市卡片", style = MaterialTheme.typography.labelSmall)
            Text("欢迎来到${guide.name}", style = MaterialTheme.typography.headlineSmall)
            Text(guide.subtitle, style = MaterialTheme.typography.bodyMedium)
            if (refreshing) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("正在获取这座城市的美食与景点…", style = MaterialTheme.typography.bodySmall)
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (guide.foods.isNotEmpty()) {
                Text(if (guide.foodKind == "places") "可以看看这些餐饮店" else "先认识本地味道", style = MaterialTheme.typography.titleSmall)
                guide.foods.forEach { food -> OutlinedActionButton(food + " · 地图搜索", onClick = { search(food) }) }
            }
            if (guide.sights.isNotEmpty()) {
                Text("可以去认识的地方", style = MaterialTheme.typography.titleSmall)
                guide.sights.forEach { sight -> OutlinedActionButton(sight + " · 地图搜索", onClick = { search(sight) }) }
            }
            if (guide.foods.isEmpty() && guide.sights.isEmpty() && !refreshing) {
                Text("城市推荐还未加载，点击刷新即可获取。", style = MaterialTheme.typography.bodySmall)
            } else if (guide.notice.isNotBlank() && guide.version >= 2) {
                Text(guide.notice, style = MaterialTheme.typography.bodySmall)
            }
            guide.sources.forEach { source ->
                if (source.url.isBlank()) Text(source.title, style = MaterialTheme.typography.bodySmall)
                else TextButton(onClick = { open(source.url) }) { Text(source.title) }
            }
            OutlinedActionButton(if (refreshing) "正在刷新…" else "刷新城市推荐", enabled = !refreshing, onClick = onRefresh)
            Text("资料更新 ${guide.updatedOn.ifBlank { "待更新" }} · 收到 " +
                SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(entry.timestamp)), style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** 通知点击后的专用页面，直接显示最新卡片，不要求用户先滚过出行设置。 */
@Composable
fun CityArrivalScreen(onBack: () -> Unit) {
    val state by MovaApp.instance.container.cityTrip.state.collectAsStateWithLifecycle()
    val trip = MovaApp.instance.container.cityTrip
    LaunchedEffect(Unit) { trip.refreshIncompleteGuides() }
    MovaScreen("一份城市照应", subtitle = "初到这里，慢慢认识", onBack = onBack) {
        val entry = state.arrivals.firstOrNull()
        if (entry == null) Text("尚未收到城市卡片，可以在首页开启一次出行或模拟行程。")
        else CityArrivalCard(entry, entry.guide.code in state.refreshingGuides,
            state.guideErrors[entry.guide.code]) { trip.refreshGuide(entry.guide.code, entry.guide.name) }
    }
}
