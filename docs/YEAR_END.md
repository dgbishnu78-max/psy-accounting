# Closing and reopening a financial year

## Before closing
Open **Financial year**, tap the year, and look at **Checks before closing**:
- **Must fix** items block closing, for example: the year has not ended yet, an earlier year is still open, a product has negative stock at year-end, the stock value does not match the Inventory account, or the trial balance does not balance.
- **Note** items do not block closing but should be reviewed, for example bills with VAT pending verification, or a balance left in Opening Balance Equity.

Also check the Profit and Loss and the Balance Sheet for the year (buttons on the same screen), and confirm VAT figures against the returns you filed. The app does not know what was filed with IRD.

## Closing
Tap **Close this financial year**, tick the confirmation and confirm. The app then:
1. marks the year as *closing in progress*;
2. makes a full **safety backup** and checks it. If the backup fails, the year is **not** closed;
3. in one single step: posts the closing entry (all income and expense accounts for the year are moved to *Retained Earnings*), saves a closing record with a snapshot of the trial balance, stock and next year's opening balances, and marks the year **Closed**.
If the phone switches off in the middle, the next time the app starts it finds the unfinished closing and puts the year back to *Open* (nothing half-done remains).

After closing:
- Nothing dated in the closed year can be added, edited or cancelled.
- Balance-sheet balances carry forward to the next year automatically (there is no separate "opening entry" to duplicate).
- All reports for the closed year still work. **Closing report** shows the year's results, the trial balance before and after closing, closing stock and next year's opening balances.

## Reopening
Only the most recent closed year can be reopened. Tap **Reopen this year**, enter the reason, and confirm. The app makes a safety backup first, then reverses the closing entry and marks the year *Open*. The reason, time and backup are recorded in the audit trail and in the closing history. After making corrections, close the year again; the new closing entry replaces the reversed one, so nothing is counted twice.
