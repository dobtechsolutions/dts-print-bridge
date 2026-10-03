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
  if (!token || !printerIp) {
    setStatus("err", "Token and printer IP are required");
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

listen<string>("bridge-log", (evt) => log(evt.payload));
listen<{ ok: boolean; text: string }>("bridge-status", (evt) => {
  setStatus(evt.payload.ok ? "ok" : "err", evt.payload.text);
});

loadConfig();
