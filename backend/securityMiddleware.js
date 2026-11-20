const crypto = require("node:crypto");

function timingSafeEqualString(a, b) {
  const bufA = Buffer.from(String(a || ""), "utf8");
  const bufB = Buffer.from(String(b || ""), "utf8");
  if (bufA.length !== bufB.length || bufA.length === 0) return false;
  return crypto.timingSafeEqual(bufA, bufB);
}

function extractClientIp(req) {
  const forwarded = String(req.headers?.["x-forwarded-for"] || "").split(",")[0].trim();
  if (forwarded) return forwarded;
  return String(req.ip || req.socket?.remoteAddress || "unknown");
}

function isLocalHost(hostHeader) {
  const host = String(hostHeader || "").toLowerCase().split(":")[0];
  return host === "localhost" || host === "127.0.0.1" || host === "::1" || host === "10.0.2.2";
}

function createRateLimiter({
  windowMs = 60_000,
  maxRequests = 60,
  maxTrackedClients = 5000,
  nowFn = () => Date.now(),
} = {}) {
  const buckets = new Map();

  function pruneExpired(now) {
    if (buckets.size < maxTrackedClients) return;
    for (const [key, timestamps] of buckets.entries()) {
      const fresh = timestamps.filter((t) => now - t < windowMs);
      if (fresh.length === 0) {
        buckets.delete(key);
      } else {
        buckets.set(key, fresh);
      }
    }
  }

  return function rateLimitMiddleware(req, res, next) {
    const now = nowFn();
    pruneExpired(now);
    const key = extractClientIp(req);
    const existing = buckets.get(key) || [];
    const recent = existing.filter((t) => now - t < windowMs);

    if (recent.length >= maxRequests) {
      const oldest = recent[0] || now;
      const retryAfterSec = Math.max(1, Math.ceil((windowMs - (now - oldest)) / 1000));
      res.setHeader?.("Retry-After", String(retryAfterSec));
      return res.status(429).json({
        error: "Too many requests. Please wait a moment and try again.",
      });
    }

    recent.push(now);
    buckets.set(key, recent);
    return next();
  };
}

function createSecurityMiddleware({
  getAccessToken = () => String(process.env.SAATHI_BACKEND_ACCESS_TOKEN || "").trim(),
  requireHttps = () => String(process.env.REQUIRE_HTTPS || "").toLowerCase() === "true",
} = {}) {
  return function verifyRequestSecurity(req, res, next) {
    // Hardened API response headers
    res.setHeader?.("X-Content-Type-Options", "nosniff");
    res.setHeader?.("X-Frame-Options", "DENY");
    res.setHeader?.("Referrer-Policy", "no-referrer");
    res.setHeader?.("Cache-Control", "no-store");

    // Optional HTTPS enforcement when behind TLS-terminating proxy (Cloud Run, Render, Fly.io, etc.)
    const mustUseHttps = typeof requireHttps === "function" ? requireHttps() : Boolean(requireHttps);
    if (mustUseHttps) {
      const proto = String(req.headers?.["x-forwarded-proto"] || "").split(",")[0].trim().toLowerCase();
      const secure = Boolean(req.secure) || proto === "https";
      if (!secure && !isLocalHost(req.headers?.host)) {
        return res.status(426).json({ error: "HTTPS is required for SAATHI backend requests." });
      }
    }

    // Optional server-configured shared access token for locking down public deployments
    const expectedToken = typeof getAccessToken === "function" ? String(getAccessToken() || "").trim() : "";
    if (expectedToken) {
      const headerToken = String(req.headers?.["x-saathi-client-token"] || "").trim();
      const authHeader = String(req.headers?.authorization || "").trim();
      const bearerToken = authHeader.startsWith("Bearer ") ? authHeader.slice(7).trim() : "";
      const provided = headerToken || bearerToken;
      if (!provided || !timingSafeEqualString(provided, expectedToken)) {
        return res.status(401).json({
          error: "Unauthorized request. Missing or invalid SAATHI backend access token.",
        });
      }
    }

    return next();
  };
}

function validateChatPayload(body) {
  if (!body || typeof body !== "object") {
    return { ok: false, error: "Request body must be a JSON object." };
  }
  const userMessage = String(body.userMessage || "").trim();
  if (!userMessage) {
    return { ok: false, error: "userMessage is required and must be non-empty." };
  }
  if (userMessage.length > 4000) {
    return { ok: false, error: "userMessage exceeds maximum length of 4000 characters." };
  }
  if (body.history !== undefined && !Array.isArray(body.history)) {
    return { ok: false, error: "history must be an array of { role, text } objects." };
  }
  if (Array.isArray(body.history) && body.history.length > 30) {
    return { ok: false, error: "history exceeds maximum limit of 30 turns." };
  }
  if (body.systemInstruction !== undefined && String(body.systemInstruction).length > 8000) {
    return { ok: false, error: "systemInstruction exceeds maximum allowed length." };
  }
  return { ok: true };
}

function validateMemoryPayload(body) {
  if (!body || typeof body !== "object") {
    return { ok: false, error: "Request body must be a JSON object." };
  }
  const prompt = String(body.prompt || "").trim();
  if (!prompt || prompt.length < 10) {
    return { ok: false, error: "prompt must be at least 10 characters." };
  }
  if (prompt.length > 3000) {
    return { ok: false, error: "prompt exceeds maximum length of 3000 characters." };
  }
  return { ok: true };
}

module.exports = {
  createRateLimiter,
  createSecurityMiddleware,
  validateChatPayload,
  validateMemoryPayload,
};
