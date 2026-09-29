# Jarvis (Android)

Say **"Jarvis"** → beep → speak a command → Gemini operates your phone → it replies by voice.

```
Porcupine wake word ─► SpeechRecognizer ─► Agent loop ─► TextToSpeech
                                              │
                    ┌─────────────────────────┘
                    ▼
   UI tree (+ screenshot when needed) ─► Gemini function calling ─► Accessibility actions
```

## Get the APK (no PC needed)
1. Push this repo to GitHub (branch `main`).
2. **Actions** tab → *Build APK* runs automatically (or run it manually).
3. When it's green, open the repo's **Releases** page on your phone and download `app-debug.apk`, then install it.

## First-time setup on the phone
1. Get keys:
   - Gemini: https://aistudio.google.com/apikey
   - Picovoice (wake word, free personal tier): https://console.picovoice.ai
2. Open Jarvis, paste both keys, **Save**.
3. **Enable Accessibility service** → Jarvis → ON.
   - Android 13+ sideloaded apps: if the toggle is greyed out, go to *Settings → Apps → Jarvis → ⋮ → Allow restricted settings*, then try again.
4. **Allow "Display over other apps"** (needed so Android lets Jarvis launch apps from the background; also shows the status pill).
5. **Battery → allow background use**, then **Start Jarvis**.
6. Say **"Jarvis"**, wait for the beep, then speak.

Test first with the *Run command* box (no wake word needed).

## Wake word
Built-in keyword is **"Jarvis"**. For the exact phrase "Hey Jarvis", train it in the Picovoice console, download the Android `.ppn`, rename it `hey-jarvis.ppn`, put it in `app/src/main/assets/`, and push. It is picked up automatically.

## Languages
- *Speech recognition language*: `en-US`, `si-LK` (Sinhala), `ta-LK` …  Gemini understands whatever you say.
- *Reply language*: `English`, or `Singlish (Sinhala in English letters)` which the English voice reads well. Real Sinhala replies need a Sinhala TTS voice installed.

## Safety
- Jarvis must call `confirm` (asks you by voice) before sending, paying, deleting, posting or calling.
- Never types passwords/OTP.
- Pull the notification down and tap **Stop task** to cancel a running task.
- Keys are stored with EncryptedSharedPreferences. Don't commit keys.

## Limits
- Won't work on the lock screen; some apps (banking, DRM) block accessibility/screenshots.
- Not for Play Store (Accessibility policy); this is a personal sideload build.
- Debug-signed APK; updates install over each other only if built by the same CI keystore (uninstall first if Android complains).

## Code map (`app/src/main/java/com/jarvis/agent`)
| File | Role |
|---|---|
| `WakeService.kt` | Foreground service, Porcupine, speech in/out, overlay |
| `Agent.kt` | Gemini tool loop + tool declarations + system prompt |
| `JarvisAccessibilityService.kt` | Read UI tree, click, type, scroll, gestures, screenshot |
| `GeminiClient.kt` | REST + function calling |
| `MainActivity.kt`, `Prefs.kt` | Settings UI, encrypted storage |
