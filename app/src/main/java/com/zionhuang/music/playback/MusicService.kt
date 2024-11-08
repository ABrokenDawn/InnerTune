package com.zionhuang.music.playback

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.database.SQLException
import android.media.audiofx.AudioEffect
import android.net.ConnectivityManager
import android.os.Binder
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import androidx.datastore.preferences.core.edit
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Player.EVENT_POSITION_DISCONTINUITY
import androidx.media3.common.Player.EVENT_TIMELINE_CHANGED
import androidx.media3.common.Player.REPEAT_MODE_ALL
import androidx.media3.common.Player.REPEAT_MODE_OFF
import androidx.media3.common.Player.REPEAT_MODE_ONE
import androidx.media3.common.Player.STATE_IDLE
import androidx.media3.common.Timeline
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.analytics.PlaybackStats
import androidx.media3.exoplayer.analytics.PlaybackStatsListener
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.mp4.FragmentedMp4Extractor
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaController
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.SongItem
import com.zionhuang.innertube.models.WatchEndpoint
import com.zionhuang.innertube.models.response.PlayerResponse
import com.zionhuang.music.MainActivity
import com.zionhuang.music.R
import com.zionhuang.music.constants.AudioNormalizationKey
import com.zionhuang.music.constants.AudioQuality
import com.zionhuang.music.constants.AudioQualityKey
import com.zionhuang.music.constants.AutoLoadMoreKey
import com.zionhuang.music.constants.AutoSkipNextOnErrorKey
import com.zionhuang.music.constants.DiscordTokenKey
import com.zionhuang.music.constants.EnableDiscordRPCKey
import com.zionhuang.music.constants.HideExplicitKey
import com.zionhuang.music.constants.MediaSessionConstants.CommandToggleLibrary
import com.zionhuang.music.constants.MediaSessionConstants.CommandToggleLike
import com.zionhuang.music.constants.MediaSessionConstants.CommandToggleRepeatMode
import com.zionhuang.music.constants.MediaSessionConstants.CommandToggleShuffle
import com.zionhuang.music.constants.PauseListenHistoryKey
import com.zionhuang.music.constants.PersistentQueueKey
import com.zionhuang.music.constants.PlayerVolumeKey
import com.zionhuang.music.constants.RepeatModeKey
import com.zionhuang.music.constants.ShowLyricsKey
import com.zionhuang.music.constants.SkipSilenceKey
import com.zionhuang.music.db.MusicDatabase
import com.zionhuang.music.db.entities.Event
import com.zionhuang.music.db.entities.FormatEntity
import com.zionhuang.music.db.entities.LyricsEntity
import com.zionhuang.music.db.entities.RelatedSongMap
import com.zionhuang.music.di.DownloadCache
import com.zionhuang.music.di.PlayerCache
import com.zionhuang.music.extensions.SilentHandler
import com.zionhuang.music.extensions.collect
import com.zionhuang.music.extensions.collectLatest
import com.zionhuang.music.extensions.currentMetadata
import com.zionhuang.music.extensions.findNextMediaItemById
import com.zionhuang.music.extensions.mediaItems
import com.zionhuang.music.extensions.metadata
import com.zionhuang.music.extensions.toMediaItem
import com.zionhuang.music.lyrics.LyricsHelper
import com.zionhuang.music.models.PersistQueue
import com.zionhuang.music.models.toMediaMetadata
import com.zionhuang.music.playback.queues.EmptyQueue
import com.zionhuang.music.playback.queues.ListQueue
import com.zionhuang.music.playback.queues.Queue
import com.zionhuang.music.playback.queues.YouTubeQueue
import com.zionhuang.music.playback.queues.filterExplicit
import com.zionhuang.music.utils.CoilBitmapLoader
import com.zionhuang.music.utils.DiscordRPC
import com.zionhuang.music.utils.dataStore
import com.zionhuang.music.utils.enumPreference
import com.zionhuang.music.utils.get
import com.zionhuang.music.utils.isInternetAvailable
import com.zionhuang.music.utils.reportException
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.LocalDateTime
import javax.inject.Inject
import kotlin.math.min
import kotlin.math.pow
import kotlin.time.Duration.Companion.seconds

