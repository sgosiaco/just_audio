package com.ryanheise.just_audio

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.util.Log
import com.google.android.exoplayer2.*
import com.google.android.exoplayer2.audio.AudioAttributes
import com.google.android.exoplayer2.audio.AudioListener
import com.google.android.exoplayer2.metadata.Metadata
import com.google.android.exoplayer2.metadata.MetadataOutput
import com.google.android.exoplayer2.metadata.icy.IcyHeaders
import com.google.android.exoplayer2.metadata.icy.IcyInfo
import com.google.android.exoplayer2.source.*
import com.google.android.exoplayer2.source.dash.DashMediaSource
import com.google.android.exoplayer2.source.hls.HlsMediaSource
import com.google.android.exoplayer2.trackselection.TrackSelectionArray
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource
import com.google.android.exoplayer2.upstream.DefaultHttpDataSourceFactory
import com.google.android.exoplayer2.util.Util
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import kotlin.properties.Delegates

class AudioPlayer(val applicationContext: Context, messenger: BinaryMessenger, id: String): MethodChannel.MethodCallHandler, Player.EventListener, AudioListener, MetadataOutput {
    private val TAG: String = "AudioPlayerKotlin"
    private val MAX_ERRORS: Int = 5

    private val random: java.util.Random = java.util.Random()

    private var context: Context = applicationContext
    private var eventSink: EventChannel.EventSink? = null

    private var processingState: ProcessingState = ProcessingState.none
    private var bufferedPosition by Delegates.notNull<Long>()

    private var seekPos: Long? = null
    private var initalPos by Delegates.notNull<Long>()
    private var initialIndex: Int? = null
    private var prepareResult: MethodChannel.Result? = null
    private var playResult: MethodChannel.Result? = null
    private var seekResult: MethodChannel.Result? = null
    private var seekProcessed by Delegates.notNull<Boolean>()
    private var playing by Delegates.notNull<Boolean>()
    private val mediaSources: MutableMap<String, MediaSource> = HashMap()
    private var icyInfo: IcyInfo? = null
    private var icyHeaders: IcyHeaders? = null
    private var errorCount = 0
    private var shuffleIndices: MutableList<Int> = mutableListOf()

    private var player: SimpleExoPlayer? = null
    private var audioSessionId: Int? = null
    private var mediaSource: MediaSource? = null
    private var currentIndex by Delegates.notNull<Int>()
    private val loopingChildren: MutableMap<LoopingMediaSource, MediaSource> = HashMap()
    private val loopingCounts: MutableMap<LoopingMediaSource, Int> = HashMap()
    private val handler: Handler = Handler()
    private val bufferWatcher = object : Runnable {
        override fun run() {
            if (player == null) {
                return
            }

            val newBufferedPosition: Long = player!!.bufferedPosition
            if (newBufferedPosition != bufferedPosition) {
                bufferedPosition = newBufferedPosition
                broadcastPlaybackEvent()
            }
            when (processingState) {
                ProcessingState.buffering -> handler.postDelayed(this, 200)
                ProcessingState.ready -> {
                    if (playing) {
                        handler.postDelayed(this, 500)
                    } else {
                        handler.postDelayed(this, 1000)
                    }
                }
            }
        }

    }


    init {
        val methodChannel = MethodChannel(messenger, "com.ryanheise.just_audio.methods.$id")
        methodChannel.setMethodCallHandler(this)
        val eventChannel = EventChannel(messenger, "com.ryanheise.just_audio.events.$id")
        eventChannel.setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                this@AudioPlayer.eventSink = events
            }

