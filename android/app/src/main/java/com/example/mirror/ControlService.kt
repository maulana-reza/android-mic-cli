package com.example.mirror

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

/** Replays input from the browser: gestures, navigation keys and text. */
class ControlService : AccessibilityService() {
    override fun onServiceConnected() {
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    fun handle(msg: JSONObject) {
        when (msg.getString("t")) {
            "gesture" -> gesture(msg.getJSONArray("pts"), msg.getLong("ms"))
            "text" -> edit { it + msg.getString("s") }
            "key" -> when (msg.getString("k")) {
                "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
                "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
                "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
                "del" -> edit { it.dropLast(1) }
                "enter" -> enter()
            }
        }
    }

    // ponytail: the stroke is replayed after release (no live drag, single touch); StrokeDescription.continueStroke if live drag matters.
    private fun gesture(points: JSONArray, ms: Long) {
        val screen = screenSize()
        val path = Path()
        for (i in 0 until points.length()) {
            val p = points.getJSONArray(i)
            val x = p.getDouble(0).coerceIn(0.0, 1.0).toFloat() * (screen.x - 1)
            val y = p.getDouble(1).coerceIn(0.0, 1.0).toFloat() * (screen.y - 1)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, ms.coerceIn(1, GestureDescription.getMaxGestureDuration()))
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    // ponytail: rewrites the whole field and ignores the cursor; AccessibilityService.InputMethod (API 33) for real typing.
    private fun edit(change: (String) -> String) {
        val node = findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return
        val text = if (node.isShowingHintText) "" else node.text?.toString().orEmpty()
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, change(text))
        })
    }

    private fun enter() {
        if (Build.VERSION.SDK_INT >= 30) {
            findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        } else {
            edit { it + "\n" }
        }
    }

    companion object {
        @Volatile var instance: ControlService? = null
    }
}
