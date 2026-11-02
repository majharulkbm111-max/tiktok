package com.mytik.custom

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.Menu
import android.view.View
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.URI

// ============ DATA MODEL ============
data class AppSettings(
    val adBlock: Boolean = true,
    val locationMode: String = "deny",
    val countryCode: String = "BD",
    val themeMode: String = "system",
    val splashMs: Int = 800,
    val navTabs: List<String> = listOf("home", "explore", "inbox", "profile", "settings"),
    val blockedDomains: Set<String> = emptySet(),
    val allowedDomains: Set<String> = emptySet()
) {
    fun toJson(): String = JSONObject().apply {
        put("adBlock", adBlock); put("locationMode", locationMode)
        put("countryCode", countryCode); put("themeMode", themeMode)
        put("splashMs", splashMs); put("navTabs", JSONArray(navTabs))
        put("blockedDomains", JSONArray(blockedDomains.toList()))
        put("allowedDomains", JSONArray(allowedDomains.toList()))
    }.toString()

    companion object {
        fun fromJson(s: String): AppSettings? = runCatching {
            val o = JSONObject(s)
            AppSettings(
                adBlock = o.optBoolean("adBlock", true),
                locationMode = o.optString("locationMode", "deny"),
                countryCode = o.optString("countryCode", "BD"),
                themeMode = o.optString("themeMode", "system"),
                splashMs = o.optInt("splashMs", 800),
                navTabs = o.optJSONArray("navTabs")?.let { a ->
                    (0 until a.length()).map { a.getString(it) }
                } ?: listOf("home", "explore", "inbox", "profile", "settings"),
                blockedDomains = o.optJSONArray("blockedDomains")?.let { a ->
                    (0 until a.length()).map { a.getString(it) }.toSet()
                } ?: emptySet(),
                allowedDomains = o.optJSONArray("allowedDomains")?.let { a ->
                    (0 until a.length()).map { a.getString(it) }.toSet()
                } ?: emptySet()
            )
        }.getOrNull()
    }
}

// ============ DATASTORE ============
private val Context.ds by preferencesDataStore("mytik_settings")

class SettingsRepository(private val ctx: Context) {
    private object K {
        val AD = booleanPreferencesKey("adBlock")
        val LOC = stringPreferencesKey("locationMode")
        val CTY = stringPreferencesKey("countryCode")
        val THM = stringPreferencesKey("themeMode")
        val SPL = intPreferencesKey("splashMs")
        val NAV = stringPreferencesKey("navTabs")
        val BLK = stringPreferencesKey("blockedDomains")
        val ALW = stringPreferencesKey("allowedDomains")
    }

    val settings: Flow<AppSettings> = ctx.ds.data.map { p ->
        AppSettings(
            adBlock = p[K.AD] ?: true,
            locationMode = p[K.LOC] ?: "deny",
            countryCode = p[K.CTY] ?: "BD",
            themeMode = p[K.THM] ?: "system",
            splashMs = p[K.SPL] ?: 800,
            navTabs = p[K.NAV]?.split(",")?.filter { it.isNotBlank() }
                ?: listOf("home", "explore", "inbox", "profile", "settings"),
            blockedDomains = p[K.BLK]?.split(",")?.filter { it.isNotBlank() }?.toSet() ?: emptySet(),
            allowedDomains = p[K.ALW]?.split(",")?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
        )
    }

    suspend fun current() = settings.first()

    suspend fun update(block: suspend (MutablePreferences) -> Unit) { ctx.ds.edit { block(it) } }

    suspend fun setAdBlock(v: Boolean) = update { it[K.AD] = v }
    suspend fun setLocation(v: String) = update { it[K.LOC] = v }
    suspend fun setCountry(v: String) = update { it[K.CTY] = v }
    suspend fun setTheme(v: String) = update { it[K.THM] = v }
    suspend fun setSplash(v: Int) = update { it[K.SPL] = v }
    suspend fun setNav(v: List<String>) = update { it[K.NAV] = v.joinToString(",") }
    suspend fun setBlocked(v: Set<String>) = update { it[K.BLK] = v.joinToString(",") }
    suspend fun setAllowed(v: Set<String>) = update { it[K.ALW] = v.joinToString(",") }
    suspend fun resetAll() { ctx.ds.edit { it.clear() } }

