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
- **Receipt scanning.** On *Add purchase*, tap **Scan a receipt** and photograph it (or pick a photo).
  The store, total and date are filled in for you to check. Text recognition runs offline on the
  phone with the open-source Tesseract engine (no Google ML Kit, no network); the photo is held in
  memory only and never saved or uploaded. Picking a photo uses Android's photo picker, so the app
  only sees the one image you choose.
- **Card billing cycles.** Every card closes on its own day. PDF statements are read for their
  billing period, balance, minimum and due date; the app learns each card's closing and due day and
  shows the current cycle (with a projected statement), past cycles checked against the statement
  balance ("✓ Matches the statement"), gaps where a statement is missing, and upcoming bills on Home.
  Budgets still count purchases by the day you made them, so cycles never skew a month. Overlapping
  files (two statements, or the PDF and CSV of the same month) are de-duplicated even when the
  descriptions are worded differently.
- **Budget alerts.** A notification at your warning threshold (default 80%) and when you go over, per
  category and overall. Purchases your spouse enters trigger alerts on your phone too.
- **Savings coach.** Plain-language habits ("About 14 coffee runs a month", "Food delivery 5× a
  month"), easy wins with a dollar value attached, and what that saving becomes over 1, 5, 10 and
  20 years. Includes a *what if I cut back on…* calculator.
- **Insights.** Category breakdown, 6-month trend, top merchants, recurring charges and
  subscriptions, who spent what, day-of-week pattern, and pace vs. budget.
- **Combined and personal views.** A *Combined | Andrew | Caitlin* switch on Home, Activity, Insights,
  Coach and Cards shows the household together or one person's spending. Imported statement lines
  belong to the account's owner (chosen at import, editable under Accounts; "Joint" works too);
  purchases typed in by hand belong to whoever entered them. Budgets stay household-wide and show
  who spent what.
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
alerts fire on both. A quiet "Sharing is on / Live with …" notification keeps the connection alive
while the app is in the background (Android otherwise pauses background apps within seconds); it can
be turned off under *Share with spouse*. Each phone remembers the other's address, so they reconnect
on their own even on networks that block device discovery. Every connection starts with a full
catch-up in both directions, and changes only count as delivered once the other phone confirms it
saved them, so nothing imported on one phone can be silently missed by the other. *Share with
spouse → Status* shows how many transactions and statements each phone holds. When you're apart, each phone keeps working and they catch up automatically
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

If the phones don't connect: make sure both are on the same Wi-Fi network (not a guest network,
which usually blocks phones from seeing each other), open *Share with spouse* and check the warning
under *Status*, then tap **Sync now**. *Connect by IP* (the address is shown under *Status* on the
other phone) works when discovery is blocked.

Truly live sync while the phones are on *different* networks (e.g. one at work) would need an
internet relay server, which would mean your data passing through someone else's computer. This app
deliberately doesn't do that.

## Privacy

- Data lives in the app's private storage on each phone, which Android encrypts on the device.
  Cloud backup is disabled (`allowBackup=false`, and the data-extraction rules exclude everything).
- The release build is not debuggable, so the data can't be pulled off the phone over USB.
- Permissions: local network, notifications, fingerprint, camera (receipts only, asked the first time),
  and a foreground service for background sharing. No contacts, location, storage or accounts.
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

## Third-party components

- [Tesseract4Android](https://github.com/adaptech-cz/Tesseract4Android) and the
  [tessdata_fast](https://github.com/tesseract-ocr/tessdata_fast) English model (Apache 2.0) for offline OCR.
- [PdfBox-Android](https://github.com/TomRoush/PdfBox-Android) (Apache 2.0) for PDF statements.
- AndroidX (Compose, Room, CameraX, Biometric) and kotlinx libraries (Apache 2.0).

## Project layout

```
app/src/main/java/com/budgetingtool/app/
  data/       Room entities + DAOs, repository (import dedupe, reconciliation, LWW merge), categorizer
  importer/   CSV, OFX/QFX, QIF and PDF readers, bank detection
  receipt/    Offline receipt OCR and total/store/date extraction
  insights/   Budget math, insights engine, savings coach
  alerts/     Over-budget notifications
  sync/       Household crypto, wire protocol, LAN discovery + live sync
  ui/         Compose screens, components, theme
```
