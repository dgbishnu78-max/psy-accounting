# Known limitations (version 1.0.0)

Please read before relying on the app.

## Testing
- The accounting engine (calculations, costing, VAT, ledgers, reports, backups, restore, year-end, database upgrades) is covered by 44 automated tests, all passing (`TEST_REPORT.md`).
- **The phone screens have not been tested on a real phone or emulator.** The build environment could not run Android. The APK was compiled, packaged, signed and checked (`aapt dump badging`, `apksigner verify`, and a scan confirming only public Android APIs available on Android 8.0+ are used), but layout problems or crashes on particular screens are possible. Please try every screen with test entries first (and then restore a backup taken before testing, or reinstall, so the test entries do not stay in your books). Report any problem with the screen name and what you tapped; the app also shows crash details on the next start.
- PDF layout was checked in tests using the built-in PDF writer; on the phone, PDFs are drawn with Android's own PDF system, which was not visually checked.

## VAT and tax
- VAT reports are prepared from your entries to help with the IRD return. The app does not submit anything electronically, is not connected to IRD/CBMS, and does not guarantee compliance.
- Points not verified from official sources are listed in `VAT_SOURCES.md` (exact IRD book layouts, input-credit time limit, credit/debit note rules, buyer-PAN thresholds, changes in Finance Act 2083, e-billing/CBMS requirements). Check with your auditor or tax adviser.
- Only standard 13% VAT, exempt and zero-rated categories are set up by default; others can be added.
- No TDS, excise or customs calculations.
- Nepal's IRD has rules for computerised billing software, including registration/approval for some businesses. This app has **not** been registered with or approved by IRD. Check with your tax adviser whether you may issue tax invoices printed from it, or whether to keep using your existing invoice book and record those invoices in the app.

## Calendar
- BS dates for future years (after 2083) are based on published calendars; the official calendar is fixed yearly. If they differ, correct them in Settings (see `BS_DATES.md`).

## Features intentionally not included
- No customer/supplier payment module (as requested). Money received/paid after the invoice is recorded with a journal entry. There is therefore no automatic "amount due per invoice" report; use the Trade Receivables / Trade Payables ledgers.
- No multi-user, cloud sync, or multiple companies. One company per phone.
- No automatic backup to the cloud (the app has no internet permission). Copy backups off the phone yourself.
- No barcode scanning.

## Other
- Uninstalling the app deletes its data and the backups stored inside it.
- Very large data (tens of thousands of invoices) may make some reports slow, because stock costs are recalculated from history for accuracy.
- The release signing key must be kept safe; without it, updates cannot be installed over the existing app (see `BUILD.md`).