    suspend fun importJson(json: String): Boolean {
        val s = AppSettings.fromJson(json) ?: return false
        update { p ->
            p[K.AD] = s.adBlock; p[K.LOC] = s.locationMode; p[K.CTY] = s.countryCode
            p[K.THM] = s.themeMode; p[K.SPL] = s.splashMs
            p[K.NAV] = s.navTabs.joinToString(",")
            p[K.BLK] = s.blockedDomains.joinToString(",")
            p[K.ALW] = s.allowedDomains.joinToString(",")
        }
        return true
    }
}

// ============ AD BLOCK ============
class AdBlockEngine {
    fun shouldBlock(url: String, userBlocked: Set<String>, userAllowed: Set<String>): Boolean {
        val host = runCatching { URI(url).host?.lowercase() }.getOrNull() ?: return false
        if (userAllowed.any { host == it || host.endsWith(".$it") }) return false
        if (userBlocked.any { host == it || host.endsWith(".$it") }) return true
        return TRACKERS.any { host == it || host.endsWith(".$it") }
    }
    fun empty() = WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
    companion object {
        val TRACKERS = setOf(
            "doubleclick.net", "googlesyndication.com", "googleadservices.com",
            "google-analytics.com", "googletagmanager.com", "connect.facebook.net",
            "scorecardresearch.com", "quantserve.com", "adnxs.com",
            "criteo.com", "taboola.com", "outbrain.com", "moatads.com",
            "amazon-adsystem.com"
        )
    }
}

// ============ SPLASH ============
class SplashActivity : AppCompatActivity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#0E0E0E"))
        }
        val title = TextView(this).apply {
            text = "MyTik Custom"
            setTextColor(Color.WHITE)
            textSize = 26f
        }
        val spinner = ProgressBar(this).apply {
            indeterminateTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#FE2C55"))
        }
        root.addView(title)
        root.addView(spinner, LinearLayout.LayoutParams(64, 64).apply { topMargin = 24 })
        setContentView(root)

        lifecycleScope.launch {
            val s = SettingsRepository(this@SplashActivity).current()
            Handler(Looper.getMainLooper()).postDelayed({
                startActivity(Intent(this@SplashActivity, MainActivity::class.java))
                finish()
            }, s.splashMs.toLong().coerceIn(200L, 5000L))
        }
    }
}

// ============ MAIN ============
class MainActivity : AppCompatActivity() {
    private lateinit var web: WebView
    private lateinit var progress: ProgressBar
    private lateinit var fullscreen: FrameLayout
    private lateinit var bottomNav: BottomNavigationView
    private var customView: View? = null
    private var customCb: WebChromeClient.CustomViewCallback? = null
    private var fileCb: ValueCallback<Array<Uri>>? = null

    private val repo by lazy { SettingsRepository(this) }
    private val adBlock = AdBlockEngine()
    private var adBlockOn = true
    private var blockedSet: Set<String> = emptySet()
    private var allowedSet: Set<String> = emptySet()
    private var locationMode = "deny"

    private val fileLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        fileCb?.onReceiveValue(if (uri != null) arrayOf(uri) else null)
        fileCb = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        buildUi()

