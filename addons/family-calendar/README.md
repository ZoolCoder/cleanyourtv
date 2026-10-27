# Family Calendar

A shared family calendar on the TV. Phones on the home Wi-Fi add events (a dentist visit, school trips,
birthdays) through a small page the TV serves itself, and the TV shows them as a month and as today's
agenda, with the Hijri date beside the Gregorian one. An event with a reminder pops up on the TV over
whatever is playing, in the top-end corner, without taking the remote's focus, and goes away after 30
seconds. Nothing leaves the house: no accounts, no cloud, unless you build it with a relay for
[family abroad](#family-away), and even then the relay sees only sealed events. For the [Clean Your TV](../../README.md) launcher it
declares a [module card](../../docs/modules.md), so once you answer **Show card** its tile reads "Family
Calendar" with a magenta glow; on any other Android TV home it is an ordinary app. The page and the screen
have Arabic and English labels, and the screen mirrors in Arabic.

## On the TV

Three tabs at the top: **Month**, **Today** and **Connect**, and a fourth, **Away**, in a build with a relay
([Family away](#family-away)).

- **Month.** A Sunday-first month with Friday and Saturday tinted as the weekend and the Hijri months under
  the title. Each day shows up to three dots in the colours of the people it is for, and "+N" for more.
  Left and Right move a day and Down a week, across the month's edges; Up moves a week within the month, and
  from its first week goes up to the tabs. Rewind and Fast-forward (or Channel up and down) change the month. The focused day's events are listed beside the month: OK goes into
  that list, OK on an event opens it, and Back returns to the days. Back on another day goes to today.
- **Today.** Today's date, its Hijri date and a small month, and the events of today and the next 7 days in
  one list.
- **An event** shows its date, Hijri date, time, who it is for, its repeat and its reminder, with **Delete**.
  Delete asks first, with the focus on Cancel; Back cancels. Deleting a repeating event removes every repeat.
- **Connect.** The **Receive events from phones** switch (off until you turn it on), the address with a QR
  code, the 6-digit PIN with a **New PIN** button, and whether pop-ups over the picture are allowed.

## Use it from a phone

1. Open the app on the TV, go to **Connect** and turn on the switch.
2. On a phone on the **same Wi-Fi**, scan the QR code. It opens the page with the PIN filled in. Or type the
   address the screen shows, like `http://192.168.1.50:8766`, and the PIN.
3. Under **Add event**: a title, the date, a start time and an optional end time (or **All day**), who it is
   for (Me, Dad, Mom, Grandpa, Grandma, Kids, or a typed name), the repeat (never, every week, every year)
   and the reminder (none, at the time, 10 minutes, 1 hour or 1 day before). Press **Save event**.
4. Under **Events** the phone lists the next 14 days, each with **Delete**.

- A title is at most 60 characters, a name at most 24, both one line; control characters and direction
  overrides are removed, and everything is shown as plain text. The calendar keeps at most 500 events.
- Every name has one of six colours, always the same for the same name; the chips on the page pick theirs.
- A yearly event on 29 February falls on 28 February in other years. An all-day event reminds at 09:00.
- Reminders are checked once a minute while the switch is on, using the TV's clock. Each pops up once; one
  missed by more than 10 minutes (the TV was off) is skipped.
- The port (default 8766) and the seconds a reminder stays (default 30) are in
  `app/src/main/res/values/config.xml`. Edit them before you build.

## Permissions

- `INTERNET`: needed to listen on a port. The app answers phones on the home network and makes **no
  outgoing connection**, except to the relay for [Family away](#family-away) in a build that sets one, while
  its switch is on.
- `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_SPECIAL_USE`: the server and the reminder check run as a
  foreground service (type `specialUse`, with a notification) for as long as the switch is on.
- `SYSTEM_ALERT_WINDOW`: draws the reminder over other apps. Many TVs hide this setting, so grant it over
  ADB (see below). Without it the app says so and the events stay on its own screen.
- `RECEIVE_BOOT_COMPLETED`: starts the service again when the TV starts up, only if the switch was on.

No telemetry, no ads, no accounts. Backups are off. Events are kept in one file in the app's own storage.

## Background service and start at boot

While the switch is on, one foreground service runs a tiny HTTP server and checks reminders on the minute.
After a restart of the TV, or an update of the app, it starts again by itself if the switch was on. Turn the
switch off and the service stops and stays off, also after a restart, until you turn it on again. With the
switch off there are no reminders.

## Family away

Relatives abroad can add events too, from a link sent on WhatsApp, through a small relay on the internet.
It is off in the build here: with `relay_url` empty in `app/src/main/res/values/strings.xml` there is no
**Away** tab and the app makes no outgoing connection. Set it to your relay's address (like
`https://family-relay.example.workers.dev`) before you build, and the tab appears.

- **Away tab.** The **Events from family abroad** switch (off until you turn it on), how the relay is doing
  (Setting up, Last contact N minutes ago, Can't reach the relay, Link revoked), and an invite: a QR code and
  the link `<relay>/f/#box.sendToken.key.lang.event`. Send the link; a relative opens it, fills in an event
  and sends it. **New link** (it asks first) makes a new send token, so links shared before stop working.
  **Delete box** (it asks first) deletes the box on the relay and its keys on the TV.
- **The relay never reads an event.** The key rides in the link's `#` part, which the browser never sends.
  The phone seals each event with AES-256-GCM before it leaves, and the TV opens it. The relay keeps sealed
  messages until the TV fetches them and says they are saved. The keys stay on the TV: backups and device
  transfer are off.
- **Polled, not pushed.** The TV asks the relay every 2 minutes, at once when you open the app, and less
  often (up to every 15 minutes) when the relay cannot be reached. It asks only while the Away switch and
  **Receive events from phones** on Connect are both on, because the same service does both.
- **Same checks as at home.** An event from abroad goes through the same limits and cleaning as one from a
  phone at home. It shows ✈ on its day in the month, and "✈ from abroad" on Today and in the event. With no
  name for whom it is for, the sender's name is used. Something that does not open, is not an event or fails
  the checks is dropped; a full calendar drops it too.
- **Saved, then removed.** The relay drops a message only after the TV has saved it. When the TV cannot save,
  the message waits on the relay for the next round.

Anyone with the link can add events, so send it only to family, and press **New link** if it goes further.

## Security

Read this before you turn it on.

**It is plain HTTP on your home network.** Anyone on the same Wi-Fi can open the page, and the PIN travels
unencrypted, so someone who can watch your network can read it: the PIN stops casual misuse, not someone
sniffing your Wi-Fi. These are the guards:

- **PIN on every request.** Adding, listing and deleting all need the PIN: six random digits
  (`SecureRandom`), made on first run, compared in constant time. After 5 wrong PINs in a row, that phone
  (its address) is refused for 5 minutes (answer 429), even with the right PIN; the other phones go on
  working. 50 wrong PINs within an hour from all addresses together lock everyone out for 5 minutes, also
  across a restart, and make a new PIN, so the phones must scan the QR code again. **New PIN** replaces it by
  hand. The QR code carries the PIN after the `#`, which the browser never sends; the page takes it, drops
  it from the address bar and keeps it only for that browser tab.
- **Local addresses only.** Connections from anything but this TV, a 10.x, 172.16-31.x or 192.168.x address
  or a link-local address are closed at once.
- **No other sites.** The `Host` must be an IP address (a name means DNS rebinding), and a browser request
  that says it is cross-site, or whose `Origin` is not the TV, is refused. Only form data is accepted.
- **Small and short.** Request line and headers up to 8 KB, body up to 2 KB. The headers must arrive within
  2 seconds and the whole request within 5, a connection that goes quiet for 1 second is closed, and there
  are 8 connections at a time, one per address.
- **Saved means saved.** The phone hears "Saved" only after the event is on the TV's flash; when the write
  fails it hears so (answer 500). A calendar file the app cannot read is kept as `events.json.bad`, never
  written over.
- **No echo.** The page is one fixed file with a strict Content-Security-Policy; answers are a few fixed
  words, and the event list is JSON the page shows as text only.

Switch it off when you do not want it. **Do not use it on shared or public Wi-Fi** (a hotel, a dorm, a
flat share): there, strangers are on your network and could read or delete your events. Do not forward its
port on your router.

## Build and install

JDK 17 and Android SDK platform 34 with build-tools 34.0.0, as for the launcher:

```bash
cd addons/family-calendar && ./gradlew assembleRelease
TV_HOST=<tv ip> ../../tools/venv/bin/python -I ../../tools/tv.py --push app/build/outputs/apk/release/app-release.apk /data/local/tmp/addon.apk
TV_HOST=<tv ip> ../../tools/venv/bin/python -I ../../tools/tv.py 'pm install -r /data/local/tmp/addon.apk; rm -f /data/local/tmp/addon.apk'
```

Allow the reminder over the picture (also on a Fire TV, where the setting is hidden):

```bash
TV_HOST=<tv ip> ../../tools/venv/bin/python -I ../../tools/tv.py 'appops set com.zoolcoder.tv.calendar SYSTEM_ALERT_WINDOW allow'
```

or, with `adb`: `adb shell appops set com.zoolcoder.tv.calendar SYSTEM_ALERT_WINDOW allow`.

Press Home: the launcher asks once whether to show the card. Change the answer later by holding OK on
Settings, then Module cards. Open the card, turn the switch on under Connect, and add a first event from
your phone.

The release build is signed with the debug key so it installs over ADB like the launcher. Sign your own
builds with your own key if you hand them out; the launcher pins its Show answer to the signing key.

`./gradlew testReleaseUnitTest` runs the tests on the JVM: event fields, weekly and yearly repeats with 29
February, the agenda order, reminders and their 10-minute grace, the event file, the endpoint (PIN, lockout,
origin, form type, add, list and delete), request limits, form decoding, the address filter, text cleaning
and the QR code.

## Credits

Icon and banner drawn for this add-on (a calendar page with a magenta header).

## Tested on

Not yet on a TV.

## Maintainer

[@ZoolCoder](https://github.com/ZoolCoder)
