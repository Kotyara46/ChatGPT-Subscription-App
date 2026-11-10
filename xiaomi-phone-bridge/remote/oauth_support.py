"""Minimal single-owner OAuth 2.1 (DCR + PKCE) for ChatGPT MCP.

All credentials remain in Render environment variables. Client credentials are
public; only the owner may approve the browser authorization request.
"""
import base64
import hashlib
import hmac
import html
import json
import os
import re
import secrets
import time
from urllib.parse import urlencode, parse_qs, urlsplit, urlunsplit
from fastapi import HTTPException, Request
from fastapi.responses import HTMLResponse, JSONResponse, RedirectResponse

ORIGIN=os.environ.get("PHONE_BRIDGE_PUBLIC_ORIGIN","https://xiaomi-phone-bridge.onrender.com").rstrip("/")
RESOURCE=ORIGIN+"/mcp"
SECRET=os.environ["PHONE_BRIDGE_MCP_TOKEN"].encode("utf-8")
SCOPES="files:read"
STABLE_CALLBACK="https://chatgpt.com/connector_platform_oauth_redirect"
PENDING={}
LOGIN_ATTEMPTS=[]
CALLBACK_RE=re.compile(r"^https://chatgpt\.com/connector/oauth/[a-zA-Z0-9_-]{6,200}$")
PKCE_RE=re.compile(r"^[a-zA-Z0-9_-]{43,128}$")

def b64(data):
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode("ascii")

def unb64(data):
    return base64.urlsafe_b64decode(data+"="*((-len(data))%4))

def signed(payload):
    blob=b64(json.dumps(payload,sort_keys=True,separators=(",",":")).encode("utf-8"))
    sig=b64(hmac.new(SECRET,blob.encode("ascii"),hashlib.sha256).digest())
    return blob+"."+sig

def verify(raw,kind):
    try:
        blob,sig=raw.split(".",1)
        want=b64(hmac.new(SECRET,blob.encode("ascii"),hashlib.sha256).digest())
        if not hmac.compare_digest(sig,want): return None
        p=json.loads(unb64(blob))
        if p.get("typ")!=kind or int(p.get("exp",0))<=time.time() or p.get("aud") not in (ORIGIN,RESOURCE): return None
        if p.get("scope")!=SCOPES: return None
        return p
    except (ValueError,UnicodeError,TypeError,KeyError):
        return None

def token(kind,client_id,resource,ttl):
    return signed({"typ":kind,"sub":"phone-owner","cid":client_id,"aud":resource,"scope":SCOPES,
                   "exp":int(time.time())+ttl,"nonce":secrets.token_hex(16)})

def callback_allowed(uri):
    return uri==STABLE_CALLBACK or bool(CALLBACK_RE.fullmatch(uri))

def client_id_for(redirect):
    return "dcr_"+b64(hmac.new(SECRET,("client:"+redirect).encode(),hashlib.sha256).digest())

def check_client(cid,redirect):
    return callback_allowed(redirect) and isinstance(cid,str) and hmac.compare_digest(cid,client_id_for(redirect))

def check_resource(r):
    return r in (ORIGIN,RESOURCE)

def challenge():
    return f'Bearer resource_metadata="{ORIGIN}/.well-known/oauth-protected-resource", error="invalid_token", error_description="Please sign in to Xiaomi Phone Bridge"'

def authenticate_mcp(authorization):
    if not isinstance(authorization,str) or not authorization.startswith("Bearer "):
        raise HTTPException(status_code=401,detail="Authentication required",headers={"WWW-Authenticate":challenge()})
    raw=authorization[7:]
    # Preserve the legacy private MCP key for existing diagnostics.
    if hmac.compare_digest(raw,SECRET.decode("utf-8")): return
    if verify(raw,"access"): return
    raise HTTPException(status_code=401,detail="Invalid or expired token",headers={"WWW-Authenticate":challenge()})

def form_body(raw):
    if len(raw)>8192: raise HTTPException(413,"Too large")
    vals=parse_qs(raw.decode("utf-8"),keep_blank_values=True)
    return {k:v[0] for k,v in vals.items() if len(v)==1}

