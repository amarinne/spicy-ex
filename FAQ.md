# Spicy EX FAQ

### It does not work with my Spotify version

Spicy EX compatibility depends on the Spotify version.

This release was built and tested against Spotify **9.1.68.1888** (`versionCode 144192416`) from
Google Play. Older, beta, ReVanced, or modified builds may not work.

### Full or Lite?

- **Full:** translation, transliteration, romanization, dictionaries, extra fonts.
- **Lite:** smaller APK. No language-processing features.
- Renderer, now-playing card, settings, and HyperGlow bridge exist in both.

### Which LSPosed scope?

Spotify only.

After install or update:

1. Force-stop Spotify.
2. Reopen Spotify.

### Spicy EX settings are missing

Check:

- Module enabled.
- Spotify selected in LSPosed scope.
- Spotify fully restarted.
- Spotify build compatible with current hooks.

Still broken? Submit a compatibility report.

### Does LSPatch work?

Possible, but less reliable. Patched Spotify may fail Play Integrity or login.
Follow the [downgrade-login-upgrade method](README.md#install) in the Install section.

### Lyrics are missing, wrong, or delayed

Lyric availability, text, language, and timing quality can vary by track and upstream source.

### HyperGlow

Optional.

### How do I update?

Install the new APK over the old installation. Then restart Spotify.

Do not install Full and Lite together.

If you use LSPatch, enable **Override version code**.
