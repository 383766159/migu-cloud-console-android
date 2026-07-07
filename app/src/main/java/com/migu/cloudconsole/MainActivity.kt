@file:OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)

package com.migu.cloudconsole

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.net.Uri
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloseFullscreen
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        ContextCompat.startForegroundService(this, Intent(this, AutomationService::class.java))
        requestBatteryOptimizationExemptionIfNeeded()
        setContent {
            MiguTheme {
                MainRoute(viewModel)
            }
        }
    }

    private fun requestBatteryOptimizationExemptionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val powerManager = getSystemService(PowerManager::class.java) ?: return
        if (powerManager.isIgnoringBatteryOptimizations(packageName)) return
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
        }
        runCatching { startActivity(intent) }
    }
}

@Composable
private fun MainRoute(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsState()
    var selectedTab by remember { mutableStateOf(0) }
    var webViewExpanded by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val activity = context.findActivity()
    val bridge = remember {
        MiguWebViewBridge(
            onConsoleLog = viewModel::handleConsole,
            onProcessList = viewModel::handleProcessList,
        )
    }
    val webView = remember(context) {
        WebView(context).also { bridge.attach(it) }
    }

    DisposableEffect(bridge, webView) {
        viewModel.attachBridge(bridge)
        onDispose {
            bridge.detach()
            viewModel.detachBridge(bridge)
            webView.destroy()
        }
    }

    DisposableEffect(webViewExpanded, activity) {
        activity?.requestedOrientation = if (webViewExpanded) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        onDispose {
            if (!webViewExpanded) {
                activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
        }
    }

    DisposableEffect(webViewExpanded, webView) {
        webView.post {
            webView.onResume()
            webView.resumeTimers()
            webView.requestLayout()
            webView.invalidate()
        }
        onDispose { }
    }

    BackHandler(enabled = webViewExpanded) {
        webViewExpanded = false
    }

    Scaffold(
        topBar = {
            if (!webViewExpanded) {
                CenterAlignedTopAppBar(
                    title = {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("咪咕云电脑控制台", fontWeight = FontWeight.SemiBold)
                            Text(
                                "Android 单 APK 版",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        if (webViewExpanded) {
            FullscreenWebViewScreen(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                webView = webView,
                onRefresh = viewModel::openCloudConsole,
                onExitFullscreen = { webViewExpanded = false },
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                ScrollableTabRow(selectedTabIndex = selectedTab) {
                    listOf("控制台", "日志", "设置").forEachIndexed { index, label ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            text = { Text(label) },
                        )
                    }
                }
                when (selectedTab) {
                    0 -> ConsoleScreen(
                        state = state,
                        viewModel = viewModel,
                        webView = webView,
                        onRefreshWebView = viewModel::openCloudConsole,
                        onToggleFullscreen = { webViewExpanded = !webViewExpanded },
                    )
                    1 -> LogScreen(state)
                    else -> SettingsScreen(
                        state = state,
                        onSave = viewModel::saveSettings,
                        onCopy = { copyToClipboard(context, it) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ConsoleScreen(
    state: AppState,
    viewModel: MainViewModel,
    webView: WebView,
    onRefreshWebView: () -> Unit,
    onToggleFullscreen: () -> Unit,
) {
    val phaseTone = when (state.phase) {
        AutomationPhase.ERROR -> Color(0xFF7D1E1E)
        AutomationPhase.KEEPALIVE -> Color(0xFF0F5B46)
        AutomationPhase.DEPLOYING -> Color(0xFF775311)
        else -> Color(0xFF143C52)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = phaseTone),
            shape = RoundedCornerShape(24.dp),
        ) {
            Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Rounded.Cloud, contentDescription = null, tint = Color.White)
                    Text(
                        state.status,
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusChip("阶段 ${state.phase.name.lowercase()}")
                    StatusChip(if (state.connected) "已连接" else "未连接")
                    StatusChip(if (state.loginRequired) "等待登录" else "登录通过")
                    StatusChip("模型 ${state.model.displayName}")
                }
                Text(
                    text = "页面: ${state.browserTitle.ifBlank { "--" }}\nURL: ${state.browserUrl.ifBlank { state.settings.cloudPcUrl }}",
                    color = Color.White.copy(alpha = 0.86f),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        Spacer(modifier = Modifier.height(14.dp))
        WebViewPanel(
            state = state,
            webView = webView,
            onRefreshWebView = onRefreshWebView,
            onToggleFullscreen = onToggleFullscreen,
        )
        Spacer(modifier = Modifier.height(14.dp))
        ActionPanel(state, viewModel)
        Spacer(modifier = Modifier.height(14.dp))
        EvidencePanel(state)
        Spacer(modifier = Modifier.height(14.dp))
        ScriptPreviewCard("部署脚本", state.bootstrapScript)
        Spacer(modifier = Modifier.height(14.dp))
        ScriptPreviewCard("API 调用示例", state.apiExample)
    }
}

@Composable
private fun ActionPanel(state: AppState, viewModel: MainViewModel) {
    Card(shape = RoundedCornerShape(24.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("主操作", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionButton("打开云电脑", Icons.Rounded.PlayArrow) { viewModel.openCloudConsole() }
                ActionButton("连接 / 重连", Icons.Rounded.Cloud) { viewModel.connectCloudConsole() }
                ActionButton("打开 PowerShell", Icons.Rounded.Dns) { viewModel.openRemoteShell() }
                ActionButton("启动部署", Icons.Rounded.PlayArrow) { viewModel.deployModel() }
                ActionButton("验证 API", Icons.Rounded.Dns) { viewModel.probeApi() }
                ActionButton("执行心跳", Icons.Rounded.PlayArrow) { viewModel.heartbeat() }
                ActionButton("恢复流程", Icons.Rounded.Cloud) { viewModel.recover() }
                ActionButton("停止保活", Icons.Rounded.Settings) { viewModel.stopServiceMode() }
            }
            if (state.manualLoginRequested || state.loginRequired) {
                Button(onClick = { viewModel.acknowledgeManualLogin() }, shape = RoundedCornerShape(16.dp)) {
                    Text("我已完成手机验证码登录")
                }
            }
        }
    }
}

@Composable
private fun EvidencePanel(state: AppState) {
    Card(shape = RoundedCornerShape(24.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("连接与部署证据", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            EvidenceRow("登录状态", if (state.loginRequired) "等待验证码" else "正常")
            EvidenceRow("连接状态", if (state.connected) "在线" else "离线")
            EvidenceRow("部署状态", state.deploymentStatus)
            EvidenceRow("Shell 验证", if (state.shellVerified) "已触发" else "未确认")
            EvidenceRow("API 状态", state.apiStatus)
            EvidenceRow("公网域名", state.tunnelUrl.ifBlank { "--" })
            EvidenceRow("模型 ID", state.apiModelId.ifBlank { "--" })
            EvidenceRow("上次心跳", state.lastHeartbeatAt ?: "--")
            EvidenceRow("下次恢复", state.nextRecoverAt ?: "--")
            if (state.connectionEvidence.isNotEmpty()) {
                HorizontalDivider()
                Text("连接证据", fontWeight = FontWeight.SemiBold)
                state.connectionEvidence.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
            }
            if (state.remoteProcesses.isNotEmpty()) {
                HorizontalDivider()
                Text("远端进程", fontWeight = FontWeight.SemiBold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.remoteProcesses.forEach { AssistChip(onClick = {}, label = { Text(it) }) }
                }
            }
        }
    }
}

@Composable
private fun WebViewPanel(
    state: AppState,
    webView: WebView,
    onRefreshWebView: () -> Unit,
    onToggleFullscreen: () -> Unit,
) {
    val busy = state.phase == AutomationPhase.LAUNCHING_BROWSER ||
        state.phase == AutomationPhase.NAVIGATING ||
        state.phase == AutomationPhase.CONNECTING ||
        state.phase == AutomationPhase.AWAITING_LOGIN ||
        state.phase == AutomationPhase.RECOVERING
    val latestCloudLog = state.cloudLogs.lastOrNull()
    val latestSystemLog = state.systemLogs.lastOrNull()

    Card(shape = RoundedCornerShape(24.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("页面状态", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (busy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusTag("阶段 ${state.phase.name.lowercase()}")
                StatusTag(
                    when {
                        state.connected -> "已到远端画面"
                        state.loginRequired -> "等待手动登录"
                        state.browserUrl.isBlank() -> "尚未打开页面"
                        else -> "页面加载中"
                    },
                )
            }
            Text(
                "当前提示: ${state.status}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            latestCloudLog?.let {
                Text(
                    "页面日志: ${it.message}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            latestSystemLog?.let {
                Text(
                    "系统日志: ${it.message}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }

    Spacer(modifier = Modifier.height(12.dp))
    Card(shape = RoundedCornerShape(24.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "云电脑页面",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onRefreshWebView) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.size(6.dp))
                    Text("刷新")
                }
                TextButton(onClick = onToggleFullscreen) {
                    Icon(Icons.Rounded.OpenInFull, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.size(6.dp))
                    Text("横屏放大")
                }
            }
            Text(
                "这里保留真正可交互的咪咕页面。看不到验证码时，可以先点刷新，再点右上角放大横屏查看。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(360.dp)
                    .background(Color(0xFF0E1721), RoundedCornerShape(20.dp)),
            ) {
                AndroidView(
                    factory = {
                        (webView.parent as? ViewGroup)?.removeView(webView)
                        webView
                    },
                    update = {
                        it.post {
                            it.requestLayout()
                            it.invalidate()
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                TextButton(
                    onClick = onToggleFullscreen,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp),
                ) {
                    Icon(Icons.Rounded.OpenInFull, contentDescription = null, tint = Color.White)
                    Spacer(modifier = Modifier.size(4.dp))
                    Text("放大", color = Color.White)
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.28f))
                        .clickable(onClick = onToggleFullscreen)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Text(
                        "点击这里横屏放大窗口",
                        color = Color.White,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            }
        }
    }
}

@Composable
private fun FullscreenWebViewScreen(
    modifier: Modifier = Modifier,
    webView: WebView,
    onRefresh: () -> Unit,
    onExitFullscreen: () -> Unit,
) {
    Box(
        modifier = modifier
            .background(Color.Black)
            .navigationBarsPadding(),
    ) {
        AndroidView(
            factory = {
                (webView.parent as? ViewGroup)?.removeView(webView)
                webView
            },
            update = {
                it.post {
                    it.requestLayout()
                    it.invalidate()
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = onRefresh, shape = RoundedCornerShape(18.dp)) {
                Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.size(6.dp))
                Text("刷新")
            }
            Button(onClick = onExitFullscreen, shape = RoundedCornerShape(18.dp)) {
                Icon(Icons.Rounded.CloseFullscreen, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.size(6.dp))
                Text("退出放大")
            }
        }
    }
}

@Composable
private fun ScriptPreviewCard(title: String, content: String) {
    val context = LocalContext.current
    Card(shape = RoundedCornerShape(24.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { copyToClipboard(context, content) }) {
                    Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.size(6.dp))
                    Text("复制")
                }
            }
            Text(
                text = if (content.isBlank()) "当前暂无内容" else content,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 14,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun LogScreen(state: AppState) {
    var showCloud by remember { mutableStateOf(false) }
    val items = if (showCloud) state.cloudLogs else state.systemLogs
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            FilterChip(selected = !showCloud, onClick = { showCloud = false }, label = { Text("系统日志") })
            FilterChip(selected = showCloud, onClick = { showCloud = true }, label = { Text("云电脑日志") })
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(items.reversed(), key = { it.id }) { log ->
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFF6F0E7)),
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            "${log.time} | ${log.level.uppercase()}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(log.message, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        if (log.details.isNotBlank()) {
                            Text(
                                log.details,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(state: AppState, onSave: (AppSettings) -> Unit, onCopy: (String) -> Unit) {
    var draft by remember(state.settings) { mutableStateOf(state.settings) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFFEAF4EF))) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("手机端配置", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "Android 版会把 Tunnel 信息和部署脚本保存在手机本地，所以这里的参数只需要配置一次。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        OutlinedTextField(
            value = draft.cloudPcUrl,
            onValueChange = { draft = draft.copy(cloudPcUrl = it) },
            label = { Text("云电脑地址") },
            modifier = Modifier.fillMaxWidth(),
        )
        ModelSelector(selected = draft.modelVariant, onSelected = { draft = draft.copy(modelVariant = it) })
        OutlinedTextField(
            value = draft.tunnelHostname,
            onValueChange = { draft = draft.copy(tunnelHostname = it) },
            label = { Text("公网域名") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = draft.tunnelToken,
            onValueChange = { draft = draft.copy(tunnelToken = it) },
            label = { Text("Tunnel Token") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = draft.cloudflaredUrl,
            onValueChange = { draft = draft.copy(cloudflaredUrl = it) },
            label = { Text("Cloudflared 下载地址") },
            modifier = Modifier.fillMaxWidth(),
        )
        NumberField("心跳间隔（分钟）", draft.heartbeatMinutes) { draft = draft.copy(heartbeatMinutes = it) }
        NumberField("恢复间隔（分钟）", draft.reconnectMinutes) { draft = draft.copy(reconnectMinutes = it) }
        NumberField("端口", draft.port) { draft = draft.copy(port = it) }
        NumberField("Shell 等待（毫秒）", draft.remoteShellReadyDelayMs) { draft = draft.copy(remoteShellReadyDelayMs = it) }
        OutlinedTextField(
            value = draft.bootstrapRepoBaseUrl,
            onValueChange = { draft = draft.copy(bootstrapRepoBaseUrl = it) },
            label = { Text("脚本仓库 Raw Base URL") },
            supportingText = { Text("例如 https://github.999cq.fun/https://raw.githubusercontent.com/<user>/<repo>/main/scripts") },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { onSave(draft) }, shape = RoundedCornerShape(16.dp)) {
                Text("保存配置")
            }
            TextButton(onClick = { onCopy(state.bootstrapScript) }) {
                Text("复制部署脚本")
            }
            TextButton(onClick = { onCopy(state.apiExample) }) {
                Text("复制 API 示例")
            }
        }
    }
}

@Composable
private fun ModelSelector(selected: String, onSelected: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("模型", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ModelCatalog.models.forEach { model ->
                FilterChip(
                    selected = model.key == selected,
                    onClick = { onSelected(model.key) },
                    label = { Text("${model.displayName} | ${model.size}") },
                )
            }
        }
    }
}

@Composable
private fun NumberField(label: String, value: Int, onChange: (Int) -> Unit) {
    OutlinedTextField(
        value = value.toString(),
        onValueChange = { onChange(it.toIntOrNull() ?: value) },
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}

@Composable
private fun ActionButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.size(8.dp))
        Text(label)
    }
}

@Composable
private fun StatusChip(text: String) {
    Surface(
        color = Color.White.copy(alpha = 0.16f),
        shape = RoundedCornerShape(999.dp),
    ) {
        Text(
            text,
            color = Color.White,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun StatusTag(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(999.dp),
    ) {
        Text(
            text,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun EvidenceRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 16.dp))
    }
}

private fun copyToClipboard(context: Context, value: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("content", value))
}

private fun Context.findActivity(): Activity? {
    return when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}

@Composable
private fun MiguTheme(content: @Composable () -> Unit) {
    val colors = androidx.compose.material3.lightColorScheme(
        primary = Color(0xFF194A5A),
        onPrimary = Color.White,
        secondary = Color(0xFF946B2D),
        onSecondary = Color.White,
        tertiary = Color(0xFF24533B),
        background = Color(0xFFF3EEE7),
        surface = Color(0xFFFFFBF7),
        surfaceVariant = Color(0xFFE6DDD1),
        onSurfaceVariant = Color(0xFF5B554E),
    )
    MaterialTheme(colorScheme = colors, content = content)
}
