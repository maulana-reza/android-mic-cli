package com.example.mirror

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import kotlin.math.PI
import kotlin.math.cos

/**
 * The phone and the laptop with the link between them: dots while not connected (the laptop shows its QR code),
 * orange dots running towards the laptop while connecting, a green line and the phone on the laptop once connected.
 */
class LinkView(context: Context) : View(context) {
    var state = IDLE
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    private val d = resources.displayMetrics.density
    private val thick = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3 * d; strokeCap = Paint.Cap.ROUND }
    private val thin = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2 * d }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO // the headline next to it says the same
    }

    override fun onDraw(canvas: Canvas) {
        val text = context.getColor(R.color.text)
        val muted = context.getColor(R.color.muted)
        val ok = context.getColor(R.color.ok)
        val top = (height - 62 * d) / 2

        // The phone, 38 x 62 dp
        val px = 6 * d
        thick.color = if (state == IDLE) muted else text
        r.set(px + 1.5f * d, top + 1.5f * d, px + 36.5f * d, top + 60.5f * d)
        canvas.drawRoundRect(r, 7 * d, 7 * d, thick)
        canvas.drawLine(px + 15 * d, top + 8 * d, px + 23 * d, top + 8 * d, thick)

        // The laptop, 84 x 58 dp at the right
        val lx = width - 90 * d
        val ly = top + 2 * d
        fun rect(x: Float, y: Float, w: Float, h: Float, radius: Float, paint: Paint) {
            r.set(lx + x * d, ly + y * d, lx + (x + w) * d, ly + (y + h) * d)
            canvas.drawRoundRect(r, radius * d, radius * d, paint)
        }
        thick.color = if (state == LIVE) text else muted
        rect(9.5f, 1.5f, 65f, 44f, 5f, thick)
        canvas.drawLine(lx + 2 * d, ly + 54.5f * d, lx + 82 * d, ly + 54.5f * d, thick)
        if (state == LIVE) { // the phone's screen in the Mirror window
            fill.color = ok
            fill.alpha = 56
            rect(34f, 8f, 16f, 31f, 3f, fill)
            thin.color = ok
            rect(34f, 8f, 16f, 31f, 3f, thin)
        } else { // the QR code the Mirror window shows until a phone connects
            thin.color = muted
            fill.color = muted
            for ((x, y) in listOf(30f to 11f, 46f to 11f, 30f to 27f)) {
                rect(x, y, 8f, 8f, 1.5f, thin)
                rect(x + 3, y + 3, 2f, 2f, 0f, fill)
            }
            for ((x, y) in listOf(41f to 11f, 41f to 17f, 41f to 23f, 46f to 23f, 51f to 27f, 42f to 31f, 47f to 32f)) {
                rect(x, y, 3f, 3f, 0f, fill)
            }
        }

        // The link
        val x0 = px + 52 * d
        val x1 = lx - 6 * d
        val cy = height / 2f
        when (state) {
            IDLE -> {
                fill.color = context.getColor(R.color.dots)
                var x = x0
                while (x <= x1) {
                    canvas.drawCircle(x, cy, 1.5f * d, fill)
                    x += 8 * d
                }
            }
            CONNECTING -> {
                fill.color = context.getColor(R.color.wait)
                val moving = ValueAnimator.areAnimatorsEnabled() // off with "Remove animations"
                val now = SystemClock.uptimeMillis()
                for (i in 0 until 5) {
                    val phase = ((now - i * 150) % 1200) / 1200.0 // each dot lights up a little after the one before
                    fill.alpha = if (moving) (64 + 191 * (1 - cos(2 * PI * phase)) / 2).toInt() else 204
                    canvas.drawCircle(x0 + (x1 - x0) * i / 4, cy, 3.5f * d, fill)
                }
                if (moving) postInvalidateOnAnimation()
            }
            LIVE -> {
                thick.color = ok
                canvas.drawLine(x0, cy, x1, cy, thick)
            }
        }
    }

    companion object {
        const val IDLE = 0
        const val CONNECTING = 1
        const val LIVE = 2
    }
}
