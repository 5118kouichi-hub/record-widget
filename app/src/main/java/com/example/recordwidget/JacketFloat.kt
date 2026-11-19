package com.example.recordwidget

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Outline
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback

/**
 * Long-pressed jacket: it lifts off the deck to the middle of the screen, the screen behind
 * blurs, and the jacket floats gently (bobbing, a slight sway). Tap anywhere or back to put it down.
 */
class JacketFloat(private val activity: ComponentActivity) {
    private var overlay: FrameLayout? = null
    private val floats = mutableListOf<Animator>()
    private var back: OnBackPressedCallback? = null

    fun show(picture: Bitmap, from: RectF) {
        if (overlay != null) return
        val decor = activity.window.decorView as ViewGroup
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        val d = activity.resources.displayMetrics.density
        val side = (minOf(decor.width, decor.height) * 0.78f).toInt()
        val radius = 6 * d

        val image = ImageView(activity).apply {
            setImageBitmap(picture)
            scaleType = ImageView.ScaleType.FIT_XY
            elevation = 28 * d
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
            clipToOutline = true
        }
        val box = FrameLayout(activity).apply {
            setBackgroundColor(0x00000000)
            isClickable = true
            setOnClickListener { dismiss() }
            addView(image, FrameLayout.LayoutParams(side, side).apply {
                leftMargin = (decor.width - side) / 2
                topMargin = (decor.height - side) / 2
            })
        }
        decor.addView(box, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        overlay = box

        // start exactly over the small jacket, then lift to the middle
        val loc = IntArray(2).also { decor.getLocationOnScreen(it) }
        val cx = (decor.width) / 2f
        val cy = (decor.height) / 2f
        val startScale = from.width() / side
        image.scaleX = startScale
        image.scaleY = startScale
        image.translationX = from.centerX() - loc[0] - cx
        image.translationY = from.centerY() - loc[1] - cy
        image.animate().scaleX(1f).scaleY(1f).translationX(0f).translationY(0f)
            .setDuration(420).setInterpolator(OvershootInterpolator(1.1f))
            .withEndAction { startFloating(image, d) }
            .start()

        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 350
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val f = it.animatedValue as Float
                box.setBackgroundColor(((0x70 * f).toInt() shl 24) or 0x0E0907)
                if (Build.VERSION.SDK_INT >= 31) {
                    val r = 1f + 22f * f
                    content.setRenderEffect(RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP))
                }
            }
            start()
        }

        back = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = dismiss()
        }.also { activity.onBackPressedDispatcher.addCallback(it) }

        // remember for the way back
        image.tag = floatArrayOf(startScale, from.centerX() - loc[0] - cx, from.centerY() - loc[1] - cy)
    }

    private fun startFloating(v: View, d: Float) {
        fun loop(prop: String, a: Float, b: Float, ms: Long) = ObjectAnimator.ofFloat(v, prop, a, b).apply {
            duration = ms
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            start()
        }
        floats += loop("translationY", -7 * d, 7 * d, 1700)
        floats += loop("rotation", -1.6f, 1.6f, 2600)
        floats += loop("translationX", -3 * d, 3 * d, 3100)
        floats += loop("rotationX", -4f, 4f, 2300)
    }

    fun dismiss() {
        val box = overlay ?: return
        overlay = null
        back?.remove()
        back = null
        floats.forEach { it.cancel() }
        floats.clear()
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        val image = box.getChildAt(0)
        val back = image.tag as? FloatArray
        image.animate().cancel()
        image.animate().scaleX(back?.get(0) ?: 0.3f).scaleY(back?.get(0) ?: 0.3f)
            .translationX(back?.get(1) ?: 0f).translationY(back?.get(2) ?: 0f).rotation(0f).rotationX(0f)
            .setDuration(300).setInterpolator(DecelerateInterpolator()).start()
        ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 300
            addUpdateListener {
                val f = it.animatedValue as Float
                box.setBackgroundColor(((0x70 * f).toInt() shl 24) or 0x0E0907)
                if (Build.VERSION.SDK_INT >= 31) {
                    content.setRenderEffect(if (f < 0.02f) null else RenderEffect.createBlurEffect(1f + 22f * f, 1f + 22f * f, Shader.TileMode.CLAMP))
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (Build.VERSION.SDK_INT >= 31) content.setRenderEffect(null)
                    (box.parent as? ViewGroup)?.removeView(box)
                }
            })
            start()
        }
    }
}
