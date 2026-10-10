# Nepal VAT: sources consulted and what remains unverified

Checked on **2026-10-10 (2083-06-24 BS)** from this development environment. The Inland Revenue
Department (IRD) website could be listed but most of its documents are embedded files that could not
be read automatically, so several items below are taken from secondary sources and are marked.

**This app prepares supporting VAT reports for your review. It does not file VAT returns, does not
connect to the IRD, and does not guarantee legal tax compliance. Have your accountant or tax adviser
check the figures before filing.**

## Sources

| Source | What was used | Status |
| --- | --- | --- |
| IRD, VAT Act 2052 as amended by Finance Act 2082 (listing dated 18 Poush 2082) - https://ird.gov.np/content/13407/value-added-tax-act--2052--as-amended/ | Confirms the Act is current and amended to 2082 | Page listing only; text not machine-readable here |
| IRD category pages: VAT Act, VAT Rules, VAT directives, VAT forms - https://ird.gov.np/category/valueaddedtaxact/ , /category/valueaddedtaxrules/ , /category/vat-2/ , /category/vat-3/ | Location of official documents | Listing only |
| VAT Act 2052 (English text, amendments up to 2066) hosted by ICAN - https://en.ican.org.np/_browsable/file/career/23Value-Added-Tax-Act-2052.pdf | s.7 rate set by Finance Act; Schedule 2 zero-rating of exports; s.14 tax invoice; s.16 records and purchase/sales book; s.17 input tax deduction needs documents; s.18 monthly return within 25 days of month end; s.19 payment within 25 days; s.24 excess credit carried forward | Official text but an older consolidation |
| Baker Tilly Nepal tax portal - https://bakertilly.com.np/tax-portal/vat | 13% standard rate; zero-rated exports; registration thresholds; 25-day return deadline; restrictions on input credit (e.g. 40% on administrative vehicles; none on fuel, alcohol, entertainment unless they are the business's own supplies) | Secondary source, undated (2021 copyright) |
| CollegeNP news on IRD sales/purchase book format (published 2021-07-13) - https://www.collegenp.com/news/inland-revenue-department-ird-published-vat-purchase-and-sales-book-new-format | New purchase/sales book format from FY 2078/79 under VAT Act s.16(2) and VAT Rules r.25 | Secondary source; the format itself is an image that could not be read |

## How the app applies these rules

- **VAT rate**: 13% by default, held in configurable VAT categories (Settings > VAT categories). Exempt and zero-rated (export) categories are also provided.
- **Tax period**: Nepali (BS) calendar month. The VAT report covers one BS month. The dashboard shows the 25th-day due date after month end as a reminder only.
- **Output VAT**: VAT on posted sales invoices, less VAT on posted sales returns (credit notes) in the same month.
- **Input VAT**: purchase and expense VAT is classified per bill as *Eligible*, *Ineligible* or *Pending verification*. Only *Eligible* VAT counts as input credit in the report. Ineligible VAT on stock purchases is added to inventory cost; on expenses it is added to the expense.
- **Partial credit** (for example 40% on administrative vehicles) is **not** calculated automatically. Enter the creditable part as eligible and the rest as ineligible (for example as two expense entries).
- **Excess credit**: the report shows a credit carried forward from earlier months *as calculated from this app's records only*. It cannot know what was actually filed, paid or refunded.
- **Sales book / purchase book**: provided in a layout modelled on the IRD format (date, invoice number, party, PAN, goods, quantity, unit, total, exempt, taxable value and VAT, export / import columns). Compare it with the official IRD format before relying on it.

## Not verified (please confirm with the IRD or your adviser)

1. Exact column layout of the current IRD purchase and sales book (format image could not be read).
2. Time limit for claiming input VAT credit (commonly cited as one year from the invoice date; not confirmed from the official text here).
3. Credit-note / debit-note rules for sales and purchase returns (the app treats returns as adjustments of the month in which the return is dated).
4. Buyer-PAN requirements and amount thresholds on tax invoices (the app warns when PAN is blank on larger sales but does not enforce a threshold).
5. Any VAT changes made by the Finance Act 2083 (2026) - not reviewed.
6. Electronic billing (CBMS / e-invoice) requirements for your turnover level - not implemented.
