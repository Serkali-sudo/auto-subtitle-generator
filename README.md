# Auto Subtitle Generator

A simple Android app that automatically generates subtitles from videos locally using [Vosk speech recognizer](https://github.com/alphacep/vosk-api) and [FFmpeg](https://github.com/arthenica/ffmpeg-kit/tree/main/android).

![auto_sub_icon2](https://github.com/user-attachments/assets/8d2f8631-a595-49ec-92ec-9b60a72e73cf)

# Features
* Generate subtitles automatically from videos without any internet.
* Edit, Merge, Delete subtitles in preview mode
* Export subtitles as .srt or .vtt
* Export the video with generated soft or hard subtitles:
  * **Soft Subtitles**: Subtitles are stored as a separate stream within the video file, allowing users to toggle them on or off. They are not permanently embedded in the video frames.
  * **Hard Subtitles**: Subtitles are permanently burned into the video frames, making them a permanent part of the visual content and always visible.

# Screenshot
![sc2](https://github.com/user-attachments/assets/487aac36-2ab5-4f08-a813-74ce6e4fd12a)

# More
* Since this app is using a small speech recognizer model, obviously results wont be as good using a large model like whisper-large-v3.
* Even though it often doesnt get all of the words right it definetly gets timings right so using edit function in the app is better than writing the subtitle from scratch.
* Currently this app only supports english but vosk supports other languages too. You can download models from here: https://alphacephei.com/vosk/models.

# Used Projects
* [Vosk Speech Recognizer](https://github.com/alphacep/vosk-api)
* [FFmpeg](https://github.com/arthenica/ffmpeg-kit/tree/main/android)
