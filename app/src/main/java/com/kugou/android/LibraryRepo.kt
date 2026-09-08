package com.kugou.android

import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 歌曲库内存缓存：扫描一次，过滤多次。
 * 过滤规则来自 SettingsRepo（忽略目录/只听模式/系统目录）。
 * 所有 onDone 回调保证回到主线程（UI 安全）。
 */
object LibraryRepo {
    @Volatile var raw: List<Song> = emptyList()
    @Volatile var filtered: List<Song> = emptyList()
    @Volatile var dirs: List<DirNode> = emptyList()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val main = Handler(Looper.getMainLooper())

    val filteredSize: Int get() = filtered.size

    /** 全量扫描（MediaStore）+ 按当前设置过滤，完成回调在主线程 */
    fun rescan(ctx: Context, onDone: (() -> Unit)? = null) {
        scope.launch {
            try {
                raw = scanAllSongs(ctx)
                applyFilterNow(ctx)
                PlayerHub.refreshLibrary(filtered)
                main.post { onDone?.invoke() }
            } catch (t: Throwable) {
                main.post { onDone?.invoke() }
            }
        }
    }

    /** 仅用当前设置重新过滤（目录规则变化后调用），完成回调在主线程 */
    fun applyFilter(ctx: Context, onDone: (() -> Unit)? = null) {
        scope.launch {
            try {
                applyFilterNow(ctx)
                main.post { onDone?.invoke() }
            } catch (t: Throwable) {
                main.post { onDone?.invoke() }
            }
        }
    }

    private suspend fun applyFilterNow(ctx: Context) {
        val s = SettingsRepo.get(ctx).snapshot()
        filtered = filterSongs(raw, s.ignoredDirs, s.ignoreSystemDirs)
        dirs = buildDirTree(filtered)
    }
}
