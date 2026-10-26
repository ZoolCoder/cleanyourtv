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

The screen also shows the last 10 notes with their time (kept on the TV), a **Clear** button, a **New PIN**
button (the old PIN stops working at once), and whether pop-ups over the picture are allowed.

- A note is at most 280 characters and 4 lines, a name at most 24 characters. Control characters are
  removed. Notes are shown as plain text, never as HTML or links.
- The port (default 8765) and the seconds a note stays (default 20) are in
  `app/src/main/res/values/config.xml`. Edit them before you build.

## Permissions

- `INTERNET`: needed to listen on a port. The app makes **no outgoing connection** at all; it only answers
  phones on the home network.
- `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_SPECIAL_USE`: the server runs as a foreground service (type
  `specialUse`, with a notification) for as long as the switch is on, so Android does not stop it.
- `SYSTEM_ALERT_WINDOW`: draws the pop-up over other apps. Many TVs hide this setting, so grant it over ADB
  (see below). Without it the app says so and keeps the notes on its own screen only.
- `RECEIVE_BOOT_COMPLETED`: starts the server again when the TV starts up, only if the switch was on.

No telemetry, no ads, no accounts. Backups are off. The server only receives text; it exposes nothing from
the TV.

## Background service and start at boot

While the switch is on, one foreground service runs a tiny HTTP server. After a restart of the TV, or after
an update of the app, it starts again by itself if the switch was on. Turn the switch off and the service
stops and stays off, also after a restart, until you turn it on again.

## Security

Read this before you turn it on.

**It is plain HTTP on your home network.** Anyone on the same Wi-Fi can open the page, and the PIN travels
unencrypted, so someone who can watch your network can read it. These are the guards:

- **PIN.** Six random digits (`SecureRandom`), made on first run, compared in constant time. After 5 wrong
  PINs in a row every note is refused for 5 minutes (answer 429), even with the right PIN. **New PIN** on the
  screen replaces it.
- **Local addresses only.** Connections from anything but this TV, a 10.x, 172.16-31.x or 192.168.x address
  or a link-local address are closed at once.
- **Small and short.** Request line and headers up to 8 KB, body up to 2 KB (larger gets 413), a request
  must arrive within 5 seconds, 2 connections at a time, then `Connection: close`.
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
filter, the PIN and its lockout, and text cleaning.

## Credits

Icon and banner drawn for this add-on (a yellow sticky note).

## Tested on

Not yet on a TV.

## Maintainer

[@ZoolCoder](https://github.com/ZoolCoder)
