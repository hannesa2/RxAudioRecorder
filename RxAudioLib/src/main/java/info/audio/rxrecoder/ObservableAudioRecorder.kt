package info.audio.rxrecoder

import android.media.AudioFormat
import android.media.AudioRecord
import android.util.Log
import io.reactivex.ObservableEmitter
import io.reactivex.ObservableOnSubscribe
import java.io.*

/**
 * Custom onSubscribe for an Observable, when start() is called, it sends out short buffers of audio data.
 * Pause/Resume/Stop/Stop methods to control the streaming/publishing of audio data to the Observer.
 * <br></br>
 */
open class ObservableAudioRecorder private constructor(private val filePath: String?, private val sampleRate: Int, channels: Int, private val audioSource: Int, private val bitsPerSecond: Int) : ObservableOnSubscribe<ShortArray>, Runnable {
    private val channels: Int
    private val mPauseLock = java.lang.Object() //  instead of Any we use a Java class, there we can .wait() and .notifyAll()
    private var audioRecorder: AudioRecord? = null
    private var bufferSize: Int = 0
    private var isRecording = false
    private var isPaused: Boolean = false
    private var subscriber: ObservableEmitter<in ShortArray>? = null
    private var thread: Thread? = null
    private var dataOutputStream: DataOutputStream? = null

    val isRecordingStopped: Boolean
        get() = audioRecorder == null || audioRecorder!!.recordingState == 1

