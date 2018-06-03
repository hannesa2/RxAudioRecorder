package info.audio.rxrecoder;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.util.Log;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;

import io.reactivex.ObservableEmitter;
import io.reactivex.ObservableOnSubscribe;

/**
 * Custom onSubscribe for an Observable, when start() is called, it sends out short buffers of audio data.
 * Pause/Resume/Stop/Stop methods to control the streaming/publishing of audio data to the Observer.
 * <br/>
 */
public class ObservableAudioRecorder implements ObservableOnSubscribe<short[]>, Runnable {
    private final int sampleRate;
    private final int channels;
    private final int audioSource;
    private final Object mPauseLock;
    private final int bitsPerSecond;
    private String filePath;
    private AudioRecord audioRecorder = null;
    private int bufferSize;
    private boolean isRecording = false;
    private boolean isPaused;
    private ObservableEmitter<? super short[]> subscriber;
    private Thread thread;
    private DataOutputStream dataOutputStream;

    private ObservableAudioRecorder(String filePath, int sampleRate, int channels, int audioSource, int bitsPerSecond) {
        this.filePath = filePath;
        this.sampleRate = sampleRate;
        this.channels = channels == 1 ? AudioFormat.CHANNEL_IN_MONO : AudioFormat.CHANNEL_IN_STEREO;
        this.audioSource = audioSource;
        this.bitsPerSecond = bitsPerSecond;

        mPauseLock = new Object();
        isPaused = false;
    }

    @Override
    public void subscribe(ObservableEmitter<short[]> e) {
        subscriber = e;
    }

