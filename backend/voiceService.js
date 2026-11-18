const fs = require("node:fs");
const path = require("node:path");

const TTS_MODEL_FALLBACKS = [
  "eleven_v4_turbo",
  "eleven_v4",
  "eleven_v3",
  "eleven_flash_v2_5",
  "eleven_turbo_v2_5",
  "eleven_multilingual_v2",
];

const STT_MODEL_FALLBACKS = [
  "scribe_v3_live",
  "scribe_v3",
  "scribe_v2",
  "scribe_v1",
];

const DEFAULT_CONFIG_PATH = path.join(__dirname, "voice-config.json");
const DEFAULT_TTS_TIMEOUT_MS = 25000;
const DEFAULT_STT_TIMEOUT_MS = 30000;
const DEFAULT_METADATA_TIMEOUT_MS = 10000;
const MAX_RETRY_ATTEMPTS = 2;

function createAbortError(message = "Request aborted") {
  const err = new Error(message);
  err.name = "AbortError";
  return err;
}

function sleep(ms, signal) {
  return new Promise((resolve, reject) => {
    if (signal?.aborted) {
      reject(createAbortError());
      return;
    }
    const timer = setTimeout(() => {
      if (signal) signal.removeEventListener("abort", onAbort);
      resolve();
    }, ms);
    function onAbort() {
      clearTimeout(timer);
      reject(createAbortError());
    }
    if (signal) {
      signal.addEventListener("abort", onAbort, { once: true });
    }
  });
}

function parseRetryAfterMs(headers, attempt) {
  const raw = headers?.get ? headers.get("retry-after") : headers?.["retry-after"];
  const seconds = Number(raw);
  if (Number.isFinite(seconds) && seconds > 0) {
    return Math.min(Math.max(seconds * 1000, 300), 5000);
  }
  return attempt === 0 ? 450 : 1100;
}

const RESERVED_VOICE_URL_SEGMENTS = new Set([
  "app",
  "voice-library",
  "voice-lab",
  "voices",
  "voice",
  "share",
  "shared",
  "v1",
  "text-to-speech",
  "stream",
  "speech-synthesis",
  "settings",
]);

const VOICE_ID_PARENT_MARKERS = new Set(["share", "voice", "voices", "text-to-speech"]);

/**
 * Extracts a clean ElevenLabs Voice ID whether stored as a raw ID or pasted as an ElevenLabs URL.
 */
