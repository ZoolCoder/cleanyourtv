import { runDurableObjectAlarm, runInDurableObject } from "cloudflare:test";
import { env, exports } from "cloudflare:workers";
import { describe, expect, it } from "vitest";
import { CREATES_PER_HOUR, DAY, MESSAGE_TTL, IDLE_TTL, SENDS_PER_HOUR, type Box } from "../src/box";
import vector from "./vector.json";

const BASE = "https://relay.example";

function b64url(bytes: Uint8Array): string {
  return btoa(String.fromCharCode(...bytes)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}
const random = (n: number) => b64url(crypto.getRandomValues(new Uint8Array(n)));

async function sha256Hex(text: string): Promise<string> {
  const d = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text));
  return [...new Uint8Array(d)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

let ipCounter = 0;
/** Each test calls from its own address, so the per-address create limit does not leak between tests. */
const freshIp = () => `198.51.100.${++ipCounter}`;

interface Family {
  boxId: string;
  read: string;
  send: string;
  ip: string;
}

function call(method: string, path: string, opts: { token?: string; body?: unknown; raw?: string; headers?: Record<string, string> } = {}) {
  const headers: Record<string, string> = { ...opts.headers };
  if (opts.token) headers.Authorization = `Bearer ${opts.token}`;
  let body: string | undefined;
  if (opts.raw !== undefined) body = opts.raw;
  else if (opts.body !== undefined) body = JSON.stringify(opts.body);
  if (body !== undefined && !headers["Content-Type"]) headers["Content-Type"] = "application/json";
  return exports.default.fetch(BASE + path, { method, headers, body });
}

async function createFamily(ip = freshIp()): Promise<Family> {
  const f = { boxId: random(16), read: random(32), send: random(32), ip };
  const res = await call("POST", "/v1/box", {
    body: { boxId: f.boxId, readHash: await sha256Hex(f.read), sendHash: await sha256Hex(f.send) },
    headers: { "CF-Connecting-IP": ip },
  });
  expect(res.status).toBe(201);
  return f;
}

const wire = (n = 64) => random(n);

function boxStub(boxId: string) {
  return env.BOX.get(env.BOX.idFromName(boxId)) as DurableObjectStub<Box>;
}

describe("box creation", () => {
  it("creates a box once and refuses a duplicate with 409", async () => {
    const f = await createFamily();
    const res = await call("POST", "/v1/box", {
      body: { boxId: f.boxId, readHash: await sha256Hex(random(32)), sendHash: await sha256Hex(random(32)) },
      headers: { "CF-Connecting-IP": freshIp() },
    });
    expect(res.status).toBe(409);
    // The original tokens still work.
    expect((await call("GET", `/v1/box/${f.boxId}/msg`, { token: f.read })).status).toBe(200);
  });

  it("rejects malformed bodies", async () => {
    const ip = { "CF-Connecting-IP": freshIp() };
    const h = await sha256Hex("x");
    expect((await call("POST", "/v1/box", { body: { boxId: "short", readHash: h, sendHash: h + "" }, headers: ip })).status).toBe(400);
    expect((await call("POST", "/v1/box", { body: { boxId: random(16), readHash: h, sendHash: h }, headers: ip })).status).toBe(400);
    expect((await call("POST", "/v1/box", { body: { boxId: random(16), readHash: "zz", sendHash: h }, headers: ip })).status).toBe(400);
    expect((await call("POST", "/v1/box", {
      body: { boxId: random(16), readHash: h, sendHash: await sha256Hex("y"), extra: 1 }, headers: ip })).status).toBe(400);
    expect((await call("POST", "/v1/box", { raw: "{not json", headers: ip })).status).toBe(400);
    expect((await call("POST", "/v1/box", { raw: "[1,2]", headers: ip })).status).toBe(400);
    expect((await call("POST", "/v1/box", { raw: "{}", headers: { ...ip, "Content-Type": "text/plain" } })).status).toBe(415);
  });

  it(`allows ${CREATES_PER_HOUR} boxes an hour from one address`, async () => {
    const ip = freshIp();
    for (let i = 0; i < CREATES_PER_HOUR; i++) await createFamily(ip);
    const res = await call("POST", "/v1/box", {
      body: { boxId: random(16), readHash: await sha256Hex(random(32)), sendHash: await sha256Hex(random(32)) },
      headers: { "CF-Connecting-IP": ip },
    });
    expect(res.status).toBe(429);
    // Another address is not affected.
    await createFamily();
  });
});

describe("auth", () => {
  it("refuses a missing, malformed or wrong token, and the send token for reading", async () => {
    const f = await createFamily();
    const path = `/v1/box/${f.boxId}/msg`;
    expect((await call("GET", path)).status).toBe(401);
    expect((await call("GET", path, { headers: { Authorization: "Bearer abc" } })).status).toBe(401);
    expect((await call("GET", path, { headers: { Authorization: `Basic ${f.read}` } })).status).toBe(401);
    expect((await call("GET", path, { token: random(32) })).status).toBe(401);
    expect((await call("GET", path, { token: f.send })).status).toBe(401);
    expect((await call("DELETE", `/v1/box/${f.boxId}`, { token: f.send })).status).toBe(401);
    expect((await call("PUT", `/v1/box/${f.boxId}/send`, { token: f.send, body: { sendHash: await sha256Hex("n") } })).status).toBe(401);
  });

  it("refuses the read token for sending", async () => {
    const f = await createFamily();
    expect((await call("POST", `/v1/box/${f.boxId}/msg`, { token: f.read, body: { c: wire() } })).status).toBe(401);
  });

  it("answers an unknown box the same as a wrong token", async () => {
    const res = await call("GET", `/v1/box/${random(16)}/msg`, { token: random(32) });
    expect(res.status).toBe(401);
    expect(await res.json()).toEqual({ error: "unauthorized" });
  });

  it("refuses another origin and sends no CORS headers", async () => {
    const f = await createFamily();
    const res = await call("POST", `/v1/box/${f.boxId}/msg`, {
      token: f.send, body: { c: wire() }, headers: { Origin: "https://evil.example" } });
    expect(res.status).toBe(403);
    expect(res.headers.get("Access-Control-Allow-Origin")).toBeNull();
    expect((await call("OPTIONS", `/v1/box/${f.boxId}/msg`, { headers: { Origin: "https://evil.example" } })).status).toBe(403);
    const same = await call("POST", `/v1/box/${f.boxId}/msg`, { token: f.send, body: { c: wire() }, headers: { Origin: BASE } });
    expect(same.status).toBe(200);
  });
});

describe("messages", () => {
  it("sends, lists in order, pages, acks and deletes", async () => {
    const f = await createFamily();
    const sent: string[] = [];
    for (let i = 0; i < 3; i++) {
      const c = wire();
      sent.push(c);
      const res = await call("POST", `/v1/box/${f.boxId}/msg`, { token: f.send, body: { c } });
      expect(res.status).toBe(200);
      expect(await res.json()).toEqual({ seq: i + 1 });
    }
    const list = await call("GET", `/v1/box/${f.boxId}/msg?after=0`, { token: f.read });
    expect(list.status).toBe(200);
    const body = await list.json<{ msgs: { seq: number; c: string; at: number }[]; next: number }>();
    expect(body.msgs.map((m: { c: string }) => m.c)).toEqual(sent);
    expect(body.msgs.map((m: { seq: number }) => m.seq)).toEqual([1, 2, 3]);
    expect(body.next).toBe(3);
    expect(typeof body.msgs[0].at).toBe("number");

    const after = await (await call("GET", `/v1/box/${f.boxId}/msg?after=2`, { token: f.read })).json<{ msgs: unknown[]; next: number }>();
    expect(after.msgs).toHaveLength(1);
    const empty = await (await call("GET", `/v1/box/${f.boxId}/msg?after=3`, { token: f.read })).json<{ msgs: unknown[]; next: number }>();
    expect(empty).toEqual({ msgs: [], next: 3 });

    expect((await call("DELETE", `/v1/box/${f.boxId}/msg?upto=2`, { token: f.read })).status).toBe(200);
    const left = await (await call("GET", `/v1/box/${f.boxId}/msg`, { token: f.read })).json<{ msgs: { seq: number }[] }>();
    expect(left.msgs.map((m: { seq: number }) => m.seq)).toEqual([3]);

    expect((await call("DELETE", `/v1/box/${f.boxId}`, { token: f.read })).status).toBe(200);
    expect((await call("GET", `/v1/box/${f.boxId}/msg`, { token: f.read })).status).toBe(401);
    expect((await call("POST", `/v1/box/${f.boxId}/msg`, { token: f.send, body: { c: wire() } })).status).toBe(401);
  });

  it("lets the same box id be created again after deletion", async () => {
    const f = await createFamily();
    await call("DELETE", `/v1/box/${f.boxId}`, { token: f.read });
    const res = await call("POST", "/v1/box", {
      body: { boxId: f.boxId, readHash: await sha256Hex(random(32)), sendHash: await sha256Hex(random(32)) },
      headers: { "CF-Connecting-IP": freshIp() } });
    expect(res.status).toBe(201);
  });

  it("returns at most 50 a page", async () => {
    const f = await createFamily();
    await runInDurableObject(boxStub(f.boxId), async (_box, state) => {
      for (let i = 1; i <= 60; i++) state.storage.sql.exec("INSERT INTO msgs (seq, c, at) VALUES (?, ?, ?)", i, "x".repeat(40), Date.now());
      state.storage.sql.exec("UPDATE meta SET v = '60' WHERE k = 'seq'");
    });
    const page = await (await call("GET", `/v1/box/${f.boxId}/msg`, { token: f.read })).json<{ msgs: unknown[]; next: number }>();
    expect(page.msgs).toHaveLength(50);
    expect(page.next).toBe(50);
  });

  it("refuses a bad ciphertext and bad query numbers", async () => {
    const f = await createFamily();
    const path = `/v1/box/${f.boxId}/msg`;
    for (const c of ["", "short", "has spaces in it and is long enough to pass length", 5, null]) {
      expect((await call("POST", path, { token: f.send, body: { c } })).status).toBe(400);
    }
    expect((await call("POST", path, { token: f.send, body: { c: wire(), x: 1 } })).status).toBe(400);
    expect((await call("GET", `${path}?after=-1`, { token: f.read })).status).toBe(400);
    expect((await call("GET", `${path}?after=1e3`, { token: f.read })).status).toBe(400);
    expect((await call("DELETE", `${path}?upto=abc`, { token: f.read })).status).toBe(400);
  });

  it("refuses a body over 4 KB before reading it as JSON", async () => {
    const f = await createFamily();
    const big = JSON.stringify({ c: "A".repeat(4100) });
    const res = await call("POST", `/v1/box/${f.boxId}/msg`, { token: f.send, raw: big });
    expect(res.status).toBe(413);
    // Chunked, with no Content-Length, is cut off as it streams.
    const stream = new ReadableStream({
      start(c) { for (let i = 0; i < 10; i++) c.enqueue(new TextEncoder().encode("A".repeat(1000))); c.close(); },
    });
    const chunked = await exports.default.fetch(`${BASE}/v1/box/${f.boxId}/msg`, {
      method: "POST", body: stream, headers: { "Content-Type": "application/json", Authorization: `Bearer ${f.send}` },
    });
    expect(chunked.status).toBe(413);
  });

  it(`allows ${SENDS_PER_HOUR} sends an hour per box`, async () => {
    const f = await createFamily();
    for (let i = 0; i < SENDS_PER_HOUR; i++) {
      expect((await call("POST", `/v1/box/${f.boxId}/msg`, { token: f.send, body: { c: wire() } })).status).toBe(200);
    }
    const res = await call("POST", `/v1/box/${f.boxId}/msg`, { token: f.send, body: { c: wire() } });
    expect(res.status).toBe(429);
    // An hour later it opens again.
    await runInDurableObject(boxStub(f.boxId), async (_box, state) => {
      state.storage.sql.exec("UPDATE sent SET at = at - ?", 61 * 60 * 1000);
    });
    expect((await call("POST", `/v1/box/${f.boxId}/msg`, { token: f.send, body: { c: wire() } })).status).toBe(200);
  });
});

describe("rotation", () => {
  it("a new send token revokes the old one", async () => {
    const f = await createFamily();
    const next = random(32);
    const res = await call("PUT", `/v1/box/${f.boxId}/send`, { token: f.read, body: { sendHash: await sha256Hex(next) } });
    expect(res.status).toBe(200);
    expect((await call("POST", `/v1/box/${f.boxId}/msg`, { token: f.send, body: { c: wire() } })).status).toBe(401);
    expect((await call("POST", `/v1/box/${f.boxId}/msg`, { token: next, body: { c: wire() } })).status).toBe(200);
  });
});

describe("expiry", () => {
  it("the daily alarm drops messages older than 14 days", async () => {
    const f = await createFamily();
    await call("POST", `/v1/box/${f.boxId}/msg`, { token: f.send, body: { c: wire() } });
    await call("POST", `/v1/box/${f.boxId}/msg`, { token: f.send, body: { c: wire() } });
    const stub = boxStub(f.boxId);
    await runInDurableObject(stub, async (_box, state) => {
      state.storage.sql.exec("UPDATE msgs SET at = at - ? WHERE seq = 1", MESSAGE_TTL + DAY);
    });
    expect(await runDurableObjectAlarm(stub)).toBe(true);
    const count = await runInDurableObject(stub, async (_box, state) =>
      state.storage.sql.exec<{ n: number }>("SELECT COUNT(*) AS n FROM msgs").one().n);
    expect(count).toBe(1);
    // The alarm re-arms itself for the next day.
    const alarm = await runInDurableObject(stub, async (_box, state) => state.storage.getAlarm());
    expect(alarm).toBeGreaterThan(Date.now());
  });

  it("an expired message is not listed even before the alarm runs", async () => {
    const f = await createFamily();
    await call("POST", `/v1/box/${f.boxId}/msg`, { token: f.send, body: { c: wire() } });
    await runInDurableObject(boxStub(f.boxId), async (_box, state) => {
      state.storage.sql.exec("UPDATE msgs SET at = at - ?", MESSAGE_TTL + 1);
    });
    const body = await (await call("GET", `/v1/box/${f.boxId}/msg`, { token: f.read })).json<{ msgs: unknown[] }>();
    expect(body.msgs).toHaveLength(0);
  });

  it("deletes a box the TV has not called for 90 days", async () => {
    const f = await createFamily();
    const stub = boxStub(f.boxId);
    await runInDurableObject(stub, async (_box, state) => {
      state.storage.sql.exec("UPDATE meta SET v = ? WHERE k = 'touched'", String(Date.now() - IDLE_TTL - DAY));
    });
    expect(await runDurableObjectAlarm(stub)).toBe(true);
    expect((await call("GET", `/v1/box/${f.boxId}/msg`, { token: f.read })).status).toBe(401);
    expect(await runInDurableObject(stub, async (_box, state) => state.storage.getAlarm())).toBeNull();
  });

  it("keeps a box the TV keeps polling", async () => {
    const f = await createFamily();
    const stub = boxStub(f.boxId);
    await runInDurableObject(stub, async (_box, state) => {
      state.storage.sql.exec("UPDATE meta SET v = ? WHERE k = 'touched'", String(Date.now() - IDLE_TTL + DAY));
    });
    await call("GET", `/v1/box/${f.boxId}/msg`, { token: f.read });
    expect(await runDurableObjectAlarm(stub)).toBe(true);
    expect((await call("GET", `/v1/box/${f.boxId}/msg`, { token: f.read })).status).toBe(200);
  });
});

describe("headers and page", () => {
  it("API replies carry the security headers", async () => {
    const res = await call("GET", `/v1/box/${random(16)}/msg`);
    expect(res.headers.get("Content-Security-Policy")).toContain("default-src 'none'");
    expect(res.headers.get("Cache-Control")).toBe("no-store");
    expect(res.headers.get("Referrer-Policy")).toBe("no-referrer");
    expect(res.headers.get("X-Content-Type-Options")).toBe("nosniff");
  });

  it("serves the send page with a hash-only CSP", async () => {
    const res = await exports.default.fetch(`${BASE}/f/`);
    expect(res.status).toBe(200);
    expect(res.headers.get("Content-Type")).toContain("text/html");
    const csp = res.headers.get("Content-Security-Policy") ?? "";
    expect(csp).toMatch(/script-src 'sha256-[A-Za-z0-9+/=]+'/);
    expect(csp).toMatch(/style-src 'sha256-[A-Za-z0-9+/=]+'/);
    expect(csp).toContain("connect-src 'self'");
    expect(csp).not.toContain("unsafe");
    const html = await res.text();
    const script = html.slice(html.indexOf("<script>") + 8, html.indexOf("</script>"));
    const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(script));
    expect(csp).toContain(`'sha256-${btoa(String.fromCharCode(...new Uint8Array(digest)))}'`);
    expect((await exports.default.fetch(`${BASE}/f`, { redirect: "manual" })).status).toBe(301);
    expect((await exports.default.fetch(`${BASE}/`)).status).toBe(404);
  });
});

