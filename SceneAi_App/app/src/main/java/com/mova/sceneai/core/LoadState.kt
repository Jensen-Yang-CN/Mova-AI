package com.mova.sceneai.core

/** 页面级加载状态。所有请求型页面共用同一套状态机，避免各写各的。 */
sealed interface LoadState<out T> {
    data object Idle : LoadState<Nothing>
    data object Loading : LoadState<Nothing>
    data class Success<T>(val data: T) : LoadState<T>
    data class Failed(val error: UiError) : LoadState<Nothing>

    val isLoading: Boolean get() = this is Loading
}