def setup(app):
    @app.get("/.well-known/oauth-protected-resource")
    @app.get("/.well-known/oauth-protected-resource/mcp")
    def protected_resource():
        return {"resource":RESOURCE,"authorization_servers":[ORIGIN],"scopes_supported":[SCOPES],
                "bearer_methods_supported":["header"]}

    @app.get("/.well-known/oauth-authorization-server")
    def authorization_server():
        return {"issuer":ORIGIN,"authorization_endpoint":ORIGIN+"/oauth/authorize",
                "token_endpoint":ORIGIN+"/oauth/token","registration_endpoint":ORIGIN+"/oauth/register",
                "response_types_supported":["code"],
                "grant_types_supported":["authorization_code","refresh_token"],
                "token_endpoint_auth_methods_supported":["none"],
                "code_challenge_methods_supported":["S256"],
                "scopes_supported":[SCOPES],
                "authorization_response_iss_parameter_supported":True}

    @app.post("/oauth/register")
    async def register(request:Request):
        body=await request.body()
        if len(body)>8192: raise HTTPException(413,"Too large")
        try: obj=json.loads(body)
        except ValueError: raise HTTPException(400,"Invalid registration")
        redirects=obj.get("redirect_uris") or []
        if not isinstance(redirects,list) or len(redirects)!=1 or not callback_allowed(redirects[0]):
            raise HTTPException(400,"Only the ChatGPT callback can register")
        if obj.get("token_endpoint_auth_method","none")!="none":
            raise HTTPException(400,"Public PKCE clients only")
        uri=redirects[0]
        return {"client_id":client_id_for(uri),"redirect_uris":[uri],
                "client_name":"ChatGPT Xiaomi Phone Bridge",
                "grant_types":["authorization_code","refresh_token"],
                "response_types":["code"],"token_endpoint_auth_method":"none"}

    @app.get("/oauth/authorize")
    def authorize(response_type:str="",client_id:str="",redirect_uri:str="",state:str="",
                  code_challenge:str="",code_challenge_method:str="",scope:str=SCOPES,
                  resource:str=RESOURCE):
        if (response_type!="code" or not check_client(client_id,redirect_uri) or
            not PKCE_RE.fullmatch(code_challenge) or code_challenge_method!="S256" or
            scope.strip()!=SCOPES or not check_resource(resource) or len(state)>500):
            raise HTTPException(400,"Invalid OAuth authorization request")
        now=time.monotonic()
        for key in list(PENDING):
            if PENDING[key]["until"]<now:PENDING.pop(key,None)
        if len(PENDING)>100: raise HTTPException(429,"Too many pending authorizations")
        nonce=secrets.token_urlsafe(32)
        PENDING[nonce]={"until":now+300,"client_id":client_id,"redirect_uri":redirect_uri,
                        "state":state,"challenge":code_challenge,"resource":resource,"type":"transaction"}
        page=('<!doctype html><html lang="ru"><meta name="viewport" content="width=device-width,initial-scale=1">'
              '<meta name="referrer" content="no-referrer"><title>Xiaomi Phone Bridge</title>'
              '<body style="font:16px sans-serif;max-width:450px;margin:3rem auto;padding:1rem">'
              '<h2>Подключить Xiaomi к ChatGPT</h2><p>Разрешить ChatGPT читать только файлы, '
              'выбранные в приложении, пока доступ включён?</p>'
              '<form method="post" action="/oauth/authorize">'
              '<input type="hidden" name="nonce" value="'+html.escape(nonce,quote=True)+'">'
              '<label>Секрет ChatGPT (MCP-токен)<input name="passphrase" type="password" '
              'autocomplete="off" required style="display:block;width:100%;padding:12px;margin:12px 0"></label>'
              '<button type="submit" style="padding:12px">Разрешить</button></form>'
              '<p>Не вводи ключ устройства. Доступ отключается на Xiaomi.</p></body></html>')
        return HTMLResponse(page,headers={"Cache-Control":"no-store","Referrer-Policy":"no-referrer","X-Frame-Options":"DENY"})

    @app.post("/oauth/authorize")
    async def approve(request:Request):
        form=form_body(await request.body())
        nonce=form.get("nonce","")
        transaction=PENDING.pop(nonce,None)
        if not transaction or transaction["type"]!="transaction" or transaction["until"]<time.monotonic():
            raise HTTPException(400,"Expired authorization")
        now=time.monotonic()
        LOGIN_ATTEMPTS[:]=[x for x in LOGIN_ATTEMPTS if now-x<300]
        if len(LOGIN_ATTEMPTS)>=10: raise HTTPException(429,"Too many attempts")
        LOGIN_ATTEMPTS.append(now)
        pwd=form.get("passphrase","")
        if not hmac.compare_digest(pwd,SECRET.decode("utf-8")):
            raise HTTPException(403,"Incorrect authorization secret")
        code=secrets.token_urlsafe(32)
        PENDING[code]={"until":time.monotonic()+120,**{k:v for k,v in transaction.items() if k!="type"},
                       "type":"code"}
        params={"code":code,"iss":ORIGIN}
        if transaction["state"]:params["state"]=transaction["state"]
        sep="&" if "?" in transaction["redirect_uri"] else "?"
        return RedirectResponse(transaction["redirect_uri"]+sep+urlencode(params),status_code=303,
                                headers={"Cache-Control":"no-store","Referrer-Policy":"no-referrer"})

    @app.post("/oauth/token")
    async def token_endpoint(request:Request):
        form=form_body(await request.body())
        grant=form.get("grant_type")
        cid=form.get("client_id","")
        resource=form.get("resource",RESOURCE)
        if not check_resource(resource):
            return JSONResponse({"error":"invalid_target"},status_code=400,headers={"Cache-Control":"no-store"})
        if grant=="authorization_code":
            code=form.get("code","")
            data=PENDING.pop(code,None)
            redirect=form.get("redirect_uri","")
            verifier=form.get("code_verifier","")
            if (not data or data["type"]!="code" or data["until"]<time.monotonic() or
                not check_client(cid,redirect) or data["client_id"]!=cid or data["redirect_uri"]!=redirect or
                data["resource"]!=resource or not 43<=len(verifier)<=128):
                return JSONResponse({"error":"invalid_grant"},status_code=400)
            check=b64(hashlib.sha256(verifier.encode("utf-8")).digest())
            if not hmac.compare_digest(check,data["challenge"]):
                return JSONResponse({"error":"invalid_grant"},status_code=400)
        elif grant=="refresh_token":
            data=verify(form.get("refresh_token",""),"refresh")
            if not data or not data.get("cid")==cid or data["aud"]!=resource:
                return JSONResponse({"error":"invalid_grant"},status_code=400)
        else:
            return JSONResponse({"error":"unsupported_grant_type"},status_code=400)
        return JSONResponse({"access_token":token("access",cid,resource,3600),
                             "refresh_token":token("refresh",cid,resource,2592000),
                             "token_type":"Bearer","expires_in":3600,"scope":SCOPES},
                            headers={"Cache-Control":"no-store","Pragma":"no-cache"})
