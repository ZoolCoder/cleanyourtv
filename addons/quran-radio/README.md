# Quran Radio

The Quran on the TV, in two tabs. **Radio** lists live Quran stations; OK plays one. **Reciters** lists a few
reciters; OK opens one, OK on a surah plays it and the next surah follows by itself. The panel beside the list
always says what is playing, and OK on the playing row (or the remote's play/pause key) pauses and resumes.
Back from the panel returns to the list and the sound keeps playing; Back from a reciter's surahs returns to
the reciters; Back from the top list closes the app, which stops the sound. The hint bar at the bottom says
what OK and Back do at that moment. For the
[Clean Your TV](../../README.md) launcher it declares a [module card](../../docs/modules.md), so once you
answer **Show card** its tile reads "Quran Radio" with a green glow; on any other Android TV home it is an
ordinary app.

- **Out of the box:** six stations (Al-Zain Mohamed Ahmed, Mishary Alafasy, Maher Al-Muaiqly, Saad
  Al-Ghamdi, Mahmoud Khalil Al-Husary and a mix of reciters) and seven reciters. Three of them recite
  **Al-Douri from Abu Amr**, the riwayah most of Sudan reads: Noreen Mohamed Siddiq, Al-Fateh Mohamed
  Al-Zubair and Al-Husary. The others are Al-Zain Mohamed Ahmed (Sudanese, Hafs), Mishary Alafasy, Maher
  Al-Muaiqly and Abdulrahman Al-Sudais, all Hafs from Asim.
- **Stream states:** "Connecting…" shows when a stream has not started within half a second. A stream that
  has not started after 10 seconds, fails, or (for a live station) ends is tried once more after 2 seconds;
  if that fails too the panel and the row say "Could not play", with no Playing dot, and OK on the play
  button or the row tries again. Paused shows "○ Paused" and is never mistaken for a failure.
- **Favourites:** while a station plays, the "☆ Favourite" pill beside the play button marks it. Favourite
  stations move to the top of the Radio list with a ★. They are kept on the TV (app storage) until you
  clear the app's data.
- **Sleep timer:** the pill on the other side of the play button cycles Off, 15, 30, 60 and 90 minutes and
  shows the minutes left. At the end the sound fades out over 10 seconds, then stops. Stopping the sound,
  or leaving the app, clears the timer.
- **Saved reciter list:** the last reciter and surah lists that loaded in full are saved on the TV and show
  at once on the next start, while a refresh runs. If the refresh fails, the saved list stays and the
  Reciters tab says "Offline · list saved 26/10 14:32". The Radio stations are in `config.xml` and need no
  network to list.
- **The lists** are in `app/src/main/res/values/config.xml`. Edit them before you build; add or drop lines
  freely.
  - `stations`: one line per station, `name | https stream URL`. The name is shown as written.
  - `reciters`: one line per reciter, `id | note`, or `id:moshaf | note` to pick one of several recitations
    of that reciter (the first is played otherwise). The note after `|` is only for you. Ids come from
    [`/api/v3/reciters?language=ar`](https://www.mp3quran.net/api/v3/reciters?language=ar); the recitation's
    riwayah is shown with the reciter's name, and the riwayat are at
    [`/api/v3/riwayat`](https://www.mp3quran.net/api/v3/riwayat) (1 Hafs, 13 Al-Douri from Abu Amr).
- **Names and language:** reciter and surah names come from mp3quran.net in Arabic when the TV is in
  Arabic, in English otherwise; the page switches to right-to-left with them. Station names are the text in
  `config.xml`.
- **Data:** station streams come from the list at `/api/v3/radios` and surah files from the reciter's
  `server` folder (`<server>/001.mp3` to `114.mp3`). Everything is HTTPS, so no cleartext exception is
  needed; a station on plain `http` is skipped.
- **Sound** plays only while the app is on screen: Home or leaving the app stops it. There is no service,
  no notification and no wake lock; the screen simply stays on while something plays.

## Permissions

- `INTERNET`: the radio streams and surah files, plus HTTPS GETs of the reciter and surah lists at
  `mp3quran.net`, only while the app is open. Nothing is sent but the requests, and nothing runs in the
  background. The only things stored are the favourite stations and the last good reciter and surah lists,
  in the app's own storage.

No wake lock (the screen stays on with a window flag while playing), no network state, no background
service, no telemetry, no ads. Backups are off.

## Build and install

JDK 17 and Android SDK platform 34 with build-tools 34.0.0, as for the launcher:

```bash
cd addons/quran-radio && ./gradlew assembleRelease
TV_HOST=<tv ip> ../../tools/venv/bin/python -I ../../tools/tv.py --push app/build/outputs/apk/release/app-release.apk /data/local/tmp/addon.apk
TV_HOST=<tv ip> ../../tools/venv/bin/python -I ../../tools/tv.py 'pm install -r /data/local/tmp/addon.apk; rm -f /data/local/tmp/addon.apk'
```

Press Home: the launcher asks once whether to show the card. Change the answer later by holding OK on
Settings, then Module cards.

The release build is signed with the debug key so it installs over ADB like the launcher. Sign your own
builds with your own key if you hand them out; the launcher pins its Show answer to the signing key.

`./gradlew testReleaseUnitTest` runs the config and API parsing tests, and the retry, favourite order and
sleep-timer rules (`Playback`), on the JVM.

## Credits

Station lists, reciter lists, surah names and the recordings are served by [mp3quran.net](https://mp3quran.net)
and its radio streams; this add-on only plays what their public API offers and carries none of their logos.
Icon and banner drawn for this add-on (the colours of the Sudanese flag over sound bars).

## Tested on

Not yet on a TV.

## Maintainer

[@ZoolCoder](https://github.com/ZoolCoder)
