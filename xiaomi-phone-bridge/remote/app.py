"""Single-user, explicitly-enabled Android ↔ ChatGPT bridge.
No broad Android access. The selected-file list lives in memory only.
"""
import asyncio
import base64
import binascii
import hmac
import json
import os
import re
import time
import uuid
from dataclasses import dataclass, field

from fastapi import FastAPI, Header, HTTPException, Request
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field

LIMIT=262144
MAX_FILES=30
HEARTBEAT_TTL=15
PHONE_TOKEN=os.environ.get("PHONE_BRIDGE_DEVICE_TOKEN","")
MCP_TOKEN=os.environ.get("PHONE_BRIDGE_MCP_TOKEN","")
if len(PHONE_TOKEN)<32 or len(MCP_TOKEN)<32 or PHONE_TOKEN==MCP_TOKEN:
    raise RuntimeError("PHONE_BRIDGE_DEVICE_TOKEN and PHONE_BRIDGE_MCP_TOKEN must be distinct, >=32 chars")

app=FastAPI(docs_url=None,openapi_url=None,redoc_url=None)
state={"session":None}
UUID=re.compile(r"^[a-f0-9-]{36}$")

class FileEntry(BaseModel):
    id:str=Field(min_length=36,max_length=36)
    name:str=Field(min_length=1,max_length=180)
    size:int=Field(ge=-1,le=2**40)
    mime:str=Field(min_length=1,max_length=100)
class Start(BaseModel):
    files:list[FileEntry]=Field(max_length=MAX_FILES)
class Poll(BaseModel):
    session_id:str
    files:list[FileEntry]=Field(max_length=MAX_FILES)
class Stop(BaseModel):
    session_id:str
class Reply(BaseModel):
    session_id:str
    request_id:str
    file_id:str
    base64_data:str|None=Field(default=None,max_length=350000)
    error:str|None=Field(default=None,max_length=300)

@dataclass
class Pending:
    request_id:str
    file_id:str
    future:asyncio.Future

@dataclass
class Session:
    id:str
    last:float
    files:dict=field(default_factory=dict)
    waiting:list=field(default_factory=list)
    inflight:dict=field(default_factory=dict)

def auth(got,secret):
    if not got or not hmac.compare_digest(got,"Bearer "+secret):
        raise HTTPException(401,"Unauthorized")
def catalog(entries):
    out={}
    for x in entries:
        if not UUID.fullmatch(x.id) or x.id in out:
            raise HTTPException(400,"Invalid or duplicate file id")
        out[x.id]=x
    return out
def revoke():
    session=state["session"]
    if session:
        for req in session.waiting+list(session.inflight.values()):
            if not req.future.done():
                req.future.cancel()
    state["session"]=None
def live():
    s=state["session"]
    if s is not None and time.monotonic()-s.last >= HEARTBEAT_TTL:
        revoke()
        return None
    return s
def require_session(identifier):
    s=live()
    if s is None or not hmac.compare_digest(s.id,identifier):
        raise HTTPException(409,"Session expired or disabled")
    return s

@app.get("/health")
def health():
    return {"ok":True,"service":"xiaomi-phone-bridge"}

@app.post("/device/start")
async def start(body:Start,authorization:str|None=Header(default=None)):
    auth(authorization,PHONE_TOKEN)
    files=catalog(body.files)
    revoke()
    s=Session(id=str(uuid.uuid4()),last=time.monotonic(),files=files)
    state["session"]=s
    return {"session_id":s.id,"expires_after_seconds":HEARTBEAT_TTL}

@app.post("/device/poll")
async def poll(body:Poll,authorization:str|None=Header(default=None)):
    auth(authorization,PHONE_TOKEN)
    s=require_session(body.session_id)
    s.files=catalog(body.files)
    s.last=time.monotonic()
    for request in s.waiting+list(s.inflight.values()):
        if request.file_id not in s.files and not request.future.done():
            request.future.cancel()
    s.waiting=[x for x in s.waiting if not x.future.done()]
    if not s.waiting:
        return {"command":None}
    request=s.waiting.pop(0)
    s.inflight[request.request_id]=request
    return {"command":{"type":"read_text","request_id":request.request_id,"file_id":request.file_id}}

@app.post("/device/reply")
async def reply(body:Reply,authorization:str|None=Header(default=None)):
    auth(authorization,PHONE_TOKEN)
    s=require_session(body.session_id)
    p=s.inflight.pop(body.request_id,None)
    if not p or p.future.done() or p.file_id != body.file_id or p.file_id not in s.files:
        raise HTTPException(409,"Request expired or file deselected")
    if body.error:
        p.future.set_exception(ValueError(body.error))
        return {"ok":True}
    if body.base64_data is None:
        raise HTTPException(400,"Missing file data")
    try:
        raw=base64.b64decode(body.base64_data,validate=True)
    except binascii.Error:
        raise HTTPException(400,"Malformed base64")
    if len(raw)>LIMIT:
        p.future.set_exception(ValueError("File exceeds 256 KiB"))
        return {"ok":True}
    try: raw.decode("utf-8")
    except UnicodeDecodeError:
        p.future.set_exception(ValueError("Only UTF-8 text supported"))
        return {"ok":True}
    if b"\x00" in raw:
        p.future.set_exception(ValueError("Binary files disabled"))
        return {"ok":True}
    p.future.set_result(raw)
    return {"ok":True}

