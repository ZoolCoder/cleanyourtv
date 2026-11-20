# Family Health

Medicines and doctor visits for the whole family, on the TV. Today's doses for everyone, who has taken what,
how many days each pack still lasts, and the coming appointments, in large glass cards a grandparent can read
from the sofa. When a dose falls due, a reminder slides up over whatever is playing with **Taken** and
**Later**, so nobody has to open the app to be reminded. Everything stays on the TV: no network permission,
no accounts. For the [Clean Your TV](../../README.md) launcher it declares a
[module card](../../docs/modules.md), so once you answer **Show card** its tile reads "Family Health" with a
teal glow; on any other Android TV home it is an ordinary app. Labels are in English and Arabic, and the
screen mirrors in Arabic.

This first version offers an invented example family (Grandma, Dad, Sara and Omar) to try it with: the
empty screen has a **Show an example** button. Adding your own medicines and appointments from a phone
comes next.

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

- `SYSTEM_ALERT_WINDOW`: draws the reminder over other apps. Many TVs hide this setting, so grant it over
  ADB (see below). Without it the app says **Reminders over other apps are off** in its header, and the
  doses stay on its own screen.
- `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_SPECIAL_USE`: the reminder check runs as a foreground
  service (type `specialUse`, with a notification) while there is anything to remind about, and stops
  itself when the records are empty.
- `RECEIVE_BOOT_COMPLETED`: starts the reminders again when the TV starts up or the app is updated, only
  when there is something to remind about.

No `INTERNET`, no telemetry, no ads, no accounts. Backups are off. The records are one file in the app's own
storage (`health.json`); doses older than 60 days are dropped from it.

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
cleaning, and the records file.

## Credits

Icon and banner drawn for this add-on (a calendar page with a pill, on teal).

## Tested on

Amazon Fire TV Stick AFTMA08C15 (Fire OS 8.1.8.5, Android 11).

## Maintainer

[@ZoolCoder](https://github.com/ZoolCoder)
