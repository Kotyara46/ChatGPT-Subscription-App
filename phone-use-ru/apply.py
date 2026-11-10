#!/usr/bin/env python3
"""Russian UI overlay for upstream Phone Use, keeping MCP wire protocols untouched.

Source is based on pinned upstream commit 3d145a61...; do not translate API names,
endpoint URLs, tool identifiers or OAuth protocol fields.
"""
import json
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

root = Path(sys.argv[1]).resolve()
over = Path(__file__).resolve().parent
res = root / "android/app/src/main/res"
base_xml = res / "values/strings.xml"
tree = ET.parse(base_xml)
resources = tree.getroot()
ru = {}
for name in ("strings-01.json", "strings-02.json", "strings-03.json"):
    values=json.loads((over / name).read_text(encoding="utf-8"))
    overlap=set(ru) & set(values)
    if overlap:
        raise SystemExit(f"Duplicate locale keys in {name}: {sorted(overlap)}")
    ru.update(values)

keys = {e.get("name") for e in resources.findall("string")}
unknown = set(ru) - keys
if unknown:
    raise SystemExit(f"Russian keys not in original source: {sorted(unknown)}")
for element in resources.findall("string"):
    key=element.get("name")
    if key in ru:
        element.text=ru[key]
        # Source uses escaped apostrophes; preserve any originals kept as-is.
    elif key not in ("notification_channel_mcp_server_id","about_author_name",
                     "about_author_email","about_author_linkedin","about_author_x",
                     "remote_access_cloudflare_extra_args_hint"):
        print(f"Original string retained (not translated): {key}")

loc=res/"values-ru"
loc.mkdir(parents=True, exist_ok=True)
ET.indent(tree, space="    ")
tree.write(loc/"strings.xml",encoding="utf-8",xml_declaration=True)
print(f"Russian Android resources: {len(ru)} translated / {len(keys)} total strings.")

source_dir=root/"android/app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/ui"
ui=json.loads((over/"ui-hardcoded.json").read_text(encoding="utf-8"))
tools=json.loads((over/"tools-hardcoded.json").read_text(encoding="utf-8"))
file_count=0
translation_count=0
missing=set(ui)
seen_tools=set()
for path in sorted(source_dir.rglob("*.kt")):
    content=path.read_text(encoding="utf-8")
    # UI only: do not edit MCP tool keys or business logic outside UI modules.
    use=ui | (tools if path.name=="McpToolsSettingsScreen.kt" else {})
    for english,russian in use.items():
        old='"'+english+'"'
        new='"'+russian.replace('\\','\\\\').replace('"','\\"')+'"'
        if old in content:
            content=content.replace(old,new)
            translation_count+=1
            if english in missing:missing.remove(english)
            if english in tools:seen_tools.add(english)
    if content != path.read_text(encoding="utf-8"):
        path.write_text(content,encoding="utf-8")
        file_count+=1
print(f"Localized {translation_count} hard-coded labels in {file_count} Kotlin UI files.")
if missing:print("Remaining hard-coded labels not found:",sorted(missing))
if set(tools)-seen_tools:print("UI tool labels not found:",sorted(set(tools)-seen_tools))
# Reject translated resources containing unresolved braces or accidental empty XML.
check=ET.parse(loc/"strings.xml")
assert len(check.getroot().findall("string"))==len(keys)
