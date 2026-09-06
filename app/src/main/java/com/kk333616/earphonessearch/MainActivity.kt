package com.kk333616.earphonessearch

import android.app.Activity
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
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
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

class MainActivity : Activity() {

    private lateinit var audioManager: AudioManager
    private lateinit var statusText: TextView
    private lateinit var volumeText: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button

    private val mainHandler = Handler(Looper.getMainLooper())
    private val searching = AtomicBoolean(false)

    @Volatile
    private var currentTrack: AudioTrack? = null

    private var originalMediaVolume: Int? = null

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

                Androidで現在選択されているメディア出力先を使用します。
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

        Thread {
            val sampleRate = 44_100
            val minBuffer = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            if (minBuffer <= 0) {
                mainHandler.post {
                    Toast.makeText(this, "音声出力を初期化できませんでした", Toast.LENGTH_LONG).show()
                }
                searching.set(false)
                restoreOriginalVolume()
                mainHandler.post {
                    startButton.isEnabled = true
                    stopButton.isEnabled = false
                    updateCurrentVolumeText()
                }
                return@Thread
            }

            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(minBuffer * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            currentTrack = track

            try {
                if (track.state != AudioTrack.STATE_INITIALIZED) {
                    throw IllegalStateException("AudioTrack initialization failed")
                }

                // BluetoothイヤホンがAndroidのメディア出力先になっている場合、
                // Androidの通常のメディアルーティングに任せる方が機種差に強い。
                // setPreferredDevice()で特定のBluetoothプロファイルを強制しない。
                setSystemMediaVolume(0.30f)
                track.play()

                val startTime = System.currentTimeMillis()
                var beepIndex = 0

                while (searching.get()) {
                    val elapsed = System.currentTimeMillis() - startTime
                    val targetVolume = when {
                        elapsed < 6_000L -> 0.30f
                        elapsed < 12_000L -> 0.50f
                        elapsed < 18_000L -> 0.70f
                        else -> 1.00f
                    }

                    setSystemMediaVolume(targetVolume)
                    val percent = (targetVolume * 100).roundToInt()
                    mainHandler.post {
                        volumeText.text = "探索中：メディア音量 $percent%"
                    }

                    val frequency = if (beepIndex % 2 == 0) 2200.0 else 3200.0
                    val beep = makeBeepBuffer(
                        sampleRate = sampleRate,
                        frequencyHz = frequency,
                        beepMs = 430,
                        silenceMs = 170
                    )

                    val written = track.write(beep, 0, beep.size, AudioTrack.WRITE_BLOCKING)
                    if (written < 0) {
                        throw IllegalStateException("AudioTrack write failed: $written")
                    }
                    beepIndex++
                }
            } catch (_: Exception) {
                mainHandler.post {
                    Toast.makeText(
                        this,
                        "探索音の再生中にエラーが発生しました",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } finally {
                try {
                    track.pause()
                    track.flush()
                    track.stop()
                } catch (_: Exception) {
                }

                try {
                    track.release()
                } catch (_: Exception) {
                }

                currentTrack = null
                restoreOriginalVolume()

                mainHandler.post {
                    searching.set(false)
                    startButton.isEnabled = true
                    stopButton.isEnabled = false
                    updateCurrentVolumeText()
                    updateDeviceStatus()
                }
            }
        }.start()
    }

    private fun makeBeepBuffer(
        sampleRate: Int,
        frequencyHz: Double,
        beepMs: Int,
        silenceMs: Int
    ): ShortArray {
        val beepSamples = sampleRate * beepMs / 1000
        val silenceSamples = sampleRate * silenceMs / 1000
        val result = ShortArray(beepSamples + silenceSamples)
        val fadeSamples = (sampleRate * 0.015).roundToInt().coerceAtLeast(1)

        for (i in 0 until beepSamples) {
            val fadeIn = (i.toFloat() / fadeSamples).coerceIn(0f, 1f)
            val fadeOut = ((beepSamples - i).toFloat() / fadeSamples).coerceIn(0f, 1f)
            val envelope = minOf(fadeIn, fadeOut)

            val sample = sin(2.0 * PI * frequencyHz * i / sampleRate)
            result[i] = (sample * Short.MAX_VALUE * 0.75 * envelope).toInt().toShort()
        }

        return result
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

    private fun stopSearchSound(message: String? = null) {
        if (!searching.getAndSet(false)) return

        try {
            currentTrack?.stop()
        } catch (_: Exception) {
        }

        if (message != null) {
            mainHandler.post {
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        }

        restoreOriginalVolume()
        mainHandler.post {
            startButton.isEnabled = true
            stopButton.isEnabled = false
            updateCurrentVolumeText()
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
