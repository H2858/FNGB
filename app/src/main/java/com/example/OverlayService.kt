package com.example

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.example.bot.BotManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * OverlayService: Floating UI Window with draggable controls and real-time Start/Stop toggle.
 */
class OverlayService : Service() {

    companion object {
        private const val TAG = "OverlayService"
        const val ACTION_SHOW = "com.example.action.SHOW_OVERLAY"
        const val ACTION_HIDE = "com.example.action.HIDE_OVERLAY"

        var isOverlayShowing: Boolean = false
            private set

        fun start(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
                return
            }
            val intent = Intent(context, OverlayService::class.java).apply {
                action = ACTION_SHOW
            }
            context.startService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, OverlayService::class.java).apply {
                action = ACTION_HIDE
            }
            context.startService(intent)
        }
    }

    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var isExpanded = true

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var statsJob: Job? = null

    // UI elements inside overlay
    private var statusDot: View? = null
    private var statusText: TextView? = null
    private var fpsText: TextView? = null
    private var slicesText: TextView? = null
    private var toggleButton: Button? = null
    private var contentContainer: LinearLayout? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW -> showFloatingOverlay()
            ACTION_HIDE -> hideFloatingOverlay()
        }
        return START_NOT_STICKY
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showFloatingOverlay() {
        if (floatingView != null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) return

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 50
            y = 200
        }

        val root = createOverlayLayout()
        floatingView = root

        // Drag-and-drop touch listener
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false

        root.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                        isDragging = true
                    }
                    params.x = initialX + dx
                    params.y = initialY + dy
                    windowManager?.updateViewLayout(root, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        toggleExpanded()
                    }
                    true
                }
                else -> false
            }
        }

        windowManager?.addView(root, params)
        isOverlayShowing = true

        observeStats()
    }

    private fun createOverlayLayout(): View {
        val dpToPx = { dp: Int ->
            TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                dp.toFloat(),
                resources.displayMetrics
            ).toInt()
        }

        val rootCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val backgroundDrawable = GradientDrawable().apply {
                setColor(0xEE1E1F22.toInt())
                cornerRadius = dpToPx(18).toFloat()
                setStroke(dpToPx(1), 0xFF43454B.toInt())
            }
            background = backgroundDrawable
            setPadding(dpToPx(14), dpToPx(12), dpToPx(14), dpToPx(12))
            elevation = dpToPx(8).toFloat()
        }

        // Header bar (Pill style with logo icon and status indicator)
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        statusDot = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dpToPx(10), dpToPx(10)).apply {
                marginEnd = dpToPx(8)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (BotManager.isBotActive.value) 0xFF00E676.toInt() else 0xFFFF5252.toInt())
            }
        }

        val title = TextView(this).apply {
            text = "🍉 Fruit Bot"
            setTextColor(Color.WHITE)
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val minBtn = TextView(this).apply {
            text = "━"
            setTextColor(0xFFAAAAAA.toInt())
            textSize = 14f
            setPadding(dpToPx(6), 0, dpToPx(4), 0)
            setOnClickListener { toggleExpanded() }
        }

        header.addView(statusDot)
        header.addView(title)
        header.addView(minBtn)
        rootCard.addView(header)

        // Expandable Content
        contentContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                dpToPx(170),
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(10)
            }
        }

        // Live Stats Row
        val statsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        fpsText = TextView(this).apply {
            text = "FPS: 0"
            setTextColor(0xFF81D4FA.toInt())
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        slicesText = TextView(this).apply {
            text = "Slices: 0"
            setTextColor(0xFFA5D6A7.toInt())
            textSize = 12f
        }

        statsRow.addView(fpsText)
        statsRow.addView(slicesText)
        contentContainer?.addView(statsRow)

        // Start / Stop Toggle Button
        toggleButton = Button(this).apply {
            text = if (BotManager.isBotActive.value) "PAUSE BOT" else "START BOT"
            setTextColor(Color.WHITE)
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            background = GradientDrawable().apply {
                setColor(if (BotManager.isBotActive.value) 0xFFFF3D00.toInt() else 0xFF00C853.toInt())
                cornerRadius = dpToPx(10).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(40)
            ).apply {
                topMargin = dpToPx(10)
            }
            setOnClickListener {
                val newActive = !BotManager.isBotActive.value
                BotManager.updateRunningState(newActive)
                updateToggleUI(newActive)
            }
        }
        contentContainer?.addView(toggleButton)

        rootCard.addView(contentContainer)
        return rootCard
    }

    private fun toggleExpanded() {
        isExpanded = !isExpanded
        contentContainer?.visibility = if (isExpanded) View.VISIBLE else View.GONE
    }

    private fun updateToggleUI(isActive: Boolean) {
        val dpToPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            10f,
            resources.displayMetrics
        )
        toggleButton?.text = if (isActive) "PAUSE BOT" else "START BOT"
        (toggleButton?.background as? GradientDrawable)?.setColor(
            if (isActive) 0xFFFF3D00.toInt() else 0xFF00C853.toInt()
        )
        (statusDot?.background as? GradientDrawable)?.setColor(
            if (isActive) 0xFF00E676.toInt() else 0xFFFF5252.toInt()
        )
    }

    private fun observeStats() {
        statsJob?.cancel()
        statsJob = serviceScope.launch {
            BotManager.stats.collectLatest { stats ->
                fpsText?.text = "FPS: ${stats.fps}"
                slicesText?.text = "Slices: ${stats.totalSlices}"
                updateToggleUI(stats.isRunning)
            }
        }
    }

    private fun hideFloatingOverlay() {
        if (floatingView != null) {
            statsJob?.cancel()
            statsJob = null
            windowManager?.removeView(floatingView)
            floatingView = null
            isOverlayShowing = false
        }
    }

    override fun onDestroy() {
        hideFloatingOverlay()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
