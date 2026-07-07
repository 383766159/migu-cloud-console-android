package com.migu.cloudconsole

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Build
import android.webkit.CookieManager
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID
import kotlin.coroutines.resume

data class DetectedPageState(
    val url: String,
    val title: String,
    val connected: Boolean,
    val loginRequired: Boolean,
    val readyToStart: Boolean,
    val connectionEvidence: List<String>,
    val remoteProcesses: List<String>,
    val connectedBySignal: Boolean,
    val connectedBySurface: Boolean,
)

data class RemoteControls(
    val hasClient: Boolean,
    val hasSendCopyText: Boolean,
    val hasControlCenter: Boolean,
    val hasDispatchKeyboard: Boolean,
    val hasSendControlMessage: Boolean,
    val hasNativeInput: Boolean,
)

private data class ConsoleSignal(
    val timeMillis: Long,
    val level: String,
    val text: String,
)

private data class TimedSignal(
    val timeMillis: Long,
    val texts: List<String>,
)

class MiguWebViewBridge(
    private val onConsoleLog: (String, String) -> Unit,
    private val onProcessList: (List<String>) -> Unit,
) {
    private var webView: WebView? = null
    private val consoleSignals = mutableListOf<ConsoleSignal>()
    private var lastProcessList: List<String> = emptyList()
    private var lastPageLoadAtMillis: Long = 0L

    @SuppressLint("SetJavaScriptEnabled")
    fun attach(webView: WebView) {
        this.webView = webView
        webView.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        webView.setBackgroundColor(Color.BLACK)
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        webView.setInitialScale(0)
        webView.isFocusable = true
        webView.isFocusableInTouchMode = true
        WebView.setWebContentsDebuggingEnabled(false)
        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            setSupportZoom(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                safeBrowsingEnabled = true
            }
        }
        webView.settings.userAgentString = desktopChromeUserAgent()
        onConsoleLog("info", "webview_user_agent ${webView.settings.userAgentString}")
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                lastPageLoadAtMillis = System.currentTimeMillis()
                consoleSignals.clear()
                lastProcessList = emptyList()
                onConsoleLog("info", "page_started ${url.orEmpty()}")
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                onConsoleLog("info", "page_finished ${url.orEmpty()}")
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true) {
                    onConsoleLog("error", "page_error ${error?.errorCode ?: -1} ${error?.description ?: ""} ${request.url}")
                }
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                val message = consoleMessage.message()
                val level = consoleMessage.messageLevel().name.lowercase()
                rememberSignal(level, message)
                onConsoleLog(level, message)
                parseProcessCandidates(message)?.let {
                    lastProcessList = it
                    onProcessList(it)
                }
                return true
            }
        }
    }

    fun detach() {
        webView = null
    }

    suspend fun load(url: String) {
        val target = requireWebView()
        lastPageLoadAtMillis = System.currentTimeMillis()
        withContext(Dispatchers.Main.immediate) {
            target.onResume()
            target.resumeTimers()
            target.requestFocus(View.FOCUS_DOWN)
            target.clearCache(true)
            target.loadUrl(url)
        }
        delay(1500)
        tryRevealLoginUiStable()
    }

    suspend fun awaitLandingState(timeoutMs: Long): DetectedPageState {
        val started = System.currentTimeMillis()
        var last = detectPageState()
        var revealAttempts = 0
        while (System.currentTimeMillis() - started < timeoutMs) {
            last = detectPageState()
            if (last.loginRequired || last.connected || last.readyToStart) {
                return last
            }
            if (revealAttempts % 3 == 0) {
                tryRevealLoginUiStable()
            }
            revealAttempts += 1
            delay(700)
        }
        return detectPageState()
    }

    suspend fun detectPageState(): DetectedPageState {
        val raw = evaluateJson(
            """
            (() => {
              const re = (source, flags = '') => new RegExp(source, flags);
              const loginPatterns = [
                re('\\u767b\\u5f55'),
                re('\\u624b\\u673a\\u53f7'),
                re('\\u9a8c\\u8bc1\\u7801'),
                re('\\u77ed\\u4fe1'),
                re('\\u83b7\\u53d6\\u9a8c\\u8bc1\\u7801'),
                re('\\u4e00\\u952e\\u767b\\u5f55'),
                re('\\u626b\\u7801\\u767b\\u5f55'),
                /login/i,
                /sign in/i,
              ];
              const startPatterns = [
                re('\\u542f\\u52a8'),
                re('\\u5f00\\u59cb'),
                re('\\u8fde\\u63a5'),
                re('\\u8fdb\\u5165'),
                re('\\u7ee7\\u7eed'),
                re('\\u4e91\\u7535\\u8111'),
                /start/i,
                /connect/i,
              ];
              const hasAny = (text, patterns) => patterns.some((pattern) => pattern.test(text));
              const isVisible = (node) => {
                if (!node) return false;
                const style = window.getComputedStyle(node);
                const rect = node.getBoundingClientRect();
                return style.visibility !== 'hidden' && style.display !== 'none' && Number(style.opacity || 1) > 0 && rect.width > 2 && rect.height > 2;
              };
              const controls = Array.from(document.querySelectorAll('input, button, textarea, [role="button"], a'))
                .filter(isVisible)
                .slice(0, 80)
                .map((node) => ({
                  text: (node.innerText || node.textContent || '').trim(),
                  aria: node.getAttribute('aria-label') || '',
                  placeholder: node.getAttribute('placeholder') || '',
                }));
              const visibleText = Array.from(document.body?.querySelectorAll('*') || [])
                .filter(isVisible)
                .slice(0, 300)
                .map((node) => (node.innerText || node.textContent || '').trim())
                .filter(Boolean)
                .join('\n');
              const viewportArea = Math.max(window.innerWidth * window.innerHeight, 1);
              const remoteNodes = Array.from(document.querySelectorAll('canvas, video, iframe, [class*="game"], [id*="game"], [class*="player"], [id*="player"]'))
                .filter(isVisible)
                .map((node) => {
                  const rect = node.getBoundingClientRect();
                  return {
                    tag: node.tagName.toLowerCase(),
                    width: rect.width,
                    height: rect.height,
                    areaRatio: (rect.width * rect.height) / viewportArea,
                  };
                })
                .filter((item) => item.width >= 240 && item.height >= 160);
              const fullText = [document.title || '', visibleText, controls.map((item) => `${'$'}{item.text} ${'$'}{item.aria} ${'$'}{item.placeholder}`).join('\n')].join('\n');
              const hasVisibleLoginControl = controls.some((item) => hasAny(`${'$'}{item.text}\n${'$'}{item.aria}\n${'$'}{item.placeholder}`, loginPatterns));
              const loginRequired = hasVisibleLoginControl || hasAny(fullText, loginPatterns);
              const largestRemoteAreaRatio = remoteNodes.reduce((max, item) => Math.max(max, item.areaRatio), 0);
              const client = window.LightPlayClient;
              const controlCenter = client?.controlCenter;
              const nativeInput = controlCenter?.nativeChineseInputControl;
              const hasDispatchKeyboard = typeof controlCenter?.dispatchKeyboard === 'function';
              const connectedBySurface = remoteNodes.length > 0 && !loginRequired;
              const readyToStart = !loginRequired && !connectedBySurface && hasAny(fullText, startPatterns);
              return JSON.stringify({
                url: location.href,
                title: document.title || '',
                connectedBySurface,
                loginRequired,
                readyToStart,
                remoteNodeCount: remoteNodes.length,
                largestRemoteAreaRatio,
                hasVisibleLoginControl,
                hasLightPlayClient: Boolean(client),
                hasControlCenter: Boolean(controlCenter),
                hasNativeInput: Boolean(nativeInput),
                hasDispatchKeyboard,
              });
            })();
            """.trimIndent()
        )

        val connectedSignals = recentConnectionEvidence()
        val disconnectSignals = recentDisconnectEvidence()
        val connectedEvidence = connectedSignals.texts
            .filterNot { it.contains("game_start", ignoreCase = true) }
        val disconnectEvidence = disconnectSignals.texts
        val loginEvidence = recentLoginEvidence().texts
        val remoteRuntimeReady = raw.optBoolean("hasLightPlayClient") &&
            raw.optBoolean("hasControlCenter") &&
            (raw.optBoolean("hasNativeInput") || raw.optBoolean("hasDispatchKeyboard"))
        val connectedBySignal = connectedEvidence.isNotEmpty() &&
            connectedSignals.timeMillis >= disconnectSignals.timeMillis
        val disconnectedBySignal = disconnectEvidence.isNotEmpty() &&
            disconnectSignals.timeMillis > connectedSignals.timeMillis
        val loginLatched = loginEvidence.isNotEmpty() && !connectedBySignal
        val pageStillSettling = isPageStillSettling()
        val loginRequired = raw.optBoolean("loginRequired") || (loginLatched && !connectedBySignal)
        val connectedBySurface = raw.optBoolean("connectedBySurface") &&
            raw.optInt("remoteNodeCount") > 0 &&
            raw.optDouble("largestRemoteAreaRatio") >= 0.18 &&
            remoteRuntimeReady &&
            !pageStillSettling &&
            !loginRequired
        val finalConnected = !disconnectedBySignal && (connectedBySignal || connectedBySurface)
        return DetectedPageState(
            url = raw.optString("url"),
            title = raw.optString("title"),
            connected = finalConnected,
            loginRequired = loginRequired,
            readyToStart = raw.optBoolean("readyToStart") && !loginRequired,
            connectionEvidence = ((if (disconnectedBySignal) disconnectEvidence else connectedEvidence) + loginEvidence).distinct(),
            remoteProcesses = lastProcessList,
            connectedBySignal = connectedBySignal,
            connectedBySurface = connectedBySurface,
        )
    }

    suspend fun clickStartIfPresent(): Boolean {
        val payload = evaluateJson(
            """
            (() => {
              const match = ['启动','开始','连接','进入','继续','云电脑'];
              const visible = (node) => {
                if (!node) return false;
                const style = window.getComputedStyle(node);
                const rect = node.getBoundingClientRect();
                return style.visibility !== 'hidden' && style.display !== 'none' && rect.width > 2 && rect.height > 2;
              };
              const nodes = Array.from(document.querySelectorAll('button, a, [role="button"], div, span')).filter(visible);
              for (const node of nodes) {
                const text = (node.innerText || node.textContent || '').trim();
                if (!text) continue;
                if (match.some((item) => text.includes(item))) {
                  node.click();
                  return JSON.stringify({ clicked: true, text });
                }
              }
              return JSON.stringify({ clicked: false });
            })();
            """.trimIndent()
        )
        return payload.optBoolean("clicked")
    }

    suspend fun tryRevealLoginUi(): Boolean {
        val payload = evaluateJson(
            """
            (() => {
              const loginPatterns = [/登录/i, /验证码/i, /手机号/i, /短信/i, /login/i, /sign in/i, /scan/i];
              const textOf = (node) => (node?.innerText || node?.textContent || '').trim();
              const styleOf = (node) => window.getComputedStyle(node);
              const matchLogin = (node) => {
                const marker = `${'$'}{node.id || ''} ${'$'}{node.className || ''} ${'$'}{textOf(node)}`;
                return loginPatterns.some((pattern) => pattern.test(marker));
              };
              const loginNodes = Array.from(document.querySelectorAll('body *')).filter(matchLogin).slice(0, 60);
              const touched = [];
              const forceShow = (node) => {
                let current = node;
                for (let depth = 0; current && depth < 5; depth += 1, current = current.parentElement) {
                  try {
                    current.style.setProperty('display', 'block', 'important');
                    current.style.setProperty('visibility', 'visible', 'important');
                    current.style.setProperty('opacity', '1', 'important');
                    current.style.setProperty('z-index', '2147483646', 'important');
                    current.style.setProperty('pointer-events', 'auto', 'important');
                    touched.push(`${'$'}{current.tagName}:${'$'}{current.id || ''}:${'$'}{current.className || ''}`);
                  } catch {}
                }
              };
              loginNodes.forEach(forceShow);

              const blockers = Array.from(document.querySelectorAll('body *'))
                .filter((node) => {
                  const tag = `${'$'}{node.id || ''} ${'$'}{node.className || ''}`.toLowerCase();
                  const style = styleOf(node);
                  const rect = node.getBoundingClientRect();
                  const fullScreenLike = rect.width >= window.innerWidth * 0.7 && rect.height >= window.innerHeight * 0.4;
                  return fullScreenLike &&
                    style.display !== 'none' &&
                    (
                      tag.includes('loading') ||
                      tag.includes('launch') ||
                      tag.includes('boot') ||
                      tag.includes('splash') ||
                      tag.includes('mask') ||
                      tag.includes('logo')
                    );
                })
                .slice(0, 40);
              blockers.forEach((node) => {
                try {
                  node.style.setProperty('display', 'none', 'important');
                  node.style.setProperty('visibility', 'hidden', 'important');
                  node.style.setProperty('opacity', '0', 'important');
                } catch {}
              });

              return JSON.stringify({
                ok: loginNodes.length > 0 || blockers.length > 0,
                loginNodes: loginNodes.length,
                blockers: blockers.length,
                sample: touched.slice(0, 6),
              });
            })();
            """.trimIndent()
        )
        if (payload.optBoolean("ok")) {
            onConsoleLog("info", "reveal_login_ui loginNodes=${payload.optInt("loginNodes")} blockers=${payload.optInt("blockers")}")
        }
        return payload.optBoolean("ok")
    }

    private suspend fun clickStartIfPresentStable(): Boolean {
        val payload = evaluateJson(
            """
            (() => {
              const match = [
                '\u542f\u52a8',
                '\u5f00\u59cb',
                '\u8fde\u63a5',
                '\u8fdb\u5165',
                '\u7ee7\u7eed',
                '\u4e91\u7535\u8111',
              ];
              const visible = (node) => {
                if (!node) return false;
                const style = window.getComputedStyle(node);
                const rect = node.getBoundingClientRect();
                return style.visibility !== 'hidden' && style.display !== 'none' && Number(style.opacity || 1) > 0 && rect.width > 2 && rect.height > 2;
              };
              const nodes = Array.from(document.querySelectorAll('button, a, [role="button"], div, span')).filter(visible);
              for (const node of nodes) {
                const text = (node.innerText || node.textContent || '').trim();
                if (!text) continue;
                if (match.some((item) => text.includes(item))) {
                  node.click();
                  return JSON.stringify({ clicked: true, text });
                }
              }
              return JSON.stringify({ clicked: false });
            })();
            """.trimIndent()
        )
        return payload.optBoolean("clicked")
    }

    private suspend fun tryRevealLoginUiStable(): Boolean {
        val payload = evaluateJson(
            """
            (() => {
              const re = (source, flags = '') => new RegExp(source, flags);
              const loginPatterns = [
                re('\\u767b\\u5f55', 'i'),
                re('\\u9a8c\\u8bc1\\u7801', 'i'),
                re('\\u624b\\u673a\\u53f7', 'i'),
                re('\\u77ed\\u4fe1', 'i'),
                re('\\u83b7\\u53d6\\u9a8c\\u8bc1\\u7801', 'i'),
                re('\\u4e00\\u952e\\u767b\\u5f55', 'i'),
                re('\\u626b\\u7801\\u767b\\u5f55', 'i'),
                /login/i,
                /sign in/i,
                /sms/i,
                /verify/i,
                /code/i,
                /phone/i,
              ];
              const authSelectors = [
                '[class*="login"]',
                '[id*="login"]',
                '[class*="auth"]',
                '[id*="auth"]',
                '[class*="verify"]',
                '[id*="verify"]',
                '[class*="code"]',
                '[id*="code"]',
                '[class*="sms"]',
                '[id*="sms"]',
                'input[type="tel"]',
                'input[type="text"]',
                'input[type="number"]',
                'input[placeholder]',
              ];
              const textOf = (node) => (node?.innerText || node?.textContent || '').trim();
              const styleOf = (node) => window.getComputedStyle(node);
              const isVisible = (node) => {
                if (!node) return false;
                const style = styleOf(node);
                const rect = node.getBoundingClientRect();
                return style.visibility !== 'hidden' && style.display !== 'none' && Number(style.opacity || 1) > 0 && rect.width > 2 && rect.height > 2;
              };
              const matchLogin = (node) => {
                const marker = `${'$'}{node.id || ''} ${'$'}{node.className || ''} ${'$'}{textOf(node)}`;
                return loginPatterns.some((pattern) => pattern.test(marker));
              };
              const selectorNodes = authSelectors.flatMap((selector) => Array.from(document.querySelectorAll(selector)));
              const loginNodes = Array.from(new Set([
                ...selectorNodes,
                ...Array.from(document.querySelectorAll('body *')).filter(matchLogin),
              ]))
                .filter(isVisible)
                .slice(0, 80);
              const touched = [];
              const forceShow = (node) => {
                let current = node;
                for (let depth = 0; current && depth < 6; depth += 1, current = current.parentElement) {
                  try {
                    current.style.setProperty('display', 'block', 'important');
                    current.style.setProperty('visibility', 'visible', 'important');
                    current.style.setProperty('opacity', '1', 'important');
                    current.style.setProperty('z-index', '2147483646', 'important');
                    current.style.setProperty('pointer-events', 'auto', 'important');
                    current.style.setProperty('transform', 'none', 'important');
                    touched.push(`${'$'}{current.tagName}:${'$'}{current.id || ''}:${'$'}{current.className || ''}`);
                  } catch {}
                }
              };
              loginNodes.forEach(forceShow);
              loginNodes[0]?.scrollIntoView?.({ block: 'center', inline: 'center' });
              try { loginNodes.find((node) => /input|textarea/i.test(node.tagName))?.focus?.(); } catch {}

              const blockers = Array.from(document.querySelectorAll('body *'))
                .filter((node) => {
                  const tag = `${'$'}{node.id || ''} ${'$'}{node.className || ''}`.toLowerCase();
                  const style = styleOf(node);
                  const rect = node.getBoundingClientRect();
                  const fullScreenLike = rect.width >= window.innerWidth * 0.55 && rect.height >= window.innerHeight * 0.25;
                  return fullScreenLike &&
                    style.display !== 'none' &&
                    (
                      tag.includes('loading') ||
                      tag.includes('launch') ||
                      tag.includes('boot') ||
                      tag.includes('splash') ||
                      tag.includes('mask') ||
                      tag.includes('logo') ||
                      tag.includes('cover') ||
                      tag.includes('guide') ||
                      tag.includes('black') ||
                      tag.includes('shade') ||
                      tag.includes('player-loading')
                    );
                })
                .slice(0, 40);
              blockers.forEach((node) => {
                try {
                  node.style.setProperty('display', 'none', 'important');
                  node.style.setProperty('visibility', 'hidden', 'important');
                  node.style.setProperty('opacity', '0', 'important');
                } catch {}
              });

              return JSON.stringify({
                ok: loginNodes.length > 0 || blockers.length > 0,
                loginNodes: loginNodes.length,
                blockers: blockers.length,
                sample: touched.slice(0, 6),
              });
            })();
            """.trimIndent()
        )
        onConsoleLog("info", "reveal_login_ui loginNodes=${payload.optInt("loginNodes")} blockers=${payload.optInt("blockers")}")
        return payload.optBoolean("ok")
    }

    suspend fun waitForConnected(timeoutMs: Long, onProgress: (String) -> Unit): DetectedPageState {
        val started = System.currentTimeMillis()
        var last = detectPageState()
        var stableConnectedPolls = 0
        while (System.currentTimeMillis() - started < timeoutMs) {
            last = detectPageState()
            if (last.connected && !last.loginRequired) {
                stableConnectedPolls += 1
                if (stableConnectedPolls >= 2) {
                    return last
                }
            } else {
                stableConnectedPolls = 0
            }
            if (last.loginRequired) {
                tryRevealLoginUiStable()
            } else {
                clickStartIfPresentStable()
            }
            onProgress(if (last.loginRequired) "等待手动完成验证码登录" else "等待连接信号与远端画面就绪")
            delay(2500)
        }
        return last
    }

    suspend fun inspectRemoteControls(): RemoteControls {
        val payload = evaluateJson(
            """
            (() => {
              const client = window.LightPlayClient;
              const controlCenter = client?.controlCenter;
              const nativeInput = controlCenter?.nativeChineseInputControl;
              return JSON.stringify({
                hasClient: Boolean(client),
                hasSendCopyText: typeof client?.sendCopyText === 'function' || typeof client?.sendTextToClipboard === 'function',
                hasControlCenter: Boolean(controlCenter),
                hasDispatchKeyboard: typeof controlCenter?.dispatchKeyboard === 'function',
                hasSendControlMessage: typeof controlCenter?.sendControlMessage === 'function',
                hasNativeInput: Boolean(nativeInput),
              });
            })();
            """.trimIndent()
        )
        return RemoteControls(
            hasClient = payload.optBoolean("hasClient"),
            hasSendCopyText = payload.optBoolean("hasSendCopyText"),
            hasControlCenter = payload.optBoolean("hasControlCenter"),
            hasDispatchKeyboard = payload.optBoolean("hasDispatchKeyboard"),
            hasSendControlMessage = payload.optBoolean("hasSendControlMessage"),
            hasNativeInput = payload.optBoolean("hasNativeInput"),
        )
    }

    suspend fun waitForRemoteInputReady(timeoutMs: Long, onProgress: (String) -> Unit): RemoteControls? {
        val started = System.currentTimeMillis()
        var last: RemoteControls? = null
        while (System.currentTimeMillis() - started < timeoutMs) {
            detectPageState()
            last = inspectRemoteControls()
            if (last.hasClient && last.hasControlCenter && last.hasDispatchKeyboard && last.hasNativeInput) {
                return last
            }
            onProgress("已连上云电脑，等待远端输入控件就绪")
            delay(1500)
        }
        return last
    }

    suspend fun waitForRemoteProcess(processName: String, timeoutMs: Long, onProgress: (String) -> Unit = {}): Boolean {
        val started = System.currentTimeMillis()
        while (System.currentTimeMillis() - started < timeoutMs) {
            detectPageState()
            if (lastProcessList.any { it.equals(processName, ignoreCase = true) }) {
                return true
            }
            onProgress("等待远端进程启动: $processName")
            delay(1200)
        }
        return lastProcessList.any { it.equals(processName, ignoreCase = true) }
    }

    suspend fun launchRemoteShell(): Boolean {
        val ready = waitForRemoteInputReady(120_000) { onConsoleLog("info", it) }
        if (ready == null || !ready.hasDispatchKeyboard || !ready.hasNativeInput) {
            throw IllegalStateException("远端输入控件尚未就绪，暂时无法打开 PowerShell")
        }

        normalizeRemoteInputState()
        sendRemoteKeyChord(listOf("Control", "Escape"))
        delay(500)
        sendRemoteText("powershell", preferClipboard = false)
        delay(250)
        sendRemoteEnterKey()
        delay(1500)
        onConsoleLog("info", "remote_shell_launch_dispatched")
        return true
    }

    suspend fun dispatchRemotePowerShellScript(script: String) {
        val ready = waitForRemoteInputReady(120_000) { onConsoleLog("info", it) }
        if (ready == null || !ready.hasDispatchKeyboard || !ready.hasNativeInput || !ready.hasSendCopyText) {
            throw IllegalStateException("远端输入或剪贴板通道尚未就绪，暂时无法下发部署脚本")
        }

        normalizeRemoteInputState()
        writeRemoteClipboard(script)
        delay(180)
        sendRemoteKeyChord(listOf("Meta", "r"))
        delay(450)
        sendRemoteText(
            "powershell -NoProfile -ExecutionPolicy Bypass -WindowStyle Maximized -Command \"iex (Get-Clipboard -Raw)\"",
            preferClipboard = false,
        )
        delay(180)
        sendRemoteEnterKey()
        delay(1200)
        normalizeRemoteInputState()
        onConsoleLog("info", "remote_powershell_script_dispatched")
    }

    suspend fun typeRemoteCommand(command: String) {
        normalizeRemoteInputState()
        val normalized = command.replace("\r\n", "\n").replace('\r', '\n')
        if (normalized.contains('\n')) {
            normalized.split('\n').forEach { line ->
                if (line.isEmpty()) {
                    sendRemoteEnterKey()
                } else {
                    sendRemoteText(line, preferClipboard = false)
                    delay(120)
                    sendRemoteEnterKey()
                }
                delay(90)
            }
        } else {
            sendRemoteText(normalized, preferClipboard = false)
            delay(220)
            sendRemoteEnterKey()
        }
        normalizeRemoteInputState()
    }

    suspend fun heartbeat(): Boolean {
        val state = detectPageState()
        if (!state.connected) return false
        evaluateJson(
            """
            (() => {
              const surface = document.querySelector('canvas, video, iframe, [class*="game"], [id*="game"], [class*="player"], [id*="player"]') || document.body;
              const rect = surface.getBoundingClientRect();
              const points = [
                { x: rect.left + Math.min(18, rect.width / 8), y: rect.top + Math.min(18, rect.height / 8) },
                { x: rect.left + Math.min(24, rect.width / 6), y: rect.top + Math.min(24, rect.height / 6) },
              ];
              for (const point of points) {
                surface.dispatchEvent(new MouseEvent('mousemove', { bubbles: true, clientX: point.x, clientY: point.y }));
              }
              return JSON.stringify({ ok: true });
            })();
            """.trimIndent()
        )
        return true
    }

    private suspend fun writeRemoteClipboard(text: String) {
        val escaped = JSONObject.quote(text)
        val result = evaluateJson(
            """
            (() => {
              const value = $escaped;
              const client = window.LightPlayClient;
              const nativeInput = client?.controlCenter?.nativeChineseInputControl;
              if (typeof client?.sendTextToClipboard === 'function') {
                client.sendTextToClipboard(value);
                return JSON.stringify({ ok: true });
              }
              if (typeof client?.sendCopyText === 'function' && nativeInput) {
                nativeInput.show?.();
                nativeInput.focus?.();
                client.sendCopyText(value);
                nativeInput.blur?.();
                nativeInput.hide?.();
                return JSON.stringify({ ok: true });
              }
              return JSON.stringify({ ok: false });
            })();
            """.trimIndent()
        )
        if (!result.optBoolean("ok")) throw IllegalStateException("远端剪贴板通道不可用")
    }

    private suspend fun sendRemoteText(text: String, preferClipboard: Boolean) {
        val escaped = JSONObject.quote(text)
        if (preferClipboard) {
            val result = evaluateJson(
                """
                (() => {
                  const value = $escaped;
                  const client = window.LightPlayClient;
                  const nativeInput = client?.controlCenter?.nativeChineseInputControl;
                  if (typeof client?.sendTextToClipboard === 'function') {
                    client.sendTextToClipboard(value);
                    return JSON.stringify({ ok: true, mode: 'clipboard' });
                  }
                  if (typeof client?.sendCopyText === 'function' && nativeInput) {
                    nativeInput.show?.();
                    nativeInput.focus?.();
                    client.sendCopyText(value);
                    nativeInput.blur?.();
                    nativeInput.hide?.();
                    return JSON.stringify({ ok: true, mode: 'copy-text' });
                  }
                  return JSON.stringify({ ok: false });
                })();
                """.trimIndent()
            )
            if (!result.optBoolean("ok")) throw IllegalStateException("页面未暴露远端剪贴板通道")
            if (result.optString("mode") == "clipboard" || result.optString("mode") == "copy-text") {
                delay(180)
                sendRemoteKeyChord(listOf("Control", "v"))
            }
        } else {
            val result = evaluateJson(
                """
                (() => {
                  const value = $escaped;
                  const client = window.LightPlayClient;
                  const controlCenter = client?.controlCenter;
                  const nativeInput = controlCenter?.nativeChineseInputControl;
                  if (!nativeInput?.sendInputData || !nativeInput.el) {
                    return JSON.stringify({ ok: false });
                  }
                  client?.switchNativeChineseInput?.(true);
                  nativeInput.show?.();
                  nativeInput.focus?.();
                  nativeInput.el.value = value;
                  nativeInput.sendInputData();
                  nativeInput.blur?.();
                  nativeInput.hide?.();
                  client?.switchNativeChineseInput?.(false);
                  return JSON.stringify({ ok: true });
                })();
                """.trimIndent()
            )
            if (!result.optBoolean("ok")) throw IllegalStateException("页面未暴露原生远端输入通道")
        }
    }

    private suspend fun normalizeRemoteInputState() {
        evaluateJson(
            """
            (() => {
              const client = window.LightPlayClient;
              const controlCenter = client?.controlCenter;
              const nativeInput = controlCenter?.nativeChineseInputControl;
              const keyboard2Touch = controlCenter?.keyboard2TouchControl;
              try { document.activeElement?.blur?.(); } catch {}
              try { nativeInput?.endInputChinese?.(); } catch {}
              try { keyboard2Touch?.setInputMode?.(false); } catch {}
              try { keyboard2Touch?.hide?.(); } catch {}
              try { nativeInput?.setInputMode?.(false); } catch {}
              try { nativeInput?.el && (nativeInput.el.value = ''); } catch {}
              try { nativeInput?.blur?.(); } catch {}
              try { nativeInput?.hide?.(); } catch {}
              try { controlCenter?.dispatchKeyboard?.({ fun: 'clearKeyDownList', arg: [] }); } catch {}
              try { client?.switchNativeChineseInput?.(false); } catch {}
              try { controlCenter?.changeInputMethod?.('ENGLISH'); } catch {}
              return JSON.stringify({ ok: true });
            })();
            """.trimIndent()
        )
    }

    private suspend fun sendRemoteEnterKey() {
        dispatchKeySequence(listOf("Enter"))
    }

    suspend fun sendRemoteKeyChord(keys: List<String>) {
        dispatchKeySequence(keys)
    }

    private suspend fun dispatchKeySequence(keys: List<String>) {
        val json = JSONArray(keys).toString()
        val payload = evaluateJson(
            """
            (() => {
              const mapKey = (name) => {
                const key = String(name || '').toLowerCase();
                const table = {
                  enter: { key: 'Enter', keyCode: 13, code: 'Enter' },
                  escape: { key: 'Escape', keyCode: 27, code: 'Escape' },
                  esc: { key: 'Escape', keyCode: 27, code: 'Escape' },
                  control: { key: 'Control', keyCode: 17, code: 'ControlLeft' },
                  ctrl: { key: 'Control', keyCode: 17, code: 'ControlLeft' },
                  shift: { key: 'Shift', keyCode: 16, code: 'ShiftLeft' },
                  alt: { key: 'Alt', keyCode: 18, code: 'AltLeft' },
                  meta: { key: 'Meta', keyCode: 91, code: 'MetaLeft' },
                  v: { key: 'v', keyCode: 86, code: 'KeyV' },
                };
                return table[key] || null;
              };
              const input = $json;
              const controlCenter = window.LightPlayClient?.controlCenter;
              if (!controlCenter?.dispatchKeyboard) {
                return JSON.stringify({ ok: false });
              }
              const normalized = input.map(mapKey).filter(Boolean);
              if (!normalized.length) {
                return JSON.stringify({ ok: false });
              }
              const modifierState = {
                shiftKey: normalized.some((item) => item.key === 'Shift'),
                ctrlKey: normalized.some((item) => item.key === 'Control'),
                altKey: normalized.some((item) => item.key === 'Alt'),
                metaKey: normalized.some((item) => item.key === 'Meta'),
              };
              const baseTarget = document.querySelector('video, canvas, #LightPlayer') || document.body;
              const toEvent = (item) => ({
                keyCode: item.keyCode,
                key: item.key,
                code: item.code,
                shiftKey: modifierState.shiftKey,
                ctrlKey: modifierState.ctrlKey,
                altKey: modifierState.altKey,
                metaKey: modifierState.metaKey,
                target: baseTarget,
                preventDefault() {},
                stopPropagation() {},
              });
              for (const item of normalized) {
                controlCenter.dispatchKeyboard({ fun: '_keydown', arg: [toEvent(item)] });
              }
              for (const item of normalized.slice().reverse()) {
                controlCenter.dispatchKeyboard({ fun: '_keyup', arg: [toEvent(item)] });
              }
              try { controlCenter.dispatchKeyboard({ fun: 'clearKeyDownList', arg: [] }); } catch {}
              return JSON.stringify({ ok: true });
            })();
            """.trimIndent()
        )
        if (!payload.optBoolean("ok")) {
            throw IllegalStateException("页面未暴露远端按键控制能力")
        }
    }

    private suspend fun evaluateJson(script: String): JSONObject {
        val raw = evaluate(script)
        return JSONObject(raw.ifBlank { "{}" })
    }

    private suspend fun evaluate(script: String): String {
        val target = requireWebView()
        return withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { continuation ->
                target.evaluateJavascript(script, ValueCallback { raw ->
                    continuation.resume(raw?.trim('"')?.replace("\\\\", "\\")?.replace("\\n", "\n")?.replace("\\\"", "\"") ?: "")
                })
            }
        }
    }

    private fun requireWebView(): WebView {
        return webView ?: throw IllegalStateException("WebView 尚未附着到自动化引擎")
    }

    private fun rememberSignal(level: String, text: String) {
        consoleSignals += ConsoleSignal(System.currentTimeMillis(), level, text)
        if (consoleSignals.size > 500) {
            consoleSignals.removeFirst()
        }
    }

