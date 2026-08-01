# Spicy EX Diagnostic Data Policy

Spicy EX sends a diagnostic report only after you open **Report a problem**, review the included
data, accept this policy, and tap **Upload**. There are no background uploads, analytics, remote
configuration, automatic GitHub issues, cookies, or embedded intake credentials.

## Included data

- Your description and chosen category.
- Spicy EX, Spotify, Android, device, build, locale, flavor, and Xposed metadata.
- Privacy-safe lyric-fetch status, provider/language/timing labels, feature availability, HyperGlow
  bridge status, and allowlisted settings.
- If you explicitly run capture: bounded operation events with timestamp, component, operation,
  exception class, and allowlisted context.

## Never included

- Lyrics, song titles, artists, albums, track URIs, artwork identifiers, URLs, or response bodies.
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
version/flavor, device model, and compatibility summary. Private captured events and settings are not
added to the GitHub issue.

To request deletion or redaction, open a Spicy EX issue with the report ID and requested action. Do
not post additional private diagnostic data in GitHub.
