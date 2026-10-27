# Family Notes

A family member opens a page on their phone, types "Dinner at 8" or "Call grandma", and the note pops up on
the TV over whatever is playing. The card sits in the top-end corner, never takes the remote's focus, and
goes away by itself after 20 seconds. A new note replaces the one showing. For the
[Clean Your TV](../../README.md) launcher it declares a [module card](../../docs/modules.md), so once you
answer **Show card** its tile reads "Family Notes" with an amber glow; on any other Android TV home it is an
ordinary app. The page and the screen have Arabic and English labels.

## Use it from a phone

1. Open the app on the TV and turn on **Receive notes from phones**. It is off until you do.
2. The screen shows an address like `http://192.168.1.50:8765` and a 6-digit PIN.
3. On a phone on the **same Wi-Fi**, open that address in the browser. Type your name, the note and the PIN,
   and press Send. The page says whether it went through.

The screen also shows the last 10 notes with their time (kept on the TV), a **Clear all notes** button that
asks first, a **New PIN** button (the old PIN stops working at once), and whether pop-ups over the picture
are allowed.

- **Who wrote it.** Each name gets one of six colours, always the same for the same name, shown as a bar
  beside the note in the list and on the pop-up.
- **Quiet hours.** OK on the **Quiet hours** row steps through Off, 22:00–07:00, 23:00–08:00 and 21:00–06:00
  (the TV's own clock). Inside the window notes still arrive and are listed, but nothing pops up over the
  picture. The choice is kept on the TV.
- **Locked out.** After 5 wrong PINs from one phone, that phone waits 5 minutes and the TV makes a new PIN;
  the screen says so for 10 minutes. If phones together send 50 wrong PINs within an hour, everyone waits,
  and the screen counts the minutes down.

- A note is at most 280 characters and 4 lines, a name at most 24 characters. Control characters are
  removed. Notes are shown as plain text, never as HTML or links.
- The port (default 8765) and the seconds a note stays (default 20) are in
  `app/src/main/res/values/config.xml`. Edit them before you build.

## Family away

Relatives outside the home (Riyadh, Cairo, London) can send a note too. It pops up like a home note, with
the tag **✈ from abroad · من الخارج**, and is listed with the same tag. This needs a small relay that you
deploy yourself, once, on your own free Cloudflare account: [`relay/`](../../relay/README.md) in this repo.
Without one, the app has no Family away panel and makes no outgoing connection.

1. Deploy `relay/` and put its address in `app/src/main/res/values/strings.xml`, `relay_url`, for example
   `https://family-away-relay.<your-subdomain>.workers.dev`. HTTPS only. Then build and install.
2. On the TV, turn on **Receive notes from phones** and, in **Family away · العائلة في الخارج**, the
   **Notes from family abroad** row. The TV sets up a box on the relay ("Setting up…").
3. Scan the QR code with your phone's camera, copy the link and send it on WhatsApp. The relative opens it
   once; their phone remembers the box, and they type their name and the note.

The TV asks the relay every 2 minutes, at once when you open the app, and less often (up to every 15
minutes) while the relay cannot be reached. The status line says when it last got through. **New link**
makes a new link and the old ones stop working; **Delete box** deletes the box on the relay. Both ask first.
Off stops asking and keeps the box, so the link still works when you turn it on again. A box the relay has
dropped (deleted, or unused for 90 days) shows "The link expired"; off and on again makes a new one.

**What the relay sees.** Every note is end-to-end encrypted on the relative's phone (AES-256-GCM) with a key
that is only in the link's `#` part, which browsers never send, and on this TV. The relay stores ciphertext
for at most 14 days, deletes it once the TV has read it, and knows each box only by an id and two token
hashes. It does see when notes are sent and their size, and Cloudflare sees the IP addresses of the TV and
the phones. Anyone holding the link can send notes until you make a new one, so share it only with family.
Notes from abroad are cleaned exactly like home notes; anything that does not decrypt, or is not a note, is
dropped.

## Permissions

- `INTERNET`: needed to listen on a port. Without a relay set, the app makes **no outgoing connection** at
  all; it only answers phones on the home network. With one set and Family away on, its only outgoing
  connection is HTTPS to that relay.
- `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_SPECIAL_USE`: the server runs as a foreground service (type
  `specialUse`, with a notification) for as long as the switch is on, so Android does not stop it.
- `SYSTEM_ALERT_WINDOW`: draws the pop-up over other apps. Many TVs hide this setting, so grant it over ADB
  (see below). Without it the app says so and keeps the notes on its own screen only.
- `RECEIVE_BOOT_COMPLETED`: starts the server again when the TV starts up, only if the switch was on.

No telemetry, no ads, no accounts. Backups and device-to-device transfer are off. The server only receives text; it exposes nothing from
the TV.

## Background service and start at boot

While the switch is on, one foreground service runs a tiny HTTP server. After a restart of the TV, or after
an update of the app, it starts again by itself if the switch was on. Turn the switch off and the service
stops and stays off, also after a restart, until you turn it on again.

## Security

Read this before you turn it on.

**It is plain HTTP on your home network.** Anyone on the same Wi-Fi can open the page, and the PIN travels
unencrypted, so someone who can watch your network can read it. The PIN stops casual misuse, not someone
sniffing your Wi-Fi. These are the guards:

- **PIN.** Six random digits (`SecureRandom`), made on first run, compared in constant time. Wrong PINs are
  counted per phone: after 5 in a row that phone is refused for 5 minutes (answer 429), even with the right
  PIN, and the TV makes a new PIN, so one phone cannot lock the family out or keep guessing. 50 wrong PINs
  within an hour from all phones together refuse everyone for 5 minutes, also after a restart. **New PIN** on
  the screen replaces it by hand. The page keeps the PIN for the browser tab only, not on the phone.
- **Local addresses only.** Connections from anything but this TV, a 10.x, 172.16-31.x or 192.168.x address
  or a link-local address are closed at once.
- **Small and short.** Request line and headers up to 8 KB, body up to 2 KB (larger gets 413). The headers
  must arrive within 2 seconds and the whole request within 5, a connection that goes quiet for 1 second
  is closed, at most 8 connections at a time and 1 per phone (a second one gets 503), then
  `Connection: close`.
- **Fixed pop-up.** The pop-up always reads "Family note · ملاحظة عائلية" and "From: name · من: name", so a
  note cannot pass itself off as a message from the TV. Invisible and direction-changing characters are
  removed from names and notes.
- **No echo.** The page is one fixed file; nothing a phone sends is ever written into a page, and answers
  are a few fixed words.

Switch it off when you do not want it. **Do not use it on shared or public Wi-Fi** (a hotel, a dorm, a
flat share): there, strangers are on your network. Do not forward its port on your router.

## Build and install

JDK 17 and Android SDK platform 34 with build-tools 34.0.0, as for the launcher:

```bash
cd addons/family-notes && ./gradlew assembleRelease
TV_HOST=<tv ip> ../../tools/venv/bin/python -I ../../tools/tv.py --push app/build/outputs/apk/release/app-release.apk /data/local/tmp/addon.apk
TV_HOST=<tv ip> ../../tools/venv/bin/python -I ../../tools/tv.py 'pm install -r /data/local/tmp/addon.apk; rm -f /data/local/tmp/addon.apk'
```

Allow the pop-up over the picture (also on a Fire TV, where the setting is hidden):

```bash
TV_HOST=<tv ip> ../../tools/venv/bin/python -I ../../tools/tv.py 'appops set com.zoolcoder.tv.notes SYSTEM_ALERT_WINDOW allow'
```

or, with `adb`: `adb shell appops set com.zoolcoder.tv.notes SYSTEM_ALERT_WINDOW allow`.

Press Home: the launcher asks once whether to show the card. Change the answer later by holding OK on
Settings, then Module cards. Open the card, turn the switch on, and send a first note from your phone.

The release build is signed with the debug key so it installs over ADB like the launcher. Sign your own
builds with your own key if you hand them out; the launcher pins its Show answer to the signing key.

`./gradlew testReleaseUnitTest` runs the tests on the JVM: request limits, form decoding, the address
filter, the PIN and its lockouts, the connection limits, text cleaning, quiet hours across midnight, the
sender colours, and Family away against a fake relay: the encryption (including a message sealed by the
relay's own send page, `relay/test/vector.json`), set-up, polling, acknowledgements, backing off, new links,
a dropped box and messages that do not decrypt.

## Credits

Icon and banner drawn for this add-on (a yellow sticky note).

## Tested on

Not yet on a TV.

## Maintainer

[@ZoolCoder](https://github.com/ZoolCoder)
