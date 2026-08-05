# T212 Widgets

Android home-screen widgets for a Trading 212 account, built because the official widget
updates too slowly and shows the same thing to everybody.

- **You design each widget.** Pick what it shows, which figures, in what order, how it is
  sorted, what it is called, and what it looks like. Place it five times and get five
  different widgets.
- **It updates as fast as Android allows.** Down to every 15 seconds, and instantly the
  moment you turn the screen on — not on the 30-minute timer the widget framework imposes.
- **Your API key stays sealed on the device.** Hardware-backed encryption, read-only scopes,
  no server of ours anywhere in the path.

Not affiliated with, endorsed by, or connected to Trading 212.

---

## Getting the APK

Every push builds one. Two ways to get it:

**From a build.** Open the [Actions tab](../../actions), click the most recent successful
*Build APK* run, and download the `t212-widgets-apk` artifact at the bottom of the page. It
contains `app-debug.apk` — install that one; it is signed and ready. (GitHub wraps artifacts
in a zip, so unzip it first. Downloading artifacts requires being signed in to GitHub.)

**From a release.** Push a tag starting with `v` and the workflow attaches the APKs to a
GitHub release:

```bash
git tag v1.0.0 && git push origin v1.0.0
```

Then install it: on the phone, open the downloaded APK and allow your browser or file
manager to install unknown apps when prompted. Android will warn you — that warning is what
sideloading looks like, and it appears for every app not from the Play Store.

### Debug or release?

The workflow builds both.

| | `app-debug.apk` | `app-release-unsigned.apk` |
|---|---|---|
| Installs directly | Yes | No — needs signing first |
| Size | ~19 MB | ~2.3 MB |
| Code shrinking, log stripping | No | Yes |

Use the debug APK unless you want to sign your own. To get a signed release build, generate
a keystore and add four repository secrets:

```bash
keytool -genkey -v -keystore release.jks -keyalg RSA -keysize 2048 \
        -validity 10000 -alias t212widgets
base64 -w0 release.jks   # paste this into RELEASE_KEYSTORE_BASE64
```

| Secret | Value |
|---|---|
| `RELEASE_KEYSTORE_BASE64` | the base64 output above |
| `RELEASE_KEYSTORE_PASSWORD` | keystore password |
| `RELEASE_KEY_ALIAS` | `t212widgets` |
| `RELEASE_KEY_PASSWORD` | key password |

Keep `release.jks` safe and off GitHub. Android only lets you update an app with the same
key it was signed with — lose it and you have to uninstall before you can install a new
build. (`.gitignore` already blocks `*.jks` and `keystore.properties` from being committed.)

## Setting it up

1. **Make a read-only API key.** In the Trading 212 app: **Settings → API (Beta) → Generate
   key**. Turn on only *Account data* and *Portfolio*. Leave every ordering scope off — then
   the key physically cannot place or cancel a trade, no matter what happens to it. This app
   only ever issues `GET` requests, and there is no code path in it that can place an order.
2. **Open T212 Widgets and paste the key.** Tap **Save and connect**. It verifies the key
   against your account before storing anything, and works out the environment for you — if
   you pick Live but the key is a Practice key, the probe finds that and switches, rather
   than leaving you with an unexplained 401.

### If you get "API key rejected (401)"

The app tries both environments and all three header conventions before reporting this, and
shows the checklist below in-app. In order of likelihood:

- **Re-copy the key.** Copying from a browser or a chat app routinely picks up a trailing
  newline or a zero-width character, neither of which is visible in a password field. (The
  app now strips these, so this is mostly fixed — but a partially-selected key still fails.)
- **Check the key still exists** in Trading 212 → Settings → API (Beta). Generating a new key
  invalidates the old one silently, and so does a password change.
- **Confirm it is from the account you are trying to view**, and that it has not passed an
  expiry date if you set one.
- **Give a brand-new key a minute** — activation is not always instant.

A **403** is a different thing entirely: the key authenticated fine but lacks a scope. Edit
the key and enable *Account data* and *Portfolio*.
3. **Add widgets.** Long-press the home screen → Widgets → *Trading 212 widgets* → drag one
   out. The builder opens automatically. Repeat for each different view you want.
4. **Turn off battery optimisation for the app** when the setup screen offers it. On Samsung,
   Xiaomi, OnePlus and Huawei phones this is the difference between updating every minute and
   updating whenever you happen to open something.

## What you can build

Five widget kinds, each configurable:

| Kind | Shows |
|---|---|
| **Account summary** | Account value, open P/L, change today, invested, free funds, realised result, pie cash, blocked, holdings count — any of them, in any order |
| **Single holding** | One stock: price, market value, quantity, average price, P/L, return %, change today, FX impact |
| **Holdings list** | Your positions, sorted by value / P/L / return / today / name, with up to three columns of your choice |
| **Today's movers** | Biggest risers and fallers since this morning, both ends in one widget |
| **Cash** | Free funds, invested, pie cash, blocked |

Per widget you also choose: title, theme (system / light / dark / transparent), accent
colour, decimal places, row count, compact numbers (`£1.2k`), whether gains and losses are
coloured, whether the header / refresh button / "updated 2m ago" line appear, and a
**privacy mode** that replaces every amount with `••••` so you can hand someone your phone.

Long-press a placed widget and choose **Reconfigure** to change any of it later.

## How the fast updates work

Android caps a widget's built-in refresh timer at 30 minutes and WorkManager's shortest
periodic job at 15. Both are too slow, so this app does not rely on either as its primary
mechanism. `updatePeriodMillis` is set to `0` — the framework timer is switched off entirely
— and three things drive updates instead:

