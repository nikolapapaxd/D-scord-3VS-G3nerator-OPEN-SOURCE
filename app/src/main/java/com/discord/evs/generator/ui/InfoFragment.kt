package com.discord.evs.generator.ui

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.discord.evs.generator.BuildConfig
import com.discord.evs.generator.automation.InstallCounter
import com.discord.evs.generator.data.Prefs
import com.discord.evs.generator.databinding.FragmentInfoBinding

class InfoFragment : Fragment() {

    private var _binding: FragmentInfoBinding? = null
    private val binding get() = _binding!!
    private var unsub: (() -> Unit)? = null
    private var displayedCount: Int = -1

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentInfoBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        UiMotion.enter(view)
        UiMotion.staggerChildren(view as? ViewGroup ?: return)

        binding.tvVersion.text = "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"

        val prefs = Prefs(requireContext())
        binding.switchDisableAccountSharing.isChecked = prefs.disableAccountSharing
        binding.switchDisableAccountSharing.setOnCheckedChangeListener { _, checked ->
            prefs.disableAccountSharing = checked
        }
        try {
            binding.switchDisableBioInjection.isChecked = prefs.disableBioInjection
            binding.switchDisableBioInjection.setOnCheckedChangeListener { _, checked ->
                prefs.disableBioInjection = checked
            }
        } catch (_: Exception) { }

        binding.btnBack.setOnClickListener {
            findNavController().navigateUp()
        }

        binding.btnYoutube.setOnClickListener {
            openUrl("")
        }

        binding.btnDiscord.setOnClickListener {
            openUrl("https://discord.gg/T4M58YQWJj")
        }
        binding.btnInstagram.setOnClickListener {
            openUrl("https://instagram.com/yourhandle")
        }

        val c = InstallCounter.currentCount()
        displayedCount = c
        binding.tvInstallCount.text = if (c >= 0) c.toString() else "…"
        binding.tvInstallStatus.text = InstallCounter.currentStatus()

        unsub = InstallCounter.subscribe { count, status ->
            activity?.runOnUiThread {
                val b = _binding ?: return@runOnUiThread
                b.tvInstallStatus.text = status
                if (count >= 0 && count != displayedCount) {
                    animateCountChange(displayedCount, count)
                    displayedCount = count
                } else if (count >= 0) {
                    b.tvInstallCount.text = count.toString()
                    displayedCount = count
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        InstallCounter.startLive(requireContext())
        InstallCounter.refresh(requireContext())
    }

    override fun onPause() {
        InstallCounter.stopLive()
        super.onPause()
    }

    /** Old number slides up & fades out; new number drops in from above with a slight bounce. */
    private fun animateCountChange(from: Int, to: Int) {
        val tv = _binding?.tvInstallCount ?: return
        val h = tv.height.toFloat().coerceAtLeast(48f)

        if (from < 0) {
            tv.text = to.toString()
            tv.alpha = 0f
            tv.translationY = -h * 0.6f
            tv.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(320)
                .setInterpolator(OvershootInterpolator(1.4f))
                .start()
            return
        }

        // Phase 1: current number exits upward
        val outY = ObjectAnimator.ofFloat(tv, View.TRANSLATION_Y, 0f, -h * 0.85f)
        val outA = ObjectAnimator.ofFloat(tv, View.ALPHA, 1f, 0f)
        val outScale = ObjectAnimator.ofFloat(tv, View.SCALE_Y, 1f, 0.85f)
        AnimatorSet().apply {
            playTogether(outY, outA, outScale)
            duration = 180
            interpolator = DecelerateInterpolator()
            start()
        }
        tv.postDelayed({
            val b = _binding ?: return@postDelayed
            b.tvInstallCount.text = to.toString()
            b.tvInstallCount.translationY = h * 0.85f
            b.tvInstallCount.alpha = 0f
            b.tvInstallCount.scaleY = 0.85f
            // Phase 2: new number enters from top
            val inY = ObjectAnimator.ofFloat(b.tvInstallCount, View.TRANSLATION_Y, h * 0.85f, 0f)
            val inA = ObjectAnimator.ofFloat(b.tvInstallCount, View.ALPHA, 0f, 1f)
            val inScale = ObjectAnimator.ofFloat(b.tvInstallCount, View.SCALE_Y, 0.85f, 1f)
            AnimatorSet().apply {
                playTogether(inY, inA, inScale)
                duration = 280
                interpolator = OvershootInterpolator(1.6f)
                start()
            }
        }, 190)
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) { }
    }

    override fun onDestroyView() {
        unsub?.invoke()
        unsub = null
        super.onDestroyView()
        _binding = null
    }
}
