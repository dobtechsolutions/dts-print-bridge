// DTS Print Bridge — Tauri app main.
// Ports the polling loop from public/lan-bridge.ts to a native desktop shell.

#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use base64::{engine::general_purpose::STANDARD as B64, Engine};
use serde::{Deserialize, Serialize};
use std::{path::PathBuf, sync::Arc, time::Duration};
use tauri::{AppHandle, Emitter, Manager, State};
use tauri_plugin_autostart::MacosLauncher;
use tokio::{io::AsyncWriteExt, net::TcpStream, sync::Mutex, task::JoinHandle, time::sleep};

const API_BASE: &str = "https://crpfwwuiujothrxflmef.supabase.co/functions/v1/lan-bridge-poll";
const POLL_INTERVAL: Duration = Duration::from_secs(2);

#[derive(Serialize, Deserialize, Clone, Default)]
#[serde(rename_all = "camelCase")]
struct Config {
    token: String,
    printer_ip: String,
    #[serde(default = "default_port")]
    printer_port: u16,
}

fn default_port() -> u16 {
    9100
}

fn config_path() -> PathBuf {
    let mut p = dirs::home_dir().unwrap_or_else(|| PathBuf::from("."));
    p.push(".dts-print-bridge");
    let _ = std::fs::create_dir_all(&p);
    p.push("config.json");
    p
}

#[tauri::command]
fn load_config() -> Option<Config> {
    let raw = std::fs::read_to_string(config_path()).ok()?;
    serde_json::from_str(&raw).ok()
}

#[tauri::command]
fn save_config(config: Config) -> Result<(), String> {
    let raw = serde_json::to_string_pretty(&config).map_err(|e| e.to_string())?;
    std::fs::write(config_path(), raw).map_err(|e| e.to_string())
}

struct BridgeState {
    handle: Mutex<Option<JoinHandle<()>>>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct Job {
    job_id: String,
    printer_address: String,
    printer_port: u16,
    payload_base64: String,
}

async fn send_to_printer(host: &str, port: u16, bytes: &[u8]) -> Result<(), String> {
    let connect = TcpStream::connect((host, port));
    let mut stream = tokio::time::timeout(Duration::from_secs(5), connect)
        .await
        .map_err(|_| "connect: timed out".to_string())?
        .map_err(|e| format!("connect: {e}"))?;
    stream.write_all(bytes).await.map_err(|e| format!("write: {e}"))?;
    let _ = stream.shutdown().await;
    Ok(())
}

fn status(app: &AppHandle, ok: bool, text: &str) {
    let _ = app.emit("bridge-status", serde_json::json!({ "ok": ok, "text": text }));
}

fn log(app: &AppHandle, line: String) {
    let _ = app.emit("bridge-log", line);
}

async fn poll_once(client: &reqwest::Client, cfg: &Config, app: &AppHandle) {
    let url = format!("{}?token={}", API_BASE, urlencoding(&cfg.token));
    let res = match client.get(&url).send().await {
        Ok(r) => r,
        Err(e) => {
            log(app, format!("poll error: {e}"));
            status(app, false, "Network error");
            return;
        }
    };
    let code = res.status().as_u16();
    if code == 204 {
        status(app, true, "Connected — waiting for jobs");
        return;
    }
    if code == 401 {
        log(app, "invalid token".to_string());
        status(app, false, "Invalid device token");
        return;
    }
    if !res.status().is_success() {
        log(app, format!("HTTP {code}"));
        return;
    }
    let job: Job = match res.json().await {
        Ok(j) => j,
        Err(e) => {
            log(app, format!("bad job body: {e}"));
            return;
        }
    };
    let short = &job.job_id[..job.job_id.len().min(8)];
    log(app, format!("job {short} → {}:{}", job.printer_address, job.printer_port));

    let bytes = match B64.decode(&job.payload_base64) {
        Ok(b) => b,
        Err(e) => {
            log(app, format!("bad payload: {e}"));
            return;
        }
    };

    let result = match send_to_printer(&job.printer_address, job.printer_port, &bytes).await {
        Ok(()) => {
            log(app, format!("job {short} printed"));
            200
        }
        Err(e) => {
            log(app, format!("printer write failed: {e}"));
            status(app, false, "Printer unreachable");
            500
        }
    };

    let _ = client
        .post(API_BASE)
        .json(&serde_json::json!({ "token": cfg.token, "jobId": job.job_id, "code": result }))
        .send()
        .await;
}

fn urlencoding(s: &str) -> String {
    s.chars()
        .map(|c| match c {
            'A'..='Z' | 'a'..='z' | '0'..='9' | '-' | '_' | '.' | '~' => c.to_string(),
            _ => format!("%{:02X}", c as u32),
        })
        .collect()
}

/// Starts the polling loop if it isn't already running. Shared by the
/// `start_bridge` command and the auto-start path in `setup`.
async fn spawn_bridge(app: AppHandle, state: Arc<BridgeState>) -> Result<(), String> {
    let mut guard = state.handle.lock().await;
    if guard.is_some() {
        return Ok(());
    }
    let cfg = load_config().ok_or_else(|| "no config".to_string())?;
    let client = reqwest::Client::builder()
        .timeout(Duration::from_secs(10))
        .build()
        .map_err(|e| e.to_string())?;
    let handle = tokio::spawn(async move {
        loop {
            poll_once(&client, &cfg, &app).await;
            sleep(POLL_INTERVAL).await;
        }
    });
    *guard = Some(handle);
    Ok(())
}

#[tauri::command]
async fn start_bridge(app: AppHandle, state: State<'_, Arc<BridgeState>>) -> Result<(), String> {
    spawn_bridge(app, state.inner().clone()).await
}

#[tauri::command]
async fn stop_bridge(state: State<'_, Arc<BridgeState>>) -> Result<(), String> {
    let mut guard = state.handle.lock().await;
    if let Some(h) = guard.take() {
        h.abort();
    }
    Ok(())
}

#[tauri::command]
async fn bridge_running(state: State<'_, Arc<BridgeState>>) -> Result<bool, String> {
    Ok(state.handle.lock().await.is_some())
}

fn main() {
    tauri::Builder::default()
        .plugin(tauri_plugin_autostart::init(
            MacosLauncher::LaunchAgent,
            Some(vec!["--minimized"]),
        ))
        .manage(Arc::new(BridgeState { handle: Mutex::new(None) }))
        .invoke_handler(tauri::generate_handler![
            load_config,
            save_config,
            start_bridge,
            stop_bridge,
            bridge_running
        ])
        .setup(|app| {
            // Launched at login: start hidden.
            if std::env::args().any(|a| a == "--minimized") {
                if let Some(w) = app.get_webview_window("main") {
                    let _ = w.minimize();
                }
            }
            // Auto-start the poller if a config already exists.
            if load_config().is_some() {
                let handle = app.handle().clone();
                let state = app.state::<Arc<BridgeState>>().inner().clone();
                tauri::async_runtime::spawn(async move {
                    let _ = spawn_bridge(handle, state).await;
                });
            }
            Ok(())
        })
        .run(tauri::generate_context!())
        .expect("error while running DTS Print Bridge");
}
