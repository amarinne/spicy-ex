<div align="center">

# Spicy EX
Spicy Lyrics for Spotify, as an Xposed/LSPosed module.<br>
For the desktop version, check out [spicy-lyrics](https://github.com/amarinne/spicy-lyrics).

<img src="assets/demo.gif" width="320" alt="Spicy EX lyrics demo">

</div>

## Features
- Full-screen synced lyrics — Spicy karaoke wash, per-word animation, interludes.
- Live current line in the player, with a ♪ placeholder on no-lyric tracks.
- Transliteration: Japanese (furigana / romaji / both), Chinese (pinyin / jyutping), Korean / Cyrillic / Greek — optionally per-word.
- Google Translate.
- In-Spotify settings; works even when Spotify itself has no lyrics.
- [Read more](FEATURES_USER.md)

## Install
APK from [Releases](../../releases). 

**Rooted (LSPosed):** install, enable, scope to Spotify — you know the drill.

**Non-rooted (LSPatch):**
Spotify enforces Play Integrity during login. Bypass this using the **Downgrade-Login-Upgrade** method:

1. Download two Spotify APKs: an older version (e.g., `v8.9.18`) and the target version (`v9.1.28.2252`).
2. Patch both APKs with Spicy EX using [LSPatch](https://github.com/JingMatrix/LSPatch) (ensures matching signatures).
3. **Uninstall** current Spotify and **install the patched OLD version**.
4. **Log in** (Email/Password only, no Google/Facebook).
5. **Install the patched NEW version** over the old one as an update.

> [!NOTE]
> Tested on Spotify **v9.1.28.2252**.

> [!WARNING]
> May conflicts with ReVanced / modified Spotify or old Spotify Plus 

## Build
JDK 21 and an Android SDK are required. Gradle wrapper builds should be run with JDK 21; newer JDKs can fail during build-script compilation. The Android app still targets Java 11 bytecode unless that is changed intentionally.

```sh
# Build both debug flavors and copy stamped APKs into artifacts/.
JAVA_HOME=/path/to/jdk21 ./gradlew :app:assembleLiteDebug :app:assembleFullDebug

# Run the primary JVM unit suite (current tests exercise full-flavor romanization code).
JAVA_HOME=/path/to/jdk21 ./gradlew :app:testFullDebugUnitTest
```

`lite` disables transliteration/translation features; `full` enables them and includes the heavier language dependencies. For app-code changes, validate both flavors unless the change is demonstrably scoped to one source set.

Docs-only changes do not require unit/device testing. Device behavior remains the final validation path for UI, hook, and Spotify-host integration changes.

## Credits
- [LeNerd46/SpotifyPlus](https://github.com/LeNerd46/SpotifyPlus)
- [Spikerko/Spicy Lyrics](https://github.com/Spikerko/spicy-lyrics)
- [surfbryce/beautiful-lyrics](https://github.com/surfbryce/beautiful-lyrics)
- [boidushya/better-lyrics](https://github.com/boidushya/better-lyrics) (+ kawarp background)

## License
[AGPL-3.0](LICENSE). See [NOTICE](NOTICE) for attribution.
