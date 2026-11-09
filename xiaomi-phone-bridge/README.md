# Xiaomi Phone Bridge Android build

This is an experimental Android client, not a connected ChatGPT app. No hard-coded server or tokens.

- Build only via .github/workflows/xiaomi-phone-bridge-apk.yml on branch xiaomi-phone-bridge-build.
- After installation, the app requires an independently deployed HTTPS server with /device/start, /device/poll, /device/reply, /device/stop API.
- The matching Python/FastAPI bridge prototype exists separately; hosting and MCP connection are NOT configured.
- A user must manually choose files and press Enable. The button Disable, or leaving the foreground activity, revokes local access.
- The app does not request SMS, accessibility, screen capture, notification or broad storage permissions.
- Do not put secrets in this public repo; do not use it to store phone documents or command queues.
- Debug builds are not production-signed. Verify APK only after GitHub Actions reports build success.
