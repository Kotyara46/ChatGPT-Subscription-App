import os, asyncio, base64, uuid
os.environ.setdefault("PHONE_BRIDGE_DEVICE_TOKEN","device-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
os.environ.setdefault("PHONE_BRIDGE_MCP_TOKEN","plugin-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
import pytest
from httpx import AsyncClient,ASGITransport
from app import app
from owner_panel import auth_cookie,COOKIE
D={"Authorization":"Bearer "+os.environ["PHONE_BRIDGE_DEVICE_TOKEN"]}

@pytest.mark.asyncio
async def test_owner_login_file_read_and_disconnect():
    async with AsyncClient(transport=ASGITransport(app=app),base_url="https://local.test",follow_redirects=False) as c:
        req=await c.get("/owner")
        assert req.status_code==200
        assert "Вход владельца" in req.text
        assert (await c.post("/owner/read",data={"file_id":"1"})).status_code==401
        bad=await c.post("/owner/login",data={"password":"bad"})
        assert "Неправильный секрет" in bad.text
        correct=await c.post("/owner/login",data={"password":os.environ["PHONE_BRIDGE_MCP_TOKEN"]})
        assert correct.status_code==303
        assert "httponly" in correct.headers["set-cookie"].lower()
        assert "secure" in correct.headers["set-cookie"].lower()
        csrf=auth_cookie(c.cookies.get(COOKIE))
        assert csrf
        assert (await c.post("/owner/logout",data={"csrf":"invalid"})).status_code==403
        file_id=str(uuid.uuid4())
        entry={"id":file_id,"name":"test <safe>.txt","size":7,"mime":"text/plain"}
        sid=(await c.post("/device/start",headers=D,json={"files":[entry]})).json()["session_id"]
        listing=await c.get("/owner")
        assert listing.status_code==200
        assert "test &lt;safe&gt;.txt" in listing.text
        assert "Прочитать содержимое" in listing.text
        assert "<safe>" not in listing.text
        reading=asyncio.create_task(c.post("/owner/read",data={"file_id":file_id,"csrf":csrf}))
        await asyncio.sleep(.05)
        poll=(await c.post("/device/poll",headers=D,json={"session_id":sid,"files":[entry]})).json()
        cmd=poll["command"]
        assert cmd["type"]=="read_text"
        await c.post("/device/reply",headers=D,json={
            "session_id":sid,"request_id":cmd["request_id"],"file_id":file_id,
            "base64_data":base64.b64encode("Привет!".encode()).decode()})
        read=await reading
        assert "Привет!" in read.text
        assert "&lt;safe&gt;" in read.text
        await c.post("/device/stop",headers=D,json={"session_id":sid})
        assert "Телефон сейчас отключён" in (await c.get("/owner")).text
        out=await c.post("/owner/logout",data={"csrf":csrf})
        assert out.status_code==303
        assert (await c.post("/owner/read",data={"file_id":file_id,"csrf":csrf})).status_code==401
