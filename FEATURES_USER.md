# Spicy EX Feature Guide

Spicy EX is an Xposed/LSPosed module that adds a Spicy Lyrics-style experience to Spotify. It
replaces the basic lyric surface with a richer fullscreen lyric screen, a live now-playing lyric
card, language-learning helpers, translation, and visual customization.

This is the user-facing feature list.

## APK Contents

- The APK includes the lyric renderer, reading and translation engines, Apple font, and HyperGlow bridge.
- Download the language model pack in Spicy EX settings to enable dictionary-based readings.

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
- Adaptive up/down arrow, static arrow, or waveform for the Follow lyrics button.
- Lyrics in picture-in-picture, with optional album artwork in landscape.
- Dedicated lyrics entry when Spotify does not expose its native lyric card.

## Lyrics Sources

- Spotify, Apple Music, AMLL, LRCLIB, QQ Music, NetEase, and SpicyLyrics.org.
- SpicyLyrics.org requires your personal client key from the [Spicy Lyrics catalog](https://developers.spicylyrics.org/catalog/spicy-ex).

## Android Auto

- Lyrics in the full player and available dashboard cards.
- Main lyrics, with selected readings or translations in the full player when they fit.
- Requires root and LSPosed.
- Add Android Auto to the module scope.
- Force-stop Android Auto.
- Turn on **Enable Android Auto lyrics** in Spicy EX settings.

## Now-Playing Lyrics

- Live current lyric line in Spotify's now-playing view.
- Placeholder display for tracks without lyrics.
- Configurable single- or double-tap shortcut to fullscreen lyrics.
- Main, transliteration, translation or combined secondary line.
- Independent size, weight, animation, glow, fill, overflow and transition settings.

## Transliteration And Reading Aids

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

## Translation

- Optional lyric translation.
- Google unofficial translation backend, or AI translation with your own provider key.
- Batched translation for faster line processing.
- Configurable target language.
- Translation brightness: dimmed or bright.
- Translation cache avoids repeated requests.

Google translation uses an unofficial endpoint. Translation sends eligible lyric text to the selected provider when enabled.

## Visual Customization

- Lyric text size: small, normal, large, xlarge or custom.
- Lyric font: Spotify Mix or Apple font.
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
- Smooth album-art ambient background.
- Force-dark background mode.
- Fallback gradient background when album-art colors are too low contrast.

## HyperGlow Integration

- Publishes synchronized lyrics to HyperGlow for HyperOS 3 lockscreen/AOD rendering.
- Publishes original lyrics, timing, metadata, generated transliteration, and
  translation together with the playback lifecycle.
- No Spotify bearer token is sent to HyperGlow.

## In-Spotify Settings

- Settings panel inside Spotify.
- Settings languages: English, Simplified Chinese, Japanese, Korean, and Russian.
  The `Interface language` row stays English so it remains easy to find.
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
