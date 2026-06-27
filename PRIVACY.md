# Privacy Policy — Forever Jukebox

**Last updated: 2026-06-26**

Forever Jukebox ("the app") is a free, open-source music visualization and
playback app developed by Creighton Linza ("we", "us"). This policy explains
what the app does and does not collect.

## Summary

- The app has **no accounts, no ads, and no in-app purchases**.
- Your audio files and listening activity stay **on your device** and are not
  uploaded to us.
- The only data that leaves your device is **anonymous crash and error
  diagnostics**, used solely to find and fix bugs.

## What the app does on your device

The Play Store version of Forever Jukebox runs in **local mode**: you choose an
audio file from your device, and all analysis and visualization happen on-device.

- Audio files you select are read locally for analysis and playback. They are
  **not** copied off your device or sent to us.
- Analysis results are cached in the app's private storage to speed up reloads.
  You can clear this cache anytime from Settings.

## Diagnostics we collect (Sentry)

To keep the app stable, we use [Sentry](https://sentry.io) to collect
**crash reports and error diagnostics** when something goes wrong. This may
include:

- The crash/error stack trace and a snapshot of the app's UI layout structure
  (no audio, no file contents, no screen captures).
- Device model, operating system version, and app version.

We have **disabled personally identifiable information (PII)** in our Sentry
configuration, so the app does not intentionally send your IP address, device
name, or request headers. This data is used **only** for diagnosing and fixing
crashes and errors, is **not sold**, and is **not shared** with anyone other
than Sentry acting as our processor. It is transmitted over an encrypted
connection.

## Permissions

- **Internet** — used only to send anonymous crash diagnostics.
- **Foreground service / media playback / notifications / wake lock** — used to
  play audio reliably and show playback controls.

## Children

The app is not directed at children and does not knowingly collect data from
children.

## Data deletion

Crash diagnostics are anonymous and not tied to an account, so there is no
personal profile to delete. To remove locally cached analysis data, clear the
cache from the app's Settings or uninstall the app. For any privacy question or
request, contact us using the details below.

## Open source

Forever Jukebox is open source (AGPLv3). You can review exactly what the app
does in the source code:
<https://github.com/creightonlinza/forever-jukebox-android>

## Contact

Creighton Linza — creightonlinza@gmail.com
