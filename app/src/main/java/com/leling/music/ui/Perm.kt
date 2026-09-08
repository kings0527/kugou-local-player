package com.leling.music.ui

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.leling.music.Song

fun hasAudioPermission(ctx: Context): Boolean {
    val perm = if (Build.VERSION.SDK_INT >= 33)
        android.Manifest.permission.READ_MEDIA_AUDIO
    else
        android.Manifest.permission.READ_EXTERNAL_STORAGE
    return ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED
}

fun audioPermissionArray(): Array<String> =
    if (Build.VERSION.SDK_INT >= 33)
        arrayOf(android.Manifest.permission.READ_MEDIA_AUDIO)
    else
        arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)

fun formatSongSub(s: Song): String {
    val artist = s.artist.ifBlank { "未知歌手" }
    val album = s.album.ifBlank { "未知专辑" }
    return if (artist == album || album == "未知专辑") artist else "$artist · $album"
}
