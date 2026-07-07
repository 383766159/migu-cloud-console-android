package com.migu.cloudconsole

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val engine = (application as MiguApplication).automationEngine
    val state: StateFlow<AppState> = engine.state

    fun attachBridge(bridge: MiguWebViewBridge) = engine.attachBridge(bridge)
    fun detachBridge(bridge: MiguWebViewBridge) = engine.detachBridge(bridge)
    fun handleConsole(level: String, message: String) = engine.handleConsole(level, message)
    fun handleProcessList(processes: List<String>) = engine.handleProcessList(processes)
    fun saveSettings(settings: AppSettings) = engine.saveSettings(settings)
    fun startServiceMode() = engine.startBackgroundMode()
    fun stopServiceMode() = engine.stop()

    fun openCloudConsole() = engine.openCloudConsole()
    fun connectCloudConsole() = engine.connectCloudConsole()
    fun acknowledgeManualLogin() = engine.acknowledgeManualLogin()
    fun openRemoteShell() = engine.openRemoteShell()
    fun deployModel() = engine.deployModel()
    fun probeApi() = engine.probeModelApi()
    fun heartbeat() = engine.heartbeat()
    fun recover() = engine.recover()
}
