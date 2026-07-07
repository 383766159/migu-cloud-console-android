package com.migu.cloudconsole

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

class AutomationEngine(context: Context) {
    companion object {
        private const val POST_CONNECT_STABILIZE_MS = 60_000L
        private const val REMOTE_SHELL_VERIFY_TIMEOUT_MS = 4_000L
    }

    private val appContext = context.applicationContext
    private val settingsRepository = SettingsRepository(appContext)
    private val storageRepository = StorageRepository(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutex = Mutex()

    private val _state = MutableStateFlow(
        AppState(
            model = ModelCatalog.defaultModel,
            bootstrapScript = BootstrapScriptGenerator.buildScript(AppSettings(), ModelCatalog.defaultModel),
        ),
    )
    val state: StateFlow<AppState> = _state.asStateFlow()

    private var bridge: MiguWebViewBridge? = null
    private var loopJob: Job? = null
    private var serviceActive = false

    init {
        scope.launch(Dispatchers.IO) {
            val restored = storageRepository.readState()
            val systemLogs = storageRepository.readLogs(LogKind.SYSTEM, 160)
            val cloudLogs = storageRepository.readLogs(LogKind.CLOUD, 220)

            restored?.let {
                val settings = normalizeSettings(it.settings)
                val model = ModelCatalog.getModel(settings.modelVariant)
                val normalizedState = it.copy(
                    settings = settings,
                    model = model,
                    systemLogs = systemLogs,
                    cloudLogs = cloudLogs,
                    phase = if (it.phase == AutomationPhase.ERROR) AutomationPhase.ERROR else AutomationPhase.IDLE,
                    status = if (it.phase == AutomationPhase.ERROR) it.status else "等待重新检测云电脑状态",
                    bootstrapScript = BootstrapScriptGenerator.buildScript(settings, model),
                    tunnelUrl = BootstrapScriptGenerator.buildTunnelBaseUrl(settings.tunnelHostname),
                    apiBaseUrl = BootstrapScriptGenerator.buildTunnelBaseUrl(settings.tunnelHostname),
                    apiExample = BootstrapScriptGenerator.buildApiExample(
                        BootstrapScriptGenerator.buildTunnelBaseUrl(settings.tunnelHostname),
                        it.apiModelId,
                        model,
                    ),
                    connected = false,
                    loginRequired = false,
                    manualLoginRequested = false,
                    currentTask = "idle",
                    currentTaskLabel = "待命",
                    backgroundEnabled = false,
                )
                _state.value = normalizedState
                storageRepository.writeState(normalizedState)
            }
        }

        scope.launch {
            settingsRepository.settingsFlow.collect { incoming ->
                val settings = normalizeSettings(incoming)
                reduceState { current ->
                    val model = ModelCatalog.getModel(settings.modelVariant)
                    val baseUrl = BootstrapScriptGenerator.buildTunnelBaseUrl(settings.tunnelHostname)
                    current.copy(
                        settings = settings,
                        model = model,
                        tunnelUrl = baseUrl,
                        apiBaseUrl = baseUrl,
                        bootstrapScript = BootstrapScriptGenerator.buildScript(settings, model),
                        apiExample = BootstrapScriptGenerator.buildApiExample(baseUrl, current.apiModelId, model),
                    )
                }
            }
        }
    }

    fun attachBridge(webViewBridge: MiguWebViewBridge) {
        bridge = webViewBridge
        log("info", "WebView 已附着，手机端自动化桥已就绪")
    }

    fun detachBridge(webViewBridge: MiguWebViewBridge) {
        if (bridge === webViewBridge) {
            bridge = null
            log("warn", "WebView 已分离，后台保活暂时无法继续驱动页面")
        }
    }

    fun setServiceActive(active: Boolean) {
        serviceActive = active
        if (active) ensureBackgroundLoop()
    }

    fun handleConsole(level: String, message: String) {
        cloudLog(level, message)
    }

    fun handleProcessList(processes: List<String>) {
        reduceState { current ->
            val normalized = processes.distinct()
            val shellVerified = current.shellVerified || normalized.any { it.equals("powershell.exe", ignoreCase = true) }
            val modelVerified = current.modelServerVerified || normalized.any { it.equals("llama-server.exe", ignoreCase = true) }
            val tunnelVerified = current.tunnelVerified || normalized.any { it.equals("cloudflared.exe", ignoreCase = true) }
            refreshDeploymentEvidence(
                current.copy(
                remoteProcesses = normalized,
                lastProcessReportAt = nowIso(),
                shellVerified = shellVerified,
                modelServerVerified = modelVerified,
                tunnelVerified = tunnelVerified,
                deploymentVerified = current.apiReady || (modelVerified && tunnelVerified),
                ),
            )
        }
    }

    fun saveSettings(settings: AppSettings) {
        scope.launch {
            settingsRepository.save(normalizeSettings(settings))
            log("info", "设置已保存")
        }
    }

    fun startBackgroundMode() {
        reduceState { it.copy(backgroundEnabled = true) }
        ensureBackgroundLoop()
    }

    fun stop() {
        reduceState {
            it.copy(
                backgroundEnabled = false,
                phase = AutomationPhase.IDLE,
                status = "已停止",
            )
        }
        log("warn", "已停止自动化流程")
    }

    fun openCloudConsole() = execute("open", "打开云电脑页面", AutomationPhase.LAUNCHING_BROWSER) {
        val current = state.value
        val currentBridge = requireBridge()
        currentBridge.load(current.settings.cloudPcUrl)
        val pageState = currentBridge.awaitLandingState(8_000L)
        reduceState {
            val phase = when {
                pageState.connected -> AutomationPhase.CONNECTING
                pageState.loginRequired -> AutomationPhase.AWAITING_LOGIN
                else -> AutomationPhase.NAVIGATING
            }
            it.copy(
                startedAt = it.startedAt ?: nowIso(),
                browserTitle = pageState.title,
                browserUrl = pageState.url,
                connected = pageState.connected,
                loginRequired = pageState.loginRequired,
                manualLoginRequested = pageState.loginRequired,
                connectionEvidence = pageState.connectionEvidence,
                remoteProcesses = pageState.remoteProcesses,
                phase = phase,
                status = when {
                    pageState.connected -> "云电脑已连接"
                    pageState.loginRequired -> "检测到登录页，请完成验证码"
                    else -> "页面已打开，等待连接"
                },
                backgroundEnabled = true,
            )
        }
    }

    fun connectCloudConsole() = execute("connect", "连接 / 重连云电脑", AutomationPhase.CONNECTING) {
        val currentBridge = requireBridge()
        if (state.value.browserUrl.isBlank()) {
            currentBridge.load(state.value.settings.cloudPcUrl)
        }
        val pageState = currentBridge.waitForConnected(10 * 60 * 1000L) { progress ->
            reduceState { it.copy(status = progress) }
        }
        reduceState {
            val connectedAt = if (pageState.connected) nowIso() else it.connectedAt
            it.copy(
                phase = when {
                    pageState.connected -> AutomationPhase.CONNECTING
                    pageState.loginRequired -> AutomationPhase.AWAITING_LOGIN
                    else -> AutomationPhase.ERROR
                },
                status = when {
                    pageState.connected -> "云电脑已连接"
                    pageState.loginRequired -> "等待手动验证码登录"
                    else -> "连接超时，请检查页面状态"
                },
                connected = pageState.connected,
                loginRequired = pageState.loginRequired,
                manualLoginRequested = pageState.loginRequired,
                browserTitle = pageState.title,
                browserUrl = pageState.url,
                connectionEvidence = pageState.connectionEvidence,
                connectedAt = connectedAt,
                nextRecoverAt = connectedAt?.let { nextRecoverIso(state.value.settings.reconnectMinutes) },
                backgroundEnabled = true,
            )
        }
    }

    fun acknowledgeManualLogin() {
        reduceState {
            it.copy(
                manualLoginRequested = false,
                loginRequired = false,
                phase = AutomationPhase.CONNECTING,
                status = "已确认手动登录，继续等待连接",
            )
        }
        log("info", "已确认手动验证码登录")
    }

    fun openRemoteShell() = execute("open-shell", "打开远端 PowerShell", AutomationPhase.DEPLOYING) {
        val verified = ensureRemoteShellLaunchOrContinue(requireBridge())
        reduceState {
            it.copy(
                shellVerified = verified,
                deploymentStage = if (verified) "shell-verified" else "shell-open-attempted",
                deploymentStatus = if (verified) "已触发远端 PowerShell 启动" else "已发送打开 PowerShell 命令，等待人工确认",
                status = "远端 PowerShell 已尝试打开",
            )
        }
    }

    fun deployModel() = execute("deploy", "部署模型服务", AutomationPhase.DEPLOYING) {
        val current = state.value
        val currentBridge = requireBridge()
        val shellVerified = ensureRemoteShellLaunchOrContinue(currentBridge)
        val script = BootstrapScriptGenerator.buildScript(current.settings, current.model)
        val downloadPlan = BootstrapScriptGenerator.buildDownloadTargets(current.settings, current.model)
        log(
            "info",
            "本次部署下载计划",
            downloadPlan.joinToString(" | ") { target ->
                "${target.label} => ${target.urls.joinToString(" -> ")}"
            },
        )
        if (!shellVerified) {
            delay(maxOf(current.settings.remoteShellReadyDelayMs.toLong(), 3000L))
        }
        currentBridge.typeRemoteCommand(script)
        reduceState {
            it.copy(
                deploymentStage = "command-sent-unverified",
                deploymentStatus = "部署脚本已下发，等待 API 就绪",
                bootstrapScript = script,
                status = "部署脚本已发送到远端 PowerShell",
                shellVerified = state.value.remoteProcesses.any { process -> process.equals("powershell.exe", ignoreCase = true) } || it.shellVerified,
                backgroundEnabled = true,
            )
        }
        delay(current.settings.remoteShellReadyDelayMs.toLong())
        probeModelApiInternal(silent = true)
    }

/*
    private suspend fun waitForPostConnectStabilization() {
        val connectedAt = state.value.connectedAt ?: return
        val elapsedMs = runCatching {
            ChronoUnit.MILLIS.between(Instant.parse(connectedAt), Instant.now())
        }.getOrDefault(POST_CONNECT_STABILIZE_MS)
        if (elapsedMs >= POST_CONNECT_STABILIZE_MS) return
        val remainMs = POST_CONNECT_STABILIZE_MS - elapsedMs
        log("info", "云电脑刚连上，先等待串流桌面稳定", "remainingMs=$remainMs")
        reduceState {
            it.copy(status = "云电脑刚连上，等待串流桌面稳定 ${(remainMs + 999) / 1000} 秒")
        }
        delay(remainMs)
    }

    private suspend fun ensureRemoteShellReady(currentBridge: MiguWebViewBridge): Boolean {
        waitForPostConnectStabilization()
        if (state.value.remoteProcesses.any { it.equals("powershell.exe", ignoreCase = true) }) {
            reduceState {
                refreshDeploymentEvidence(
                    it.copy(
                        shellVerified = true,
                        deploymentStage = "shell-verified",
                        deploymentStatus = "已验证远端 PowerShell 进程启动",
                    ),
                )
            }
            return true
        }

        repeat(2) { attempt ->
            reduceState {
                it.copy(
                    deploymentStage = "opening-shell",
                    deploymentStatus = "正在打开远端 PowerShell（第 ${attempt + 1} 次）",
                    status = "正在等待远端 PowerShell 启动",
                )
            }
            val verified = currentBridge.launchRemoteShell()
            if (verified || currentBridge.waitForRemoteProcess("powershell.exe", REMOTE_SHELL_VERIFY_TIMEOUT_MS) { progress ->
                    reduceState { current -> current.copy(status = progress) }
                }
            ) {
                reduceState {
                    refreshDeploymentEvidence(
                        it.copy(
                            shellVerified = true,
                            deploymentStage = "shell-verified",
                            deploymentStatus = "已验证远端 PowerShell 进程启动",
                            status = "远端 PowerShell 已就绪",
                        ),
                    )
                }
                return true
            }
            log("warn", "未在预期时间内验证到远端 PowerShell，准备重试", "attempt=${attempt + 1}")
            delay(1200)
        }
        throw IllegalStateException("远端 PowerShell 未在 15 秒内出现，已取消本次部署")
    }

*/

    private suspend fun waitForPostConnectStabilization() {
        val connectedAt = state.value.connectedAt ?: return
        val elapsedMs = runCatching {
            ChronoUnit.MILLIS.between(Instant.parse(connectedAt), Instant.now())
        }.getOrDefault(POST_CONNECT_STABILIZE_MS)
        if (elapsedMs >= POST_CONNECT_STABILIZE_MS) return
        val remainMs = POST_CONNECT_STABILIZE_MS - elapsedMs
        log("info", "云电脑刚连上，先等待串流桌面稳定", "remainingMs=$remainMs")
        reduceState {
            it.copy(status = "云电脑刚连上，等待串流桌面稳定 ${(remainMs + 999) / 1000} 秒")
        }
        delay(remainMs)
    }

    private suspend fun ensureRemoteShellReady(currentBridge: MiguWebViewBridge): Boolean {
        waitForPostConnectStabilization()
        if (state.value.remoteProcesses.any { it.equals("powershell.exe", ignoreCase = true) }) {
            reduceState {
                refreshDeploymentEvidence(
                    it.copy(
                        shellVerified = true,
                        deploymentStage = "shell-verified",
                        deploymentStatus = "已验证远端 PowerShell 进程启动",
                    ),
                )
            }
            return true
        }

        repeat(2) { attempt ->
            reduceState {
                it.copy(
                    deploymentStage = "opening-shell",
                    deploymentStatus = "正在打开远端 PowerShell（第 ${attempt + 1} 次）",
                    status = "正在等待远端 PowerShell 启动",
                )
            }
            val verified = currentBridge.launchRemoteShell()
            if (verified || currentBridge.waitForRemoteProcess("powershell.exe", REMOTE_SHELL_VERIFY_TIMEOUT_MS) { progress ->
                    reduceState { current -> current.copy(status = progress) }
                }
            ) {
                reduceState {
                    refreshDeploymentEvidence(
                        it.copy(
                            shellVerified = true,
                            deploymentStage = "shell-verified",
                            deploymentStatus = "已验证远端 PowerShell 进程启动",
                            status = "远端 PowerShell 已就绪",
                        ),
                    )
                }
                return true
            }
            log("warn", "未在预期时间内验证到远端 PowerShell，准备重试", "attempt=${attempt + 1}")
            delay(1200)
        }
        throw IllegalStateException("远端 PowerShell 未在 15 秒内出现，已取消本次部署")
    }

    private suspend fun ensureRemoteShellEntryReady(currentBridge: MiguWebViewBridge): Boolean {
        waitForPostConnectStabilization()
        if (state.value.remoteProcesses.any { it.equals("powershell.exe", ignoreCase = true) }) {
            reduceState {
                refreshDeploymentEvidence(
                    it.copy(
                        shellVerified = true,
                        deploymentStage = "shell-verified",
                        deploymentStatus = "已验证远端 PowerShell 进程启动",
                    ),
                )
            }
            return true
        }

        repeat(2) { attempt ->
            reduceState {
                it.copy(
                    deploymentStage = "opening-shell",
                    deploymentStatus = "正在打开远端 PowerShell（第 ${attempt + 1} 次）",
                    status = "正在等待远端 PowerShell 启动",
                )
            }
            val verified = currentBridge.launchRemoteShell()
            if (verified || currentBridge.waitForRemoteProcess("powershell.exe", REMOTE_SHELL_VERIFY_TIMEOUT_MS) { progress ->
                    reduceState { current -> current.copy(status = progress) }
                }
            ) {
                reduceState {
                    refreshDeploymentEvidence(
                        it.copy(
                            shellVerified = true,
                            deploymentStage = "shell-verified",
                            deploymentStatus = "已验证远端 PowerShell 进程启动",
                            status = "远端 PowerShell 已就绪",
                        ),
                    )
                }
                return true
            }
            log("warn", "未在预期时间内验证到远端 PowerShell，准备重试", "attempt=${attempt + 1}")
            delay(1200)
        }

        log("warn", "未检测到远端 PowerShell 进程，按已触发打开处理")
        reduceState {
            it.copy(
                shellVerified = false,
                deploymentStage = "shell-open-attempted",
                deploymentStatus = "已触发打开 PowerShell 指令，但暂未检测到进程",
                status = "未检测到 PowerShell 进程，继续按已打开处理",
            )
        }
        return false
    }

    private suspend fun ensureRemoteShellLaunchOrContinue(currentBridge: MiguWebViewBridge): Boolean {
        waitForPostConnectStabilization()
        if (state.value.remoteProcesses.any { it.equals("powershell.exe", ignoreCase = true) }) {
            reduceState {
                refreshDeploymentEvidence(
                    it.copy(
                        shellVerified = true,
                        deploymentStage = "shell-verified",
                        deploymentStatus = "已验证远端 PowerShell 进程启动",
                    ),
                )
            }
            return true
        }

        repeat(2) { attempt ->
            reduceState {
                it.copy(
                    deploymentStage = "opening-shell",
                    deploymentStatus = "正在打开远端 PowerShell（第 ${attempt + 1} 次）",
                    status = "正在等待远端 PowerShell 启动",
                )
            }

            val launchDispatched = currentBridge.launchRemoteShell()
            val verified = currentBridge.waitForRemoteProcess("powershell.exe", REMOTE_SHELL_VERIFY_TIMEOUT_MS) { progress ->
                reduceState { current -> current.copy(status = progress) }
            }
            if (verified) {
                reduceState {
                    refreshDeploymentEvidence(
                        it.copy(
                            shellVerified = true,
                            deploymentStage = "shell-verified",
                            deploymentStatus = "已验证远端 PowerShell 进程启动",
                            status = "远端 PowerShell 已就绪",
                        ),
                    )
                }
                return true
            }

            if (launchDispatched) {
                log("warn", "已触发打开 PowerShell 指令，但暂未检测到进程", "attempt=${attempt + 1}")
                reduceState {
                    it.copy(
                        shellVerified = false,
                        deploymentStage = "shell-open-attempted",
                        deploymentStatus = "已触发打开 PowerShell 指令，但暂未检测到进程",
                        status = "未检测到 PowerShell 进程，继续按已打开处理",
                    )
                }
                return false
            }

            log("warn", "未在预期时间内验证到远端 PowerShell，准备重试", "attempt=${attempt + 1}")
            delay(1200)
        }

        log("warn", "未检测到远端 PowerShell 进程，按已触发打开处理")
        reduceState {
            it.copy(
                shellVerified = false,
                deploymentStage = "shell-open-attempted",
                deploymentStatus = "已触发打开 PowerShell 指令，但暂未检测到进程",
                status = "未检测到 PowerShell 进程，继续按已打开处理",
            )
        }
        return false
    }

    fun probeModelApi() {
        scope.launch {
            probeModelApiInternal(silent = false)
        }
    }

    fun heartbeat() = execute("heartbeat", "执行心跳", AutomationPhase.KEEPALIVE) {
        val ok = requireBridge().heartbeat()
        if (!ok) {
            reduceState {
                it.copy(
                    connected = false,
                    loginRequired = false,
                    phase = AutomationPhase.RECOVERING,
                    status = "连接信号缺失，准备恢复",
                )
            }
            recover("heartbeat-disconnected")
        } else {
            reduceState {
                it.copy(
                    connected = true,
                    loginRequired = false,
                    manualLoginRequested = false,
                    lastHeartbeatAt = nowIso(),
                    phase = AutomationPhase.KEEPALIVE,
                    status = "心跳已完成",
                )
            }
        }
    }

    fun recover(reason: String = "manual") = execute("recover", "恢复全流程", AutomationPhase.RECOVERING) {
        reduceState {
            it.copy(
                recoveryCount = it.recoveryCount + 1,
                status = "开始恢复流程: $reason",
                connected = false,
                shellVerified = false,
            )
        }
        val current = state.value
        val currentBridge = requireBridge()
        currentBridge.load(current.settings.cloudPcUrl)
        val pageState = currentBridge.waitForConnected(10 * 60 * 1000L) { progress ->
            reduceState { it.copy(status = progress) }
        }
        reduceState {
            it.copy(
                connected = pageState.connected,
                loginRequired = pageState.loginRequired,
                manualLoginRequested = pageState.loginRequired,
                browserUrl = pageState.url,
                browserTitle = pageState.title,
                connectionEvidence = pageState.connectionEvidence,
                connectedAt = if (pageState.connected) nowIso() else it.connectedAt,
                nextRecoverAt = if (pageState.connected) nextRecoverIso(it.settings.reconnectMinutes) else it.nextRecoverAt,
                status = if (pageState.connected) "云电脑已重新连接" else "恢复未完成，请检查登录态",
            )
        }
        if (pageState.connected && (state.value.deploymentStage != "idle" || state.value.apiReady || state.value.bootstrapScript.isNotBlank())) {
            val shellVerified = ensureRemoteShellLaunchOrContinue(currentBridge)
            val script = state.value.bootstrapScript.ifBlank {
                BootstrapScriptGenerator.buildScript(state.value.settings, state.value.model)
            }
            if (!shellVerified) {
                delay(maxOf(state.value.settings.remoteShellReadyDelayMs.toLong(), 3000L))
            }
            currentBridge.typeRemoteCommand(script)
            reduceState {
                it.copy(
                    deploymentStage = "command-sent-unverified",
                    deploymentStatus = "恢复流程已重新下发部署脚本",
                )
            }
        }
    }

    private suspend fun probeModelApiInternal(silent: Boolean) {
        val current = state.value
        val baseUrl = current.apiBaseUrl.ifBlank {
            BootstrapScriptGenerator.buildTunnelBaseUrl(current.settings.tunnelHostname)
        }
        if (baseUrl.isBlank()) {
            if (!silent) {
                reduceState {
                    it.copy(
                        apiStatus = "未配置公网域名",
                        lastApiCheckAt = nowIso(),
                        status = "请先填写 Tunnel 域名",
                    )
                }
            }
            return
        }

        val result = runCatching {
            withContext(Dispatchers.IO) {
                val connection = URL("$baseUrl/v1/models").openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 8_000
                connection.readTimeout = 8_000
                connection.inputStream.bufferedReader().use { it.readText() }
            }
        }

        result.onSuccess { payload ->
            val modelId = Regex("\"id\"\\s*:\\s*\"([^\"]+)\"").find(payload)?.groupValues?.getOrNull(1).orEmpty()
            reduceState {
                it.copy(
                    apiReady = true,
                    apiStatus = "已就绪",
                    apiModelId = modelId,
                    lastApiCheckAt = nowIso(),
                    deploymentStatus = "已通过公网 API 验证服务就绪",
                    deploymentStage = "deployment-verified",
                    deploymentVerified = true,
                    tunnelVerified = true,
                    modelServerVerified = true,
                    deploymentFinishedAt = nowIso(),
                    phase = AutomationPhase.KEEPALIVE,
                    status = "模型 API 已就绪，继续保活",
                    apiExample = BootstrapScriptGenerator.buildApiExample(baseUrl, modelId, it.model),
                )
            }
            if (!silent) log("info", "公网 API 探测成功", "modelId=$modelId")
        }.onFailure { error ->
            reduceState {
                it.copy(
                    apiReady = false,
                    apiStatus = error.message ?: "请求失败",
                    lastApiCheckAt = nowIso(),
                    phase = if (it.connected) AutomationPhase.KEEPALIVE else it.phase,
                    status = if (silent) it.status else "API 仍未就绪，请稍后重试",
                )
            }
            if (!silent) log("warn", "公网 API 探测失败", error.message.orEmpty())
        }
    }

    private fun ensureBackgroundLoop() {
        if (loopJob?.isActive == true) return
        loopJob = scope.launch {
            while (true) {
                delay(60_000)
                if (!serviceActive || !state.value.backgroundEnabled) continue
                runCatching { scheduledTickAutoRecover() }.onFailure { error ->
                    reduceState {
                        it.copy(
                            phase = AutomationPhase.ERROR,
                            status = "后台巡检失败: ${error.message}",
                            lastError = error.message.orEmpty(),
                        )
                    }
                    log("error", "后台巡检失败", error.message.orEmpty())
                }
            }
        }
    }

    private suspend fun scheduledTick() {
        val current = state.value
        if (!current.connected || current.loginRequired) return

        if (current.deploymentStage == "command-sent-unverified" && current.apiBaseUrl.isNotBlank()) {
            probeModelApiInternal(silent = true)
        }

        current.connectedAt?.let { connectedAt ->
            val ageMinutes = ChronoUnit.MINUTES.between(Instant.parse(connectedAt), Instant.now())
            if (ageMinutes >= current.settings.reconnectMinutes - 15) {
                log("warn", "接近重置窗口，开始预防性恢复")
                recover("scheduled-reconnect")
                return
            }
        }

        val ok = bridge?.heartbeat() ?: false
        if (ok) {
            reduceState {
                it.copy(
                    connected = true,
                    loginRequired = false,
                    manualLoginRequested = false,
                    lastHeartbeatAt = nowIso(),
                    phase = AutomationPhase.KEEPALIVE,
                    status = "后台心跳正常",
                )
            }
        }
    }

    private suspend fun scheduledTickAutoRecover() {
        val current = state.value
        if (current.currentTask != "idle" || current.loginRequired) return

        if (!current.connected) {
            log("warn", "检测到云电脑断线，开始自动恢复")
            reduceState {
                it.copy(
                    phase = AutomationPhase.RECOVERING,
                    status = "检测到云电脑断线，准备自动恢复",
                )
            }
            recover("scheduled-disconnect")
            return
        }

        if (current.deploymentStage == "command-sent-unverified" && current.apiBaseUrl.isNotBlank()) {
            probeModelApiInternal(silent = true)
        }

        current.connectedAt?.let { connectedAt ->
            val ageMinutes = ChronoUnit.MINUTES.between(Instant.parse(connectedAt), Instant.now())
            if (ageMinutes >= current.settings.reconnectMinutes - 15) {
                log("warn", "接近重置窗口，开始预防性恢复")
                recover("scheduled-reconnect")
                return
            }
        }

        val currentBridge = bridge ?: return
        val ok = currentBridge.heartbeat()
        if (ok) {
            reduceState {
                it.copy(
                    connected = true,
                    loginRequired = false,
                    manualLoginRequested = false,
                    lastHeartbeatAt = nowIso(),
                    phase = AutomationPhase.KEEPALIVE,
                    status = "后台心跳正常",
                )
            }
        } else {
            log("warn", "后台心跳失败，开始自动恢复")
            reduceState {
                it.copy(
                    connected = false,
                    phase = AutomationPhase.RECOVERING,
                    status = "后台心跳失败，准备自动恢复",
                )
            }
            recover("scheduled-heartbeat-disconnected")
        }
    }

    private fun refreshDeploymentEvidence(current: AppState): AppState {
        val normalized = current.remoteProcesses.map { it.lowercase() }.toSet()
        val shellVerified = current.shellVerified || normalized.contains("powershell.exe")
        val modelVerified = current.modelServerVerified || normalized.contains("llama-server.exe")
        val tunnelVerified = current.tunnelVerified || normalized.contains("cloudflared.exe")
        var next = current.copy(
            shellVerified = shellVerified,
            modelServerVerified = modelVerified,
            tunnelVerified = tunnelVerified,
            deploymentVerified = current.apiReady || (modelVerified && tunnelVerified),
        )

        if (next.deploymentStage == "opening-shell" || next.deploymentStage == "shell-open-attempted") {
            if (shellVerified) {
                next = next.copy(
                    deploymentStage = "shell-verified",
                    deploymentStatus = "已验证远端 PowerShell 进程启动",
                )
            }
            return next
        }

        if (next.deploymentStage == "command-sent-unverified") {
            next = when {
                next.apiReady -> next.copy(
                    deploymentStage = "deployment-verified",
                    deploymentStatus = "已通过外部 API 探测验证服务可调用",
                    phase = AutomationPhase.KEEPALIVE,
                    status = "模型 API 已就绪，继续保活",
                )

                modelVerified && tunnelVerified -> next.copy(
                    deploymentStage = "deployment-verified",
                    deploymentStatus = "已验证 llama-server 和 Cloudflare Tunnel 进程启动",
                    phase = AutomationPhase.KEEPALIVE,
                    status = "模型与 Tunnel 已启动，继续保活",
                )

                modelVerified -> next.copy(
                    deploymentStage = "model-running",
                    deploymentStatus = "已验证 llama-server 进程启动，等待 cloudflared 进程出现",
                )

                tunnelVerified -> next.copy(
                    deploymentStage = "tunnel-running",
                    deploymentStatus = "已验证 cloudflared 进程启动，等待 llama-server 进程出现",
                )

                shellVerified -> next.copy(
                    deploymentStatus = "远端 PowerShell 已在运行，等待 llama-server / cloudflared 进程出现",
                )

                else -> next
            }
        }
        return next
    }

    private fun execute(taskName: String, taskLabel: String, phase: AutomationPhase, block: suspend () -> Unit) {
        scope.launch {
            mutex.withLock {
                log("info", "开始任务: $taskLabel")
                reduceState {
                    it.copy(
                        currentTask = taskName,
                        currentTaskLabel = taskLabel,
                        phase = phase,
                        status = taskLabel,
                        lastError = "",
                        backgroundEnabled = true,
                    )
                }

                runCatching { block() }
                    .onSuccess {
                        log("info", "任务完成: $taskLabel")
                    }
                    .onFailure { error ->
                        reduceState {
                            it.copy(
                                phase = AutomationPhase.ERROR,
                                status = "失败: ${error.message}",
                                lastError = error.message.orEmpty(),
                            )
                        }
                        log("error", "任务失败: $taskLabel", error.message.orEmpty())
                    }

                reduceState {
                    it.copy(
                        currentTask = "idle",
                        currentTaskLabel = "待命",
                    )
                }
            }
        }
    }

    private fun requireBridge(): MiguWebViewBridge {
        return bridge ?: throw IllegalStateException("请先打开控制台页面，让 WebView 完成附着")
    }

    private fun normalizeSettings(settings: AppSettings): AppSettings {
        val defaults = AppSettings()
        return settings.copy(
            heartbeatMinutes = settings.heartbeatMinutes.coerceAtLeast(1),
            reconnectMinutes = settings.reconnectMinutes.coerceAtLeast(10),
            modelVariant = ModelCatalog.normalizeModelKey(settings.modelVariant),
            port = settings.port.coerceAtLeast(1),
            remoteShellReadyDelayMs = settings.remoteShellReadyDelayMs.coerceAtLeast(500),
            tunnelHostname = settings.tunnelHostname.trim().ifBlank { defaults.tunnelHostname },
            tunnelToken = settings.tunnelToken.trim().ifBlank { defaults.tunnelToken },
            cloudflaredUrl = BootstrapScriptGenerator.preferGithubMirror(
                settings.cloudflaredUrl.ifBlank { defaults.cloudflaredUrl },
            ),
            bootstrapRepoBaseUrl = BootstrapScriptGenerator.normalizeBootstrapRepoBaseUrl(
                settings.bootstrapRepoBaseUrl,
            ),
        )
    }

    private fun reduceState(transform: (AppState) -> AppState) {
        val next = refreshDeploymentEvidence(transform(_state.value))
        _state.value = next
        scope.launch(Dispatchers.IO) {
            storageRepository.writeState(next)
        }
    }

    private fun log(level: String, message: String, details: String = "") {
        appendLog(LogKind.SYSTEM, level, message, details)
    }

    private fun cloudLog(level: String, message: String, details: String = "") {
        appendLog(LogKind.CLOUD, level, message, details)
    }

    private fun appendLog(kind: LogKind, level: String, message: String, details: String) {
        val entry = LogEntry(
            id = "${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}",
            time = nowIso(),
            level = level,
            message = message,
            details = details,
        )
        reduceState { current ->
            if (kind == LogKind.SYSTEM) {
                current.copy(systemLogs = (current.systemLogs + entry).takeLast(320))
            } else {
                current.copy(cloudLogs = (current.cloudLogs + entry).takeLast(420))
            }
        }
        scope.launch(Dispatchers.IO) {
            storageRepository.appendLog(kind, entry)
        }
    }

    private fun nowIso(): String = Instant.now().toString()

    private fun nextRecoverIso(minutes: Int): String {
        return Instant.now().plus(minutes.toLong(), ChronoUnit.MINUTES).toString()
    }
}
