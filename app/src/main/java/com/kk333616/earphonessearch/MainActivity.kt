package com.kk333616.earphonessearch

import android.app.Activity
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

class MainActivity : Activity() {

    private lateinit var audioManager: AudioManager
    private lateinit var statusText: TextView
    private lateinit var volumeText: TextView
    private lateinit var playbackText: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button

    private val mainHandler = Handler(Looper.getMainLooper())
    private val searching = AtomicBoolean(false)

    @Volatile
    private var currentPlayer: MediaPlayer? = null

    @Volatile
    private var volumeThread: Thread? = null

    private var originalMediaVolume: Int? = null
    private var toneFile: File? = null

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            updateDeviceStatus()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            updateDeviceStatus()
            if (findBluetoothOutput() == null && searching.get()) {
                stopSearchSound("イヤホンとの接続が切れたため停止しました")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        setContentView(createScreen())

        audioManager.registerAudioDeviceCallback(audioDeviceCallback, mainHandler)
        updateDeviceStatus()
        updateCurrentVolumeText()
    }

    override fun onDestroy() {
        stopSearchSound()
        try {
            audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    private fun createScreen(): View {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).roundToInt()

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(36), dp(24), dp(36))
        }

        content.addView(TextView(this).apply {
            text = "イヤホン探すんだホン"
            textSize = 28f
        })

        content.addView(TextView(this).apply {
            text = "家の中で見失ったBluetoothイヤホンを探索音で探します。"
            textSize = 15f
            setPadding(0, dp(10), 0, dp(26))
        })

        content.addView(TextView(this).apply {
            text = "接続状態"
            textSize = 14f
        })

        statusText = TextView(this).apply {
            textSize = 20f
            setPadding(0, dp(8), 0, dp(22))
        }
        content.addView(statusText)

        content.addView(Button(this).apply {
            text = "接続状態を更新"
            setOnClickListener {
                updateDeviceStatus()
                updateCurrentVolumeText()
            }
        })

        volumeText = TextView(this).apply {
            textSize = 16f
            setPadding(0, dp(24), 0, dp(8))
        }
        content.addView(volumeText)

        playbackText = TextView(this).apply {
            text = "再生状態：停止中"
            textSize = 14f
            setPadding(0, dp(4), 0, dp(14))
        }
        content.addView(playbackText)

        content.addView(TextView(this).apply {
            text = "注意：探索中は最大音量まで上がります。イヤホンを耳に装着した状態では開始しないでください。停止すると開始前のメディア音量へ戻します。"
            textSize = 14f
            setPadding(0, dp(8), 0, dp(20))
        })

        startButton = Button(this).apply {
            text = "🔊 探索音を鳴らす"
            setOnClickListener { startSearchSound() }
        }
        content.addView(startButton)

        stopButton = Button(this).apply {
            text = "■ 停止"
            isEnabled = false
            setOnClickListener { stopSearchSound() }
        }
        content.addView(stopButton)

        content.addView(TextView(this).apply {
            text = """

                探索音の動作
                ・0〜6秒：メディア音量 30%
                ・6〜12秒：50%
                ・12〜18秒：70%
                ・18秒以降：100%
                ・2.2kHz / 3.2kHz の高めの音を交互に再生

                Androidの通常のメディア再生経路を使用します。
                Bluetoothイヤホンをスマホの音声出力先にした状態で開始してください。
                ケースに入って接続が切れている場合や、イヤホンの電池が切れている場合は音を鳴らせません。
            """.trimIndent()
            textSize = 14f
            setPadding(0, dp(26), 0, 0)
        })

