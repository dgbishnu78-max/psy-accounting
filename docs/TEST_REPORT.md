# Test report

Run on 2026-10-10 with `tools/build.sh` (JVM, SQLite 3.46 via JDBC). The same SQL runs on the phone's SQLite.

Tests 1–40 of the specification are C01–C05, A06–A20, B21–B27, Y28–Y36, G37–G40. A21x, A22x, C01b and G41 are extra checks.

```
PASS  C01  BS<->AD conversion matches published references and 5 independent datasets
PASS  C01b  Every day round-trips BS->AD->BS over the whole supported range
PASS  C02  Month boundaries and varying month lengths
PASS  C03  Direct BS date entry works for every transaction type
PASS  C04  Chronological sorting and BS reporting periods
PASS  C05  Nepal financial year starts Shrawan 1 and ends on the last day of Asar
PASS  A06  Purchase invoice increases stock and inventory value (VAT excluded)
PASS  A07  Sales invoice decreases stock at weighted-average cost
PASS  A08  Insufficient stock warns, needs explicit confirmation, and records negative stock
PASS  A09  Moving weighted-average cost recalculates after each purchase
PASS  A10  Editing/cancelling a past transaction recalculates later costs
PASS  A11  Recoverable VAT is excluded from inventory cost
PASS  A12  Non-recoverable VAT is added to inventory cost
PASS  A13  Expense VAT eligibility: only eligible VAT is input credit
PASS  A14  Sales, purchases, returns and expenses generate balanced journals
PASS  A15  Trial balance debits equal credits
PASS  A16  Balance Sheet balances
PASS  A17  Profit or loss flows into equity
PASS  A18  Bill-wise and product-wise profit reconcile with sales and COGS
PASS  A19  Duplicate posting is prevented
PASS  A20  Opening balances are not duplicated
PASS  A21x  Sales return restores stock at original cost; purchase return at average
PASS  A22x  Closed-period and customer price rules
PASS  B21  Manual backup and restore work correctly
PASS  B22  Automatic daily backup: once per calendar day with date-based name
PASS  B23  A failed backup never deletes existing valid backups
PASS  B24  Retention keeps the configured number of successful backups
PASS  B25  Corrupt and incompatible backup files are rejected safely
PASS  B26  Restore makes a safety backup of current data first
PASS  B27  Backups include accounting and financial-year history
PASS  Y28  Closing entries balance and zero income/expense accounts
PASS  Y29  Repeated closing attempts do not duplicate closing entries
PASS  Y30  Closing fails safely when the safety backup fails
PASS  Y31  Interrupted closing leaves no partial entries
PASS  Y32  Next-year opening balances are correct
PASS  Y33  Closed-year transactions cannot be edited or added
PASS  Y34  Controlled reopening needs a reason and backup, and is audited
PASS  Y35  Reopening and reclosing do not duplicate balances or closing entries
PASS  Y36  Historical reports stay accessible and consistent after closing
PASS  G37  PDF and CSV exports contain correct data
PASS  G38  Data persists after closing and reopening the app
PASS  G39  No network use: the app declares no INTERNET permission and core has no network code
PASS  G40  Database upgrades preserve existing records
PASS  G41  Calendar corrections change display only and are validated

44 passed, 0 failed
```

Not covered by automated tests: the Android screens (see KNOWN_LIMITATIONS.md).