/*
    private fun recentConnectionEvidenceLegacy(windowMs: Long = 90_000L): List<String> {
        val cutoff = System.currentTimeMillis() - windowMs
        return consoleSignals
            .filter { it.timeMillis >= cutoff }
            .map { it.text }
            .filter {
                it.contains("Socket.IO 已连接", ignoreCase = true) ||
                    it.contains("hello_server", ignoreCase = true) ||
                    it.contains("game_start", ignoreCase = true) ||
                    it.contains("TTYY SDK初始化成功", ignoreCase = true) ||
                    it.contains("TTYY 资源分配成功", ignoreCase = true) ||
                    it.contains("TTYY 游戏开始渲染", ignoreCase = true) ||
                    it.contains("setGamestart true", ignoreCase = true) ||
                    it.contains("setIsGameStart true", ignoreCase = true)
            }
            .takeLast(8)
    }

    private fun recentLoginEvidenceLegacy(windowMs: Long = 90_000L): List<String> {
        val cutoff = System.currentTimeMillis() - windowMs
        return consoleSignals
            .filter { it.timeMillis >= cutoff }
            .map { it.text }
            .filter {
                it.contains("登录弹窗", ignoreCase = true) ||
                    it.contains("验证码", ignoreCase = true) ||
                    it.contains("短信", ignoreCase = true) ||
                    it.contains("手机号", ignoreCase = true) ||
                    it.contains("login", ignoreCase = true) ||
                    it.contains("sign in", ignoreCase = true)
            }
            .takeLast(8)
    }

    private fun recentConnectionEvidence(windowMs: Long = 90_000L): TimedSignal {
        val cutoff = System.currentTimeMillis() - windowMs
        val matches = consoleSignals
            .filter { it.timeMillis >= cutoff }
            .filter {
                it.text.contains("Socket.IO 宸茶繛鎺?, ignoreCase = true) ||
                    it.text.contains("hello_server", ignoreCase = true) ||
                    it.text.contains("game_start", ignoreCase = true) ||
                    it.text.contains("TTYY SDK鍒濆鍖栨垚鍔?, ignoreCase = true) ||
                    it.text.contains("TTYY 璧勬簮鍒嗛厤鎴愬姛", ignoreCase = true) ||
                    it.text.contains("TTYY 娓告垙寮€濮嬫覆鏌?, ignoreCase = true) ||
                    it.text.contains("setGamestart true", ignoreCase = true) ||
                    it.text.contains("setIsGameStart true", ignoreCase = true)
            }
            .takeLast(8)
        return TimedSignal(
            timeMillis = matches.maxOfOrNull { it.timeMillis } ?: 0L,
            texts = matches.map { it.text },
        )
    }

    private fun recentDisconnectEvidence(windowMs: Long = 90_000L): TimedSignal {
        val cutoff = System.currentTimeMillis() - windowMs
        val matches = consoleSignals
            .filter { it.timeMillis >= cutoff }
            .filter {
                it.text.contains("TTYY 串流已经断开", ignoreCase = true) ||
                    it.text.contains("TTYY 游戏已结束", ignoreCase = true) ||
                    it.text.contains("系统异常", ignoreCase = true) ||
                    it.text.contains("handleReleasedListener", ignoreCase = true) ||
                    it.text.contains("主动关闭WebSocket", ignoreCase = true) ||
                    it.text.contains("999999超时", ignoreCase = true)
            }
            .takeLast(8)
        return TimedSignal(
            timeMillis = matches.maxOfOrNull { it.timeMillis } ?: 0L,
            texts = matches.map { it.text },
        )
    }

    private fun recentLoginEvidence(windowMs: Long = 90_000L): TimedSignal {
        val cutoff = System.currentTimeMillis() - windowMs
        val matches = consoleSignals
            .filter { it.timeMillis >= cutoff }
            .filter {
                it.text.contains("鐧诲綍寮圭獥", ignoreCase = true) ||
                    it.text.contains("楠岃瘉鐮?, ignoreCase = true) ||
                    it.text.contains("鐭俊", ignoreCase = true) ||
                    it.text.contains("鎵嬫満鍙?, ignoreCase = true) ||
                    it.text.contains("login", ignoreCase = true) ||
                    it.text.contains("sign in", ignoreCase = true)
            }
            .takeLast(8)
        return TimedSignal(
            timeMillis = matches.maxOfOrNull { it.timeMillis } ?: 0L,
            texts = matches.map { it.text },
        )
    }

*/

    private fun recentConnectionEvidence(windowMs: Long = 90_000L): TimedSignal {
        val cutoff = System.currentTimeMillis() - windowMs
        val matches = consoleSignals
            .filter { it.timeMillis >= cutoff }
            .filter {
                it.text.contains("Socket.IO 已连接", ignoreCase = true) ||
                    it.text.contains("hello_server", ignoreCase = true) ||
                    it.text.contains("game_start", ignoreCase = true) ||
                    it.text.contains("TTYY SDK初始化成功", ignoreCase = true) ||
                    it.text.contains("TTYY 资源分配成功", ignoreCase = true) ||
                    it.text.contains("TTYY 游戏画面出现成功", ignoreCase = true) ||
                    it.text.contains("setGamestart true", ignoreCase = true) ||
                    it.text.contains("setIsGameStart true", ignoreCase = true)
            }
            .takeLast(8)
        return TimedSignal(
            timeMillis = matches.maxOfOrNull { it.timeMillis } ?: 0L,
            texts = matches.map { it.text },
        )
    }

    private fun recentDisconnectEvidence(windowMs: Long = 90_000L): TimedSignal {
        val cutoff = System.currentTimeMillis() - windowMs
        val matches = consoleSignals
            .filter { it.timeMillis >= cutoff }
            .filter {
                it.text.contains("TTYY 串流已经断开", ignoreCase = true) ||
                    it.text.contains("TTYY 游戏已结束", ignoreCase = true) ||
                    it.text.contains("系统异常", ignoreCase = true) ||
                    it.text.contains("handleReleasedListener", ignoreCase = true) ||
                    it.text.contains("主动关闭WebSocket", ignoreCase = true) ||
                    it.text.contains("999999超时", ignoreCase = true)
            }
            .takeLast(8)
        return TimedSignal(
            timeMillis = matches.maxOfOrNull { it.timeMillis } ?: 0L,
            texts = matches.map { it.text },
        )
    }

    private fun recentLoginEvidence(windowMs: Long = 90_000L): TimedSignal {
        val cutoff = System.currentTimeMillis() - windowMs
        val matches = consoleSignals
            .filter { it.timeMillis >= cutoff }
            .filter {
                it.text.contains("登录弹窗", ignoreCase = true) ||
                    it.text.contains("验证码", ignoreCase = true) ||
                    it.text.contains("短信", ignoreCase = true) ||
                    it.text.contains("手机号", ignoreCase = true) ||
                    it.text.contains("login", ignoreCase = true) ||
                    it.text.contains("sign in", ignoreCase = true)
            }
            .takeLast(8)
        return TimedSignal(
            timeMillis = matches.maxOfOrNull { it.timeMillis } ?: 0L,
            texts = matches.map { it.text },
        )
    }

    private fun desktopChromeUserAgent(): String {
        return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36"
    }

    private fun isPageStillSettling(windowMs: Long = 8_000L): Boolean {
        if (lastPageLoadAtMillis <= 0L) return false
        return System.currentTimeMillis() - lastPageLoadAtMillis < windowMs
    }

    private fun parseProcessCandidates(message: String): List<String>? {
        val regex = Regex("""([A-Za-z0-9._-]+\.exe)""")
        val matches = regex.findAll(message).map { it.value }.toList().distinct()
        return if (matches.isEmpty()) null else matches
    }
}
