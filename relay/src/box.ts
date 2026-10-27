import { DurableObject } from "cloudflare:workers";
import { sameHash } from "./http";

export const HOUR = 60 * 60 * 1000;
export const DAY = 24 * HOUR;
export const MESSAGE_TTL = 14 * DAY;
export const IDLE_TTL = 90 * DAY;
export const SENDS_PER_HOUR = 30;
// The main module may export only handlers and classes, so the router's limit lives here too.
export const CREATES_PER_HOUR = 5;
export const MAX_QUEUED = 200;
export const PAGE = 50;

export interface Result {
  status: number;
  body: Record<string, unknown>;
}

const ok = (body: Record<string, unknown>, status = 200): Result => ({ status, body });
const fail = (status: number, code: string): Result => ({ status, body: { error: code } });
// Every token failure, including a box that does not exist, gives the same answer.
const UNAUTHORIZED = fail(401, "unauthorized");

/**
 * One family box: the hashes of its two tokens and the queue of ciphertexts. It never sees a key or a
 * plaintext. The router hands it SHA-256 hashes of the presented tokens, never the tokens themselves.
 */
export class Box extends DurableObject {
  private readonly sql: SqlStorage;

  constructor(ctx: DurableObjectState, env: Cloudflare.Env) {
    super(ctx, env);
    this.sql = ctx.storage.sql;
  }

  // Tables exist only once a box is created, so a request for an unknown id stores nothing.
  private exists(): boolean {
    return this.sql.exec("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'meta'").toArray().length > 0;
  }

  private schema(): void {
    this.sql.exec(`CREATE TABLE IF NOT EXISTS meta (k TEXT PRIMARY KEY, v TEXT NOT NULL);
      CREATE TABLE IF NOT EXISTS msgs (seq INTEGER PRIMARY KEY, c TEXT NOT NULL, at INTEGER NOT NULL);
      CREATE TABLE IF NOT EXISTS sent (at INTEGER NOT NULL);`);
  }

  private get(k: string): string | null {
    if (!this.exists()) return null;
    const row = this.sql.exec<{ v: string }>("SELECT v FROM meta WHERE k = ?", k).toArray()[0];
    return row ? row.v : null;
  }

  private put(k: string, v: string | number): void {
    this.sql.exec("INSERT INTO meta (k, v) VALUES (?, ?) ON CONFLICT(k) DO UPDATE SET v = excluded.v", k, String(v));
  }

  private reader(hash: string): boolean {
    const stored = this.get("read");
    if (stored === null || !sameHash(stored, hash)) return false;
    this.put("touched", Date.now());
    return true;
  }

  async create(readHash: string, sendHash: string): Promise<Result> {
    if (this.get("read") !== null) return fail(409, "exists");
    this.schema();
    const now = Date.now();
    this.put("read", readHash);
    this.put("send", sendHash);
    this.put("seq", 0);
    this.put("created", now);
    this.put("touched", now);
    await this.ctx.storage.setAlarm(now + DAY);
    return ok({ ok: true }, 201);
  }

  async rotate(readHash: string, sendHash: string): Promise<Result> {
    if (!this.reader(readHash)) return UNAUTHORIZED;
    this.put("send", sendHash);
    return ok({ ok: true });
  }

  async send(sendHash: string, c: string): Promise<Result> {
    const stored = this.get("send");
    if (stored === null || !sameHash(stored, sendHash)) return UNAUTHORIZED;
    const now = Date.now();
    this.sql.exec("DELETE FROM sent WHERE at <= ?", now - HOUR);
    const recent = this.sql.exec<{ n: number }>("SELECT COUNT(*) AS n FROM sent").one().n;
    if (recent >= SENDS_PER_HOUR) return fail(429, "rate_limited");
    const queued = this.sql.exec<{ n: number }>("SELECT COUNT(*) AS n FROM msgs").one().n;
    if (queued >= MAX_QUEUED) return fail(429, "queue_full");
    const seq = Number(this.get("seq")) + 1;
    this.put("seq", seq);
    this.sql.exec("INSERT INTO msgs (seq, c, at) VALUES (?, ?, ?)", seq, c, now);
    this.sql.exec("INSERT INTO sent (at) VALUES (?)", now);
    return ok({ seq });
  }

  async list(readHash: string, after: number): Promise<Result> {
    if (!this.reader(readHash)) return UNAUTHORIZED;
    const since = Date.now() - MESSAGE_TTL;
    const msgs = this.sql
      .exec<{ seq: number; c: string; at: number }>(
        "SELECT seq, c, at FROM msgs WHERE seq > ? AND at > ? ORDER BY seq LIMIT ?", after, since, PAGE)
      .toArray()
      .map((m) => ({ seq: m.seq, c: m.c, at: m.at }));
    const next = msgs.length ? msgs[msgs.length - 1].seq : after;
    return ok({ msgs, next });
  }

  async ack(readHash: string, upto: number): Promise<Result> {
    if (!this.reader(readHash)) return UNAUTHORIZED;
    this.sql.exec("DELETE FROM msgs WHERE seq <= ?", upto);
    return ok({ ok: true });
  }

  async destroy(readHash: string): Promise<Result> {
    if (!this.reader(readHash)) return UNAUTHORIZED;
    await this.wipe();
    return ok({ ok: true });
  }

  /** Daily: drop expired messages, and the whole box once the TV has not called for IDLE_TTL. */
  async alarm(): Promise<void> {
    const touched = this.get("touched");
    if (touched === null) return this.wipe();
    const now = Date.now();
    if (now - Number(touched) >= IDLE_TTL) return this.wipe();
    this.sql.exec("DELETE FROM msgs WHERE at <= ?", now - MESSAGE_TTL);
    this.sql.exec("DELETE FROM sent WHERE at <= ?", now - HOUR);
    await this.ctx.storage.setAlarm(now + DAY);
  }

  private async wipe(): Promise<void> {
    await this.ctx.storage.deleteAlarm();
    await this.ctx.storage.deleteAll();
  }
}

/** Box creations per caller, keyed by a hash of the address so the address itself is never stored. */
export class Limiter extends DurableObject {
  private readonly sql: SqlStorage;

  constructor(ctx: DurableObjectState, env: Cloudflare.Env) {
    super(ctx, env);
    this.sql = ctx.storage.sql;
    this.sql.exec("CREATE TABLE IF NOT EXISTS hits (at INTEGER NOT NULL)");
  }

  /** True and counted when fewer than limit hits fall in the last window. */
  async take(limit: number, window: number): Promise<boolean> {
    const now = Date.now();
    this.sql.exec("DELETE FROM hits WHERE at <= ?", now - window);
    const n = this.sql.exec<{ n: number }>("SELECT COUNT(*) AS n FROM hits").one().n;
    if (n >= limit) return false;
    this.sql.exec("INSERT INTO hits (at) VALUES (?)", now);
    await this.ctx.storage.setAlarm(now + window);
    return true;
  }

  async alarm(): Promise<void> {
    await this.ctx.storage.deleteAll();
  }
}