@app.post("/device/stop")
async def stop(body:Stop,authorization:str|None=Header(default=None)):
    auth(authorization,PHONE_TOKEN)
    require_session(body.session_id)
    revoke()
    return {"ok":True}

TOOLS=[
 {"securitySchemes":[{"type":"oauth2","scopes":["files:read"]}],"name":"phone_status","description":"Read-only: check whether the owner's phone is connected and access enabled.",
  "inputSchema":{"type":"object","properties":{},"additionalProperties":False},"annotations":{"readOnlyHint":True}},
 {"securitySchemes":[{"type":"oauth2","scopes":["files:read"]}],"name":"list_selected_files","description":"Read-only: list only files that the owner explicitly selected in the phone app.",
  "inputSchema":{"type":"object","properties":{},"additionalProperties":False},"annotations":{"readOnlyHint":True}},
 {"securitySchemes":[{"type":"oauth2","scopes":["files:read"]}],"name":"read_selected_text_file","description":"Read-only: request text of one specifically selected file (max 256 KiB) during active consent.",
  "inputSchema":{"type":"object","properties":{"file_id":{"type":"string"}},"required":["file_id"],"additionalProperties":False},
  "annotations":{"readOnlyHint":True}}
]

async def execute_tool(name,args):
    s=live()
    if name=="phone_status":
        return {"connected":s is not None,"selected_files":len(s.files) if s else 0}
    if s is None: raise ValueError("Phone offline or access disabled")
    if name=="list_selected_files":
        return {"files":[x.model_dump() for x in s.files.values()]}
    if name!="read_selected_text_file": raise ValueError("Unknown tool")
    file_id=args.get("file_id")
    if not isinstance(file_id,str) or file_id not in s.files:
        raise ValueError("File not selected")
    if len(s.waiting)+len(s.inflight)>=5:
        raise ValueError("Too many requests")
    pending=Pending(str(uuid.uuid4()),file_id,asyncio.get_running_loop().create_future())
    s.waiting.append(pending)
    try:
        raw=await asyncio.wait_for(pending.future,12)
    except asyncio.TimeoutError:
        raise ValueError("Phone did not respond")
    except asyncio.CancelledError:
        raise ValueError("Permission revoked")
    finally:
        if pending in s.waiting: s.waiting.remove(pending)
        s.inflight.pop(pending.request_id,None)
    if live() is not s or file_id not in s.files:
        raise ValueError("Permission revoked")
    return {"file_id":file_id,"name":s.files[file_id].name,"text":raw.decode("utf-8")}

@app.post("/mcp")
async def mcp(request:Request,authorization:str|None=Header(default=None)):
    from oauth_support import authenticate_mcp
    authenticate_mcp(authorization)
    if "application/json" not in request.headers.get("content-type",""):
        raise HTTPException(415,"JSON required")
    if int(request.headers.get("content-length","0") or 0)>400000:
        raise HTTPException(413,"Request too large")
    try: obj=await request.json()
    except Exception: raise HTTPException(400,"Bad JSON")
    if not isinstance(obj,dict) or obj.get("jsonrpc")!="2.0":
        raise HTTPException(400,"Bad JSON-RPC")
    method=obj.get("method")
    reqid=obj.get("id")
    if method=="notifications/initialized" and reqid is None:
        return JSONResponse({},status_code=202)
    if method=="ping" and reqid is not None:
        out={}
    elif method=="initialize" and reqid is not None:
        requested=(obj.get("params") or {}).get("protocolVersion")
        ver=requested if requested in ("2025-03-26","2025-06-18","2025-11-25") else "2025-06-18"
        out={"protocolVersion":ver,"capabilities":{"tools":{"listChanged":False}},"serverInfo":{"name":"xiaomi-phone-bridge","version":"0.2.0"}}
    elif method=="tools/list" and reqid is not None:
        out={"tools":TOOLS}
    elif method=="tools/call" and reqid is not None:
        params=obj.get("params") or {}
        try:
            value=await execute_tool(params.get("name"),params.get("arguments") or {})
            out={"content":[{"type":"text","text":json.dumps(value,ensure_ascii=False)}],"structuredContent":value,"isError":False}
        except (ValueError,TypeError) as err:
            out={"content":[{"type":"text","text":str(err)}],"isError":True}
    else:
        return {"jsonrpc":"2.0","id":reqid,"error":{"code":-32601,"message":"Method not found"}}
    return {"jsonrpc":"2.0","id":reqid,"result":out}

from oauth_support import setup as setup_oauth
setup_oauth(app)
