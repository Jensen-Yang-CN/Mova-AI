package com.mova.sceneai.core

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.mova.sceneai.BuildConfig
import java.net.URI
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.movaDataStore: DataStore<Preferences> by preferencesDataStore(name = "mova_settings")

/**
 * 旧版曾把本机、模拟器或公司内网地址保存到用户设置。评审包注入公网地址后，升级安装也应改用新地址；
 * 用户后来手动填写的其他服务地址继续保留，不覆盖他们的选择。
 */
private fun isPrivateHost(host: String?): Boolean {
    if (host.isNullOrBlank()) return false
    if (host in setOf("localhost", "::1") || host.startsWith("127.")) return true
    val octets = host.split('.').map { it.toIntOrNull() }
    if (octets.size != 4 || octets.any { it == null || it !in 0..255 }) return false
    val first = octets[0] ?: return false
    val second = octets[1] ?: return false
    return first == 10 || (first == 172 && second in 16..31) ||
        (first == 192 && second == 168) || (first == 169 && second == 254)
}

private fun effectiveBaseUrl(saved: String?): String {
    val published = BuildConfig.DEFAULT_BASE_URL
    if (published.isBlank()) return saved ?: published
    val oldHost = runCatching { URI(saved.orEmpty()).host?.lowercase() }.getOrNull()
    return if (saved.isNullOrBlank() || isPrivateHost(oldHost)) {
        published
    } else {
        saved
    }
}

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** 云端地址由用户填写或构建时注入；空地址不能悄悄变成手机自身的 localhost。 */
class ServiceNotConfiguredException : IllegalStateException("尚未配置 Mova 云端服务地址")

class InvalidServiceAddressException : IllegalArgumentException("服务地址必须是完整的 HTTP 或 HTTPS 地址")

/** 打扰敏感度：越低越安静。对应触发层里打扰成本项的一个乘子。 */
enum class InterruptSensitivity(val label: String, val factor: Double) {
    LOW("安静一点", 1.6),
    MEDIUM("正常", 1.0),
    HIGH("更主动", 0.6),
}

/** 一份完整的用户设置快照。UI 只读这个对象，不直接读 DataStore。 */
data class MovaSettings(
    val baseUrl: String = BuildConfig.DEFAULT_BASE_URL,
    val onboardingDone: Boolean = false,
    val proactiveEnabled: Boolean = true,
    val preferEdge: Boolean = true,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dndEnabled: Boolean = false,
    val dndStartMinutes: Int = 23 * 60,
    val dndEndMinutes: Int = 7 * 60,
    val sensitivity: InterruptSensitivity = InterruptSensitivity.MEDIUM,
    val timeoutSeconds: Int = 60,
    val chatPreference: String = "",
    val foodAvoid: String = "",
) {
    /** 归一化后的服务基地址：永远以 "/" 结尾，便于拼接端点路径。 */
    val normalizedBaseUrl: String
        get() {
            val trimmed = baseUrl.trim()
            if (trimmed.isEmpty()) throw ServiceNotConfiguredException()
            val uri = runCatching { URI(trimmed) }.getOrNull()
            if (uri?.scheme !in listOf("http", "https") || uri?.host.isNullOrBlank()) {
                throw InvalidServiceAddressException()
            }
            return if (trimmed.endsWith("/")) trimmed else "$trimmed/"
        }

    fun endpoint(path: String): String = normalizedBaseUrl + path.trimStart('/')
}

class SettingsRepository(private val context: Context) {

    private object Keys {
        val BASE_URL = stringPreferencesKey("base_url")
        val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
        val PROACTIVE = booleanPreferencesKey("proactive_enabled")
        val PREFER_EDGE = booleanPreferencesKey("prefer_edge")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DND_ENABLED = booleanPreferencesKey("dnd_enabled")
        val DND_START = intPreferencesKey("dnd_start")
        val DND_END = intPreferencesKey("dnd_end")
        val SENSITIVITY = intPreferencesKey("sensitivity")
        val TIMEOUT = intPreferencesKey("timeout_seconds")
        val CHAT_PREFERENCE = stringPreferencesKey("chat_preference")
        val FOOD_AVOID = stringPreferencesKey("food_avoid")
    }

    val settings: Flow<MovaSettings> = context.movaDataStore.data.map { p ->
        MovaSettings(
            baseUrl = effectiveBaseUrl(p[Keys.BASE_URL]),
            onboardingDone = p[Keys.ONBOARDING_DONE] ?: false,
            proactiveEnabled = p[Keys.PROACTIVE] ?: true,
            preferEdge = p[Keys.PREFER_EDGE] ?: true,
            themeMode = runCatching { ThemeMode.valueOf(p[Keys.THEME_MODE] ?: ThemeMode.SYSTEM.name) }
                .getOrDefault(ThemeMode.SYSTEM),
            dndEnabled = p[Keys.DND_ENABLED] ?: false,
            dndStartMinutes = p[Keys.DND_START] ?: (23 * 60),
            dndEndMinutes = p[Keys.DND_END] ?: (7 * 60),
            sensitivity = InterruptSensitivity.entries
                .getOrElse(p[Keys.SENSITIVITY] ?: InterruptSensitivity.MEDIUM.ordinal) { InterruptSensitivity.MEDIUM },
            timeoutSeconds = p[Keys.TIMEOUT] ?: 60,
            chatPreference = p[Keys.CHAT_PREFERENCE] ?: "",
            foodAvoid = p[Keys.FOOD_AVOID] ?: "",
        )
    }

    suspend fun current(): MovaSettings = settings.first()

    suspend fun setBaseUrl(value: String) = edit { it[Keys.BASE_URL] = value.trim() }
    suspend fun setOnboardingDone(value: Boolean) = edit { it[Keys.ONBOARDING_DONE] = value }
    suspend fun setProactiveEnabled(value: Boolean) = edit { it[Keys.PROACTIVE] = value }
    suspend fun setPreferEdge(value: Boolean) = edit { it[Keys.PREFER_EDGE] = value }
    suspend fun setThemeMode(value: ThemeMode) = edit { it[Keys.THEME_MODE] = value.name }
    suspend fun setDndEnabled(value: Boolean) = edit { it[Keys.DND_ENABLED] = value }
    suspend fun setDndStart(minutes: Int) = edit { it[Keys.DND_START] = minutes }
    suspend fun setDndEnd(minutes: Int) = edit { it[Keys.DND_END] = minutes }
    suspend fun setSensitivity(value: InterruptSensitivity) = edit { it[Keys.SENSITIVITY] = value.ordinal }
    suspend fun setTimeoutSeconds(value: Int) = edit { it[Keys.TIMEOUT] = value }
    // 偏好由用户主动保存；只在相应功能发起云端请求时传递，不自动读取聊天或相册。
    suspend fun setPreferences(chat: String, avoid: String) = edit {
        it[Keys.CHAT_PREFERENCE] = chat.trim().take(200)
        it[Keys.FOOD_AVOID] = avoid.trim().take(200)
    }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.movaDataStore.edit(block)
    }
}
