const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { createVoiceService, extractElevenLabsVoiceId } = require("./voiceService");

function makeTempConfigPath() {
  return path.join(os.tmpdir(), `saathi-voice-test-${Date.now()}-${Math.random()}.json`);
}

test("voice IDs are configurable via backend settings without hardcoding unavailable IDs", async () => {
  const cfgPath = makeTempConfigPath();
  const service = createVoiceService({
    env: { ELEVENLABS_API_KEY: "test_secret_key" },
    configPath: cfgPath,
    fetchImpl: async () => new Response(JSON.stringify({ voices: [] }), { status: 200 }),
  });

  const initial = await service.getConfiguredVoices();
  assert.equal(initial.voices.length, 2);
  assert.equal(initial.voices[0].configured, false);
  assert.equal(initial.voices[1].configured, false);
  assert.equal(initial.ttsModel, "eleven_v4_turbo");
  assert.equal(initial.sttModel, "scribe_v3_live");

  const updated = await service.updateVoiceConfig({
    femaleVoiceId: "lib_indian_female_01",
    femaleVoiceName: "Ananya (Hyderabad)",
    maleVoiceId: "lib_indian_male_01",
    maleVoiceName: "Arjun (Mumbai)",
  });

  assert.equal(updated.voices.find((v) => v.slot === "female").voiceId, "lib_indian_female_01");
  assert.equal(updated.voices.find((v) => v.slot === "male").voiceId, "lib_indian_male_01");

  if (fs.existsSync(cfgPath)) fs.unlinkSync(cfgPath);
});

test("auto-discovers natural Indian Male and Female voices from ElevenLabs Voice Library", async () => {
  const cfgPath = makeTempConfigPath();
  const service = createVoiceService({
    env: { ELEVENLABS_API_KEY: "test_secret_key" },
    configPath: cfgPath,
    fetchImpl: async (url) => {
      if (url.includes("/v1/voices")) {
        return new Response(JSON.stringify({ voices: [] }), { status: 200 });
      }
      if (url.includes("/v1/shared-voices")) {
        return new Response(
          JSON.stringify({
            voices: [
              {
                voice_id: "indian_f_voice_99",
                name: "Kavya - Warm Conversational",
                gender: "female",
                accent: "indian",
                language: "en, hi, te",
                descriptive: "warm, conversational",
                description: "Natural Telugu and Hindi fluent Indian female companion voice.",
              },
              {
                voice_id: "indian_m_voice_88",
                name: "Rohan - Friendly Indian",
                gender: "male",
                accent: "indian",
                language: "en, hi, te",
                descriptive: "calm, friendly",
                description: "Natural Indian male voice for daily conversations.",
              },
            ],
          }),
          { status: 200 }
        );
      }
      return new Response("{}", { status: 404 });
    },
  });

  const config = await service.getConfiguredVoices();
  const female = config.voices.find((v) => v.slot === "female");
  const male = config.voices.find((v) => v.slot === "male");
  assert.equal(female.voiceId, "indian_f_voice_99");
  assert.equal(female.source, "voice_library");
  assert.equal(male.voiceId, "indian_m_voice_88");
  assert.equal(male.source, "voice_library");

  if (fs.existsSync(cfgPath)) fs.unlinkSync(cfgPath);
});

