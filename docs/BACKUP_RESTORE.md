# Backup, export and restore

## Automatic daily backup
- The app makes **one backup per day** automatically. Android runs the backup job in the background when it can, and the app also checks every time you open it, so a missed day is caught up as soon as you open the app.
- Daily backups are named by the AD date, e.g. `PSY_Backup_2026-10-10.zip` (the list in the app also shows the BS date). Manual backups are named `PSY_Backup_<date>_<time>_manual.zip`.
- The app keeps the newest **7** daily backups (change it under *Backup and restore > Number of daily backups to keep*; 1 is allowed). Older daily backups are deleted only **after** a new backup has been made and verified, and the only good backup is never deleted.
- The dashboard shows when the last backup was made and warns if it is more than a day old or if the last automatic backup failed.

## Back up now
*Backup and restore > Back up now* makes a backup immediately and checks it. Then tap **Save a copy elsewhere** to put a copy on a USB drive, SD card, Downloads, Google Drive etc.

**Important:** backups made by the app are stored inside the app. If the app is uninstalled or the phone is reset or lost, they are lost too. Save copies elsewhere regularly (for example every week, and always at month-end and year-end).

## Exporting an existing backup
Tap any backup in the list: **Save a copy elsewhere** or **Share** (email, Drive, WhatsApp to yourself).

## Restoring
1. *Backup and restore* > tap a backup in the list > **Restore this backup**, or **Restore from a file** to pick a backup you saved elsewhere.
2. The app shows the backup's date, company, number of documents and app version. Confirm twice.
3. What happens:
   - the backup file is checked completely (format, version, checksums of every table);
   - a **safety backup of your current data** is made first;
   - a new database is built from the backup and checked (balanced entries, links, row counts);
   - only then is it put in place of the current data, and checked again.
   If any step fails, your current data is kept and nothing changes.
4. After restoring, everything entered after the backup's date is no longer in the app (it is still in the safety backup, which you can restore if needed).

## Safety backups
Made automatically before restoring, before closing a year and before reopening a year. They are listed as "Safety". The newest 20 manual backups and the newest 20 safety backups are kept on the phone; older ones are removed automatically, so export the ones you want to keep long-term.

## Backup file format
A `.zip` containing `manifest.json` (format name, version, data version, row counts and SHA-256 checksums) and one JSON file per table. Backups from older app versions can be restored into newer versions.
