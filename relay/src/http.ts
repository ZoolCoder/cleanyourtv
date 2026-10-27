// Responses and request checks shared by the router and the Durable Objects.

export const MAX_BODY = 4096;

const BASE_HEADERS: Record<string, string> = {
  "Cache-Control": "no-store",
  "Referrer-Policy": "no-referrer",
  "X-Content-Type-Options": "nosniff",
  "X-Frame-Options": "DENY",
  "Strict-Transport-Security": "max-age=31536000",
  "Cross-Origin-Resource-Policy": "same-origin",
  "Permissions-Policy": "camera=(), microphone=(), geolocation=(), interest-cohort=()",
};

const API_CSP = "default-src 'none'; frame-ancestors 'none'";

export class HttpError extends Error {
  constructor(readonly status: number, readonly code: string) {
    super(code);
  }
}

export function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...BASE_HEADERS, "Content-Security-Policy": API_CSP, "Content-Type": "application/json; charset=utf-8" },
  });
}

export function error(status: number, code: string): Response {
  return json({ error: code }, status);
}

export function html(body: string, csp: string): Response {
  return new Response(body, {
    headers: { ...BASE_HEADERS, "Content-Security-Policy": csp, "Content-Type": "text/html; charset=utf-8" },
  });
}

export function redirect(location: string): Response {
  return new Response(null, { status: 301, headers: { ...BASE_HEADERS, "Content-Security-Policy": API_CSP, Location: location } });
}

/** The body as a JSON object, refusing anything over MAX_BODY before it is parsed. */
export async function readJson(request: Request): Promise<Record<string, unknown>> {
  const declared = Number(request.headers.get("Content-Length") ?? "0");
  if (declared > MAX_BODY) throw new HttpError(413, "too_large");
  const type = request.headers.get("Content-Type") ?? "";
  if (!type.toLowerCase().startsWith("application/json")) throw new HttpError(415, "json_only");
  const reader = request.body?.getReader();
  if (!reader) throw new HttpError(400, "bad_json");
  const chunks: Uint8Array[] = [];
  let size = 0;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    size += value.byteLength;
    if (size > MAX_BODY) {
      await reader.cancel();
      throw new HttpError(413, "too_large");
    }
    chunks.push(value);
  }
  const bytes = new Uint8Array(size);
  let at = 0;
  for (const c of chunks) {
    bytes.set(c, at);
    at += c.byteLength;
  }
  let parsed: unknown;
  try {
    parsed = JSON.parse(new TextDecoder("utf-8", { fatal: true, ignoreBOM: false }).decode(bytes));
  } catch {
    throw new HttpError(400, "bad_json");
  }
  if (parsed === null || typeof parsed !== "object" || Array.isArray(parsed)) throw new HttpError(400, "bad_json");
  return parsed as Record<string, unknown>;
}

/** Exactly these keys, no others. */
export function onlyKeys(body: Record<string, unknown>, keys: string[]): void {
  const got = Object.keys(body);
  if (got.length !== keys.length || !keys.every((k) => k in body)) throw new HttpError(400, "bad_fields");
}

export const BOX_ID = /^[A-Za-z0-9_-]{22}$/;
export const TOKEN = /^[A-Za-z0-9_-]{43}$/;
export const HASH = /^[0-9a-f]{64}$/;
// iv (12) + tag (16) is 28 bytes, 38 base64url characters; the body cap bounds the top.
export const WIRE = /^[A-Za-z0-9_-]{38,4000}$/;

export function bearer(request: Request): string {
  const value = request.headers.get("Authorization") ?? "";
  const match = /^Bearer ([A-Za-z0-9_-]{43})$/.exec(value);
  if (!match) throw new HttpError(401, "unauthorized");
  return match[1];
}

export async function sha256Hex(text: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text));
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

function hexBytes(hex: string): Uint8Array {
  const out = new Uint8Array(hex.length / 2);
  for (let i = 0; i < out.length; i++) out[i] = parseInt(hex.substr(i * 2, 2), 16);
  return out;
}

/** Compares two SHA-256 hex digests in constant time. */
export function sameHash(a: string, b: string): boolean {
  if (!HASH.test(a) || !HASH.test(b)) return false;
  return crypto.subtle.timingSafeEqual(hexBytes(a), hexBytes(b));
}
