# Play Store Release Plan — Forever Jukebox (`play` flavor)

Goal: ship the `play` product flavor (local-only, on-device analysis) to the
Google Play Store. The `full` flavor stays on GitHub Releases.

Quick answers to the upfront questions:

- **Privacy policy?** Yes — Google **requires** a privacy policy URL for every
  app, even one that collects nothing. This app also sends crash/diagnostic data
  to Sentry, which must be disclosed. See Phase 3.
- **Website or GitHub?** GitHub is fine. The privacy-policy URL and the optional
  "website" field can both point at GitHub (a raw markdown file or GitHub Pages).
  No standalone website needed.

Locked decisions:

- **Public package name**: `com.foreverjukebox.app.play` (the `play` flavor's
  `applicationIdSuffix = ".play"` in [app/build.gradle.kts:164](app/build.gradle.kts)).
  Permanent after first upload.
- **Store display name**: Forever Jukebox. **Developer name**: Creighton Linza.
- **Pricing**: Free (no ads, no IAP). Permanent once published free.
- **Account type**: Personal.
- **Privacy policy hosting**: raw markdown in the repo (`PRIVACY.md`).
- **Deep links**: the `play` flavor ships **without** App Links. The
  `foreverjukebox.com/listen` filter now lives in the `full`-only manifest
  ([app/src/full/AndroidManifest.xml](app/src/full/AndroidManifest.xml)), so
  there's no `assetlinks.json` requirement for the Play release.
- **Coil**: removed from the `play` build (moved to `fullImplementation`) — the
  local-only flavor never loads remote images.
- **Google Cast**: kept as a dependency but verified fully gated in `play`.
  Every Cast entry point is behind `appMode == AppMode.Server`, which is
  unreachable when `SERVER_MODE_AVAILABLE=false` (all mode writes resolve to
  `AppMode.Local`). The Cast button never renders, `castEnabled` is always
  false, and `CastContext` never initializes — the library ships dormant. No
  Cast UI, behavior, or permissions surface in the Play build.

---

## Completed so far

Done in-repo (no Play Console account needed yet):

- ✅ **Privacy policy written** → [PRIVACY.md](PRIVACY.md). Covers on-device
  local mode, Sentry crash diagnostics (PII off), no ads/accounts/data sale.
- ✅ **Store listing copy drafted** → [STORE_LISTING.md](STORE_LISTING.md):
  app name, short + full description, category/tags, and Data Safety answers.
- ✅ **Build prep for the `play` flavor**: package set to
  `com.foreverjukebox.app.play`; deep links moved to `full`-only; Coil removed
  from `play`; Google Cast verified inert in `play`.

Still requires the developer account / Console to finish (Phases 1, 5–7) plus
graphics + screenshots (Phase 4) and committing `PRIVACY.md` to get its public
URL (Phase 3).

---

## Phase 1 — Google Play Developer account

- [ ] Create a Google account (or use an existing one) to own the developer
  profile. `creightonlinza@gmail.com` works.
- [ ] Register at https://play.google.com/console — **one-time $25 USD** fee.
- [ ] Choose account type: **Personal** (vs Organization). Personal is simplest
  for a solo open-source app.
- [ ] Complete **identity verification** (Google now requires legal name,
  address, phone, and a government ID / D-U-N-S for orgs). This can take a few
  days — start early; it gates publishing.
- [ ] For a personal account created recently, Google may require **closed
  testing with 12+ testers for 14 days** before you can apply for production
  access. Check the Console's requirements for your account; if it applies,
  budget two weeks and recruit testers early (see Phase 6).

## Phase 2 — Build a release App Bundle (.aab)

Play requires an **Android App Bundle (.aab)**, not an APK.

- [ ] Ensure release signing is configured. The repo reads
  `keystore.properties` or `ANDROID_*` env vars
  ([app/build.gradle.kts:45](app/build.gradle.kts)). `release.keystore` is
  present in the repo root.
