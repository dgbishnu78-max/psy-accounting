package np.psy.core.db

/**
 * Database schema and versioned migrations. Each migration runs once, inside a transaction,
 * and PRAGMA user_version records the current version. Migrations must only ever be added,
 * never edited, once released.
 */
object Schema {
    const val CURRENT_VERSION = 1

    /** Tables in dependency order (parents first). Used by backup/restore. */
    val TABLES = listOf(
        "settings", "unit", "vat_category", "account", "expense_category", "party", "product",
        "product_unit", "customer_price", "fiscal_year", "voucher", "voucher_line", "stock_move",
        "journal", "journal_line", "fy_closing", "audit_log", "calendar_correction"
    )

    private val V1 = listOf(
        """CREATE TABLE settings (key TEXT PRIMARY KEY NOT NULL, value TEXT)""",
        """CREATE TABLE unit (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            name TEXT NOT NULL UNIQUE COLLATE NOCASE,
            decimals INTEGER NOT NULL DEFAULT 0 CHECK (decimals BETWEEN 0 AND 3),
            active INTEGER NOT NULL DEFAULT 1)""",
        """CREATE TABLE vat_category (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            name TEXT NOT NULL UNIQUE COLLATE NOCASE,
            rate_bp INTEGER NOT NULL CHECK (rate_bp >= 0 AND rate_bp <= 10000),
            kind TEXT NOT NULL CHECK (kind IN ('TAXABLE','ZERO_RATED','EXEMPT')),
            active INTEGER NOT NULL DEFAULT 1)""",
        """CREATE TABLE account (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            code TEXT NOT NULL UNIQUE COLLATE NOCASE,
            name TEXT NOT NULL UNIQUE COLLATE NOCASE,
            type TEXT NOT NULL CHECK (type IN ('ASSET','LIABILITY','EQUITY','REVENUE','COGS','EXPENSE')),
            subtype TEXT NOT NULL DEFAULT '',
            system_key TEXT UNIQUE,
            active INTEGER NOT NULL DEFAULT 1)""",
        """CREATE TABLE expense_category (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            name TEXT NOT NULL UNIQUE COLLATE NOCASE,
            account_id INTEGER NOT NULL REFERENCES account(id),
            active INTEGER NOT NULL DEFAULT 1)""",
        """CREATE TABLE party (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            name TEXT NOT NULL UNIQUE COLLATE NOCASE,
            is_customer INTEGER NOT NULL DEFAULT 0,
            is_supplier INTEGER NOT NULL DEFAULT 0,
            pan TEXT NOT NULL DEFAULT '',
            address TEXT NOT NULL DEFAULT '',
            phone TEXT NOT NULL DEFAULT '',
            email TEXT NOT NULL DEFAULT '',
            active INTEGER NOT NULL DEFAULT 1,
            created_at TEXT NOT NULL)""",
        """CREATE TABLE product (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            code TEXT NOT NULL UNIQUE COLLATE NOCASE,
            name TEXT NOT NULL UNIQUE COLLATE NOCASE,
            category TEXT NOT NULL DEFAULT '',
            unit_id INTEGER NOT NULL REFERENCES unit(id),
            vat_category_id INTEGER NOT NULL REFERENCES vat_category(id),
            sale_price INTEGER NOT NULL DEFAULT 0 CHECK (sale_price >= 0),
            opening_qty INTEGER NOT NULL DEFAULT 0 CHECK (opening_qty >= 0),
            opening_value INTEGER NOT NULL DEFAULT 0 CHECK (opening_value >= 0),
            min_stock INTEGER NOT NULL DEFAULT 0 CHECK (min_stock >= 0),
            active INTEGER NOT NULL DEFAULT 1,
            created_at TEXT NOT NULL)""",
        """CREATE TABLE product_unit (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            product_id INTEGER NOT NULL REFERENCES product(id),
            unit_id INTEGER NOT NULL REFERENCES unit(id),
            factor INTEGER NOT NULL CHECK (factor > 0),
            UNIQUE (product_id, unit_id))""",
        """CREATE TABLE customer_price (
            party_id INTEGER NOT NULL REFERENCES party(id),
            product_id INTEGER NOT NULL REFERENCES product(id),
            price INTEGER NOT NULL CHECK (price >= 0),
            updated_at TEXT NOT NULL,
            PRIMARY KEY (party_id, product_id))""",
        """CREATE TABLE fiscal_year (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            name TEXT NOT NULL UNIQUE,
            start_date TEXT NOT NULL UNIQUE,
            end_date TEXT NOT NULL UNIQUE,
            status TEXT NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','CLOSING','CLOSED')),
            CHECK (end_date > start_date))""",
        """CREATE TABLE voucher (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            type TEXT NOT NULL CHECK (type IN ('SALE','PURCHASE','SALES_RETURN','PURCHASE_RETURN','EXPENSE','STOCK_ADJUSTMENT')),
            number TEXT NOT NULL,
            date TEXT NOT NULL,
            seq INTEGER NOT NULL UNIQUE,
            status TEXT NOT NULL DEFAULT 'POSTED' CHECK (status IN ('POSTED','CANCELLED','SUPERSEDED')),
            party_id INTEGER REFERENCES party(id),
            party_name TEXT NOT NULL DEFAULT '',
            party_pan TEXT NOT NULL DEFAULT '',
            party_address TEXT NOT NULL DEFAULT '',
            ref_no TEXT NOT NULL DEFAULT '',
            original_voucher_id INTEGER REFERENCES voucher(id),
            settle_account_id INTEGER REFERENCES account(id),
            expense_category_id INTEGER REFERENCES expense_category(id),
            vat_class TEXT NOT NULL DEFAULT 'NONE' CHECK (vat_class IN ('NONE','ELIGIBLE','INELIGIBLE','PENDING')),
            description TEXT NOT NULL DEFAULT '',
            gross INTEGER NOT NULL DEFAULT 0,
            discount INTEGER NOT NULL DEFAULT 0,
            taxable INTEGER NOT NULL DEFAULT 0,
            vat INTEGER NOT NULL DEFAULT 0,
            total INTEGER NOT NULL DEFAULT 0,
            notes TEXT NOT NULL DEFAULT '',
            doc_ref TEXT NOT NULL DEFAULT '',
            post_token TEXT UNIQUE,
            replaces_id INTEGER REFERENCES voucher(id),
            replaced_by_id INTEGER REFERENCES voucher(id),
            cancel_reason TEXT NOT NULL DEFAULT '',
            cancelled_at TEXT,
            created_at TEXT NOT NULL,
            updated_at TEXT NOT NULL)""",
        """CREATE UNIQUE INDEX ux_voucher_number ON voucher(type, number) WHERE status IN ('POSTED','CANCELLED')""",
        """CREATE UNIQUE INDEX ux_voucher_supplier_bill ON voucher(type, party_id, ref_no COLLATE NOCASE)
            WHERE status = 'POSTED' AND ref_no <> '' AND type IN ('PURCHASE','EXPENSE')""",
        """CREATE INDEX ix_voucher_date ON voucher(date, seq)""",
        """CREATE TABLE voucher_line (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            voucher_id INTEGER NOT NULL REFERENCES voucher(id),
            line_no INTEGER NOT NULL,
            product_id INTEGER REFERENCES product(id),
            description TEXT NOT NULL DEFAULT '',
            unit_id INTEGER REFERENCES unit(id),
            qty INTEGER NOT NULL DEFAULT 0,
            factor INTEGER NOT NULL DEFAULT 1000,
            base_qty INTEGER NOT NULL DEFAULT 0,
            rate INTEGER NOT NULL DEFAULT 0,
            gross INTEGER NOT NULL DEFAULT 0,
            discount INTEGER NOT NULL DEFAULT 0,
            taxable INTEGER NOT NULL DEFAULT 0,
            vat_category_id INTEGER REFERENCES vat_category(id),
            vat_kind TEXT NOT NULL DEFAULT 'TAXABLE',
            vat_rate_bp INTEGER NOT NULL DEFAULT 0,
            vat INTEGER NOT NULL DEFAULT 0,
            total INTEGER NOT NULL DEFAULT 0,
            cost INTEGER NOT NULL DEFAULT 0,
            original_line_id INTEGER REFERENCES voucher_line(id),
            UNIQUE (voucher_id, line_no))""",
        """CREATE INDEX ix_line_product ON voucher_line(product_id)""",
        """CREATE TABLE stock_move (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            product_id INTEGER NOT NULL REFERENCES product(id),
            voucher_id INTEGER REFERENCES voucher(id),
            line_id INTEGER REFERENCES voucher_line(id),
            date TEXT NOT NULL,
            ord INTEGER NOT NULL,
            kind TEXT NOT NULL,
            qty INTEGER NOT NULL,
            value INTEGER NOT NULL,
            balance_qty INTEGER NOT NULL,
            balance_value INTEGER NOT NULL,
            provisional INTEGER NOT NULL DEFAULT 0)""",
        """CREATE INDEX ix_move_product ON stock_move(product_id, ord)""",
        """CREATE TABLE journal (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            voucher_id INTEGER REFERENCES voucher(id),
            type TEXT NOT NULL,
            number TEXT NOT NULL,
            date TEXT NOT NULL,
            seq INTEGER NOT NULL UNIQUE,
            narration TEXT NOT NULL DEFAULT '',
            status TEXT NOT NULL DEFAULT 'POSTED' CHECK (status IN ('POSTED','REVERSED')),
            reversal_of_id INTEGER REFERENCES journal(id),
            fiscal_year_id INTEGER REFERENCES fiscal_year(id),
            created_at TEXT NOT NULL)""",
        """CREATE INDEX ix_journal_date ON journal(date, seq)""",
        """CREATE INDEX ix_journal_voucher ON journal(voucher_id)""",
        """CREATE UNIQUE INDEX ux_journal_closing ON journal(fiscal_year_id) WHERE type = 'CLOSING' AND status = 'POSTED'""",
        """CREATE UNIQUE INDEX ux_journal_opening ON journal(type) WHERE type IN ('OPENING','OPENING_STOCK') AND status = 'POSTED'""",
        """CREATE TABLE journal_line (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            journal_id INTEGER NOT NULL REFERENCES journal(id),
            account_id INTEGER NOT NULL REFERENCES account(id),
            debit INTEGER NOT NULL DEFAULT 0 CHECK (debit >= 0),
            credit INTEGER NOT NULL DEFAULT 0 CHECK (credit >= 0),
            memo TEXT NOT NULL DEFAULT '',
            CHECK (NOT (debit > 0 AND credit > 0)))""",
        """CREATE INDEX ix_jl_journal ON journal_line(journal_id)""",
        """CREATE INDEX ix_jl_account ON journal_line(account_id)""",
        """CREATE TABLE fy_closing (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            fiscal_year_id INTEGER NOT NULL REFERENCES fiscal_year(id),
            journal_id INTEGER REFERENCES journal(id),
            status TEXT NOT NULL CHECK (status IN ('ACTIVE','REOPENED')),
            closed_at TEXT NOT NULL,
            net_profit INTEGER NOT NULL,
            backup_file TEXT NOT NULL DEFAULT '',
            snapshot TEXT NOT NULL DEFAULT '',
            reopened_at TEXT,
            reopen_reason TEXT NOT NULL DEFAULT '',
            reopen_backup_file TEXT NOT NULL DEFAULT '')""",
        """CREATE UNIQUE INDEX ux_fy_closing_active ON fy_closing(fiscal_year_id) WHERE status = 'ACTIVE'""",
        """CREATE TABLE audit_log (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            at TEXT NOT NULL,
            action TEXT NOT NULL,
            entity TEXT NOT NULL,
            entity_id INTEGER,
            summary TEXT NOT NULL DEFAULT '',
            details TEXT NOT NULL DEFAULT '',
            reason TEXT NOT NULL DEFAULT '')""",
        """CREATE TABLE calendar_correction (
            bs_year INTEGER PRIMARY KEY,
            month_lengths TEXT NOT NULL,
            note TEXT NOT NULL DEFAULT '',
            updated_at TEXT NOT NULL)""",
    )

    val MIGRATIONS: Map<Int, List<String>> = mapOf(1 to V1)

    /** Brings an empty or older database up to [target] (default [CURRENT_VERSION]). */
    fun migrate(db: SqlDb, now: String, migrations: Map<Int, List<String>> = MIGRATIONS, target: Int = CURRENT_VERSION) {
        db.execute("PRAGMA foreign_keys = ON")
        val start = db.userVersion
        if (start > target)
            throw IllegalStateException("This database was created by a newer app version (schema $start). Please install the newer app.")
        for (v in (start + 1)..target) {
            db.transaction {
                for (sql in migrations.getValue(v)) db.execute(sql)
                if (v == 1) Seed.seed(db, now)
                db.userVersion = v
            }
        }
    }
}
