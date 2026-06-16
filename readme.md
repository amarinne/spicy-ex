<div align="center">

# Spicy EX
Spicy Lyrics for Spotify, as an Xposed/LSPosed module.

<img src="assets/demo.gif" width="320" alt="Spicy EX lyrics demo">

</div>

## Features
- Full-screen synced lyrics — Spicy karaoke wash, per-word animation, interludes.
- Live current line in the player, with a ♪ placeholder on no-lyric tracks.
- Transliteration: Japanese (furigana / romaji / both), Chinese (pinyin / jyutping), Korean / Cyrillic / Greek — optionally per-word.
- Google Translate.
- In-Spotify settings; works even when Spotify itself has no lyrics.

## Install
APK from [Releases](../../releases). Spicy EX is a module that runs inside Spotify — not an app you open.

**Rooted (LSPosed):** install the APK → enable in LSPosed, scope to Spotify → force-stop Spotify.

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
> May conflicts with ReVanced / modified Spotify or old Spotify Plus — use a clean Spotify.

## Build
JDK 21, Android SDK.

```sh
./gradlew assembleDebug
```

## Credits
- [LeNerd46/SpotifyPlus](https://github.com/LeNerd46/SpotifyPlus)
- [Spikerko/Spicy Lyrics](https://github.com/Spikerko/spicy-lyrics)
- [surfbryce/beautiful-lyrics](https://github.com/surfbryce/beautiful-lyrics)
</content>
