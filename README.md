# TV Cast

An Android app that plays MP4 videos from your phone on a Samsung Smart TV, over your home Wi-Fi.
Samsung TVs have a built-in DLNA player, so you don't need to install anything on the TV.

## How it works

1. The app finds TVs on your Wi-Fi using SSDP/UPnP (the same discovery protocol the TV uses).
2. It runs a small HTTP server on the phone that serves the chosen video with byte-range support (needed for seeking).
3. It tells the TV, using the UPnP AVTransport protocol, to play that URL. The TV then streams the file directly from the phone.

The phone doesn't re-encode the video. The TV plays the file as it is, so the TV's own codec support is what matters (H.264 or H.265 video with AAC audio in an MP4 file works everywhere).

## Features

- Automatically finds Samsung TVs and other DLNA renderers (LG, Sony, Philips, Kodi, …)
- Pick a video from the gallery or files app, or use **Share → TV Cast** from any app
- Play / pause, seek bar, −10 s / +30 s
- TV volume, including from the phone's volume buttons while casting
- Keeps streaming with the screen off (foreground service with a "Stop" button in the notification)
- Remembers your TV, and lets you add a TV by IP address if discovery is blocked on your network
- Light and dark mode

## Build the APK

**With GitHub, no computer needed:** push this folder to a GitHub repository. The included workflow
(`.github/workflows/build.yml`) builds the app and publishes `TVCast.apk` under **Releases → latest**.
Open that link on your phone to download it.

**With Android Studio:** open this folder, wait for Gradle to sync, then run **Build → Build APK(s)**.
From the command line, run `./gradlew assembleDebug`. The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

Requirements: JDK 17 and the Android SDK (platform 35). No third-party libraries are used.

## Install on your phone

1. Open the downloaded `TVCast.apk`.
2. When Android asks, allow your browser or file manager to install unknown apps.
3. Open **TV Cast**, choose a video, pick your TV and tap **Play on TV**.

## Troubleshooting

- **No TVs found:** the TV must be switched on and connected to the same Wi-Fi as the phone.
  Guest networks and "AP isolation" block this. Try **Enter TV IP**. On the TV, the IP address is under
  Settings → General → Network → Network Status → IP Settings.
- **The TV says it can't play the file:** the video codec isn't supported by that TV. Re-encode it to H.264/AAC MP4.
- **A Samsung TV asks to allow the device:** accept it on the TV once. You can also allow it under
  Settings → General → External Device Manager → Device Connection Manager.

`app/debug.keystore` is a throwaway signing key with the public password `android`. It's included so that
every build installs cleanly over the previous one. Don't use it for anything else.