    init {
        this.channels = if (channels == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO
        isPaused = false
    }

    override fun subscribe(e: ObservableEmitter<ShortArray>) {
        subscriber = e
    }

    @Throws(RuntimeException::class, FileNotFoundException::class)
    fun start() {
        if (filePath != null) {
            this.dataOutputStream = DataOutputStream(BufferedOutputStream(FileOutputStream(filePath)))
        }

        this.bufferSize = AudioRecord.getMinBufferSize(sampleRate, channels, bitsPerSecond)

        if (bufferSize != AudioRecord.ERROR_BAD_VALUE && bufferSize != AudioRecord.ERROR) {
            audioRecorder = AudioRecord(audioSource, sampleRate, channels, bitsPerSecond, bufferSize * 10)

            if (audioRecorder!!.state == AudioRecord.STATE_INITIALIZED) {
                audioRecorder!!.startRecording()
                isRecording = true
                thread = Thread(this)
                thread!!.start()
            } else {
                throw RuntimeException("Unable to create AudioRecord instance")
            }
        } else {
            throw RuntimeException("Unable to get minimum buffer size")
        }
    }

    override fun toString(): String {
        val bits = if (bitsPerSecond == AudioFormat.ENCODING_PCM_16BIT) "16BIT" else "8BIT"
        val channelConfig = if (channels == AudioFormat.CHANNEL_IN_MONO) "Mono" else "Stereo"
        return sampleRate.toString() + " Hz, bits: " + bits + ", channel: " + channelConfig
    }

    fun stop() {
        isRecording = false
        if (audioRecorder != null) {
            if (audioRecorder!!.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                audioRecorder!!.stop()
            }
            if (audioRecorder!!.state == AudioRecord.STATE_INITIALIZED) {
                audioRecorder!!.release()
            }
        }
    }

    /**
     * Call this on pause.
     */
    fun pause() {
        synchronized(mPauseLock) {
            isPaused = true
        }
    }

    /**
     * Call this on resume.
     */
    fun resume() {
        synchronized(mPauseLock) {
            isPaused = false
            mPauseLock.notifyAll()
        }
    }

    override fun run() {
        val tempBuf = ShortArray(bufferSize / 2)

        while (isRecording && audioRecorder!!.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
            audioRecorder!!.read(tempBuf, 0, tempBuf.size)
            subscriber!!.onNext(tempBuf)
            synchronized(mPauseLock) {
                while (isPaused) {
                    try {
                        mPauseLock.wait()
                    } catch (e: InterruptedException) {
                        subscriber!!.onError(e)
                    }

                }
            }
        }
    }

    @Throws(Throwable::class)
    protected fun finalize() {

        if (audioRecorder != null && audioRecorder!!.state == AudioRecord.STATE_INITIALIZED) {
            audioRecorder!!.stop()
            audioRecorder!!.release()
        }

        audioRecorder = null
        thread = null
    }

    fun isRecording(): Boolean {
        return audioRecorder != null && audioRecorder!!.recordingState == AudioRecord.RECORDSTATE_RECORDING
    }

    @Throws(IOException::class)
    private fun convertFileToWave(file: File) {
        try {
            rawToWave(file, sampleRate, 16)
        } catch (e: IOException) {
            throw IOException("Unable to convert file")
        }
    }

    @Throws(IOException::class)
    private fun rawToWave(rawFile: File, sampleRate: Int, bitsPerSecond: Int) {
        val randomAccessFile = RandomAccessFile(rawFile, "rw")
        val channels = if (this.channels == AudioFormat.CHANNEL_IN_MONO) 1 else 2
        //seek to beginning
        randomAccessFile.seek(0)
        try {
            writeString(randomAccessFile, "RIFF") // chunk id
            writeInt(randomAccessFile, (36 + rawFile.length()).toInt()) // chunk size
            writeString(randomAccessFile, "WAVE") // format
            writeString(randomAccessFile, "fmt ") // subchunk 1 id
            writeInt(randomAccessFile, 16) // subchunk 1 size
            writeShort(randomAccessFile, 1.toShort()) // audio format (1 = PCM)
            writeShort(randomAccessFile, channels.toShort()) // number of channels
            writeInt(randomAccessFile, sampleRate) // sample rate
            writeInt(randomAccessFile, sampleRate * 2) // byte rate
            writeShort(randomAccessFile, 2.toShort()) // block align
            writeShort(randomAccessFile, bitsPerSecond.toShort()) // bits per sample
            writeString(randomAccessFile, "data") // subchunk 2 id
            writeInt(randomAccessFile, rawFile.length().toInt()) // subchunk 2 size
        } finally {
            randomAccessFile.close()
        }
    }

    @Throws(IOException::class)
    private fun writeInt(output: RandomAccessFile, value: Int) {
        output.write(value)
        output.write(value shr 8)
        output.write(value shr 16)
        output.write(value shr 24)
    }

    @Throws(IOException::class)
    private fun writeShort(output: RandomAccessFile, value: Short) {
        output.write(value.toInt())
        output.write(value.toInt() shr 8)
    }

    @Throws(IOException::class)
    private fun writeString(output: RandomAccessFile, value: String) {
        for (i in 0 until value.length) {
            output.write(value[i].toInt())
        }
    }

    @Throws(IOException::class)
    fun writeDataToFile(shorts: ShortArray) {
        for (aShort in shorts) {
            if (dataOutputStream != null) {
                dataOutputStream!!.writeByte(aShort.toInt() and 0xFF)
                dataOutputStream!!.writeByte(aShort.toInt() shr 8 and 0xFF)
            }
        }
    }

    @Throws(IOException::class)
    fun completeRecording() {
        if (dataOutputStream != null) {
            dataOutputStream!!.flush()
            dataOutputStream!!.close()
        }

        if (filePath != null) {
            val file = File(filePath)
            convertFileToWave(file)
        }
    }

    class Builder(internal var audioSource: Int) {
        internal var sampleRate: Int = 0
        internal var bitsPerSecond: Int = 0
        internal var channels: Int = 0
        internal var filePath: String? = null

        init {
            findMinimalAudioRate()
        }

        fun sampleRate(sampleRate: Int): Builder {
            this.sampleRate = sampleRate
            return this
        }

        fun file(filePath: String): Builder {
            this.filePath = filePath
            return this
        }

        fun stereo(): Builder {
            channels = 2
            return this
        }

        fun mono(): Builder {
            channels = 1
            return this
        }

        fun audioSource(audioSource: Int): Builder {
            this.audioSource = audioSource
            return this
        }

        fun build(): ObservableAudioRecorder {
            return ObservableAudioRecorder(filePath, this.sampleRate, this.channels, this.audioSource, bitsPerSecond)
        }

        private fun findMinimalAudioRate() {
            val mSampleRates = intArrayOf(8000, 11025, 22050, 44100)
            for (rate in mSampleRates) {
                for (audioFormat in shortArrayOf(AudioFormat.ENCODING_PCM_8BIT.toShort(), AudioFormat.ENCODING_PCM_16BIT.toShort())) {
                    for (channelConfig in shortArrayOf(AudioFormat.CHANNEL_IN_MONO.toShort(), AudioFormat.CHANNEL_IN_STEREO.toShort())) {
                        try {
                            Log.d("audioRate", "Attempting rate " + rate + "Hz, bits: " + audioFormat + ", channel: " + channelConfig)
                            val bufferSize = AudioRecord.getMinBufferSize(rate, channelConfig.toInt(), audioFormat.toInt())

                            if (bufferSize != AudioRecord.ERROR_BAD_VALUE) {
                                // check if we can instantiate and have a success
                                val recorder = AudioRecord(audioSource, rate, channelConfig.toInt(), audioFormat.toInt(), bufferSize)

                                if (recorder.state == AudioRecord.STATE_INITIALIZED) {
                                    sampleRate = rate
                                    bitsPerSecond = audioFormat.toInt()
                                    channels = channelConfig.toInt()
                                    recorder.stop()
                                    return
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("audioRate", rate.toString() + "Exception, keep trying.", e)
                        }

                    }
                }
            }
        }
    }
}
