package app.couchphone

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Notices when a text box is picked on the TV (a search bar in an app or on a website), so the
 * phone remote opens its keyboard by itself. Turned on with root; it only looks at whether the
 * focused thing takes text, and reads nothing else.
 */
class TypingWatch : AccessibilityService() {
    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        when (e.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED, AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                val src = e.source ?: return
                Typing.set(src.isEditable, e.packageName?.toString())
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                // a new screen: typing only if it opened with a text box already picked
                val f = runCatching { rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()
                Typing.set(f?.isEditable == true, e.packageName?.toString())
            }
        }
    }

    override fun onInterrupt() {}
}

/** Whether a text box is picked on the TV right now; the remote hears of each change (Now). */
object Typing {
    @Volatile var on = false
        private set
    @Volatile var since = 0L
        private set

    fun set(typing: Boolean, pkg: String?) {
        if (pkg == "com.sec.android.inputmethod" || pkg == "com.samsung.android.honeyboard") return   // the keyboard itself
        if (typing == on) return
        on = typing; since = System.currentTimeMillis()
        Now.push()
    }
}