            override fun onCancel(arguments: Any?) {
                eventSink = null
            }
        })

    }

    private fun startWatchingBuffer() {
        handler.removeCallbacks(bufferWatcher)
        handler.post(bufferWatcher)
    }

    override fun onAudioSessionId(audioSessionId: Int) {
        if (audioSessionId == C.AUDIO_SESSION_ID_UNSET) {
            this.audioSessionId = null
        } else {
            this.audioSessionId = audioSessionId
        }
    }

    override fun onMetadata(metadata: Metadata) {
        for (i in 0 until metadata.length()) {
            val entry: Metadata.Entry = metadata.get(i)
            if (entry is IcyInfo) {
                icyInfo = entry
                broadcastPlaybackEvent()
            }
        }

    }

    override fun onTracksChanged(trackGroups: TrackGroupArray, trackSelections: TrackSelectionArray) {
        for(i in 0 until trackGroups.length) {
            val trackGroup: TrackGroup = trackGroups.get(i)
            for(j in 0 until trackGroup.length) {
                val metadata: Metadata? = trackGroup.getFormat(j).metadata

                if (metadata != null) {
                    onMetadata(metadata) // improved? by not having another copy of same code as onMetadata
                }
            }
        }
    }

    override fun onPositionDiscontinuity(reason: Int) {
        when (reason) {
            Player.DISCONTINUITY_REASON_SEEK -> onItemMayHaveChanged()
            else -> return
        }
    }

    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
        if (initalPos != C.TIME_UNSET || initialIndex != null) {
            initialIndex?.let { player?.seekTo(it, initalPos) } //TODO
            initialIndex = null
            initalPos = C.TIME_UNSET
        }
        if (reason == Player.TIMELINE_CHANGE_REASON_DYNAMIC) {
            onItemMayHaveChanged()
        }
    }

    private fun onItemMayHaveChanged() {
        player?.run {
            if (currentWindowIndex != currentIndex) {
                currentIndex = currentWindowIndex
            }
            broadcastPlaybackEvent()
        }
    }

    override fun onPlayerStateChanged(playWhenReady: Boolean, playbackState: Int) {
        when (playbackState) {
            Player.STATE_READY -> {
                if (prepareResult != null) {
                    transition(ProcessingState.ready)
                    val response = HashMap<String, Any>() //TODO
                    response["duration"] = 1000 * getDuration()
                    prepareResult!!.success(response)
                    prepareResult = null
                } else {
                    transition(ProcessingState.ready)
                }
                if (seekProcessed) {
                    completeSeek()
                }
            }
            Player.STATE_BUFFERING -> {
                if (processingState != ProcessingState.buffering && processingState != ProcessingState.loading) {
                    transition(ProcessingState.buffering)
                    startWatchingBuffer()
                }
            }
            Player.STATE_ENDED -> {
                if (processingState != ProcessingState.completed) {
                    transition(ProcessingState.completed)
                }
                if (playResult != null) {
                    playResult!!.success(HashMap<String, Any>())
                    playResult = null
                }
            }
            Player.STATE_IDLE -> return
        }
    }

    override fun onPlayerError(error: ExoPlaybackException) {
        when (error.type) {
            ExoPlaybackException.TYPE_SOURCE -> Log.e(TAG, "TYPE_SOURCE: ${error.sourceException.message}")
            ExoPlaybackException.TYPE_RENDERER -> Log.e(TAG, "TYPE_RENDERER: ${error.sourceException.message}")
            ExoPlaybackException.TYPE_UNEXPECTED -> Log.e(TAG, "TYPE_UNEXPECTED: ${error.sourceException.message}")
            ExoPlaybackException.TYPE_OUT_OF_MEMORY -> Log.e(TAG, "TYPE_OUT_OF_MEMORY: ${error.sourceException.message}")
            ExoPlaybackException.TYPE_REMOTE -> Log.e(TAG, "TYPE_REMOTE: ${error.sourceException.message}")
        }
        error.message?.let { sendError(error.type.toString(), it) }
        errorCount += 1
        player?.run {
            if (hasNext() && errorCount <= MAX_ERRORS) { //currentIndex != null now always true
                mediaSource?.let { prepare(it) }
                seekTo(currentIndex + 1, 0)
            }
        }
    }

    override fun onSeekProcessed() {
        if (seekResult != null) {
            seekProcessed = true
            if (player!!.playbackState == Player.STATE_READY) { //player shouldn't be null?
                completeSeek()
            }
        }
    }

    private fun completeSeek() {
        seekProcessed = false
        seekPos = null
        seekResult?.success(HashMap<String, Any>())
        seekResult = null
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        ensurePlayerInitialized()

        val request : Map<Any, Any> = call.arguments()
        try {
            when (call.method) {
                "load" -> {
                    val initialPosition: Long? = request["initialPosition"] as Long?
                    val initialIndex: Int = request["initialIndex"] as Int
                    load(request["audioSource"] as MediaSource, if (initialPosition == null) C.TIME_UNSET else initialPosition / 1000, initialIndex, result) //TODO
                }
                "play" -> play(result)
                "pause" -> {
                    pause()
                    result.success(HashMap<String, Any>())
                }
                "setVolume" -> {
                    setVolume(request["volume"] as Float)
                    result.success(HashMap<String, Any>())
                }
                "setSpeed" -> {
                    setSpeed(request["speed"] as Float)
                    result.success(HashMap<String, Any>())
                }
                "setLoopMode" -> {
                    setLoopMode(request["loopMode"] as Int)
                    result.success(HashMap<String, Any>())
                }
                "setShuffleMode" -> {
                    setShuffleModeEnabled(request["shuffleMode"] as Int == 1)
                    val response = HashMap<String, Any>()
                    response["shuffleIndices"] = shuffleIndices
                    result.success(response)
                }
                "setAutomaticallyWaitsToMinimizeStalling" -> {
                    result.success(HashMap<String, Any>())
                }
                "seek" -> {
                    val position: Long? = request["position"] as Long?
                    val index: Int = request["index"] as Int
                    seek(if (position == null) C.TIME_UNSET else position / 1000, index, result)
                }
                "concatenatingInsertAll" -> {
                    (mediaSources[request["id"]] as ConcatenatingMediaSource)
                            .addMediaSources(
                                    request["index"] as Int,
                                    getAudioSources(request["children"] as List<Any>),
                                    handler,
                                    Runnable { result.success(HashMap<String, Any>()) })
                }
                "concatenatingRemoveRange" -> {
                    (mediaSources[request["id"]] as ConcatenatingMediaSource)
                            .removeMediaSourceRange(
                                    request["startIndex"] as Int,
                                    request["endIndex"] as Int,
                                    handler,
                                    Runnable { result.success(HashMap<String, Any>()) })
                }
                "concatenatingMove" -> {
                    (mediaSources[request["id"]] as ConcatenatingMediaSource)
                            .moveMediaSource(
                                    request["currentIndex"] as Int,
                                    request["newIndex"] as Int,
                                    handler,
                                    Runnable { result.success(HashMap<String, Any>()) })
                }
                "setAndroidAudioAttributes" -> {
                    setAudioAttributes(request["contentType"] as Int, request["flags"] as Int, request["usage"] as Int)
                    result.success(HashMap<String, Any>())
                }
                else -> result.notImplemented()
            }
        } catch (e: IllegalStateException) {
            e.printStackTrace()
            result.error("Illegal state: ${e.message}", null, null)
        } catch (e: Exception) {
            e.printStackTrace()
            result.error("Error: $e", null, null)
        }
    }

    private fun setShuffleOrder(mediaSource: MediaSource, index: Int): Int {
        var newIndex = index
        when (mediaSource) {
            is ConcatenatingMediaSource -> {
                val source: ConcatenatingMediaSource = mediaSource

                var currentChildIndex: Int? = null
                for (i in 0..source.size) {
                    val indexBefore: Int = newIndex
                    val child: MediaSource = source.getMediaSource(i)
                    newIndex = setShuffleOrder(child, newIndex)

                    if (currentIndex in indexBefore..newIndex) {
                        currentChildIndex = i
                    }
                }
                source.setShuffleOrder(createShuffleOrder(source.size, currentChildIndex))
            }
            is LoopingMediaSource -> {
                val source: LoopingMediaSource = mediaSource
                val child: MediaSource = loopingChildren[source]!!
                val count: Int = loopingCounts[source]!!
                for (i in 0..count) {
                    newIndex = setShuffleOrder(child, newIndex)
                }
            }
            else -> {
                newIndex += 1;
            }
        }
        return newIndex
    }

    private fun createShuffleOrder(length: Int, firstIndex: Int?): ShuffleOrder {
        shuffleIndices = shuffle(length, firstIndex)
        return ShuffleOrder.DefaultShuffleOrder(shuffleIndices.toIntArray(), random.nextLong())
    }

    private fun shuffle(length: Int, firstIndex: Int?): MutableList<Int> {
        val shuffleOrder: MutableList<Int> = mutableListOf()
        for (i in 0..length) {
            val j: Int = random.nextInt(i + 1)
            shuffleOrder[i] = shuffleOrder[j].also { shuffleOrder[j] = i } //maybe use kotlin swap stuff here?
        }
        if (firstIndex != null) {
            for (i in 1..length) {
                if (shuffleOrder[i] == firstIndex) {
                    shuffleOrder[0] = shuffleOrder[i].also { shuffleOrder[i] = shuffleOrder[0] } //swap here too
                    break
                }
            }
        }
        return shuffleOrder
    }

    private fun getAudioSource(json: Map<Any, Any>): MediaSource {
        val id: String = json["id"] as String
        var mediaSource: MediaSource? = mediaSources[id]
        if (mediaSource == null) {
            mediaSource = decodeAudioSource(json)
            mediaSources[id] = mediaSource
        }
        return mediaSource
    }

    private fun decodeAudioSource(json: Map<Any, Any>): MediaSource {
        val id: String = json["id"] as String
        when (json["type"]) {
            "progressive" -> {
                return ProgressiveMediaSource.Factory(buildDataSourceFactory())
                        .setTag(id)
                        .createMediaSource(Uri.parse(json["uri"] as String))
            }
            "dash" -> {
                return DashMediaSource.Factory(buildDataSourceFactory())
                        .setTag(id)
                        .createMediaSource(Uri.parse(json["uri"] as String))
            }
            "hls" -> {
                return HlsMediaSource.Factory(buildDataSourceFactory())
                        .setTag(id)
                        .createMediaSource(Uri.parse(json["uri"] as String))
            }
            "concatenating" -> {
                val mediaSources: Array<MediaSource> = getAudioSourcesArray(json["children"] as List<Any>)
                return ConcatenatingMediaSource(
                        false,
                        json["useLazyPreparation"] as Boolean,
                        ShuffleOrder.DefaultShuffleOrder(mediaSources.size),
                        *mediaSources
                )
            }
            "clipping" -> {
                return ClippingMediaSource(getAudioSource(json["child"] as Map<Any, Any>), json["start"] as Long? ?: 0, json["end"] as Long? ?: C.TIME_END_OF_SOURCE)
            }
            "looping" -> {
                val count: Int = json["count"] as Int
                val looperChild: MediaSource = getAudioSource(json["child"] as Map<Any, Any>)
                val looper: LoopingMediaSource = LoopingMediaSource(looperChild, count)
                loopingChildren[looper] = looperChild
                loopingCounts[looper] = count
                return looper
            }
            else -> {
                throw IllegalArgumentException("Unknown AudioSource type: ${json["type"]}")
            }
        }
    }

    // java implementation of these two functions are odd. Do we really need to copy?
    private fun getAudioSourcesArray(json: List<Any>): Array<MediaSource> {
        val mediaSources: List<MediaSource> = getAudioSources(json)
        return arrayOf(*mediaSources.toTypedArray())
    }

    private fun getAudioSources(json: List<Any>): List<MediaSource> {
        return arrayListOf(json) as List<MediaSource>
    }

    private fun buildDataSourceFactory(): com.google.android.exoplayer2.upstream.DataSource.Factory {
        val userAgent: String = Util.getUserAgent(context, "just_audio")
        val httpDataSourceFactory: com.google.android.exoplayer2.upstream.DataSource.Factory = DefaultHttpDataSourceFactory(
                userAgent,
                DefaultHttpDataSource.DEFAULT_CONNECT_TIMEOUT_MILLIS,
                DefaultHttpDataSource.DEFAULT_READ_TIMEOUT_MILLIS,
                true
        )
        return DefaultDataSourceFactory(context, httpDataSourceFactory)
    }

    private fun load(mediaSource: MediaSource, initialPosition: Long, initialIndex: Int, result: MethodChannel.Result) {
        this.initalPos = initialPosition
        this.initialIndex = initialIndex
        when (processingState) {
            ProcessingState.none -> {
            }
            ProcessingState.loading -> {
                abortExistingConnection()
                player?.stop()
            }
            else -> {
                player?.stop()
            }
        }
        errorCount = 0
        prepareResult = result
        transition(ProcessingState.loading)
        if (player?.shuffleModeEnabled!!) { // TODO
            setShuffleOrder(mediaSource, 0)
        }
        this.mediaSource = mediaSource
        player?.prepare(mediaSource)
    }

    private fun ensurePlayerInitialized() {
        if (player == null) {
            player = SimpleExoPlayer.Builder(context).build()
            player!!.addMetadataOutput(this)
            player!!.addListener(this)
            player!!.addAudioListener(this)
        }
    }

    private fun setAudioAttributes(contentType: Int, flags: Int, usage: Int) {
        ensurePlayerInitialized()
        val builder: AudioAttributes.Builder = AudioAttributes.Builder()
        builder.setContentType(contentType)
        builder.setFlags(flags)
        builder.setUsage(usage)
        player?.setAudioAttributes(builder.build()) // TODO idk why dep?
    }

    private fun broadcastPlaybackEvent() {
        val event: MutableMap<String, Any> = HashMap()
        val updatePosition: Long = getCurrentPosition()
        val duration: Long = getDuration()
        event["processingState"] = processingState.ordinal
        event["updatePosition"] = 1000 * updatePosition
        event["updateTime"] = System.currentTimeMillis()
        event["bufferedPosition"] = 1000 * updatePosition.coerceAtLeast(bufferedPosition)
        event["icyMetadata"] = collectIcyMetadata()
        event["duration"] = 1000 * duration
        event["currentIndex"] = currentIndex
        event["androidAudioSessionId"] = audioSessionId!!

        eventSink?.success(event)
    }

    private fun collectIcyMetadata(): Map<String, Any> {
        val icyData: MutableMap<String, Any> = HashMap()
        if (icyInfo != null) {
            val info: MutableMap<String, String> = HashMap()
            info["title"] = icyInfo!!.title!!
            info["url"] = icyInfo!!.url!!
            icyData["info"] = info
        }
        if (icyHeaders != null) {
            val headers: MutableMap<String, Any> = HashMap()
            headers["bitrate"] = icyHeaders!!.bitrate
            headers["genre"] = icyHeaders!!.genre!!
            headers["name"] = icyHeaders!!.name!!
            headers["metadataInterval"] = icyHeaders!!.metadataInterval
            headers["url"] = icyHeaders!!.url!!
            headers["isPublic"] = icyHeaders!!.isPublic
            icyData["headers"] = headers
        }
        return icyData
    }

    private fun getCurrentPosition(): Long {
        return if (processingState == ProcessingState.none || processingState == ProcessingState.loading) {
            0
        } else if (seekPos != null && seekPos != C.TIME_UNSET) {
            seekPos!!
        } else {
            player!!.currentPosition
        }
    }

    private fun getDuration(): Long {
        return if (processingState == ProcessingState.none || processingState == ProcessingState.loading) {
            C.TIME_UNSET
        } else {
            player!!.duration
        }
    }

    private fun sendError(errorCode: String, errorMsg: String) {
        if (prepareResult != null) {
            prepareResult!!.error(errorCode, errorMsg, null)
            prepareResult = null
        }
        if (eventSink != null) {
            eventSink!!.error(errorCode, errorMsg, null)
        }
    }

    private fun transition(newState: ProcessingState) {
        processingState = newState
        broadcastPlaybackEvent()
    }

    private fun play(result: MethodChannel.Result) {
        if (player!!.playWhenReady) {
            result.success(java.util.HashMap<String, Any>())
            return
        }
        if (playResult != null) {
            playResult!!.success(java.util.HashMap<String, Any>())
        }
        playResult = result
        startWatchingBuffer()
        player!!.playWhenReady = true
        if (processingState == ProcessingState.completed && playResult != null) {
            playResult!!.success(java.util.HashMap<String, Any>())
            playResult = null
        }
    }

    private fun pause() {
        if (!player!!.playWhenReady) return
        player!!.playWhenReady = false
        if (playResult != null) {
            playResult!!.success(java.util.HashMap<String, Any>())
            playResult = null
        }
    }

    private fun setVolume(volume: Float) {
        player!!.volume = volume
    }

    private fun setSpeed(speed: Float) {
        player!!.setPlaybackParameters(PlaybackParameters(speed))
        broadcastPlaybackEvent()
    }

    private fun setLoopMode(mode: Int) {
        player!!.repeatMode = mode
    }

    private fun setShuffleModeEnabled(enabled: Boolean) {
        if (enabled) {
            setShuffleOrder(mediaSource!!, 0)
        }
        player!!.shuffleModeEnabled = enabled
    }

    private fun seek(position: Long, index: Int?, result: MethodChannel.Result) {
        if (processingState == ProcessingState.none || processingState == ProcessingState.loading) {
            result.success(java.util.HashMap<String, Any>())
            return
        }
        abortSeek()
        seekPos = position
        seekResult = result
        seekProcessed = false
        val windowIndex = index ?: player!!.currentWindowIndex
        player!!.seekTo(windowIndex, position)
    }

    fun dispose() {
        if (processingState == ProcessingState.loading) {
            abortExistingConnection()
        }
        mediaSources.clear()
        mediaSource = null
        loopingChildren.clear()
        if (player != null) {
            player!!.release()
            player = null
            transition(ProcessingState.none)
        }
        if (eventSink != null) {
            eventSink!!.endOfStream()
        }
    }

    private fun abortSeek() {
        if (seekResult != null) {
            seekResult!!.success(java.util.HashMap<String, Any>())
            seekResult = null
            seekPos = null
            seekProcessed = false
        }
    }

    private fun abortExistingConnection() {
        sendError("abort", "Connection aborted")
    }

    enum class ProcessingState {
        none,
        loading,
        buffering,
        ready,
        completed
    }
}