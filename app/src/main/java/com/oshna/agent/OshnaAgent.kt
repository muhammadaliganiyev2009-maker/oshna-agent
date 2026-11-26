package com.oshna.agent

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.*
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class OshnaService : AccessibilityService() {

    companion object {
        @Volatile var instance: OshnaService? = null
    }

    private val nodes = mutableListOf<AccessibilityNodeInfo>()

    override fun onServiceConnected() { instance = this }
    override fun onUnbind(intent: Intent?): Boolean { instance = null; return super.onUnbind(intent) }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    fun readScreen(): String {
        nodes.clear()
        val root = rootInActiveWindow ?: return "(ekran o'qilmadi)"
        val sb = StringBuilder("Ochiq ilova: ${root.packageName}\n")
        walk(root, sb)
        return sb.toString().take(6000)
    }

    private fun walk(n: AccessibilityNodeInfo, sb: StringBuilder) {
        if (!n.isVisibleToUser) return
        val text = (n.text ?: n.contentDescription)?.toString().orEmpty()
        if (text.isNotBlank() || n.isClickable || n.isEditable || n.isScrollable) {
            val flags = buildString {
                if (n.isClickable) append(" [bosiladi]")
                if (n.isEditable) append(" [yoziladi]")
                if (n.isScrollable) append(" [suriladi]")
            }
            sb.append("[${nodes.size}] ${n.className?.toString()?.substringAfterLast('.')} \"${text.take(60)}\"$flags\n")
            nodes.add(n)
        }
        for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, sb) }
    }

    fun click(index: Int): Boolean {
        var n = nodes.getOrNull(index) ?: return false
        while (!n.isClickable) n = n.parent ?: return false
        return n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    fun type(index: Int, text: String): Boolean {
        val n = nodes.getOrNull(index) ?: return false
        n.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun scroll(forward: Boolean): Boolean {
        val n = nodes.firstOrNull { it.isScrollable } ?: return false
        return n.performAction(
            if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        )
    }

    fun back() = performGlobalAction(GLOBAL_ACTION_BACK)
    fun home() = performGlobalAction(GLOBAL_ACTION_HOME)
}

object AppLauncher {

    private val known = mapOf(
        "instagram" to "com.instagram.android",
        "telegram" to "org.telegram.messenger",
        "youtube" to "com.google.android.youtube",
        "capcut" to "com.lemon.lvoverseas",
        "chrome" to "com.android.chrome",
        "whatsapp" to "com.whatsapp",
        "tiktok" to "com.zhiliaoapp.musically"
    )

    fun open(ctx: Context, name: String): Boolean {
        val pm = ctx.packageManager
        val q = name.trim().lowercase()
        val pkg = known[q] ?: pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
        ).firstOrNull { it.loadLabel(pm).toString().lowercase().contains(q) }
            ?.activityInfo?.packageName
        ?: return false
        val intent = pm.getLaunchIntentForPackage(pkg) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
        return true
    }
}

object WebSearch {
    private val client = OkHttpClient()