describe("test vector", () => {
  it("opens with WebCrypto in workerd exactly as written by Node", async () => {
    const bytes = (s: string) => Uint8Array.from(atob(s.replace(/-/g, "+").replace(/_/g, "/")), (ch) => ch.charCodeAt(0));
    const wireBytes = bytes(vector.wire);
    const key = await crypto.subtle.importKey("raw", bytes(vector.key), { name: "AES-GCM" }, false, ["decrypt"]);
    const plain = await crypto.subtle.decrypt(
      { name: "AES-GCM", iv: wireBytes.slice(0, 12), additionalData: new TextEncoder().encode(vector.boxId) },
      key, wireBytes.slice(12));
    expect(new TextDecoder().decode(plain)).toBe(vector.plaintext);
    expect(b64url(wireBytes.slice(0, 12))).toBe(vector.iv);
    // A different box id as AAD must fail.
    await expect(crypto.subtle.decrypt(
      { name: "AES-GCM", iv: wireBytes.slice(0, 12), additionalData: new TextEncoder().encode(random(16)) },
      key, wireBytes.slice(12))).rejects.toThrow();
  });
});

describe("main module", () => {
  it("exports only handlers and classes, as workerd requires", async () => {
    const main: Record<string, unknown> = await import("../src/index");
    for (const [name, value] of Object.entries(main)) {
      const ok = typeof value === "function" || (typeof value === "object" && value !== null && "fetch" in value);
      expect(ok, name).toBe(true);
    }
  });
});
