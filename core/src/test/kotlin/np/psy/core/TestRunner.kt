package np.psy.core

/** Minimal test runner (no external test framework needed). */
object T {
    class Case(val id: String, val name: String, val body: () -> Unit)
    val cases = ArrayList<Case>()
    fun test(id: String, name: String, body: () -> Unit) { cases.add(Case(id, name, body)) }

    fun eq(expected: Any?, actual: Any?, msg: String = "") { if (expected != actual) throw AssertionError("$msg expected <$expected> but was <$actual>") }
    fun ok(cond: Boolean, msg: String) { if (!cond) throw AssertionError(msg) }
    inline fun <reified E : Throwable> fails(msg: String = "", body: () -> Unit): E {
        try { body() } catch (e: Throwable) { if (e is E) return e; throw AssertionError("$msg expected ${E::class.simpleName} but got $e", e) }
        throw AssertionError("$msg expected ${E::class.simpleName} but nothing was thrown")
    }
}

fun main(args: Array<String>) {
    CalendarTests.register(); AccountingTests.register(); BackupTests.register(); YearEndTests.register(); GeneralTests.register()
    val filter = args.firstOrNull()
    var pass = 0; var fail = 0
    val report = StringBuilder()
    for (c in T.cases.filter { filter == null || it.id.contains(filter) || it.name.contains(filter, true) }) {
        try { c.body(); pass++; report.append("PASS  ${c.id}  ${c.name}\n"); println("PASS  ${c.id}  ${c.name}") }
        catch (e: Throwable) { fail++; report.append("FAIL  ${c.id}  ${c.name}: $e\n"); println("FAIL  ${c.id}  ${c.name}: $e"); e.printStackTrace(System.out) }
    }
    println("\n$pass passed, $fail failed")
    System.getProperty("report")?.let { java.io.File(it).writeText(report.toString() + "\n$pass passed, $fail failed\n") }
    if (fail > 0) System.exit(1)
}
