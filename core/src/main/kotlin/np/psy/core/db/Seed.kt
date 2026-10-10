package np.psy.core.db

/**
 * Initial configuration for a new, empty database. This is configuration only
 * (chart of accounts, units, VAT categories, company name) - never transactions.
 */
object Seed {
    fun seed(db: SqlDb, now: String) {
        val settings = linkedMapOf(
            "company_name" to "P.S.Y. Trading Private Limited",
            "company_address" to "Shankhamul-31, Kathmandu, Nepal",
            "company_phone" to "",
            "company_email" to "",
            "company_pan" to "",
            "vat_registration_status" to "NOT_SET",
            "currency" to "NPR",
            "fy_start_month" to "4",
            "number_format.SALE" to "SI-{FY}-{N4}",
            "number_format.SALES_RETURN" to "CN-{FY}-{N4}",
            "number_format.PURCHASE" to "PB-{FY}-{N4}",
            "number_format.PURCHASE_RETURN" to "DN-{FY}-{N4}",
            "number_format.EXPENSE" to "EX-{FY}-{N4}",
            "number_format.STOCK_ADJUSTMENT" to "SA-{FY}-{N4}",
            "number_format.JOURNAL" to "JV-{FY}-{N4}",
            "invoice_footer" to "Thank you for your business.",
            "invoice_terms" to "",
            "print_ad_dates" to "1",
            "backup_retention_days" to "7",
            "logo_jpeg_base64" to "",
            "created_at" to now,
        )
        for ((k, v) in settings) db.execute("INSERT INTO settings(key, value) VALUES (?, ?)", k, v)

        listOf("Pcs" to 0, "Kg" to 3, "Jar" to 0, "Box" to 0, "Ltr" to 3, "Packet" to 0, "Dozen" to 0)
            .forEach { (n, d) -> db.execute("INSERT INTO unit(name, decimals) VALUES (?, ?)", n, d) }

        db.execute("INSERT INTO vat_category(name, rate_bp, kind) VALUES ('VAT 13%', 1300, 'TAXABLE')")
        db.execute("INSERT INTO vat_category(name, rate_bp, kind) VALUES ('VAT Exempt', 0, 'EXEMPT')")
        db.execute("INSERT INTO vat_category(name, rate_bp, kind) VALUES ('Zero-rated (Export) 0%', 0, 'ZERO_RATED')")

        // code, name, type, subtype, system key
        val accounts = listOf(
            arrayOf("1000", "Cash in Hand", "ASSET", "CURRENT", "CASH"),
            arrayOf("1010", "Bank Accounts", "ASSET", "CURRENT", "BANK"),
            arrayOf("1100", "Trade Receivables (Debtors)", "ASSET", "CURRENT", "RECEIVABLES"),
            arrayOf("1200", "Inventory (Stock in Trade)", "ASSET", "CURRENT", "INVENTORY"),
            arrayOf("1300", "Input VAT Recoverable", "ASSET", "CURRENT", "VAT_INPUT"),
            arrayOf("1310", "Input VAT - Pending Verification", "ASSET", "CURRENT", "VAT_INPUT_PENDING"),
            arrayOf("1400", "Advances and Deposits", "ASSET", "CURRENT", null),
            arrayOf("1500", "Property, Plant and Equipment", "ASSET", "NON_CURRENT", null),
            arrayOf("2000", "Trade Payables (Creditors)", "LIABILITY", "CURRENT", "PAYABLES"),
            arrayOf("2100", "Output VAT Payable", "LIABILITY", "CURRENT", "VAT_OUTPUT"),
            arrayOf("2200", "Other Payables", "LIABILITY", "CURRENT", null),
            arrayOf("2300", "Loans and Borrowings", "LIABILITY", "NON_CURRENT", null),
            arrayOf("3000", "Share Capital / Owner's Capital", "EQUITY", "", "CAPITAL"),
            arrayOf("3100", "Retained Earnings (Accumulated Profit/Loss)", "EQUITY", "", "RETAINED_EARNINGS"),
            arrayOf("3200", "Opening Balance Equity", "EQUITY", "", "OPENING_EQUITY"),
            arrayOf("4000", "Sales", "REVENUE", "SALES", "SALES"),
            arrayOf("4010", "Sales Returns", "REVENUE", "SALES", "SALES_RETURNS"),
            arrayOf("4020", "Sales Discounts", "REVENUE", "SALES", "SALES_DISCOUNT"),
            arrayOf("4100", "Other Income", "REVENUE", "OTHER", null),
            arrayOf("5000", "Cost of Goods Sold", "COGS", "", "COGS"),
            arrayOf("5010", "Inventory Adjustments (Shortage/Excess)", "COGS", "", "INV_ADJ"),
            arrayOf("6000", "Rent", "EXPENSE", "OPERATING", null),
            arrayOf("6010", "Electricity", "EXPENSE", "OPERATING", null),
            arrayOf("6020", "Transport", "EXPENSE", "OPERATING", null),
            arrayOf("6030", "Salaries and Wages", "EXPENSE", "OPERATING", null),
            arrayOf("6040", "Office Expenses", "EXPENSE", "OPERATING", null),
            arrayOf("6050", "Repairs and Maintenance", "EXPENSE", "OPERATING", null),
            arrayOf("6090", "Other Operating Expenses", "EXPENSE", "OPERATING", null),
            arrayOf("7000", "Other Expenses", "EXPENSE", "OTHER", null),
        )
        for (a in accounts) db.execute("INSERT INTO account(code, name, type, subtype, system_key) VALUES (?,?,?,?,?)", a[0], a[1], a[2], a[3], a[4])
        val cats = listOf("Rent" to "6000", "Electricity" to "6010", "Transport" to "6020", "Salaries" to "6030",
            "Office Expenses" to "6040", "Repairs" to "6050", "Other Operating Expenses" to "6090")
        for ((n, code) in cats) db.execute("INSERT INTO expense_category(name, account_id) SELECT ?, id FROM account WHERE code = ?", n, code)
        db.execute("INSERT INTO audit_log(at, action, entity, summary) VALUES (?, 'CREATE', 'database', 'New company database created')", now)
    }
}
