package me.rerere.rikkahub.service

import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.*
import kotlinx.serialization.json.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import me.rerere.ai.ui.UIMessagePart
import kotlin.math.roundToInt

/** One accessibility window, owned and removed by the bound service. */
internal class OperationOverlay(private val service: RikkaAccessibilityService) {
    private val wm = service.getSystemService(android.content.Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private val panel = LinearLayout(service).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(10), dp(6), dp(10), dp(6))
        background = GradientDrawable().apply {
            setColor(0xfffff6e8.toInt())
            cornerRadius = dp(20).toFloat()
        }
        elevation = dp(4).toFloat()
    }
    private val params = WindowManager.LayoutParams(-2, -2,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.RIGHT; y = dp(128) }
    private val paused = MutableStateFlow(false)
    suspend fun awaitResume() { paused.first { !it } }

    private var owner = ""
    private var shown = ""
    private var question: UIMessagePart.Tool? = null
    private var expanded = false
    private val dismiss = Runnable { clear() }
    private fun dp(n: Int) = (n * service.resources.displayMetrics.density).roundToInt()

    fun finish(id: String) {
        if (owner == id && question == null) {
            handler.removeCallbacks(dismiss)
            handler.postDelayed(dismiss, 4500)
        }
    }

    fun clear() {
        handler.removeCallbacks(dismiss)
        if (panel.isAttachedToWindow) wm.removeViewImmediate(panel)
        paused.value = false
        owner = ""
        shown = ""
        question = null
    }

    fun update(id: String, start: Boolean, name: String, text: String,
               ask: UIMessagePart.Tool?, answer: (String, String) -> Unit, stop: () -> Unit) {
        if (owner != id) {
            if (!start || owner.isNotEmpty()) return
            owner = id
        }
        handler.removeCallbacks(dismiss)
        val key = listOf(name, text, ask?.toolCallId).toString()
        if (shown == key) return
        shown = key
        question = ask
        expanded = ask != null || text.isNotBlank()
        fun label(value: String) = TextView(service).apply {
            this.text = value
            setTextColor(0xff45372f.toInt())
            textSize = 15f
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        fun render() {
            panel.removeAllViews()
            params.width = if (expanded) minOf(dp(265), service.resources.displayMetrics.widthPixels - dp(30)) else -2
            params.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                (if (expanded && ask != null) 0 else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            val handle = label(if (expanded) "🍉  $name" else "🍉")
            panel.addView(handle)
            handle.contentDescription = "西瓜瓣操作小窗，点按展开或收起，上下拖动调整位置"
            handle.setOnClickListener { expanded = !expanded; render() }
            var down = 0f
            var origin = 0
            var dragged = false
            handle.setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { down = event.rawY; origin = params.y; dragged = false }
                    MotionEvent.ACTION_MOVE -> {
                        val delta = event.rawY - down
                        if (kotlin.math.abs(delta) > dp(6)) dragged = true
                        if (dragged) {
                            params.y = (origin + delta.toInt()).coerceIn(0,
                                (service.resources.displayMetrics.heightPixels - panel.height).coerceAtLeast(0))
                            wm.updateViewLayout(panel, params)
                        }
                    }
                    MotionEvent.ACTION_UP -> if (!dragged) v.performClick()
                }
                true
            }
            if (expanded) {
                val body = LinearLayout(service).apply { orientation = LinearLayout.VERTICAL }
                val scroll = ScrollView(service)
                scroll.addView(body)
                panel.addView(scroll, -1, if (ask != null) dp(230) else -2)
                if (ask == null) {
                    body.addView(label(text.ifBlank { "正在操作" }).apply { maxLines = 5 })
                    body.addView(Button(service).apply {
                        this.text = if (paused.value) "继续操作" else "暂停操作"
                        setOnClickListener { paused.value = !paused.value; render() }
                    })
                    body.addView(Button(service).apply { this.text = "停止操作"; setOnClickListener { stop(); clear() } })
                } else {
                    body.addView(label("已暂停，等你回答"))
                    val fields = linkedMapOf<String, EditText>()
                    val questions = (ask.inputAsJson() as? JsonObject)?.get("questions") as? JsonArray
                    for (item in questions.orEmpty()) {
                        val q = item as? JsonObject ?: continue
                        val qid = (q["id"] as? JsonPrimitive)?.content ?: continue
                        body.addView(label((q["question"] as? JsonPrimitive)?.content.orEmpty()))
                        val input = EditText(service).apply { hint = "回一句…"; setTextColor(0xff45372f.toInt()) }
                        fields[qid] = input
                        val multi = (q["selection_type"] as? JsonPrimitive)?.content == "multi"
                        val selected = linkedSetOf<String>()
                        for (option in (q["options"] as? JsonArray).orEmpty()) {
                            val value = (option as? JsonPrimitive)?.content ?: continue
                            body.addView(Button(service).apply {
                                this.text = value
                                setOnClickListener {
                                    if (multi) {
                                        if (!selected.add(value)) selected.remove(value)
                                        this.text = if (value in selected) "✓ $value" else value
                                        input.setText(selected.joinToString(", "))
                                    } else input.setText(value)
                                }
                            })
                        }
                        body.addView(input)
                    }
                    body.addView(Button(service).apply {
                        this.text = "发送回答"
                        setOnClickListener {
                            if (fields.isEmpty() || fields.values.any { it.text.isBlank() }) return@setOnClickListener
                            val payload = buildJsonObject { put("answers", buildJsonObject {
                                fields.forEach { (key, field) -> put(key, field.text.toString()) }
                            }) }
                            clear()
                            answer(ask.toolCallId, payload.toString())
                        }
                    })
                }
            }
            if (panel.isAttachedToWindow) wm.updateViewLayout(panel, params) else wm.addView(panel, params)
        }
        render()
    }
}
