/* Relay Steam Deck: original PS Portal-style canvas, local authenticated
 * launcher bridge. Actual game video/audio remain in installed Chiaki client
 * until the native Relay Linux decoder is integrated. No demo success UI. */
(() => {
  "use strict";
  let token = "";
  const setStatus = msg => {
    let note = document.getElementById("deck-status");
    if (!note) {
      note = document.createElement("div");
      note.id = "deck-status";
      note.setAttribute("role", "status");
      note.style.cssText = "position:fixed;left:3%;bottom:8%;color:#ddd9ff;background:#151228da;padding:10px 16px;border-radius:12px;z-index:99;font:15px sans-serif;max-width:80vw;";
      document.body.appendChild(note);
    }
    note.textContent = msg;
  };
  const launch = async () => {
    if (!token) return setStatus("Local backend is not ready.");
    try {
      const response = await fetch("/api/connect", {method:"POST", headers:{"X-Relay-Local-Token":token}});
      const result = await response.json();
      setStatus(result.launched ? "Opening your installed native Remote Play client."
        : result.message || "Native streaming client is unavailable.");
    } catch (_) { setStatus("Cannot reach local Relay launcher."); }
  };
  const bind = () => {
    const connect = document.getElementById("connect-button");
    if (connect) connect.addEventListener("click", event => {
      event.stopImmediatePropagation();
      launch();
    }, true);
    document.getElementById("home-subtitle").textContent =
      "Steam Deck • Native PS5 client required";
    fetch("/api/status", {cache:"no-store"}).then(r=>r.json()).then(data => {
      token = data.token || "";
      setStatus(data.backend_available
        ? "Native streaming client found. Select Connect to launch it."
        : "Install Chiaki-ng/Chiaki4deck for PS5 streaming.");
    }).catch(() => setStatus("Local launcher unavailable."));
    let held = false;
    setInterval(() => {
      const pad = Array.from(navigator.getGamepads?.() || []).find(Boolean);
      const pressed = !!pad?.buttons?.[0]?.pressed;
      if (pressed && !held && document.activeElement?.id === "connect-button") launch();
      held = pressed;
    }, 80);
  };
  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", bind);
  else bind();
})();