test("sample conversations in Telugu (te), Hindi (hi), and English (en) preserve voice identity and use Eleven v4 Turbo", async () => {
  const cfgPath = makeTempConfigPath();
  const recordedCalls = [];

  const service = createVoiceService({
    env: {
      ELEVENLABS_API_KEY: "test_secret_key",
      ELEVENLABS_FEMALE_VOICE_ID: "indian_female_voice_id",
      ELEVENLABS_MALE_VOICE_ID: "indian_male_voice_id",
    },
    configPath: cfgPath,
    fetchImpl: async (url, init) => {
      recordedCalls.push({ url, body: JSON.parse(init.body) });
      const audioChunk = new Uint8Array([0x49, 0x44, 0x33, 0x01]);
      return new Response(new ReadableStream({
        start(controller) {
          controller.enqueue(audioChunk);
          controller.close();
        },
      }), { status: 200 });
    },
  });

  const sampleTurns = [
    {
      lang: "te",
      text: "హేయ్! బాగున్నా... నువ్వెలా ఉన్నావ్? ఈరోజు ఏం చేస్తున్నావ్? 😊",
    },
    {
      lang: "hi",
      text: "अरे! मैं बढ़िया हूँ, तुम बताओ — आज का दिन कैसा जा रहा है?",
    },
    {
      lang: "en",
      text: "Hey! I'm doing great... *Tell me*, how's your day going?",
    },
  ];

  // Keep the exact same selected voice slot ("female") across all 3 languages
  for (const turn of sampleTurns) {
    const res = await service.synthesizeSpeech({
      text: turn.text,
      voiceSlot: "female",
      languageCode: turn.lang,
      speed: 1.0,
      expressive: true,
    });
    assert.equal(res.ok, true);
    assert.equal(res.modelUsed, "eleven_v4_turbo");
    assert.equal(res.voiceId, "indian_female_voice_id");
    assert.equal(res.languageCode, turn.lang);
  }

  assert.equal(recordedCalls.length, 3);
  assert.equal(recordedCalls[0].body.language_code, "te");
  assert.ok(!recordedCalls[0].body.text.includes("😊"), "Emojis stripped for natural Telugu pronunciation");
  assert.ok(recordedCalls[0].body.text.includes("..."), "Natural pause ellipsis preserved");
  assert.equal(recordedCalls[1].body.language_code, "hi");
  assert.equal(recordedCalls[2].body.language_code, "en");
  assert.ok(!recordedCalls[2].body.text.includes("*"), "Markdown stripped before TTS");

  // Verify Roman Telugu text from the Android UI is converted to natural Telugu phonetic script for ElevenLabs TTS
  const romanTeRes = await service.synthesizeSpeech({
    text: "Arey, ela unnav ra? Nenu baane unna ra 😂",
    voiceSlot: "female",
    languageCode: "te",
    speed: 1.0,
    expressive: true,
  });
  assert.equal(romanTeRes.ok, true);
  assert.equal(recordedCalls[3].body.language_code, "te");
  assert.ok(recordedCalls[3].body.text.includes("అరే"), "Roman Telugu 'Arey' converted to Telugu script for natural pronunciation");
  assert.ok(recordedCalls[3].body.text.includes("ఎలా ఉన్నావ్ రా"), "Roman Telugu 'ela unnav ra' converted to Telugu phonetic script");
  assert.ok(recordedCalls[3].body.text.includes("నేను బానే ఉన్నా రా"), "Roman Telugu 'Nenu baane unna ra' converted to Telugu phonetic script");

  if (fs.existsSync(cfgPath)) fs.unlinkSync(cfgPath);
});

