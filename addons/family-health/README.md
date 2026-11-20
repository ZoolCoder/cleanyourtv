# Family Health

Medicines and doctor visits for the whole family, on the TV. Today's doses for everyone, who has taken what,
how many days each pack still lasts, and the coming appointments, in large glass cards a grandparent can read
from the sofa. When a dose falls due, a reminder slides up over whatever is playing with **Taken** and
**Later**, so nobody has to open the app to be reminded. Everything stays on the TV: the family, their medicines
and appointments are added from a phone on the home network, and nothing goes out; no accounts. For the [Clean Your TV](../../README.md) launcher it declares a
[module card](../../docs/modules.md), so once you answer **Show card** its tile reads "Family Health" with a
teal glow; on any other Android TV home it is an ordinary app. Labels are in English and Arabic, and the
screen mirrors in Arabic.

To try it first, the empty screen has a **Show an example** button that fills in an invented family
(Grandma, Dad, Sara and Omar). Adding your first own person removes it.

## On the TV

- **Today.** A row of people at the top: **Everyone**, then one tab per member. Under it three panels:
  - **Now**: the doses due, each with **Later 15 min** and **Taken** (focused). OK on Taken moves the dose
    to Done.
  - **Later today**: the doses still to come, with their time, and today's appointments. An appointment
    moves to Now 2 hours before it and stays there until an hour after.
  - **Done**: what was taken, with the time. Hold OK on one to undo it.
- **A member's page.** OK on a tab opens it: their name, seven dots for this week (taken, missed, still to
  come), their medicines with how to take them, the times and the days left in the pack (red at 3 days or
  fewer), and their coming appointments with the doctor, specialty, place, day and time. Left and Right
  past the edge move to the next member; Back returns to Today, on that member's tab.
- **Remove the example** in the header clears the invented family. It shows only while the example is there.
- **Add from a phone** in the header (and on the empty screen) opens the phone page's switch, QR code and
  PIN. Back returns to Today.

## From a phone

1. On the TV, open **Add from a phone** and turn on **Phone page**.
2. On a phone on the **same Wi-Fi**, scan the QR code. It opens the page with the PIN filled in. Or type the
   address the screen shows, like `http://192.168.1.50:8767`, and the PIN.
3. Three tabs, in English and Arabic:
   - **People**: a name (at most 24 characters) and one of six colours. Up to 12 people. **Delete** removes a
     person with their medicines and appointments.
   - **Medicines**: who it is for, the name, the dose, how to take it, one to eight times a day, the first
     day, how many days (blank: ongoing, at most 365), units a dose (1 to 20) and units in the pack (blank:
     not counted). Up to 40. **Refill** sets the pack after buying more; **Delete** removes one.
   - **Visits**: who, the doctor, the specialty, the day (today up to two years ahead), the time, the place
     and a note. Up to 60. The list shows those not yet over.
4. The TV updates at once. Turn the switch off when everyone is set up: the reminders go on without it.

- Text is one line; control characters and direction overrides are removed, and everything is shown as
  plain text on the phone and the TV.
- The port (default 8767; Family Notes uses 8765 and Family Calendar 8766) is in
  `app/src/main/res/values/config.xml`. Edit it before you build.

## Reminders

- **Doses.** A dose shows under Now from 30 minutes before its time until 3 hours after, also across
  midnight. At its time a
  reminder pops up over the picture: the member's initial and name, the medicine, how to take it, and
  **Later** and **Taken**, with Taken focused. Taken records the dose and counts it off the pack. Later,
  Back or 60 seconds without an answer put it off for 15 minutes; on Today the card then says when the
  reminder comes back. A dose not taken within 3 hours counts as missed in the week dots.
- **Appointments.** One reminder from 7 PM the evening before, and one in the 2 hours before. OK closes it.
- Reminders due together wait in a queue of up to 6 and show one after the other. None shows while the app
  itself is open, since the screen already shows the same thing; one already up closes within a minute.
- Reminders are checked once a minute by the TV's clock, while there is anything to remind about.

## Permissions

- `INTERNET`: needed to listen on a port while **Phone page** is on. The app answers phones on the home
  network and makes **no outgoing connection**.
