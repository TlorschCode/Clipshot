package dev.rylry.clip

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.hardware.camera2.CameraAccessException
import android.os.Binder
import android.os.IBinder
import android.util.Log
import android.view.Surface
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationCompat
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.min


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
    var isRecordingVideo: Boolean = false
    var recordingThread: Thread? = null
    var record: AudioRecord? = null

    lateinit var cameraDevice: CameraDevice
    lateinit var videoBuffer : ArrayDeque<Pair<ByteArray, MediaCodec.BufferInfo>>
    lateinit var encoder: MediaCodec
    lateinit var inputSurface: Surface

    private var videoBasePts: Long = -1
    private var lastVideoPts: Long = 0

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
        startAudio()
        return START_STICKY
    }

    private val saveReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            saveMedia()
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

    fun startAudio(){
        isRecording = true
        record?.startRecording()
        recordingThread = Thread {
            while (isRecording) {
                val firstChunkSize = min(audioChunkSize, audioBufferSize - audioWritePointer)

                // Read up to firstChunkSize frames
                val readFirst = record?.read(audioBuffer, audioWritePointer, firstChunkSize) ?: 0

                // If there’s remaining to wrap around the circular buffer
                val remaining = audioChunkSize - readFirst
                if (remaining > 0) {
                    // Only read as much as fits at the start of the buffer
                    record?.read(audioBuffer, 0, remaining)
                }
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

    @RequiresPermission(Manifest.permission.CAMERA)
    fun startCamera() {
        val manager: CameraManager = getSystemService(CAMERA_SERVICE) as CameraManager

        val cameras = try {
            manager.cameraIdList.filter {
                val characteristics = manager.getCameraCharacteristics(it)
                val lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING)
                lensFacing != null && lensFacing == CameraCharacteristics.LENS_FACING_BACK
            }
        } catch (e: Exception) {
            Log.e("RecordingService", "Failed to get camera list: ${e.message}")
            return
        }

        if (cameras.isEmpty()) {
            Log.w("RecordingService", "No suitable back-facing camera found")
            return
        }

        val camera = cameras[0]
        try {
            manager.openCamera(camera, object : CameraDevice.StateCallback() {
                override fun onDisconnected(camera: CameraDevice) {
                    try {
                        camera.close()
                    } catch (e: Exception) {
                        Log.w("RecordingService", "Error closing disconnected camera: ${e.message}")
                    }
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    Log.w("RecordingService", "Camera error $error")
                    try {
                        camera.close()
                    } catch (e: Exception) {
                        Log.w("RecordingService", "Error closing camera on error: ${e.message}")
                    }
                }

                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    setupCaptureSession()
                }
            }, null)
        } catch (e: CameraAccessException) {
            Log.e("RecordingService", "Camera access exception: ${e.message}")
        } catch (e: SecurityException) {
            Log.e("RecordingService", "Camera permission missing: ${e.message}")
        } catch (e: Exception) {
            Log.e("RecordingService", "Unknown camera open exception: ${e.message}")
        }

        val characteristics = try {
            manager.getCameraCharacteristics(camera)
        } catch (e: Exception) {
            Log.w("RecordingService", "Failed to get camera characteristics: ${e.message}")
            return
        }

        val map = try {
            characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return
        } catch (e: Exception) {
            Log.w("RecordingService", "Failed to get stream configuration map: ${e.message}")
            return
        }

        val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        val codecInfo = try {
            codecList.codecInfos.first { it.isEncoder && it.supportedTypes.contains(MediaFormat.MIMETYPE_VIDEO_AVC) }
        } catch (e: Exception) {
            Log.e("RecordingService", "No suitable encoder found: ${e.message}")
            return
        }

        val videoCaps = codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities
        val supportedCameraSizes = map.getOutputSizes(ImageFormat.YUV_420_888)
        val size = supportedCameraSizes
            .firstOrNull { s -> videoCaps.isSizeSupported(s.width, s.height) }
            ?: supportedCameraSizes.first()

        val supportedFpsRange = videoCaps.getSupportedFrameRatesFor(size.width, size.height)
        val fps: Int = supportedFpsRange.upper.toInt()

        val bitsPerPixel = 0.1
        val bitRate = (size.width * size.height * fps * bitsPerPixel).toInt()

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, size.width, size.height)
            .apply {
                setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            }

        try {
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)

            val videoBufferSize = fps * 30
            videoBuffer = ArrayDeque<Pair<ByteArray, MediaCodec.BufferInfo>>(videoBufferSize)

            encoder.setCallback(object : MediaCodec.Callback() {
                override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                    Log.e("RecordingService", "Encoder error: ${e.message}")
                }

                override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                    // No-op
                }

                override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                    try {
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            codec.releaseOutputBuffer(index, false)
                            return
                        }

                        if (info.size > 0) {
                            val encodedData = codec.getOutputBuffer(index) ?: return
                            encodedData.position(info.offset)
                            encodedData.limit(info.offset + info.size)

                            if (videoBasePts < 0) videoBasePts = info.presentationTimeUs
                            val pts = info.presentationTimeUs - videoBasePts
                            val flags = if (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0 || videoBuffer.isEmpty()) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                            val frameBytes = ByteArray(info.size)
                            encodedData.get(frameBytes)

                            synchronized(videoBuffer) {
                                while (videoBuffer.isNotEmpty() && pts - videoBuffer.first().second.presentationTimeUs > recordDurationSeconds * 1_000_000) {
                                    videoBuffer.removeFirst()
                                }
                                videoBuffer.addLast(Pair(
                                    frameBytes,
                                    MediaCodec.BufferInfo().apply { set(info.offset, info.size, pts, flags) }
                                ))
                            }
                        } else {
                            Log.w("RecordingService", "Empty output buffer. Flags: ${info.flags}")
                        }
                    } catch (e: Exception) {
                        Log.e("RecordingService", "Error handling output buffer: ${e.message}")
                    } finally {
                        try { codec.releaseOutputBuffer(index, false) } catch (_: Exception) {}
                    }
                }

                override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                    // No-op
                }
            })

            inputSurface = encoder.createInputSurface()
            encoder.start()
        } catch (e: Exception) {
            Log.e("RecordingService", "Failed to configure/start encoder: ${e.message}")
        }
    }

    private fun setupCaptureSession() {
        val surfaces = listOf(inputSurface)
        try {
            cameraDevice.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    try {
                        val captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                            addTarget(inputSurface)
                        }
                        session.setRepeatingRequest(captureRequestBuilder.build(), null, null)
                    } catch (e: CameraAccessException) {
                        Log.w("RecordingService", "Failed to start repeating request: ${e.message}")
                    } catch (e: IllegalStateException) {
                        Log.w("RecordingService", "Capture session illegal state: ${e.message}")
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.w("RecordingService", "Capture session configuration failed")
                }
            }, null)
        } catch (e: CameraAccessException) {
            Log.w("RecordingService", "Failed to create capture session: ${e.message}")
        } catch (e: IllegalStateException) {
            Log.w("RecordingService", "Camera device illegal state during capture session creation: ${e.message}")
        } catch (e: Exception) {
            Log.w("RecordingService", "Unknown error creating capture session: ${e.message}")
        }
    }
    fun saveMedia() {
        // Retrieve and prepare audio PCM data from the in-memory buffer
        val first = audioBuffer.copyOfRange(audioWritePointer, audioBuffer.size)
        val second = audioBuffer.copyOfRange(0, audioWritePointer)
        val pcmData = first + second

        // --- Audio encoding setup ---
        val audioSampleRate = 44100
        val audioChannelCount = 1
        val audioBitRate = 128_000

        val audioFormat = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC,
            audioSampleRate,
            audioChannelCount
        ).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, audioBitRate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }

        val audioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        audioEncoder.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        audioEncoder.start()

        // --- Encode audio synchronously and gather encoded frames ---
        val encodedAudioFrames = mutableListOf<Pair<ByteArray, MediaCodec.BufferInfo>>()
        val audioBufferInfo = MediaCodec.BufferInfo()
        val inputBuffers = audioEncoder.inputBuffers
        val outputBuffers = audioEncoder.outputBuffers
        var pcmOffset = 0

        while (pcmOffset < pcmData.size) {
            // Feed PCM to encoder
            val inputIndex = audioEncoder.dequeueInputBuffer(10000)
            if (inputIndex >= 0) {
                val inputBuffer = inputBuffers[inputIndex]
                inputBuffer.clear()

                val bytesToWrite = min(inputBuffer.capacity(), pcmData.size - pcmOffset)
                inputBuffer.put(pcmData, pcmOffset, bytesToWrite)

                val presentationTimeUs = (pcmOffset.toLong() * 1_000_000L) / (audioSampleRate * 2 * audioChannelCount)
                audioEncoder.queueInputBuffer(inputIndex, 0, bytesToWrite, presentationTimeUs, 0)
                pcmOffset += bytesToWrite
            }

            // Get encoded output and store it
            var outputIndex = audioEncoder.dequeueOutputBuffer(audioBufferInfo, 10000)
            while (outputIndex >= 0) {
                val encodedData = outputBuffers[outputIndex]
                val encodedFrameBytes = ByteArray(audioBufferInfo.size)
                encodedData.position(audioBufferInfo.offset)
                encodedData.limit(audioBufferInfo.offset + audioBufferInfo.size)
                encodedData.get(encodedFrameBytes)

                encodedAudioFrames.add(encodedFrameBytes to MediaCodec.BufferInfo().apply {
                    set(audioBufferInfo.offset, audioBufferInfo.size, audioBufferInfo.presentationTimeUs, audioBufferInfo.flags)
                })
                audioEncoder.releaseOutputBuffer(outputIndex, false)
                outputIndex = audioEncoder.dequeueOutputBuffer(audioBufferInfo, 0)
            }
        }

        // --- Signal end of stream for audio ---
        val inputIndex = audioEncoder.dequeueInputBuffer(10000)
        if (inputIndex >= 0) {
            audioEncoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        }

        // Drain remaining audio output
        var outputIndex = audioEncoder.dequeueOutputBuffer(audioBufferInfo, 10000)
        while (outputIndex >= 0) {
            val encodedData = outputBuffers[outputIndex]
            val encodedFrameBytes = ByteArray(audioBufferInfo.size)
            encodedData.position(audioBufferInfo.offset)
            encodedData.limit(audioBufferInfo.offset + audioBufferInfo.size)
            encodedData.get(encodedFrameBytes)

            encodedAudioFrames.add(encodedFrameBytes to MediaCodec.BufferInfo().apply {
                set(audioBufferInfo.offset, audioBufferInfo.size, audioBufferInfo.presentationTimeUs, audioBufferInfo.flags)
            })
            audioEncoder.releaseOutputBuffer(outputIndex, false)
            outputIndex = audioEncoder.dequeueOutputBuffer(audioBufferInfo, 0)
        }

        val videoOutputFormat = encoder.getOutputFormat()
        val outputFile = File(filesDir, "combined_${System.currentTimeMillis()}.mp4")

        // --- Muxer setup ---
        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val videoTrackIndex = muxer.addTrack(videoOutputFormat)
        var audioTrackIndex = muxer.addTrack(audioFormat)
        muxer.start()

        // --- Write all frames to muxer in chronological order ---
        val videoBasePts = try {
            videoBuffer.first().second.presentationTimeUs
        } catch(e: NoSuchElementException){
            0
        }
        val allFrames = (encodedAudioFrames.map { (bytes, info) ->
            FrameWrapper(bytes, info, audioTrackIndex)
        } + videoBuffer.map { (bytes, info) ->
            FrameWrapper(bytes, MediaCodec.BufferInfo().apply {
                set(info.offset, info.size, info.presentationTimeUs - videoBasePts, info.flags)
            }, videoTrackIndex)
        }.sortedBy { it.info.presentationTimeUs })


    for (frame in allFrames) {
            muxer.writeSampleData(frame.trackIndex, ByteBuffer.wrap(frame.bytes), frame.info)
        }

        // --- Cleanup ---
        audioEncoder.stop()
        audioEncoder.release()
        muxer.stop()
        muxer.release()
    }

    // Helper class to store frame data and track index
    data class FrameWrapper(
        val bytes: ByteArray,
        val info: MediaCodec.BufferInfo,
        val trackIndex: Int
    )
}