function extractElevenLabsVoiceId(rawValue) {
  if (typeof rawValue !== "string") return "";
  const trimmed = rawValue.trim().replace(/^['"]|['"]$/g, "");
  if (!trimmed) return "";

  if (/^https?:\/\//i.test(trimmed) || trimmed.includes("elevenlabs.io")) {
    try {
      const normalized = /^https?:\/\//i.test(trimmed) ? trimmed : `https://${trimmed}`;
      const url = new URL(normalized);
      const queryCandidate =
        url.searchParams.get("voiceId") ||
        url.searchParams.get("voice_id") ||
        url.searchParams.get("id");
      if (queryCandidate && /^[A-Za-z0-9_-]{8,64}$/.test(queryCandidate.trim())) {
        return queryCandidate.trim();
      }
      const segments = url.pathname
        .split("/")
        .map((s) => s.trim())
        .filter(Boolean);
      for (let i = 0; i < segments.length - 1; i += 1) {
        if (VOICE_ID_PARENT_MARKERS.has(segments[i].toLowerCase())) {
          const nextSeg = segments[i + 1];
          if (!RESERVED_VOICE_URL_SEGMENTS.has(nextSeg.toLowerCase()) && /^[A-Za-z0-9_-]{8,64}$/.test(nextSeg)) {
            return nextSeg;
          }
        }
      }
      for (let i = segments.length - 1; i >= 0; i -= 1) {
        const seg = segments[i];
        if (!RESERVED_VOICE_URL_SEGMENTS.has(seg.toLowerCase()) && /^[A-Za-z0-9_-]{8,64}$/.test(seg)) {
          return seg;
        }
      }
    } catch {
      // Fall through to raw cleanup below if URL parsing fails
    }
  }

  return trimmed;
}

function createVoiceService({
  env = process.env,
  fetchImpl = globalThis.fetch,
  configPath = DEFAULT_CONFIG_PATH,
  ttsTimeoutMs = DEFAULT_TTS_TIMEOUT_MS,
  sttTimeoutMs = DEFAULT_STT_TIMEOUT_MS,
  metadataTimeoutMs = DEFAULT_METADATA_TIMEOUT_MS,
} = {}) {
  let runtimeConfig = loadRuntimeConfig(configPath);
  let cachedIndianLibrary = null;
  let cachedModels = null;

  // Coalesces/caches short preview clips briefly to prevent accidental duplicate billing
  const previewBufferCache = new Map();

  function loadRuntimeConfig(filePath) {
    try {
      if (filePath && fs.existsSync(filePath)) {
        const raw = fs.readFileSync(filePath, "utf8");
        const parsed = JSON.parse(raw);
        return typeof parsed === "object" && parsed ? parsed : {};
      }
    } catch (err) {
      console.warn("Could not read runtime voice-config.json:", err.message);
    }
    return {};
  }

  function persistRuntimeConfig(nextConfig) {
    runtimeConfig = { ...runtimeConfig, ...nextConfig };
    previewBufferCache.clear();
    if (!configPath) return;
    try {
      fs.writeFileSync(configPath, JSON.stringify(runtimeConfig, null, 2), "utf8");
    } catch (err) {
      // Cloud Run root filesystem may be read-only outside /tmp; keep in memory gracefully
      console.warn("Could not persist voice-config.json to disk (using in-memory config):", err.message);
    }
  }

  function getApiKey() {
    return (env.ELEVENLABS_API_KEY || "").trim();
  }

  function getPreferredTtsModel(requestedModel) {
    const base =
      (requestedModel || runtimeConfig.ttsModel || env.ELEVENLABS_TTS_MODEL || "eleven_v4_turbo")
        .trim();
    return [base, ...TTS_MODEL_FALLBACKS.filter((m) => m !== base)];
  }

  function getPreferredSttModel(requestedModel) {
    const base =
      (requestedModel || runtimeConfig.sttModel || env.ELEVENLABS_STT_MODEL || "scribe_v3_live")
        .trim();
    return [base, ...STT_MODEL_FALLBACKS.filter((m) => m !== base)];
  }

  function normalizeLanguageCode(languageCode) {
    const clean = String(languageCode || "en").trim().toLowerCase();
    if (clean.startsWith("te")) return "te";
    if (clean.startsWith("hi")) return "hi";
    return "en";
  }

  const ROMAN_TO_SPOKEN_TELUGU_MAP = {
    arey: "అరే",
    ayyo: "అయ్యో",
    haha: "హాహా",
    hey: "హేయ్",
    hi: "హాయ్",
    saathi: "సాథీ",
    ela: "ఎలా",
    unnav: "ఉన్నావ్",
    unnavu: "ఉన్నావు",
    unna: "ఉన్నా",
    unnanu: "ఉన్నాను",
    undi: "ఉంది",
    ra: "రా",
    baagunnava: "బాగున్నావా",
    bagunnava: "బాగున్నావా",
    baagunna: "బాగున్నా",
    bagunna: "బాగున్నా",
    baagunnanu: "బాగున్నాను",
    baagundi: "బాగుంది",
    bagundi: "బాగుంది",
    baane: "బానే",
    bane: "బానే",
    eeroju: "ఈరోజు",
    em: "ఏం",
    chestunnav: "చేస్తున్నావ్",
    chestunnavu: "చేస్తున్నావు",
    chestunna: "చేస్తున్నా",
    nenu: "నేను",
    nuvvu: "నువ్వు",
    naaku: "నాకు",
    naku: "నాకు",
    neeku: "నీకు",
    nee: "నీ",
    naa: "నా",
    mana: "మన",
    manam: "మనం",
    iddaram: "ఇద్దరం",
    konchem: "కొంచెం",
    kaburlu: "కబుర్లు",
    cheppukundama: "చెప్పుకుందామా",
    cheppu: "చెప్పు",
    cheppava: "చెప్పవా",
    maatladukundam: "మాట్లాడుకుందాం",
    vintunnanu: "వింటున్నాను",
    vintunna: "వింటున్నా",
    vinte: "వింటే",
    maata: "మాట",
    chaala: "చాలా",
    chala: "చాలా",
    santhoshanga: "సంతోషంగా",
    baadhaga: "బాధగా",
    nemmadiga: "నెమ్మదిగా",
    nijamga: "నిజంగా",
    avunaa: "అవునా",
    avunu: "అవును",
    kaadu: "కాదు",
    enduku: "ఎందుకు",
    adaithe: "అదైతే",
    aithe: "అయితే",
    bhale: "భలే",
    ayindi: "అయింది",
    mari: "మరి",
    bore: "బోర్",
    koduthundi: "కొడుతుంది",
    nidra: "నిద్ర",
    vastundi: "వస్తుంది",
    phone: "ఫోన్",
    pakkana: "పక్కన",
    petti: "పెట్టి",
    rest: "రెస్ట్",
    teesuko: "తీసుకో",
    repu: "రేపు",
    malli: "మళ్ళీ",
    saayantram: "సాయంత్రం",
    chai: "చాయ్",
    thaaguthu: "తాగుతూ",
    inka: "ఇంకా",
    sare: "సరే",
    manchidi: "మంచిది",
  };

  /**
   * Preserves natural pauses (commas, ellipses, periods, question/exclamation marks)
   * while stripping markdown artifacts, stage directions ([laughs]), or awkward emoji glyphs
   * that cause unnatural pronunciation in Telugu (Roman or script), Hindi, or English TTS.
   * When languageCode === "te", converts Roman Telugu words into phonetic Telugu script so
   * ElevenLabs speaks natural Telugu pronunciation instead of reading Roman Telugu as English.
   */
  function normalizeSpeechText(text, languageCode = "en") {
    let cleaned = String(text || "")
      .replace(/```[\s\S]*?```/g, " ")
      .replace(/\[(?:laughs?|chuckles?|sighs?|smiles?|giggles?|pause)\]/gi, " ")
      .replace(/[*_`~#>]+/g, "")
      .replace(/\[([^\]]+)\]\([^)]+\)/g, "$1")
      // Remove emoji / pictograph symbols so Telugu/Hindi/English TTS never spells them out
      .replace(/[\u{1F000}-\u{1FAFF}\u{2600}-\u{27BF}\u{FE00}-\u{FE0F}]/gu, "")
      .replace(/\s*\.\.\.\s*/g, "... ")
      .replace(/\s*—\s*/g, ", ")
      .replace(/\s+/g, " ")
      .trim();

    if (normalizeLanguageCode(languageCode) === "te") {
      cleaned = cleaned.replace(/[A-Za-z]+/g, (word) => {
        const mapped = ROMAN_TO_SPOKEN_TELUGU_MAP[word.toLowerCase()];
        return mapped || word;
      });
      // Ensure clean spacing after punctuation for natural breath pauses in Telugu speech
      cleaned = cleaned.replace(/([,;?!।])\s*/g, "$1 ").replace(/\s+/g, " ").trim();
    }
    return cleaned.slice(0, 2500);
  }

  async function fetchWithTimeoutAndRetry(url, init, { timeoutMs, signal, maxRetries = MAX_RETRY_ATTEMPTS } = {}) {
    let attempt = 0;
    while (true) {
      if (signal?.aborted) {
        throw createAbortError();
      }
      const attemptController = new AbortController();
      let timedOut = false;
      const timer = setTimeout(() => {
        timedOut = true;
        attemptController.abort();
      }, timeoutMs || DEFAULT_TTS_TIMEOUT_MS);

      const onExternalAbort = () => attemptController.abort();
      if (signal) {
        signal.addEventListener("abort", onExternalAbort, { once: true });
      }

      try {
        const res = await fetchImpl(url, {
          ...init,
          signal: attemptController.signal,
        });
        clearTimeout(timer);
        if (signal) signal.removeEventListener("abort", onExternalAbort);

        const isTransientStatus = res.status === 429 || res.status === 500 || res.status === 502 || res.status === 503 || res.status === 504;
        if (isTransientStatus && attempt < maxRetries && !signal?.aborted) {
          // Drain body carefully before retrying
          try { await res.text(); } catch (_) {}
          const delayMs = parseRetryAfterMs(res.headers, attempt);
          attempt += 1;
          await sleep(delayMs, signal);
          continue;
        }
        return res;
      } catch (err) {
        clearTimeout(timer);
        if (signal) signal.removeEventListener("abort", onExternalAbort);

        if (signal?.aborted) {
          throw createAbortError();
        }
        if (attempt < maxRetries) {
          const delayMs = attempt === 0 ? 400 : 1000;
          attempt += 1;
          await sleep(delayMs, signal);
          continue;
        }
        if (timedOut) {
          const timeoutErr = new Error("ElevenLabs upstream request timed out.");
          timeoutErr.code = "UPSTREAM_TIMEOUT";
          throw timeoutErr;
        }
        throw err;
      }
    }
  }

  function scoreIndianVoiceCandidate(voice, targetGender) {
    const labels = voice.labels || {};
    const textBlob = [
      voice.name,
      voice.description,
      labels.accent,
      labels.language,
      labels.gender,
      labels.descriptive,
      labels.use_case,
    ]
      .filter(Boolean)
      .join(" ")
      .toLowerCase();

    const genderLabel = String(labels.gender || "").toLowerCase();
    if (targetGender === "female" && (genderLabel === "male" || (/\bmale\b/.test(textBlob) && !/\bfemale\b/.test(textBlob)))) {
      return -100;
    }
    if (targetGender === "male" && (genderLabel === "female" || /\bfemale\b/.test(textBlob))) {
      return -100;
    }

    let score = 0;
    if (genderLabel === targetGender) score += 40;
    if (textBlob.includes("indian") || textBlob.includes("india") || textBlob.includes("desi")) score += 50;
    if (textBlob.includes("telugu") || textBlob.includes("hindi") || textBlob.includes("hyderabad")) score += 35;
    if (textBlob.includes("conversational") || textBlob.includes("friendly") || textBlob.includes("warm") || textBlob.includes("natural")) score += 20;
    if (voice.category === "cloned" || voice.category === "professional" || voice.category === "shared") score += 10;
    return score;
  }

  async function fetchIndianVoiceLibrary({ gender = "", search = "" } = {}) {
    const apiKey = getApiKey();
    if (!apiKey) {
      return { voices: [], source: "unconfigured" };
    }

    const candidates = [];
    const seenIds = new Set();

    // 1. Account's personal/saved voices first
    try {
      const accountRes = await fetchWithTimeoutAndRetry(
        "https://api.elevenlabs.io/v1/voices",
        {
          method: "GET",
          headers: { "xi-api-key": apiKey },
        },
        { timeoutMs: metadataTimeoutMs, maxRetries: 1 }
      );
      if (accountRes.ok) {
        const data = await accountRes.json();
        for (const v of data.voices || []) {
          if (v.voice_id && !seenIds.has(v.voice_id)) {
            seenIds.add(v.voice_id);
            candidates.push({
              voiceId: v.voice_id,
              name: v.name || "Custom Voice",
              gender: String(v.labels?.gender || "").toLowerCase() || "unknown",
              accent: v.labels?.accent || "",
              language: v.labels?.language || "Multilingual",
              tone: v.labels?.descriptive || v.labels?.use_case || "Conversational",
              description: v.description || "Saved in your ElevenLabs voice list",
              previewUrl: v.preview_url || "",
              source: "account_voices",
              raw: v,
            });
          }
        }
      }
    } catch (err) {
      console.warn("Account /v1/voices lookup warning:", err.message);
    }

    // 2. ElevenLabs Voice Library (shared-voices filtered for Indian accent)
    try {
      const params = new URLSearchParams({
        page_size: "30",
        accent: "indian",
      });
      if (gender === "female" || gender === "male") {
        params.set("gender", gender);
      }
      if (search && search.trim()) {
        params.set("search", search.trim());
      }
      const sharedRes = await fetchWithTimeoutAndRetry(
        `https://api.elevenlabs.io/v1/shared-voices?${params.toString()}`,
        {
          method: "GET",
          headers: { "xi-api-key": apiKey },
        },
        { timeoutMs: metadataTimeoutMs, maxRetries: 1 }
      );
      if (sharedRes.ok) {
        const data = await sharedRes.json();
        for (const v of data.voices || []) {
          const id = v.voice_id;
          if (id && !seenIds.has(id)) {
            seenIds.add(id);
            candidates.push({
              voiceId: id,
              name: v.name || "Indian Library Voice",
              gender: String(v.gender || v.labels?.gender || "").toLowerCase() || "unknown",
              accent: v.accent || v.labels?.accent || "Indian",
              language: v.language || "Multilingual (en, hi, te)",
              tone: v.descriptive || v.use_case || "Natural & Conversational",
              description: v.description || "Natural Indian voice from ElevenLabs Voice Library",
              previewUrl: v.preview_url || "",
              source: "voice_library",
              raw: {
                name: v.name,
                description: v.description,
                category: v.category || "shared",
                labels: {
                  accent: v.accent || "indian",
                  gender: v.gender,
                  language: v.language,
                  descriptive: v.descriptive,
                  use_case: v.use_case,
                },
              },
            });
          }
        }
      }
    } catch (err) {
      console.warn("ElevenLabs /v1/shared-voices lookup warning:", err.message);
    }

    const filtered = candidates
      .filter((c) => {
        if (gender && c.gender !== "unknown" && c.gender !== gender.toLowerCase()) return false;
        if (search && search.trim()) {
          const q = search.trim().toLowerCase();
          return (
            c.name.toLowerCase().includes(q) ||
            c.description.toLowerCase().includes(q) ||
            c.accent.toLowerCase().includes(q)
          );
        }
        return true;
      })
      .map(({ raw, ...rest }) => ({
        ...rest,
        _score: scoreIndianVoiceCandidate(raw, gender || rest.gender),
      }))
      .sort((a, b) => b._score - a._score)
      .map(({ _score, ...clean }) => clean);

    return { voices: filtered, source: "elevenlabs_library" };
  }

  function getEnvVoiceId(isMale) {
    const raw = isMale
      ? (env.ELEVENLABS_MALE_VOICE_ID || env.ELEVENLABS_VOICE_ID_MALE || env.MALE_VOICE_ID || "")
      : (env.ELEVENLABS_FEMALE_VOICE_ID || env.ELEVENLABS_VOICE_ID_FEMALE || env.FEMALE_VOICE_ID || "");
    return extractElevenLabsVoiceId(raw);
  }

  async function resolveSlotVoice(slot) {
    const normalizedSlot = String(slot || "female").toLowerCase() === "male" ? "male" : "female";
    const isMale = normalizedSlot === "male";

    const runtimeId = extractElevenLabsVoiceId(isMale ? runtimeConfig.maleVoiceId : runtimeConfig.femaleVoiceId);
    const runtimeName = isMale ? runtimeConfig.maleVoiceName : runtimeConfig.femaleVoiceName;
    const envId = getEnvVoiceId(isMale);
    const envName = isMale ? env.ELEVENLABS_MALE_VOICE_NAME : env.ELEVENLABS_FEMALE_VOICE_NAME;

    if (runtimeId) {
      return {
        slot: normalizedSlot,
        gender: normalizedSlot.toUpperCase(),
        voiceId: runtimeId,
        name: (runtimeName || (isMale ? "SAATHI Male (Indian)" : "SAATHI Female (Indian)")).trim(),
        accent: "Indian",
        tone: isMale ? "Warm, Grounded & Friendly" : "Warm, Expressive & Empathetic",
        description: "Configured in backend runtime settings — consistent across Telugu, Hindi & English.",
        modelId: getPreferredTtsModel()[0],
        configured: true,
        source: "backend_settings",
      };
    }

    if (envId) {
      return {
        slot: normalizedSlot,
        gender: normalizedSlot.toUpperCase(),
        voiceId: envId,
        name: (envName || (isMale ? "SAATHI Male (Indian)" : "SAATHI Female (Indian)")).trim(),
        accent: "Indian",
        tone: isMale ? "Warm, Grounded & Friendly" : "Warm, Expressive & Empathetic",
        description: "Configured via backend environment settings — consistent across Telugu, Hindi & English.",
        modelId: getPreferredTtsModel()[0],
        configured: true,
        source: "env",
      };
    }

    // Auto-discover natural Indian voice from ElevenLabs Voice Library if API key is present
    if (getApiKey()) {
      if (!cachedIndianLibrary || Date.now() - cachedIndianLibrary.fetchedAt > 5 * 60 * 1000) {
        const discovered = await fetchIndianVoiceLibrary();
        cachedIndianLibrary = { fetchedAt: Date.now(), voices: discovered.voices };
      }
      const match =
        cachedIndianLibrary.voices.find(
          (v) => v.gender === normalizedSlot && v.accent.toLowerCase().includes("indian")
        ) || cachedIndianLibrary.voices.find((v) => v.gender === normalizedSlot);

      if (match) {
        return {
          slot: normalizedSlot,
          gender: normalizedSlot.toUpperCase(),
          voiceId: match.voiceId,
          name: match.name,
          accent: match.accent || "Indian",
          tone: match.tone || "Natural Indian Companion",
          description: match.description,
          modelId: getPreferredTtsModel()[0],
          configured: true,
          source: "voice_library",
        };
      }
    }

    return {
      slot: normalizedSlot,
      gender: normalizedSlot.toUpperCase(),
      voiceId: "",
      name: isMale ? "Indian Male Companion" : "Indian Female Companion",
      accent: "Indian",
      tone: isMale ? "Warm & Grounded" : "Warm & Expressive",
      description:
        "No voice ID pinned yet. Set ELEVENLABS_MALE_VOICE_ID / ELEVENLABS_FEMALE_VOICE_ID or choose from Voice Library.",
      modelId: getPreferredTtsModel()[0],
      configured: false,
      source: "unconfigured",
    };
  }

  async function getConfiguredVoices() {
    const [female, male] = await Promise.all([
      resolveSlotVoice("female"),
      resolveSlotVoice("male"),
    ]);
    return {
      ttsModel: getPreferredTtsModel()[0],
      ttsFallbacks: getPreferredTtsModel(),
      sttModel: getPreferredSttModel()[0],
      sttFallbacks: getPreferredSttModel(),
      supportedLanguages: ["te", "hi", "en"],
      voices: [female, male],
    };
  }

  function updateVoiceConfig({
    femaleVoiceId,
    femaleVoiceName,
    maleVoiceId,
    maleVoiceName,
    ttsModel,
    sttModel,
  }) {
    const patch = {};
    if (typeof femaleVoiceId === "string" && femaleVoiceId.trim()) {
      patch.femaleVoiceId = extractElevenLabsVoiceId(femaleVoiceId);
    }
    if (typeof femaleVoiceName === "string" && femaleVoiceName.trim()) {
      patch.femaleVoiceName = femaleVoiceName.trim();
    }
    if (typeof maleVoiceId === "string" && maleVoiceId.trim()) {
      patch.maleVoiceId = extractElevenLabsVoiceId(maleVoiceId);
    }
    if (typeof maleVoiceName === "string" && maleVoiceName.trim()) {
      patch.maleVoiceName = maleVoiceName.trim();
    }
    if (typeof ttsModel === "string" && ttsModel.trim()) {
      patch.ttsModel = ttsModel.trim();
    }
    if (typeof sttModel === "string" && sttModel.trim()) {
      patch.sttModel = sttModel.trim();
    }
    persistRuntimeConfig(patch);
    return getConfiguredVoices();
  }

  async function fetchAvailableModels() {
    const apiKey = getApiKey();
    if (!apiKey) return [];
    if (cachedModels && Date.now() - cachedModels.fetchedAt < 5 * 60 * 1000) {
      return cachedModels.ids;
    }
    try {
      const res = await fetchWithTimeoutAndRetry(
        "https://api.elevenlabs.io/v1/models",
        {
          method: "GET",
          headers: { "xi-api-key": apiKey },
        },
        { timeoutMs: metadataTimeoutMs, maxRetries: 1 }
      );
      if (!res.ok) return [];
      const data = await res.json();
      const ids = Array.isArray(data) ? data.map((m) => m.model_id).filter(Boolean) : [];
      cachedModels = { fetchedAt: Date.now(), ids };
      return ids;
    } catch {
      return [];
    }
  }

  function shouldRetryWithFallbackModel(status, errBody) {
    if (![400, 403, 404, 422].includes(status)) return false;
    const text = String(errBody || "").toLowerCase();
    return (
      text.includes("model") ||
      text.includes("not found") ||
      text.includes("not available") ||
      text.includes("unsupported") ||
      text.includes("language_code") ||
      text.includes("speed") ||
      text.includes("invalid")
    );
  }

  function mapUpstreamError(status, rawBody) {
    if (status === 401) {
      return { status: 401, error: "Invalid ElevenLabs API key configured on the backend." };
    }
    if (status === 429) {
      return { status: 429, error: "ElevenLabs rate limit reached right now. Please try again in a moment." };
    }
    return { status: status || 502, error: rawBody || "ElevenLabs TTS failed" };
  }

  async function synthesizeSpeech({
    text,
    voiceSlot,
    voiceId,
    modelId,
    languageCode = "en",
    speed = 1.0,
    expressive = true,
    isPreview = false,
    signal,
  }) {
    const apiKey = getApiKey();
    if (!apiKey) {
      return {
        ok: false,
        status: 503,
        error: "ELEVENLABS_API_KEY is not configured on the backend server.",
      };
    }

    const lang = normalizeLanguageCode(languageCode);
    const cleanedText = normalizeSpeechText(text, lang);
    if (!cleanedText) {
      return { ok: false, status: 400, error: "text is required" };
    }

    let resolvedVoiceId = typeof voiceId === "string" ? extractElevenLabsVoiceId(voiceId) : "";
    let resolvedSlot = voiceSlot || "female";
    if (!resolvedVoiceId || resolvedVoiceId === "female" || resolvedVoiceId === "male") {
      resolvedSlot = resolvedVoiceId === "male" || resolvedVoiceId === "female" ? resolvedVoiceId : resolvedSlot;
      const slotProfile = await resolveSlotVoice(resolvedSlot);
      resolvedVoiceId = slotProfile.voiceId;
    }

    if (!resolvedVoiceId) {
      return {
        ok: false,
        status: 422,
        error: `No ElevenLabs Voice ID configured for '${resolvedSlot}'. Configure ELEVENLABS_${resolvedSlot.toUpperCase()}_VOICE_ID or choose an Indian voice in Voice Settings.`,
      };
    }

    const clampedSpeed = Math.min(1.2, Math.max(0.75, Number(speed) || 1.0));
    const cacheKey = isPreview
      ? `${resolvedVoiceId}:${lang}:${clampedSpeed.toFixed(2)}:${cleanedText}`
      : null;

    if (cacheKey && previewBufferCache.has(cacheKey)) {
      const entry = previewBufferCache.get(cacheKey);
      if (Date.now() - entry.createdAt < 60_000) {
        return {
          ok: true,
          status: 200,
          voiceId: resolvedVoiceId,
          modelUsed: entry.modelUsed,
          languageCode: lang,
          body: new ReadableStream({
            start(controller) {
              controller.enqueue(entry.bytes);
              controller.close();
            },
          }),
        };
      }
      previewBufferCache.delete(cacheKey);
    }

    const modelChain = getPreferredTtsModel(modelId);
    let lastStatus = 502;
    let lastError = "ElevenLabs TTS failed";

    for (const candidateModel of modelChain) {
      const voiceSettings = {
        stability: expressive ? 0.42 : 0.55,
        similarity_boost: 0.78,
        style: expressive ? 0.30 : 0.15,
        use_speaker_boost: true,
      };
      if (candidateModel !== "eleven_multilingual_v2") {
        voiceSettings.speed = clampedSpeed;
      }

      const payload = {
        text: cleanedText,
        model_id: candidateModel,
        voice_settings: voiceSettings,
      };

      // Pass language_code for models supporting explicit language enforcement (v4 / v3 / flash_v2_5 / turbo_v2_5)
      if (candidateModel !== "eleven_multilingual_v2") {
        payload.language_code = lang;
      }

      try {
        const upstream = await fetchWithTimeoutAndRetry(
          `https://api.elevenlabs.io/v1/text-to-speech/${encodeURIComponent(resolvedVoiceId)}/stream?optimize_streaming_latency=3`,
          {
            method: "POST",
            headers: {
              "xi-api-key": apiKey,
              "Content-Type": "application/json",
              Accept: "audio/mpeg",
              Connection: "keep-alive",
            },
            body: JSON.stringify(payload),
          },
          { timeoutMs: ttsTimeoutMs, signal }
        );

        if (upstream.ok && upstream.body) {
          if (cacheKey) {
            const [clientStream, cacheStream] = upstream.body.tee();
            // Cache preview bytes asynchronously without blocking live stream delivery
            new Response(cacheStream)
              .arrayBuffer()
              .then((buf) => {
                const bytes = new Uint8Array(buf);
                if (bytes.length > 0 && bytes.length <= 512 * 1024) {
                  if (previewBufferCache.size >= 10) {
                    const oldestKey = previewBufferCache.keys().next().value;
                    previewBufferCache.delete(oldestKey);
                  }
                  previewBufferCache.set(cacheKey, {
                    bytes,
                    modelUsed: candidateModel,
                    createdAt: Date.now(),
                  });
                }
              })
              .catch(() => {});
            return {
              ok: true,
              status: 200,
              voiceId: resolvedVoiceId,
              modelUsed: candidateModel,
              languageCode: lang,
              body: clientStream,
            };
          }

          return {
            ok: true,
            status: 200,
            voiceId: resolvedVoiceId,
            modelUsed: candidateModel,
            languageCode: lang,
            body: upstream.body,
          };
        }

        lastStatus = upstream.status || 502;
        lastError = await upstream.text().catch(() => "");
        if (!shouldRetryWithFallbackModel(lastStatus, lastError)) {
          const mapped = mapUpstreamError(lastStatus, lastError);
          lastStatus = mapped.status;
          lastError = mapped.error;
          break;
        }
      } catch (err) {
        if (err.name === "AbortError" || signal?.aborted) {
          return { ok: false, status: 499, error: "TTS request cancelled." };
        }
        if (err.code === "UPSTREAM_TIMEOUT") {
          return { ok: false, status: 504, error: "ElevenLabs voice generation timed out. Please try again." };
        }
        return { ok: false, status: 502, error: err.message || "Failed to reach ElevenLabs API." };
      }
    }

    return {
      ok: false,
      status: lastStatus,
      error: lastError || "ElevenLabs TTS failed",
    };
  }

  async function transcribeAudio({ audioBuffer, mimetype = "audio/wav", originalname = "speech.wav", languageCode, modelId, signal }) {
    const apiKey = getApiKey();
    if (!apiKey) {
      return {
        ok: false,
        status: 503,
        error: "ELEVENLABS_API_KEY is not configured on the backend server.",
      };
    }
    if (!audioBuffer || audioBuffer.length === 0) {
      return { ok: false, status: 400, error: "audio file is required" };
    }

    const lang = languageCode ? normalizeLanguageCode(languageCode) : "";
    const modelChain = getPreferredSttModel(modelId);
    let lastStatus = 502;
    let lastError = "ElevenLabs Scribe failed";

    for (const candidateModel of modelChain) {
      const form = new FormData();
      form.append("model_id", candidateModel);
      if (lang) {
        form.append("language_code", lang);
      }
      form.append("file", new Blob([audioBuffer], { type: mimetype }), originalname);

      try {
        const upstream = await fetchWithTimeoutAndRetry(
          "https://api.elevenlabs.io/v1/speech-to-text",
          {
            method: "POST",
            headers: {
              "xi-api-key": apiKey,
            },
            body: form,
          },
          { timeoutMs: sttTimeoutMs, signal }
        );

        const data = await upstream.json().catch(() => ({}));
        if (upstream.ok) {
          return {
            ok: true,
            status: 200,
            text: data.text || "",
            modelUsed: candidateModel,
            languageCode: data.language_code || lang,
          };
        }

        lastStatus = upstream.status || 502;
        lastError = data?.detail?.message || data?.error || JSON.stringify(data);
        if (!shouldRetryWithFallbackModel(lastStatus, lastError)) {
          break;
        }
      } catch (err) {
        if (err.name === "AbortError" || signal?.aborted) {
          return { ok: false, status: 499, error: "STT request cancelled." };
        }
        return { ok: false, status: 504, error: err.message || "ElevenLabs Scribe timed out." };
      }
    }

    return {
      ok: false,
      status: lastStatus,
      error: lastError || "ElevenLabs Scribe failed",
    };
  }

  return {
    getApiKey,
    normalizeLanguageCode,
    normalizeSpeechText,
    getPreferredTtsModel,
    getPreferredSttModel,
    fetchAvailableModels,
    fetchIndianVoiceLibrary,
    getConfiguredVoices,
    updateVoiceConfig,
    synthesizeSpeech,
    transcribeAudio,
  };
}

module.exports = {
  createVoiceService,
  extractElevenLabsVoiceId,
  TTS_MODEL_FALLBACKS,
  STT_MODEL_FALLBACKS,
};
