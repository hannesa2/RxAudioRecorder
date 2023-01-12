package info.audio.rxrecorder.demo

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.MediaRecorder
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import info.audio.rxrecoder.ObservableAudioRecorder
import info.audio.rxrecorder.demo.databinding.ActivityMainBinding
import io.reactivex.Observable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.Disposable
import io.reactivex.schedulers.Schedulers
import library.minimize.com.chronometerpersist.ChronometerPersist
import permissions.dispatcher.NeedsPermission
import permissions.dispatcher.RuntimePermissions
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

@RuntimePermissions
class MainActivity : AppCompatActivity() {
    private lateinit var filePath: String
    private lateinit var subscription: Disposable
    private lateinit var observableAudioRecorder: ObservableAudioRecorder

    private lateinit var chronometerPersist: ChronometerPersist
    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        chronometerPersist = ChronometerPersist.getInstance(binding.chronometer, "abc", getSharedPreferences("Prefs", Context.MODE_PRIVATE))
        filePath = this.externalCacheDir?.absolutePath + FILE_NAME

        observableAudioRecorder = ObservableAudioRecorder.Builder(MediaRecorder.AudioSource.CAMCORDER)
                .file(filePath)
                .findBestAudioRate()
                .build()

        binding.textViewAudioFormat.text = observableAudioRecorder.toString()

        binding.buttonRecord.setOnClickListener {
            //toggleRecordingWithPermissionCheck()
        }

        subscription = Observable.create(observableAudioRecorder)
                .subscribeOn(Schedulers.computation())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe({ shorts ->
                    try {
                        observableAudioRecorder.writeDataToFile(shorts)
                    } catch (e: IOException) {
                        Log.e("Write", "IOException")
                    }
                }) { throwable -> Log.e("Error", "${throwable.message}") }

        binding.buttonPlay.visibility = View.GONE
        binding.buttonPlay.setOnClickListener { playFile() }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    override fun onPause() {
        super.onPause()
        observableAudioRecorder.stop()
        subscription.dispose()
    }

    @SuppressLint("SetTextI18n")
    @NeedsPermission(Manifest.permission.RECORD_AUDIO, Manifest.permission.WRITE_EXTERNAL_STORAGE)
    internal fun toggleRecording() {
        binding.buttonPlay.visibility = View.VISIBLE
        if (observableAudioRecorder.isRecording()) {
            binding.buttonPlay.isEnabled = true
            try {
                binding.buttonRecord.text = "Record"
                observableAudioRecorder.stop()
                //helper method for closing the dataStream, also writes the wave header
                observableAudioRecorder.completeRecording()
            } catch (e: IOException) {
                Log.e("Recorder", "IOException")
            }

            binding.buttonPlay.text = "Play"
            chronometerPersist.stopChronometer()
        } else {
            binding.buttonPlay.isEnabled = false
            try {
                observableAudioRecorder.start()
                binding.buttonRecord.text = "Stop"
            } catch (e: FileNotFoundException) {
                Log.e("Recorder Error", "FileNotFoundException")
            }

            chronometerPersist.startChronometer()
        }
    }

    private fun playFile() {
        val intent = Intent()
        intent.action = Intent.ACTION_VIEW
        val fileUri = FileProvider.getUriForFile(this, BuildConfig.APPLICATION_ID + ".audioprovider", File(filePath))
        intent.setDataAndType(fileUri, contentResolver.getType(fileUri))
        intent.putExtra(Intent.EXTRA_STREAM, fileUri)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(intent, "Play Sound File"))
    }

    companion object {
        private const val FILE_NAME = "/sample.wav"
    }
}
