package com.mova.sceneai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.mova.sceneai.ui.MovaRoot

/**
 * 唯一的 Activity。
 *
 * 整个应用是**单 Activity + Compose 导航**：进入后台/返回栈/状态恢复由
 * Navigation Compose 统一管理，不再有多个 Activity 之间传参的样板代码。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { MovaRoot() }
    }
}
