# Features

Most options are available in **Spicy EX settings** inside Spotify.

- **Lyrics:** fullscreen and now-playing synced lyrics with animated timing.
- **Sources:** Spotify, Apple Music, LRCLIB, QQ Music, NetEase, and SpicyLyrics.org.
- **Readings:** Japanese, Chinese, Korean, Cyrillic, and Greek. See [readings and transliteration](TRANSLITERATION.md).
- **Translation:** Google or [AI](#ai).
- **Android Auto:** lyrics appear in the player and dashboard. Root and LSPosed are required.
- **Appearance:** typography, layout, animation, spacing, and animated background effects.
- **Integration:** synchronized lyrics for HyperGlow lockscreen/AOD.

See the [FAQ](FAQ.md) for installation and troubleshooting.

## AI

Use your own API key with Gemini, OpenAI, OpenRouter, DeepSeek, or a custom OpenAI-compatible endpoint.

Translation modes:

- **AI only:** AI translates the original lyrics.
- **Google preview:** Google supplies a temporary translation while AI translates the original lyrics independently.
- **Google draft:** AI refines a Google translation with the original lyrics.

For pronunciation, offline readings take priority. AI can use the original lyrics or refine an available Google reading for uncovered languages.

Translation and pronunciation have separate manual or automatic triggers and custom instructions.

Spicy EX saves results locally and reuses them for the same lyrics and AI configuration.
