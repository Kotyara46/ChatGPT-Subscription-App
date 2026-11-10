"""OAuth DCR + PKCE with local secrets; do not expose actual Render tokens."""
import base64, hashlib, os, re
from urllib.parse import urlsplit,parse_qs
import pytest
from httpx import ASGITransport,AsyncClient
from app import app
from oauth_support import ORIGIN,RESOURCE,SCOPES,STABLE_CALLBACK

@pytest.mark.asyncio
async def test_auth_metadata_and_pkce():
    async with AsyncClient(transport=ASGITransport(app=app),base_url=ORIGIN,follow_redirects=False) as c:
        m=(await c.get("/.well-known/oauth-protected-resource")).json()
        assert m["resource"]==RESOURCE
        a=(await c.get("/.well-known/oauth-authorization-server")).json()
        assert a["code_challenge_methods_supported"]==["S256"]
        assert a["authorization_response_iss_parameter_supported"]
        challenge=await c.post("/mcp",json={"jsonrpc":"2.0","id":1,"method":"initialize"})
        assert challenge.status_code==401
        assert "resource_metadata=" in challenge.headers["WWW-Authenticate"]
        fail=await c.post("/oauth/register",json={"redirect_uris":["https://evil.example/callback"]})
        assert fail.status_code==400
        reg=(await c.post("/oauth/register",json={"redirect_uris":[STABLE_CALLBACK]})).json()
        cid=reg["client_id"]
        verifier="the-xiaomi-phone-bridge-verifier-0123456789abc"
        pkce=base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).decode().rstrip("=")
        params={"response_type":"code","client_id":cid,"redirect_uri":STABLE_CALLBACK,"state":"teststate",
                "code_challenge":pkce,"code_challenge_method":"S256","scope":SCOPES,"resource":RESOURCE}
        page=await c.get("/oauth/authorize",params=params)
        assert page.status_code==200
        nonce=re.search(r'name="nonce" value="([^"]+)"',page.text).group(1)
        login=await c.post("/oauth/authorize",data={"nonce":nonce,"passphrase":os.environ["PHONE_BRIDGE_MCP_TOKEN"]})
        assert login.status_code==303
        q=parse_qs(urlsplit(login.headers["location"]).query)
        assert q["iss"]==[ORIGIN] and q["state"]==["teststate"]
        wrong=await c.post("/oauth/token",data={"grant_type":"authorization_code","client_id":cid,
                "redirect_uri":STABLE_CALLBACK,"resource":RESOURCE,
                "code":q["code"][0],"code_verifier":"Z"*44})
        assert wrong.status_code==400
        # Reauthorize, because the OAuth code is single-use even on failure.
        page=await c.get("/oauth/authorize",params=params)
        nonce=re.search(r'name="nonce" value="([^"]+)"',page.text).group(1)
        login=await c.post("/oauth/authorize",data={"nonce":nonce,"passphrase":os.environ["PHONE_BRIDGE_MCP_TOKEN"]})
        code=parse_qs(urlsplit(login.headers["location"]).query)["code"][0]
        response=await c.post("/oauth/token",data={"grant_type":"authorization_code","client_id":cid,
                "redirect_uri":STABLE_CALLBACK,"resource":RESOURCE,"code":code,"code_verifier":verifier})
        assert response.status_code==200,response.text
        tokens=response.json()
        called=await c.post("/mcp",headers={"Authorization":"Bearer "+tokens["access_token"]},
                json={"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}})
        assert called.status_code==200
        assert len(called.json()["result"]["tools"])==3
        refreshed=await c.post("/oauth/token",data={"grant_type":"refresh_token","client_id":cid,
                        "resource":RESOURCE,"refresh_token":tokens["refresh_token"]})
        assert refreshed.status_code==200
        bad=(await c.post("/mcp",headers={"Authorization":"Bearer invalid"},
                json={"jsonrpc":"2.0","id":2,"method":"tools/list"}))
        assert bad.status_code==401
