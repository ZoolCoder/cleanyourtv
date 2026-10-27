// Writes test/vector.json: a fixed AES-256-GCM message sealed the way the send page seals it, so the
// TV add-ons' Java tests can check they open exactly what a phone sends. Run: npm run vector
import { webcrypto as crypto } from "node:crypto";
import { writeFileSync } from "node:fs";

const b64url = (bytes) => Buffer.from(bytes).toString("base64url");
const fill = (n, start) => Uint8Array.from({ length: n }, (_, i) => (start + i) & 0xff);

const boxId = b64url(fill(16, 0x10));
const key = b64url(fill(32, 0x40));
const iv = fill(12, 0xa0);
const plaintext = JSON.stringify({ v: 1, kind: "note", from: "آمنة", text: "Salam from Riyadh · سلام من الرياض", ts: 1761500000000 });

const cryptoKey = await crypto.subtle.importKey("raw", Buffer.from(key, "base64url"), { name: "AES-GCM" }, false, ["encrypt"]);
const ct = new Uint8Array(await crypto.subtle.encrypt(
  { name: "AES-GCM", iv, additionalData: new TextEncoder().encode(boxId), tagLength: 128 },
  cryptoKey, new TextEncoder().encode(plaintext)));
const wire = new Uint8Array(iv.length + ct.length);
wire.set(iv, 0);
wire.set(ct, iv.length);

const vector = {
  about: "AES-256-GCM, 12-byte IV, AAD = UTF-8 bytes of boxId, wire = base64url(iv || ciphertext || tag), no padding",
  boxId,
  key,
  iv: b64url(iv),
  plaintext,
  wire: b64url(wire),
};
writeFileSync(new URL("../test/vector.json", import.meta.url), JSON.stringify(vector, null, 2) + "\n");
console.log(vector.wire);
