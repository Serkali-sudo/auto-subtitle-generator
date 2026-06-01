# Auto Subtitle Generator

Auto Subtitle Generator is an Android app for creating, editing, and exporting subtitles directly on your device. It uses [Vosk](https://github.com/alphacep/vosk-api) for local speech recognition and [FFmpeg Kit](https://github.com/arthenica/ffmpeg-kit/tree/main/android) for media processing, so subtitle generation does not need a cloud service.

![Auto Subtitle Generator icon](https://github.com/user-attachments/assets/8d2f8631-a595-49ec-92ec-9b60a72e73cf)

## Highlights

- Generate subtitles from videos offline.
- Queue multiple videos and keep processing while the app is in the background.
- Download, pause, resume, cancel, and manage Vosk speech models inside the app.
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

## Features

### Local subtitle generation

Auto Subtitle Generator extracts audio from the selected video, runs speech recognition locally with Vosk, and turns recognized speech into timed subtitle cues. The generated subtitles can be reviewed immediately in the preview screen.

Because recognition is local, the video audio is not sent to a server. Accuracy depends on the selected Vosk model, audio quality, language, accents, background noise, and device performance.

### Queue processing

You can add multiple videos to the subtitle queue and process them one by one. Queue state is persisted, so pending and completed items remain visible across app restarts.

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

Long-running work is handled by a foreground service instead of being owned only by the activity or view model. This keeps user-started work alive when the app is backgrounded or the UI is recreated.

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

- Soft subtitles: subtitles are stored as a separate subtitle stream in the video container when possible, so compatible players can toggle them on or off.
- Hard subtitles: subtitles are burned into the video frames and are always visible.

Soft subtitle export is useful when you want a non-destructive caption track. Hard subtitle export is useful for platforms or players where captions must always be visible.

### Shorts captions

Short-form caption styling is available for vertical/social videos. The app can use word-level timing to create word-by-word captions and can export styled subtitles into supported video outputs.

Settings include options for:

- Default short caption size.
- Uppercase short captions.
- Word-by-word mode.
- Skipping the shorts setup dialog.

### Model manager

The app includes model management for Vosk speech recognition models.

Model features include:

- Bundled English model support.
- Downloadable model catalog.
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
2. Choose the speech model you want to use.
3. Generate subtitles locally on the device.
4. Preview and edit the generated subtitles.
5. Export subtitles as a file or render a captioned video.

For long-running operations, the foreground service keeps the active task connected to Android's notification system. The UI binds back to that service when the app is reopened, so progress and active task state can be shown again without restarting the task.

## Permissions

The app uses Android foreground service permissions for long-running work:

- `FOREGROUND_SERVICE`
- `FOREGROUND_SERVICE_MEDIA_PROCESSING`
- `FOREGROUND_SERVICE_DATA_SYNC`

Media processing work uses the media processing foreground service type. Model downloads use the data sync foreground service type.

On Android 13 and newer, notification permission affects optional completion/progress notifications. Required foreground service notifications are still part of the active foreground service path.

## Tech stack

- Android app written in Java.
- [Vosk Android](https://github.com/alphacep/vosk-api) for offline speech recognition.
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

- Recognition quality depends heavily on the selected Vosk model and source audio.
- Larger models can improve recognition but require more storage, memory, and processing time.
- Full guaranteed resume after Android kills the whole app process during an active FFmpeg export is not currently promised.
- Pending/completed queue state and partial model downloads are recoverable on the next launch.

## Used projects

- [Vosk Speech Recognizer](https://github.com/alphacep/vosk-api)
- [FFmpeg Kit](https://github.com/arthenica/ffmpeg-kit/tree/main/android)
- [AndroidX Media3](https://developer.android.com/jetpack/androidx/releases/media3)
- [Material Components for Android](https://github.com/material-components/material-components-android)
