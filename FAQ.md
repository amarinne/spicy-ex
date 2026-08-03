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

Supported, but less reliable than rooted LSPosed. Patched Spotify may fail Play Integrity or login.
Follow the [downgrade-login-upgrade method](README.md#install) in the Install section.

### Translation or romanization is missing

Requires Full version.

### Lyrics are missing, wrong, or delayed

Lyric data is sourced and aggregated through Spicy Lyrics server. Availability, text, language, and
timing quality can vary by track and upstream source.

### Is HyperGlow required?

No. HyperGlow is an optional lockscreen/AOD companion.

### Does HyperGlow receive my Spotify token?

No. Only bounded lyrics, timing, metadata, playback state, and presentation data cross the local
bridge.

### Are diagnostics uploaded automatically?

No. Diagnostic report upload requires your manual confirmation.

### How do I update?

Install the new APK over the old installation. Then restart Spotify.

Do not install Full and Lite together.

If you use LSPatch, enable **Override version code**.
