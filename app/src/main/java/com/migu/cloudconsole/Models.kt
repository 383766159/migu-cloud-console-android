package com.migu.cloudconsole

data class ModelInfo(
    val key: String,
    val family: String,
    val displayName: String,
    val fileName: String,
    val remoteName: String = fileName,
    val mmprojFileName: String = "",
    val size: String,
    val apiKind: String,
    val promptHint: String = "",
    val ngl: Int,
    val contextSize: Int,
    val maxTokens: Int,
)

data class AppSettings(
    val cloudPcUrl: String = "https://www.migufun.com/miguplay/middleGame/gameplay/700000000?gameName=%E4%BA%91%E7%94%B5%E8%84%91",
    val heartbeatMinutes: Int = 5,
    val reconnectMinutes: Int = 24 * 60,
    val modelVariant: String = "PADDLEOCR_VL_16",
    val port: Int = 8080,
    val remoteShellReadyDelayMs: Int = 2500,
    val tunnelMode: String = "remote-shell",
    val tunnelHostname: String = "chat.999cq.fun",
    val tunnelToken: String = "",
    val cloudflaredUrl: String = BootstrapScriptGenerator.defaultCloudflaredUrl(),
    val bootstrapRepoBaseUrl: String = "",
)

enum class AutomationPhase {
    IDLE,
    LAUNCHING_BROWSER,
    NAVIGATING,
    AWAITING_LOGIN,
    CONNECTING,
    DEPLOYING,
    KEEPALIVE,
    RECOVERING,
    ERROR,
}

data class LogEntry(
    val id: String,
    val time: String,
    val level: String,
    val message: String,
    val details: String = "",
)

data class AppState(
    val settings: AppSettings = AppSettings(),
    val phase: AutomationPhase = AutomationPhase.IDLE,
    val status: String = "已停止",
    val connected: Boolean = false,
    val loginRequired: Boolean = false,
    val manualLoginRequested: Boolean = false,
    val currentTask: String = "idle",
    val currentTaskLabel: String = "待命",
    val lastError: String = "",
    val startedAt: String? = null,
    val connectedAt: String? = null,
    val lastHeartbeatAt: String? = null,
    val nextRecoverAt: String? = null,
    val browserUrl: String = "",
    val browserTitle: String = "",
    val deploymentStage: String = "idle",
    val deploymentStatus: String = "未部署",
    val deploymentVerified: Boolean = false,
    val shellVerified: Boolean = false,
    val modelServerVerified: Boolean = false,
    val tunnelVerified: Boolean = false,
    val remoteProcesses: List<String> = emptyList(),
    val lastProcessReportAt: String? = null,
    val connectionEvidence: List<String> = emptyList(),
    val model: ModelInfo = ModelCatalog.defaultModel,
    val tunnelUrl: String = "",
    val apiBaseUrl: String = "",
    val apiReady: Boolean = false,
    val apiStatus: String = "未检测",
    val apiModelId: String = "",
    val lastApiCheckAt: String? = null,
    val apiExample: String = "",
    val bootstrapScript: String = "",
    val deploymentFinishedAt: String? = null,
    val recoveryCount: Int = 0,
    val backgroundEnabled: Boolean = false,
    val systemLogs: List<LogEntry> = emptyList(),
    val cloudLogs: List<LogEntry> = emptyList(),
)

object ModelCatalog {
    val models: List<ModelInfo> = listOf(
        ModelInfo(
            key = "PADDLEOCR_VL_16",
            family = "paddleocr-vl",
            displayName = "PaddleOCR-VL-1.6",
            fileName = "PaddleOCR-VL-1.6-GGUF.gguf",
            mmprojFileName = "PaddleOCR-VL-1.6-GGUF-mmproj.gguf",
            size = "1.82GB",
            apiKind = "openai-compatible-vision",
            promptHint = "适合图片、截图、票据、文档结构识别，可直接走 OpenAI 兼容接口",
            ngl = 999,
            contextSize = 8192,
            maxTokens = 4096,
        ),
        ModelInfo("Q8_K_P", "qwen-gguf", "Q8_K_P", "QW36-35B-Q8_K_P.gguf", "Qwen3.6-35B-A3B-Uncensored-HauhauCS-Aggressive-Q8_K_P.gguf", size = "44GB", apiKind = "openai-compatible-text", ngl = 15, contextSize = 4096, maxTokens = 4096),
        ModelInfo("Q6_K_P", "qwen-gguf", "Q6_K_P", "QW36-35B-Q6_K_P.gguf", "Qwen3.6-35B-A3B-Uncensored-HauhauCS-Aggressive-Q6_K_P.gguf", size = "31GB", apiKind = "openai-compatible-text", ngl = 18, contextSize = 6144, maxTokens = 4096),
        ModelInfo("Q5_K_P", "qwen-gguf", "Q5_K_P", "QW36-35B-Q5_K_P.gguf", "Qwen3.6-35B-A3B-Uncensored-HauhauCS-Aggressive-Q5_K_P.gguf", size = "28GB", apiKind = "openai-compatible-text", ngl = 18, contextSize = 8192, maxTokens = 4096),
        ModelInfo("Q4_K_M", "qwen-gguf", "Q4_K_M", "QW36-35B-Q4_K_M.gguf", "Qwen3.6-35B-A3B-Uncensored-HauhauCS-Aggressive-Q4_K_M.gguf", size = "21GB", apiKind = "openai-compatible-text", ngl = 20, contextSize = 8192, maxTokens = 4096),
        ModelInfo("Q4_K_P", "qwen-gguf", "Q4_K_P", "QW36-35B-Q4_K_P.gguf", "Qwen3.6-35B-A3B-Uncensored-HauhauCS-Aggressive-Q4_K_P.gguf", size = "23GB", apiKind = "openai-compatible-text", ngl = 20, contextSize = 8192, maxTokens = 4096),
        ModelInfo("IQ4_NL", "qwen-gguf", "IQ4_NL", "QW36-35B-IQ4_NL.gguf", "Qwen3.6-35B-A3B-Uncensored-HauhauCS-Aggressive-IQ4_NL.gguf", size = "20GB", apiKind = "openai-compatible-text", ngl = 22, contextSize = 8192, maxTokens = 4096),
        ModelInfo("IQ4_XS", "qwen-gguf", "IQ4_XS", "QW36-35B-IQ4_XS.gguf", "Qwen3.6-35B-A3B-Uncensored-HauhauCS-Aggressive-IQ4_XS.gguf", size = "19GB", apiKind = "openai-compatible-text", ngl = 22, contextSize = 16384, maxTokens = 4096),
    )

    val defaultModel: ModelInfo = models.first()

    fun normalizeModelKey(input: String?): String {
        val normalized = input.orEmpty()
        return if (models.any { it.key == normalized }) normalized else defaultModel.key
    }

    fun getModel(input: String?): ModelInfo {
        return models.firstOrNull { it.key == input } ?: defaultModel
    }

    fun isOcrModel(model: ModelInfo): Boolean = model.family == "paddleocr-vl"
}
