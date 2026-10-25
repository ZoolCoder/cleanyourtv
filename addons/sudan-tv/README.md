# Sudan TV

Sudanese and Arabic channels as tiles on the TV: Sudan TV, Sudania 24, Blue Nile TV, Sudan Bukra, Al Hadath
Sudan, Al Jazeera, Al Jazeera Mubasher, Al Arabiya, Al Hadath, Sky News Arabia and the Quran channel. Each
tile shows the channel's name in Arabic and English; OK looks up the channel's live video on its public
YouTube page (`youtube.com/channel/<id>/live`) and opens that video in the YouTube app the TV already has,
or says the channel is off air. For the [Clean Your TV](../../README.md) launcher it declares a
[module card](../../docs/modules.md), so once you answer **Show card** its tile reads "Sudan TV" with a
green glow; on any other Android TV home it is an ordinary app.

- **Nothing is bundled or played here.** The page holds channel ids only; the video comes from each
  channel's public YouTube live stream, through the YouTube app. No account, no key, no server of its own.
- **Why the lookup:** the TV YouTube apps play `watch?v=` links only and send a channel's `/live` or page
  link to their own Home. So OK fetches the `/live` page once (its canonical link names the live video)
  and hands the YouTube app the watch link. Off air, the page has no such link and a toast says so.
- **The channel list** is `app/src/main/res/values/config.xml`: one line per tile, `Arabic name | English
  name | YouTube channel id` (the `UC...` part of `youtube.com/channel/UC...`). Edit it before you build.
- **The player** is the first installed of the Fire TV YouTube app (`com.amazon.firetv.youtube`), YouTube
  for Android TV (`com.google.android.youtube.tv`) or SmartTube; without any of them the link goes to
  whatever app claims YouTube links, and a toast says so when nothing does.
- Arabic strings when the TV's language is Arabic, English otherwise.

## Permissions

- `INTERNET`: one HTTPS GET of `www.youtube.com/channel/<id>/live` when you press OK on a tile, to read the
  id of the live video. Nothing is sent but the request, nothing is stored, and nothing runs in the
  background.

No network state, no background service, no telemetry, no ads. Backups are off. The manifest declares the
three YouTube packages in `<queries>` only so the app can see which one is installed.

## Build and install

JDK 17 and Android SDK platform 34 with build-tools 34.0.0, as for the launcher:

```bash
cd addons/sudan-tv && ./gradlew assembleRelease
TV_HOST=<tv ip> ../../tools/venv/bin/python -I ../../tools/tv.py --push app/build/outputs/apk/release/app-release.apk /data/local/tmp/addon.apk
TV_HOST=<tv ip> ../../tools/venv/bin/python -I ../../tools/tv.py 'pm install -r /data/local/tmp/addon.apk; rm -f /data/local/tmp/addon.apk'
```

Press Home: the launcher asks once whether to show the card. Change the answer later by holding OK on
Settings, then Module cards.

The release build is signed with the debug key so it installs over ADB like the launcher. Sign your own
builds with your own key if you hand them out; the launcher pins its Show answer to the signing key.

## Credits

Icon and banner drawn for this add-on (the colours of the Sudanese flag and a play mark). The channels
belong to their broadcasters; this add-on links to their public YouTube pages and carries none of their
names, logos or video.

## Tested on

Amazon Fire TV Stick AFTMA08C15 (karat), Fire OS 8.1.8.5, Android 11, with the Fire TV YouTube app: the
page, the D-pad from every edge, a live tile opening its stream and an off-air tile's toast, and the tile in
the launcher's grid.

## Maintainer

[@ZoolCoder](https://github.com/ZoolCoder)
