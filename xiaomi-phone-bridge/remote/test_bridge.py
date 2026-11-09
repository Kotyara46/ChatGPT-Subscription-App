import os
os.environ["PHONE_BRIDGE_DEVICE_TOKEN"]="device-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
os.environ["PHONE_BRIDGE_MCP_TOKEN"]="plugin-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"

import asyncio, base64, uuid
import pytest
from httpx import AsyncClient, ASGITransport
from app import app

D={"Authorization":"Bearer "+os.environ["PHONE_BRIDGE_DEVICE_TOKEN"]}
M={"Authorization":"Bearer "+os.environ["PHONE_BRIDGE_MCP_TOKEN"]}
FID=str(uuid.uuid4())
CAT=[{"id":FID,"name":"hello.txt","size":11,"mime":"text/plain"}]
def rpc(method, params=None, id=1):
    return {"jsonrpc":"2.0","id":id,"method":method,"params":params or {}}

@pytest.mark.asyncio
async def test_auth_health_and_tools():
    async with AsyncClient(transport=ASGITransport(app=app),base_url="http://test") as c:
        assert (await c.get("/health")).status_code==200
        assert (await c.post("/device/start",json={"files":CAT})).status_code==401
        assert (await c.post("/mcp",json=rpc("tools/list"))).status_code==401
        init=await c.post("/mcp",headers=M,json=rpc("initialize",{"protocolVersion":"2025-06-18"}))
        assert init.json()["result"]["serverInfo"]["name"]=="xiaomi-phone-bridge"
        tools=(await c.post("/mcp",headers=M,json=rpc("tools/list"))).json()["result"]["tools"]
        assert {x["name"] for x in tools}=={"phone_status","list_selected_files","read_selected_text_file"}

@pytest.mark.asyncio
async def test_phone_file_round_trip_and_revocation():
    async with AsyncClient(transport=ASGITransport(app=app),base_url="http://test") as c:
        sid=(await c.post("/device/start",headers=D,json={"files":CAT})).json()["session_id"]
        listing=(await c.post("/mcp",headers=M,json=rpc("tools/call",{"name":"list_selected_files"}))).json()
        assert listing["result"]["structuredContent"]["files"][0]["id"]==FID
        pending=asyncio.create_task(c.post("/mcp",headers=M,json=rpc("tools/call",{"name":"read_selected_text_file","arguments":{"file_id":FID}})))
        await asyncio.sleep(.04)
        cmd=(await c.post("/device/poll",headers=D,json={"session_id":sid,"files":CAT})).json()["command"]
        assert cmd["type"]=="read_text"
        raw=base64.b64encode("привет".encode()).decode()
        assert (await c.post("/device/reply",headers=D,json={"session_id":sid,"request_id":cmd["request_id"],"file_id":FID,"base64_data":raw})).status_code==200
        output=(await pending).json()
        assert output["result"]["structuredContent"]["text"]=="привет"
        assert (await c.post("/device/stop",headers=D,json={"session_id":sid})).status_code==200
        status=(await c.post("/mcp",headers=M,json=rpc("tools/call",{"name":"phone_status"}))).json()
        assert not status["result"]["structuredContent"]["connected"]

@pytest.mark.asyncio
async def test_deselection_cancels_pending_request():
    async with AsyncClient(transport=ASGITransport(app=app),base_url="http://test") as c:
        sid=(await c.post("/device/start",headers=D,json={"files":CAT})).json()["session_id"]
        task=asyncio.create_task(c.post("/mcp",headers=M,json=rpc("tools/call",{"name":"read_selected_text_file","arguments":{"file_id":FID}})))
        await asyncio.sleep(.04)
        cmd=(await c.post("/device/poll",headers=D,json={"session_id":sid,"files":CAT})).json()["command"]
        await c.post("/device/poll",headers=D,json={"session_id":sid,"files":[]})
        out=(await task).json()
        assert out["result"]["isError"]
        late=await c.post("/device/reply",headers=D,json={"session_id":sid,"request_id":cmd["request_id"],"file_id":FID,"base64_data":"YQ=="})
        assert late.status_code==409
