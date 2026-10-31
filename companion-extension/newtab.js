if (typeof browser === "undefined") var browser = chrome;
const $ = (id) => document.getElementById(id);
const API = "http://127.0.0.1:8182/ask";

async function cfg() {
  const { token = "", webDefault = false } = await browser.storage.local.get(["token", "webDefault"]);
  return { token, webDefault };
}

async function init() {
  const { token, webDefault } = await cfg();
  $("web").checked = webDefault;
  $("privacyBadge").textContent = webDefault ? "web-assisted" : "on-device";
  $("privacyBadge").classList.toggle("web", webDefault);
  if (!token) {
    $("status").textContent = "No access token set — open settings (\u2699) and paste the token from the PocketAI app.";
  }
}

$("web").addEventListener("change", () => {
  const on = $("web").checked;
  $("privacyBadge").textContent = on ? "web-assisted" : "on-device";
  $("privacyBadge").classList.toggle("web", on);
});

$("f").addEventListener("submit", async (e) => {
  e.preventDefault();
  const q = $("q").value.trim();
  if (!q) return;
  const { token } = await cfg();
  if (!token) {
    $("status").textContent = "Set your access token first (\u2699 settings).";
    return;
  }
  $("answer").textContent = "";
  $("status").textContent = "thinking\u2026 (local model, this can take a while)";
  try {
    const resp = await fetch(API, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "Authorization": "Bearer " + token,
      },
      body: JSON.stringify({ q: q, web: $("web").checked }),
    });
    const data = await resp.json();
    if (resp.status === 401) {
      $("status").textContent = "Bad token — re-paste it from the PocketAI app (\u2699 settings).";
      return;
    }
    if (resp.status === 503) {
      $("status").textContent = "The model is not running. Open the PocketAI app and press START once.";
      return;
    }
    if (!resp.ok) {
      $("status").textContent = "Error " + resp.status + ": " + (data.error || "?");
      return;
    }
    $("status").textContent = "";
    $("answer").textContent = data.answer;
  } catch (err) {
    $("status").textContent =
      "Gateway unreachable \u2014 is the PocketAI app running on this phone?";
  }
});

init();
