package com.mova.sceneai.data.city

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.mova.sceneai.MainActivity

/** 到达通知与持续定位通知分频道；模拟通知的标题始终带明确标记。 */
class CityNotifications(private val context: Context) {
    init {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("trip_running", "出行定位状态", NotificationManager.IMPORTANCE_LOW))
        manager.createNotificationChannel(NotificationChannel("city_arrival", "城市初见", NotificationManager.IMPORTANCE_DEFAULT))
    }

    fun allowed(): Boolean = (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
        context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
        NotificationManagerCompat.from(context).areNotificationsEnabled() &&
        context.getSystemService(NotificationManager::class.java)
            .getNotificationChannel("city_arrival").importance != NotificationManager.IMPORTANCE_NONE

    private fun openCity(): PendingIntent = PendingIntent.getActivity(context, 1201,
        Intent(context, MainActivity::class.java).putExtra("open_city", true)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun ongoing(): Notification {
        val stop = PendingIntent.getService(context, 1202,
            Intent(context, CityLocationService::class.java).setAction("stop"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(context, "trip_running")
            .setSmallIcon(android.R.drawable.ic_dialog_map).setContentTitle("城市初见 · 本次出行中")
            .setContentText("正在识别城市变化，点击返回；可随时结束")
            .setOngoing(true).setContentIntent(openCity())
            .addAction(0, "结束出行", stop).build()
    }

    fun arrival(entry: CityArrival, silent: Boolean = false): Boolean {
        if (!allowed()) return false
        val guide = entry.guide
        val parts = listOfNotNull(
            guide.foods.takeIf { it.isNotEmpty() }?.joinToString("、")?.let { "本地味道：$it" },
            guide.sights.takeIf { it.isNotEmpty() }?.joinToString("、")?.let { "值得认识：$it" },
        )
        val body = parts.joinToString("；").ifEmpty { "一份城市卡片已准备好，点击查看" }
        val notification = NotificationCompat.Builder(context, "city_arrival")
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .setContentTitle((if (entry.simulated) "模拟行程 · " else "") + "欢迎来到${guide.name}")
            .setContentText(body).setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(openCity()).setAutoCancel(true).setSilent(silent).build()
        try { NotificationManagerCompat.from(context).notify(1203, notification) }
        catch (_: SecurityException) { return false }
        return true
    }
}
