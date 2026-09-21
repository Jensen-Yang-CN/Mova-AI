package com.mova.sceneai.core

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mova.sceneai.MovaApp
import com.mova.sceneai.data.local.LocalStore
import com.mova.sceneai.data.remote.ApiFactory
import com.mova.sceneai.data.repo.MovaRepository

/**
 * 手写的依赖容器。
 *
 * 刻意不引入 Hilt：本项目依赖图只有 4 个对象，用构造函数注入 + 一个容器
 * 比一整套注解处理器更透明、编译更快，读代码的人也不用先学 DI 框架。
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    val json = ApiFactory.json
    val settings = SettingsRepository(appContext)
    val store = LocalStore(appContext, json)
    val repository = MovaRepository(
        context = appContext,
        api = ApiFactory.createApi(ApiFactory.createClient(), json),
        settingsRepo = settings,
        store = store,
        json = json,
    )
}

/**
 * 统一的 ViewModel 获取入口：`movaViewModel { HomeViewModel(it) }`。
 * 让每个 ViewModel 都只声明自己需要容器里的什么。
 */
@Composable
inline fun <reified VM : ViewModel> movaViewModel(
    noinline create: (AppContainer) -> VM,
): VM = viewModel { create(MovaApp.instance.container) }
