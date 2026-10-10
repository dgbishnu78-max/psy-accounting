# PSY Accounts – user guide

PSY Accounts is an offline accounting app for P.S.Y. Trading Private Limited. Everything is stored on the phone; the app has no internet permission. All amounts are in NPR. Dates are in BS (see `BS_DATES.md`).

## Dashboard
Shows today's sales, this month's sales, gross and net profit, expenses, stock value, VAT payable or credit for the month, the financial year status, low/negative stock, and when the last backup was made. Tap a tile to open the related list or report. The **+ Sale**, **+ Purchase** and **+ Expense** buttons start a new entry. The menu below leads to every part of the app.

## Sales
*Sales > New sales invoice* (or **+ Sale**):
1. Choose the date (BS). The invoice number is given automatically (e.g. `SI-2083/84-0001`).
2. Type the customer name. Existing customers appear as you type; a new name is saved as a new customer automatically. PAN and address fill in for known customers.
3. **Received through**: *On credit* (the customer owes you) or *Cash in Hand*/*Bank Accounts* if paid now.
4. **+ Add item**: type the product name or code (or **+ New product**), choose the unit, enter quantity and rate. The rate is suggested from the customer's special price, or the product's default price. Current stock is shown.
5. Check the totals and tap **Save**.

If there is not enough stock, the app warns you and lists the shortage. You can go back, or **Continue anyway**: the stock then shows as negative and the cost of those items is provisional until the purchase or a stock adjustment is entered (the cost is then recalculated automatically).

After saving: **View**, **PDF** (save, share by WhatsApp/Viber/email, or print), or **New entry**.

## Purchases
Same as sales, with the supplier's name, PAN and **supplier's invoice/bill number** (used to stop the same bill being entered twice). **Input VAT on this bill**: *Eligible* (normal case with a valid tax invoice), *Ineligible* (VAT is added to the cost of the goods), or *Pending verification* (kept aside until you decide; edit the purchase later to change it).

## Returns
Open the original invoice or purchase and tap **Record a sales return / purchase return against this** (or use the menu in the Sales/Purchases list and search for the original). Enter the quantity returned for each item; the app does not allow returning more than was sold/bought. Returned goods come back into stock at their original cost (sales returns) or leave stock at average cost (purchase returns). VAT is reversed at the original rate.

## Correcting or cancelling an entry
Open the entry and tap:
- **Edit (correct this entry)**: change anything and give a reason. The old version stays on record marked *replaced*, its accounting is reversed, and the corrected version is posted (a sales invoice keeps its number).
- **Cancel this entry**: give a reason. It stays on record marked *CANCELLED* and its accounting is reversed.
Entries in a closed financial year cannot be changed. All changes are in the audit trail and in the entry's *History*.

## Expenses
*Expenses > New expense* (or **+ Expense**): date, category (rent, electricity, transport...; add your own), description, supplier (optional), bill number, amount before VAT, VAT on the bill, whether the VAT is eligible / ineligible / pending, and how it was paid. A bill already entered as a purchase cannot be entered again as an expense.

## Products and stock
- **+ New product**: code/SKU, name, category, base unit (the unit stock is counted in), VAT category, default selling price, minimum stock (for low-stock warnings), and opening stock (quantity and total value at cost on the books start date).
- **Other units**: e.g. 1 Box = 12 Pcs. You can then buy or sell in boxes; stock is still counted in pieces.
- The list shows stock on hand and value. Products below the minimum or negative are highlighted.
- **Stock adjustment** (menu): stock IN (found, excess, free goods) or OUT (damage, loss, shortage, own use) with a reason. OUT is valued at average cost; IN at the cost you enter, or the current average.
- Cost method: **moving weighted average**. When any past entry changes, the app recalculates the cost of everything after it, in date order.

## Customers and suppliers
Name, customer/supplier, PAN, address, phone, email. For customers you can set **special prices** per product; these are suggested on new invoices. There is no separate payment module: money received or paid later is recorded with a journal entry (see Accounting).

## Accounting
- **Chart of accounts**: standard accounts are created for you; add your own (e.g. a second bank account, a loan). Tap an account to see its ledger.
- **Journal entries**: for anything that is not a sale, purchase, expense or stock adjustment. Examples: customer pays you (Debit Cash/Bank, Credit Trade Receivables); you pay a supplier (Debit Trade Payables, Credit Cash/Bank); bank deposit; capital introduced; loan; depreciation. Debits must equal credits. The Inventory account cannot be used here.
- **Opening balances**: set the books start date first, then the balances of cash, bank, receivables, payables, capital, loans etc. on that date. Opening stock comes from products. Any difference goes to *Opening Balance Equity*.

## Reports
*Reports* lists: Profit and Loss; bill-wise and product-wise profit; VAT monthly report (summary, sales book, purchase book); registers for sales, returns, purchases, expenses and adjustments; stock on hand, low stock, inventory valuation, stock movement; trial balance; balance sheet; account ledger; general ledger; journal register; audit trail. Choose the period (month, quarter, financial year or custom) and tap **Show report**. Tap a line to open the invoice, journal or account behind it. Use the menu (⋮) to **save/share as PDF**, **CSV (opens in Excel)** or **print**.

VAT reports are worksheets prepared from your entries to help you fill in the return on the IRD system. The app does **not** submit returns and does not guarantee tax compliance. See `VAT_SOURCES.md`.

## Financial year
See `YEAR_END.md`.

## Backup and restore
See `BACKUP_RESTORE.md`.

## Settings
Company details (name, address, phone, email, PAN/VAT number, VAT registration status, logo), invoice footer and terms, whether to print AD dates next to BS dates, document number formats, VAT categories, units, expense categories, financial-year start month, BS calendar corrections, and the audit trail.
