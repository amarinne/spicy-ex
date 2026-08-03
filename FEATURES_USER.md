# Spicy EX Feature Guide

Spicy EX is an Xposed/LSPosed module that adds a Spicy Lyrics-style experience to Spotify. It
replaces the basic lyric surface with a richer fullscreen lyric screen, a live now-playing lyric
card, language-learning helpers, translation, and visual customization.

This is the user-facing feature list.

## APK Flavors

- Full includes transliteration, romanization, translation, language dictionaries and extra fonts.
- Lite removes the heavy language-processing assets.
- Both flavors include the complete lyric renderer and HyperGlow bridge.

## Lyrics Experience

- Fullscreen synced lyrics inside Spotify.
- Spicy-style karaoke wash that follows the current lyric timing.
- Line-, word- and syllable-timed lyrics when the source provides them.
- Top-to-bottom, block-horizontal and sentence-horizontal lyric fill.
- Spotlight, glow and interlude animation.
- Static/unsynced lyric fallback when line timing is unavailable.
- Interlude indicators between sung lines, using dots or a music note.
- Loading, empty, error, and no-lyrics states.
- Optional "stay in lyrics" behavior so the lyric screen remains open across track changes.
- Tap-to-seek on lyric rows, configurable as off, single tap, or double tap.
- Manual sync offset from -5000 ms to +5000 ms.
- Jump back to the current lyric after manual scrolling.
- Dedicated lyrics entry when Spotify does not expose its native lyric card.

## Now-Playing Lyrics

- Live current lyric line in Spotify's now-playing view.
- Placeholder display for tracks without lyrics.
- Configurable single- or double-tap shortcut to fullscreen lyrics.
- Main, transliteration, translation or combined secondary line in Full.
- Independent size, weight, animation, glow, fill, overflow and transition settings.

## Transliteration And Reading Aids — Full

- Global transliteration toggle.
- Optional per-word transliteration attached under lyrics.
- In-lyrics transliteration chip that can cycle modes.
- Cycle modes remember the last selected language mode.

Supported reading modes:

- Japanese:
  - furigana only
  - furigana + romaji
  - romaji only
  - off/cycle
- Chinese:
  - Mandarin pinyin
  - Cantonese jyutping
  - optional pinyin tone marks and jyutping tone numbers
  - off/cycle
- Korean:
  - letter-by-letter readable romanization
  - pronunciation mode with sound changes
  - off/cycle
- Cyrillic:
  - Russian mode
  - Ukrainian mode
  - optional hard/soft sign display
  - off/cycle
- Greek:
  - static table romanization.

## Translation — Full

- Optional lyric translation.
- Google unofficial translation backend.
- Batched translation for faster line processing.
- Configurable target language.
- Translation brightness: dimmed or bright.
- Translation cache avoids repeated requests.

Translation uses an unofficial Google endpoint. Eligible lyric text is sent only when translation
is enabled.

## Visual Customization

- Lyric text size: small, normal, large, xlarge or custom.
- Lyric font: Spotify Mix; Apple font in Full.
- Lyric weight: regular, medium, bold.
- Line spacing: compact, default, spacious, more, max or custom.
- Interlude indicator: dots or note.
- Animation style:
  - gradient wash
  - spotlight
- Lyric fill direction:
  - top to bottom
  - left to right block
  - left to right sentence
- Text glow toggle (on by default).
- Blur distant lines toggle.

## Backgrounds

- Optional animated lyric background.
- Kawarp-style album-art ambient background.
- Force-dark background mode.
- Fallback gradient background when album-art colors are too low contrast.

## HyperGlow Integration

- Publishes synchronized lyrics to HyperGlow for HyperOS 3 lockscreen/AOD rendering.
- Lite and Full publish original lyrics, timing, metadata and playback lifecycle.
- Full can also publish generated transliteration and translation.
- No Spotify bearer token is sent to HyperGlow.

## In-Spotify Settings

- Settings panel inside Spotify.
- English and Simplified Chinese settings/report UI. The `Interface language` row stays English so
  it remains findable after a language change.
- Controls grouped by lyrics, transliteration, translation, now-playing, text, animation, and
  background.
- Cache actions:
  - clear translation cache
  - clear lyrics response cache
- Status panel with last lyric state and build version.
- User-triggered private problem reports with a 30-minute privacy-safe event capture, readable JSON
  preview, public data-policy link, and a report ID for a separately opened formatted GitHub issue.
- Reports may include the current track identity and bounded current lyric lines shown in preview.
  They never include Spotify tokens, full logcat, LSPosed logs, or screenshots. Upload is manual and
  is never performed in the background.
- `app/translation/strings-template.xml` provides the complete XML key set for new translations.

## Installation And Distribution

- Rooted install through LSPosed.
- Non-root LSPatch flow documented for patched Spotify APKs.
- APK releases published to the public Spicy EX repository.
- Listed through the LSPosed Modules Repo.

## Current Limits

- Spotify login behavior under non-root LSPatch still depends on the documented downgrade-login-upgrade
  flow.
- Japanese song-specific custom readings can still be wrong when the written lyric uses an artistic
  reading that dictionaries cannot know.
- Mandarin pinyin still has known polyphone/context limits.
- Russian/Ukrainian Cyrillic is romanization, not full pronunciation.
- Greek support is a static romanization table, not a full Greek phonology engine.
