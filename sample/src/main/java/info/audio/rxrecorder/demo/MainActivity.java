package info.audio.rxrecorder.demo;

import android.Manifest;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.support.annotation.NonNull;
import android.support.v4.content.FileProvider;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.Chronometer;
import android.widget.TextView;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

import info.audio.rxrecoder.ObservableAudioRecorder;
import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import io.reactivex.functions.Consumer;
import io.reactivex.schedulers.Schedulers;
import library.minimize.com.chronometerpersist.ChronometerPersist;
import permissions.dispatcher.NeedsPermission;
import permissions.dispatcher.RuntimePermissions;

@RuntimePermissions
public class MainActivity extends AppCompatActivity {
    private static final String FILE_NAME = "/sample.wav";
    private Disposable subscription;
    private ObservableAudioRecorder observableAudioRecorder;

    private Button record;
    private Button play;
    private Chronometer chronometer;
    private TextView filePathDisplay;

    private ChronometerPersist chronometerPersist;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        filePathDisplay = findViewById(R.id.textViewPath);
        chronometer = findViewById(R.id.chronometer);

        chronometerPersist = ChronometerPersist.getInstance(chronometer, getSharedPreferences("MyPrefs", MODE_PRIVATE));
        final String filePath = Environment.getExternalStorageDirectory() + FILE_NAME;

        filePathDisplay.setText(filePath);

        observableAudioRecorder = new ObservableAudioRecorder.Builder(filePath).audioSourceCamcorder().build();

        play = findViewById(R.id.buttonPlay);
        record = findViewById(R.id.buttonRecord);
        record.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                MainActivityPermissionsDispatcher.startRecordingWithCheck(MainActivity.this);
            }
        });

        subscription = Observable.create(observableAudioRecorder)
                .subscribeOn(Schedulers.computation())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new Consumer<short[]>() {
                    @Override
                    public void accept(@NonNull short[] shorts) {
                        try {
                            observableAudioRecorder.writeShortsToFile(shorts);
                        } catch (IOException e) {
                            Log.e("Write", e.getMessage());
                        }
                    }
                }, new Consumer<Throwable>() {
                    @Override
                    public void accept(Throwable throwable) {
                        Log.e("Error", throwable.getMessage());
                    }
                });

        play.setVisibility(View.GONE);
        play.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                playFile();
            }
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        MainActivityPermissionsDispatcher.onRequestPermissionsResult(this, requestCode, grantResults);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (subscription != null) {
            observableAudioRecorder.stop();
            subscription.dispose();
        }
    }

    @NeedsPermission({Manifest.permission.RECORD_AUDIO, Manifest.permission.WRITE_EXTERNAL_STORAGE})
    void startRecording() {
        if (observableAudioRecorder.isRecording()) {
            record.setText("Record");
            try {
                observableAudioRecorder.stop();
                //helper method for closing the dataStream, also writes the
                //wave header
                observableAudioRecorder.completeRecording();
            } catch (IOException e) {
                Log.e("Recorder", e.getMessage());
            }
            play.setVisibility(View.VISIBLE);
            chronometerPersist.stopChronometer();
        } else {
            record.setText("Stop");
            try {
                observableAudioRecorder.start();
            } catch (FileNotFoundException e) {
                Log.e("Recorder Error", e.getMessage());
            }
            chronometerPersist.startChronometer();
        }
    }

    private void playFile() {
        Intent intent = new Intent();
        intent.setAction(android.content.Intent.ACTION_VIEW);
        File file = new File(Environment.getExternalStorageDirectory(), FILE_NAME);
        Uri fileUri = FileProvider.getUriForFile(this, BuildConfig.APPLICATION_ID + ".audioprovider", file);
        intent.setDataAndType(fileUri, "audio/*");
        startActivity(intent);
    }
}
