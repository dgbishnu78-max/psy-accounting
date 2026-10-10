# Using Bikram Sambat (BS) dates

All dates in the app are entered and shown in **BS**. The matching AD date is always shown underneath so you can check it.

## Entering a date
- Type it as **YYYY-MM-DD**, for example `2083-06-24` (24 Asoj 2083). `2083/6/24` also works.
- Or tap **Calendar** to pick it: choose the year and month at the top, then tap the day. Saturday is shown in red. **Today** picks today's date.
- Under the field you will see the AD date and the weekday, e.g. *= 2026-10-10 AD (Saturday)*. If you see *Not a valid BS date*, the day does not exist in that month (for example Asoj has 31 days in 2083 but some months have 29 or 32).

## Months
1 Baisakh, 2 Jestha, 3 Asar, 4 Shrawan, 5 Bhadra, 6 Asoj, 7 Kartik, 8 Mangsir, 9 Poush, 10 Magh, 11 Falgun, 12 Chaitra.

## Financial year
The Nepal financial year runs from **1 Shrawan to the last day of Asar**. For example FY 2083/84 = 1 Shrawan 2083 to 31/32 Asar 2084. Document numbers include the year, e.g. `SI-2083/84-0001`, and restart at 0001 each year.

## Reports by period
Report screens have quick buttons: **This month** (current BS month), **Month...** (any of the last 36 BS months), **Quarter...** (Shrawan–Asoj, Kartik–Poush, Magh–Chaitra, Baisakh–Asar), **Financial year...**, or any custom From/To dates.

## How accurate is the calendar?
The app contains BS month lengths for 2000–2099 BS. They were compared across five independent published datasets; all agree for 2000–2083 (one source differs in 2062 and was outvoted). Future years were spot-checked against Hamro Patro. Details: `docs/calendar/source-comparison.json`.

Future BS month lengths are decided each year by the Nepal Panchanga Nirnayak Samiti. If the official calendar for a future year turns out different, open **Settings > BS calendar check and correction**, choose the year, and type the correct number of days for each month. Entries keep their AD date; only how they are shown in BS changes. Years that are already closed cannot be affected.