    public void start() throws RuntimeException, FileNotFoundException {
        if (filePath != null) {
            this.dataOutputStream = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(filePath)));
        }

        this.bufferSize = AudioRecord.getMinBufferSize(sampleRate, channels, bitsPerSecond);

        if (bufferSize != AudioRecord.ERROR_BAD_VALUE && bufferSize != AudioRecord.ERROR) {
            audioRecorder = new AudioRecord(audioSource, sampleRate, channels, bitsPerSecond, bufferSize * 10);

            if (audioRecorder.getState() == AudioRecord.STATE_INITIALIZED) {
                audioRecorder.startRecording();
                isRecording = true;
                thread = new Thread(this);
                thread.start();
            } else {
                throw new RuntimeException("Unable to create AudioRecord instance");
            }
        } else {
            throw new RuntimeException("Unable to get minimum buffer size");
        }
    }

    @Override
    public String toString() {
        String bits = bitsPerSecond == AudioFormat.ENCODING_PCM_16BIT ? "16BIT" : "8BIT";
        String channelConfig = channels == AudioFormat.CHANNEL_IN_MONO ? "Mono" : "Stereo";
        return sampleRate + "Hz, bits: " + bits + ", channel: " + channelConfig;
    }

    public void stop() {
        isRecording = false;
        if (audioRecorder != null) {
            if (audioRecorder.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                audioRecorder.stop();
            }
            if (audioRecorder.getState() == AudioRecord.STATE_INITIALIZED) {
                audioRecorder.release();
            }
        }
    }

    /**
     * Call this on pause.
     */
    public void pause() {
        synchronized (mPauseLock) {
            isPaused = true;
        }
    }

    /**
     * Call this on resume.
     */
    public void resume() {
        synchronized (mPauseLock) {
            isPaused = false;
            mPauseLock.notifyAll();
        }
    }

    @Override
    public void run() {
        short[] tempBuf = new short[bufferSize / 2];

        while (isRecording && audioRecorder.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
            audioRecorder.read(tempBuf, 0, tempBuf.length);
            subscriber.onNext(tempBuf);
            synchronized (mPauseLock) {
                while (isPaused) {
                    try {
                        mPauseLock.wait();
                    } catch (InterruptedException e) {
                        subscriber.onError(e);
                    }
                }
            }
        }
    }

    public boolean isRecording() {
        return (audioRecorder != null) && (audioRecorder.getRecordingState()
                == AudioRecord.RECORDSTATE_RECORDING);
    }

    @Override
    protected void finalize() throws Throwable {
        super.finalize();

        if (audioRecorder != null && audioRecorder.getState() == AudioRecord.STATE_INITIALIZED) {
            audioRecorder.stop();
            audioRecorder.release();
        }

        audioRecorder = null;
        thread = null;
    }

    public boolean isRecordingStopped() {
        return audioRecorder == null || audioRecorder.getRecordingState() == 1;
    }

    private void convertFileToWave(File file) throws IOException {
        try {
            rawToWave(file, sampleRate, 16);
        } catch (IOException e) {
            throw new IOException("Unable to convert file");
        }
    }

    //channels -> Mono or Stereo
    //bitsPerSecond -> 16 or 8 (currently android supports 16 only)
    private void rawToWave(File rawFile, int sampleRate, int bitsPerSecond) throws IOException {
        RandomAccessFile randomAccessFile = new RandomAccessFile(rawFile, "rw");
        int channels = this.channels == AudioFormat.CHANNEL_IN_MONO ? 1 : 2;
        //seek to beginning
        randomAccessFile.seek(0);
        try {
            writeString(randomAccessFile, "RIFF"); // chunk id
            writeInt(randomAccessFile, (int) (36 + rawFile.length())); // chunk size
            writeString(randomAccessFile, "WAVE"); // format
            writeString(randomAccessFile, "fmt "); // subchunk 1 id
            writeInt(randomAccessFile, 16); // subchunk 1 size
            writeShort(randomAccessFile, (short) 1); // audio format (1 = PCM)
            writeShort(randomAccessFile, (short) channels); // number of channels
            writeInt(randomAccessFile, sampleRate); // sample rate
            writeInt(randomAccessFile, sampleRate * 2); // byte rate
            writeShort(randomAccessFile, (short) 2); // block align
            writeShort(randomAccessFile, (short) bitsPerSecond); // bits per sample
            writeString(randomAccessFile, "data"); // subchunk 2 id
            writeInt(randomAccessFile, (int) rawFile.length()); // subchunk 2 size
        } finally {
            randomAccessFile.close();
        }
    }

    private void writeInt(final RandomAccessFile output, final int value) throws IOException {
        output.write(value);
        output.write(value >> 8);
        output.write(value >> 16);
        output.write(value >> 24);
    }

    private void writeShort(final RandomAccessFile output, final short value) throws IOException {
        output.write(value);
        output.write(value >> 8);
    }

    private void writeString(final RandomAccessFile output, final String value) throws IOException {
        for (int i = 0; i < value.length(); i++) {
            output.write(value.charAt(i));
        }
    }

    public void writeDataToFile(short[] shorts) throws IOException {
        for (short aShort : shorts) {
            if (dataOutputStream != null) {
                dataOutputStream.writeByte(aShort & 0xFF);
                dataOutputStream.writeByte((aShort >> 8) & 0xFF);
            }
        }
    }

    public void completeRecording() throws IOException {
        if (dataOutputStream != null) {
            dataOutputStream.flush();
            dataOutputStream.close();
        }

        if (filePath != null) {
            File file = new File(filePath);
            convertFileToWave(file);
        }
    }

    public static class Builder {
        int sampleRate;
        int bitsPerSecond;
        int channels;
        int audioSource;
        String filePath = null;

        public Builder(int audioSource) {
            this.audioSource = audioSource;
            findMinimalAudioRate();
        }

        public Builder sampleRate(int sampleRate) {
            this.sampleRate = sampleRate;
            return this;
        }

        public Builder file(String filePath) {
            this.filePath = filePath;
            return this;
        }

        public Builder stereo() {
            channels = 2;
            return this;
        }

        public Builder mono() {
            channels = 1;
            return this;
        }

        public Builder audioSource(int audioSource) {
            this.audioSource = audioSource;
            return this;
        }

        public ObservableAudioRecorder build() {
            return new ObservableAudioRecorder(filePath, this.sampleRate, this.channels, this.audioSource, bitsPerSecond);
        }

        private void findMinimalAudioRate() {
            int[] mSampleRates = new int[]{8000, 11025, 22050, 44100};
            for (int rate : mSampleRates) {
                for (short audioFormat : new short[]{AudioFormat.ENCODING_PCM_8BIT, AudioFormat.ENCODING_PCM_16BIT}) {
                    for (short channelConfig : new short[]{AudioFormat.CHANNEL_IN_MONO, AudioFormat.CHANNEL_IN_STEREO}) {
                        try {
                            Log.d("audioRate", "Attempting rate " + rate + "Hz, bits: " + audioFormat + ", channel: " + channelConfig);
                            int bufferSize = AudioRecord.getMinBufferSize(rate, channelConfig, audioFormat);

                            if (bufferSize != AudioRecord.ERROR_BAD_VALUE) {
                                // check if we can instantiate and have a success
                                AudioRecord recorder = new AudioRecord(audioSource, rate, channelConfig, audioFormat, bufferSize);

                                if (recorder.getState() == AudioRecord.STATE_INITIALIZED) {
                                    sampleRate = rate;
                                    bitsPerSecond = audioFormat;
                                    channels = channelConfig;
                                    recorder.stop();
                                    return;
                                }
                            }
                        } catch (Exception e) {
                            Log.e("audioRate", rate + "Exception, keep trying.", e);
                        }
                    }
                }
            }
        }
    }
}
