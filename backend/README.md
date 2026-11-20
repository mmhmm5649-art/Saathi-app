# SAATHI Standalone AI & Voice Backend

This lightweight Express service keeps your `GEMINI_API_KEY`, `ELEVENLABS_API_KEY`, and ElevenLabs Male/Female Voice IDs exclusively on the server — never inside the Android APK or GitHub source.

## Key Capabilities
- **Direct Gemini API Streaming (`POST /v1/chat/stream`)**: Streams natural conversational replies via Server-Sent Events (SSE) using `gemini-2.5-flash` with automatic fallback to `gemini-2.5-flash-lite` on timeout, 429, or 5xx errors, plus full cancellation propagation.
- **Structured Memory Extraction (`POST /v1/memory/extract`)**: Suggests durable personal facts for SAATHI's on-device Room memory store.
- **Eleven v4 Turbo (`eleven_v4_turbo`)**: Low-latency expressive speech streaming with automatic fallback (`eleven_v4` -> `eleven_flash_v2_5` -> `eleven_multilingual_v2`).
- **Scribe v3 Live (`scribe_v3_live`)**: Multilingual speech recognition (`te`, `hi`, `en`) with automatic fallback (`scribe_v3` -> `scribe_v2` -> `scribe_v1`).
- **Configurable Indian Voice IDs**: Pin `ELEVENLABS_FEMALE_VOICE_ID` / `ELEVENLABS_MALE_VOICE_ID` in server environment variables, auto-discover Indian voices from ElevenLabs, or update dynamically via `POST /v1/voices/config`.
- **Standalone Security**: Per-IP sliding-window rate limiting, payload size & schema validation, hardened response headers, optional `REQUIRE_HTTPS=true`, and optional shared `SAATHI_BACKEND_ACCESS_TOKEN`.

## Endpoints
- `GET /health` -> Gemini + ElevenLabs readiness, models, and voice slot status
- `POST /v1/chat/stream` -> SSE streaming Gemini conversation endpoint
- `POST /v1/memory/extract` -> Structured JSON memory suggestion endpoint
- `GET /v1/voices` -> Active Female and Male voice configuration
- `GET /v1/voices/library?gender=female|male&search=...` -> Search natural Indian voices in the ElevenLabs Voice Library
- `POST /v1/voices/config` -> Update backend Female/Male Voice IDs or models
- `POST /v1/tts/stream` & `POST /v1/tts/preview` -> Stream `audio/mpeg`
- `POST /v1/stt` -> Transcribe speech audio via Scribe

## Quick Deploy (Node 20+ / Cloud Run / Render / Fly.io)
```bash
cd backend
npm install
export GEMINI_API_KEY="your_gemini_api_key"
export ELEVENLABS_API_KEY="your_elevenlabs_api_key"
export ELEVENLABS_FEMALE_VOICE_ID="your_indian_female_voice_id"
export ELEVENLABS_MALE_VOICE_ID="your_indian_male_voice_id"
export REQUIRE_HTTPS="true"
npm start
```
