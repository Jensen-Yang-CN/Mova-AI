package com.mova.sceneai.data.city

import android.Manifest
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.mova.sceneai.MovaApp
import kotlinx.coroutines.*

/**
 * 仅由可见页面中的「开始真实出行」启动。结束或进程终止后不自动重启。
 * 不申请全天后台位置权限；持续前台服务有可见通知与停止按钮。
 */
class CityLocationService : Service(), LocationListener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var locationManager: LocationManager
    private val trip get() = MovaApp.instance.container.cityTrip
    private var lastProcessed = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") { stopSelf(); return START_NOT_STICKY }
        if (trip.state.value.active && !trip.state.value.simulated) return START_NOT_STICKY
        trip.begin(false, intent?.getStringExtra("destination") ?: "")
        try {
            ServiceCompat.startForeground(this, 1200, CityNotifications(this).ongoing(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            locationManager = getSystemService(LocationManager::class.java)
            val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            if (!coarse && !fine) throw SecurityException("没有定位授权")
            val providers = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
                .filter { locationManager.isProviderEnabled(it) && (it != LocationManager.GPS_PROVIDER || fine) }
            if (providers.isEmpty()) { trip.report("手机定位未开启，请打开系统定位后重试"); stopSelf() }
            for (provider in providers) {
                locationManager.requestLocationUpdates(provider, 120_000, 500f, this)
                locationManager.getLastKnownLocation(provider)?.let(::onLocationChanged)
            }
        } catch (_: Exception) {
            trip.report("无法启动位置服务，请检查定位授权与系统定位开关")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onLocationChanged(location: Location) {
        val now = SystemClock.elapsedRealtime()
        val age = now - location.elapsedRealtimeNanos / 1_000_000
        if (age !in 0..120_000 || !location.hasAccuracy() || location.accuracy > 3_000) return
        if (lastProcessed != 0L && now - lastProcessed < 120_000) return
        lastProcessed = now
        scope.launch { trip.submit(CityFixRequest(location.longitude, location.latitude, "wgs84"), now) }
    }

    override fun onDestroy() {
        if (::locationManager.isInitialized) runCatching { locationManager.removeUpdates(this) }
        scope.cancel()
        if (trip.state.value.active && !trip.state.value.simulated) trip.end()
        super.onDestroy()
    }
}
