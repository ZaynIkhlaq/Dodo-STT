# Dodo STT

Personal Android voice input. It is an input method with no keys: one mic button. Speak, tap, and
the transcript from Groq's Whisper is typed into the focused text field.

- Engine: `whisper-large-v3-turbo` (or `whisper-large-v3`) on the Groq API, with your own key.
- No accounts, analytics, or third-party libraries. Audio goes only to `api.groq.com`.
- Builds in GitHub Actions; every push to `main` publishes a signed APK under Releases.

## Use

1. Install the APK from Releases, open **Dodo STT**, allow the microphone, turn the keyboard on, paste the Groq key.
2. In any app, tap a text field, tap the keyboard-switch icon in the bottom corner, pick **Dodo STT**.
3. Speak. Tap the mic to finish. The keyboard icon on the panel returns to your normal keyboard.
