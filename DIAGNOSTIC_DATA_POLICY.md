# Spicy EX Diagnostic Data Policy

Spicy EX sends a diagnostic report only after you open **Report a problem**, review the included
data, accept this policy, and tap **Upload**. There are no background uploads, analytics, remote
configuration, automatic GitHub issues, cookies, or embedded intake credentials.

## Included data

- Your description and chosen category.
- Spicy EX, Spotify, Android, device, build, locale, flavor, and Xposed metadata.
- Privacy-safe lyric-fetch status, provider/language/timing labels, feature availability, HyperGlow
  bridge status, and allowlisted settings.
- Current song title, artist, album, Spotify track URI, and bounded current
  original/transliterated/translated lyric lines when available.
- If you use AI translation or AI pronunciation: your AI provider choice, the endpoint host (never
  the full URL), model name, readiness state, the outcome of the built-in structured-output test
  including its full request and response exchange, the last AI failure reason with its HTTP status,
  and bounded recent AI translation/pronunciation request payloads with explicit success, failure,
  running, or cancellation state. These payloads can contain lyric text and prompts. The API key and
  provider response bodies are never included. The structured-output test uses a fixed internal
  fixture; captured AI events carry only allowlisted tokens such as provider, status, reason, and
  result.
- If you explicitly run capture: bounded operation events with timestamp, component, operation,
  exception class, and allowlisted context.

## Never included

- Your API keys — not the Spicy EX key, not any provider key, in any form.
- Artwork identifiers or arbitrary URLs.
- Spotify tokens, cookies, account details, Android ID, serial, IMEI, or Wi-Fi SSID.
- Throwable messages, full logcat, LSPosed logs, screenshots, or arbitrary files.
- Your source IP in the application or NocoDB report record. Network infrastructure may process it
  normally while handling the HTTPS request.

## Storage and retention

Reports are private. Accepted report data is retained indefinitely until a maintainer manually
deletes or redacts it. There is no automatic expiry. Temporary capture state expires after 30
minutes. Captured events are deleted after finish, cancellation, timeout, or successful upload.

The report ID is a private-storage reference, not a public download key. It cannot retrieve report
contents from the intake endpoint.

## GitHub issues

Opening GitHub creates a separate public draft containing your description, report ID, Spicy EX
version/flavor, device model, compatibility summary, song identity, provider, language, timing
type, and an AI summary: provider choice, endpoint host, model name, readiness state, and the last
AI failure reason. Lyric text, AI request payloads, and settings values are not added to the GitHub
issue. Screenshots can be attached manually in GitHub when useful.

To request deletion or redaction, open a Spicy EX issue with the report ID and requested action. Do
not post additional private diagnostic data in GitHub.
