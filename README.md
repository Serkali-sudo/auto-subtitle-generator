# Auto Subtitle Generator

Auto Subtitle Generator is an Android app for creating, editing, translating, and exporting subtitles directly on your device. Speech recognition runs locally with [whisper.cpp](https://github.com/ggerganov/whisper.cpp) (Whisper models) or [Vosk](https://github.com/alphacep/vosk-api), translation runs on-device with [ML Kit](https://developers.google.com/ml-kit/language/translation), and media work is handled by [FFmpeg Kit](https://github.com/arthenica/ffmpeg-kit/tree/main/android). No cloud service is required and your audio never leaves the device.

![Auto Subtitle Generator icon](https://github.com/user-attachments/assets/8d2f8631-a595-49ec-92ec-9b60a72e73cf)

## Highlights

- Generate subtitles from videos completely offline.
- Two recognition engines: Whisper (whisper.cpp) and Vosk.
- Whisper word-level timestamps for accurate, tappable cue timing.
- Multilingual transcription with automatic language detection.
- On-device translation, including bilingual (original + translated) subtitles.
- Optional Voice Activity Detection (VAD) to skip silence and speed up long videos.
- Queue multiple videos and keep processing while the app is in the background.
- Download, pause, resume, cancel, and manage speech models inside the app.
- Bundled English Whisper model so the app works out of the box.
- Preview subtitles with video playback before exporting.
- Edit, merge, split, delete, and retime generated subtitles.
- Export subtitle files as SRT or VTT.
- Export videos with soft subtitles or hard burned-in subtitles.
- Create short-form styled captions, including word-by-word caption timing.
- Batch export subtitle files or captioned videos.
- Track completed exports in an export library.

## Screenshots

| Generate | Preview | Models |
| --- | --- | --- |
| <img width="270" height="600" alt="Screenshot_20260602_001838" src="https://github.com/user-attachments/assets/1b0b5085-228b-4503-b573-de4e3254001d" /> | <img width="270" height="600" alt="Screenshot_20260602_002104" src="https://github.com/user-attachments/assets/fcaec73e-fd06-4018-9f1f-40e5ceaac0d5" /> | <img width="270" height="600" alt="Screenshot_20260602_002154" src="https://github.com/user-attachments/assets/587eb864-e00f-419e-84db-bf352fd14abe" /> |

| Exports | Settings |
| --- | --- |
| <img width="270" height="600" alt="Screenshot_20260602_002525" src="https://github.com/user-attachments/assets/3ba347db-fd3d-4073-9e75-fdbc7a75136e" /> | <img width="270" height="600" alt="Screenshot_20260602_002536" src="https://github.com/user-attachments/assets/6e5429ef-df4b-4024-b675-aec1d40b6040" /> |

## Install APK
You can find the latest releases with apk files here: https://github.com/Serkali-sudo/auto-subtitle-generator/releases

## Features

### Local subtitle generation

Auto Subtitle Generator extracts audio from the selected video, runs speech recognition locally, and turns recognized speech into timed subtitle cues. Generated subtitles can be reviewed immediately in the preview screen.

Because recognition is local, the video audio is not sent to a server. Accuracy depends on the selected model, audio quality, language, accents, background noise, and device performance.

### Recognition engines

The app supports two offline engines and lets you pick a model from a single catalog:

- **Whisper (whisper.cpp):** the default engine. Provides word-level timestamps, strong multilingual accuracy, and automatic language detection. Models range from `tiny` and `base` up to `small`, `medium`, and `large-v3` / `large-v3-turbo`, each with English-only and multilingual variants and quantized (`q5`/`q8`) builds that trade size for accuracy. A quantized English Base model is bundled in the APK so generation works without any download.
- **Vosk:** lightweight Kaldi-based models covering a wide range of languages, useful on lower-end devices or for languages where a small dedicated model is preferred.

### Language and translation

- **Language selection:** choose a specific transcription language or let Whisper auto-detect it. Multilingual Whisper models can transcribe non-English audio directly.
- **On-device translation:** translate generated subtitles with ML Kit. Translation language packs download on demand and run locally.
- **Subtitle layers:** export the original transcription, the translation only, or **double (bilingual)** subtitles that stack the original and translated text together.

### Voice Activity Detection (silence skipping)

For long videos, VAD can detect speech regions and skip silent stretches so transcription runs faster. Two detectors are available:

- **WebRTC VAD** (fast, lightweight)
- **Silero VAD** (neural, more selective)

Each supports Normal / Aggressive / Very aggressive sensitivity. VAD is **off by default** because skipping audio can occasionally drop quiet speech or shift timings. When you add a long video, the app offers to enable silence-skipping for that video, with a clear speed-vs-accuracy tradeoff, while leaving shorter videos untouched.

### Queue processing

You can add multiple videos to the subtitle queue and process them one by one. Queue state is persisted, so pending and completed items remain visible across app restarts. Each queued video also remembers its own settings, such as whether silence-skipping was enabled for it.

The queue supports:

- Single-video generation.
- Multi-video queue generation.
- Progress tracking for active work.
- Canceling active media work.
- Multi-select actions.
- Select all for visible queue items.
- Batch subtitle export.
- Batch video export.

### Foreground background work

Long-running work is handled by a foreground service instead of being owned only by the activity or view model. This keeps user-started work alive when the app is backgrounded or the UI is recreated, and a wake lock keeps the device working through long jobs.

The service is used for:

- Subtitle generation.
- Subtitle file saves.
- Soft subtitle video exports.
- Hard subtitle video exports.
- Batch exports.
- Model downloads.

Model downloads and media processing use separate notifications, so a download can continue while subtitle generation or export is also running.

### Subtitle preview and editing

The preview screen lets you inspect and adjust generated subtitles before exporting.

Supported editing actions include:

- Edit subtitle text.
- Merge cues.
- Split or adjust cue timing.
- Delete cues.
- Preview subtitles during video playback.
- Use styled captions for short-form videos.

### Subtitle file export

Generated subtitles can be saved as:

- `SRT`
- `VTT`

The default batch subtitle format can be changed in settings.

### Video export

The app can create captioned video files in two main ways:

- **Soft subtitles:** subtitles are stored as a separate subtitle stream in the video container when possible, so compatible players can toggle them on or off.
- **Hard subtitles:** subtitles are burned into the video frames and are always visible.

Soft subtitle export is useful when you want a non-destructive caption track. Hard subtitle export is useful for platforms or players where captions must always be visible. Both honor the selected subtitle layer (original, translation, or bilingual).

### Shorts captions

Short-form caption styling is available for vertical/social videos. The app can use word-level timing to create word-by-word captions and can export styled subtitles into supported video outputs.

Settings include options for:

- Default short caption size.
- Uppercase short captions.
- Word-by-word mode.
- Skipping the shorts setup dialog.

### Model manager

The app includes a unified manager for Whisper and Vosk speech recognition models.

Model features include:

- Bundled English Whisper model support.
- Downloadable model catalog covering many languages and accuracy tiers.
- Search and horizontal filter chips.
- Pause, resume, and cancel for downloads.
- Queued model downloads.
- Partial download resume.
- Compatibility checks for large models.
- RAM usage display option.

Downloaded models are not auto-loaded after download completion while another generation or export task is active. This prevents active media processing from being interrupted by a model switch.

### Export library

Completed exports are saved into the export library so they are easy to find later.

The export library supports:

- Subtitle and video export records.
- Search and filtering.
- Grid/list viewing.
- Opening exported files.
- Sharing exported files.
- Moving files.
- Deleting records.
- Multi-select actions.
- Select all for visible export items.

### Settings

Current settings include:

- Recognition language / auto-detect (Whisper).
- Subtitle translation, with source and target languages.
- Voice Activity Detection model and sensitivity.
- Maximum subtitle line length.
- Keep sentences together.
- Suppress non-speech (SDH) captions.
- Batch subtitle export format.
- Export location preference.
- Short caption text size.
- Uppercase short captions.
- Word-by-word short captions.
- Skip shorts setup dialog.
- Completion notifications.
- RAM usage display.

## How it works

1. Pick a video or add videos to the queue.
2. Choose the speech model (Whisper or Vosk) you want to use.
3. Optionally set the language, translation, and silence-skipping options.
4. Generate subtitles locally on the device.
5. Preview and edit the generated subtitles.
6. Export subtitles as a file or render a captioned video.

For long-running operations, the foreground service keeps the active task connected to Android's notification system. The UI binds back to that service when the app is reopened, so progress and active task state can be shown again without restarting the task.

## Permissions

The app uses Android foreground service and wake lock permissions for long-running work:

- `FOREGROUND_SERVICE`
- `FOREGROUND_SERVICE_MEDIA_PROCESSING`
- `FOREGROUND_SERVICE_DATA_SYNC`
- `WAKE_LOCK`

Media processing work uses the media processing foreground service type. Model downloads use the data sync foreground service type. The wake lock keeps the CPU active during long transcriptions and exports.

On Android 13 and newer, notification permission affects optional completion/progress notifications. Required foreground service notifications are still part of the active foreground service path.

## Tech stack

- Android app written in Java.
- [whisper.cpp](https://github.com/ggerganov/whisper.cpp) for offline Whisper speech recognition with word-level timing.
- [Vosk Android](https://github.com/alphacep/vosk-api) for offline Kaldi-based speech recognition.
- [android-vad](https://github.com/gkonovalov/android-vad) (WebRTC and Silero) for voice activity detection.
- [ML Kit Translation](https://developers.google.com/ml-kit/language/translation) for on-device subtitle translation.
- [FFmpeg Kit](https://github.com/arthenica/ffmpeg-kit/tree/main/android) for audio extraction and video/subtitle export.
- [AndroidX Media3](https://developer.android.com/jetpack/androidx/releases/media3) for video playback.
- Android foreground services for long-running generation, export, and download work.
- Material Components for Android UI.

## Build from source

Clone the project and open it in Android Studio, or build from the command line:

```powershell
.\gradlew.bat :app:assembleDebug
```

The app module uses:

- Minimum SDK: 24
- Target SDK: 36

## Notes

- Recognition quality depends heavily on the selected model and source audio.
- Larger models can improve recognition but require more storage, memory, and processing time.
- Voice Activity Detection trades accuracy for speed; leave it off when timing precision matters most.
- Full guaranteed resume after Android kills the whole app process during an active FFmpeg export is not currently promised.
- Pending/completed queue state and partial model downloads are recoverable on the next launch.

## Used projects

- [whisper.cpp](https://github.com/ggerganov/whisper.cpp)
- [Vosk Speech Recognizer](https://github.com/alphacep/vosk-api)
- [android-vad](https://github.com/gkonovalov/android-vad)
- [ML Kit Translation](https://developers.google.com/ml-kit/language/translation)
- [FFmpeg Kit](https://github.com/arthenica/ffmpeg-kit/tree/main/android)
- [AndroidX Media3](https://developer.android.com/jetpack/androidx/releases/media3)
- [Material Components for Android](https://github.com/material-components/material-components-android)
</content>
</invoke>