test("falls back gracefully if eleven_v4_turbo or scribe_v3_live is unavailable on account tier", async () => {
  const cfgPath = makeTempConfigPath();
  const ttsModelsAttempted = [];
  const sttModelsAttempted = [];

  const service = createVoiceService({
    env: {
      ELEVENLABS_API_KEY: "test_secret_key",
      ELEVENLABS_MALE_VOICE_ID: "indian_male_voice_id",
    },
    configPath: cfgPath,
    fetchImpl: async (url, init) => {
      if (url.includes("/text-to-speech/")) {
        const body = JSON.parse(init.body);
        ttsModelsAttempted.push(body.model_id);
        if (body.model_id === "eleven_v4_turbo") {
          return new Response(JSON.stringify({ detail: { message: "model not available on plan" } }), { status: 403 });
        }
        return new Response(new ReadableStream({
          start(controller) {
            controller.enqueue(new Uint8Array([1, 2, 3]));
            controller.close();
          },
        }), { status: 200 });
      }
      if (url.includes("/speech-to-text")) {
        const model = init.body.get("model_id");
        sttModelsAttempted.push(model);
        if (model === "scribe_v3_live") {
          return new Response(JSON.stringify({ detail: { message: "model_id scribe_v3_live not available" } }), { status: 400 });
        }
        return new Response(JSON.stringify({ text: "హాయ్ సాథీ", language_code: "te" }), { status: 200 });
      }
      return new Response("{}", { status: 404 });
    },
  });

  const ttsRes = await service.synthesizeSpeech({
    text: "Hey there, good to hear from you!",
    voiceSlot: "male",
    languageCode: "en",
  });
  assert.equal(ttsRes.ok, true);
  assert.deepEqual(ttsModelsAttempted, ["eleven_v4_turbo", "eleven_v4"]);
  assert.equal(ttsRes.modelUsed, "eleven_v4");

  const sttRes = await service.transcribeAudio({
    audioBuffer: Buffer.from("RIFFDATA"),
    languageCode: "te",
  });
  assert.equal(sttRes.ok, true);
  assert.deepEqual(sttModelsAttempted, ["scribe_v3_live", "scribe_v3"]);
  assert.equal(sttRes.text, "హాయ్ సాథీ");
  assert.equal(sttRes.modelUsed, "scribe_v3");

  if (fs.existsSync(cfgPath)) fs.unlinkSync(cfgPath);
});

test("retries HTTP 429 rate limits with Retry-After and coalesces duplicate preview calls", async () => {
  const cfgPath = makeTempConfigPath();
  let upstreamCalls = 0;

  const service = createVoiceService({
    env: {
      ELEVENLABS_API_KEY: "test_secret_key",
      ELEVENLABS_FEMALE_VOICE_ID: "indian_female_voice_id",
    },
    configPath: cfgPath,
    fetchImpl: async () => {
      upstreamCalls += 1;
      if (upstreamCalls === 1) {
        return new Response("Rate limit", { status: 429, headers: { "retry-after": "0.1" } });
      }
      const audioChunk = new Uint8Array([0x49, 0x44, 0x33, 0x09, 0x10]);
      return new Response(new ReadableStream({
        start(controller) {
          controller.enqueue(audioChunk);
          controller.close();
        },
      }), { status: 200 });
    },
  });

  const first = await service.synthesizeSpeech({
    text: "హేయ్! బాగున్నా...",
    voiceSlot: "female",
    languageCode: "te",
    isPreview: true,
  });
  assert.equal(first.ok, true);
  const firstBytes = new Uint8Array(await new Response(first.body).arrayBuffer());
  assert.equal(firstBytes.length, 5);
  assert.equal(upstreamCalls, 2);

  // Give the async cache tee a microtask turn to settle, then verify duplicate preview uses cache
  await new Promise((r) => setTimeout(r, 20));
  const second = await service.synthesizeSpeech({
    text: "హేయ్! బాగున్నా...",
    voiceSlot: "female",
    languageCode: "te",
    isPreview: true,
  });
  assert.equal(second.ok, true);
  const secondBytes = new Uint8Array(await new Response(second.body).arrayBuffer());
  assert.equal(secondBytes.length, 5);
  assert.equal(upstreamCalls, 2, "Duplicate preview should not trigger extra upstream ElevenLabs call");

  if (fs.existsSync(cfgPath)) fs.unlinkSync(cfgPath);
});

test("aborts upstream ElevenLabs call on timeout or client interruption", async () => {
  const cfgPath = makeTempConfigPath();
  const service = createVoiceService({
    env: {
      ELEVENLABS_API_KEY: "test_secret_key",
      ELEVENLABS_MALE_VOICE_ID: "indian_male_voice_id",
    },
    configPath: cfgPath,
    ttsTimeoutMs: 60,
    fetchImpl: async (_url, init) => {
      return await new Promise((_resolve, reject) => {
        init.signal?.addEventListener("abort", () => {
          const err = new Error("Aborted");
          err.name = "AbortError";
          reject(err);
        });
      });
    },
  });

  const ac = new AbortController();
  setTimeout(() => ac.abort(), 15);
  const aborted = await service.synthesizeSpeech({
    text: "Interrupt test",
    voiceSlot: "male",
    languageCode: "en",
    signal: ac.signal,
  });
  assert.equal(aborted.ok, false);
  assert.equal(aborted.status, 499);

  if (fs.existsSync(cfgPath)) fs.unlinkSync(cfgPath);
});

