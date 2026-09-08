package com.leling.music

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 一首歌（来自 MediaStore） */
data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val durationMs: Long,
    val size: Long,
    val dir: String,          // 相对目录 如 "Music/周杰伦"（无首尾斜杠，顶层目录直接 "Music"）
    val uri: Uri,
)

/** 歌曲所属目录节点（浏览用） */
data class DirNode(
    val dir: String,       // 相对完整目录
    val name: String,      // 显示名（最后一段，顶层就是整段）
    val depth: Int,        // 层级 0=顶层
    val count: Int,        // 直接歌曲数（不含子目录）
    val total: Int,        // 含子目录
)

/** MediaStore 扫描（全部音频，不带过滤），查询在 IO 线程执行 */
suspend fun scanAllSongs(ctx: Context): List<Song> = withContext(Dispatchers.IO) {
    val result = ArrayList<Song>()
    val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    val projection = arrayOf(
        MediaStore.Audio.Media._ID,
        MediaStore.Audio.Media.TITLE,
        MediaStore.Audio.Media.ARTIST,
        MediaStore.Audio.Media.ALBUM,
        MediaStore.Audio.Media.ALBUM_ID,
        MediaStore.Audio.Media.DURATION,
        MediaStore.Audio.Media.SIZE,
        MediaStore.Audio.Media.RELATIVE_PATH,
    )
    ctx.contentResolver.query(uri, projection, null, null, null)?.use { c ->
        val iId = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
        val iTitle = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
        val iArtist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
        val iAlbum = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
        val iAlbumId = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
        val iDur = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
        val iSize = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
        val iRel = c.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)
        while (c.moveToNext()) {
            val rel = if (iRel >= 0) c.getString(iRel) else null
            val dir = rel?.trim('/')?.substringBeforeLast('/')?.takeIf { it.isNotEmpty() } ?: ""
            result.add(
                Song(
                    id = c.getLong(iId),
                    title = c.getString(iTitle) ?: "未知",
                    artist = c.getString(iArtist) ?: "未知歌手",
                    album = c.getString(iAlbum) ?: "未知专辑",
                    albumId = c.getLong(iAlbumId),
                    durationMs = c.getLong(iDur),
                    size = c.getLong(iSize),
                    dir = dir,
                    uri = Uri.withAppendedPath(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        c.getLong(iId).toString()
                    ),
                )
            )
        }
    }
    result
}

/**
 * 依据设置过滤：白名单模式=只听勾选目录；
 * 否则去掉黑名单目录（含子目录）+ 系统噪音目录。
 */
fun filterSongs(
    songs: List<Song>,
    ignoredDirs: Set<String>,
    whitelistMode: Boolean,
    whitelistDirs: Set<String>,
    ignoreSystemDirs: Boolean,
): List<Song> = songs.filter { s ->
    if (whitelistMode) {
        whitelistDirs.any { rule -> dirMatches(s.dir, rule) }
    } else {
        val black = ignoredDirs
        black.none { rule -> dirMatches(s.dir, rule) } &&
            !(ignoreSystemDirs && SYSTEM_NOISE_DIRS.any { rule -> dirMatches(s.dir, rule) })
    }
}

/** 由完整歌曲列表构建目录树（所有出现过的目录 + 其祖先） */
fun buildDirTree(songs: List<Song>): List<DirNode> {
    val countByDir = HashMap<String, Int>()
    val allDirs = HashSet<String>()
    for (s in songs) {
        if (s.dir.isEmpty()) continue
        countByDir.merge(s.dir, 1) { a, b -> a + b }
        var d = s.dir
        while (d.isNotEmpty()) {
            allDirs.add(d)
            d = d.substringBeforeLast('/', "")
        }
    }
    return allDirs.map { dir ->
        val name = dir.substringAfterLast('/')
        DirNode(
            dir = dir,
            name = name,
            depth = dir.count { it == '/' },
            count = countByDir[dir] ?: 0,
            total = countByDir.entries.filter { it.key == dir || it.key.startsWith("$dir/") }
                .sumOf { it.value },
        )
    }.sortedWith(compareBy({ it.depth }, { it.name }))
}

/** 取目录（含子目录）下歌曲 */
fun songsInDir(songs: List<Song>, dir: String): List<Song> =
    if (dir.isEmpty()) songs else songs.filter { dirMatches(it.dir, dir) }
