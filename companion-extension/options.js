if (typeof browser === "undefined") var browser = chrome;
const $ = (id) => document.getElementById(id);

async function load() {
  const { token = "", webDefault = false } = await browser.storage.local.get(["token", "webDefault"]);
  $("token").value = token;
  $("webDefault").checked = webDefault;
}

$("save").addEventListener("click", async () => {
  await browser.storage.local.set({
    token: $("token").value.trim(),
    webDefault: $("webDefault").checked,
  });
  $("saved").textContent = "saved \u2713";
  setTimeout(() => ($("saved").textContent = ""), 1500);
});

load();