- `SYSTEM_ALERT_WINDOW`: draws the reminder over other apps. Many TVs hide this setting, so grant it over
  ADB (see below). Without it the app says **Reminders over other apps are off** in its header, and the
  doses stay on its own screen.
- `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_SPECIAL_USE`: the reminder check and the phone page run as
  a foreground service (type `specialUse`, with a notification) while there is anything to remind about or
  the phone page is on, and it stops itself otherwise.
- `RECEIVE_BOOT_COMPLETED`: starts the reminders again when the TV starts up or the app is updated, only
  when there is something to remind about.

No telemetry, no ads, no accounts. Backups are off. The records are one file in the app's own storage
(`health.json`); doses older than 60 days are dropped from it. A file the app cannot read is kept as
`health.json.bad`, never written over.

## Security

Read this before you turn the phone page on. It works like [Family Calendar](../family-calendar/README.md#security)'s.

**It is plain HTTP on your home network.** Anyone on the same Wi-Fi can open the page, and the PIN travels
unencrypted, so someone who can watch your network can read it: the PIN stops casual misuse, not someone
sniffing your Wi-Fi. The phone page is **off** until you turn it on. These are the guards:

- **PIN on every request.** Listing, adding, refilling and deleting all need the PIN: six random digits
  (`SecureRandom`), new each time the page is switched on, compared in constant time. After 5 wrong PINs in
  a row, that phone (its address) is refused for 5 minutes (answer 429). 50 wrong PINs within an hour from
  all addresses together make a new PIN and switch the page off, until you switch it on again. **New PIN**
  replaces it by hand. The QR code carries the PIN after the `#`, which the browser never sends; the page takes it,
  drops it from the address bar and keeps it only for that browser tab.
- **Local addresses only.** Connections from anything but this TV, a 10.x, 172.16-31.x or 192.168.x address
  or a link-local address are closed at once.
- **No other sites.** The `Host` must be an IP address (a name means DNS rebinding), and a browser request
  that says it is cross-site, or whose `Origin` is not the TV, is refused. Only form data is accepted.
- **Small and short.** Request line and headers up to 8 KB, body up to 2 KB, with time limits and at most
  8 connections, one per address.
- **Saved means saved.** The phone hears "Saved" only after the change is on the TV's flash.
- **No echo.** The page is one fixed file with a strict Content-Security-Policy; answers are a few fixed
  words, and the list is JSON the page shows as text only.

**Do not turn it on on shared or public Wi-Fi**, and do not forward its port on your router: health
details are private.

## Build and install

JDK 17 and Android SDK platform 34 with build-tools 34.0.0, as for the launcher:

```bash
cd addons/family-health && ./gradlew assembleRelease
TV_HOST=<tv ip> ../../tools/venv/bin/python -I ../../tools/tv.py --push app/build/outputs/apk/release/app-release.apk /data/local/tmp/addon.apk
TV_HOST=<tv ip> ../../tools/venv/bin/python -I ../../tools/tv.py 'pm install -r /data/local/tmp/addon.apk; rm -f /data/local/tmp/addon.apk'
```

Allow the reminder over the picture (also on a Fire TV, where the setting is hidden):

```bash
TV_HOST=<tv ip> ../../tools/venv/bin/python -I ../../tools/tv.py 'appops set com.zoolcoder.tv.health SYSTEM_ALERT_WINDOW allow'
```

or, with `adb`: `adb shell appops set com.zoolcoder.tv.health SYSTEM_ALERT_WINDOW allow`.

Press Home: the launcher asks once whether to show the card. Change the answer later by holding OK on
Settings, then Module cards.

The release build is signed with the debug key so it installs over ADB like the launcher. Sign your own
builds with your own key if you hand them out; the launcher pins its Show answer to the signing key.

`./gradlew testReleaseUnitTest` runs the tests on the JVM: the dose window, missed doses, snoozing, the
appointment reminders, the week dots, taking and undoing a dose with the pack count, text
cleaning, the records file, what a phone may add or delete, and the phone server's routes, PIN, lockout,
origin checks and limits.

## Credits

Icon and banner drawn for this add-on (a calendar page with a pill, on teal).

## Tested on

Amazon Fire TV Stick AFTMA08C15 (Fire OS 8.1.8.5, Android 11).

## Maintainer

[@ZoolCoder](https://github.com/ZoolCoder)
