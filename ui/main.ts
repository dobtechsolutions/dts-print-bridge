import { invoke } from "@tauri-apps/api/core";
import { listen } from "@tauri-apps/api/event";
import { enable as enableAutostart, disable as disableAutostart, isEnabled as autostartEnabled } from "@tauri-apps/plugin-autostart";

const $ = <T extends HTMLElement>(id: string) => document.getElementById(id) as T;

const tokenEl = $<HTMLInputElement>("token");
const ipEl = $<HTMLInputElement>("ip");
const portEl = $<HTMLInputElement>("port");
const autostartEl = $<HTMLInputElement>("autostart");
const connectBtn = $<HTMLButtonElement>("connect");
const disconnectBtn = $<HTMLButtonElement>("disconnect");
const pasteBtn = $<HTMLButtonElement>("paste");
const dot = $<HTMLSpanElement>("dot");
const statusText = $<HTMLSpanElement>("statusText");
const logEl = $<HTMLDivElement>("log");
const findBtn = $<HTMLButtonElement>("find");
const findStatus = $<HTMLDivElement>("findStatus");
const foundEl = $<HTMLDivElement>("found");

const logLines: string[] = [];
function log(line: string) {
  const ts = new Date().toLocaleTimeString();
  logLines.push(`[${ts}] ${line}`);
  while (logLines.length > 20) logLines.shift();
  logEl.textContent = logLines.join("\n");
  logEl.scrollTop = logEl.scrollHeight;
}

function setStatus(state: "idle" | "ok" | "err", text: string) {
  dot.className = "dot" + (state === "ok" ? " ok" : state === "err" ? " err" : "");
  statusText.textContent = text;
}

function setRunning(running: boolean) {
  connectBtn.disabled = running;
  disconnectBtn.disabled = !running;
}

async function loadConfig() {
  const cfg = await invoke<{ token: string; printerIp: string; printerPort: number } | null>("load_config");
  if (cfg) {
    tokenEl.value = cfg.token ?? "";
    ipEl.value = cfg.printerIp ?? "";
    portEl.value = String(cfg.printerPort ?? 9100);
  }
  try {
    autostartEl.checked = await autostartEnabled();
  } catch (e) {
    log("autostart unavailable: " + String(e));
  }
  const running = await invoke<boolean>("bridge_running");
  setRunning(running);
  if (running) setStatus("ok", "Connected — waiting for jobs");
}

pasteBtn.addEventListener("click", async () => {
  try { tokenEl.value = (await navigator.clipboard.readText()).trim(); } catch { /* ignore */ }
});

autostartEl.addEventListener("change", async () => {
  try {
    if (autostartEl.checked) await enableAutostart();
    else await disableAutostart();
  } catch (e) {
    log("autostart error: " + String(e));
  }
});

connectBtn.addEventListener("click", async () => {
  const token = tokenEl.value.trim();
  const printerIp = ipEl.value.trim();
  const printerPort = parseInt(portEl.value || "9100", 10);
  if (!token) {
    setStatus("err", "Paste the device token first");
    return;
  }
  try {
    await invoke("save_config", { config: { token, printerIp, printerPort } });
    await invoke("start_bridge");
    if (autostartEl.checked) {
      try { await enableAutostart(); } catch { /* ignore */ }
    }
    setRunning(true);
    setStatus("ok", "Connecting…");
    log("bridge started");
  } catch (e) {
    setStatus("err", "Could not start: " + String(e));
  }
});

disconnectBtn.addEventListener("click", async () => {
  await invoke("stop_bridge");
  setRunning(false);
  setStatus("idle", "Not connected");
  log("bridge stopped");
});

type FoundPrinter = { ip: string; port: number; model: string };

findBtn.addEventListener("click", async () => {
  findBtn.disabled = true;
  foundEl.textContent = "";
  findStatus.textContent = "Searching the network for printers… (about 10 seconds)";
  try {
    const res = await invoke<{ ownIp: string | null; printers: FoundPrinter[] }>("find_printers");
    if (!res.ownIp) {
      findStatus.textContent = "This computer isn't on a network. Connect it to the restaurant's WiFi or cable first.";
    } else if (res.printers.length === 0) {
      findStatus.textContent =
        `No printers found on ${res.ownIp.replace(/\.\d+$/, ".x")}. Is the printer on and connected to the same network (cable or WiFi)?`;
    } else {
      findStatus.textContent = `${res.printers.length} printer(s) found. Click the one to use:`;
      for (const p of res.printers) {
        const b = document.createElement("button");
        b.textContent = `${p.model || "Network printer"} — ${p.ip}`;
        b.addEventListener("click", () => {
          ipEl.value = p.ip;
          portEl.value = String(p.port);
          findStatus.textContent = `Selected ${p.model || "printer"} at ${p.ip}. Press Connect.`;
          foundEl.textContent = "";
          log(`printer chosen: ${p.model || "printer"} ${p.ip}:${p.port}`);
        });
        foundEl.appendChild(b);
      }
    }
    log(`find printers: ${res.printers.length} found`);
  } catch (e) {
    findStatus.textContent = "Search failed: " + String(e);
  } finally {
    findBtn.disabled = false;
  }
});

listen<string>("bridge-log", (evt) => log(evt.payload));
listen<{ ok: boolean; text: string }>("bridge-status", (evt) => {
  setStatus(evt.payload.ok ? "ok" : "err", evt.payload.text);
});

loadConfig();
