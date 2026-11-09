"""Package a private plugin ONLY after the user has deployed the real HTTPS bridge."""
import json
import sys
import zipfile
from pathlib import Path
from urllib.parse import urlsplit
p=Path(__file__).resolve().parent
if len(sys.argv)!=2:
    raise SystemExit("Usage: python make_plugin.py https://real-service.onrender.com")
u=urlsplit(sys.argv[1])
if u.scheme!="https" or not u.netloc or u.username or u.password or u.query or u.fragment:
    raise SystemExit("Real HTTPS base URL required, without credentials")
if u.hostname and (u.hostname.endswith((".example",".invalid",".test")) or u.hostname=="example.com"):
    raise SystemExit("Do not use a placeholder")
endpoint=sys.argv[1].rstrip("/")
if not endpoint.endswith("/mcp"):endpoint+="/mcp"
mcp={
 "$schema":"https://agent-plugins.org/schemas/1.0.0/mcp.schema.json",
 "mcpServers":{
   "xiaomi-phone":{
     "type":"streamable-http","url":endpoint,
     "extensions":{"com.openai":{"auth":{"type":"api_key","headerScheme":"bearer"}}}
   }
 }
}
out=p/"xiaomi-phone-bridge-plugin.zip"
with zipfile.ZipFile(out,"w",zipfile.ZIP_DEFLATED) as z:
  z.write(p/"plugin"/"plugin.json","xiaomi-phone-bridge/plugin.json")
  z.write(p/"plugin"/"skills"/"xiaomi-phone-bridge"/"SKILL.md","xiaomi-phone-bridge/skills/xiaomi-phone-bridge/SKILL.md")
  z.writestr("xiaomi-phone-bridge/mcp.json",json.dumps(mcp,indent=2,ensure_ascii=False))
print("Created:",out)
print("This does not install a plugin. Connect to the server using the plugin manager.")
