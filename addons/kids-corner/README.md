# Kids' Corner

Arabic children's videos on the TV, one row per channel: Toyor Al Janah, Toyor Baby, Majid Kids, Adam wa
Mishmish, Ahlan Simsim, Cocomelon Arabic and Spacetoon. Each row shows the channel's latest videos with their
thumbnails; OK opens the video in the YouTube app the TV already has. For the [Clean Your TV](../../README.md)
launcher it declares a [module card](../../docs/modules.md), so once you answer **Show card** its tile reads
"Kids' Corner" with a yellow glow; on any other Android TV home it is an ordinary app.

- **Nothing is bundled or played here.** The app reads each channel's public uploads feed
  (`youtube.com/feeds/videos.xml?channel_id=<id>`, Atom, no key, no account) and shows the title and
  thumbnail of each video; the video itself plays in the YouTube app. No server of its own.
- **Parents: check the channel list.** The app shows whatever a channel uploads. It does not filter, it is not
  YouTube Kids and it gives no parental controls. Some channels carry cartoons for older children (Spacetoon
  shows anime), so look through the list in `config.xml` and drop what you do not want before you build.
  The YouTube app's own recommendations, comments and ads are outside this add-on.
- **Shorts are left out.** A Short has a `/shorts/` link in the feed, and the app skips those entries. The
  feed holds the latest 15 uploads, so a channel that posts mostly Shorts shows fewer videos (up to 12 are shown).
- **The channels** are `app/src/main/res/values/config.xml`: one line per row, `Arabic name | English name |
  YouTube channel id` (the `UC...` part of `youtube.com/channel/UC...`). Edit it before you build.
- **The player** is the first installed of the Fire TV YouTube app (`com.amazon.firetv.youtube`), YouTube for
  Android TV (`com.google.android.youtube.tv`) or SmartTube; without any of them the link goes to whatever app
  claims YouTube links, and a toast says so when nothing does. TV YouTube apps play `watch?v=` links only, which
  is why the rows list videos and not channels.
- **When it reads:** when the app opens, and again when you come back to it after 15 minutes. A channel that
  does not load shows a short note and the other rows stay. Thumbnails are small, shrunk to fit and kept in
  memory only while the app is open.
- Arabic strings and right-to-left layout when the TV's language is Arabic, English otherwise.

## Permissions

- `INTERNET`: HTTPS GETs of the uploads feeds listed in `config.xml` and of each video's thumbnail
  (`i.ytimg.com`), only while the app is open. Nothing is sent but the requests, nothing is stored, and
  nothing runs in the background.

No network state, no background service, no telemetry, no ads. Backups are off. The manifest declares the
three YouTube packages in `<queries>` only so the app can see which one is installed.

## Build and install

JDK 17 and Android SDK platform 34 with build-tools 34.0.0, as for the launcher:

```bash
cd addons/kids-corner && ./gradlew assembleRelease
TV_HOST=<tv ip> ../../tools/venv/bin/python -I ../../tools/tv.py --push app/build/outputs/apk/release/app-release.apk /data/local/tmp/addon.apk
TV_HOST=<tv ip> ../../tools/venv/bin/python -I ../../tools/tv.py 'pm install -r /data/local/tmp/addon.apk; rm -f /data/local/tmp/addon.apk'
```

Press Home: the launcher asks once whether to show the card. Change the answer later by holding OK on
Settings, then Module cards.

The release build is signed with the debug key so it installs over ADB like the launcher. Sign your own
builds with your own key if you hand them out; the launcher pins its Show answer to the signing key.

`./gradlew testReleaseUnitTest` runs the feed-reading tests on the JVM.

## Credits

Icon and banner drawn for this add-on (a star, clouds and a play mark in bright flat colours). The channels
and their videos belong to their owners; this add-on links to their public YouTube feeds and carries none of
their logos, artwork or video.

## Tested on

Not yet on a TV.

## Maintainer

[@ZoolCoder](https://github.com/ZoolCoder)
