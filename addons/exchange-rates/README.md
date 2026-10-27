# Exchange Rates

The Sudanese pound against the currencies Sudanese families receive money in, on one screen of large
tiles: "1 USD = N SDG", with the currency's name in Arabic and English. Out of the box: US dollar, Saudi
riyal, UAE dirham, Egyptian pound, euro, Qatari riyal, Kuwaiti dinar and pound sterling. For the
[Clean Your TV](../../README.md) launcher it declares a [module card](../../docs/modules.md), so once you
answer **Show card** its tile reads "Exchange Rates" with a green glow; on any other Android TV home it is
an ordinary app.

## Which rate this is

**The official reference rate, not the parallel-market rate.** The free exchange-rate services publish the
rate at which banks and the central bank quote the Sudanese pound. The rate that people in Sudan actually
use, the parallel (black) market rate, is different and has been several times higher. This add-on does not
show it: no public, stable, machine-readable source of it exists, and a guessed number would be worse than
none. The screen says so, in Arabic and English, under the headline:

> Official rate · ExchangeRate-API · updated 26 Oct
> This is the official reference rate. The parallel-market rate that people actually use in Sudan is
> different, and usually much higher.

Use it to see the official number, not to price a transfer. Ask the person you are sending money through
for their rate.

- **Source:** [ExchangeRate-API](https://www.exchangerate-api.com) open access endpoint
  `https://open.er-api.com/v6/latest/USD`: no key, no account. It updates once a day; the date on the
  screen is the source's own update time. Other free sources disagree on the Sudanese pound (a
  community-run feed showed about 600 SDG per dollar on the day this was written, ExchangeRate-API about
  459), so the screen names its source.
- **When it reads it:** when the app opens, and again when you come back to it after an hour or more
  (the source allows one request an hour without a rate limit). The last good answer is kept on the TV, so
  without a connection the screen shows it with a notice and its date.
- **The list** is `app/src/main/res/values/config.xml`: one line per tile, `ISO code | Arabic name |
  English name`, in the order shown. Eight tiles fill the screen in two rows of four. A code the source
  does not list shows a dash. Edit the list before you build.
- The page is laid out right to left when the TV is in Arabic; the notice is always shown in both languages.

## On the screen

- **Update time and offline.** The top end says "Updated 26 Oct, 00:02", the source's own update time.
  When a refresh fails, the line under it reads "Offline · showing rates from <date, time>" in amber and a
  **Try again** pill appears beside it (Up from the top row reaches it). Without any saved rates it reads
  "Rates unavailable". A currency the source does not list shows a dash, never 0.
- **Change since the previous update.** The app also keeps the answer before the newest one. Each tile
  shows ▲ or ▼ and the difference in SDG against that earlier update, in plain grey: a dearer dollar is
  good news for some families and bad for others, so it is never green or red. Nothing shows on the first
  run, or when the rate did not move at the precision shown.
- **Large view.** OK on a tile opens the currency full screen: code, English and Arabic names,
  `1 USD = 458.6 SDG` in large type, the inverse (`1 SDG = 0.0022 USD`, two significant digits), the
  change since the previous update with its date, the source and the update time. Back returns to the
  same tile.
- **Pins.** **★ Pin** in the large view moves that currency to the front of the grid, with a ★ on its tile;
  press it again to unpin. Pinned currencies keep their config.xml order among themselves.
- Rates use tabular figures, so the digits line up from tile to tile. The bottom start shows the remote
  keys for the screen you are on.

## Permissions

- `INTERNET`: one HTTPS GET of `open.er-api.com`, only while the app is open. Nothing is sent but the
  request. The last answer and the one before it (about 5 KB of rates each) and the pinned currency codes
  are saved in the app's own preferences, so the screen has something to show offline and can show the
  change; nothing runs in the background.

No network state, no background service, no telemetry, no ads. Backups are off.

## Build and install

JDK 17 and Android SDK platform 34 with build-tools 34.0.0, as for the launcher:

```bash
cd addons/exchange-rates && ./gradlew assembleRelease
TV_HOST=<tv ip> ../../tools/venv/bin/python -I ../../tools/tv.py --push app/build/outputs/apk/release/app-release.apk /data/local/tmp/addon.apk
TV_HOST=<tv ip> ../../tools/venv/bin/python -I ../../tools/tv.py 'pm install -r /data/local/tmp/addon.apk; rm -f /data/local/tmp/addon.apk'
```

Press Home: the launcher asks once whether to show the card. Change the answer later by holding OK on
Settings, then Module cards.

The release build is signed with the debug key so it installs over ADB like the launcher. Sign your own
builds with your own key if you hand them out; the launcher pins its Show answer to the signing key.

`./gradlew testReleaseUnitTest` runs the tests of the rate reading, conversion, number format, change arrows, inverse rate and pin order
on the JVM.

## Credits

Rates By [Exchange Rate API](https://www.exchangerate-api.com): the attribution their free terms ask for,
shown on the screen. The data is theirs and is fetched live, never bundled. Icon and banner drawn for this
add-on (a banknote in the colours of the Sudanese flag).

## Tested on

Not yet on a TV.

## Maintainer

[@ZoolCoder](https://github.com/ZoolCoder)