        return ScrollView(this).apply {
            addView(
                content,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
    }

    private fun updateDeviceStatus() {
        val device = findBluetoothOutput()

        mainHandler.post {
            if (device == null) {
                statusText.text = "● Bluetoothイヤホン未検出"
                startButton.isEnabled = !searching.get()
            } else {
                val name = device.productName?.toString()?.takeIf { it.isNotBlank() }
                    ?: "Bluetoothイヤホン"
                statusText.text = "● 接続中：$name"
                startButton.isEnabled = !searching.get()
            }
        }
    }

    private fun findBluetoothOutput(): AudioDeviceInfo? {
        return audioManager
            .getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { isBluetoothAudioDevice(it) }
    }

    private fun isBluetoothAudioDevice(device: AudioDeviceInfo): Boolean {
        return when (device.type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_HEARING_AID -> true

            else -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    device.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                        device.type == AudioDeviceInfo.TYPE_BLE_SPEAKER
                } else {
                    false
                }
            }
        }
    }

    private fun startSearchSound() {
        if (searching.get()) return

        if (findBluetoothOutput() == null) {
            Toast.makeText(
                this,
                "Bluetoothイヤホンが接続されていません",
                Toast.LENGTH_LONG
            ).show()
            updateDeviceStatus()
            return
        }

        originalMediaVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        searching.set(true)
        startButton.isEnabled = false
        stopButton.isEnabled = true
        playbackText.text = "再生状態：探索音を準備中"

        try {
            val file = createSearchToneWav()
            toneFile = file

            val player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                setDataSource(file.absolutePath)
                isLooping = true
                setVolume(1.0f, 1.0f)
                prepare()
            }

            currentPlayer = player
            setSystemMediaVolume(0.30f)
            player.start()
            playbackText.text = "再生状態：再生中"

            mainHandler.postDelayed({
                val activePlayer = currentPlayer
                if (searching.get() && activePlayer != null) {
                    val route = activePlayer.routedDevice
                    val routeName = route?.productName?.toString()?.takeIf { it.isNotBlank() }
                    playbackText.text = if (routeName != null) {
                        "再生状態：再生中 / 出力先：$routeName"
                    } else {
                        "再生状態：再生中 / 出力先：Androidのメディア出力"
                    }
                }
            }, 800L)

            val thread = Thread {
                val startTime = System.currentTimeMillis()
                var lastPercent = -1

                while (searching.get()) {
                    val elapsed = System.currentTimeMillis() - startTime
                    val targetVolume = when {
                        elapsed < 6_000L -> 0.30f
                        elapsed < 12_000L -> 0.50f
                        elapsed < 18_000L -> 0.70f
                        else -> 1.00f
                    }

                    val percent = (targetVolume * 100).roundToInt()
                    if (percent != lastPercent) {
                        setSystemMediaVolume(targetVolume)
                        lastPercent = percent
                        mainHandler.post {
                            volumeText.text = "探索中：メディア音量 $percent%"
                        }
                    }

                    try {
                        Thread.sleep(200L)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }
            volumeThread = thread
            thread.start()
        } catch (e: Exception) {
            searching.set(false)
            safelyReleasePlayer()
            restoreOriginalVolume()
            startButton.isEnabled = true
            stopButton.isEnabled = false
            playbackText.text = "再生状態：エラー (${e.javaClass.simpleName})"
            Toast.makeText(
                this,
                "探索音を開始できませんでした：${e.javaClass.simpleName}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun createSearchToneWav(): File {
        val sampleRate = 44_100
        val beepMs = 430
        val silenceMs = 170
        val sequence = doubleArrayOf(2200.0, 3200.0)
        val beepSamples = sampleRate * beepMs / 1000
        val silenceSamples = sampleRate * silenceMs / 1000
        val samplesPerTone = beepSamples + silenceSamples
        val totalSamples = samplesPerTone * sequence.size
        val pcm = ShortArray(totalSamples)
        val fadeSamples = (sampleRate * 0.015).roundToInt().coerceAtLeast(1)

        sequence.forEachIndexed { toneIndex, frequencyHz ->
            val offset = toneIndex * samplesPerTone
            for (i in 0 until beepSamples) {
                val fadeIn = (i.toFloat() / fadeSamples).coerceIn(0f, 1f)
                val fadeOut = ((beepSamples - i).toFloat() / fadeSamples).coerceIn(0f, 1f)
                val envelope = minOf(fadeIn, fadeOut)
                val sample = sin(2.0 * PI * frequencyHz * i / sampleRate)
                pcm[offset + i] = (sample * Short.MAX_VALUE * 0.80 * envelope).toInt().toShort()
            }
        }

        val pcmBytes = ByteBuffer.allocate(pcm.size * 2)
            .order(ByteOrder.LITTLE_ENDIAN)
        pcm.forEach { pcmBytes.putShort(it) }
        val audioData = pcmBytes.array()

        val file = File(cacheDir, "search_tone.wav")
        FileOutputStream(file).use { out ->
            val dataSize = audioData.size
            val byteRate = sampleRate * 2
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray(Charsets.US_ASCII))
                putInt(36 + dataSize)
                put("WAVE".toByteArray(Charsets.US_ASCII))
                put("fmt ".toByteArray(Charsets.US_ASCII))
                putInt(16)
                putShort(1.toShort())
                putShort(1.toShort())
                putInt(sampleRate)
                putInt(byteRate)
                putShort(2.toShort())
                putShort(16.toShort())
                put("data".toByteArray(Charsets.US_ASCII))
                putInt(dataSize)
            }.array()

            out.write(header)
            out.write(audioData)
        }

        return file
    }

    private fun setSystemMediaVolume(fraction: Float) {
        if (audioManager.isVolumeFixed) return

        try {
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val value = (maxVolume * fraction)
                .roundToInt()
                .coerceIn(1, maxVolume)

            audioManager.setStreamVolume(
                AudioManager.STREAM_MUSIC,
                value,
                0
            )
        } catch (_: SecurityException) {
        }
    }

    private fun restoreOriginalVolume() {
        val original = originalMediaVolume ?: return
        originalMediaVolume = null

        if (audioManager.isVolumeFixed) return

        try {
            audioManager.setStreamVolume(
                AudioManager.STREAM_MUSIC,
                original,
                0
            )
        } catch (_: SecurityException) {
        }
    }

    private fun safelyReleasePlayer() {
        val player = currentPlayer
        currentPlayer = null
        if (player != null) {
            try {
                if (player.isPlaying) player.stop()
            } catch (_: Exception) {
            }
            try {
                player.reset()
            } catch (_: Exception) {
            }
            try {
                player.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun stopSearchSound(message: String? = null) {
        if (!searching.getAndSet(false)) return

        volumeThread?.interrupt()
        volumeThread = null
        safelyReleasePlayer()
        restoreOriginalVolume()

        playbackText.text = "再生状態：停止中"
        startButton.isEnabled = true
        stopButton.isEnabled = false
        updateCurrentVolumeText()
        updateDeviceStatus()

        if (message != null) {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun updateCurrentVolumeText() {
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val percent = if (max > 0) ((current.toFloat() / max) * 100).roundToInt() else 0

        mainHandler.post {
            volumeText.text = "現在のメディア音量：約 $percent%"
        }
    }
}
