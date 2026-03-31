package com.example.heartratemonitor

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random

object RandomAudioController {
    private const val tag = "HR_AUDIO"
    private const val ASSET_DIR = "sounds"
    private const val MIN_GAP_MS = 2 * 60 * 1000L
    private const val MAX_GAP_MS = 5 * 60 * 1000L
    private const val PLAY_WINDOW_MS = 60 * 1000L

    private var appContext: Context? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val playerMutex = Mutex()

    @Volatile
    private var automationActive = false

    @Volatile
    private var activeAssetPath: String? = null

    private var schedulerJob: Job? = null
    private var player: MediaPlayer? = null

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        Log.i(tag, "Audio controller initialized dir=$ASSET_DIR")
    }

    fun setAutomationActive(active: Boolean) {
        val context = appContext ?: return
        if (automationActive == active && (active.not() || schedulerJob?.isActive == true)) {
            return
        }
        Log.i(
            tag,
            "Automation audio gate active=$active schedulerRunning=${schedulerJob?.isActive == true} file=${activeAssetPath.orEmpty()}"
        )
        automationActive = active
        scope.launch {
            if (active) {
                startSchedulerIfNeeded(context)
            } else {
                stopScheduler(reason = "automation_inactive")
            }
        }
    }

    private suspend fun startSchedulerIfNeeded(context: Context) {
        if (schedulerJob?.isActive == true) {
            Log.i(tag, "Audio scheduler already active")
            return
        }
        schedulerJob = scope.launch {
            Log.i(tag, "Audio scheduler starting")
            ResearchLogger.logEvent(
                event = "audio_scheduler_start",
                fromMode = HrAutomationController.currentMode.value,
                toMode = HrAutomationController.currentMode.value,
                details = "dir=$ASSET_DIR"
            )
            try {
                runPlaybackLoop(context)
            } finally {
                if (!automationActive) {
                    stopPlayer(reason = "automation_inactive")
                }
            }
        }
    }

    private suspend fun stopScheduler(reason: String) {
        val job = schedulerJob
        schedulerJob = null
        Log.i(tag, "Audio scheduler stopping reason=$reason")
        job?.cancelAndJoin()
        stopPlayer(reason = reason)
        ResearchLogger.logEvent(
            event = "audio_scheduler_stop",
            fromMode = HrAutomationController.currentMode.value,
            toMode = HrAutomationController.currentMode.value,
            details = reason
        )
    }

    private suspend fun runPlaybackLoop(context: Context) {
        while (currentCoroutineContext().isActive && automationActive) {
            val assets = listPlayableAssets(context)
            if (assets.isEmpty()) {
                Log.w(tag, "Audio scan found no playable files in $ASSET_DIR")
                ResearchLogger.logEvent(
                    event = "audio_assets_missing",
                    fromMode = HrAutomationController.currentMode.value,
                    toMode = HrAutomationController.currentMode.value,
                    success = false,
                    details = "dir=$ASSET_DIR"
                )
                return
            }
            Log.i(tag, "Audio scan found ${assets.size} playable files")

            val gapMs = Random.nextLong(MIN_GAP_MS, MAX_GAP_MS + 1)
            Log.i(tag, "Audio gap scheduled ${gapMs}ms")
            ResearchLogger.logEvent(
                event = "audio_gap_scheduled",
                fromMode = HrAutomationController.currentMode.value,
                toMode = HrAutomationController.currentMode.value,
                details = "gap_ms=$gapMs"
            )
            delay(gapMs)
            if (!automationActive || !currentCoroutineContext().isActive) {
                return
            }

            val selectedAsset = assets.random()
            Log.i(tag, "Audio file selected file=$selectedAsset")
            ResearchLogger.logEvent(
                event = "audio_asset_selected",
                fromMode = HrAutomationController.currentMode.value,
                toMode = HrAutomationController.currentMode.value,
                details = "file=$selectedAsset asset_count=${assets.size}"
            )
            playRandomAsset(context, selectedAsset)
        }
    }

    private suspend fun playRandomAsset(context: Context, assetPath: String) {
        Log.i(tag, "Preparing audio file=$assetPath")
        val mediaPlayer = preparePlayer(context, assetPath) ?: run {
            Log.w(tag, "Audio file not playable file=$assetPath")
            ResearchLogger.logEvent(
                event = "audio_prepare_failed",
                fromMode = HrAutomationController.currentMode.value,
                toMode = HrAutomationController.currentMode.value,
                success = false,
                details = assetPath
            )
            return
        }

        val assetDurationMs = mediaPlayer.duration.takeIf { it > 0 }?.toLong() ?: PLAY_WINDOW_MS
        val shouldLoop = assetDurationMs < PLAY_WINDOW_MS
        mediaPlayer.isLooping = shouldLoop
        Log.i(
            tag,
            "Audio file playable file=$assetPath duration_ms=$assetDurationMs loop=$shouldLoop play_window_ms=$PLAY_WINDOW_MS"
        )
        ResearchLogger.logEvent(
            event = "audio_prepare_success",
            fromMode = HrAutomationController.currentMode.value,
            toMode = HrAutomationController.currentMode.value,
            success = true,
            details = "file=$assetPath duration_ms=$assetDurationMs loop=$shouldLoop"
        )

        playerMutex.withLock {
            player = mediaPlayer
            activeAssetPath = assetPath
        }

        Log.i(tag, "Audio playback starting file=$assetPath")
        ResearchLogger.logEvent(
            event = "audio_play_start",
            fromMode = HrAutomationController.currentMode.value,
            toMode = HrAutomationController.currentMode.value,
            details = "file=$assetPath duration_ms=$assetDurationMs loop=$shouldLoop"
        )

        try {
            mediaPlayer.start()
            Log.i(tag, "Audio playback started file=$assetPath")
            delay(PLAY_WINDOW_MS)
        } finally {
            val stopReason =
                if (automationActive && currentCoroutineContext().isActive) {
                    "window_complete"
                } else {
                    "automation_inactive"
                }
            stopPlayer(reason = stopReason)
        }
    }

    private suspend fun stopPlayer(reason: String) {
        playerMutex.withLock {
            val currentPlayer = player
            val assetPath = activeAssetPath
            player = null
            activeAssetPath = null

            if (currentPlayer != null) {
                Log.i(tag, "Audio playback stopping file=${assetPath.orEmpty()} reason=$reason")
                runCatching {
                    if (currentPlayer.isPlaying) {
                        currentPlayer.stop()
                    }
                }
                currentPlayer.release()
                Log.i(tag, "Audio playback stopped file=${assetPath.orEmpty()} reason=$reason")
                ResearchLogger.logEvent(
                    event = "audio_play_stop",
                    fromMode = HrAutomationController.currentMode.value,
                    toMode = HrAutomationController.currentMode.value,
                    details = "file=${assetPath.orEmpty()} reason=$reason"
                )
            } else {
                Log.i(tag, "Audio stop requested with no active player reason=$reason")
            }
        }
    }

    private fun preparePlayer(context: Context, assetPath: String): MediaPlayer? {
        return runCatching {
            context.assets.openFd(assetPath).use { afd ->
                MediaPlayer().apply {
                    setOnErrorListener { _, what, extra ->
                        Log.e(tag, "Audio playback error file=$assetPath what=$what extra=$extra")
                        ResearchLogger.logEvent(
                            event = "audio_play_error",
                            fromMode = HrAutomationController.currentMode.value,
                            toMode = HrAutomationController.currentMode.value,
                            success = false,
                            details = "file=$assetPath what=$what extra=$extra"
                        )
                        false
                    }
                    setOnCompletionListener {
                        Log.i(tag, "Audio playback completed naturally file=$assetPath")
                        ResearchLogger.logEvent(
                            event = "audio_play_complete",
                            fromMode = HrAutomationController.currentMode.value,
                            toMode = HrAutomationController.currentMode.value,
                            success = true,
                            details = "file=$assetPath"
                        )
                    }
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                    prepare()
                }
            }
        }.onFailure { error ->
            Log.e(tag, "Audio prepare failed file=$assetPath", error)
        }.getOrNull()
    }

    private fun listPlayableAssets(context: Context): List<String> {
        val fileNames = context.assets.list(ASSET_DIR)?.toList().orEmpty()
        val playableAssets = fileNames
            .filter { name ->
                name.endsWith(".mp3", ignoreCase = true) ||
                    name.endsWith(".wav", ignoreCase = true) ||
                    name.endsWith(".m4a", ignoreCase = true) ||
                    name.endsWith(".ogg", ignoreCase = true) ||
                    name.endsWith(".aac", ignoreCase = true) ||
                    name.endsWith(".flac", ignoreCase = true)
            }
            .map { "$ASSET_DIR/$it" }
        Log.i(
            tag,
            "Audio asset listing total=${fileNames.size} playable=${playableAssets.size} files=${playableAssets.joinToString()}"
        )
        return playableAssets
    }
}
