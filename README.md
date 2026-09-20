# Dodo STT

Fast, accurate voice typing for Android, powered by [Groq](https://groq.com)'s Whisper.

Dodo STT is a keyboard with no keys. It shows one mic button where your keyboard normally sits.
Speak, tap, and what you said is typed into whatever text field you were in, in any app. It lives in
the same place as Google or Samsung voice input, so there is no floating bubble over your screen.

- **Accurate.** Uses Whisper large v3 (turbo by default), not the phone's built-in recognizer.
- **Fast.** Groq returns a transcript in about a second.
- **Simple.** One panel, one settings screen. No account, no subscription.
- **Yours.** You bring your own Groq API key. Groq's free tier covers normal dictation.

## Install

1. Download `dodo-stt.apk` from the [latest release](https://github.com/ZaynIkhlaq/Dodo-STT/releases/latest)
   and open it. Android asks you to allow installs from your browser; Play Protect may say the app is
   unknown because it is not on the Play Store.
2. Open **Dodo STT**. Tap **Allow** for the microphone, then **Open** and switch on Dodo STT in the
   keyboard list. Android shows a standard warning for every third-party keyboard; see
   [Privacy](#privacy) for what this one actually does.
3. Create a free API key at [console.groq.com/keys](https://console.groq.com/keys), paste it in, and
   tap **Test key**.

## Use

1. Tap any text field.
2. Tap the keyboard-switch icon in the bottom corner of the screen and pick **Dodo STT**. On Samsung
   phones, turn the icon on under Settings → General management → Keyboard list and default →
   Keyboard button on navigation bar.
3. Speak. Tap the mic when you are done and the text appears.

The panel also has backspace (hold to repeat), enter, discard, and a button that returns to your
normal keyboard.

## Settings

| Setting | What it does |
|---|---|
| Model | **Turbo** is fastest. **Large v3** is slightly more accurate and slightly slower. |
| Language | **English** avoids accented English being mistaken for another language. **Detect automatically** handles any language Whisper supports. |
| Start listening as soon as the panel opens | On by default. Turn it off to start each dictation with a tap. |
| Go back to my keyboard after each dictation | Off by default, so Dodo STT stays ready for the next sentence. |

## Privacy

- Audio is recorded only while the panel shows "Listening", and is sent only to `api.groq.com` using
  your key. The recording is deleted from the phone as soon as it has been transcribed or discarded.
  How Groq handles it is covered by [Groq's privacy policy](https://groq.com/privacy-policy/).
- There are no analytics, ads, accounts, or third-party libraries. The app talks to no server other
  than Groq.
- Your API key is encrypted with a key held in the Android Keystore and is excluded from cloud
  backups and phone-to-phone transfers. It never leaves the device except as the authorization header
  on requests to Groq.
- Dodo STT has no keys, so it never sees what you type on your normal keyboard. It only inserts the
  text it transcribes.

## Build

Requires JDK 17 and the Android SDK (API 35). There is no Gradle wrapper in the repo; use Gradle 8.11+.

```bash
gradle :app:assembleDebug
```

Pushes to `main` are built by GitHub Actions, signed, and published under Releases. The signing key
lives in the repository's encrypted secrets, so forks build unsigned APKs unless they add their own
`SIGNING_KEYSTORE_B64` (a base64 PKCS12 keystore with alias `dodostt`) and `SIGNING_PASSWORD`.
