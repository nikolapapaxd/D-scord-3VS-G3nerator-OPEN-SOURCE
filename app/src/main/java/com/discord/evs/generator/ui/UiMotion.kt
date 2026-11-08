package com.discord.evs.generator.ui

import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import androidx.interpolator.view.animation.FastOutSlowInInterpolator

/**
 * Small, shared motion language for the app shell. The animations are deliberately
 * short and subtle so they add hierarchy without slowing down power users.
 */
object UiMotion {
    private const val ENTER_DURATION = 280L

    fun enter(view: View, delay: Long = 0L) {
        view.alpha = 0f
        view.translationY = 18f
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay(delay)
            .setDuration(ENTER_DURATION)
            .setInterpolator(FastOutSlowInInterpolator())
            .start()
    }

    fun staggerChildren(container: ViewGroup) {
        var delay = 35L
        for (index in 0 until container.childCount) {
            val child = container.getChildAt(index)
            if (child.visibility != View.VISIBLE) continue
            enter(child, delay)
            delay = (delay + 22L).coerceAtMost(180L)
        }
    }

    fun press(view: View) {
        view.animate()
            .scaleX(0.97f)
            .scaleY(0.97f)
            .setDuration(90L)
            .setInterpolator(OvershootInterpolator())
            .withEndAction {
                view.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(150L)
                    .setInterpolator(FastOutSlowInInterpolator())
                    .start()
            }
            .start()
    }
}