        lifecycleScope.launch {
            val s = repo.current()
            adBlockOn = s.adBlock; blockedSet = s.blockedDomains
            allowedSet = s.allowedDomains; locationMode = s.locationMode
            applyTheme(s.themeMode)
            buildNav(s.navTabs)
            if (b == null) web.loadUrl("https://www.tiktok.com/")
        }

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = true
            loadWithOverviewMode = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)

        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? {
                if (adBlockOn && adBlock.shouldBlock(req.url.toString(), blockedSet, allowedSet))
                    return adBlock.empty()
                return super.shouldInterceptRequest(view, req)
            }
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                val u = req.url
                if (u.scheme == "intent" || u.scheme == "market") {
                    Toast.makeText(this@MainActivity,
                        "এই কনটেন্ট TikTok অ্যাপে খুলতে হবে", Toast.LENGTH_LONG).show()
                    return true
                }
                val h = u.host ?: return false
                if ((u.scheme == "http" || u.scheme == "https") && !h.endsWith("tiktok.com")) {
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, u)) }
                    return true
                }
                return false
            }
            override fun onPageFinished(view: WebView, url: String) { progress.visibility = View.GONE }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onGeolocationPermissionsShowPrompt(origin: String?, cb: GeolocationPermissions.Callback?) {
                if (origin == null || cb == null) return
                val allowed = locationMode == "allow" &&
                    androidx.core.content.ContextCompat.checkSelfPermission(
                        this@MainActivity, android.Manifest.permission.ACCESS_COARSE_LOCATION
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                cb.invoke(origin, allowed, false)
            }
            override fun onProgressChanged(view: WebView, p: Int) {
                progress.progress = p
                progress.visibility = if (p < 100) View.VISIBLE else View.GONE
            }
            override fun onShowCustomView(view: View, cb: CustomViewCallback?) {
                if (customView != null) { cb?.onCustomViewHidden(); return }
                customView = view; customCb = cb
                fullscreen.addView(view); fullscreen.visibility = View.VISIBLE
            }
            override fun onHideCustomView() {
                fullscreen.removeView(customView); fullscreen.visibility = View.GONE
                customView = null; customCb?.onCustomViewHidden(); customCb = null
            }
            override fun onShowFileChooser(
                v: WebView?, cb: ValueCallback<Array<Uri>>?, p: FileChooserParams?
            ): Boolean {
                fileCb?.onReceiveValue(null); fileCb = cb
                return runCatching { fileLauncher.launch("*/*"); true }.getOrDefault(false)
            }
        }

        web.setDownloadListener { url, _, cd, mime, _ ->
            runCatching {
                val name = URLUtil.guessFileName(url, cd, mime)
                val req = DownloadManager.Request(Uri.parse(url)).apply {
                    setMimeType(mime); setTitle(name)
                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
                }
                (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
                Toast.makeText(this, "ডাউনলোড শুরু", Toast.LENGTH_SHORT).show()
            }.onFailure { Toast.makeText(this, "ব্যর্থ: ${it.message}", Toast.LENGTH_LONG).show() }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    fullscreen.visibility == View.VISIBLE -> web.webChromeClient?.onHideCustomView()
                    web.canGoBack() -> web.goBack()
                    else -> finish()
                }
            }
        })
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val webContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        web = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#FE2C55"))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, 6
            ).apply { gravity = Gravity.TOP }
        }
        fullscreen = FrameLayout(this).apply {
            visibility = View.GONE
            setBackgroundColor(Color.BLACK)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        webContainer.addView(web)
        webContainer.addView(progress)
        webContainer.addView(fullscreen)
        bottomNav = BottomNavigationView(this)
        root.addView(webContainer)
        root.addView(bottomNav)
        setContentView(root)
    }

    private fun applyTheme(mode: String) {
        val m = when (mode) {
            "light" -> AppCompatDelegate.MODE_NIGHT_NO
            "dark" -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        if (AppCompatDelegate.getDefaultNightMode() != m) AppCompatDelegate.setDefaultNightMode(m)
    }

    private fun buildNav(tabs: List<String>) {
        val info = mapOf(
            "home" to Triple(1, android.R.drawable.ic_menu_slideshow, "হোম"),
            "explore" to Triple(2, android.R.drawable.ic_menu_compass, "এক্সপ্লোর"),
            "inbox" to Triple(3, android.R.drawable.ic_dialog_email, "ইনবক্স"),
            "profile" to Triple(4, android.R.drawable.ic_menu_myplaces, "প্রোফাইল"),
            "settings" to Triple(5, android.R.drawable.ic_menu_preferences, "সেটিংস")
        )
        bottomNav.menu.clear()
        tabs.forEach { k ->
            info[k]?.let { (id, icon, label) ->
                bottomNav.menu.add(Menu.NONE, id, Menu.NONE, label).setIcon(icon)
            }
        }
        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                1 -> web.loadUrl("https://www.tiktok.com/")
                2 -> web.loadUrl("https://www.tiktok.com/explore")
                3 -> web.loadUrl("https://www.tiktok.com/messages")
                4 -> web.loadUrl("https://www.tiktok.com/@me")
                5 -> startActivity(Intent(this, SettingsActivity::class.java))
            }
            true
        }
        if (bottomNav.menu.size() > 0) bottomNav.selectedItemId = 1
    }

    override fun onResume() {
        super.onResume(); web.onResume()
        lifecycleScope.launch {
            val s = repo.current()
            adBlockOn = s.adBlock; blockedSet = s.blockedDomains
            allowedSet = s.allowedDomains; locationMode = s.locationMode
            buildNav(s.navTabs)
        }
    }

    override fun onPause() {
        super.onPause(); web.onPause(); CookieManager.getInstance().flush()
    }
    override fun onDestroy() { web.destroy(); super.onDestroy() }
}

