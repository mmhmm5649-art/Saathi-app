const GEMINI_API_BASE = "https://generativelanguage.googleapis.com/v1beta/models";

const DEFAULT_PRIMARY_MODEL = "gemini-2.5-flash";
const DEFAULT_FALLBACK_MODEL = "gemini-2.5-flash-lite";

const ALLOWED_MODELS = new Set([
  "gemini-2.5-flash",
  "gemini-2.5-flash-lite",
  "gemini-2.5-pro",
  "gemini-2.0-flash",
  "gemini-2.0-flash-lite",
]);

function sleep(ms, signal) {
  return new Promise((resolve, reject) => {
    if (signal?.aborted) {
      const err = new Error("Aborted");
      err.name = "AbortError";
      return reject(err);
    }
    const timer = setTimeout(() => {
      signal?.removeEventListener("abort", onAbort);
      resolve();
    }, ms);
    const onAbort = () => {
      clearTimeout(timer);
      const err = new Error("Aborted");
      err.name = "AbortError";
      reject(err);
    };
    signal?.addEventListener("abort", onAbort, { once: true });
  });
}

function createAttemptSignal(parentSignal, timeoutMs) {
  const controller = new AbortController();
  let timedOut = false;

  const timer = setTimeout(() => {
    timedOut = true;
    controller.abort(new Error("Attempt timed out"));
  }, timeoutMs);

  const onParentAbort = () => {
    clearTimeout(timer);
    controller.abort(parentSignal.reason || new Error("Client aborted"));
  };

  if (parentSignal) {
    if (parentSignal.aborted) {
      clearTimeout(timer);
      controller.abort(parentSignal.reason || new Error("Client aborted"));
    } else {
      parentSignal.addEventListener("abort", onParentAbort, { once: true });
    }
  }

  return {
    signal: controller.signal,
    didTimeout: () => timedOut,
    cleanup: () => {
      clearTimeout(timer);
      if (parentSignal) {
        parentSignal.removeEventListener("abort", onParentAbort);
      }
    },
  };
}

function parseRetryAfterMs(headerVal, attempt) {
  const parsed = Number.parseFloat(String(headerVal || "").trim());
  if (Number.isFinite(parsed) && parsed > 0) {
    return Math.min(Math.max(Math.round(parsed * 1000), 250), 4000);
  }
  return attempt === 0 ? 450 : 1200;
}

function extractTextFromGeminiPayload(payload) {
  if (!payload || !Array.isArray(payload.candidates)) return "";
  let out = "";
  for (const cand of payload.candidates) {
    const parts = cand?.content?.parts;
    if (!Array.isArray(parts)) continue;
    for (const part of parts) {
      if (typeof part?.text === "string" && part.text.length > 0 && !part.thought) {
        out += part.text;
      }
    }
  }
  return out;
}

function buildGeminiContents(history, userMessage) {
  const contents = [];
  if (Array.isArray(history)) {
    for (const item of history.slice(-24)) {
      const role = item?.role === "model" ? "model" : "user";
      const text = String(item?.text || "").trim().slice(0, 1200);
      if (!text) continue;
      if (contents.length > 0 && contents[contents.length - 1].role === role) {
        contents[contents.length - 1].parts[0].text += `\n${text}`;
      } else {
        contents.push({ role, parts: [{ text }] });
      }
    }
  }

  const trimmedUser = String(userMessage || "").trim().slice(0, 4000);
  if (trimmedUser) {
    if (contents.length > 0 && contents[contents.length - 1].role === "user") {
      if (contents[contents.length - 1].parts[0].text !== trimmedUser) {
        contents[contents.length - 1].parts[0].text += `\n${trimmedUser}`;
      }
    } else {
      contents.push({ role: "user", parts: [{ text: trimmedUser }] });
    }
  }
  return contents;
}