- [ ] **Adopt Play App Signing** (recommended/default). You upload with an
  **upload key**; Google holds the real app-signing key. Implication: the
  SHA-256 in the README
  (`B5:30:EB:...`) is the GitHub build's signature — **Play installs will have a
  different signature** (Google's). That's expected; just don't tell users the
  two builds are byte-identical.
- [ ] Build the bundle for the `play` flavor + `release` type:
  ```bash
  ./gradlew bundlePlayRelease
  ```
  Output: `app/build/outputs/bundle/playRelease/app-play-release.aab`
- [ ] **versionCode** must be unique and strictly increasing per upload. It's
  derived from CI (`GITHUB_RUN_NUMBER + APP_VERSION_CODE_BASE`,
  [app/build.gradle.kts:66](app/build.gradle.kts)). Decide how you'll set a
  stable, monotonic versionCode for Play uploads (e.g. a dedicated env value)
  so you don't collide or go backwards.
- [x] Verify the bundle installs and runs from a release build — confirmed.
- [x] Sanity-check that **server-mode UI is hidden** in the `play` build
  (`SERVER_MODE_AVAILABLE=false`) — confirmed. (Cast gating was also verified
  statically; see "Google Cast" in Locked decisions.)

## Phase 3 — Privacy policy

- [x] Write a short privacy policy → [PRIVACY.md](PRIVACY.md). Covers on-device
  local mode, Sentry crash diagnostics (`send-default-pii=false`,
  [AndroidManifest.xml:53](app/src/main/AndroidManifest.xml)), no
  ads/analytics-beyond-crashes/accounts/data sale, and a contact email.
- [ ] **Commit & push `PRIVACY.md` to `main`** so it has a public raw URL:
  `https://raw.githubusercontent.com/creightonlinza/forever-jukebox-android/main/PRIVACY.md`
  (hosting decision: raw repo markdown).
- [ ] Paste the URL into Play Console → Policy → App content → Privacy policy.

## Phase 4 — Store listing assets (graphics + screenshots)

Required graphics:

- [ ] **App icon** — 512×512 PNG (32-bit, with alpha). Can be derived from
  `ic_launcher`.
- [ ] **Feature graphic** — 1024×500 PNG/JPG (shown at top of listing).
- [ ] **Phone screenshots** — 2–8 images. JPG/PNG, 16:9 or 9:16, each side
  320–3840px. Capture from a real device or emulator:
  - Suggested shots: mode picker, local file analysis in progress, the
    visualization playing, fullscreen visualization, settings/theme toggle.
  - `adb exec-out screencap -p > shot.png` is the easiest capture path.
- [ ] *(Optional)* 7" and 10" tablet screenshots if you want tablet placement.

Listing text — all drafted in [STORE_LISTING.md](STORE_LISTING.md), ready to
paste once the Console listing exists:

- [x] **App name** (≤30 chars) — "Forever Jukebox".
- [x] **Short description** (≤80 chars).
- [x] **Full description** (≤4000 chars) — local on-device analysis,
  visualization, beat-accurate playback; notes open source (AGPLv3).
- [x] App category (Music & Audio), tags, contact email, website
  (GitHub repo URL).

## Phase 5 — Play Console "App content" declarations

These are all in Console → Policy → App content:

- [ ] **Privacy policy URL** (Phase 3).
- [ ] **Data safety form** — declare Sentry crash data (answers drafted in
  [STORE_LISTING.md](STORE_LISTING.md), just transcribe into the Console):
  - Data type: *App activity / Crash logs* + *Device or other IDs / Diagnostics*.
  - Collected, **not** shared, used for app functionality / diagnostics.
  - Processed by a third party (Sentry).
  - Encrypted in transit: yes. User can request deletion: per your policy.
- [ ] **Content rating questionnaire** → generates IARC ratings (this app is
  benign; expect "Everyone").
- [ ] **Target audience & content** — set age groups (not aimed at children;
  avoids Families policy obligations).
- [ ] **Ads declaration** — "No ads".
- [ ] **App access** — "All functionality available without special access"
  (no login). True for the `play` local-only flavor.
- [ ] **Government apps / financial / health** — all "No".
- [ ] **News app** — "No".
- [ ] **Data collection / COPPA / US state privacy** prompts as presented.

## Phase 6 — Testing track (likely prerequisite for new accounts)

- [ ] Create an **Internal testing** release first (instant, up to 100 testers)
  to validate the upload, signing, and install flow.
- [ ] If your account requires it: run **Closed testing** with **≥12 testers for
  14 continuous days**, then apply for production access. Recruit testers now.
- [x] No App Links to verify for the `play` flavor — the
  `foreverjukebox.com/listen` deep link is `full`-flavor only
  ([app/src/full/AndroidManifest.xml](app/src/full/AndroidManifest.xml)), so no
  `assetlinks.json` work is needed for the Play release.

## Phase 7 — Production release

- [ ] Promote the tested build to **Production**, choose rollout %
  (staged rollout recommended, e.g. 20% → 100%).
- [ ] Select countries/regions.
- [ ] Submit for review. First review can take a few days.
- [ ] After approval, monitor Sentry + Play Console crash/ANR vitals.

---

## Open items

All upfront decisions are locked (see "Locked decisions" above). Remaining work
is execution of the phases.
