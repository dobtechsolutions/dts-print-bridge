# DTS Print Bridge

Small desktop app that relays print jobs from Restaurant by Dob Tech Solutions to a
WiFi thermal printer (Munbyn, Xprinter, Rongta, etc.) over the local network.

Restaurant owners don't build this. They download the installer from
**Settings › Printers** in the web app, which links to the latest release here:

- Windows: `releases/latest/download/DTSPrintBridge-Setup.exe`
- macOS (Apple Silicon + Intel): `releases/latest/download/DTSPrintBridge.dmg`
- Linux: `releases/latest/download/DTSPrintBridge.AppImage`

## Setup at a restaurant

1. In the web app, go to **Settings › Printers → Add printer**, choose
   *WiFi thermal (Generic ESC/POS)*, enter the printer's local IP (from its
   self-test page) and port `9100`.
2. Copy the **device token** from the printer card.
3. On an always-on computer on the same WiFi, install DTS Print Bridge, paste the
   token, enter the printer IP, click **Connect**.
4. The printer card in the web app switches to **Bridge online**. Use **Test print**.

## How it works

1. Every 2 seconds: `GET https://crpfwwuiujothrxflmef.supabase.co/functions/v1/lan-bridge-poll?token=<device-token>`.
2. If a job comes back, open a TCP socket to `printerAddress:printerPort`
   (usually `9100`), write the base64-decoded ESC/POS bytes, close.
3. `POST` the same endpoint with `{ token, jobId, code }` to acknowledge.

Config is stored at `~/.dts-print-bridge/config.json`. "Start at login" uses
`tauri-plugin-autostart`.

## Development

Requires Rust (`rustup`) and Node 20+.

```
npm install
npm run icons        # generates src-tauri/icons from app-icon.svg
npm run tauri dev
```

## Releasing

Push a tag named `dts-print-bridge-vX.Y.Z` (or create a release with that tag
on GitHub). The `release` workflow builds Windows, macOS and Linux installers
and attaches them to the release.

Installers are currently **unsigned**: Windows SmartScreen and macOS Gatekeeper
warn on first launch (Windows: *More info → Run anyway*; macOS: right-click →
*Open*). Code signing is a follow-up.
