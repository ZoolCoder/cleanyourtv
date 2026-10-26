# Sudan News

The latest Sudan headlines on the TV, in an Arabic and an English tab: Radio Dabanga and Sudan Tribune in
Arabic, Radio Dabanga in English, and the Sudan stories of UN News in both. Each headline shows its source
and how long ago it was published; OK shows the story's summary, Back returns to the list. For the
[Clean Your TV](../../README.md) launcher it declares a [module card](../../docs/modules.md), so once you
answer **Show card** its tile reads "Sudan News" with a red glow; on any other Android TV home it is an
ordinary app.

- **Headlines and summaries only.** The app reads each outlet's public RSS feed and shows what the feed
  itself publishes: headline, summary, source and time. Full articles, pictures and video stay on the
  publishers' sites. No account, no key, no server of its own.
- **The feeds** are `app/src/main/res/values/config.xml`: one line per feed, `language | source name |
  RSS feed URL | keyword`. The language (`ar` or `en`) picks the tab; the optional keyword keeps only the
  stories that mention it, for a feed that also covers other countries (UN News Africa is filtered on
  "السودان" and "Sudan"). Edit the list before you build. Two Google News lines in the file's comment add
  many more outlets, as headlines without a summary.
- **When it reads them:** when the app opens, and again when you come back to it after 15 minutes. A
  feed that does not load is skipped and the others are shown.
- The tab follows the TV's language at start: Arabic when the TV is in Arabic, English otherwise. Up from
  the list reaches the tabs.

## Permissions

- `INTERNET`: HTTPS GETs of the feeds listed in `config.xml`, only while the app is open. Nothing is sent
  but the requests, nothing is stored, and nothing runs in the background.

No network state, no background service, no telemetry, no ads. Backups are off.

## Build and install

JDK 17 and Android SDK platform 34 with build-tools 34.0.0, as for the launcher:

```bash
cd addons/sudan-news && ./gradlew assembleRelease
TV_HOST=<tv ip> ../../tools/venv/bin/python -I ../../tools/tv.py --push app/build/outputs/apk/release/app-release.apk /data/local/tmp/addon.apk
TV_HOST=<tv ip> ../../tools/venv/bin/python -I ../../tools/tv.py 'pm install -r /data/local/tmp/addon.apk; rm -f /data/local/tmp/addon.apk'
```

Press Home: the launcher asks once whether to show the card. Change the answer later by holding OK on
Settings, then Module cards.

The release build is signed with the debug key so it installs over ADB like the launcher. Sign your own
builds with your own key if you hand them out; the launcher pins its Show answer to the signing key.

`./gradlew testReleaseUnitTest` runs the feed-reading tests on the JVM.

## Credits

Icon and banner drawn for this add-on (a news page in the colours of the Sudanese flag). The stories
belong to their publishers; this add-on shows what their public feeds publish and carries none of their
logos.

## Tested on

Not yet on a TV.

## Maintainer

[@ZoolCoder](https://github.com/ZoolCoder)
