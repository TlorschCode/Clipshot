package dev.rylry.clip

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.widget.Toast
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.FileOutputStream


class RecordingService : Service() {
    private var fileName: String ? = null
    var sampleRate: Int = 44100 // Example sample rate
    var recordLength: Int = 30
    var channelConfig: Int = AudioFormat.CHANNEL_IN_MONO
    var audioFormat: Int = AudioFormat.ENCODING_PCM_16BIT
    var bufferSize: Int = sampleRate * recordLength * 2 // Because PCM16 is 2 bytes/sample
    var ringBuffer = ByteArray(bufferSize)
    var ringStart: Int = 0
    var samplesForUpdate: Int = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
    var isRecording: Boolean = false
    var recordingThread: Thread? = null
    var record: AudioRecord? = null

    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): RecordingService = this@RecordingService
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }


    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override fun onCreate() {
        super.onCreate()
        record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channelConfig,
            audioFormat,
            bufferSize
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Create notification
        val channelId = "RecordingServiceChannel"
        createNotificationChannel(channelId)
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Recording Audio")
            .setContentText("Your audio is being recorded")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()

        // Start foreground
        startForeground(1, notification)

        // Start recording in background thread
        startRecording()

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRecording()
        record?.release()
        record = null
    }

    private fun createNotificationChannel(channelId: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Audio Recording Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    fun startRecording(){
        isRecording = true
        record?.startRecording()
        recordingThread = Thread {
            while (isRecording) {
                record?.read(ringBuffer, ringStart, if(ringStart + samplesForUpdate < bufferSize) samplesForUpdate else bufferSize - ringStart)
                if(ringStart + samplesForUpdate >= bufferSize) record?.read(ringBuffer, 0, samplesForUpdate - bufferSize + ringStart)
                ringStart = (ringStart + samplesForUpdate) % bufferSize
            }
        }
        recordingThread?.start()
    }

    fun stopRecording() {
        isRecording = false
        try {
            recordingThread?.join()
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
        record?.stop()
    }

    fun saveCurrentBufferToFile() {
        val file = File(filesDir, "audio_${System.currentTimeMillis()}.wav")
        val size = ringBuffer.size
        writeWavHeader(file, ringBuffer.size, sampleRate, if(channelConfig == AudioFormat.CHANNEL_IN_MONO) 1 else 2)
        val first: ByteArray = ringBuffer.copyOfRange(ringStart, size)
        val second: ByteArray = ringBuffer.copyOfRange(0, ringStart)
        FileOutputStream(file, true).use { out ->
            out.write(first)
            out.write(second)
        }
    }

    fun writeWavHeader(file: File, size: Int, sampleRate: Int = 44100, channels: Int = 1) {
        val byteRate = 16 * sampleRate * channels / 8
        val totalDataLen = size + 36
        val totalAudioLen = size.toLong()

        FileOutputStream(file).use { out ->
            // RIFF header
            out.write("RIFF".toByteArray(Charsets.US_ASCII))
            out.write(intToByteArray(totalDataLen))   // ChunkSize
            out.write("WAVE".toByteArray(Charsets.US_ASCII))

            // fmt subchunk
            out.write("fmt ".toByteArray(Charsets.US_ASCII))
            out.write(intToByteArray(16))             // Subchunk1Size
            out.write(shortToByteArray(1))            // AudioFormat = PCM
            out.write(shortToByteArray(channels.toShort()))
            out.write(intToByteArray(sampleRate))
            out.write(intToByteArray(byteRate))
            out.write(shortToByteArray((channels * 16 / 8).toShort())) // BlockAlign
            out.write(shortToByteArray(16))           // BitsPerSample

            // data subchunk
            out.write("data".toByteArray(Charsets.US_ASCII))
            out.write(intToByteArray(totalAudioLen.toInt()))
        }
    }

    private fun intToByteArray(value: Int): ByteArray {
        return byteArrayOf(
            (value and 0xff).toByte(),
            ((value shr 8) and 0xff).toByte(),
            ((value shr 16) and 0xff).toByte(),
            ((value shr 24) and 0xff).toByte()
        )
    }

    private fun shortToByteArray(value: Short): ByteArray {
        return byteArrayOf(
            (value.toInt() and 0xff).toByte(),
            ((value.toInt() shr 8) and 0xff).toByte()
        )
    }


}
