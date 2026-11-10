import hashlib
import hmac
import html
import os
import secrets
import time
from urllib.parse import parse_qs
from fastapi import HTTPException, Request
from fastapi.responses import HTMLResponse, RedirectResponse

SECRET = os.environ["PHONE_BRIDGE_MCP_TOKEN"].encode()
COOKIE = "xb_owner"
KEY = hmac.new(SECRET, b"phone-owner-web-v1", hashlib.sha256).digest()
ATTEMPTS = {}

def esc(x):
    return html.escape(str(x), quote=True)

def cookie_sign(payload):
    return hmac.new(KEY, payload.encode(), hashlib.sha256).hexdigest()

def auth_cookie(value):
    try:
        payload, sig = value.rsplit(".",1)
        if not hmac.compare_digest(cookie_sign(payload),sig):
            return None
        until, csrf = payload.split(":",1)
        return csrf if int(until)>time.time() and len(csrf)>20 else None
    except (ValueError,TypeError):
        return None

def secure(body):
    return HTMLResponse(
        '<!doctype html><html lang="ru"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"></head>'
        '<body style="font:16px system-ui;background:#101727;color:#f6f9ff;max-width:680px;margin:20px auto;padding:16px">'
        '<h1>Xiaomi Phone Bridge</h1>'+body+
        '<p style="font-size:13px;color:#9ca3af">Доступ только к выбранным текстовым файлам, пока разрешение включено на Xiaomi.</p></body></html>',
        headers={"Cache-Control":"no-store","Referrer-Policy":"no-referrer","X-Frame-Options":"DENY",
                 "Content-Security-Policy":"default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'"})

async def parse_form(req):
    data=await req.body()
    if len(data)>400000:raise HTTPException(413)
    if "application/x-www-form-urlencoded" not in req.headers.get("content-type",""):raise HTTPException(415)
    try: return {k:v[0] for k,v in parse_qs(data.decode(),keep_blank_values=True).items() if len(v)==1}
    except (UnicodeDecodeError,ValueError):raise HTTPException(400)

def owner(req):
    key=auth_cookie(req.cookies.get(COOKIE,""))
    if not key:raise HTTPException(401,"Сначала войди на /owner")
    return key

def csrf_check(data,csrf):
    if not hmac.compare_digest(data.get("csrf",""),csrf):raise HTTPException(403)

def button(label):
    return '<button style="padding:12px;border-radius:8px" type="submit">'+esc(label)+'</button>'

def status_page(csrf,message=""):
    from app import live
    s=live()
    content='<p>'+esc(message)+'</p>' if message else ''
    if s is None:
        content+='<h2>Телефон сейчас отключён</h2><p>Включи сеанс в приложении Xiaomi.</p>'
    else:
        content+='<h2>Телефон подключён</h2><p>Разрешено файлов: '+str(len(s.files))+'</p>'
        for f in s.files.values():
            content+='<form method="post" action="/owner/read"><input type="hidden" name="csrf" value="'+esc(csrf)+'">'
            content+='<input type="hidden" name="file_id" value="'+esc(f.id)+'"><p>'+esc(f.name)+'</p>'+button('Прочитать')+'</form>'
    content+='<p><a href="/owner" style="color:#93c5fd">Обновить статус</a></p>'
    content+='<form method="post" action="/owner/logout"><input type="hidden" name="csrf" value="'+esc(csrf)+'">'+button('Выйти')+'</form>'
    return secure(content)

def setup(app):
    @app.get("/owner")
    async def home(req:Request):
        csrf=auth_cookie(req.cookies.get(COOKIE,""))
        if csrf:return status_page(csrf)
        return secure('<h2>Вход владельца</h2><p>Введи второй секрет PHONE_BRIDGE_MCP_TOKEN в форму, не в чат.</p>'
                      '<form method="post" action="/owner/login"><input name="password" type="password" autocomplete="off" required>'
                      +button('Войти')+'</form>')

    @app.post("/owner/login")
    async def login(req:Request):
        data=await parse_form(req)
        ip=req.client.host if req.client else "unknown"
        now=time.monotonic()
        ATTEMPTS[ip]=[t for t in ATTEMPTS.get(ip,[]) if now-t<900]
        if len(ATTEMPTS[ip])>=7:raise HTTPException(429,"Too many attempts")
        ATTEMPTS[ip].append(now)
        if not hmac.compare_digest(data.get("password","").encode(),SECRET):
            return secure('<p>Неправильный секрет. <a href="/owner">Вернуться</a></p>')
        ATTEMPTS.pop(ip,None)
        csrf=secrets.token_urlsafe(24)
        payload=str(int(time.time())+3600)+":"+csrf
        response=RedirectResponse("/owner",status_code=303)
        response.set_cookie(COOKIE,payload+"."+cookie_sign(payload),max_age=3600,secure=True,httponly=True,samesite="lax",path="/owner")
        response.headers["Cache-Control"]="no-store"
        return response

    @app.post("/owner/logout")
    async def logout(req:Request):
        csrf=owner(req)
        csrf_check(await parse_form(req),csrf)
        response=RedirectResponse("/owner",status_code=303)
        response.delete_cookie(COOKIE,path="/owner")
        return response

    @app.post("/owner/read")
    async def read_file(req:Request):
        csrf=owner(req)
        data=await parse_form(req)
        csrf_check(data,csrf)
        from app import live,execute_tool
        s=live()
        file_id=data.get("file_id","")
        if s is None or file_id not in s.files:
            return status_page(csrf,"Файл не выбран или сеанс отключён")
        try:
            result=await execute_tool("read_selected_text_file",{"file_id":file_id})
        except ValueError as exc:
            return status_page(csrf,"Ошибка чтения: "+str(exc))
        return secure('<p><a href="/owner" style="color:#93c5fd">Назад к списку</a></p><h2>'+esc(result["name"])+'</h2>'
                      '<pre style="white-space:pre-wrap;overflow-wrap:anywhere">'+esc(result["text"])+'</pre>')
