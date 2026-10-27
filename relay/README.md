# Family away relay

A small Cloudflare Worker that lets relatives outside the home send notes and calendar events to the
Family Notes and Family Calendar add-ons on the TV. It is optional: the add-ons work on the home Wi-Fi
without it, and "Family away" stays off until you set `relay_url` on the TV.

```
phone (anywhere)  --POST ciphertext-->  relay  <--TV polls, outward only--  TV at home
```

- Nothing reaches the TV from the internet. The TV asks the relay for new messages, decrypts them, then
  acknowledges them, which deletes them from the relay.
- Every message is sealed on the phone with AES-256-GCM. The key is in the invite link's fragment (the
  part after `#`), which browsers never send to a server. The relay stores ciphertext it cannot read.
- You run your own relay on your own Cloudflare account. There is no shared server.

## What the relay can and cannot see

| The relay sees | The relay never sees |
|---|---|
| A random box id (16 bytes) per invite | Message text, names, event titles or dates |
| SHA-256 hashes of the read and send tokens | The tokens themselves, or the encryption key |
| Ciphertext, its size, and when it arrived | Who in the family sent what |
| Your relatives' IP addresses while a request is in flight (Cloudflare handles every Worker request) | Anything about your home network: the TV only connects out |

The Worker logs nothing: `observability` is off in `wrangler.jsonc`, and the code never logs bodies,
tokens or addresses. The box-creation rate limit is keyed by a SHA-256 hash of the address, not the
address itself.

## Limits built in

- Bodies over 4 KB are refused before parsing (413). Only `application/json` is accepted (415).
- 30 messages an hour per box, at most 200 waiting at once (429).
- 5 new boxes an hour per address (429).
- Messages expire after 14 days whether or not the TV fetched them.
- A box nobody has read for 90 days deletes itself.
- Requests from other websites are refused (403), and no CORS headers are ever sent.
- Token checks use a constant-time comparison. An unknown box and a wrong token give the same 401.

## Deploy

You need a free Cloudflare account, Node.js 20 or newer, and this folder.

```sh
cd relay
npm ci
npx vitest run            # optional: the tests run locally, offline
npx wrangler login        # opens the browser once
npx wrangler deploy
```

`wrangler deploy` prints the address, something like `https://family-away-relay.<your-subdomain>.workers.dev`.
Put that address in the TV add-ons as `relay_url` (see each add-on's README), then open the add-on's
"Family away" panel on the TV to make an invite link or QR code for each relative.

To try it without deploying: `npx wrangler dev`, then open `http://localhost:8787/f/`.

## Cost

The Workers free plan covers a family easily: 100,000 requests a day, and Durable Objects on SQLite
storage are included in the free plan. One TV polling every few minutes and a few relatives sending notes
uses well under 1% of that. Nothing here needs a paid plan or a credit card.

## Delete everything

- One relative: revoke their invite on the TV. The TV rotates the send token, and the old link stops
  working at once.
- One box: turn "Family away" off on the TV. It deletes its box on the relay (`DELETE /v1/box/<id>`).
- The whole relay: `npx wrangler delete` in this folder, or delete the `family-away-relay` Worker in the
  Cloudflare dashboard. That removes the Worker and all its stored data.

## API

All replies are JSON with `Cache-Control: no-store`. Tokens are 32 random bytes and box ids 16, both
base64url without padding.

| Request | Auth | Body or query | Reply |
|---|---|---|---|
| `POST /v1/box` | none | `{"boxId","readHash","sendHash"}` (hashes: SHA-256 hex of the tokens) | 201 `{"ok":true}`, 409 `exists` |
| `POST /v1/box/<id>/msg` | send token | `{"c":"<base64url iv‖ciphertext‖tag>"}` | 200 `{"seq":n}` |
| `GET /v1/box/<id>/msg?after=<seq>` | read token | | 200 `{"msgs":[{"seq","c","at"}],"next":seq}`, 50 per page |
| `DELETE /v1/box/<id>/msg?upto=<seq>` | read token | | 200, deletes messages up to `seq` |
| `PUT /v1/box/<id>/send` | read token | `{"sendHash"}` | 200, the old send token stops working |
| `DELETE /v1/box/<id>` | read token | | 200, the box and its messages are gone |
| `GET /f/` | none | invite in the fragment | the phone page |

Auth is `Authorization: Bearer <token>`. Errors are `{"error":"<code>"}`.

Ciphertext is AES-256-GCM with a 12-byte IV and the box id (UTF-8) as additional data. The plaintext is
JSON: `{"v":1,"kind":"note","from","text","ts"}` or `{"v":1,"kind":"event","from","title","date","time","who","repeat","remind","ts"}`, where `time` is empty
for an all-day event and `repeat` is `none`, `weekly` or `yearly`.
`test/vector.json` is a fixed example that the Java side checks against; `npm run vector` rebuilds it.

An invite link is `https://<relay>/f/#<boxId>.<sendToken>.<key>.<ar|en>`, with an optional fifth part,
`.note` or `.event`, so one phone can hold an invite for each add-on.
