package com.ryanheise.just_audio

import android.content.Context
import android.net.Uri
import android.os.Handler
import com.google.android.exoplayer2.*
import com.google.android.exoplayer2.audio.AudioAttributes
import com.google.android.exoplayer2.audio.AudioListener
import com.google.android.exoplayer2.metadata.Metadata
import com.google.android.exoplayer2.metadata.MetadataOutput
import com.google.android.exoplayer2.metadata.icy.IcyHeaders
import com.google.android.exoplayer2.metadata.icy.IcyInfo
import com.google.android.exoplayer2.source.*
import com.google.android.exoplayer2.source.ShuffleOrder.DefaultShuffleOrder
import com.google.android.exoplayer2.source.dash.DashMediaSource
import com.google.android.exoplayer2.source.hls.HlsMediaSource
import com.google.android.exoplayer2.trackselection.TrackSelectionArray
import com.google.android.exoplayer2.upstream.DataSource
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource
import com.google.android.exoplayer2.upstream.DefaultHttpDataSourceFactory
import com.google.android.exoplayer2.util.Util
import io.flutter.Log
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.EventChannel.EventSink
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import java.util.*
import kotlin.collections.ArrayList
import kotlin.collections.HashMap

class AudioPlayer(private val context: Context, messenger: BinaryMessenger?, id: String) : MethodCallHandler, Player.EventListener, AudioListener, MetadataOutput {
    private var eventSink: EventSink? = null
    private lateinit var processingState: ProcessingState
    private val MAX_ERRORS = 5
    private var bufferedPosition: Long = 0

