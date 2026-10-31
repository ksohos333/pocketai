if (typeof browser === "undefined") var browser = chrome;
const $ = (id) => document.getElementById(id);
const API = "http://127.0.0.1:8182/ask";
let convo = [];

async function cfg() {
  const { token = "", webDefault = false, keepDefault = false } =
    await browser.storage.local.get(["token", "webDefault", "keepDefault"]);
  return { token, webDefault, keepDefault };
}

function render() {
  $("answer").textContent = "";
  convo.forEach((m) => {
    const who = document.createElement("div");
    who.className = "who " + (m.role === "user" ? "you" : "ai");
    who.textContent = m.role === "user" ? "you" : "pocketai";
    const body = document.createElement("div");
    body.textContent = m.content;
    $("answer").append(who, body);
  });
}

async function persistMaybe() {
  const { keepDefault } = await cfg();
  if (keepDefault) await browser.storage.local.set({ convo });
  else await browser.storage.local.remove("convo");
}

async function init() {
  const { token, webDefault, keepDefault } = await cfg();
  $("web").checked = webDefault;
  $("keep").checked = keepDefault;
  $("privacyBadge").textContent = webDefault ? "web-assisted" : "on-device";
  $("privacyBadge").classList.toggle("web", webDefault);
  if (keepDefault) {
    const { convo: saved = [] } = await browser.storage.local.get("convo");
    convo = saved;
    render();
  }
  if (!token) {
    $("status").textContent = "No access token set — open settings (\u2699) and paste the token from the PocketAI app.";
  }
}

$("web").addEventListener("change", () => {
  const on = $("web").checked;
  $("privacyBadge").textContent = on ? "web-assisted" : "on-device";
  $("privacyBadge").classList.toggle("web", on);
});

$("keep").addEventListener("change", async () => {
  const on = $("keep").checked;
  await browser.storage.local.set({ keepDefault: on });
  if (!on) {
    await browser.storage.local.remove("convo");
    $("status").textContent = "history cleared from storage — session-only from now";
  } else {
    $("status").textContent = "conversation will be kept on this device";
    await persistMaybe();
  }
});

$("del").addEventListener("click", async () => {
  convo = [];
  await browser.storage.local.remove("convo");
  $("q").value = "";
  $("status").textContent = "chat deleted from this page and local storage";
  render();
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
  $("status").textContent = "thinking\u2026 (local model, this can take a while)";
  try {
    const resp = await fetch(API, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "Authorization": "Bearer " + token,
      },
      body: JSON.stringify({ q: q, web: $("web").checked, history: convo.slice(-12) }),
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
    convo.push({ role: "user", content: q });
    convo.push({ role: "assistant", content: data.answer });
    if (convo.length > 40) convo = convo.slice(-40);
    await persistMaybe();
    $("q").value = "";
    $("status").textContent = "";
    render();
    window.scrollTo(0, document.body.scrollHeight);
  } catch (err) {
    $("status").textContent =
      "Gateway unreachable \u2014 is the PocketAI app running on this phone?";
  }
});

init();
