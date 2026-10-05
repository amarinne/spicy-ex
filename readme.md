<div align="center">

# Spicy EX
[![Telegram](https://img.shields.io/badge/Telegram-Join%20group-26A5E4?logo=telegram&logoColor=white)](https://t.me/spicyex)

Animated synced lyrics inside Spotify for Android, as an Xposed/LSPosed module.<br>
Unofficial community project — not affiliated with Spotify or Spicy Lyrics.<br>
Desktop: [spicy-lyrics](https://github.com/amarinne/spicy-lyrics) · HyperOS 3 lockscreen/AOD: [HyperGlow](https://github.com/amarinne/hyperglow)

<!-- TODO media: new fast-forwarded phone GIF. -->
<img src="assets/demo.gif" width="320" alt="Spicy EX lyrics in Spotify">
<img src="assets/android-auto.gif" width="480" alt="Spicy EX lyrics on Android Auto">

</div>

## Features
- Synced lyrics with per-word animation, in full screen and in the player.
- Lyrics on Android Auto (root only).
- Readings: Japanese, Chinese, Korean, Cyrillic and Greek.
- Translation with Google, or AI with your own key.
- Lyrics from Spotify, Apple Music, LRCLIB, QQ Music, NetEase and SpicyLyrics.org.
- [All features](FEATURES_USER.md) · [FAQ](FAQ.md)

## Install
Download the APK from [Releases](../../releases).
Then download the language models in Spicy EX settings to enable readings.

**Rooted (LSPosed):** enable the module, select Spotify in its scope, then restart Spotify.

<details>
<summary><b>Non-rooted (LSPatch)</b></summary>

Spotify checks Play Integrity at login. Log in on an old version, then update:

1. Patch an old Spotify (e.g., `v8.9.18`) and the tested version with Spicy EX in [LSPatch](https://github.com/JingMatrix/LSPatch).
2. Uninstall Spotify and install the patched old version.
3. Log in with email and password.
4. Install the patched new version over it.

</details>

> [!NOTE]
> Tested on Spotify **v9.1.80.2221** and **v9.1.88.2204**.
> Can conflict with ReVanced, other modified Spotify builds, or the old Spotify Plus module.

## Android Auto
Requires root. Add Android Auto to the module scope, force-stop Android Auto,
then turn on **Enable Android Auto lyrics** in Spicy EX settings.

## SpicyLyrics.org
Add [Spicy EX from the Spicy Lyrics catalog](https://developers.spicylyrics.org/catalog/spicy-ex) to get a personal client key.
Paste it in Spicy EX settings → Lyrics Sources, then enable SpicyLyrics.org.

## Build
Use JDK 21. Newer JDKs can fail to compile the build scripts.

```sh
JAVA_HOME=/path/to/jdk21 ./gradlew :app:assembleDebug
JAVA_HOME=/path/to/jdk21 ./gradlew :app:testDebugUnitTest
```

To translate the settings, start from `app/translation/strings-template.xml`.

## Credits
- [LeNerd46/SpotifyPlus](https://github.com/LeNerd46/SpotifyPlus)
- [Spikerko/Spicy Lyrics](https://github.com/Spikerko/spicy-lyrics)
- [surfbryce/beautiful-lyrics](https://github.com/surfbryce/beautiful-lyrics)
- [boidushya/better-lyrics](https://github.com/boidushya/better-lyrics) (+ kawarp background)

## License
[AGPL-3.0](LICENSE). See [NOTICE](NOTICE) for attribution.
