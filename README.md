# Budgeting Tool (Android)

A private, on-device budgeting app for couples. Import statements from any bank, enter purchases as
they happen, get alerts before you overspend, see your habits and what small changes would add up
to, and share everything live with your spouse's phone with no cloud account.

## Features

- **Statement import, done on the phone.** CSV, QFX/OFX (Quicken), QIF and PDF. Columns are
  recognized by name, so files from **Chase, KeyBank, M&T, Empower FCU, Beginning FCU, First Niagara**
  (now KeyBank) and almost any other bank or card work. A preview lets you choose the account,
  untick rows, and flip signs. Re-importing a statement never creates duplicates.
- **Live purchases.** Tap *Add purchase* at the register. Before saving, the app warns you if it will
  push a category or your monthly total over budget. When the statement arrives later, the import
  matches the hand-entered purchase instead of counting it twice.
- **Budget alerts.** A notification at your warning threshold (default 80%) and when you go over, per
  category and overall. Purchases your spouse enters trigger alerts on your phone too.
- **Savings coach.** Plain-language habits ("About 14 coffee runs a month", "Food delivery 5× a
  month"), easy wins with a dollar value attached, and what that saving becomes over 1, 5, 10 and
  20 years. Includes a *what if I cut back on…* calculator.
- **Insights.** Category breakdown, 6-month trend, top merchants, recurring charges and
  subscriptions, who spent what, day-of-week pattern, and pace vs. budget.
- **Spouse sharing without the cloud.** See below.
- **Privacy screen** (on by default) blocks screenshots and hides balances in the recent-apps view.
  **App lock** (optional) asks for fingerprint, face or the phone PIN.

## Sharing with your spouse (and how it stays private)

1. On your phone: **Home → Share with spouse → Create invite**. You get a one-time code like
   `XXXX-XXXX-XXXX`. Tap **Invite via Gmail** to email it, or send it any other way.
2. On your spouse's phone (same Wi-Fi): **Share with spouse → Join**, enter the code.
3. Your phone asks **"Let Sam join?"**. Tap **Allow**.

From then on, while both phones are on the same Wi-Fi (or one phone's hotspot), every purchase,
import, budget or category change appears on the other phone within about a second, and budget
alerts fire on both. When you're apart, each phone keeps working and they catch up automatically
the next time they're on the same network. For a catch-up without shared Wi-Fi, save an encrypted
sync file and send it with Quick Share, Bluetooth or USB.

Why this is safe:

- The **only** thing that travels by email is the invite code. It works once, expires in 24 hours,
  and does nothing unless you tap Allow on your own phone. If someone else used it, you'd see their
  request and deny it.
- The real **household key** is 160 random bits generated on the first phone. It is never shown,
  typed or emailed; it goes to your spouse's phone only after you tap Allow, encrypted with the
  invite code, directly over your Wi-Fi.
- All sync traffic is AES-256-GCM encrypted and authenticated with the household key. A device
  without it can't read anything or inject changes, even on the same network.
- There is no server and no account. Nothing about your finances is sent to Google, GitHub, or us.

Truly live sync while the phones are on *different* networks (e.g. one at work) would need an
internet relay server, which would mean your data passing through someone else's computer. This app
deliberately doesn't do that.

## Privacy

- Data lives in the app's private storage on each phone, which Android encrypts on the device.
  Cloud backup is disabled (`allowBackup=false`, and the data-extraction rules exclude everything).
- The release build is not debuggable, so the data can't be pulled off the phone over USB.
- Permissions: local network, notifications, fingerprint. No contacts, location, storage or accounts.
- No analytics or third-party SDKs that talk to the internet.
- Uninstalling deletes the data on that phone. Keep the other phone paired, or save a sync file, as a
  backup.

## Installing the APK

1. Copy the signed APK to the phone and open it.
2. Android asks to allow installs from that app (e.g. Files). Allow it, then tap **Install**.

Release builds are signed with a key kept only on the build machine, never in this repository.
Keep a private backup of it: future updates must be signed with the same key, or Android will
refuse to install them over the current version.

## How to get each bank's file

| Bank | Where |
| --- | --- |
| Chase | chase.com → account → download icon (*Download account activity*) → CSV or QFX |
| KeyBank | key.com → account → *Download/Export* → CSV or QFX |
| M&T | mtb.com → account → *Download transactions* → CSV or QFX |
| Empower FCU / Beginning FCU | Online banking → account history → *Export/Download* → CSV/QFX/OFX; eStatements are PDF |
| First Niagara | Accounts moved to KeyBank in 2016; old PDF statements still import |

CSV or QFX give exact results. PDF reading is best-effort (scanned, image-only PDFs can't be read).

## Building

Requires JDK 17 and the Android SDK (platform 35).

```bash
./gradlew testDebugUnitTest assembleRelease
```

The APK is written to `app/build/outputs/apk/release/app-release.apk`. To sign it, add
`budgetKeystore=/path/to/keystore.properties` to your user-level `gradle.properties` (outside this
repository); without it, Gradle builds an unsigned release APK.

## Project layout

```
app/src/main/java/com/budgetingtool/app/
  data/       Room entities + DAOs, repository (import dedupe, reconciliation, LWW merge), categorizer
  importer/   CSV, OFX/QFX, QIF and PDF readers, bank detection
  insights/   Budget math, insights engine, savings coach
  alerts/     Over-budget notifications
  sync/       Household crypto, wire protocol, LAN discovery + live sync
  ui/         Compose screens, components, theme
```
