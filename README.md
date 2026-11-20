# SAATHI — Your Friend. Your World.

**Created by Usman**

SAATHI is a standalone native Android AI Voice Companion built with Kotlin, Jetpack Compose, Material 3, Room, DataStore, Android SpeechRecognizer, and a secure HTTPS Node.js backend proxy for Gemini AI and ElevenLabs Voices.

---

## 1. Standalone Privacy-First Architecture
- **No Accounts or Login Required**: SAATHI opens directly into the companion experience (Splash -> First-Launch Onboarding -> Voice Companion) using private on-device Room & DataStore storage.
- **Zero Secrets in APK**: `GEMINI_API_KEY`, `ELEVENLABS_API_KEY`, `ELEVENLABS_MALE_VOICE_ID`, and `ELEVENLABS_FEMALE_VOICE_ID` remain strictly on the backend server (`backend/server.js`) and are never compiled into `BuildConfig`, resources, or the Android APK.
- **Local On-Device Persistence**: Conversation threads, messages, approved long-term memories, language settings (Telugu, Hindi, English), and voice preferences are stored locally in Room and DataStore. When long-term memory is enabled, approved facts are sent only as per-turn model context over HTTPS.

## 2. Secure Gemini & ElevenLabs HTTPS Backend Setup
All Gemini conversational streaming (`/v1/chat/stream`), memory extraction (`/v1/memory/extract`), ElevenLabs Text-to-Speech streaming (`/v1/tts/stream`), and Scribe Speech-to-Text (`/v1/stt`) requests route through the standalone Node.js Express backend in `/backend`:
```bash
cd backend
npm install
export GEMINI_API_KEY="your_gemini_api_key"
export ELEVENLABS_API_KEY="your_elevenlabs_api_key"
export ELEVENLABS_MALE_VOICE_ID="your_male_voice_id"
export ELEVENLABS_FEMALE_VOICE_ID="your_female_voice_id"
npm start
```
Deploy `/backend` to Cloud Run (or any HTTPS Node.js host), then open **SAATHI -> Voice Select -> SAATHI Secure AI & Voice Backend**, paste your HTTPS URL, and tap **Save & Test Connection**.

## 3. Running Tests & Building APKs
```bash
# Run backend unit tests
node --test backend/server.test.js

# Run Android unit & Robolectric tests
gradle :app:testDebugUnitTest

# Build Debug APK (no external config secrets required)
gradle :app:assembleDebug

# Build Release APK (requires KEYSTORE_PATH / STORE_PASSWORD / KEY_PASSWORD env vars)
gradle :app:assembleRelease
```
Install on a connected Android device:
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
