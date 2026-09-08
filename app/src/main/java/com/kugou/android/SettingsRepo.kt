package com.kugou.android

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "leling_settings")

/** 应用设置仓库（DataStore，异步持久化，不阻塞主线程） */
class SettingsRepo private constructor(private val context: Context) {

    companion object {
        @Volatile
        private var instance: SettingsRepo? = null
        fun get(ctx: Context): SettingsRepo =
            instance ?: synchronized(this) {
                instance ?: SettingsRepo(ctx.applicationContext).also { instance = it }
            }
    }

    data class Settings(
        val eqEnabled: Boolean = false,
        val eqPreset: String = "标准",
        val repeatMode: Int = 0,          // 0=顺序到尾停 1=列表循环 2=单曲循环
        val shuffle: Boolean = false,
        val ignoredDirs: Set<String> = emptySet(),   // 黑名单：忽略的目录（相对路径，无首尾斜杠）
        val ignoreSystemDirs: Boolean = true,        // 忽略 闹钟/通知/铃声/录音 目录
        val lastScanAt: Long = 0L,
    )

    private val kEqEnabled = booleanPreferencesKey("eq_enabled")
    private val kEqPreset = stringPreferencesKey("eq_preset")
    private val kRepeat = intPreferencesKey("repeat_mode")
    private val kShuffle = booleanPreferencesKey("shuffle")
    private val kIgnored = stringSetPreferencesKey("ignored_dirs")
    private val kIgnoreSys = booleanPreferencesKey("ignore_system_dirs")
    private val kScanAt = longPreferencesKey("last_scan_at")

    val settings: Flow<Settings> = context.dataStore.data.map { p ->
        Settings(
            eqEnabled = p[kEqEnabled] ?: false,
            eqPreset = p[kEqPreset] ?: "标准",
            repeatMode = p[kRepeat] ?: 0,
            shuffle = p[kShuffle] ?: false,
            ignoredDirs = p[kIgnored] ?: emptySet(),
            ignoreSystemDirs = p[kIgnoreSys] ?: true,
            lastScanAt = p[kScanAt] ?: 0L,
        )
    }

    suspend fun snapshot(): Settings = settings.first()

    suspend fun setEqEnabled(on: Boolean) = context.dataStore.edit { it[kEqEnabled] = on }
    suspend fun setEqPreset(name: String) = context.dataStore.edit { it[kEqPreset] = name }
    suspend fun setRepeatMode(mode: Int) = context.dataStore.edit { it[kRepeat] = mode }
    suspend fun setShuffle(on: Boolean) = context.dataStore.edit { it[kShuffle] = on }
    suspend fun setIgnoredDirs(dirs: Set<String>) = context.dataStore.edit { it[kIgnored] = dirs }
    suspend fun addIgnoredDir(dir: String) = context.dataStore.edit {
        it[kIgnored] = (it[kIgnored] ?: emptySet()) + dir
    }
    suspend fun removeIgnoredDir(dir: String) = context.dataStore.edit {
        it[kIgnored] = (it[kIgnored] ?: emptySet()) - dir
    }
    suspend fun setIgnoreSystemDirs(on: Boolean) = context.dataStore.edit { it[kIgnoreSys] = on }
    suspend fun touchScan() = context.dataStore.edit { it[kScanAt] = System.currentTimeMillis() }
}

/** 系统音频目录（闹钟/通知/铃声/录音），默认自动忽略 */
val SYSTEM_NOISE_DIRS = listOf("Alarms", "Notifications", "Ringtones", "Recordings", "CallRecord", "call_recordings")

/** 目录匹配工具：dir 是相对路径（如 "Music/周杰伦"），rule 可能是它的任意祖先目录 */
fun dirMatches(dir: String?, rule: String): Boolean {
    if (dir.isNullOrEmpty()) return false
    val r = rule.trim('/')
    return dir == r || dir.startsWith("$r/")
}