// ============ SETTINGS ============
class SettingsActivity : AppCompatActivity() {
    private val repo by lazy { SettingsRepository(this) }
    private lateinit var container: LinearLayout

    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) lifecycleScope.launch {
            runCatching {
                contentResolver.openOutputStream(uri)?.use { it.write(repo.current().toJson().toByteArray()) }
                Toast.makeText(this@SettingsActivity, "এক্সপোর্ট সম্পন্ন", Toast.LENGTH_SHORT).show()
            }.onFailure { Toast.makeText(this@SettingsActivity, "ব্যর্থ: ${it.message}", Toast.LENGTH_LONG).show() }
        }
    }
    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) lifecycleScope.launch {
            runCatching {
                val txt = contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: return@launch
                val ok = repo.importJson(txt)
                Toast.makeText(this@SettingsActivity,
                    if (ok) "ইমপোর্ট সফল" else "ফাইল সঠিক নয়", Toast.LENGTH_LONG).show()
                if (ok) rebuild()
            }.onFailure { Toast.makeText(this@SettingsActivity, "ব্যর্থ: ${it.message}", Toast.LENGTH_LONG).show() }
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val scroll = ScrollView(this)
        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        scroll.addView(container)
        setContentView(scroll)
        rebuild()
    }

    private fun rebuild() {
        container.removeAllViews()
        lifecycleScope.launch {
            val s = repo.current()

            header("প্রাইভেসি ও লোকেশন")
            card("অ্যাড ও ট্র্যাকার ব্লক", "তৃতীয়-পক্ষ ট্র্যাকার ডোমেইন") {
                Switch(this@SettingsActivity).apply {
                    isChecked = s.adBlock
                    setOnCheckedChangeListener { _, v ->
                        lifecycleScope.launch { repo.setAdBlock(v) }
                    }
                }
            }
            card("লোকেশন পারমিশন", "ওয়েবসাইটের অবস্থান অনুমতি") {
                radio(listOf("deny" to "ডিনাই (ডিফল্ট)", "allow" to "অ্যালাও", "ask" to "জিজ্ঞাসা"), s.locationMode) { v ->
                    lifecycleScope.launch { repo.setLocation(v) }; rebuild()
                }
            }

            header("অ্যাপিয়ারেন্স")
            card("থিম", "সিস্টেম / লাইট / ডার্ক") {
                radio(listOf("system" to "সিস্টেম", "light" to "লাইট", "dark" to "ডার্ক"), s.themeMode) { v ->
                    lifecycleScope.launch { repo.setTheme(v) }; rebuild()
                }
            }
            card("স্প্ল্যাশ সময় (ms)", "২০০ থেকে ৫০০০") {
                val et = EditText(this@SettingsActivity).apply { setText(s.splashMs.toString()) }
                val btn = Button(this@SettingsActivity).apply {
                    text = "সেভ"
                    setOnClickListener {
                        val v = et.text.toString().toIntOrNull()?.coerceIn(200, 5000) ?: 800
                        lifecycleScope.launch { repo.setSplash(v) }
                        Toast.makeText(this@SettingsActivity, "সেভ হয়েছে", Toast.LENGTH_SHORT).show()
                    }
                }
                column(et, btn)
            }

            header("বটম নেভিগেশন")
            card("ট্যাব অন/অফ", "যেকোনো ট্যাব বন্ধ করা যায়") {
                val all = listOf(
                    "home" to "হোম", "explore" to "এক্সপ্লোর", "inbox" to "ইনবক্স",
                    "profile" to "প্রোফাইল", "settings" to "সেটিংস"
                )
                val col = LinearLayout(this@SettingsActivity).apply { orientation = LinearLayout.VERTICAL }
                all.forEach { (k, label) ->
                    col.addView(Switch(this@SettingsActivity).apply {
                        text = label
                        isChecked = k in s.navTabs
                        setOnCheckedChangeListener { _, checked ->
                            val set = s.navTabs.toMutableSet()
                            if (checked) set.add(k) else set.remove(k)
                            if (set.isEmpty()) { isChecked = true; return@setOnCheckedChangeListener }
                            lifecycleScope.launch { repo.setNav(all.filter { it.first in set }.map { it.first }) }
                        }
                    })
                }
                col
            }

            header("দেশ / রিজিয়ন (শুধু লোকাল প্রেফারেন্স)")
            card("কান্ট্রি", "TikTok অ্যাকাউন্ট বদলাবে না") {
                radio(
                    listOf(
                        "BD" to "বাংলাদেশ", "US" to "যুক্তরাষ্ট্র", "GB" to "যুক্তরাজ্য",
                        "IN" to "ভারত", "PK" to "পাকিস্তান", "MY" to "মালয়েশিয়া",
                        "ID" to "ইন্দোনেশিয়া", "JP" to "জাপান", "KR" to "দক্ষিণ কোরিয়া",
                        "SA" to "সৌদি আরব", "AE" to "সংযুক্ত আরব আমিরাত"
                    ),
                    s.countryCode
                ) { v -> lifecycleScope.launch { repo.setCountry(v) }; rebuild() }
            }

            header("ডোমেইন ফিল্টার")
            card("কাস্টম ব্লকড ডোমেইন", "কমা-বিভক্ত") {
                val et = EditText(this@SettingsActivity).apply { setText(s.blockedDomains.joinToString(",")) }
                val btn = Button(this@SettingsActivity).apply {
                    text = "সেভ"
                    setOnClickListener {
                        val set = et.text.toString().split(",").map { it.trim() }
                            .filter { it.isNotEmpty() }.toSet()
                        lifecycleScope.launch { repo.setBlocked(set) }
                    }
                }
                column(et, btn)
            }
            card("কাস্টম অ্যালাওলিস্ট", "এরা ব্লক হবে না") {
                val et = EditText(this@SettingsActivity).apply { setText(s.allowedDomains.joinToString(",")) }
                val btn = Button(this@SettingsActivity).apply {
                    text = "সেভ"
                    setOnClickListener {
                        val set = et.text.toString().split(",").map { it.trim() }
                            .filter { it.isNotEmpty() }.toSet()
                        lifecycleScope.launch { repo.setAllowed(set) }
                    }
                }
                column(et, btn)
            }

            header("ব্যাকআপ ও রিসেট")
            card("এক্সপোর্ট (JSON)", "সেটিংস ফাইল হিসেবে সেভ") {
                Button(this@SettingsActivity).apply {
                    text = "এক্সপোর্ট"
                    setOnClickListener { exportLauncher.launch("mytik_settings.json") }
                }
            }
            card("ইমপোর্ট (JSON)", "পূর্বে সেভ করা ফাইল") {
                Button(this@SettingsActivity).apply {
                    text = "ইমপোর্ট"
                    setOnClickListener { importLauncher.launch(arrayOf("application/json")) }
                }
            }
            card("সব রিসেট", "সব সেটিংস ডিফল্টে ফিরবে") {
                Button(this@SettingsActivity).apply {
                    text = "রিসেট"
                    setOnClickListener {
                        AlertDialog.Builder(this@SettingsActivity)
                            .setTitle("নিশ্চিত?")
                            .setMessage("সব সেটিংস মুছে যাবে।")
                            .setPositiveButton("হ্যাঁ") { _, _ ->
                                lifecycleScope.launch { repo.resetAll(); rebuild() }
                            }
                            .setNegativeButton("না", null).show()
                    }
                }
            }
        }
    }

    private fun header(title: String) {
        container.addView(TextView(this).apply {
            text = title; textSize = 13f; alpha = 0.6f
            setPadding(8, 32, 8, 8)
        })
    }

    private fun card(title: String, sub: String, content: () -> View) {
        val c = MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 12 }
            radius = 24f; cardElevation = 2f
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 24)
        }
        col.addView(TextView(this).apply { text = title; textSize = 16f })
        col.addView(TextView(this).apply { text = sub; textSize = 12f; alpha = 0.6f; setPadding(0, 4, 0, 12) })
        col.addView(content())
        c.addView(col)
        container.addView(c)
    }

    private fun column(vararg v: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        v.forEach { addView(it) }
    }

    private fun radio(opts: List<Pair<String, String>>, sel: String, onSelect: (String) -> Unit): View {
        val g = RadioGroup(this)
        opts.forEach { (v, label) ->
            g.addView(RadioButton(this).apply {
                text = label; tag = v; isChecked = v == sel
                id = View.generateViewId()
            })
        }
        g.setOnCheckedChangeListener { grp, id ->
            (grp.findViewById<RadioButton>(id)?.tag as? String)?.let(onSelect)
        }
        return g
    }
}
