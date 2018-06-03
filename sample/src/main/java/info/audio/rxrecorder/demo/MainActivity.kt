package info.audio.rxrecorder.demo

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Environment
import android.support.v4.content.FileProvider
import android.support.v7.app.AppCompatActivity
import android.util.Log
import android.view.View
import info.audio.rxrecoder.ObservableAudioRecorder
import io.reactivex.Observable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.Disposable
import io.reactivex.schedulers.Schedulers
import kotlinx.android.synthetic.main.activity_main.*
import library.minimize.com.chronometerpersist.ChronometerPersist
import permissions.dispatcher.NeedsPermission
import permissions.dispatcher.RuntimePermissions
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

@RuntimePermissions
class MainActivity : AppCompatActivity() {
    private var subscription: Disposable? = null
    private var observableAudioRecorder: ObservableAudioRecorder? = null

    private var chronometerPersist: ChronometerPersist? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        chronometerPersist = ChronometerPersist.getInstance(chronometer, getSharedPreferences("Prefs", Context.MODE_PRIVATE))
        val filePath = Environment.getExternalStorageDirectory().toString() + FILE_NAME

        observableAudioRecorder = ObservableAudioRecorder.Builder(MediaRecorder.AudioSource.CAMCORDER)
                .file(Environment.getExternalStorageDirectory().absolutePath + "/sample.wav")
                .file(filePath)
                .build()

        textViewAudioFormat.text = observableAudioRecorder.toString()

        buttonRecord.setOnClickListener { view -> startRecordingWithPermissionCheck() }

        subscription = Observable.create(observableAudioRecorder)
                .subscribeOn(Schedulers.computation())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe({ shorts ->
                    try {
                        observableAudioRecorder!!.writeDataToFile(shorts)
                    } catch (e: IOException) {
                        Log.e("Write", e.message)
                    }
                }) { throwable -> Log.e("Error", throwable.message) }

        buttonPlay.visibility = View.GONE
        buttonPlay.setOnClickListener { view -> playFile() }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        onRequestPermissionsResult(requestCode, grantResults)
    }

    override fun onPause() {
        super.onPause()
        observableAudioRecorder?.stop()
        subscription?.dispose()
    }

    @SuppressLint("SetTextI18n")
    @NeedsPermission(Manifest.permission.RECORD_AUDIO, Manifest.permission.WRITE_EXTERNAL_STORAGE)
    internal fun startRecording() {
        if (observableAudioRecorder!!.isRecording()) {
            try {
                observableAudioRecorder!!.stop()
                //helper method for closing the dataStream, also writes the wave header
                observableAudioRecorder!!.completeRecording()
                buttonPlay.text = "Record"
            } catch (e: IOException) {
                Log.e("Recorder", e.message)
            }

            buttonPlay.visibility = View.VISIBLE
            chronometerPersist!!.stopChronometer()
        } else {
            try {
                observableAudioRecorder!!.start()
                buttonPlay.text = "Stop"
            } catch (e: FileNotFoundException) {
                Log.e("Recorder Error", e.message)
            }

            chronometerPersist!!.startChronometer()
        }
    }

    private fun playFile() {
        val intent = Intent()
        intent.action = android.content.Intent.ACTION_VIEW
        val file = File(Environment.getExternalStorageDirectory(), FILE_NAME)
        val fileUri = FileProvider.getUriForFile(this, BuildConfig.APPLICATION_ID + ".audioprovider", file)
        intent.setDataAndType(fileUri, "audio/*")
        startActivity(intent)
    }

    companion object {
        private val FILE_NAME = "/sample.wav"
    }
}
