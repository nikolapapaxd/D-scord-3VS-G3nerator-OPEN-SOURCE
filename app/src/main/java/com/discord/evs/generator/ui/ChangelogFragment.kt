package com.discord.evs.generator.ui

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.discord.evs.generator.BuildConfig
import com.discord.evs.generator.data.ChangelogData
import com.discord.evs.generator.databinding.FragmentChangelogBinding
import com.discord.evs.generator.databinding.ItemChangelogReleaseBinding
import java.util.regex.Pattern

/**
 * Changelog screen — Discord Tools style.
 * Entry text supports lightweight markdown:
 *  # heading / ## heading
 *  `inline code`
 *  **bold**  *italic*
 *  plain body
 */
class ChangelogFragment : Fragment() {

    private var _binding: FragmentChangelogBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentChangelogBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.tvChangelogSubtitle.text = "EVS Generator · current ${BuildConfig.VERSION_NAME}"
        binding.btnChangelogBack.setOnClickListener { findNavController().navigateUp() }
        binding.rvChangelog.layoutManager = LinearLayoutManager(requireContext())
        binding.rvChangelog.adapter = ReleaseAdapter(ChangelogData.releases)
        binding.rvChangelog.setHasFixedSize(false)
        binding.rvChangelog.setItemViewCacheSize(8)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private class ReleaseAdapter(
        private val items: List<ChangelogData.Release>
    ) : RecyclerView.Adapter<ReleaseAdapter.VH>() {

        inner class VH(val b: ItemChangelogReleaseBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = ItemChangelogReleaseBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(b)
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val r = items[position]
            holder.b.tvVersion.text = "v${r.version}"
            holder.b.tvDate.text = r.date
            holder.b.tvTitle.text = formatInlineMarkdown(r.title)
            holder.b.entriesContainer.removeAllViews()
            val ctx = holder.b.root.context
            for (e in r.entries) {
                val row = TextView(ctx)
                val (badge, color) = when (e.type) {
                    ChangelogData.Type.NEW -> "NEW" to "#57F287"
                    ChangelogData.Type.IMPROVED -> "IMPROVED" to "#5865F2"
                    ChangelogData.Type.FIXED -> "FIXED" to "#FEE75C"
                    ChangelogData.Type.NOTE -> "NOTE" to "#B5BAC1"
                }
                val body = formatEntryMarkdown(e.text)
                val full = SpannableStringBuilder()
                val badgeStart = full.length
                full.append(badge)
                val badgeEnd = full.length
                full.setSpan(StyleSpan(Typeface.BOLD), badgeStart, badgeEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                full.setSpan(ForegroundColorSpan(Color.parseColor(color)), badgeStart, badgeEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                full.append("  ")
                full.append(body)
                row.text = full
                row.setTextColor(Color.parseColor("#DBDEE1"))
                row.textSize = 12.5f
                row.setPadding(0, 6, 0, 6)
                row.setLineSpacing(0f, 1.15f)
                holder.b.entriesContainer.addView(
                    row,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                )
            }
        }
    }

    companion object {
        private val CODE = Pattern.compile("`([^`]+)`")
        private val BOLD = Pattern.compile("\\*\\*([^*]+)\\*\\*")
        private val ITALIC = Pattern.compile("(?<!\\*)\\*([^*]+)\\*(?!\\*)")
        private val HEADING = Pattern.compile("^(#{1,3})\\s+(.+)$", Pattern.MULTILINE)

        /** Title / single-line: code + bold + italic */
        fun formatInlineMarkdown(src: String): CharSequence {
            if (src.isEmpty()) return src
            return applyInline(SpannableStringBuilder(src))
        }

        /**
         * Entry body: headings (# ## ###) become larger bold lines;
         * inline `code`, **bold**, *italic* still apply.
         */
        fun formatEntryMarkdown(src: String): CharSequence {
            if (src.isEmpty()) return src
            val out = SpannableStringBuilder()
            val lines = src.split('\n')
            for ((idx, rawLine) in lines.withIndex()) {
                if (idx > 0) out.append('\n')
                val m = HEADING.matcher(rawLine)
                if (m.matches()) {
                    val level = m.group(1)!!.length
                    val text = m.group(2)!!
                    val start = out.length
                    out.append(text)
                    val end = out.length
                    val scale = when (level) {
                        1 -> 1.35f
                        2 -> 1.22f
                        else -> 1.12f
                    }
                    out.setSpan(RelativeSizeSpan(scale), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    out.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    out.setSpan(ForegroundColorSpan(Color.parseColor("#F2F3F5")), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                } else {
                    out.append(rawLine)
                }
            }
            return applyInline(out)
        }

        private fun applyInline(sb: SpannableStringBuilder): SpannableStringBuilder {
            // code first (so * inside code is not italicized)
            replaceAll(sb, CODE) { _, start, end ->
                sb.setSpan(TypefaceSpan("monospace"), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(BackgroundColorSpan(Color.parseColor("#1E1F22")), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(ForegroundColorSpan(Color.parseColor("#E3E5E8")), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(RelativeSizeSpan(0.95f), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            replaceAll(sb, BOLD) { _, start, end ->
                sb.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            replaceAll(sb, ITALIC) { _, start, end ->
                sb.setSpan(StyleSpan(Typeface.ITALIC), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            return sb
        }

        /** Find pattern, strip markers, apply span on remaining text. Processes from end so indices stay valid. */
        private fun replaceAll(
            sb: SpannableStringBuilder,
            pattern: Pattern,
            apply: (inner: String, start: Int, end: Int) -> Unit
        ) {
            val matches = mutableListOf<IntArray>() // [fullStart, fullEnd, innerStart, innerEnd] in original
            val m = pattern.matcher(sb)
            while (m.find()) {
                matches.add(intArrayOf(m.start(), m.end(), m.start(1), m.end(1)))
            }
            // from last to first
            for (i in matches.size - 1 downTo 0) {
                val (fs, fe, is_, ie) = matches[i]
                val inner = sb.substring(is_, ie)
                // delete markers: keep only inner
                sb.replace(fs, fe, inner)
                val newStart = fs
                val newEnd = fs + inner.length
                apply(inner, newStart, newEnd)
            }
        }
    }
}
