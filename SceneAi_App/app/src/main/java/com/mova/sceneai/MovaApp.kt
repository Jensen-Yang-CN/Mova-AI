package com.mova.sceneai

import android.app.Application
import com.mova.sceneai.core.AppContainer

/**
 * 应用入口。
 *
 * 在这里创建唯一的依赖容器；后续端侧推理引擎（llama.cpp / LiteRT-LM）的
 * 初始化也挂在这里，避免把 native 库加载散落到 Activity 里。
 */
class MovaApp : Application() {

    /** 全局依赖容器。第一次访问时创建（避免拖慢 Application 启动）。 */
    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: MovaApp
            private set
    }
}
