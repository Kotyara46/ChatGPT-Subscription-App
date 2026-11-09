# Xiaomi Phone Bridge — deployment without buying server space

- **APK build (successful):** https://github.com/Kotyara46/ChatGPT-Subscription-App/actions/runs/42696789694
- **Server tests (successful):** https://github.com/Kotyara46/ChatGPT-Subscription-App/actions/runs/42698956292
- **Source branch:** https://github.com/Kotyara46/ChatGPT-Subscription-App/tree/xiaomi-phone-bridge-build
- **Server blueprint:** root `render.yaml`. This repo is public; do not commit device keys or phone data.

## Required user-controlled actions
1. Open Render's Deploy page for the above branch and approve creating the Free service.
2. During blueprint creation set **two distinct secret environment variables** (`PHONE_BRIDGE_DEVICE_TOKEN` and `PHONE_BRIDGE_MCP_TOKEN`, 32+ characters each). Use password-manager-generated random values; DO NOT send either value to ChatGPT.
3. Wait for Render to show a live HTTPS service URL and check `https://<host>/health` returns `{"ok":true,...}`.
4. Xiaomi APK: enter `https://<host>` (without `/mcp`) and **only the device token**. Select test text and tap Enable. After testing, tap Disable.
5. In ChatGPT **web** use Add custom MCP server: `https://<host>/mcp`. Configure API key / Bearer authentication using **only the MCP token**, or generate the private plugin archive from this source after host is live. The plugin is not connected until these steps complete.

## Limitations
The Android APK reads only explicitly selected text files up to 256 KiB; no touch control, shell or background screen view. No 24/7 access. Session ends on Stop, app exit, or server heartbeat timeout (up to 15 seconds).
Render Free may sleep after 15 minutes of inactivity, and has quotas; this is experimental, not audited for sensitive data.
