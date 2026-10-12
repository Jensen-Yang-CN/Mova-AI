package com.mova.sceneai.data.repo

/**
 * 端侧推理运行时的状态。
 *
 * 界面对端侧能力必须**如实展示**：未部署就说未部署，不假装有。
 * 这既是诚信问题，也避免在面试/答辩被追问时穿帮。
 */
data class EdgeRuntimeState(
    val deployed: Boolean = false,
    val loaded: Boolean = false,
    val name: String? = null,
    val sizeMb: Double? = null,
    val quantization: String? = null,
    val loadingError: String? = null,
) {
    /** 端侧是否已经可以承接推理 */
    val ready: Boolean get() = deployed && loaded

    val statusLabel: String
        get() = when {
            ready -> "端侧模型运行中"
            loadingError != null -> "端侧模型加载失败"
            deployed -> "端侧模型加载中…"
            else -> "端侧模型未部署"
        }

    val unavailableReason: String?
        get() = when {
            loadingError != null -> loadingError
            ready -> null
            deployed -> "模型文件已就位，正在加载"
            else -> "请在设置中导入 Q4_K_M 模型；正文生成仍需云端服务"
        }
}
