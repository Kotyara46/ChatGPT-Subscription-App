# Xiaomi Phone Bridge — Render-ready backend (experimental)

The installable Android APK is produced by GitHub Actions in this branch.

## Deploy
Use Render Web Service Free plan with:
- Repository branch: `xiaomi-phone-bridge-build`
- Root directory: `xiaomi-phone-bridge/remote`
- Runtime: Python
- Build command: `pip install -r requirements.txt`
- Start command: `uvicorn app:app --host 0.0.0.0 --port $PORT --workers 1`

Set two DIFFERENT randomly generated environment secrets, each >=32 chars:
- `PHONE_BRIDGE_DEVICE_TOKEN`: copy privately into the phone app
- `PHONE_BRIDGE_MCP_TOKEN`: use only in the ChatGPT plugin's Bearer authentication field

Never paste these secrets into public GitHub files, screenshots, or chat messages.

Once deployed you get `https://YOUR-RENDER-SERVICE.onrender.com`.
Use the base URL in the Android app, and `https://YOUR-RENDER-SERVICE.onrender.com/mcp` in a ChatGPT MCP plugin.

Without a Render account connected, a deployed server URL does NOT yet exist.

## Security / scope
Only three read-only MCP tools, using files explicitly selected in Android.
File texts are fetched only on demand and never persisted. Phone OFF switch revokes the session;
if connection drops, server session expires after 15 seconds since last poll.
In-memory single-process sessions. Do not enable multiple workers.
No SMS, messages, contacts, screen, microphone, camera, root, shell, or broad storage.
Public-health endpoint shows no phone status.
This prototype has NOT been through security audit. Use only for non-sensitive test text first.
