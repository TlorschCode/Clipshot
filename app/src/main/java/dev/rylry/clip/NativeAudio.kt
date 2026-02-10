package dev.rylry.clip

object NativeAudio {
    init {
        System.loadLibrary("clip")
    }

    external fun start()
    external fun stop()
    external fun copySnapshot(out: ByteArray)
    external fun isRecordingActive() : Boolean
}