test("extracts ElevenLabs Voice IDs cleanly whether provided as raw IDs or URLs", async () => {
  assert.equal(
    extractElevenLabsVoiceId("https://elevenlabs.io/app/voice-library?voiceId=CustomMaleVoice123"),
    "CustomMaleVoice123"
  );
  assert.equal(
    extractElevenLabsVoiceId("https://elevenlabs.io/app/voice-lab/share/CustomFemaleVoice456/ananya-indian-voice"),
    "CustomFemaleVoice456"
  );
  assert.equal(
    extractElevenLabsVoiceId("https://elevenlabs.io/app/voice-library/voice/UploadedFemaleID99"),
    "UploadedFemaleID99"
  );
  assert.equal(
    extractElevenLabsVoiceId("https://api.elevenlabs.io/v1/voices/UploadedMaleID77"),
    "UploadedMaleID77"
  );

  const cfgPath = makeTempConfigPath();
  const service = createVoiceService({
    env: {
      ELEVENLABS_API_KEY: "test_secret_key",
      ELEVENLABS_MALE_VOICE_ID: "https://elevenlabs.io/app/voice-library/voice/UploadedMaleID77",
      ELEVENLABS_FEMALE_VOICE_ID: "https://elevenlabs.io/app/voice-library?voiceId=UploadedFemaleID99",
    },
    configPath: cfgPath,
  });

  const configured = await service.getConfiguredVoices();
  assert.equal(configured.voices.find((v) => v.slot === "male").voiceId, "UploadedMaleID77");
  assert.equal(configured.voices.find((v) => v.slot === "female").voiceId, "UploadedFemaleID99");

  if (fs.existsSync(cfgPath)) fs.unlinkSync(cfgPath);
});

if (process.env.ELEVENLABS_API_KEY) {
  test("LIVE ELEVENLABS INTEGRATION: tests BOTH configured Male and Female Voice IDs across Telugu, Hindi, and English", async () => {
    const cfgPath = makeTempConfigPath();
    const service = createVoiceService({ configPath: cfgPath });
    const config = await service.getConfiguredVoices();
    const female = config.voices.find((v) => v.slot === "female");
    const male = config.voices.find((v) => v.slot === "male");
    assert.ok(female?.voiceId, "Missing ELEVENLABS_FEMALE_VOICE_ID");
    assert.ok(male?.voiceId, "Missing ELEVENLABS_MALE_VOICE_ID");

    const samples = {
      te: "హేయ్! నేను సాథీ... నువ్వెలా ఉన్నావ్?",
      hi: "अरे! मैं बढ़िया हूँ, तुम बताओ?",
      en: "Hey there! How is your day going?",
    };

    for (const voice of [female, male]) {
      for (const [lang, text] of Object.entries(samples)) {
        const res = await service.synthesizeSpeech({
          text,
          voiceSlot: voice.slot,
          voiceId: voice.voiceId,
          languageCode: lang,
        });
        assert.equal(res.ok, true, `Failed live synthesis for ${voice.slot} (${voice.voiceId}) in ${lang}: ${res.error}`);
        const bytes = new Uint8Array(await new Response(res.body).arrayBuffer());
        assert.ok(bytes.length > 500, `Expected non-empty MPEG audio for ${voice.slot}/${lang}, got ${bytes.length} bytes`);
      }
    }

    if (fs.existsSync(cfgPath)) fs.unlinkSync(cfgPath);
  });
}

