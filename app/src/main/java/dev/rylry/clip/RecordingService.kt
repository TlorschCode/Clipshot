package dev.rylry.clip

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.FileInputStream
import kotlin.math.min

const val EXTRA_SAVE_BUFFERS = "save_buffers"
class RecordingService : Service() {
    var sampleRateHz: Int = 44100 // Example sample rate
    var recordDurationSeconds: Int = 30
    var channelConfig: Int = AudioFormat.CHANNEL_IN_MONO
    var audioFormat: Int = AudioFormat.ENCODING_PCM_16BIT
    var audioBufferSize: Int = sampleRateHz * recordDurationSeconds * 2 // Because PCM16 is 2 bytes/sample
    var audioBuffer = ByteArray(audioBufferSize)
    var audioWritePointer: Int = 0
    var audioChunkSize: Int = AudioRecord.getMinBufferSize(sampleRateHz, channelConfig, audioFormat)
    var isRecording: Boolean = false
    var recordingThread: Thread? = null
    var record: AudioRecord? = null
    private val binder = AudioServiceBinder()

    inner class AudioServiceBinder : Binder() {
        fun getService(): RecordingService = this@RecordingService
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }


    @RequiresPermission(allOf = [Manifest.permission.RECORD_AUDIO])
    override fun onCreate() {
        super.onCreate()
        registerReceiver(saveReceiver, IntentFilter("dev.rylry.SAVE_BUFFERS"), Context.RECEIVER_EXPORTED)
        record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRateHz,
            channelConfig,
            audioFormat,
            audioBufferSize
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val saveIntent = Intent("dev.rylry.SAVE_BUFFERS")
        val pendingIntent: PendingIntent = PendingIntent.getBroadcast(
            this,
            0,
            saveIntent,
            PendingIntent.FLAG_IMMUTABLE
        )
        // Create notification
        val channelId = "RecordingServiceChannel"
        createNotificationChannel(channelId)
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Recording Audio")
            .setContentText("Your audio is being recorded")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .addAction(
                android.R.drawable.ic_btn_speak_now,
                "Save",
                pendingIntent
            )
            .build()

        // Start foreground
        startForeground(1, notification)

        // Start recording in background thread
        startRecording()
        return START_STICKY
    }

    private val saveReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            saveBuffersM4A()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRecording()
        unregisterReceiver(saveReceiver)
        record?.release()
        record = null
    }

    private fun createNotificationChannel(channelId: String) {
        val channel = NotificationChannel(
            channelId,
            "Audio Recording Service",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    fun startRecording(){
        isRecording = true
        record?.startRecording()
        recordingThread = Thread {
            while (isRecording) {
                record?.read(audioBuffer, audioWritePointer, if(audioWritePointer + audioChunkSize < audioBufferSize) audioChunkSize else audioBufferSize - audioWritePointer)
                if(audioWritePointer + audioChunkSize >= audioBufferSize) record?.read(audioBuffer, 0, audioChunkSize - audioBufferSize + audioWritePointer)
                audioWritePointer = (audioWritePointer + audioChunkSize) % audioBufferSize
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

    fun saveBuffersM4A() {
        val clipTime = System.currentTimeMillis()
        val outputFile = File(filesDir, "cache.m4a")

        val first = audioBuffer.copyOfRange(audioWritePointer, audioBuffer.size)
        val second = audioBuffer.copyOfRange(0, audioWritePointer)
        val pcmData = first + second

        val sampleRate = 44100
        val channelCount = 1 // mono
        val bitRate = 128_000

        // --- Setup encoder ---
        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC,
            sampleRate,
            channelCount
        ).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }

        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()

        // --- Setup muxer ---
        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxerTrackIndex = -1
        var muxerStarted = false

        val bufferInfo = MediaCodec.BufferInfo()
        val inputBuffers = encoder.inputBuffers
        val outputBuffers = encoder.outputBuffers

        var pcmOffset = 0

        while (pcmOffset < pcmData.size) {
            // Feed PCM to encoder
            val inputIndex = encoder.dequeueInputBuffer(10000)
            if (inputIndex >= 0) {
                val inputBuffer = inputBuffers[inputIndex]
                inputBuffer.clear()

                val bytesToWrite = min(inputBuffer.capacity(), pcmData.size - pcmOffset)
                inputBuffer.put(pcmData, pcmOffset, bytesToWrite)

                val presentationTimeUs = (pcmOffset.toLong() * 1_000_000L) / (sampleRate * 2 * channelCount)
                encoder.queueInputBuffer(inputIndex, 0, bytesToWrite, presentationTimeUs, 0)
                pcmOffset += bytesToWrite
            }

            // Get encoded output
            var outputIndex = encoder.dequeueOutputBuffer(bufferInfo, 10000)
            while (outputIndex >= 0) {
                val encodedData = outputBuffers[outputIndex]
                encodedData.position(bufferInfo.offset)
                encodedData.limit(bufferInfo.offset + bufferInfo.size)

                if (!muxerStarted) {
                    val newFormat = encoder.outputFormat
                    muxerTrackIndex = muxer.addTrack(newFormat)
                    muxer.start()
                    muxerStarted = true
                }

                muxer.writeSampleData(muxerTrackIndex, encodedData, bufferInfo)
                encoder.releaseOutputBuffer(outputIndex, false)
                outputIndex = encoder.dequeueOutputBuffer(bufferInfo, 0)
            }
        }

        // --- Signal end of stream ---
        val inputIndex = encoder.dequeueInputBuffer(10000)
        if (inputIndex >= 0) {
            encoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        }

        // Drain remaining output
        var outputIndex = encoder.dequeueOutputBuffer(bufferInfo, 10000)
        while (outputIndex >= 0) {
            val encodedData = outputBuffers[outputIndex]
            encodedData.position(bufferInfo.offset)
            encodedData.limit(bufferInfo.offset + bufferInfo.size)

            muxer.writeSampleData(muxerTrackIndex, encodedData, bufferInfo)
            encoder.releaseOutputBuffer(outputIndex, false)
            outputIndex = encoder.dequeueOutputBuffer(bufferInfo, 0)
        }
        // --- Cleanup ---
        encoder.stop()
        encoder.release()
        muxer.stop()
        muxer.release()

        // Make file accessible to all
        downloadCache(this, clipTime)
    }

    fun downloadCache(context: Context, time: Long) {
        val sourceFile = File(context.filesDir, "cache.m4a")
        if (!sourceFile.exists()) return

        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "clip_${time}")
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
            put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }

        val resolver = context.contentResolver
        val uri: Uri = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        } else {
            val musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
            if (!musicDir.exists()) musicDir.mkdirs()
            val destFile = File(musicDir, "clip_${time}.m4a")
            Uri.fromFile(destFile)
        })!!

        resolver.openOutputStream(uri, "w")?.use { out ->
            FileInputStream(sourceFile).use { input ->
                input.copyTo(out)
            }
            out.flush()   // ensure all bytes are written
        }
    }
}