    private var seekPos: Long? = null
    private var initialPos: Long = 0
    private var initialIndex: Int? = null
    private var prepareResult: MethodChannel.Result? = null
    private var playResult: MethodChannel.Result? = null
    private var seekResult: MethodChannel.Result? = null
    private var seekProcessed = false
    private val playing = false
    private val mediaSources: MutableMap<String?, MediaSource?> = HashMap()
    private var icyInfo: IcyInfo? = null
    private var icyHeaders: IcyHeaders? = null
    private var errorCount = 0
    private var shuffleIndices: IntArray = IntArray(0) // TODO
    private var player: SimpleExoPlayer? = null
    private var audioSessionId: Int? = null
    private var mediaSource: MediaSource? = null
    private var currentIndex: Int? = null
    private val loopingChildren: MutableMap<LoopingMediaSource, MediaSource?> = HashMap()
    private val loopingCounts: MutableMap<LoopingMediaSource, Int?> = HashMap()
    private val handler = Handler()
    private val bufferWatcher: Runnable = object : Runnable {
        override fun run() {
            if (player == null) {
                return
            }
            val newBufferedPosition = player!!.bufferedPosition
            if (newBufferedPosition != bufferedPosition) {
                bufferedPosition = newBufferedPosition
                broadcastPlaybackEvent()
            }
            when (processingState) {
                ProcessingState.Buffering -> handler.postDelayed(this, 200)
                ProcessingState.Ready -> if (playing) {
                    handler.postDelayed(this, 500)
                } else {
                    handler.postDelayed(this, 1000)
                }
            }
        }
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
            val entry = metadata[i]
            if (entry is IcyInfo) {
                icyInfo = entry
                broadcastPlaybackEvent()
            }
        }
    }

    override fun onTracksChanged(trackGroups: TrackGroupArray, trackSelections: TrackSelectionArray) {
        for (i in 0 until trackGroups.length) {
            val trackGroup = trackGroups[i]
            for (j in 0 until trackGroup.length) {
                val metadata = trackGroup.getFormat(j).metadata
                if (metadata != null) {
                    for (k in 0 until metadata.length()) {
                        val entry = metadata[k]
                        if (entry is IcyHeaders) {
                            icyHeaders = entry
                            broadcastPlaybackEvent()
                        }
                    }
                }
            }
        }
    }

    override fun onPositionDiscontinuity(reason: Int) {
        when (reason) {
            Player.DISCONTINUITY_REASON_PERIOD_TRANSITION, Player.DISCONTINUITY_REASON_SEEK -> onItemMayHaveChanged()
            Player.DISCONTINUITY_REASON_AD_INSERTION, Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT, Player.DISCONTINUITY_REASON_INTERNAL -> {
            }
        }
    }

    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
        if (initialPos != C.TIME_UNSET || initialIndex != null) {
            player!!.seekTo(initialIndex!!, initialPos)
            initialIndex = null
            initialPos = C.TIME_UNSET
        }
        if (reason == Player.TIMELINE_CHANGE_REASON_DYNAMIC) {
            onItemMayHaveChanged()
        }
    }

    private fun onItemMayHaveChanged() {
        val newIndex = player!!.currentWindowIndex
        if (newIndex !== currentIndex) {
            currentIndex = newIndex
        }
        broadcastPlaybackEvent()
    }

    override fun onPlayerStateChanged(playWhenReady: Boolean, playbackState: Int) {
        when (playbackState) {
            Player.STATE_READY -> {
                if (prepareResult != null) {
                    transition(ProcessingState.Ready)
                    val response: MutableMap<String, Any> = HashMap()
                    response["duration"] = 1000 * duration
                    prepareResult!!.success(response)
                    prepareResult = null
                } else {
                    transition(ProcessingState.Ready)
                }
                if (seekProcessed) {
                    completeSeek()
                }
            }
            Player.STATE_BUFFERING -> if (processingState != ProcessingState.Buffering && processingState != ProcessingState.Loading) {
                transition(ProcessingState.Buffering)
                startWatchingBuffer()
            }
            Player.STATE_ENDED -> {
                if (processingState != ProcessingState.Completed) {
                    transition(ProcessingState.Completed)
                }
                if (playResult != null) {
                    playResult!!.success(HashMap<String, Any>())
                    playResult = null
                }
            }
            Player.STATE_IDLE -> {
            }
        }
    }

    override fun onPlayerError(error: ExoPlaybackException) {
        when (error.type) {
            ExoPlaybackException.TYPE_SOURCE -> Log.e(TAG, "TYPE_SOURCE: " + error.sourceException.message)
            ExoPlaybackException.TYPE_RENDERER -> Log.e(TAG, "TYPE_RENDERER: " + error.rendererException.message)
            ExoPlaybackException.TYPE_UNEXPECTED -> Log.e(TAG, "TYPE_UNEXPECTED: " + error.unexpectedException.message)
            ExoPlaybackException.TYPE_OUT_OF_MEMORY -> Log.e(TAG, "TYPE_OUT_OF_MEMORY: " + error.unexpectedException.message)
            ExoPlaybackException.TYPE_REMOTE -> Log.e(TAG, "TYPE_REMOTE: " + error.unexpectedException.message)
            else -> Log.e(TAG, "default: " + error.unexpectedException.message)
        }
        sendError(error.type.toString(), error.message)
        errorCount++
        if (player!!.hasNext() && currentIndex != null && errorCount <= MAX_ERRORS) {
            val nextIndex = currentIndex!! + 1
            player!!.prepare(mediaSource!!)
            player!!.seekTo(nextIndex, 0)
        }
    }

    override fun onSeekProcessed() {
        if (seekResult != null) {
            seekProcessed = true
            if (player!!.playbackState == Player.STATE_READY) {
                completeSeek()
            }
        }
    }

    private fun completeSeek() {
        seekProcessed = false
        seekPos = null
        seekResult!!.success(HashMap<String, Any>())
        seekResult = null
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        ensurePlayerInitialized()
        val request = call.arguments as Map<*, *>
        try {
            when (call.method) {
                "load" -> {
                    val initialPosition = getLong(request["initialPosition"])
                    val initialIndex = request["initialIndex"] as Int?
                    load(getAudioSource(request["audioSource"]),
                            if (initialPosition == null) C.TIME_UNSET else initialPosition / 1000,
                            initialIndex, result)
                }
                "play" -> play(result)
                "pause" -> {
                    pause()
                    result.success(HashMap<String, Any>())
                }
                "setVolume" -> {
                    setVolume((request["volume"] as Double? as Double).toFloat())
                    result.success(HashMap<String, Any>())
                }
                "setSpeed" -> {
                    setSpeed((request["speed"] as Double? as Double).toFloat())
                    result.success(HashMap<String, Any>())
                }
                "setLoopMode" -> {
                    setLoopMode((request["loopMode"] as Int?)!!)
                    result.success(HashMap<String, Any>())
                }
                "setShuffleMode" -> {
                    setShuffleModeEnabled(request["shuffleMode"] as Int? == 1)
                    val response: MutableMap<String, Any> = HashMap()
                    response["shuffleIndices"] = shuffleIndices
                    result.success(response)
                }
                "setAutomaticallyWaitsToMinimizeStalling" -> result.success(HashMap<String, Any>())
                "seek" -> {
                    val position = getLong(request["position"])
                    val index = request["index"] as Int?
                    seek(if (position == null) C.TIME_UNSET else position / 1000, index, result)
                }
                "concatenatingInsertAll" -> concatenating(request["id"])
                        ?.addMediaSources((request["index"] as Int?)!!, getAudioSources(request["children"]), handler) { result.success(HashMap<String, Any>()) }
                "concatenatingRemoveRange" -> concatenating(request["id"])
                        ?.removeMediaSourceRange((request["startIndex"] as Int?)!!, (request["endIndex"] as Int?)!!, handler) { result.success(HashMap<String, Any>()) }
                "concatenatingMove" -> concatenating(request["id"])
                        ?.moveMediaSource((request["currentIndex"] as Int?)!!, (request["newIndex"] as Int?)!!, handler) { result.success(HashMap<String, Any>()) }
                "setAndroidAudioAttributes" -> {
                    setAudioAttributes((request["contentType"] as Int?)!!, (request["flags"] as Int?)!!, (request["usage"] as Int?)!!)
                    result.success(HashMap<String, Any>())
                }
                else -> result.notImplemented()
            }
        } catch (e: IllegalStateException) {
            e.printStackTrace()
            result.error("Illegal state: " + e.message, null, null)
        } catch (e: Exception) {
            e.printStackTrace()
            result.error("Error: $e", null, null)
        }
    }

    // Set the shuffle order for mediaSource, with currentIndex at
    // the first position. Traverse the tree incrementing index at each
    // node.
    private fun setShuffleOrder(mediaSource: MediaSource?, index: Int): Int {
        var newIndex = index
        when (mediaSource) {
            is ConcatenatingMediaSource -> {
                val source = mediaSource
                // Find which child is current
                var currentChildIndex: Int? = null
                for (i in 0 until source.size) {
                    val indexBefore = newIndex
                    val child = source.getMediaSource(i)
                    newIndex = setShuffleOrder(child, newIndex)
                    // If currentIndex falls within this child, make this child come first.
                    if (currentIndex!! in indexBefore until newIndex) {
                        currentChildIndex = i
                    }
                }
                // Shuffle so that the current child is first in the shuffle order
                source.setShuffleOrder(createShuffleOrder(source.size, currentChildIndex))
            }
            is LoopingMediaSource -> {
                val source = mediaSource
                // The ExoPlayer API doesn't provide accessors for these so we have
                // to index them ourselves.
                val child = loopingChildren[source]
                val count = loopingCounts[source]!!
                for (i in 0 until count) {
                    newIndex = setShuffleOrder(child, newIndex)
                }
            }
            else -> {
                // An actual media item takes up one spot in the playlist.
                newIndex++
            }
        }
        return newIndex
    }

    // Create a shuffle order optionally fixing the first index.
    private fun createShuffleOrder(length: Int, firstIndex: Int?): ShuffleOrder {
        shuffleIndices = shuffle(length, firstIndex)
        return DefaultShuffleOrder(shuffleIndices, random.nextLong())
    }

    private fun concatenating(index: Any?): ConcatenatingMediaSource? {
        return mediaSources[index] as ConcatenatingMediaSource?
    }

    private fun getAudioSource(json: Any?): MediaSource? {
        val map = json as Map<*, *>?
        val id = map!!["id"] as String?
        var mediaSource = mediaSources[id]
        if (mediaSource == null) {
            mediaSource = decodeAudioSource(map)
            mediaSources[id] = mediaSource
        }
        return mediaSource
    }

    private fun decodeAudioSource(json: Any?): MediaSource {
        val map = json as Map<*, *>?
        val id = map!!["id"] as String?
        return when (map["type"] as String?) {
            "progressive" -> ProgressiveMediaSource.Factory(buildDataSourceFactory())
                    .setTag(id)
                    .createMediaSource(Uri.parse(map["uri"] as String?))
            "dash" -> DashMediaSource.Factory(buildDataSourceFactory())
                    .setTag(id)
                    .createMediaSource(Uri.parse(map["uri"] as String?))
            "hls" -> HlsMediaSource.Factory(buildDataSourceFactory())
                    .setTag(id)
                    .createMediaSource(Uri.parse(map["uri"] as String?))
            "concatenating" -> {
                val mediaSources = getAudioSourcesArray(map["children"])
                ConcatenatingMediaSource(
                        false,  // isAtomic
                        (map["useLazyPreparation"] as Boolean?)!!,
                        DefaultShuffleOrder(mediaSources.size),
                        *mediaSources)
            }
            "clipping" -> {
                val start = getLong(map["start"])
                val end = getLong(map["end"])
                ClippingMediaSource(getAudioSource(map["child"]),
                        start ?: 0,
                        end ?: C.TIME_END_OF_SOURCE)
            }
            "looping" -> {
                val count = map["count"] as Int?
                val looperChild = getAudioSource(map["child"])
                val looper = LoopingMediaSource(looperChild, count!!)
                // TODO: store both in a single map
                loopingChildren[looper] = looperChild
                loopingCounts[looper] = count
                looper
            }
            else -> throw IllegalArgumentException("Unknown AudioSource type: " + map["type"])
        }
    }

    private fun getAudioSourcesArray(json: Any?): Array<MediaSource?> {
        val mediaSources = getAudioSources(json)
        //val mediaSourcesArray = mediaSources.toTypedArray().clone()
        //mediaSources.toArray(mediaSourcesArray) TODO
        return mediaSources.toTypedArray().clone()
    }

    private fun getAudioSources(json: Any?): List<MediaSource?> {
        val audioSources = json as List<*>?
        val mediaSources: MutableList<MediaSource?> = ArrayList()
        for (i in audioSources!!.indices) {
            mediaSources.add(getAudioSource(audioSources[i]))
        }
        return mediaSources
    }

    private fun buildDataSourceFactory(): DataSource.Factory {
        val userAgent = Util.getUserAgent(context, "just_audio")
        val httpDataSourceFactory: DataSource.Factory = DefaultHttpDataSourceFactory(
                userAgent,
                DefaultHttpDataSource.DEFAULT_CONNECT_TIMEOUT_MILLIS,
                DefaultHttpDataSource.DEFAULT_READ_TIMEOUT_MILLIS,
                true
        )
        return DefaultDataSourceFactory(context, httpDataSourceFactory)
    }

    private fun load(mediaSource: MediaSource?, initialPosition: Long, initialIndex: Int?, result: MethodChannel.Result) {
        initialPos = initialPosition
        this.initialIndex = initialIndex
        when (processingState) {
            ProcessingState.None -> {
            }
            ProcessingState.Loading -> {
                abortExistingConnection()
                player!!.stop()
            }
            else -> player!!.stop()
        }
        errorCount = 0
        prepareResult = result
        transition(ProcessingState.Loading)
        if (player!!.shuffleModeEnabled) {
            setShuffleOrder(mediaSource, 0)
        }
        this.mediaSource = mediaSource
        player!!.prepare(mediaSource!!)
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
        val builder = AudioAttributes.Builder()
        builder.setContentType(contentType)
        builder.setFlags(flags)
        builder.setUsage(usage)
        //builder.setAllowedCapturePolicy((Integer)json.get("allowedCapturePolicy"));
        player!!.audioAttributes = builder.build()
    }

    private fun broadcastPlaybackEvent() {
        val event: MutableMap<String, Any?> = HashMap()
        val updatePosition = currentPosition
        val duration = duration
        event["processingState"] = processingState.ordinal
        event["updatePosition"] = 1000 * updatePosition
        event["updateTime"] = System.currentTimeMillis()
        event["bufferedPosition"] = 1000 * updatePosition.coerceAtLeast(bufferedPosition)
        event["icyMetadata"] = collectIcyMetadata()
        event["duration"] = 1000 * duration
        event["currentIndex"] = currentIndex
        event["androidAudioSessionId"] = audioSessionId
        eventSink?.success(event)
    }

    private fun collectIcyMetadata(): Map<String, Any> {
        val icyData: MutableMap<String, Any> = HashMap()
        if (icyInfo != null) {
            val info: MutableMap<String, String?> = HashMap()
            info["title"] = icyInfo!!.title
            info["url"] = icyInfo!!.url
            icyData["info"] = info
        }
        if (icyHeaders != null) {
            val headers: MutableMap<String, Any?> = HashMap()
            headers["bitrate"] = icyHeaders!!.bitrate
            headers["genre"] = icyHeaders!!.genre
            headers["name"] = icyHeaders!!.name
            headers["metadataInterval"] = icyHeaders!!.metadataInterval
            headers["url"] = icyHeaders!!.url
            headers["isPublic"] = icyHeaders!!.isPublic
            icyData["headers"] = headers
        }
        return icyData
    }

    private val currentPosition: Long
        get() = if (processingState == ProcessingState.None || processingState == ProcessingState.Loading) {
            0
        } else if (seekPos != null && seekPos != C.TIME_UNSET) {
            seekPos!! //TODO
        } else {
            player!!.currentPosition
        }

    private val duration: Long
        get() {
            return if (processingState == ProcessingState.None || processingState == ProcessingState.Loading) {
                C.TIME_UNSET
            } else {
                player!!.duration
            }
        }

    private fun sendError(errorCode: String, errorMsg: String?) {
        if (prepareResult != null) {
            prepareResult!!.error(errorCode, errorMsg, null)
            prepareResult = null
        }
        eventSink?.error(errorCode, errorMsg, null)
    }

    private fun transition(newState: ProcessingState) {
        processingState = newState
        broadcastPlaybackEvent()
    }

    private fun play(result: MethodChannel.Result) {
        if (player!!.playWhenReady) {
            result.success(HashMap<String, Any>())
            return
        }
        if (playResult != null) {
            playResult!!.success(HashMap<String, Any>())
        }
        playResult = result
        startWatchingBuffer()
        player!!.playWhenReady = true
        if (processingState == ProcessingState.Completed && playResult != null) {
            playResult!!.success(HashMap<String, Any>())
            playResult = null
        }
    }

    private fun pause() {
        if (!player!!.playWhenReady) return
        player!!.playWhenReady = false
        if (playResult != null) {
            playResult!!.success(HashMap<String, Any>())
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
            setShuffleOrder(mediaSource, 0)
        }
        player!!.shuffleModeEnabled = enabled
    }

    private fun seek(position: Long, index: Int?, result: MethodChannel.Result) {
        if (processingState == ProcessingState.None || processingState == ProcessingState.Loading) {
            result.success(HashMap<String, Any>())
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
        if (processingState == ProcessingState.Loading) {
            abortExistingConnection()
        }
        mediaSources.clear()
        mediaSource = null
        loopingChildren.clear()
        if (player != null) {
            player!!.release()
            player = null
            transition(ProcessingState.None)
        }
        eventSink?.endOfStream()
    }

    private fun abortSeek() {
        if (seekResult != null) {
            seekResult!!.success(HashMap<String, Any>())
            seekResult = null
            seekPos = null
            seekProcessed = false
        }
    }

    private fun abortExistingConnection() {
        sendError("abort", "Connection aborted")
    }

    internal enum class ProcessingState {
        None, Loading, Buffering, Ready, Completed
    }

    companion object {
        const val TAG = "AudioPlayer"
        private val random = Random()
        private fun shuffle(length: Int, firstIndex: Int?): IntArray {
            val shuffleOrder = IntArray(length)
            for (i in 0 until length) {
                val j = random.nextInt(i + 1)
                shuffleOrder[i] = shuffleOrder[j]
                shuffleOrder[j] = i
            }
            if (firstIndex != null) {
                for (i in 1 until length) {
                    if (shuffleOrder[i] == firstIndex) {
                        val v = shuffleOrder[0]
                        shuffleOrder[0] = shuffleOrder[i]
                        shuffleOrder[i] = v
                        break
                    }
                }
            }
            return shuffleOrder
        }

        fun getLong(o: Any?): Long? {
            return if (o == null || o is Long) o as Long? else (o as Int).toLong()
        }
    }

    init {
        val methodChannel = MethodChannel(messenger, "com.ryanheise.just_audio.methods.$id")
        methodChannel.setMethodCallHandler(this)
        val eventChannel = EventChannel(messenger, "com.ryanheise.just_audio.events.$id")
        eventChannel.setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, eventSink: EventSink?) {
                this@AudioPlayer.eventSink = eventSink
            }

            override fun onCancel(arguments: Any?) {
                eventSink = null
            }
        })
        processingState = ProcessingState.None
    }
}