package com.mova.sceneai

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.mova.sceneai.core.ShareSource
import com.mova.sceneai.ui.MovaRoot

/**
 * 唯一的 Activity。
 *
 * 整个应用是**单 Activity + Compose 导航**：返回栈与状态恢复由 Navigation Compose
 * 统一管理，不再有多个 Activity 之间传参的样板代码。
 *
 * 它同时是**分享目标**：在微信里长按对方的消息 → 分享 → Mova-AI 会走到这里。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        receiveSharedText(intent)
        setContent { MovaRoot() }
    }

    /**
     * 应用已在前台/后台时又收到一次分享。
     *
     * 因为 Manifest 里声明了 `launchMode="singleTask"`，分享不会新建 Activity 实例，
     * 而是复用当前实例并通过这里投递 —— 否则用户每分享一次就会叠一层页面。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        receiveSharedText(intent)
    }

    /**
     * 取出分享携带的文字放进收件箱。
     *
     * 这里只负责"接住"，不负责"处理"——导航到聊天页、触发生成这些事，
     * 由 Compose 侧观察 SharedInbox 完成。Activity 管入口、Compose 管流程。
     */
    private fun receiveSharedText(intent: Intent?) {
        if (intent == null) return

        val shared: String? = when (intent.action) {
            // 有的 App 放 EXTRA_TEXT，有的只放 ClipData，两条都兜一下
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
                ?: intent.clipData?.getItemAt(0)?.coerceToText(this)?.toString()

            Intent.ACTION_PROCESS_TEXT ->
                intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()

            else -> null
        }
        if (shared.isNullOrBlank()) return

        val source = if (intent.action == Intent.ACTION_PROCESS_TEXT) {
            ShareSource.PROCESS_TEXT
        } else {
            ShareSource.SEND
        }
        (application as MovaApp).container.sharedInbox.post(shared, source)
    }
}
