package np.psy.accounting

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.FrameLayout
import np.psy.accounting.screens.DashboardScreen
import np.psy.accounting.ui.*
import java.io.File

/** A screen in the app's simple navigation stack. Screens build their views in code. */
abstract class Screen(val act: MainActivity) {
    abstract val title: String
    abstract fun build(): View
    open val actions: List<Pair<String, () -> Unit>> get() = emptyList()
    val ctx: Context get() = act
    val books get() = App.books()
    fun push(s: Screen) = act.push(s)
    fun back() = act.pop()
    fun refresh() = act.refreshTop()
    /** Called when this screen becomes visible again after the screen above it closes. */
    open fun onReturn() = refresh()
}

class MainActivity : Activity() {
    private lateinit var container: FrameLayout
    private val stack = ArrayList<Screen>()
    private val results = HashMap<Int, (Int, Intent?) -> Unit>()
    private var nextCode = 100

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = C.PRIMARY_DARK
        container = FrameLayout(this).apply { setBackgroundColor(C.BG) }
        setContentView(container)
        try {
            App.books()
        } catch (e: Exception) {
            info("Cannot open the company data", "${e.message}\n\nYour data has not been changed. If you recently installed an older version of the app, install the newer version again.") { finish() }
            return
        }
        push(DashboardScreen(this))
        // Missed-backup check at every start (Android may delay the scheduled job).
        App.bg({ App.dailyBackupCheck() }) { r -> if (r.getOrNull() != null) (stack.firstOrNull() as? DashboardScreen)?.let { if (stack.size == 1) refreshTop() } }
        val crash = File(filesDir, "last_crash.txt")
        if (crash.exists()) {
            val text = crash.readText().take(3000); crash.delete()
            confirm("The app closed unexpectedly last time", "Your saved data is safe: every entry is saved in one step or not at all.\n\nTechnical details (for support):\n$text", yes = "OK", no = "Close") {}
        }
    }

    fun push(s: Screen) { stack.add(s); show() }
    fun pop() { if (stack.size > 1) { stack.removeAt(stack.size - 1); stack.last().onReturn() } else finish() }
    fun home() { while (stack.size > 1) stack.removeAt(stack.size - 1); stack.last().onReturn() }
    fun replace(s: Screen) { stack.removeAt(stack.size - 1); push(s) }
    fun refreshTop() = show()

    private fun show() {
        val s = stack.last()
        title = s.title
        actionBar?.setDisplayHomeAsUpEnabled(stack.size > 1)
        container.removeAllViews()
        val v = try { s.build() } catch (e: Throwable) {
            android.util.Log.e("PSY", "screen failed", e)
            vbox(16).apply { warning("This screen could not be opened: ${e.message}"); button("Go back") { pop() } }
        }
        container.addView(v, FrameLayout.LayoutParams(MATCH, MATCH))
        invalidateOptionsMenu()
    }

    override fun onBackPressed() = pop()

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        stack.lastOrNull()?.actions?.forEachIndexed { i, (label, _) -> menu.add(0, i + 1, i, label) }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { pop(); return true }
        val a = stack.lastOrNull()?.actions?.getOrNull(item.itemId - 1) ?: return false
        safely(this) { a.second() }
        return true
    }

    fun startForResult(i: Intent, cb: (Int, Intent?) -> Unit) {
        val code = nextCode++
        results[code] = cb
        try { startActivityForResult(i, code) } catch (e: Exception) { results.remove(code); error(e) }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        results.remove(requestCode)?.let { cb -> safely(this) { cb(resultCode, data) } }
    }
}
