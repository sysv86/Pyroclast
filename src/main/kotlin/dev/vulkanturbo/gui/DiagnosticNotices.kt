package dev.vulkanturbo.gui

import com.mojang.blaze3d.platform.InputConstants
import dev.vulkanturbo.api.shader.TurboShadersBridge
import dev.vulkanturbo.diagnostics.VulkanDiagnosticLog
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.toasts.SystemToast
import net.minecraft.client.gui.components.toasts.Toast
import net.minecraft.client.gui.components.toasts.ToastManager
import net.minecraft.network.chat.Component
import java.lang.reflect.Method

/**
 * Pure-Kotlin clickable bottom-right diagnostic toast and F9 hotkey poller opening
 * [VulkanErrorLogScreen]. Works standalone in Pyroclast and automatically serves as the single
 * unified error notification handler when an extension (such as Hyaloclast) is registered.
 */
object DiagnosticNotices {
    private const val ERROR_TOAST_DURATION_MS: Long = 8000L

    private val errorToastId = SystemToast.SystemToastId(ERROR_TOAST_DURATION_MS)

    private data class ToastBounds(
        val x: Float,
        val y: Float,
        val width: Float,
        val height: Float,
    )

    private enum class KeyDownMode {
        WINDOW_INT,
        INT_ONLY,
        LONG_INT,
        NONE,
    }

    @Volatile
    private var activeErrorToastBounds: ToastBounds? = null

    @Volatile
    private var activeErrorToastUntilMs: Long = 0L

    @Volatile
    private var activeErrorToast: BottomToast? = null

    @Volatile
    private var cachedKeyDownMethod: Method? = null

    @Volatile
    private var cachedKeyDownMode: KeyDownMode? = null

    private var f9WasDown: Boolean = false
    private var leftMouseWasDown: Boolean = false

    @Suppress("UNUSED_PARAMETER")
    @JvmStatic
    fun showClickableErrorToast(summary: String) {
        val mc = runCatching { Minecraft.getInstance() }.getOrNull() ?: return
        val showAction = {
            val manager = mc.gui.toastManager()
            val ext = TurboShadersBridge.extension
            val title = if (ext != null) {
                Component.translatable("vulkanturbo.toast.error_title_unified", ext.displayName)
            } else {
                Component.translatable("vulkanturbo.toast.error_title")
            }
            val message = Component.translatable("vulkanturbo.toast.error_click")
            val existing = manager.getToast(BottomToast::class.java, errorToastId)
            val toast = if (existing != null && !existing.dismissed) {
                existing.reset(title, message)
                existing
            } else {
                val created = BottomToast(errorToastId, title, message, trackAsErrorToast = true)
                manager.addToast(created)
                created
            }
            activeErrorToast = toast
            activeErrorToastUntilMs = System.currentTimeMillis() + ERROR_TOAST_DURATION_MS
            val guiWidth = mc.window.guiScaledWidth
            val guiHeight = mc.window.guiScaledHeight
            val w = toast.width().toFloat()
            val h = toast.height().toFloat()
            activeErrorToastBounds = ToastBounds(
                x = (guiWidth - w).coerceAtLeast(0f),
                y = (guiHeight - h - 6f).coerceAtLeast(0f),
                width = w,
                height = h,
            )
        }
        if (mc.isSameThread) {
            showAction()
        } else {
            mc.execute(showAction)
        }
    }

    @JvmStatic
    fun pollErrorToastInteraction(mc: Minecraft) {
        val window = runCatching { mc.window }.getOrNull() ?: return

        val now = System.currentTimeMillis()
        if (activeErrorToastUntilMs > 0L && now > activeErrorToastUntilMs) {
            activeErrorToastBounds = null
            activeErrorToastUntilMs = 0L
            activeErrorToast = null
        }

        val f9Down = isKeyDown(window, InputConstants.KEY_F9)
        val f9Triggered = f9Down && !f9WasDown
        f9WasDown = f9Down

        val leftDown = runCatching { mc.mouseHandler.isLeftPressed() }.getOrDefault(false)
        val leftClicked = leftDown && !leftMouseWasDown
        leftMouseWasDown = leftDown

        val currentScreen = mc.gui.screen()
        if (currentScreen is VulkanErrorLogScreen) return

        val toastActive = now <= activeErrorToastUntilMs
        if (f9Triggered && (toastActive || VulkanDiagnosticLog.hasUnreadErrors)) {
            openErrorLogScreen(mc)
            return
        }

        if (leftClicked && toastActive) {
            val bounds = activeErrorToastBounds ?: return
            if (runCatching { mc.mouseHandler.isMouseGrabbed() }.getOrDefault(true)) return

            val gx = mc.mouseHandler.xpos() * window.guiScaledWidth.toDouble() /
                window.screenWidth.toDouble().coerceAtLeast(1.0)
            val gy = mc.mouseHandler.ypos() * window.guiScaledHeight.toDouble() /
                window.screenHeight.toDouble().coerceAtLeast(1.0)
            if (gx >= bounds.x &&
                gx <= bounds.x + bounds.width &&
                gy >= bounds.y &&
                gy <= bounds.y + bounds.height
            ) {
                openErrorLogScreen(mc)
            }
        }
    }

