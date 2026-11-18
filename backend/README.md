# SAATHI Secure Voice Backend

This lightweight Express service keeps your `ELEVENLABS_API_KEY` exclusively on the backend and verifies every Android request with the signed-in user's Firebase Auth ID token.

## Key Capabilities
- **Eleven v4 Turbo (`eleven_v4_turbo`)**: Low-latency expressive speech streaming with automatic fallback (`eleven_v4` -> `eleven_flash_v2_5` -> `eleven_multilingual_v2`) if an account tier lacks a model.
- **Scribe v3 Live (`scribe_v3_live`)**: Multilingual speech recognition (`te`, `hi`, `en`) with automatic fallback (`scribe_v3` -> `scribe_v2` -> `scribe_v1`).
- **Configurable Indian Voice IDs**: Pin `ELEVENLABS_FEMALE_VOICE_ID` / `ELEVENLABS_MALE_VOICE_ID` in environment variables, let the backend auto-discover Indian voices from the ElevenLabs Voice Library, or configure Voice IDs dynamically via `POST /v1/voices/config`.
- **Consistent Voice Identity**: Male and Female voice slots preserve the exact same voice ID when switching across **Telugu (`te`)**, **Hindi (`hi`)**, and **English (`en`)**.

## Endpoints
- `GET /health` -> Server, model, and voice readiness status
- `GET /v1/voices` -> Active Female and Male voice configuration
- `GET /v1/voices/library?gender=female|male&search=...` -> Search natural Indian voices in the ElevenLabs Voice Library
- `POST /v1/voices/config` -> Update backend Female/Male Voice IDs or models
- `POST /v1/tts/stream` & `POST /v1/tts/preview` -> Stream `audio/mpeg`
- `POST /v1/stt` -> Transcribe speech audio via Scribe

## Quick Deploy (Cloud Run / Node 20+)
```bash
cd backend
npm install
export ELEVENLABS_API_KEY="your_elevenlabs_api_key"
export ELEVENLABS_FEMALE_VOICE_ID="your_indian_female_voice_id"
export ELEVENLABS_MALE_VOICE_ID="your_indian_male_voice_id"
export GOOGLE_CLOUD_PROJECT="gen-lang-client-0541897442"
npm start
```
