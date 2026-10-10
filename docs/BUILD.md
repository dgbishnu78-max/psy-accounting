# Building the APK from source

The app uses only Android's built-in framework (no Google libraries), so it is built with plain command-line tools instead of Android Studio/Gradle. Tested on Ubuntu/Debian Linux with Java 17 or 21.

```bash
git clone https://github.com/dgbishnu78-max/psy-accounting.git
cd psy-accounting
tools/setup-tools.sh          # downloads kotlinc, D8, android.jar, sqlite-jdbc; apt-installs aapt/zipalign/apksigner
tools/build.sh                # compiles, runs all tests, builds build/PSY-Accounts.apk
```

`tools/build.sh` stops if any test fails. The test report is written to `build/test-report.txt`.

## Signing with the release key
Updates install over the existing app only if they are signed with the **same key**. The release key is kept outside the repository (in the project files, `signing/psy-release.keystore`, with its password in `signing/README.txt`). Keep a second copy somewhere safe: if the key is lost, future versions cannot update the installed app and the data would have to be moved with a backup/restore.

```bash
KEYSTORE=/path/to/psy-release.keystore KS_PASS='password' tools/build.sh
```

Without `KEYSTORE`, a throw-away debug key is generated (fine for testing only).

## What the build does
1. `kotlinc` compiles `core/` (all business logic, pure Kotlin) and runs the tests in `core/src/test` against SQLite (JDBC).
2. `kotlinc` compiles `app/` (the Android screens) against `android-34.jar`.
3. `D8` converts the classes to Android bytecode (`classes.dex`, minimum Android 8.0 / API 26).
4. `aapt` packages the manifest and resources; `zipalign` and `apksigner` produce the signed APK.

## Project layout
- `core/src/main/kotlin/np/psy/core/` – accounting engine: calendar, database schema and migrations, documents, costing, journals, VAT, reports, PDF/CSV, backup/restore, year-end.
- `core/src/test/kotlin/` – automated tests (mandatory tests 1–40 plus extra checks).
- `app/src/main/` – Android app: manifest, resources, screens (`screens/`), Android database/PDF adapters, scheduled backup job.
- `docs/` – guides, VAT sources, calendar source comparison.
