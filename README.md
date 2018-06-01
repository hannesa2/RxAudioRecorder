# RxJava2 Reactive Audio Recorder

[![GitHub license](https://img.shields.io/badge/license-Apache%20Version%202.0-blue.svg)](https://github.com/sbrukhanda/fragmentviewpager/blob/master/LICENSE.txt)

A reactive (RxJava 2) implementation of the AudioRecord API for recording raw (pcm) audio-data

### Usage

##### Create an instance of RecorderOnSubscribe giving it the path to the file
```java
final String filePath = Environment.getExternalStorageDirectory() + "/sample.wav"; //dummy file 
ObservableAudioRecorder recorder = new ObservableAudioRecorder.Builder(filePath)
                                                      .sampleRate(22000)       //by default 44100
                                                      .stereo()                //by default mono
                                                      .audioSourceCamcorder()  //by default MIC
                                                      .build();
```
##### Use the recorder OnSubscribe to create an observable
```java
Observable.create(recorder)#
          .subscribe((Consumer) (shorts) -> {
              ...
              recorder.writeShortsToFile(shorts);
          });
```

#### After setting up the Observer, manipulate the recording-process by using these methods

| Name | Description |
|:----:|:-----------:|
| start() | Starts the recorder and moves it to *Recording* state |
| stop() | Stops the recorder and moves it to *Stopped* state |
| pause() | Pauses the recorder and moves it to *Paused* state |
| resume() | Resumes the recorder if it's in *Paused* state |
| isRecording() | Returns true if the recorder is in *Recording* state |
| isRecordingStopped() | Checks whether the recorder is in *Stopped* state or not |

#### Helper methods for wave file write operations

| Name | Description |
|:----:|:-----------:|
| writeShortsToFile(shorts) | Writes the short buffers to wave file |
| completeRecording() | Writes the Wave header info to the file (Call it after *stop()* method) |

## Download 
Repository available on jitpack

```Gradle
allprojects {
    repositories {
        ...
        maven { url 'https://jitpack.io' }
    }
}
```
```Gradle
dependencies {
    implementation 'com.github.hannesa2:RxAudioRecorder:master-SNAPSHOT'
}

```

## License 
```
Copyright 2018 Hannes Achleitner

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

   http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```


