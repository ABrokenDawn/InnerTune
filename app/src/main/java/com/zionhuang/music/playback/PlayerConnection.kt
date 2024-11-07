package com.zionhuang.music.playback

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM
import androidx.media3.common.Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM
import androidx.media3.common.Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM
import androidx.media3.common.Player.REPEAT_MODE_OFF
import androidx.media3.common.Player.STATE_ENDED
import androidx.media3.common.Timeline
import com.zionhuang.music.constants.TranslateLyricsKey
import com.zionhuang.music.db.MusicDatabase
import com.zionhuang.music.db.entities.LyricsEntity.Companion.LYRICS_NOT_FOUND
import com.zionhuang.music.extensions.currentMetadata
import com.zionhuang.music.extensions.getCurrentQueueIndex
import com.zionhuang.music.extensions.getQueueWindows
import com.zionhuang.music.extensions.metadata
import com.zionhuang.music.playback.MusicService.MusicBinder
import com.zionhuang.music.playback.queues.Queue
import com.zionhuang.music.utils.TranslationHelper
import com.zionhuang.music.utils.dataStore
import com.zionhuang.music.utils.reportException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/**
 * 管理与媒体播放器相关的各种状态与操作
 * 与音乐播放服务进行交互
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerConnection(
    context: Context,
    binder: MusicBinder,
    val database: MusicDatabase,
    scope: CoroutineScope,
) : Player.Listener {
    val service = binder.service
    val player = service.player

    //播放器的当前状态
    val playbackState = MutableStateFlow(player.playbackState)
    //是否准备好了就立刻播放
    private val playWhenReady = MutableStateFlow(player.playWhenReady)

    //根据 playbackState 和 playWhenReady 综合判断是否曾在播放
    //stateIn接受一个协程范围（scope）作为参数，这使得isPlaying这个Flow的生命周期与这个协程范围绑定
    val isPlaying = combine(playbackState, playWhenReady) { playbackState, playWhenReady ->
        playWhenReady && playbackState != STATE_ENDED
    }.stateIn(scope, SharingStarted.Lazily, player.playWhenReady && player.playbackState != STATE_ENDED)

    //当前加载的媒体元数据
    val mediaMetadata = MutableStateFlow(player.currentMetadata)

    val currentSong = mediaMetadata.flatMapLatest {
        database.song(it?.id)
    }
    //是否翻译歌词
    val translating = MutableStateFlow(false)

    //当前歌词
    val currentLyrics = combine(
        context.dataStore.data.map {
            it[TranslateLyricsKey] ?: false
        }.distinctUntilChanged(),
        mediaMetadata.flatMapLatest { mediaMetadata ->
            database.lyrics(mediaMetadata?.id)
        }
    ) { translateEnabled, lyrics ->
        if (!translateEnabled || lyrics == null || lyrics.lyrics == LYRICS_NOT_FOUND) return@combine lyrics
        translating.value = true
        try {
            TranslationHelper.translate(lyrics)
        } catch (e: Exception) {
            reportException(e)
            lyrics
        }.also {
            translating.value = false
        }
    }.stateIn(scope, SharingStarted.Lazily, null)

    val currentFormat = mediaMetadata.flatMapLatest { mediaMetadata ->
        database.format(mediaMetadata?.id)
    }

    //队列标题
    val queueTitle = MutableStateFlow<String?>(null)
    //队列窗口
    val queueWindows = MutableStateFlow<List<Timeline.Window>>(emptyList())
    //当前媒体下标
    val currentMediaItemIndex = MutableStateFlow(-1)
    //当前窗口下标
    val currentWindowIndex = MutableStateFlow(-1)
    //是否乱序模式
    val shuffleModeEnabled = MutableStateFlow(false)
    //重复模式
    val repeatMode = MutableStateFlow(REPEAT_MODE_OFF)

    //是否可以跳到上一个
    val canSkipPrevious = MutableStateFlow(true)
    //是否可以跳到下一个
    val canSkipNext = MutableStateFlow(true)

    val error = MutableStateFlow<PlaybackException?>(null)

    //初始化状态参数
    init {
        player.addListener(this)

        playbackState.value = player.playbackState
        playWhenReady.value = player.playWhenReady
        mediaMetadata.value = player.currentMetadata
        queueTitle.value = service.queueTitle
        queueWindows.value = player.getQueueWindows()
        currentWindowIndex.value = player.getCurrentQueueIndex()
        currentMediaItemIndex.value = player.currentMediaItemIndex
        shuffleModeEnabled.value = player.shuffleModeEnabled
        repeatMode.value = player.repeatMode
    }

    //播放整个列表
    fun playQueue(queue: Queue) {
        service.playQueue(queue)
    }

    //稍后播放
    fun playNext(item: MediaItem) = playNext(listOf(item))
    fun playNext(items: List<MediaItem>) {
        service.playNext(items)
    }

    //加入到播放列表
    fun addToQueue(item: MediaItem) = addToQueue(listOf(item))
    fun addToQueue(items: List<MediaItem>) {
        service.addToQueue(items)
    }

    //加入/移除喜欢
    fun toggleLike() {
        service.toggleLike()
    }

    //加入/移除媒体库
    fun toggleLibrary() {
        service.toggleLibrary()
    }

    //下一首
    fun seekToNext() {
        player.seekToNext()
        player.prepare()
        player.playWhenReady = true
    }

    //上一首
    fun seekToPrevious() {
        player.seekToPrevious()
        player.prepare()
        player.playWhenReady = true
    }

    //当播放状态改变
    override fun onPlaybackStateChanged(state: Int) {
        playbackState.value = state
        error.value = player.playerError
    }

    //当"准备好就播放"改变
    override fun onPlayWhenReadyChanged(newPlayWhenReady: Boolean, reason: Int) {
        playWhenReady.value = newPlayWhenReady
    }

    //当播放器在播放不同媒体项（如不同的视频、音频文件等）之间发生转换
    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        mediaMetadata.value = mediaItem?.metadata
        currentMediaItemIndex.value = player.currentMediaItemIndex
        currentWindowIndex.value = player.getCurrentQueueIndex()
        updateCanSkipPreviousAndNext()
    }

    //当播放时间表（Timeline）变化
    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
        queueWindows.value = player.getQueueWindows()
        queueTitle.value = service.queueTitle
        currentMediaItemIndex.value = player.currentMediaItemIndex
        currentWindowIndex.value = player.getCurrentQueueIndex()
        updateCanSkipPreviousAndNext()
    }

    //当乱序播放模式状态改变
    override fun onShuffleModeEnabledChanged(enabled: Boolean) {
        shuffleModeEnabled.value = enabled
        queueWindows.value = player.getQueueWindows()
        currentWindowIndex.value = player.getCurrentQueueIndex()
        updateCanSkipPreviousAndNext()
    }

    //当重复播放模式状态改变
    override fun onRepeatModeChanged(mode: Int) {
        repeatMode.value = mode
        updateCanSkipPreviousAndNext()
    }

    override fun onPlayerErrorChanged(playbackError: PlaybackException?) {
        if (playbackError != null) {
            reportException(playbackError)
        }
        error.value = playbackError
    }

    //更新是否可以跳到上一首或下一首的状态
    private fun updateCanSkipPreviousAndNext() {
        if (!player.currentTimeline.isEmpty) {
            val window = player.currentTimeline.getWindow(player.currentMediaItemIndex, Timeline.Window())
            canSkipPrevious.value = player.isCommandAvailable(COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
                    || !window.isLive()
                    || player.isCommandAvailable(COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
            canSkipNext.value = window.isLive() && window.isDynamic
                    || player.isCommandAvailable(COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
        } else {
            canSkipPrevious.value = false
            canSkipNext.value = false
        }
    }

    fun dispose() {
        player.removeListener(this)
    }
}
