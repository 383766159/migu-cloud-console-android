package com.migu.cloudconsole

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "migu_settings")

class SettingsRepository(private val context: Context) {
    private object Keys {
        val cloudPcUrl = stringPreferencesKey("cloud_pc_url")
        val heartbeatMinutes = intPreferencesKey("heartbeat_minutes")
        val reconnectMinutes = intPreferencesKey("reconnect_minutes")
        val modelVariant = stringPreferencesKey("model_variant")
        val port = intPreferencesKey("port")
        val remoteShellReadyDelayMs = intPreferencesKey("remote_shell_ready_delay_ms")
        val tunnelMode = stringPreferencesKey("tunnel_mode")
        val tunnelHostname = stringPreferencesKey("tunnel_hostname")
        val tunnelToken = stringPreferencesKey("tunnel_token")
        val cloudflaredUrl = stringPreferencesKey("cloudflared_url")
        val bootstrapRepoBaseUrl = stringPreferencesKey("bootstrap_repo_base_url")
    }

    val settingsFlow: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            cloudPcUrl = prefs[Keys.cloudPcUrl] ?: AppSettings().cloudPcUrl,
            heartbeatMinutes = prefs[Keys.heartbeatMinutes] ?: AppSettings().heartbeatMinutes,
            reconnectMinutes = prefs[Keys.reconnectMinutes] ?: AppSettings().reconnectMinutes,
            modelVariant = prefs[Keys.modelVariant] ?: AppSettings().modelVariant,
            port = prefs[Keys.port] ?: AppSettings().port,
            remoteShellReadyDelayMs = prefs[Keys.remoteShellReadyDelayMs] ?: AppSettings().remoteShellReadyDelayMs,
            tunnelMode = prefs[Keys.tunnelMode] ?: AppSettings().tunnelMode,
            tunnelHostname = prefs[Keys.tunnelHostname] ?: AppSettings().tunnelHostname,
            tunnelToken = prefs[Keys.tunnelToken] ?: AppSettings().tunnelToken,
            cloudflaredUrl = prefs[Keys.cloudflaredUrl] ?: AppSettings().cloudflaredUrl,
            bootstrapRepoBaseUrl = prefs[Keys.bootstrapRepoBaseUrl] ?: AppSettings().bootstrapRepoBaseUrl,
        )
    }

    suspend fun save(settings: AppSettings) {
        context.dataStore.edit { prefs ->
            prefs[Keys.cloudPcUrl] = settings.cloudPcUrl
            prefs[Keys.heartbeatMinutes] = settings.heartbeatMinutes
            prefs[Keys.reconnectMinutes] = settings.reconnectMinutes
            prefs[Keys.modelVariant] = settings.modelVariant
            prefs[Keys.port] = settings.port
            prefs[Keys.remoteShellReadyDelayMs] = settings.remoteShellReadyDelayMs
            prefs[Keys.tunnelMode] = settings.tunnelMode
            prefs[Keys.tunnelHostname] = settings.tunnelHostname
            prefs[Keys.tunnelToken] = settings.tunnelToken
            prefs[Keys.cloudflaredUrl] = settings.cloudflaredUrl
            prefs[Keys.bootstrapRepoBaseUrl] = settings.bootstrapRepoBaseUrl
        }
    }
}
