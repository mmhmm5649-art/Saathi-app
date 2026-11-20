function createFirebaseAuthMiddleware({ verifyIdToken, verifyAppCheckToken } = {}) {
  return async function requireFirebaseAuth(req, res, next) {
    const authHeader = String(req.headers.authorization || "");
    const appCheckHeader = String(req.headers["x-firebase-appcheck"] || "").trim();

    if (authHeader.startsWith("Bearer ")) {
      const idToken = authHeader.slice(7).trim();
      if (idToken && typeof verifyIdToken === "function") {
        try {
          req.user = await verifyIdToken(idToken);
          req.authMethod = "firebase_id_token";
          return next();
        } catch {
          if (!appCheckHeader) {
            return res.status(401).json({ error: "Invalid or expired Firebase Auth token" });
          }
        }
      }
    }

    if (appCheckHeader && typeof verifyAppCheckToken === "function") {
      try {
        req.appCheck = await verifyAppCheckToken(appCheckHeader);
        req.authMethod = "firebase_app_check";
        return next();
      } catch {
        return res.status(401).json({ error: "Invalid or expired Firebase App Check token" });
      }
    }

    return res.status(401).json({
      error: "Missing Firebase App Check (X-Firebase-AppCheck) or Firebase Auth Bearer token",
    });
  };
}

module.exports = { createFirebaseAuthMiddleware };
