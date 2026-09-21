package com.mova.sceneai.core

import kotlinx.serialization.SerializationException
import retrofit2.HttpException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * 面向用户的错误描述。
 *
 * 设计原则（对应 docs/03 文案规范）：错误必须同时给出
 *   ① 发生了什么（title）② 为什么（detail）③ 怎么办（hint + action）
 * 例外：技术面板里可以展示原始异常文本，但主流程一律不出现英文堆栈。
 */
data class UiError(
    val title: String,
    val detail: String,
    val hint: String? = null,
    val actionLabel: String? = null,
    val action: ErrorAction = ErrorAction.None,
    val retryable: Boolean = true,
) {
    companion object {
        val None = UiError("未知错误", "出现了未预期的问题。", "可以重试一次。", "重试")
    }
}

enum class ErrorAction { None, Retry, OpenSettings }

/**
 * 把底层异常翻译成"用户能自己解决"的提示。
 * 这是整个 App 里唯一允许出现技术判断的地方，UI 层不重复这套逻辑。
 */
fun Throwable.toUiError(baseUrl: String? = null): UiError = when (this) {

    is UnknownHostException -> UiError(
        title = "找不到这个服务地址",
        detail = "手机无法解析「${baseUrl ?: "当前地址"}」，通常是地址写错了，或者手机没联网。",
        hint = "地址格式应类似 http://192.168.1.10:8000/",
        actionLabel = "去设置",
        action = ErrorAction.OpenSettings,
        retryable = false,
    )

    is ConnectException -> UiError(
        title = "连不上服务",
        detail = "地址能解析，但没有服务在监听。电脑上的服务可能没有启动。",
        hint = "确认电脑已执行 uvicorn app:app --host 0.0.0.0 --port 8000，且端口与地址一致。",
        actionLabel = "重试",
        action = ErrorAction.Retry,
    )

    is SocketTimeoutException -> UiError(
        title = "服务响应太慢",
        detail = "等待超过设定时间。网络不稳定，或这次处理的图片比较大。",
        hint = "可以重试一次；也可以在设置里把超时时间调大。",
        actionLabel = "重试",
        action = ErrorAction.Retry,
    )

    is HttpException -> {
        val code = code()
        when (code) {
            401, 403 -> UiError(
                title = "服务拒绝了这次请求",
                detail = "服务端鉴权失败（HTTP $code），通常是服务端没配置好模型密钥。",
                hint = "这是服务端配置问题，请在电脑上检查 .env 里的 API Key。",
                retryable = false,
            )

            413 -> UiError(
                title = "文件太大了",
                detail = "服务端拒绝了这个体积的文件（HTTP 413）。",
                hint = "换一张更小的图片，或裁剪后再试。",
            )

            422 -> UiError(
                title = "请求内容不符合服务要求",
                detail = "服务端没能理解这次请求（HTTP 422）。",
                hint = "如果是文本输入，确认内容不是空的。",
            )

            in 500..599 -> UiError(
                title = "服务端处理失败",
                detail = "服务内部出错了（HTTP $code），常见于模型调用失败或返回格式异常。",
                hint = "稍后重试；如果一直失败，请查看电脑上服务的日志输出。",
                actionLabel = "重试",
                action = ErrorAction.Retry,
            )

            else -> UiError(
                title = "请求被拒绝",
                detail = "服务返回了 HTTP $code。",
                hint = "请查看电脑上服务的日志。",
            )
        }
    }

    is SerializationException -> UiError(
        title = "返回内容看不懂",
        detail = "服务返回的数据结构与 App 预期的格式不一致。",
        hint = "通常是服务端版本与 App 不匹配，请确认两边都是最新代码。",
    )

    is IOException -> UiError(
        title = "网络请求失败",
        detail = message?.takeIf { it.isNotBlank() } ?: "网络连接中断。",
        hint = "确认手机与电脑在同一个 WiFi，并且没有开启会拦截局域网的 VPN。",
        actionLabel = "重试",
        action = ErrorAction.Retry,
    )

    else -> UiError(
        title = "出了点问题",
        detail = message?.takeIf { it.isNotBlank() } ?: this::class.simpleName.orEmpty(),
        hint = "可以重试一次。",
        actionLabel = "重试",
        action = ErrorAction.Retry,
    )
}
