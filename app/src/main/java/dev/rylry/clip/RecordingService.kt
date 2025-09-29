package dev.rylry.clip

import android.Manifest
import android.R
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
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
import android.util.Size
import android.view.Surface
import androidx.annotation.RequiresPermission
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
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
    var videoBuffer = ArrayDeque<Pair<ByteArray, MediaCodec.BufferInfo>>()
    lateinit var encoder: MediaCodec
    lateinit var inputSurface: Surface

    private var videoBasePts: Long = -1
    private var lastVideoPts: Long = 0

    private var cameraProvider: ProcessCameraProvider? = null
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
        record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRateHz,
            channelConfig,
            audioFormat,
            audioBufferSize
        )
    }

    @RequiresPermission(allOf = [Manifest.permission.CAMERA])
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Create notification
        val channelId = "RecordingServiceChannel"
        createNotificationChannel(channelId)
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Recording Audio")
            .setContentText("Your audio is being recorded")
            .setSmallIcon(R.drawable.ic_btn_speak_now)
            .build()

        // Start foreground
        startForeground(1, notification)

        // Start recording in background thread
        startRecording()
        setupCameraX()

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRecording()
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
                val firstChunkSize = min(audioChunkSize, audioBufferSize - audioWritePointer)

                // Read up to firstChunkSize frames
                val readFirst = record?.read(audioBuffer, audioWritePointer, firstChunkSize) ?: 0

                // If there’s remaining to wrap around the circular buffer
                val remaining = audioChunkSize - readFirst
                if (remaining > 0) {
                    // Only read as much as fits at the start of the buffer
                    record?.read(audioBuffer, 0, remaining)
                }

                // Advance the write pointer
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

    /**
     * Selects a camera and starts recording
     */
    @RequiresPermission(Manifest.permission.CAMERA)
    private fun setupCameraX() {
        val manager = getSystemService(CAMERA_SERVICE) as CameraManager

        // 1. Pick back-facing camera
        val cameraId = try {
            manager.cameraIdList.firstOrNull {
                val characteristics = manager.getCameraCharacteristics(it)
                characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: run {
                Log.w("RecordingService", "No back-facing camera found")
                return
            }
        } catch (e: Exception) {
            Log.e("RecordingService", "Failed to get camera list: ${e.message} ${e.stackTrace} ${e.stackTrace}")
            return
        }

        val characteristics = try {
            manager.getCameraCharacteristics(cameraId)
        } catch (e: Exception) {
            Log.e("RecordingService", "Failed to get camera characteristics: ${e.message} ${e.stackTrace}")
            return
        }

        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        if (map == null) {
            Log.e("RecordingService", "No stream configuration map available")
            return
        }

        // 2. Get encoder capabilities
        val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        val codecInfo = codecList.codecInfos.firstOrNull {
            it.isEncoder && it.supportedTypes.contains(MediaFormat.MIMETYPE_VIDEO_AVC)
        } ?: run {
            Log.e("RecordingService", "No suitable H.264 encoder found")
            return
        }

        val videoCaps = codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities

        // 3. Pick resolution
        val supportedSizes = map.getOutputSizes(ImageFormat.YUV_420_888) ?: arrayOf()
        val size = supportedSizes.firstOrNull { s ->
            videoCaps.isSizeSupported(s.width, s.height)
        } ?: supportedSizes.firstOrNull() ?: run {
            Log.e("RecordingService", "No compatible resolution found")
            return
        }

        // 4. Pick FPS
        val fpsRange = videoCaps.getSupportedFrameRatesFor(size.width, size.height)
        val fps = fpsRange.upper.toInt().coerceAtMost(30) // cap at 30 fps for stability

        // 5. Compute bitrate (basic formula: w*h*fps*0.1)
        val bitRate = (size.width * size.height * fps * 0.1).toInt()

        Log.d("RecordingService", "Selected resolution: ${size.width}x${size.height}, fps: $fps, bitrate: $bitRate")

        // 6. Setup encoder and start camera
        setupEncoderX(size.width, size.height, fps, bitRate)
    }

    /**
     * Sets up encoder with CameraX API
     * @param width width of the video recording
     * @param height height of the video recording
     * @param fps number of fps to start recording video at
     * @param bitRate bit rate to use for encoder
     */
    private fun setupEncoderX(width: Int, height: Int, fps: Int, bitRate: Int) {
        try {
            // Configure MediaFormat
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }

            // Create and configure encoder
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.setCallback(object : MediaCodec.Callback() {
                override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                    Log.e("RecordingService", "Encoder error: ${e.message} ${e.stackTrace}")
                }

                override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                    // No-op for Surface input
                }

                override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                    val outputBuffer = codec.getOutputBuffer(index)
                    if (outputBuffer != null && info.size > 0) {
                        outputBuffer.position(info.offset)
                        outputBuffer.limit(info.offset + info.size)

                        // Copy or write to ring buffer
                        val frameBytes = ByteArray(info.size)
                        outputBuffer.get(frameBytes)

                        synchronized(videoBuffer) {
                            while (videoBuffer.isNotEmpty() &&
                                info.presentationTimeUs - videoBuffer.first().second.presentationTimeUs > recordDurationSeconds * 1_000_000
                            ) {
                                videoBuffer.removeFirst()
                            }
                            videoBuffer.addLast(Pair(frameBytes, MediaCodec.BufferInfo().apply {
                                set(info.offset, info.size, info.presentationTimeUs, info.flags)
                            }))
                        }
                    }
                    codec.releaseOutputBuffer(index, false)
                }

                override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                    Log.d("RecordingService", "Encoder format changed: $format")
                }
            })

            // Create input surface for encoder
            inputSurface = encoder.createInputSurface()

            // Start the encoder
            encoder.start()

            // Start camera with encoder surface
            cameraLifecycleInit(inputSurface)

            // Optional: setup callback to capture output buffers

        } catch (e: Exception) {
            Log.e("RecordingService", "Failed to setup encoder: ${e.message} ${e.stackTrace}")
        }
    }

    /**
     * Starts camera with CameraX API and outputs to a surface
     */
    private fun cameraLifecycleInit(videoSurface: Surface) {
        val context = this // service context
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            cameraProvider?.unbindAll()

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            // Get width/height from encoder's configured format
            val encoderFormat = encoder.outputFormat
            val width = encoderFormat.getInteger(MediaFormat.KEY_WIDTH)
            val height = encoderFormat.getInteger(MediaFormat.KEY_HEIGHT)
            val targetSize = Size(width, height)

            // Preview use-case is required for CameraX, but we won't display it
            val preview = Preview.Builder()
                .setTargetResolution(targetSize)
                .build()
                .also { p ->
                    p.setSurfaceProvider { request ->
                        request.provideSurface(
                            videoSurface,
                            ContextCompat.getMainExecutor(context)
                        ) { }
                    }
                }

            try {
                val lifecycleOwner = ServiceLifecycleOwner()
                cameraProvider?.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview
                )
            } catch (exc: Exception) {
                exc.printStackTrace()
            }

        }, ContextCompat.getMainExecutor(context))
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
                        Log.w("RecordingService", "Failed to start repeating request: ${e.message} ${e.stackTrace}")
                    } catch (e: IllegalStateException) {
                        Log.w("RecordingService", "Capture session illegal state: ${e.message} ${e.stackTrace}")
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.w("RecordingService", "Capture session configuration failed")
                }
            }, null)
        } catch (e: CameraAccessException) {
            Log.w("RecordingService", "Failed to create capture session: ${e.message} ${e.stackTrace}")
        } catch (e: IllegalStateException) {
            Log.w("RecordingService", "Camera device illegal state during capture session creation: ${e.message} ${e.stackTrace}")
        } catch (e: Exception) {
            Log.w("RecordingService", "Unknown error creating capture session: ${e.message} ${e.stackTrace}")
        }
    }
    fun saveBuffersMP4() {
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

        val allFrames = encodedAudioFrames.map { (bytes, info) ->
            FrameWrapper(bytes, info, audioTrackIndex)
        } + videoBuffer.map { (bytes, info) ->
            FrameWrapper(bytes, MediaCodec.BufferInfo().apply {
                set(info.offset, info.size, info.presentationTimeUs - videoBasePts, info.flags)
            }, videoTrackIndex)
        }.sortedBy { it.info.presentationTimeUs }

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
    // Helper class to keep track of camera state
    private class ServiceLifecycleOwner() : LifecycleOwner {
        override val lifecycle = LifecycleRegistry(this)
        private val lifecycleRegistry = LifecycleRegistry(this)
        init {
            // Keep it always RESUMED so CameraX keeps running
            lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        }
    }
}