function createGeminiService(options = {}) {
  const env = options.env || process.env;
  const fetchImpl = options.fetchImpl || globalThis.fetch;
  const apiBase = (options.apiBase || GEMINI_API_BASE).replace(/\/+$/, "");
  const primaryAttemptTimeoutMs = options.primaryAttemptTimeoutMs || 28_000;
  const fallbackAttemptTimeoutMs = options.fallbackAttemptTimeoutMs || 22_000;
  const memoryTimeoutMs = options.memoryTimeoutMs || 18_000;

  function getApiKey() {
    return String(env.GEMINI_API_KEY || "").trim();
  }

  function getPrimaryModel() {
    const configured = String(env.GEMINI_PRIMARY_MODEL || "").trim();
    return configured || DEFAULT_PRIMARY_MODEL;
  }

  function getFallbackModel() {
    const configured = String(env.GEMINI_FALLBACK_MODEL || "").trim();
    return configured || DEFAULT_FALLBACK_MODEL;
  }

  function resolveModelChain(requestedModel) {
    const primary = getPrimaryModel();
    const fallback = getFallbackModel();
    const cleanRequested = String(requestedModel || "").trim();
    const chain = [];
    if (cleanRequested && ALLOWED_MODELS.has(cleanRequested)) {
      chain.push(cleanRequested);
    }
    if (!chain.includes(primary)) chain.push(primary);
    if (fallback && !chain.includes(fallback)) chain.push(fallback);
    return chain;
  }

  async function streamConversation({
    systemInstruction = "",
    history = [],
    userMessage = "",
    maxOutputTokens = 384,
    modelName = "",
    thinkingLevel = "LOW",
    signal,
    onChunk,
  }) {
    const apiKey = getApiKey();
    if (!apiKey) {
      return {
        ok: false,
        status: 503,
        error: "GEMINI_API_KEY is not configured on the backend server.",
      };
    }

    const contents = buildGeminiContents(history, userMessage);
    if (contents.length === 0) {
      return {
        ok: false,
        status: 400,
        error: "userMessage is required.",
      };
    }

    const clampedMaxTokens = Math.min(Math.max(Number(maxOutputTokens) || 384, 64), 1024);
    const modelChain = resolveModelChain(modelName);
    let lastErrorStatus = 502;
    let lastErrorMessage = "Gemini service could not generate a response right now.";

    for (let modelIndex = 0; modelIndex < modelChain.length; modelIndex++) {
      const activeModel = modelChain[modelIndex];
      const timeoutMs = modelIndex === 0 ? primaryAttemptTimeoutMs : fallbackAttemptTimeoutMs;
      let emittedAnyChunk = false;

      const requestBody = {
        contents,
        generationConfig: {
          maxOutputTokens: clampedMaxTokens,
          temperature: 0.85,
          topP: 0.95,
          thinkingConfig: {
            thinkingBudget: String(thinkingLevel).toUpperCase() === "MINIMAL" ? 0 : 128,
          },
        },
      };

      const cleanSystem = String(systemInstruction || "").trim().slice(0, 8000);
      if (cleanSystem) {
        requestBody.systemInstruction = {
          parts: [{ text: cleanSystem }],
        };
      }

      for (let retry = 0; retry <= 1; retry++) {
        if (signal?.aborted) {
          return { ok: false, status: 499, error: "Request cancelled by client." };
        }

        const attempt = createAttemptSignal(signal, timeoutMs);
        try {
          const url = `${apiBase}/${encodeURIComponent(activeModel)}:streamGenerateContent?alt=sse`;
          const resp = await fetchImpl(url, {
            method: "POST",
            headers: {
              "Content-Type": "application/json",
              "x-goog-api-key": apiKey,
            },
            body: JSON.stringify(requestBody),
            signal: attempt.signal,
          });

          if (!resp.ok) {
            const errText = await resp.text().catch(() => "");
            lastErrorStatus = resp.status;
            lastErrorMessage = `Gemini API returned HTTP ${resp.status}.`;
            attempt.cleanup();

            // Retry transient 429/503 once on the same model before falling back
            if ((resp.status === 429 || resp.status === 503) && retry === 0 && !signal?.aborted) {
              const waitMs = parseRetryAfterMs(resp.headers?.get?.("retry-after"), retry);
              await sleep(waitMs, signal);
              continue;
            }
            // Fall through to next model in chain on model-unavailable (400/404) or transient 429/5xx
            if (resp.status === 400 || resp.status === 404 || resp.status === 429 || resp.status >= 500) {
              break;
            }
            return {
              ok: false,
              status: resp.status === 401 || resp.status === 403 ? 502 : resp.status,
              error: errText ? lastErrorMessage : "Gemini API request rejected.",
            };
          }

          if (!resp.body) {
            attempt.cleanup();
            lastErrorStatus = 502;
            lastErrorMessage = "Gemini API returned an empty stream.";
            break;
          }

          const reader = resp.body.getReader();
          const decoder = new TextDecoder("utf-8");
          let buffer = "";

          while (true) {
            const { done, value } = await reader.read();
            if (done) break;
            buffer += decoder.decode(value, { stream: true });

            let newlineIdx = buffer.indexOf("\n");
            while (newlineIdx !== -1) {
              const rawLine = buffer.slice(0, newlineIdx).replace(/\r$/, "");
              buffer = buffer.slice(newlineIdx + 1);
              newlineIdx = buffer.indexOf("\n");

              if (!rawLine.startsWith("data:")) continue;
              const dataStr = rawLine.slice(5).trim();
              if (!dataStr || dataStr === "[DONE]") continue;
              try {
                const parsed = JSON.parse(dataStr);
                const textPiece = extractTextFromGeminiPayload(parsed);
                if (textPiece) {
                  emittedAnyChunk = true;
                  if (typeof onChunk === "function") {
                    await onChunk(textPiece);
                  }
                }
              } catch {
                // Ignore incomplete or keepalive SSE comments
              }
            }
          }

          // Flush any trailing line without newline
          if (buffer.trim().startsWith("data:")) {
            const dataStr = buffer.trim().slice(5).trim();
            if (dataStr && dataStr !== "[DONE]") {
              try {
                const parsed = JSON.parse(dataStr);
                const textPiece = extractTextFromGeminiPayload(parsed);
                if (textPiece) {
                  emittedAnyChunk = true;
                  if (typeof onChunk === "function") {
                    await onChunk(textPiece);
                  }
                }
              } catch {
                // Ignore trailing fragment
              }
            }
          }

          attempt.cleanup();
          if (emittedAnyChunk) {
            return { ok: true, modelUsed: activeModel };
          }
          lastErrorStatus = 502;
          lastErrorMessage = "Gemini returned an empty response.";
          break;
        } catch (err) {
          const timedOut = attempt.didTimeout();
          attempt.cleanup();
          if (signal?.aborted) {
            return { ok: false, status: 499, error: "Request cancelled by client." };
          }
          if (emittedAnyChunk) {
            return { ok: false, status: timedOut ? 504 : 502, error: "Stream interrupted mid-response." };
          }
          lastErrorStatus = timedOut ? 504 : 502;
          lastErrorMessage = timedOut
            ? `Model ${activeModel} timed out before first token.`
            : "Network error communicating with Gemini API.";
          break;
        }
      }
    }

    return {
      ok: false,
      status: lastErrorStatus,
      error: lastErrorMessage,
    };
  }

  async function extractMemoryCandidate({ prompt = "", signal }) {
    const apiKey = getApiKey();
    if (!apiKey) {
      return { ok: false, status: 503, error: "GEMINI_API_KEY is not configured on the backend server." };
    }
    const cleanPrompt = String(prompt || "").trim().slice(0, 3000);
    if (!cleanPrompt) {
      return { ok: false, status: 400, error: "prompt is required." };
    }

    const model = getFallbackModel() || getPrimaryModel();
    const attempt = createAttemptSignal(signal, memoryTimeoutMs);
    try {
      const url = `${apiBase}/${encodeURIComponent(model)}:generateContent`;
      const body = {
        contents: [{ role: "user", parts: [{ text: cleanPrompt }] }],
        generationConfig: {
          responseMimeType: "application/json",
          responseSchema: {
            type: "OBJECT",
            properties: {
              shouldSuggest: { type: "BOOLEAN" },
              category: { type: "STRING" },
              content: { type: "STRING" },
            },
            required: ["shouldSuggest", "category", "content"],
          },
          maxOutputTokens: 160,
          thinkingConfig: { thinkingBudget: 0 },
        },
      };

      const resp = await fetchImpl(url, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "x-goog-api-key": apiKey,
        },
        body: JSON.stringify(body),
        signal: attempt.signal,
      });

      if (!resp.ok) {
        return { ok: false, status: resp.status, error: `Gemini memory extraction failed (HTTP ${resp.status}).` };
      }
      const data = await resp.json();
      const rawJson = extractTextFromGeminiPayload(data).trim();
      if (!rawJson) {
        return { ok: true, suggestion: { shouldSuggest: false, category: "Context", content: "" } };
      }
      const parsed = JSON.parse(rawJson);
      return {
        ok: true,
        suggestion: {
          shouldSuggest: Boolean(parsed.shouldSuggest),
          category: String(parsed.category || "Context").trim().slice(0, 40) || "Context",
          content: String(parsed.content || "").trim().slice(0, 160),
        },
      };
    } catch (err) {
      if (signal?.aborted) {
        return { ok: false, status: 499, error: "Cancelled." };
      }
      return { ok: false, status: attempt.didTimeout() ? 504 : 502, error: "Memory extraction unavailable." };
    } finally {
      attempt.cleanup();
    }
  }

  return {
    getApiKey,
    getPrimaryModel,
    getFallbackModel,
    streamConversation,
    extractMemoryCandidate,
  };
}

module.exports = {
  createGeminiService,
  buildGeminiContents,
  extractTextFromGeminiPayload,
  DEFAULT_PRIMARY_MODEL,
  DEFAULT_FALLBACK_MODEL,
};
