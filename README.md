# SAATHI — Your Friend. Your World.

**Created by Usman**

SAATHI is a native Android AI Voice Companion built with Kotlin, Jetpack Compose, Material 3, Firebase (Core, Cloud Firestore, App Check, Firebase AI Logic with Gemini), Room, DataStore, and a secure ElevenLabs Voice Backend proxy.

---

## 1. Firebase Setup
- **No Google Login Required**: SAATHI opens directly into the companion experience (Splash -> First-Launch Onboarding -> Voice Companion) using private on-device Room & DataStore storage without requiring Google Sign-In or OAuth web client IDs.
- **Firebase AI Logic (Gemini 2.5 Flash & Gemini 2.5 Flash-Lite)**: Configured via `app/google-services.json`, App Check (`configureAppCheck(this, intent)` in `MainActivity.onCreate`), and `Firebase.ai` — no client-side `GEMINI_API_KEY` is embedded in the APK.
- **Cloud Firestore**: Uses the provisioned named database (`R.string.firestore_database_id`) and hardened per-user security rules in `firestore.rules`.

## 2. Secure ElevenLabs Voice Backend Setup
ElevenLabs API keys are **never** stored in the Android APK. All ElevenLabs Text-to-Speech streaming and Scribe Speech-to-Text requests route through the Node.js Express proxy in `/backend` and are verified via Firebase App Check (`X-Firebase-AppCheck`) or Firebase Auth Bearer tokens:
```bash
cd backend
npm install
export ELEVENLABS_API_KEY="your_elevenlabs_api_key"
export GOOGLE_CLOUD_PROJECT="gen-lang-client-0541897442"
npm start
```
Deploy `/backend` to Cloud Run (or any HTTPS Node host), then open **SAATHI -> Voice Select -> ElevenLabs Secure Backend**, paste your HTTPS URL, and tap **Save & Test Connection**.

## 3. Building & Installing the Release / Debug APK
```bash
# Run Tier 1 & Tier 2 Firestore rules tests
FIRESTORE_EMULATOR_HOST="127.0.0.1:8085" node --test firestore.test.js
gradle :app:testDebugUnitTest

# Build Debug APK
gradle :app:assembleDebug

# Build Release APK (requires KEYSTORE_PATH / STORE_PASSWORD / KEY_PASSWORD env vars)
gradle :app:assembleRelease
```
Install on a connected Android device:
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