    private fun isKeyDown(window: Any, keyCode: Int): Boolean {
        val mode = cachedKeyDownMode ?: resolveKeyDownMethod(window)
        val method = cachedKeyDownMethod
        if (mode == KeyDownMode.NONE || method == null) return false
        return runCatching {
            when (mode) {
                KeyDownMode.WINDOW_INT -> method.invoke(null, window, keyCode) as? Boolean ?: false
                KeyDownMode.INT_ONLY -> method.invoke(null, keyCode) as? Boolean ?: false
                KeyDownMode.LONG_INT -> {
                    val handle = window.javaClass.getMethod("handle").invoke(window) as? Long ?: 0L
                    if (handle == 0L) false else (method.invoke(null, handle, keyCode) as? Boolean ?: false)
                }
                KeyDownMode.NONE -> false
            }
        }.getOrDefault(false)
    }

    private fun resolveKeyDownMethod(window: Any): KeyDownMode {
        val methods = InputConstants::class.java.methods.filter { it.name == "isKeyDown" }
        val windowInt = methods.firstOrNull {
            it.parameterCount == 2 &&
                it.parameterTypes[0].isInstance(window) &&
                it.parameterTypes[1] == Int::class.javaPrimitiveType
        }
        if (windowInt != null) {
            cachedKeyDownMethod = windowInt
            cachedKeyDownMode = KeyDownMode.WINDOW_INT
            return KeyDownMode.WINDOW_INT
        }

        val intOnly = methods.firstOrNull {
            it.parameterCount == 1 && it.parameterTypes[0] == Int::class.javaPrimitiveType
        }
        if (intOnly != null) {
            cachedKeyDownMethod = intOnly
            cachedKeyDownMode = KeyDownMode.INT_ONLY
            return KeyDownMode.INT_ONLY
        }

        val longInt = methods.firstOrNull {
            it.parameterCount == 2 &&
                it.parameterTypes[0] == Long::class.javaPrimitiveType &&
                it.parameterTypes[1] == Int::class.javaPrimitiveType
        }
        if (longInt != null) {
            cachedKeyDownMethod = longInt
            cachedKeyDownMode = KeyDownMode.LONG_INT
            return KeyDownMode.LONG_INT
        }

        cachedKeyDownMethod = null
        cachedKeyDownMode = KeyDownMode.NONE
        return KeyDownMode.NONE
    }

    private fun openErrorLogScreen(mc: Minecraft) {
        dismissActiveErrorToast()
        mc.gui.setScreen(VulkanErrorLogScreen(mc.gui.screen(), mc.options))
    }

    private fun dismissActiveErrorToast() {
        activeErrorToast?.dismissed = true
        activeErrorToast = null
        activeErrorToastBounds = null
        activeErrorToastUntilMs = 0L
    }

    private class BottomToast(
        id: SystemToastId,
        title: Component,
        message: Component,
        private val trackAsErrorToast: Boolean = false,
    ) : SystemToast(id, title, message) {
        private var screenWidth = Minecraft.getInstance().window.guiScaledWidth
        private var screenHeight = Minecraft.getInstance().window.guiScaledHeight
        private var lastY: Float = (screenHeight - height() - 6).toFloat()

        @Volatile
        var dismissed: Boolean = false

        override fun getWantedVisibility(): Toast.Visibility =
            if (dismissed) Toast.Visibility.HIDE else super.getWantedVisibility()

        override fun update(manager: ToastManager, fullyVisibleForMs: Long) {
            screenWidth = manager.minecraft.window.guiScaledWidth
            screenHeight = manager.minecraft.window.guiScaledHeight
            if (trackAsErrorToast && !dismissed) {
                activeErrorToastBounds = ToastBounds(
                    x = (screenWidth - width()).toFloat(),
                    y = lastY,
                    width = width().toFloat(),
                    height = height().toFloat(),
                )
            }
            super.update(manager, fullyVisibleForMs)
        }

        override fun yPos(firstSlotIndex: Int): Float {
            val y = (screenHeight - height() - 6 - firstSlotIndex * Toast.SLOT_HEIGHT).toFloat()
            lastY = y
            if (trackAsErrorToast && !dismissed) {
                activeErrorToastBounds = ToastBounds(
                    x = (screenWidth - width()).toFloat(),
                    y = y,
                    width = width().toFloat(),
                    height = height().toFloat(),
                )
            }
            return y
        }
    }
}