/**
 * 音乐服务
 * 主要用于管理音乐播放，包括播放队列、与数据库交互、处理用户操作、设置通知等功能
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@AndroidEntryPoint
class MusicService : MediaLibraryService(),
    Player.Listener,
    PlaybackStatsListener.Callback {
    @Inject
    //用于与音乐数据库交互，存储和检索歌曲信息、格式信息、歌词等
    lateinit var database: MusicDatabase

    @Inject
    //用于获取歌词
    lateinit var lyricsHelper: LyricsHelper

    @Inject
    //用于处理与媒体库会话相关的回调操作
    lateinit var mediaLibrarySessionCallback: MediaLibrarySessionCallback

    private var scope = CoroutineScope(Dispatchers.Main) + Job()
    private val binder = MusicBinder()

    //用于检查网络连接状态
    private lateinit var connectivityManager: ConnectivityManager

    //音质
    private val audioQuality by enumPreference(this, AudioQualityKey, AudioQuality.AUTO)

    //当前队列
    private var currentQueue: Queue = EmptyQueue

    //队列标题
    var queueTitle: String? = null

    //当前媒体元数据
    val currentMediaMetadata = MutableStateFlow<com.zionhuang.music.models.MediaMetadata?>(null)

    //当前歌曲
    private val currentSong = currentMediaMetadata.flatMapLatest { mediaMetadata ->
        database.song(mediaMetadata?.id)
    }.stateIn(scope, SharingStarted.Lazily, null)

    //当前格式
    private val currentFormat = currentMediaMetadata.flatMapLatest { mediaMetadata ->
        database.format(mediaMetadata?.id)
    }

    //标准化因子
    private val normalizeFactor = MutableStateFlow(1f)
    //播放器音量
    val playerVolume = MutableStateFlow(dataStore.get(PlayerVolumeKey, 1f).coerceIn(0f, 1f))

    //用于实现睡眠定时功能
    lateinit var sleepTimer: SleepTimer

    @Inject
    @PlayerCache
    //用于缓存音乐数据
    lateinit var playerCache: SimpleCache

    @Inject
    @DownloadCache
    //用于缓存下载数据
    lateinit var downloadCache: SimpleCache

    //用于播放音乐
    lateinit var player: ExoPlayer
    //用于管理媒体库会话
    private lateinit var mediaSession: MediaLibrarySession

    //是否启用音频效果
    private var isAudioEffectSessionOpened = false

    //与discord通信
    private var discordRpc: DiscordRPC? = null

    override fun onCreate() {
        super.onCreate()
        //设置媒体通知提供程序，用于在播放音乐时显示通知
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider(this, { NOTIFICATION_ID }, CHANNEL_ID, R.string.music_player)
                .apply {
                    setSmallIcon(R.drawable.small_icon)
                }
        )
        //初始化 ExoPlayer，设置各种参数如音频属性、处理音频变得嘈杂的情况、设置媒体源工厂和渲染器工厂等
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(createMediaSourceFactory())
            .setRenderersFactory(createRenderersFactory())
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(), true
            )
            .setSeekBackIncrementMs(5000)
            .setSeekForwardIncrementMs(5000)
            .build()
            .apply {
                addListener(this@MusicService)
                sleepTimer = SleepTimer(scope, this)
                addListener(sleepTimer)
                addAnalyticsListener(PlaybackStatsListener(false, this@MusicService))
            }
        mediaLibrarySessionCallback.apply {
            toggleLike = ::toggleLike
            toggleLibrary = ::toggleLibrary
        }
        //初始化媒体会话
        mediaSession = MediaLibrarySession.Builder(this, player, mediaLibrarySessionCallback)
            .setSessionActivity(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
            .setBitmapLoader(CoilBitmapLoader(this, scope))
            .build()
        //设定循环播放模式
        player.repeatMode = dataStore.get(RepeatModeKey, REPEAT_MODE_OFF)

        //保存已连接的控制器，以确保通知正常工作
        val sessionToken = SessionToken(this, ComponentName(this, MusicService::class.java))
        val controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()
        controllerFuture.addListener({ controllerFuture.get() }, MoreExecutors.directExecutor())

        connectivityManager = getSystemService()!!

        //标准化：将音频的某些特性（如音量、响度等）调整到一个标准范围，使得不同音频之间在这些特性上具有可比性或者符合特定的播放要求
        combine(playerVolume, normalizeFactor) { playerVolume, normalizeFactor ->
            playerVolume * normalizeFactor
        }.collectLatest(scope) {
            player.volume = it
        }

        //音量
        //更新速度过快（1000ms内）的数据将不被发送
        playerVolume.debounce(1000).collect(scope) { volume ->
            dataStore.edit { settings ->
                settings[PlayerVolumeKey] = volume
            }
        }

        //当前歌曲
        //更新速度过快（1000ms内）的数据将不被发送
        currentSong.debounce(1000).collect(scope) { song ->
            updateNotification()
            if (song != null) {
                discordRpc?.updateSong(song)
            } else {
                discordRpc?.closeRPC()
            }
        }

        //根据元数据和是否显示歌词来更新歌词数据
        combine(
            currentMediaMetadata.distinctUntilChangedBy { it?.id },
            dataStore.data.map { it[ShowLyricsKey] ?: false }.distinctUntilChanged()
        ) { mediaMetadata, showLyrics ->
            mediaMetadata to showLyrics
        }.collectLatest(scope) { (mediaMetadata, showLyrics) ->
            if (showLyrics && mediaMetadata != null && database.lyrics(mediaMetadata.id).first() == null) {
                val lyrics = lyricsHelper.getLyrics(mediaMetadata)
                database.query {
                    upsert(
                        LyricsEntity(
                            id = mediaMetadata.id,
                            lyrics = lyrics
                        )
                    )
                }
            }
        }

        //监听“跳过静音部分”的值，当有变化时，设置给player
        dataStore.data
            .map { it[SkipSilenceKey] ?: false }
            .distinctUntilChanged()//用于确保只有当设置的值发生变化时才触发后续的操作，避免不必要的重复处理
            .collectLatest(scope) {
                player.skipSilenceEnabled = it
            }

        //根据歌曲格式与标准化音频来得出标准化因子
        combine(
            currentFormat,
            dataStore.data
                .map { it[AudioNormalizationKey] ?: true }
                .distinctUntilChanged()
        ) { format, normalizeAudio ->
            format to normalizeAudio
        }.collectLatest(scope) { (format, normalizeAudio) ->
            normalizeFactor.value = if (normalizeAudio && format?.loudnessDb != null) {
                min(10f.pow(-format.loudnessDb.toFloat() / 20), 1f)
            } else {
                1f
            }
        }

        //监听"DiscordTokenKey"与"EnableDiscordRPCKey"的值，当有变化且变化速度不超过300ms时，对discordRpc进行操作
        dataStore.data
            .map { it[DiscordTokenKey] to (it[EnableDiscordRPCKey] ?: true) }
            .debounce(300)
            .distinctUntilChanged()
            .collect(scope) { (key, enabled) ->
                if (discordRpc?.isRpcRunning() == true) {
                    discordRpc?.closeRPC()
                }
                discordRpc = null
                if (key != null && enabled) {
                    discordRpc = DiscordRPC(this, key)
                    currentSong.value?.let {
                        discordRpc?.updateSong(it)
                    }
                }
            }

        //尝试从磁盘加载持久化的播放队列，并在加载成功后将其设置为当前播放队列
        if (dataStore.get(PersistentQueueKey, true)) {
            runCatching {
                filesDir.resolve(PERSISTENT_QUEUE_FILE).inputStream().use { fis ->
                    ObjectInputStream(fis).use { oos ->
                        oos.readObject() as PersistQueue
                    }
                }
            }.onSuccess { queue ->
                playQueue(
                    queue = ListQueue(
                        title = queue.title,
                        items = queue.items.map { it.toMediaItem() },
                        startIndex = queue.mediaItemIndex,
                        position = queue.position
                    ),
                    playWhenReady = false
                )
            }
        }

        //定期保存队列以防止因崩溃或强制终止而丢失队列
        scope.launch {
            while (isActive) {
                delay(30.seconds)
                if (dataStore.get(PersistentQueueKey, true)) {
                    saveQueueToDisk()
                }
            }
        }
    }

    //更新通知
    private fun updateNotification() {
        mediaSession.setCustomLayout(
            listOf(
                CommandButton.Builder()
                    .setDisplayName(getString(if (currentSong.value?.song?.inLibrary != null) R.string.remove_from_library else R.string.add_to_library))
                    .setIconResId(if (currentSong.value?.song?.inLibrary != null) R.drawable.library_add_check else R.drawable.library_add)
                    .setSessionCommand(CommandToggleLibrary)
                    .setEnabled(currentSong.value != null)
                    .build(),
                CommandButton.Builder()
                    .setDisplayName(getString(if (currentSong.value?.song?.liked == true) R.string.action_remove_like else R.string.action_like))
                    .setIconResId(if (currentSong.value?.song?.liked == true) R.drawable.favorite else R.drawable.favorite_border)
                    .setSessionCommand(CommandToggleLike)
                    .setEnabled(currentSong.value != null)
                    .build(),
                CommandButton.Builder()
                    .setDisplayName(getString(if (player.shuffleModeEnabled) R.string.action_shuffle_off else R.string.action_shuffle_on))
                    .setIconResId(if (player.shuffleModeEnabled) R.drawable.shuffle_on else R.drawable.shuffle)
                    .setSessionCommand(CommandToggleShuffle)
                    .build(),
                CommandButton.Builder()
                    .setDisplayName(
                        getString(
                            when (player.repeatMode) {
                                REPEAT_MODE_OFF -> R.string.repeat_mode_off
                                REPEAT_MODE_ONE -> R.string.repeat_mode_one
                                REPEAT_MODE_ALL -> R.string.repeat_mode_all
                                else -> throw IllegalStateException()
                            }
                        )
                    )
                    .setIconResId(
                        when (player.repeatMode) {
                            REPEAT_MODE_OFF -> R.drawable.repeat
                            REPEAT_MODE_ONE -> R.drawable.repeat_one_on
                            REPEAT_MODE_ALL -> R.drawable.repeat_on
                            else -> throw IllegalStateException()
                        }
                    )
                    .setSessionCommand(CommandToggleRepeatMode)
                    .build()
            )
        )
    }

    //根据歌曲的媒体 ID 恢复歌曲信息。
    //包括从数据库中获取歌曲信息、如果歌曲时长未知则从其他来源获取时长、如果数据库中没有相关歌曲则获取相关歌曲并插入到数据库中
    private suspend fun recoverSong(mediaId: String, playerResponse: PlayerResponse? = null) {
        val song = database.song(mediaId).first()
        val mediaMetadata = withContext(Dispatchers.Main) {
            player.findNextMediaItemById(mediaId)?.metadata
        } ?: return
        val duration = song?.song?.duration?.takeIf { it != -1 }
            ?: mediaMetadata.duration.takeIf { it != -1 }
            ?: (playerResponse ?: YouTube.player(mediaId).getOrNull())?.videoDetails?.lengthSeconds?.toInt()
            ?: -1
        database.query {
            if (song == null) insert(mediaMetadata.copy(duration = duration))
            else if (song.song.duration == -1) update(song.song.copy(duration = duration))
        }
        if (!database.hasRelatedSongs(mediaId)) {
            val relatedEndpoint = YouTube.next(WatchEndpoint(videoId = mediaId)).getOrNull()?.relatedEndpoint ?: return
            val relatedPage = YouTube.related(relatedEndpoint).getOrNull() ?: return
            database.query {
                relatedPage.songs
                    .map(SongItem::toMediaMetadata)
                    .onEach(::insert)
                    .map {
                        RelatedSongMap(
                            songId = mediaId,
                            relatedSongId = it.id
                        )
                    }
                    .forEach(::insert)
            }
        }
    }

    //播放指定的队列
    fun playQueue(queue: Queue, playWhenReady: Boolean = true) {
        if (!scope.isActive) {
            scope = CoroutineScope(Dispatchers.Main) + Job()
        }
        currentQueue = queue
        queueTitle = null
        player.shuffleModeEnabled = false
        if (queue.preloadItem != null) {
            player.setMediaItem(queue.preloadItem!!.toMediaItem())
            player.prepare()
            player.playWhenReady = playWhenReady
        }

        scope.launch(SilentHandler) {
            val initialStatus = withContext(Dispatchers.IO) {
                queue.getInitialStatus().filterExplicit(dataStore.get(HideExplicitKey, false))
            }
            if (queue.preloadItem != null && player.playbackState == STATE_IDLE) return@launch
            if (initialStatus.title != null) {
                queueTitle = initialStatus.title
            }
            if (initialStatus.items.isEmpty()) return@launch
            if (queue.preloadItem != null) {
                // add missing songs back, without affecting current playing song
                player.addMediaItems(0, initialStatus.items.subList(0, initialStatus.mediaItemIndex))
                player.addMediaItems(initialStatus.items.subList(initialStatus.mediaItemIndex + 1, initialStatus.items.size))
            } else {
                player.setMediaItems(initialStatus.items, if (initialStatus.mediaItemIndex > 0) initialStatus.mediaItemIndex else 0, initialStatus.position)
                player.prepare()
                player.playWhenReady = playWhenReady
            }
        }
    }

    //启动无缝电台播放
    //根据当前播放的媒体元数据生成电台队列，并更新播放器的媒体项
    fun startRadioSeamlessly() {
        val currentMediaMetadata = player.currentMetadata ?: return
        if (player.currentMediaItemIndex > 0) player.removeMediaItems(0, player.currentMediaItemIndex)
        if (player.currentMediaItemIndex < player.mediaItemCount - 1) player.removeMediaItems(player.currentMediaItemIndex + 1, player.mediaItemCount)
        scope.launch(SilentHandler) {
            val radioQueue = YouTubeQueue(endpoint = WatchEndpoint(videoId = currentMediaMetadata.id))
            val initialStatus = radioQueue.getInitialStatus()
            if (initialStatus.title != null) {
                queueTitle = initialStatus.title
            }
            player.addMediaItems(initialStatus.items.drop(1))
            currentQueue = radioQueue
        }
    }

    fun playNext(items: List<MediaItem>) {
        player.addMediaItems(if (player.mediaItemCount == 0) 0 else player.currentMediaItemIndex + 1, items)
        player.prepare()
    }

    fun addToQueue(items: List<MediaItem>) {
        player.addMediaItems(items)
        player.prepare()
    }

    fun toggleLibrary() {
        database.query {
            currentSong.value?.let {
                update(it.song.toggleLibrary())
            }
        }
    }

    fun toggleLike() {
        database.query {
            currentSong.value?.let {
                update(it.song.toggleLike())
            }
        }
    }

    //开启音频效果会话
    private fun openAudioEffectSession() {
        if (isAudioEffectSessionOpened) return
        isAudioEffectSessionOpened = true
        sendBroadcast(
            Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION).apply {
                putExtra(AudioEffect.EXTRA_AUDIO_SESSION, player.audioSessionId)
                putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
                putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
            }
        )
    }

    //关闭音频效果会话
    private fun closeAudioEffectSession() {
        if (!isAudioEffectSessionOpened) return
        isAudioEffectSessionOpened = false
        sendBroadcast(
            Intent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION).apply {
                putExtra(AudioEffect.EXTRA_AUDIO_SESSION, player.audioSessionId)
                putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
            }
        )
    }

    //当媒体项切换
    //如果设置了自动加载更多歌曲且满足一定条件，则自动加载更多歌曲到队列
    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        // Auto load more songs
        if (dataStore.get(AutoLoadMoreKey, true) &&
            reason != Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT &&
            player.mediaItemCount - player.currentMediaItemIndex <= 5 &&
            currentQueue.hasNextPage()
        ) {
            scope.launch(SilentHandler) {
                val mediaItems = currentQueue.nextPage().filterExplicit(dataStore.get(HideExplicitKey, false))
                if (player.playbackState != STATE_IDLE) {
                    player.addMediaItems(mediaItems)
                }
            }
        }
    }

    //当播放状态改变时
    override fun onPlaybackStateChanged(@Player.State playbackState: Int) {
        //如果变为空闲状态，则重置播放队列和随机播放状态等
        if (playbackState == STATE_IDLE) {
            currentQueue = EmptyQueue
            player.shuffleModeEnabled = false
            queueTitle = null
        }
    }

    //处理播放器的各种事件
    override fun onEvents(player: Player, events: Player.Events) {
        //根据播放状态和准备状态打开或关闭音频效果会话
        if (events.containsAny(Player.EVENT_PLAYBACK_STATE_CHANGED, Player.EVENT_PLAY_WHEN_READY_CHANGED)) {
            val isBufferingOrReady = player.playbackState == Player.STATE_BUFFERING || player.playbackState == Player.STATE_READY
            if (isBufferingOrReady && player.playWhenReady) {
                openAudioEffectSession()
            } else {
                closeAudioEffectSession()
            }
        }
        //并在时间线改变或位置不连续时更新当前媒体元数据
        if (events.containsAny(EVENT_TIMELINE_CHANGED, EVENT_POSITION_DISCONTINUITY)) {
            currentMediaMetadata.value = player.currentMetadata
        }
    }


    //当随机播放模式启用状态更改，更新通知和执行相应的数据库操作
    override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
        updateNotification()
        if (shuffleModeEnabled) {
            // 始终将当前播放的项目放在最前面
            val shuffledIndices = IntArray(player.mediaItemCount) { it }
            shuffledIndices.shuffle()
            shuffledIndices[shuffledIndices.indexOf(player.currentMediaItemIndex)] = shuffledIndices[0]
            shuffledIndices[0] = player.currentMediaItemIndex
            player.setShuffleOrder(DefaultShuffleOrder(shuffledIndices, System.currentTimeMillis()))
        }
    }

    //当循环模式启用状态更改，更新通知和执行相应的数据库操作
    override fun onRepeatModeChanged(repeatMode: Int) {
        updateNotification()
        scope.launch {
            dataStore.edit { settings ->
                settings[RepeatModeKey] = repeatMode
            }
        }
    }

    //处理播放器错误
    override fun onPlayerError(error: PlaybackException) {
        //如果设置了自动跳过下一首且满足一定条件，则自动跳过到下一首歌曲
        if (dataStore.get(AutoSkipNextOnErrorKey, false) &&
            isInternetAvailable(this) &&
            player.hasNextMediaItem()
        ) {
            player.seekToNext()
            player.prepare()
            player.playWhenReady = true
        }
    }

    /**
     * CacheDataSource 可以从缓存中获取数据，减少对网络或其他慢速数据源的频繁访问
     * DefaultDataSource 用于从各种不同的位置获取数据，如网络（通过OkHttpDataSource.Factory等）、本地文件系统等
     */
    private fun createCacheDataSource(): CacheDataSource.Factory {
        //downloadCache 和 playerCache 的双缓存
        val okHttpClient =  OkHttpClient.Builder()
            .proxy(YouTube.proxy)
            .build()

        val upperDataSource2 = DefaultDataSource.Factory(
            this,
            OkHttpDataSource.Factory(okHttpClient)
        )

        //upperDataSource2获取的数据会缓存到playerCache
        //获取数据时，如果playerCache没数据，则向上游的upperDataSource2请求数据
        val upperDataSource = CacheDataSource.Factory()
            .setCache(playerCache)
            .setUpstreamDataSourceFactory(upperDataSource2)

        val cacheDataSourceFactory = CacheDataSource.Factory()
            .setCache(downloadCache)
            .setUpstreamDataSourceFactory(upperDataSource)
            .setCacheWriteDataSinkFactory(null)
            .setFlags(FLAG_IGNORE_CACHE_ON_ERROR)

        return cacheDataSourceFactory
    }


    //创建数据源工厂
    private fun createDataSourceFactory(): DataSource.Factory {
        //用于缓存歌曲的 URL 和过期时间。这个缓存用于减少对网络的重复请求，提高性能
        val songUrlCache = HashMap<String, Pair<String, Long>>()

        //数据源负责提供媒体数据给播放器，这里的数据源工厂实现了从缓存和网络中获取音乐数据的功能
        return ResolvingDataSource.Factory(createCacheDataSource()) { dataSpec ->
            //dataSpec参数是一个DataSpec对象，它包含了请求数据的详细信息，如请求的 URL、起始位置、长度等

            //回调函数在每次请求数据时调用，用于决定如何获取数据
            val mediaId = dataSpec.key ?: error("No media id")

            //首先检查缓存，这里检查 downloadCache 和 playerCache 是否包含请求的数据
            if (downloadCache.isCached(mediaId, dataSpec.position, if (dataSpec.length >= 0) dataSpec.length else 1) ||
                playerCache.isCached(mediaId, dataSpec.position, CHUNK_LENGTH)
            ) {
                //如果数据在缓存中，则启动一个协程来恢复歌曲信息（可能是更新数据库中的歌曲信息或者进行其他处理），然后直接返回请求的DataSpec，表示可以从缓存中获取数据
                scope.launch(Dispatchers.IO) { recoverSong(mediaId) }
                return@Factory dataSpec
            }

            //检查缓存中的 URL。这里检查缓存中的歌曲 URL 是否存在并且未过期（通过比较当前时间和缓存中的过期时间）
            songUrlCache[mediaId]?.takeIf { it.second < System.currentTimeMillis() }?.let {
                //如果满足条件，则启动一个协程来恢复歌曲信息，然后返回带有缓存 URL 的DataSpec，表示可以从缓存的 URL 中获取数据
                scope.launch(Dispatchers.IO) { recoverSong(mediaId) }
                return@Factory dataSpec.withUri(it.first.toUri())
            }

            // 检查格式是否存在，以便旧版本的用户可以查看格式详细信息
            // 如果用户频繁更改音频质量，则下载的文件和显示的信息之间可能存在不一致

            //从数据库中获取已经播放过的格式信息
            val playedFormat = runBlocking(Dispatchers.IO) { database.format(mediaId).first() }
            //获取视频的播放信息。如果获取过程中出现网络连接问题，则抛出相应的异常
            val playerResponse = runBlocking(Dispatchers.IO) {
                YouTube.player(mediaId)
            }.getOrElse { throwable ->
                when (throwable) {
                    is ConnectException, is UnknownHostException -> {
                        throw PlaybackException(getString(R.string.error_no_internet), throwable, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
                    }

                    is SocketTimeoutException -> {
                        throw PlaybackException(getString(R.string.error_timeout), throwable, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT)
                    }

                    else -> throw PlaybackException(getString(R.string.error_unknown), throwable, PlaybackException.ERROR_CODE_REMOTE_ERROR)
                }
            }
            if (playerResponse.playabilityStatus.status != "OK") {
                throw PlaybackException(playerResponse.playabilityStatus.reason, null, PlaybackException.ERROR_CODE_REMOTE_ERROR)
            }

            //根据音频质量设置和已有播放格式信息选择最合适的音频播放格式
            //如果没有找到合适的格式，则抛出异常
            val format =
                if (playedFormat != null) {
                    playerResponse.streamingData?.adaptiveFormats?.find {
                        // 使用 itag 识别以前播放的格式
                        it.itag == playedFormat.itag
                    }
                } else {
                    playerResponse.streamingData?.adaptiveFormats
                        ?.filter { it.isAudio }
                        ?.maxByOrNull {
                            it.bitrate * when (audioQuality) {
                                AudioQuality.AUTO -> if (connectivityManager.isActiveNetworkMetered) -1 else 1
                                AudioQuality.HIGH -> 1
                                AudioQuality.LOW -> -1
                            } + (if (it.mimeType.startsWith("audio/webm")) 10240 else 0) // prefer opus stream
                        }
                } ?: throw PlaybackException(getString(R.string.error_no_stream), null, ERROR_CODE_NO_STREAM)

            //更新数据库中的格式信息
            database.query {
                upsert(
                    FormatEntity(
                        id = mediaId,
                        itag = format.itag,
                        mimeType = format.mimeType.split(";")[0],
                        codecs = format.mimeType.split("codecs=")[1].removeSurrounding("\""),
                        bitrate = format.bitrate,
                        sampleRate = format.audioSampleRate,
                        contentLength = format.contentLength!!,
                        loudnessDb = playerResponse.playerConfig?.audioConfig?.loudnessDb
                    )
                )
            }

            //启动一个协程来恢复歌曲信息
            scope.launch(Dispatchers.IO) { recoverSong(mediaId, playerResponse) }

            //将歌曲的 URL 和过期时间存入缓存
            songUrlCache[mediaId] = format.url!! to playerResponse.streamingData!!.expiresInSeconds * 1000L

            //返回带有新 URL 的DataSpec，表示可以从这个 URL 中获取数据，并且指定了数据的子范围（从uriPositionOffset开始，长度为CHUNK_LENGTH）
            dataSpec.withUri(format.url!!.toUri()).subrange(dataSpec.uriPositionOffset, CHUNK_LENGTH)
        }
    }

    private fun createMediaSourceFactory() =
        DefaultMediaSourceFactory(
            createDataSourceFactory(),
            ExtractorsFactory {
                arrayOf(MatroskaExtractor(), FragmentedMp4Extractor())
            }
        )

    //创建渲染器工厂
    private fun createRenderersFactory() =
        object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ) = DefaultAudioSink.Builder(this@MusicService)
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .setAudioProcessorChain(
                    DefaultAudioSink.DefaultAudioProcessorChain(
                        emptyArray(),
                        SilenceSkippingAudioProcessor(2_000_000, 0.01f, 2_000_000, 0, 256),
                        SonicAudioProcessor()
                    )
                )
                .build()
        }

    //当播放统计信息准备好时
    override fun onPlaybackStatsReady(eventTime: AnalyticsListener.EventTime, playbackStats: PlaybackStats) {
        //如果播放时间满足一定条件，则更新数据库中的播放历史记录
        val mediaItem = eventTime.timeline.getWindow(eventTime.windowIndex, Timeline.Window()).mediaItem
        if (playbackStats.totalPlayTimeMs >= 30000 && !dataStore.get(PauseListenHistoryKey, false)) {
            database.query {
                incrementTotalPlayTime(mediaItem.mediaId, playbackStats.totalPlayTimeMs)
                try {
                    insert(
                        Event(
                            songId = mediaItem.mediaId,
                            timestamp = LocalDateTime.now(),
                            playTime = playbackStats.totalPlayTimeMs
                        )
                    )
                } catch (_: SQLException) {
                }
            }
        }
    }

    //将当前播放队列保存到磁盘，如果播放器处于空闲状态则删除持久化文件
    private fun saveQueueToDisk() {
        if (player.playbackState == STATE_IDLE) {
            filesDir.resolve(PERSISTENT_QUEUE_FILE).delete()
            return
        }
        val persistQueue = PersistQueue(
            title = queueTitle,
            items = player.mediaItems.mapNotNull { it.metadata },
            mediaItemIndex = player.currentMediaItemIndex,
            position = player.currentPosition
        )
        runCatching {
            filesDir.resolve(PERSISTENT_QUEUE_FILE).outputStream().use { fos ->
                ObjectOutputStream(fos).use { oos ->
                    oos.writeObject(persistQueue)
                }
            }
        }.onFailure {
            reportException(it)
        }
    }

    //在服务销毁时，保存播放队列到磁盘、关闭 Discord RPC、释放媒体会话和播放器资源。
    override fun onDestroy() {
        if (dataStore.get(PersistentQueueKey, true)) {
            saveQueueToDisk()
        }
        if (discordRpc?.isRpcRunning() == true) {
            discordRpc?.closeRPC()
        }
        discordRpc = null
        mediaSession.release()
        player.removeListener(this)
        player.removeListener(sleepTimer)
        player.release()
        super.onDestroy()
    }

    //用于绑定服务
    override fun onBind(intent: Intent?) = super.onBind(intent) ?: binder

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        stopSelf()
    }

    //用于获取媒体会话
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = mediaSession

    inner class MusicBinder : Binder() {
        val service: MusicService
            get() = this@MusicService
    }

    companion object {
        const val ROOT = "root"
        const val SONG = "song"
        const val ARTIST = "artist"
        const val ALBUM = "album"
        const val PLAYLIST = "playlist"

        const val CHANNEL_ID = "music_channel_01"
        const val NOTIFICATION_ID = 888
        const val ERROR_CODE_NO_STREAM = 1000001
        const val CHUNK_LENGTH = 512 * 1024L
        const val PERSISTENT_QUEUE_FILE = "persistent_queue.data"
    }
}
