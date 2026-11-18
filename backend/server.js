const fs = require("node:fs");
const path = require("node:path");
const express = require("express");
const multer = require("multer");
const admin = require("firebase-admin");
const { createVoiceService } = require("./voiceService");

function loadBackendEnvFile() {
  for (const candidate of [path.join(__dirname, ".env"), path.join(__dirname, "..", ".env")]) {
    try {
      if (!fs.existsSync(candidate)) continue;
      const lines = fs.readFileSync(candidate, "utf8").split(/\r?\n/);
      for (const rawLine of lines) {
        const line = rawLine.trim();
        if (!line || line.startsWith("#")) continue;
        const eqIdx = line.indexOf("=");
        if (eqIdx <= 0) continue;
        const key = line.slice(0, eqIdx).trim();
        const val = line.slice(eqIdx + 1).trim().replace(/^['"]|['"]$/g, "");
        if (key && !(key in process.env)) {
          process.env[key] = val;
        }
      }
    } catch {
      // Ignore unreadable env file and rely on process.env
    }
  }
}

loadBackendEnvFile();

if (!admin.apps.length) {
  admin.initializeApp();
}

const app = express();
const upload = multer({
  storage: multer.memoryStorage(),
  limits: { fileSize: 15 * 1024 * 1024 },
});

app.use(express.json({ limit: "1mb" }));

const voiceService = createVoiceService();
const PORT = process.env.PORT || 8080;

async function requireFirebaseAuth(req, res, next) {
  const header = req.headers.authorization || "";
  if (!header.startsWith("Bearer ")) {
    return res.status(401).json({ error: "Missing Firebase Auth Bearer token" });
  }
  const idToken = header.slice(7).trim();
  try {
    req.user = await admin.auth().verifyIdToken(idToken);
    next();
  } catch (err) {
    return res.status(401).json({ error: "Invalid or expired Firebase Auth token" });
  }
}

app.get("/health", async (_req, res) => {
  const [config, models] = await Promise.all([
    voiceService.getConfiguredVoices(),
    voiceService.fetchAvailableModels(),
  ]);
  const femaleConfigured = Boolean(config.voices.find((v) => v.slot === "female")?.voiceId);
  const maleConfigured = Boolean(config.voices.find((v) => v.slot === "male")?.voiceId);
  res.json({
    ok: true,
    service: "saathi-voice-backend",
    elevenLabsConfigured: Boolean(voiceService.getApiKey()),
    ttsModel: config.ttsModel,
    sttModel: config.sttModel,
    elevenV4TurboAvailable: models.includes("eleven_v4_turbo") || models.length === 0,
    voicesReady: { female: femaleConfigured, male: maleConfigured },
    supportedLanguages: config.supportedLanguages,
  });
});

// Return backend-configured Male & Female Indian voice slots (no hardcoded unavailable IDs)
app.get("/v1/voices", requireFirebaseAuth, async (_req, res) => {
  const config = await voiceService.getConfiguredVoices();
  res.json(config);
});

// Search natural Indian voices directly from the ElevenLabs Voice Library & account voices
app.get("/v1/voices/library", requireFirebaseAuth, async (req, res) => {
  if (!voiceService.getApiKey()) {
    return res.status(503).json({ error: "ELEVENLABS_API_KEY is not configured on the server." });
  }
  const gender = String(req.query.gender || "").toLowerCase();
  const search = String(req.query.search || "");
  const result = await voiceService.fetchIndianVoiceLibrary({ gender, search });
  res.json(result);
});

// Update backend runtime configuration for Male/Female Voice IDs or TTS/STT models
app.post("/v1/voices/config", requireFirebaseAuth, async (req, res) => {
  const updated = await voiceService.updateVoiceConfig(req.body || {});
  res.json(updated);
});

async function handleTtsStream(req, res, isPreview = false) {
  const abortController = new AbortController();
  const onClose = () => abortController.abort();
  req.on("close", onClose);

  try {
    const {
      text,
      voiceSlot = "female",
      voiceId = "",
      modelId = "",
      languageCode = "en",
      speed = 1.0,
      expressive = true,
    } = req.body || {};

    const result = await voiceService.synthesizeSpeech({
      text,
      voiceSlot,
      voiceId,
      modelId,
      languageCode,
      speed,
      expressive,
      isPreview,
      signal: abortController.signal,
    });

    if (abortController.signal.aborted || res.writableEnded) {
      return;
    }

    if (!result.ok) {
      return res.status(result.status).json({ error: result.error });
    }

    res.setHeader("Content-Type", "audio/mpeg");
    res.setHeader("Cache-Control", "no-store");
    res.setHeader("Transfer-Encoding", "chunked");
    res.setHeader("X-Saathi-Model-Used", result.modelUsed);
    res.setHeader("X-Saathi-Voice-Id", result.voiceId);
    res.setHeader("X-Saathi-Language", result.languageCode);

    const reader = result.body.getReader();
    while (true) {
      if (abortController.signal.aborted) {
        await reader.cancel().catch(() => {});
        break;
      }
      const { done, value } = await reader.read();
      if (done) break;
      if (!res.writableEnded) {
        res.write(Buffer.from(value));
      }
    }
    if (!res.writableEnded) {
      res.end();
    }
  } catch (err) {
    if (!abortController.signal.aborted) {
      console.error("TTS proxy error:", err);
    }
    if (!res.headersSent) {
      res.status(500).json({ error: "Internal TTS proxy error" });
    } else if (!res.writableEnded) {
      res.end();
    }
  } finally {
    req.off("close", onClose);
  }
}

// Stream natural conversational speech (Eleven v4 Turbo primary with graceful fallback)
app.post("/v1/tts/stream", requireFirebaseAuth, (req, res) => handleTtsStream(req, res, false));
app.post("/v1/tts/preview", requireFirebaseAuth, (req, res) => handleTtsStream(req, res, true));

// Speech-to-text via ElevenLabs Scribe v3 Live (with graceful fallback to scribe_v3/v2/v1)
app.post("/v1/stt", requireFirebaseAuth, upload.single("audio"), async (req, res) => {
  if (!req.file) {
    return res.status(400).json({ error: "audio file is required" });
  }
  const abortController = new AbortController();
  const onClose = () => abortController.abort();
  req.on("close", onClose);

  try {
    const result = await voiceService.transcribeAudio({
      audioBuffer: req.file.buffer,
      mimetype: req.file.mimetype || "audio/wav",
      originalname: req.file.originalname || "speech.wav",
      languageCode: req.body?.languageCode,
      modelId: req.body?.modelId,
      signal: abortController.signal,
    });
    if (abortController.signal.aborted || res.writableEnded) {
      return;
    }
    if (!result.ok) {
      return res.status(result.status).json({ error: result.error });
    }
    res.json({
      text: result.text,
      modelUsed: result.modelUsed,
      languageCode: result.languageCode,
    });
  } catch (err) {
    if (!abortController.signal.aborted) {
      console.error("STT proxy error:", err);
    }
    if (!res.headersSent) {
      res.status(500).json({ error: "Internal STT proxy error" });
    }
  } finally {
    req.off("close", onClose);
  }
});

if (require.main === module) {
  app.listen(PORT, () => {
    console.log(`SAATHI Voice Backend listening on port ${PORT}`);
  });
}

module.exports = app;
