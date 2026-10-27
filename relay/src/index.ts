import { Box, CREATES_PER_HOUR, Limiter, HOUR, type Result } from "./box";
import { BOX_ID, HASH, HttpError, WIRE, bearer, error, html, json, onlyKeys, readJson, redirect, sha256Hex } from "./http";
import { page } from "./page";

export { Box, Limiter };

export interface Env {
  BOX: DurableObjectNamespace<Box>;
  LIMITER: DurableObjectNamespace<Limiter>;
}

const BOX_PATH = /^\/v1\/box\/([^/]+)(\/send|\/msg)?$/;

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    try {
      return await route(request, env);
    } catch (e) {
      if (e instanceof HttpError) return error(e.status, e.code);
      // Never echo the error: it could carry request content.
      return error(500, "internal");
    }
  },
} satisfies ExportedHandler<Env>;

async function route(request: Request, env: Env): Promise<Response> {
  const url = new URL(request.url);
  const path = url.pathname;

  if (path === "/f") return redirect("/f/");
  if (path === "/f/") {
    if (request.method !== "GET" && request.method !== "HEAD") return error(405, "method");
    const { body, csp } = await page();
    return html(body, csp);
  }
  if (!path.startsWith("/v1/")) return error(404, "not_found");

  // The API serves the TV (no Origin) and the page on this same origin. Any other origin is refused,
  // and no CORS headers are ever sent, so browsers on other sites cannot read a reply either.
  const origin = request.headers.get("Origin");
  if (origin !== null && origin !== url.origin) return error(403, "origin");
  if (request.method === "OPTIONS") return error(403, "origin");

  if (path === "/v1/box") {
    if (request.method !== "POST") return error(405, "method");
    return createBox(request, env);
  }

  const match = BOX_PATH.exec(path);
  if (!match) return error(404, "not_found");
  const boxId = match[1];
  if (!BOX_ID.test(boxId)) return error(404, "not_found");
  const box = env.BOX.get(env.BOX.idFromName(boxId));
  const tail = match[2] ?? "";
  const method = request.method;

  if (tail === "" && method === "DELETE") return reply(await box.destroy(await tokenHash(request)));
  if (tail === "/send" && method === "PUT") {
    const hash = await tokenHash(request);
    const body = await readJson(request);
    onlyKeys(body, ["sendHash"]);
    return reply(await box.rotate(hash, hashField(body.sendHash)));
  }
  if (tail === "/msg" && method === "POST") {
    const hash = await tokenHash(request);
    const body = await readJson(request);
    onlyKeys(body, ["c"]);
    if (typeof body.c !== "string" || !WIRE.test(body.c)) return error(400, "bad_fields");
    return reply(await box.send(hash, body.c));
  }
  if (tail === "/msg" && method === "GET") {
    const hash = await tokenHash(request);
    return reply(await box.list(hash, seqParam(url, "after")));
  }
  if (tail === "/msg" && method === "DELETE") {
    const hash = await tokenHash(request);
    return reply(await box.ack(hash, seqParam(url, "upto")));
  }
  return error(405, "method");
}

async function createBox(request: Request, env: Env): Promise<Response> {
  const body = await readJson(request);
  onlyKeys(body, ["boxId", "readHash", "sendHash"]);
  if (typeof body.boxId !== "string" || !BOX_ID.test(body.boxId)) return error(400, "bad_fields");
  const readHash = hashField(body.readHash);
  const sendHash = hashField(body.sendHash);
  if (readHash === sendHash) return error(400, "bad_fields");

  // Keyed by a hash of the address: the limiter never stores the address itself.
  const ip = request.headers.get("CF-Connecting-IP") ?? "local";
  const limiter = env.LIMITER.get(env.LIMITER.idFromName(await sha256Hex("create:" + ip)));
  if (!(await limiter.take(CREATES_PER_HOUR, HOUR))) return error(429, "rate_limited");

  const box = env.BOX.get(env.BOX.idFromName(body.boxId));
  return reply(await box.create(readHash, sendHash));
}

/** The presented bearer token, hashed here so the Durable Object only ever sees the hash. */
async function tokenHash(request: Request): Promise<string> {
  return sha256Hex(bearer(request));
}

function hashField(value: unknown): string {
  if (typeof value !== "string" || !HASH.test(value)) throw new HttpError(400, "bad_fields");
  return value;
}

function seqParam(url: URL, name: string): number {
  const raw = url.searchParams.get(name) ?? "0";
  if (!/^\d{1,15}$/.test(raw)) throw new HttpError(400, "bad_query");
  return Number(raw);
}

function reply(result: Result): Response {
  return json(result.body, result.status);
}
