package io.github.yhzion.velo.ui

import android.graphics.Rect
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider
import android.widget.EditText
import androidx.core.view.descendants

/** An insert-only input field. Its identity expires when the receiving terminal changes. */
internal class TerminalAccessibilityEditor(
    private val host: TerminalInputView,
    private val active: () -> Boolean,
    private val insert: (String) -> Boolean,
) : AccessibilityNodeProvider() {
    private var fieldId = 1
    private var accessibilityFocused = false

    fun invalidateTarget() {
        fieldId++
        updateVisibility()
    }

    fun updateVisibility() {
        // A voice overlay may briefly own window focus and then use the same pane again.
        accessibilityFocused = false
        host.importantForAccessibility = if (active()) View.IMPORTANT_FOR_ACCESSIBILITY_YES
            else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        host.parent?.notifySubtreeAccessibilityStateChanged(host, host, AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE)
        if (active()) sendFieldEvent(AccessibilityEvent.TYPE_VIEW_FOCUSED)
    }

    @Suppress("DEPRECATION")
    override fun createAccessibilityNodeInfo(virtualViewId: Int): AccessibilityNodeInfo? {
        if (virtualViewId == HOST_VIEW_ID) {
            return AccessibilityNodeInfo.obtain(host).also {
                host.onInitializeAccessibilityNodeInfo(it)
                it.isFocused = false
                it.isFocusable = false
                it.removeAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_FOCUS)
                it.removeAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLEAR_FOCUS)
                if (active()) it.addChild(host, fieldId)
            }
        }
        if (virtualViewId != fieldId || !active()) return null
        return AccessibilityNodeInfo.obtain().apply {
            setSource(host, fieldId)
            setParent(host)
            packageName = host.context.packageName
            className = EditText::class.java.name
            contentDescription = "Terminal input"
            inputType = InputType.TYPE_CLASS_TEXT
            isEditable = true
            isImportantForAccessibility = true
            isEnabled = true
            isFocusable = true
            isFocused = true
            isAccessibilityFocused = accessibilityFocused
            text = ""
            setTextSelection(0, 0)
            // The IME owner is one pixel; services need the visible receiving pane's bounds.
            val pane = (host.rootView as? ViewGroup)?.descendants?.filterIsInstance<PaneSurfaceView>()
                ?.firstOrNull { it.isShown && !it.preview && it.block == host.target() }
            val bounds = Rect()
            isVisibleToUser = pane != null && pane.getLocalVisibleRect(bounds)
            if (pane != null) {
                val location = IntArray(2)
                pane.getLocationOnScreen(location)
                bounds.offset(location[0], location[1])
                setBoundsInScreen(bounds)
                host.getLocationOnScreen(location)
                bounds.offset(-location[0], -location[1])
                setBoundsInParent(bounds)
            }
            addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_TEXT)
            addAction(if (accessibilityFocused) AccessibilityNodeInfo.AccessibilityAction.ACTION_CLEAR_ACCESSIBILITY_FOCUS
                else AccessibilityNodeInfo.AccessibilityAction.ACTION_ACCESSIBILITY_FOCUS)
        }
    }

    override fun findFocus(focus: Int): AccessibilityNodeInfo? =
        if (focus == AccessibilityNodeInfo.FOCUS_INPUT ||
            (focus == AccessibilityNodeInfo.FOCUS_ACCESSIBILITY && accessibilityFocused)) {
            createAccessibilityNodeInfo(fieldId)
        } else null

    @Suppress("DEPRECATION")
    override fun performAction(virtualViewId: Int, action: Int, arguments: Bundle?): Boolean {
        if (virtualViewId == HOST_VIEW_ID) return host.performAccessibilityAction(action, arguments)
        if (virtualViewId != fieldId || !active()) return false
        when (action) {
            AccessibilityNodeInfo.ACTION_SET_TEXT -> {
                val value = arguments?.getCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE)
                    ?: return false
                return insert(value.toString())
            }
            AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS -> {
                val focus = action == AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS
                if (focus == accessibilityFocused) return false
                accessibilityFocused = focus
                val type = if (focus) AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED
                    else AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED
                sendFieldEvent(type)
                return true
            }
            else -> return false
        }
    }

    fun focusEvent(event: AccessibilityEvent) {
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED && active()) {
            event.setSource(host, fieldId)
            event.className = EditText::class.java.name
        }
    }

    @Suppress("DEPRECATION")
    private fun sendFieldEvent(type: Int) {
        if (host.context.getSystemService(AccessibilityManager::class.java)?.isEnabled != true) return
        val event = AccessibilityEvent.obtain(type)
        event.setSource(host, fieldId)
        event.packageName = host.context.packageName
        event.className = EditText::class.java.name
        host.parent?.requestSendAccessibilityEvent(host, event)
    }
}
