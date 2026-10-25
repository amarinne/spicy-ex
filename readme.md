<div align="center">

# Spicy EX

[![Telegram](https://img.shields.io/badge/Telegram-Join%20group-26A5E4?logo=telegram&logoColor=white)](https://t.me/spicyex)

**Animated synced lyrics inside Spotify for Android.**

[Releases](https://github.com/amarinne/spicy-ex/releases) · [Features](FEATURES_USER.md) · [FAQ](FAQ.md)

<img src="assets/demo.gif" width="320" alt="Spicy EX lyrics in Spotify">
<img src="assets/android-auto.gif" width="480" alt="Spicy EX lyrics on Android Auto">

</div>

Spicy EX is an Xposed/LSPosed module for lyrics, reading aids, translation, and Android Auto.
It is an unofficial project and is not affiliated with Spotify or Spicy Lyrics.

## Install

Download the APK from [Releases](https://github.com/amarinne/spicy-ex/releases).

- **LSPosed:** enable Spicy EX and scope it to Spotify.
- **LSPatch:** see [Does LSPatch work?](FAQ.md#does-lspatch-work).

Restart Spotify after enabling or updating the module.

## Android Auto

Requires root. Add Android Auto to the module scope, force-stop Android Auto,
then turn on **Enable Android Auto lyrics** in Spicy EX settings.

## SpicyLyrics.org

Add [Spicy EX from the Spicy Lyrics catalog](https://developers.spicylyrics.org/catalog/spicy-ex) to get a personal client key.
Paste it in Spicy EX settings → Lyrics Sources, then enable SpicyLyrics.org.

SpicyLyrics.org responses may be cached for offline use, but each response must be refetched or
discarded within 30 days under the [Spicy Lyrics terms](https://developers.spicylyrics.org/terms).
This source is not retained indefinitely in the catalog database like other lyric sources.

## Build

Use JDK 21. Newer JDKs can fail to compile the build scripts.

```sh
JAVA_HOME=/path/to/jdk21 ./gradlew :app:assembleDebug
JAVA_HOME=/path/to/jdk21 ./gradlew :app:testDebugUnitTest
```

To translate the settings, start from `app/translation/strings-template.xml`.

## Links

[Spicy Lyrics desktop](https://github.com/amarinne/spicy-lyrics) · [HyperGlow](https://github.com/amarinne/hyperglow)

## Credits

- [LeNerd46/SpotifyPlus](https://github.com/LeNerd46/SpotifyPlus)
- [Spikerko/Spicy Lyrics](https://github.com/Spikerko/spicy-lyrics)
- [surfbryce/beautiful-lyrics](https://github.com/surfbryce/beautiful-lyrics)
- [boidushya/better-lyrics](https://github.com/boidushya/better-lyrics) (+ kawarp background)
- [paper-design/shaders](https://github.com/paper-design/shaders) (background effects)

## License

[AGPL-3.0](LICENSE). See [NOTICE](NOTICE) for attribution.
