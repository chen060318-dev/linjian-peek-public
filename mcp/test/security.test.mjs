import { after, before, test } from "node:test";
import assert from "node:assert/strict";
import http from "node:http";
import { spawn } from "node:child_process";
import { generateKeyPair, exportJWK, SignJWT } from "jose";

const appPort = 42000 + Math.floor(Math.random() * 1000);
const issuerPort = appPort + 1001;
const audience = `http://127.0.0.1:${appPort}`;
const issuer = `http://127.0.0.1:${issuerPort}/`;
let issuerServer;
let child;
let privateKey;
const kid = "security-test-key";

async function token(scope = "phone.read", permissions = undefined) {
  return new SignJWT({
    scope,
    name: "Security Test",
    ...(permissions ? { permissions } : {})
  })
    .setProtectedHeader({ alg: "RS256", kid })
    .setIssuer(issuer)
    .setAudience(audience)
    .setSubject("auth0|security-test-user")
    .setIssuedAt()
    .setExpirationTime("5m")
    .sign(privateKey);
}

async function waitForHealth() {
  for (let i = 0; i < 50; i++) {
    try {
      const response = await fetch(`${audience}/health`);
      if (response.ok) return;
    } catch {}
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
  throw new Error("MCP test server did not start");
}

before(async () => {
  const pair = await generateKeyPair("RS256");
  privateKey = pair.privateKey;
  const publicJwk = await exportJWK(pair.publicKey);
  publicJwk.kid = kid;
  publicJwk.alg = "RS256";
  publicJwk.use = "sig";

  issuerServer = http.createServer((req, res) => {
    if (req.url === "/.well-known/jwks.json") {
      res.writeHead(200, { "content-type": "application/json" });
      res.end(JSON.stringify({ keys: [publicJwk] }));
      return;
    }
    res.writeHead(404).end();
  });
  await new Promise((resolve) => issuerServer.listen(issuerPort, "127.0.0.1", resolve));

  child = spawn(process.execPath, ["server.js"], {
    cwd: new URL("..", import.meta.url),
    stdio: ["ignore", "pipe", "pipe"],
    env: {
      ...process.env,
      PORT: String(appPort),
      MCP_PUBLIC_BASE_URL: audience,
      MCP_OAUTH_ISSUER: issuer,
      MCP_OAUTH_AUDIENCE: audience,
      MCP_ENABLE_SENSITIVE_TOOLS: "false",
      MCP_ENABLE_COMPANION_TOOLS: "true",
      MCP_ENABLE_WALLET_TOOLS: "false",
      LINJIAN_URL: "http://127.0.0.1:9",
      LINJIAN_TOKEN: "phone-sync-token-used-only-between-services"
    }
  });
  await waitForHealth();
});

after(async () => {
  child?.kill("SIGTERM");
  await new Promise((resolve) => issuerServer?.close(resolve));
});

test("protected resource metadata is public and advertises OAuth scopes", async () => {
  const response = await fetch(`${audience}/.well-known/oauth-protected-resource`);
  assert.equal(response.status, 200);
  const body = await response.json();
  assert.equal(body.resource, audience);
  assert.deepEqual(body.authorization_servers, [issuer]);
  assert.ok(body.scopes_supported.includes("phone.read"));
});

test("all MCP transports reject missing credentials", async () => {
  for (const [method, path] of [["POST", "/mcp"], ["GET", "/sse"], ["POST", "/messages?sessionId=fake"], ["POST", "/mcp-wallet"]]) {
    const response = await fetch(audience + path, {
      method,
      headers: method === "POST" ? { "content-type": "application/json" } : {},
      body: method === "POST" ? "{}" : undefined
    });
    assert.equal(response.status, 401, `${method} ${path}`);
    assert.match(response.headers.get("www-authenticate") || "", /oauth-protected-resource/);
  }
});

test("invalid bearer token is rejected", async () => {
  const response = await fetch(`${audience}/mcp`, {
    method: "POST",
    headers: { authorization: "Bearer invalid", "content-type": "application/json" },
    body: "{}"
  });
  assert.equal(response.status, 401);
});

test("valid read token can initialize the MCP server", async () => {
  const accessToken = await token("phone.read");
  const response = await fetch(`${audience}/mcp`, {
    method: "POST",
    headers: {
      authorization: `Bearer ${accessToken}`,
      "content-type": "application/json",
      accept: "application/json, text/event-stream"
    },
    body: JSON.stringify({
      jsonrpc: "2.0",
      id: 1,
      method: "initialize",
      params: {
        protocolVersion: "2025-06-18",
        capabilities: {},
        clientInfo: { name: "security-test", version: "1.0.0" }
      }
    })
  });
  assert.equal(response.status, 200);
  assert.doesNotMatch(await response.text(), /send_phone_command|take_screenshot|input_text/);
});

test("valid read token only exposes the read-only tool set", async () => {
  const accessToken = await token("phone.read");
  const response = await fetch(`${audience}/mcp`, {
    method: "POST",
    headers: {
      authorization: `Bearer ${accessToken}`,
      "content-type": "application/json",
      accept: "application/json, text/event-stream"
    },
    body: JSON.stringify({ jsonrpc: "2.0", id: 2, method: "tools/list", params: {} })
  });
  assert.equal(response.status, 200);
  const body = await response.text();
  assert.match(body, /get_phone_state/);
  assert.match(body, /get_app_usage/);
  assert.match(body, /get_profile/);
  assert.doesNotMatch(body, /send_phone_command|take_screenshot|input_text|open_app/);
});

test("wallet and control surface remains disabled with valid control scope", async () => {
  const accessToken = await token("phone.read phone.control");
  const response = await fetch(`${audience}/mcp-wallet`, {
    method: "POST",
    headers: { authorization: `Bearer ${accessToken}`, "content-type": "application/json" },
    body: "{}"
  });
  assert.equal(response.status, 404);
});


test("scoped control token exposes companion diary and wallet but not phone control", async () => {
  const accessToken = await token("phone.read phone.control");
  const response = await fetch(`${audience}/mcp`, {
    method: "POST",
    headers: {
      authorization: `Bearer ${accessToken}`,
      "content-type": "application/json",
      accept: "application/json, text/event-stream"
    },
    body: JSON.stringify({ jsonrpc: "2.0", id: 7, method: "tools/list", params: {} })
  });
  assert.equal(response.status, 200);
  const body = await response.text();
  for (const enabled of ["get_profile", "write_diary_entry", "get_companion_actions", "list_wallet_approvals", "decide_wallet_approval", "get_guardian_calendar"]) {
    assert.match(body, new RegExp(enabled), `expected ${enabled}`);
  }
  for (const disabled of ["peek_screen", "get_screen_nodes", "tap_text", "input_text", "send_phone_command", "open_app", "delete_diary_entry", "delete_wallet_record", "prepare_takeout_checkout", "wallet_takeout_action", "start_focus_mode"]) {
    assert.doesNotMatch(body, new RegExp(`"name":"${disabled}"`), `unexpected ${disabled}`);
  }
});

test("Auth0 RBAC permissions claim unlocks companion mode when scope remains read-only", async () => {
  const accessToken = await token("phone.read", ["phone.read", "phone.control"]);
  const response = await fetch(`${audience}/mcp`, {
    method: "POST",
    headers: {
      authorization: `Bearer ${accessToken}`,
      "content-type": "application/json",
      accept: "application/json, text/event-stream"
    },
    body: JSON.stringify({ jsonrpc: "2.0", id: 8, method: "tools/list", params: {} })
  });
  assert.equal(response.status, 200);
  const body = await response.text();
  for (const enabled of ["get_profile", "write_diary_entry", "get_companion_actions", "list_wallet_approvals", "decide_wallet_approval"]) {
    assert.match(body, new RegExp(enabled), `expected ${enabled}`);
  }
  for (const disabled of ["peek_screen", "tap_text", "input_text", "send_phone_command", "open_app"]) {
    assert.doesNotMatch(body, new RegExp(`"name":"${disabled}"`), `unexpected ${disabled}`);
  }
});

test("get_profile shows token scopes without exposing bearer token", async () => {
  for (const [scope, expectedMode] of [
    ["phone.read", "read_only"],
    ["phone.read phone.control", "companion"]
  ]) {
    const accessToken = await token(scope);
    const response = await fetch(`${audience}/mcp`, {
      method: "POST",
      headers: {
        authorization: `Bearer ${accessToken}`,
        "content-type": "application/json",
        accept: "application/json, text/event-stream"
      },
      body: JSON.stringify({
        jsonrpc: "2.0", id: 91, method: "tools/call",
        params: { name: "get_profile", arguments: {} }
      })
    });
    assert.equal(response.status, 200);
    const body = await response.text();
    assert.match(body, /granted_scopes/);
    assert.match(body, /phone\.read/);
    assert.match(body, new RegExp(expectedMode));
    if (expectedMode === "companion") assert.match(body, /phone\.control/);
    else assert.doesNotMatch(body, /phone\.control/);
    assert.ok(!body.includes(accessToken), "token must not be echoed");
  }
});

test("read-only OAuth client can discover companion step-up without access to write tools", async () => {
  const accessToken = await token("phone.read");
  const headers = {
    authorization: `Bearer ${accessToken}`,
    "content-type": "application/json",
    accept: "application/json, text/event-stream"
  };
  const list = await fetch(`${audience}/mcp`, {
    method: "POST", headers,
    body: JSON.stringify({ jsonrpc: "2.0", id: 90, method: "tools/list", params: {} })
  });
  assert.equal(list.status, 200);
  const text = await list.text();
  assert.match(text, /request_companion_access/);
  assert.match(text, /phone.control/);
  for (const unavailable of ["write_diary_entry", "decide_wallet_approval", "tap_text", "peek_screen"]) {
    assert.doesNotMatch(text, new RegExp(`"name":"${unavailable}"`));
  }
  const call = await fetch(`${audience}/mcp`, {
    method: "POST", headers,
    body: JSON.stringify({
      jsonrpc: "2.0", id: 91, method: "tools/call",
      params: { name: "request_companion_access", arguments: {} }
    })
  });
  assert.equal(call.status, 200);
  const result = await call.text();
  assert.ok(result.includes("mcp/www_authenticate"));
  assert.match(result, /insufficient_scope/);
  assert.match(result, /phone.control/);
});
