# Spicy EX Feature Guide

Spicy EX is an Xposed/LSPosed module that adds a Spicy Lyrics-style experience to Spotify. It
replaces the basic lyric surface with a richer fullscreen lyric screen, a live now-playing lyric
card, language-learning helpers, translation, and visual customization.

This is the user-facing feature list.

## Lyrics Experience

- Fullscreen synced lyrics inside Spotify.
- Spicy-style karaoke wash that follows the current lyric timing.
- Word-aware and sentence-aware lyric fill when timing data supports it.
- Static/unsynced lyric fallback when line timing is unavailable.
- Interlude indicators between sung lines, using dots or a music note.
- Loading, empty, error, and no-lyrics states.
- Optional "stay in lyrics" behavior so the lyric screen remains open across track changes.
- Tap-to-seek on lyric rows, configurable as off, single tap, or double tap.
- Manual sync offset from -5000 ms to +5000 ms.

## Now-Playing Lyrics

- Live current lyric line in Spotify's now-playing view.
- Placeholder display for tracks without lyrics.
- Configurable tap behavior for the now-playing lyric.
- Optional transliteration on the now-playing lyric.
- Configurable now-playing lyric size and font weight.
- Full animation mode or a minimal animation mode.

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
- Google unofficial translation backend.
- Batched translation for faster line processing.
- Configurable target language.
- Translation brightness: dimmed or bright.
- Translation cache avoids repeated requests.

## Visual Customization

- Lyric text size: small, normal, large, xlarge.
- Lyric font: Spotify, Apple.
- Lyric weight: regular, medium, bold.
- Line spacing: compact, default, spacious, more, max.
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

## In-Spotify Settings

- Settings panel inside Spotify.
- Controls grouped by lyrics, transliteration, translation, now-playing, text, animation, and
  background.
- Cache actions:
  - clear translation cache
  - clear lyrics response cache
- Status panel with last lyric state and build version.

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