1. **A self-rescheduling alarm** at your chosen interval, from 15 seconds up. Each tick
   fetches, redraws, and books the next tick, so a failure can never break the chain.
2. **Screen-on and unlock triggers.** The instant the display wakes, a refresh fires. By the
   time the home screen is drawn the numbers are current. While the phone is in your pocket
   nothing polls at all — that is where the battery savings come from, and it is why the
   default is *only while the screen is on*.
3. **A 15-minute WorkManager job** as a floor, so a dropped alarm can never leave a widget
   stale indefinitely.

There is also an optional **live mode**: a foreground service that polls on a cadence the
system cannot defer. It costs a permanent notification and noticeably more battery, Android
limits it to roughly six hours a day, and the app hands back to the normal scheduler
automatically when that budget runs out. It is off by default and almost nobody needs it.

**Rate limits are respected.** Trading 212 allows one `/equity/portfolio` call every 5
seconds and one `/account/cash` call every 2. The app enforces a 6-second floor between
network refreshes regardless of your interval setting, collapses concurrent requests from
multiple widgets into a single fetch, and backs off exponentially on a `429`. Ten widgets on
your home screen cost exactly the same API traffic as one.

## Security

The API key is the whole reason to be careful here, so:

- **Encrypted at rest with AES-256-GCM.** The encryption key is generated in and held by the
  Android Keystore — on most phones inside the secure element, where it cannot be extracted
  even from a rooted device. Only ciphertext is ever written to storage, with a fresh random
  IV per write.
- **Write-only from the UI's point of view.** The setup screen can store a key and show a
  4-character fingerprint of it; there is no code path that displays the key back. If you
  forget it, you generate a new one.
- **Backups disabled.** `allowBackup="false"`, plus explicit exclusions in both the legacy
  and Android 12+ backup rules, so the key cannot ride a cloud backup or a phone-to-phone
  transfer off the device.
- **`FLAG_SECURE` on the setup screen.** No screenshots, no screen recording, blank
  thumbnail in the recents list.
- **HTTPS only, system CAs only.** Cleartext is blocked at the platform level with no
  per-domain exception. User-installed CA certificates are excluded from the trust anchors,
  so a proxy on the phone cannot decrypt the request and read the key. Redirects are never
  followed, so the `Authorization` header can never be replayed to another host.
- **Logging stripped from release builds.** ProGuard removes every `android.util.Log` call,
  so the key cannot reach logcat even through a stray debug statement. Exceptions carry only
  a class name, never a request or header.
- **Nothing else in the path.** No analytics, no crash reporting, no third-party SDKs, no
  backend of ours. The key goes from your phone to `trading212.com` and nowhere else. The
  app requests no permission for contacts, location, storage or accounts, because it needs
  none.
- **Optional unlock gate.** Turn on *Require unlock to change the key* and replacing or
  removing the stored key needs your fingerprint, face or PIN.

The keystore key is deliberately **not** marked `setUserAuthenticationRequired`. Widgets
refresh while the phone is locked and in your pocket, where no biometric prompt can be shown;
requiring authentication to decrypt would break every background refresh. The optional gate
above protects the operation an attacker with your unlocked phone would actually want.

**Never commit an API key to this repository.** Nothing in the build needs one — the key is
entered on the device at runtime and lives only there.

## Known limits

Honest ones, mostly from the API rather than from this app:

- **Change today is measured from the first update of the day, not the previous close.** The
  public API exposes no previous-close or intraday-open figure, so a true daily change cannot
  be computed. The app records the first values it sees after local midnight and reports
  movement against those. Anything derived from it is labelled *since first update today*.
- **Per-holding values are in the instrument's currency, not yours.** The API gives prices in
  the instrument's currency and P/L in your account currency, with no FX rate to bridge them.
  So market value for a US stock in a GBP account is shown in USD, tagged as such, and
  account-level totals always come from `/account/cash` rather than from summing positions.
  Return % is computed from local-currency cost and value, which makes it FX-neutral — the
  number you usually want.
- **Prices are as fresh as the API makes them,** which is not a live tick feed. Do not trade
  on these figures.
- **Sub-minute intervals need the exact-alarm permission** to hold precisely. Without it the
  app asks for a timing window instead and updates drift by a few seconds. It never breaks —
  it just loosens.
- **Instrument names cost a large download.** The catalogue endpoint returns several
  megabytes and is rate-limited to one call per 50 seconds, so it is streamed, filtered to
  the tickers you hold, and cached for a week. Turn it off in settings if you would rather
  see `AAPL_US_EQ`.
- **The auth header is auto-detected.** Trading 212's API docs sit behind a login wall, so
  the app probes the documented raw `Authorization: <key>` form plus `Bearer` and
  `X-API-Key`, and stores whichever the server accepts. If they change the convention,
  pressing *Test connection* re-detects it rather than the app simply dying.

## Building locally

```bash
echo "sdk.dir=/path/to/Android/Sdk" > local.properties
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Requires JDK 17+ and Android SDK 35. Nothing else — no API key, no secrets.

## Project layout

```
app/src/main/java/com/t212widgets/
├── api/           Trading 212 REST client and response models
├── core/          Keystore-backed secret storage, settings, formatting
├── data/          Snapshot cache, rate limiting, daily baseline
├── refresh/       Alarm chain, WorkManager floor, optional live service
├── ui/            Setup screen and the widget builder
└── widget/        Glance widget, per-instance config, rendering
```
