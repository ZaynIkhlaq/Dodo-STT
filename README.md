# Dodo STT

Fast, accurate voice typing for Android, powered by [Groq](https://groq.com)'s Whisper.

Dodo STT is a keyboard that listens first. A voice pill sits in the toolbar above a normal QWERTY layout: speak
and tap, and what you said is typed into whatever text field you were in, in any app — then fix a typo
with the keys right below, without switching keyboards. It lives in the same place as Google or
Samsung voice input, so there is no floating bubble over your screen.

- **Accurate.** Uses Whisper large v3 (turbo by default), not the phone's built-in recognizer.
- **Fast.** Groq returns a transcript in about a second.
- **Complete.** Voice and keys in one panel, so editing never means switching keyboards.
- **Simple.** One panel, one settings screen. No account, no subscription.
- **Yours.** You bring your own Groq API key. Groq's free tier covers normal dictation.

## Install

1. Download `dodo-stt.apk` from the [latest release](https://github.com/ZaynIkhlaq/Dodo-STT/releases/latest)
   and open it. Android asks you to allow installs from your browser; Play Protect may say the app is
   unknown because it is not on the Play Store.
2. Open **Dodo STT**. It walks you through the three things it needs — the microphone, a tick beside
   Dodo in Android's keyboard list, and a Groq key — one step at a time. Android shows a standard
   warning for every third-party keyboard; see [Privacy](#privacy) for what this one actually does.
3. The key step links straight to [console.groq.com/keys](https://console.groq.com/keys). Paste the
   key in and tap **Test**.
4. Under **Updates** in settings, tap **Allow** once. From then on Dodo checks GitHub for a new
   release every hour (and whenever the keyboard opens), downloads it in the background, and
   installs it about ten seconds after you close the keyboard — never while you are typing or
   dictating. On Android 12 and later that happens without a prompt; older versions show a
   notification to tap.

## Use

1. Tap any text field.
2. Tap the keyboard-switch icon in the bottom corner of the screen and pick **Dodo STT**. On Samsung
   phones, turn the icon on under Settings → General management → Keyboard list and default →
   Keyboard button on navigation bar.
3. Speak. Tap **Done** on the pill when you are finished and the text appears.

Dictation lives in the toolbar: the pill's bars follow your voice, a timer counts up beside it, and
✕ discards. The keys stay on screen the whole time, so you can fix a typo mid-sentence. Hold the
menu button to go back to your previous keyboard.

The keyboard follows Samsung Keyboard's feel: light and dark themes that follow the system, a tint
and preview bubble on every press, auto-capitals, double-space for a full stop, and long-press for
numbers, accents and punctuation (hold `.`). Drag along the space bar to move the cursor; hold
backspace and it speeds up from letters to whole words. `!#1` has two symbol pages.

## Settings

| Setting | What it does |
|---|---|
| Model | **Turbo** is fastest. **Large v3** is slightly more accurate and slightly slower. |
| Language | **English** avoids accented English being mistaken for another language. **Automatic** handles any language Whisper supports. |
| Start listening when the panel opens | On by default. Turn it off to start each dictation with a tap. |
| Return to my keyboard after each dictation | Off by default, so Dodo STT stays ready for the next sentence. |

Setup steps only appear on this screen while something still needs doing; once the microphone and the
keyboard list are sorted, that block disappears.

## Privacy

- Audio is recorded only while the panel shows "Listening", and is sent only to `api.groq.com` using
  your key. The recording is deleted from the phone as soon as it has been transcribed or discarded.
  How Groq handles it is covered by [Groq's privacy policy](https://groq.com/privacy-policy/).
- There are no analytics, ads, accounts, or third-party libraries. The app talks to no server other
  than Groq, and GitHub for updates: it reads the latest release of this repository and downloads the
  APK, sending nothing about you. Android refuses any update not signed with the release key.
- Your API key is encrypted with a key held in the Android Keystore and is excluded from cloud
  backups and phone-to-phone transfers. It never leaves the device except as the authorization header
  on requests to Groq.
- Dodo STT has no keys, so it never sees what you type on your normal keyboard. It only inserts the
  text it transcribes.

## Build

Requires JDK 17 and the Android SDK (API 35). There is no Gradle wrapper in the repo; use Gradle 8.11+.
The app has no third-party dependencies — not AppCompat, not Material — so every control on screen is
either a framework widget or a custom `View` in this repo.

```bash
gradle :app:assembleDebug
```

Pushes to `main` are built by GitHub Actions, signed, and published under Releases. The signing key
lives in the repository's encrypted secrets, so forks build unsigned APKs unless they add their own
`SIGNING_KEYSTORE_B64` (a base64 PKCS12 keystore with alias `dodostt`) and `SIGNING_PASSWORD`.
