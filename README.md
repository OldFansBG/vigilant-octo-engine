# T212 Widgets

Android home-screen widgets for a Trading 212 account, built because the official widget
updates too slowly and shows the same thing to everybody.

- **You design each widget.** Pick what it shows, which figures, in what order, how it is
  sorted, what it is called, and what it looks like. Place it five times and get five
  different widgets.
- **It updates as fast as the API allows.** One second in the live view, down to five in a
  widget, and instantly the moment you turn the screen on — not on the 30-minute timer the
  widget framework imposes.
- **Your API credentials stay sealed on the device.** Hardware-backed encryption, read-only
  scopes, no server of ours anywhere in the path.

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

1. **Generate API credentials.** In the Trading 212 app: **Settings → API (Beta)** →
   generate a key. You get back **two** values, an **API Key** and an **API Secret** — the
   secret is shown *once*, at creation, so copy it there and then. Enable only the read
   permissions; leave every ordering scope off and the credentials physically cannot place or
   cancel a trade. This app only ever issues `GET` requests and has no code path that can
   place an order.

   Do **not** tick "restrict access to trusted IPs" unless you know your phone's public IP is
   fixed — a mobile connection's address changes constantly and every request will be
   refused.

   The API works on **Invest** and **Stocks & Shares ISA** accounts only. CFD and SIPP
   accounts cannot generate a working key.
2. **Open T212 Widgets and paste both values.** Tap **Save and connect**. It verifies them
   against your account before storing anything, and works out the environment for you — if
   you pick Live but the credentials are for Practice, the probe finds that and switches,
   rather than leaving you with an unexplained 401.

### If you get "API key rejected (401)"

The app tries both environments before reporting this, and shows the checklist below in-app.
In order of likelihood:

- **You did not enter the API Secret.** Keys issued now are a *pair*, and Basic auth needs
  both halves. If you no longer have the secret, generate a new key — it is only ever shown
  at creation.
- **The key is IP-restricted.** A phone's address changes; generate one without the
  restriction.
- **Wrong account type.** Only Invest and Stocks & Shares ISA accounts can use the API.
- **The key no longer exists.** Check Settings → API (Beta); generating a new key
  invalidates the old one, and so can a password change.
- **A partial copy.** The app strips invisible characters, but a half-selected value still
  fails.

A **403** is different: the credentials authenticated but lack a permission. Edit the key and
enable the account and portfolio read scopes.

## What you can build

Five widget kinds, each configurable:

| Kind | Shows |
|---|---|
| **Account summary** | Account value, investments value, cost, open P/L and P/L %, change today, free funds, realised result, cash in pies, reserved for orders, holdings count — any of them, in any order |
| **Single holding** | One stock: price, market value, cost paid, quantity, average price, P/L, return %, change today, FX impact |
| **Holdings list** | Your positions, sorted by value / P/L / return / today / name, with up to three columns of your choice |
| **Today's movers** | Biggest risers and fallers since this morning, both ends in one widget |
| **Cash** | Free funds, cash in pies, reserved for orders |

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

1. **A self-rescheduling alarm** at your chosen interval, from 5 seconds up. Each tick
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

**Rate limits are respected, per endpoint.** `/equity/positions` allows one call per second
and `/equity/account/summary` one per five, so they are paced separately — holding both to a
single shared floor is what used to make the value jump in steps. Every response carries
`x-ratelimit-*` headers and the app paces itself from those, falling back to the documented
figures, so it stays correct if Trading 212 retunes a limit. Concurrent requests from
multiple widgets collapse into a single fetch, and a `429` arms a backoff. Ten widgets on
your home screen cost exactly the same API traffic as one.

## Live view

Tap **Open live view** in the app for the fast version:

- **Updates every second** — the fastest the API permits. `/equity/positions` carries live
  prices and per-holding values, so the account total is recomputed each second by swapping
  the summary's investments component for the freshly summed positions. The cash side is
  taken as reported. Nothing is interpolated or invented; you see £130.94 → £130.91 → £130.88
  rather than a jump once a minute.
- **A chart you can scrub.** Drag a finger across it and the headline switches from "now" to
  the moment under your finger, with its timestamp. Ranges from 5 minutes to everything
  recorded. Pick the whole account or any single holding.
- Polling stops the instant the screen is not in the foreground, so it never runs down the
  battery in your pocket. The screen is kept awake while it is open.

Widgets are bounded by what Android lets a background alarm do, so they are best-effort at
short intervals; the live view is the place to actually watch a value move.

## Security

The API credentials are the whole reason to be careful here, so:

- **Encrypted at rest with AES-256-GCM.** Key and secret are sealed into a single ciphertext,
  so one can never be stored without the other. The encryption key is generated in and held
  by the Android Keystore — on most phones inside the secure element, where it cannot be extracted
  even from a rooted device. Only ciphertext is ever written to storage, with a fresh random
  IV per write.
- **Write-only from the UI's point of view.** The setup screen stores credentials and shows a
  4-character fingerprint of the key; no code path displays either value back. If you lose
  them, you generate a new pair.
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

**Never commit API credentials to this repository.** Nothing in the build needs one — the key is
entered on the device at runtime and lives only there.

## Known limits

Honest ones, from the API rather than from this app:

- **Change today is measured from the first update of the day, not the previous close.** The
  public API exposes no previous-close or intraday-open figure, so a true daily change cannot
  be computed. The app records the first values it sees after local midnight and reports
  movement against those. Anything derived from it is labelled *since first update today*.
- **Prices are as fresh as the API makes them,** which is a one-second poll, not a push tick
  feed. Trading 212 publishes no WebSocket or streaming endpoint — the whole API is
  request/response — so one second is the floor, not a limitation of this app. Do not trade
  on these figures.
- **The chart is recorded, not fetched.** The API exposes no intraday price or
  portfolio-value history, only current state and settled historical events. So the chart is
  built from samples this app records while it runs, which is why it starts empty and fills
  in. That also means it shows exactly what your device observed.
- **Sub-minute intervals need the exact-alarm permission** to hold precisely. Without it the
  app asks for a timing window instead and updates drift by a few seconds. It never breaks —
  it just loosens.
- **Current price and average price are quoted in the instrument's currency.** Everything
  else — market value, cost, P/L, FX impact — arrives already converted to your account
  currency by Trading 212, so those figures can be totalled and compared directly.

## API notes

Built against the current public API, as documented at
[docs.trading212.com/api](https://docs.trading212.com/api):

| | |
|---|---|
| Auth | `Authorization: Basic base64(apiKey:apiSecret)`; legacy single-key headers still accepted |
| Live | `https://live.trading212.com` |
| Practice | `https://demo.trading212.com` |
| Account | `GET /api/v0/equity/account/summary` — 1 req / 5s |
| Positions | `GET /api/v0/equity/positions` — 1 req / 1s |
| Pacing | `x-ratelimit-limit` / `-period` / `-remaining` / `-reset` read from every response |
| Streaming | none — the API is request/response only |

Credentials are environment-specific and cannot be used across Live and Practice.

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
