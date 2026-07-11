# HowDoISay / HDIS

> Think in Chinese. Speak in English.

HDIS is an Android floating English-expression assistant. Tap its bubble, say what you mean in Chinese, and receive one natural English sentence without opening a chat app.

## What v1 does

```text
Tap bubble → record Chinese → Doubao BigASR → Ark Chat → English overlay
```

- English-only interface
- draggable, edge-docked floating bubble
- explicit tap-to-start and tap-to-stop recording, capped at 30 seconds
- translucent result card with **Copy**, **Speak**, **Again**, and **Close**
- Android system TTS for optional American-English playback; never auto-speaks
- no chat history, account, automatic listening, accessibility service, or Google speech dependency

## Volcengine setup

HDIS stores credentials on the device only. It does not put them in source code, GitHub Actions, or logs.

1. In Volcengine Speech, enable **Doubao BigASR recording-file flash recognition** and obtain its App ID, access token, and resource ID. The default resource ID in the app is `volc.bigasr.auc_turbo`.
2. In Ark, activate the low-cost `doubao-seed-translation-250915` model. HDIS uses this model ID by default; you can override it with an `ep-...` inference endpoint ID if you create one. Create an Ark API key for that project.
3. Install HDIS, grant Microphone and **Display over other apps**, enter both sets of credentials, and select **Save Settings**.
4. Select **Start Bubble**. In aggressive Android builds, use the app's Battery Optimization link and allow background operation in your phone's own settings.

The ASR adapter uploads a 16 kHz mono WAV only after the user taps **Stop**, then removes the temporary file. Its endpoint is the official short-audio BigASR flash endpoint. The adapter is intentionally isolated behind `AudioTranscriber`, so it can later be replaced with the documented streaming SDK without changing the overlay or Ark translation flow.

## GitHub build and phone testing

This repository deliberately does not need a local Android installation. The GitHub Actions workflow uses JDK 17 and Gradle 8.11.1 to run unit tests, lint, and `assembleDebug`.

1. Push this repository to GitHub.
2. Open **Actions → Android build**.
3. Download the `HowDoISay-debug-apk` artifact from the successful run.
4. Extract and install `app-debug.apk` on your Android phone. Android may ask you to allow installs from the browser or file manager you use.

The APK is a personal-testing debug build and is not signed for store distribution.

## Project layout

- `domain/`: contracts, expression prompt, text cleanup, and state definitions
- `data/`: Keystore-backed credentials and Volcengine adapters
- `overlay/`: foreground service, draggable bubble, recording/result card, and state reducer
- `tts/`: Android `TextToSpeech` wrapper
- `ui/`: English-only Compose settings screen

## Privacy and background behavior

The microphone is used only between the user's **Start** and **Stop** actions. The foreground service is used solely to keep the visible bubble available and displays a persistent notification. It is not an accessibility service and cannot survive Android Force Stop; manufacturer background policies must be configured by the user where necessary.
