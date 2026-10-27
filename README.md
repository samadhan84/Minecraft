# Arduino AI

Write Arduino code on your **Android TV** (or phone) with help from **Claude**. Say or type what you want
your board to do, and Claude writes the sketch, explains the wiring, changes your code, finds mistakes and
explains code line by line. Works for **Arduino Uno / Nano / Mega / Leonardo / Pro Mini**, **ESP32**
(Dev Module, DevKit V1, S3, C3, CAM) and **ESP8266**.

The TV can't compile or upload code. Use **Copy to phone (QR)** to move the sketch to your phone, then paste
it into the Arduino IDE on a computer and upload it to the board.

(This app replaces the DhruvVishu game that used to live in this repository. It keeps the same Android
package id and signing key, so TVs and phones that had the game update to Arduino AI.)

## Download

https://github.com/samadhan84/Minecraft/releases/download/latest/ArduinoAI.apk

On Android TV, install the **Downloader** app, open that link in it and install. The app updates itself from
the same link.

## First run: add your Claude API key

1. Make an account at **console.anthropic.com** and add some credit (you pay Anthropic per request; a typical
   request costs a few cents).
2. Go to **API keys → Create key** and copy it.
3. In the app, open **Settings**, paste or type the key, and press **Save**. It is stored only on that device.

## How to use

| Button | What it does |
|---|---|
| **✨ Write new code** | Type or say (🎤 uses the remote's microphone) what the board should do. Claude writes a complete sketch for the board you chose, plus the wiring and libraries you need. |
| **✏️ Change my code** | Describe a change, e.g. "add a buzzer on pin 8". Claude edits the code on the right. |
| **🛠️ Fix error** | Type the error from the Arduino IDE (or what goes wrong). Claude explains the problem simply and fixes the code. |
| **💡 Explain code** | Claude explains the current sketch step by step. |
| **Edit / Undo / Save / New** | Edit by hand, undo the last change, keep sketches under **My sketches**. |
| **Copy to phone (QR)** | Shows the code as a QR code; scan it with your phone's camera to copy the text. |
| **Examples** | Blink, Serial, Analog Read, ESP32 WiFi scan, ESP32 web server, ESP32 touch. |

Under **Settings → Explanations in** you can get explanations in Hindi, Marathi, Gujarati, Tamil, Telugu,
Kannada or Bengali (the code stays in English).

On a TV, move with the arrow keys, press OK to choose, and Back to close a window.

## How it's built

| Part | Where |
|---|---|
| Screen (HTML/CSS/JS, TV-remote navigation, code colouring, dialogs) | `app/src/main/assets/www/` |
| WebView host, API key store, voice typing, QR codes | `app/src/main/java/com/vishucraft/game/MenuActivity.kt` |
| Claude requests (official Anthropic Java SDK, model `claude-opus-5-5`) | `app/src/main/java/com/vishucraft/game/ClaudeCoder.kt` |
| Self-updater for the download-link APK | `app/src/main/java/com/vishucraft/game/Updater.kt` |
| Build and publish (APK + Google Play bundle) | `.github/workflows/android.yml` |

Every push builds the APK on GitHub Actions; builds of the default branch are published to the `latest` release.
To test the screen in a desktop browser, open `app/src/main/assets/www/index.html`: it uses a stand-in for the
Android parts and gives fake answers.
