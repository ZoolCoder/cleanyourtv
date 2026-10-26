# Weather, prayer times and the Hijri date

Three header switches a theme can turn on, and one build setting they share.

## The place

`weather` and `prayer_times` need to know where the TV is. That place is the same pair of string
resources the sky uses, in `launcher/app/src/main/res/values/config.xml`, in decimal degrees (south
and west negative), set before the launcher is built:

```xml
<string name="latitude" translatable="false">15.50</string>
<string name="longitude" translatable="false">32.56</string>
```

A theme cannot set it ([sky.md](sky.md) says why: a theme is public, a place is not), so a theme that
turns the pills on says so in its README and leaves the two lines to whoever builds. With the place
empty both pills stay hidden whatever the theme asks; nothing else changes.

## `weather`

A pill in the top bar, left of the Inputs pill: the sky as an emoji (the moon at night), the
temperature rounded to a degree, and one word for the sky (`Clear`, `Partly cloudy`, `Rain`...),
from [Open-Meteo](https://open-meteo.com/)'s free forecast (no key, no account). It is fetched on a
plain thread every 30 minutes while Home is on screen, never in the background; the last reading is
kept in preferences so the pill is filled at once after a restart and stays up to three hours while
the network is away. The pill is display only: it takes no focus and no key.

**This is the one key that needs the `INTERNET` permission**, and the manifest carries that
permission for it. Nothing else in the launcher opens a connection: the permission is used by
`Weather.java` alone, for one GET to `api.open-meteo.com` carrying the two coordinates from
`config.xml` and nothing about the TV. The upstream rule for the reference launcher is no network at
all, so a build without the weather pill can drop the permission from the manifest and the switch
from `config.xml` and lose nothing else.

## `prayer_times`

A pill with the next of the five daily prayers, its time in the TV's clock format and how long until
it (`Asr 15:12 · in 1h 20m`), refreshed on the minute like the clock. The times are computed on the
TV, no network: the standard sun-position formulas with the Egyptian General Authority of Survey's
angles (Fajr 19.5°, Isha 17.5°, Asr at shadow length 1), in the TV's time zone, truncated to the
minute. Sunrise is computed too, for the end of Fajr, but is never shown as a prayer. Another
calculation method is a one-line change of two constants in `PrayerTimes.java`.

## `hijri_date`

The Hijri date under the Gregorian one, from `java.time`'s Umm al-Qura calendar: `14 Jumada I 1448 AH`,
with the month names translated where the launcher has them. When `lunar_date` is also on it wins and
the Hijri line stays off; the two share the line under the date.

## `locale`

Not a header switch but the key that makes the three read well in Arabic: `"ar"` gives Arabic text,
`"ar-EG-u-nu-latn"` Arabic text with Western digits in the clock and the pills. It exists for TVs with
no per-app language setting, Fire OS first of all. The header turns letter spacing off for joined
scripts; the weather pill keeps a left-to-right paragraph so `22°` stays next to its emoji, while the
prayer pill is plain text and reads right to left from the prayer's name.

## Together

The [`sudan/`](../themes/sudan/) theme turns on all four for one TV in Egypt.