    fun search(query: String): String = try {
        val url = "https://html.duckduckgo.com/html/?q=" + URLEncoder.encode(query, "UTF-8")
        val req = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build()
        val html = client.newCall(req).execute().use { it.body?.string().orEmpty() }
        Regex("""class="result__snippet"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(html).take(4)
            .joinToString("\n") { it.groupValues[1].replace(Regex("<[^>]+>"), "").trim() }
            .ifBlank { "Natija topilmadi." }
    } catch (e: Exception) {
        "Qidiruvda xato: ${e.message}"
    }
}

object Agent {

    private const val MODEL = "openai/gpt-oss-120b"
    private const val MAX_STEPS = 15

    private val client = OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).build()
    private val steps = mutableListOf<String>()

    private val SYSTEM_PROMPT = """
Sen "Oshna" — Android telefonni boshqaradigan o'zbek tilidagi AI yordamchisan.
Foydalanuvchi o'zbekcha (lotin yozuvida, ba'zan xatolar va so'zlashuv shevasi bilan) buyruq beradi.
Hamma javoblaringni sodda, to'g'ri, ravon o'zbek tilida yoz. Ruscha yoki inglizcha so'z aralashtirma
(ilova nomlari bundan mustasno).

Senga har qadamda: buyruq, oldingi qadamlar va hozirgi ekran ro'yxati beriladi.
Ekran elementlari [raqam] bilan belgilangan. Faqat BITTA amalni qaytar. Javobing FAQAT JSON bo'lsin:

{"fikr":"qisqa o'zbekcha izoh","action":"...", ...}

action turlari:
- open_app: {"app":"instagram"} — ilovani ochadi
- click: {"index":5} — elementni bosadi
- type: {"index":3,"text":"matn"} — yoziladigan maydonga yozadi
- scroll_down / scroll_up — ekranni suradi
- back / home — orqaga / bosh ekran
- wait — 2 soniya kutadi
- search_web: {"query":"..."} — qiynalsang, internetdan izla
- ask_user: {"message":"..."} — hech qanday yo'l topolmasang, foydalanuvchidan o'zbekcha yordam so'ra
- done: {"message":"..."} — vazifa tugadi, natijani o'zbekcha qisqa ayt

Qoidalar:
1. Avval kerakli ilova ochiqligini tekshir, ochiq bo'lmasa open_app qil.
2. Faqat ekranda bor raqamlardan foydalan. Raqamni o'ylab topma.
3. Bir amal ish bermasa, boshqa yo'lni sina. 2-3 marta urinib bo'lmasa, search_web yoki ask_user qil.
4. Pul to'lash, xabar yuborish, post joylash, hisobdan chiqish kabi qaytarib bo'lmaydigan ishlardan
   oldin ask_user bilan tasdiq so'ra.
5. JSON'dan boshqa hech narsa yozma.
""".trimIndent()

    suspend fun run(apiKey: String, command: String, log: (String) -> Unit) {
        steps.add("Foydalanuvchi buyrug'i: $command")
        repeat(MAX_STEPS) { n ->
            val service = OshnaService.instance
            if (service == null) {
                log("⚠️ Avval Maxsus imkoniyatlarda Oshna Agent'ni yoqing."); return
            }
            val screen = service.readScreen()
            val prompt = "Oldingi qadamlar:\n${steps.takeLast(15).joinToString("\n")}\n\nHozirgi ekran:\n$screen"

            val reply = try { askGroq(apiKey, prompt) } catch (e: Exception) {
                log("❌ Groq xatosi: ${e.message}"); return
            }
            val a = try { JSONObject(reply) } catch (e: Exception) {
                log("❌ AI noto'g'ri javob berdi"); return
            }
            val action = a.optString("action")
            log("🧠 ${a.optString("fikr")}  →  $action")
            steps.add("Amal: $action ${a.optString("app")}${a.optString("index")} ${a.optString("text")}".trim())

            when (action) {
                "open_app" -> {
                    val ok = AppLauncher.open(service, a.optString("app"))
                    if (!ok) steps.add("Natija: '${a.optString("app")}' ilovasi topilmadi")
                }
                "click" -> if (!service.click(a.optInt("index", -1))) steps.add("Natija: bosilmadi")
                "type" -> if (!service.type(a.optInt("index", -1), a.optString("text"))) steps.add("Natija: yozilmadi")
                "scroll_down" -> service.scroll(true)
                "scroll_up" -> service.scroll(false)
                "back" -> service.back()
                "home" -> service.home()
                "wait" -> delay(2000)
                "search_web" -> {
                    val res = withContext(Dispatchers.IO) { WebSearch.search(a.optString("query")) }
                    steps.add("Qidiruv natijasi: $res")
                }
                "ask_user" -> { log("🙋 ${a.optString("message")}"); return }
                "done" -> { log("✅ ${a.optString("message")}"); return }
                else -> { log("❌ Noma'lum amal: $action"); return }
            }
            delay(1500)
        }
        log("⏱️ Qadamlar limiti tugadi. Buyruqni aniqroq yozib ko'ring.")
    }

    private suspend fun askGroq(apiKey: String, prompt: String): String = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("model", MODEL)
            .put("temperature", 0.2)
            .put("response_format", JSONObject().put("type", "json_object"))
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
                .put(JSONObject().put("role", "user").put("content", prompt)))
        val req = Request.Builder()
            .url("https://api.groq.com/openai/v1/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(req).execute().use { r ->
            val txt = r.body?.string().orEmpty()
            if (!r.isSuccessful) error("HTTP ${r.code}: ${txt.take(150)}")
            JSONObject(txt).getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content")
        }
    }
}

class MainActivity : Activity() {

    private val scope = CoroutineScope(Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("oshna", MODE_PRIVATE)
        val pad = (16 * resources.displayMetrics.density).toInt()

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }

        val keyInput = EditText(this).apply {
            hint = "Groq API kaliti"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefs.getString("key", ""))
        }
        val accBtn = Button(this).apply {
            text = "1) Maxsus imkoniyatlarni yoqish"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        val cmdInput = EditText(this).apply { hint = "Buyruq: masalan, instagramni och" }
        val logView = TextView(this).apply { setTextColor(Color.DKGRAY) }
        val runBtn = Button(this).apply { text = "2) Bajar" }

        runBtn.setOnClickListener {
            val key = keyInput.text.toString().trim()
            val cmd = cmdInput.text.toString().trim()
            if (key.isEmpty() || cmd.isEmpty()) return@setOnClickListener
            prefs.edit().putString("key", key).apply()
            cmdInput.setText("")
            logView.append("\n▶ $cmd\n")
            scope.launch { Agent.run(key, cmd) { msg -> runOnUiThread { logView.append("$msg\n") } } }
        }

        root.addView(keyInput, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(accBtn, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(cmdInput, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(runBtn, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(ScrollView(this).apply { addView(logView) }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        setContentView(root)
    }
}
