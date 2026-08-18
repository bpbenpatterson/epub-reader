# EPUB Reader

A minimal Android app that reads EPUB books aloud using the device's text-to-speech engine. Written in Kotlin, no external UI framework — the interface is built programmatically.

## Features

- **Open EPUB files** — the document picker is filtered to `application/epub+zip`.
- **Real chapter titles** — parsed from the book's table of contents (EPUB2 NCX or EPUB3 nav), falling back to each chapter's HTML `<title>` / first heading, then "Chapter N".
- **Text-to-speech playback** — uses the **system's preferred TTS engine** (see [TTS engine setup](#tts-engine-setup)). Reads sentence-by-sentence through the book.
- **Transport controls** — Prev / Play-Pause / Next, where Prev and Next step through the book sentence-by-sentence, plus a media notification with controls (works with Bluetooth/headset media keys).
- **Chapter progress slider** — shows your position through the current chapter (sentence-based) and is draggable to jump around.
- **Contents toggle** — the chapter list is hidden by default; a "Contents" button reveals it.
- **Recents** — remembers up to 6 recently opened books (kept as local copies) with a "Recents" button to reopen them; each entry has a Delete button that removes it from the list and frees its stored copy.
- **Sleep timer** — set a countdown (5–60 min); a live countdown shows on screen while active, and playback pauses when it expires.
- **Speech speed** — adjustable 0.5×–2.0× from Settings.
- **Theme** — Light / Dark / System (follows the phone's dark-mode setting).
- **Resume support** — remembers the last book and exact reading position, and restores it on launch.

## Requirements

- Android 8.0 (API 26) or newer.
- A TTS engine installed and set as the system default (most phones ship Google TTS; for better neural voices see below).

## TTS engine setup

The app deliberately does **not** bundle its own speech engine — it speaks with whatever TTS engine is selected in the system settings. To get high-quality, fully-offline neural voices:

1. Install a sherpa-onnx based engine such as **SherpaTTS** (open source, on F-Droid: `org.woheller69.ttsengine`).
2. Open **Settings → Accessibility → Text-to-speech output → Preferred engine** and select it.
3. In the engine's own app, download the voice(s) you want (e.g. a US-English Piper voice).

The app declares a `<queries>` entry for `android.intent.action.TTS_SERVICE` in its manifest, which is required on Android 11+ for an app to see non-default TTS engines and use the real system default. Without it, the app silently falls back to the first visible engine (usually Google TTS).

## Building

The project has no Gradle wrapper. It was built with:

- **Gradle 8.7** (installed at `~/tools/gradle-8.7`)
- **Android Gradle Plugin 8.5.2**, **Kotlin 1.9.24**, **JDK 17+**
- Android SDK at `/home/moses/Android/Sdk` (configured via `local.properties`)

```bash
# from the project root
~/tools/gradle-8.7/bin/gradle :app:assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/EpubReader.apk`.

Notes:

- `compileSdk`/`targetSdk` is 34; the SDK must have the `android-34` platform installed (AGP auto-downloads it if licenses are accepted).
- If the SDK path differs, edit `local.properties`:
  ```
  sdk.dir=/path/to/Android/Sdk
  ```

## Installing on a device

With the phone connected over ADB:

```bash
adb install -r app/build/outputs/apk/debug/EpubReader.apk
```

## Project structure

```
app/src/main/
├── AndroidManifest.xml
├── java/com/example/epubreader/
│   ├── MainActivity.kt    # UI, book opening, recents, settings
│   ├── TtsService.kt      # foreground TTS playback service, media session, sleep timer
│   └── EpubParser.kt      # EPUB parsing (chapters, titles, text extraction)
└── res/
    ├── values/themes.xml       # Light / Dark theme definitions
    └── values-night/themes.xml # Night-mode variant (System theme)
```

### Data storage

- **Books** are copied into the app's private storage (`filesDir/books/`) so they reopen reliably; the most recent ones (up to 6) are listed in **Recents**.
- **Preferences** (SharedPreferences):
  - `settings.xml` — speech rate, saved reading position (chapter + sentence).
  - `book.xml` — recents list and theme choice.

## How it works (brief)

- `MainActivity` opens/picks an EPUB, `EpubParser` extracts chapters, and the book is handed to `TtsService` via a static `BookHolder`.
- `TtsService` runs as a foreground service with a media session + notification. It splits each chapter into sentences and speaks them one at a time through the system TTS engine, tracking position as it goes.
- The UI polls the service (once per second) to keep the chapter progress slider and sleep-timer countdown in sync.
