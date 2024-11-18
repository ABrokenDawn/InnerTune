package com.zionhuang.music.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat.startActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavController
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionState
import com.google.accompanist.permissions.PermissionStatus
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.zionhuang.music.App
import com.zionhuang.music.BuildConfig

@OptIn(ExperimentalPermissionsApi::class)
@SuppressLint("SetJavaScriptEnabled", "PermissionLaunchedDuringComposition")
@Composable
fun LocalMusicScreen(navController: NavController) {

    val lifecycleOwner = LocalLifecycleOwner.current
    val permission = if (Build.VERSION.SDK_INT>=Build.VERSION_CODES.TIRAMISU){
        Manifest.permission.READ_MEDIA_AUDIO
    }else{
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
    val permissionState = rememberPermissionState(permission = permission)

    DisposableEffect(key1 = lifecycleOwner, effect = {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    permissionState.launchPermissionRequest()
                }
                else->{}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    })
    when(permissionState.status) {
        is PermissionStatus.Granted ->{
            Content()
        }
        is PermissionStatus.Denied ->{
            PermissionRationale(permissionState)

        }

    }
}


@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun PermissionRationale(state: PermissionState) {
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        val context = LocalContext.current

        Column(Modifier.padding(vertical = 120.dp, horizontal = 16.dp)) {
//            Icon(Icons.Rounded.Camera, contentDescription = null, tint = MaterialTheme.colors.onBackground)
            Spacer(Modifier.height(8.dp))
            Text("Camera permission required", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(4.dp))
            Text("This is required in order for the app to take pictures")
            Button(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                onClick = {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", context.packageName, null)
                    }
                    context.startActivity(intent)
                }
            ) {
                Text("Go to settings")
            }
        }

    }
}

@Composable
fun Content() {
    val context = LocalContext.current
    scanLocalMusic(context)
}

private fun scanLocalMusic(context: Context){
    val projection = arrayOf(
        MediaStore.Audio.Media._ID,          // 音频文件的唯一 ID
        MediaStore.Audio.Media.DISPLAY_NAME, // 文件名
        MediaStore.Audio.Media.ARTIST,       // 艺术家
        MediaStore.Audio.Media.ALBUM,        // 专辑名
        MediaStore.Audio.Media.DURATION,     // 时长
        MediaStore.Audio.Media.DATE_ADDED    // 添加日期
    )

    val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0" // 只筛选音乐文件
    val sortOrder = "${MediaStore.Audio.Media.DATE_ADDED} DESC" // 按日期降序排列

    context.applicationContext.contentResolver.query(
        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
        projection,
        selection,
        null,
        sortOrder
    )?.use { cursor ->
        while (cursor.moveToNext()) {
            val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID))
            val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
            val displayName = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME))
            val artist = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST))
            val album = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM))
            val duration = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION))
            val dateAdded = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED))

            // 输出音频文件的信息
            println("Name: $displayName, Artist: $artist, Album: $album, Duration: $duration ms, Uri: $uri")
        }
    }

}