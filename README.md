# Dodo STT

Fast, accurate voice typing for Android, powered by [Groq](https://groq.com)'s Whisper.

Dodo STT is a button, not a keyboard. Keep the keyboard you already use — Samsung's, Gboard,
whatever — and Dodo docks a small tile against the edge of the screen. Hold it, talk, let go, and
what you said is typed where your cursor is.

- **Accurate.** Uses Whisper large v3 (turbo by default), not the phone's built-in recognizer.
- **Fast.** Groq returns a transcript in about a second.
- **Out of the way.** Your keyboard, your layout, your muscle memory. Dodo adds one pill above it.
- **Simple.** One bar, one settings screen. No account, no subscription.
- **Yours.** You bring your own Groq API key. Groq's free tier covers normal dictation.

## Install

1. Download `dodo-stt.apk` from the [latest release](https://github.com/ZaynIkhlaq/Dodo-STT/releases/latest)
   and open it. Android asks you to allow installs from your browser; Play Protect may say the app is
   unknown because it is not on the Play Store.
2. Open **Dodo STT**. It walks you through what it needs, one step at a time: the microphone, the
   bar itself, and a Groq key.
3. The bar step asks for two switches. **Accessibility** is what puts the bar above your keyboard and
   lets it type into the field you are in — Android has no other way for an app to do either. **Display
   over other apps** is not used to draw anything; it is the exemption Android requires before it will
   hand an app the microphone from inside somebody else's app.
4. The key step links straight to [console.groq.com/keys](https://console.groq.com/keys). Paste the
   key in and tap **Test**.
5. Under **Updates**, tap **Allow** once. After that Dodo installs its own updates: it checks GitHub
   hourly, downloads anything newer, and installs it about ten seconds after you stop dictating.

## Use

1. Tap any text field. Dodo's tile appears against the edge of the screen, beside your keyboard.
2. **Hold it and talk.** Let go and what you said is typed at your cursor.
3. Or **double-tap** to lock it listening, hands free, and tap once to finish.

While recording the tile grows out of the edge into a pill with a level rail and a clock. Slide your
finger away before letting go to throw the recording away. Drag the tile anywhere along either edge;
it stays where you put it.

Recording is sent to Groq in one piece when you finish, not streamed in fragments — a minute of
speech comes back in about a second, and the transcript is better for having heard the whole thing.

## Settings

| Setting | What it does |
|---|---|
| Model | **Turbo** is fastest. **Large v3** is slightly more accurate and slightly slower. |
| Language | **English** avoids accented English being mistaken for another language. **Automatic** handles any language Whisper supports. |
| Updates | Shows the installed version, and grants Dodo permission to install its own updates. |

Setup steps only appear on this screen while something still needs doing; once everything is granted,
that block disappears.

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
- The accessibility service looks at one thing: where the cursor is, so it knows where to put text
  and where to park the bar. It does not read what you type, keeps no record of any field, and sends
  nothing anywhere except the audio you dictate, to Groq.

## Build

Requires JDK 17 and the Android SDK (API 35). There is no Gradle wrapper in the repo; use Gradle 8.11+.
The app has no third-party dependencies — not AppCompat, not Material — so every control on screen is
either a framework widget or a custom `View` in this repo.

`Dictation` owns a session end to end — record, send, hand back the text — and knows nothing about
where that text goes. `DodoAccessibility` is the button: an accessibility overlay window, which is
the only window type Android layers *above* the keyboard, plus `ACTION_SET_TEXT` on the focused node
to type. `DodoTab` is the tile, one view that changes shape rather than a set that come and go.

```bash
gradle :app:assembleDebug
```

Pushes to `main` are built by GitHub Actions, signed, and published under Releases. The signing key
lives in the repository's encrypted secrets, so forks build unsigned APKs unless they add their own
`SIGNING_KEYSTORE_B64` (a base64 PKCS12 keystore with alias `dodostt`) and `SIGNING_PASSWORD`.
