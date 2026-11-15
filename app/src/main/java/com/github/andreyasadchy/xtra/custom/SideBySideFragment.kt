package com.github.andreyasadchy.xtra.custom

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.util.getAlertDialogBuilder
import com.google.android.material.button.MaterialButton

/**
 * Xtra5s custom: the "Side-By-Side" tab on the Following page. A big "+" opens the popup where
 * two channels are typed in (see [SideBySide.showAddDialog]); recent pairs are listed below it.
 * Tap a pair to open the popup with it filled in, long-press to remove it. Each pair shows both
 * channels' profile pictures.
 */
class SideBySideFragment : Fragment() {

    private var recentList: LinearLayout? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val context = SideBySide.material(requireContext())
        val dp = resources.displayMetrics.density
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val pad = (16 * dp).toInt()
            setPadding(pad, (32 * dp).toInt(), pad, pad)
        }
        val add = MaterialButton(context, null, com.google.android.material.R.attr.materialIconButtonFilledStyle).apply {
            setIconResource(R.drawable.xtra5s_add)
            iconSize = (40 * dp).toInt()
            iconPadding = 0
            iconGravity = MaterialButton.ICON_GRAVITY_TEXT_TOP
            contentDescription = "Add two channels"
            val size = (88 * dp).toInt()
            minimumWidth = size
            minimumHeight = size
            minWidth = size
            minHeight = size
            setOnClickListener { SideBySide.showAddDialog(requireActivity()) }
        }
        column.addView(add, LinearLayout.LayoutParams((88 * dp).toInt(), (88 * dp).toInt()))
        column.addView(TextView(context).apply {
            text = "Watch two streams side by side"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            gravity = Gravity.CENTER
            setPadding(0, (12 * dp).toInt(), 0, (24 * dp).toInt())
        })
        val recent = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        recentList = recent
        column.addView(recent, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return ScrollView(context).apply {
            isFillViewport = true
            addView(column, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    override fun onResume() {
        super.onResume()
        showRecent()
    }

    private fun showRecent() {
        val list = recentList ?: return
        val context = SideBySide.material(list.context)
        val dp = resources.displayMetrics.density
        list.removeAllViews()
        val pairs = SideBySide.recentPairs(context)
        if (pairs.isEmpty()) return
        list.addView(TextView(context).apply {
            text = "Recent"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, 0, 0, (8 * dp).toInt())
        })
        pairs.forEach { pair -> list.addView(pairRow(context, pair), LinearLayout.LayoutParams((520 * dp).toInt().coerceAtMost(resources.displayMetrics.widthPixels - (32 * dp).toInt()), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = (6 * dp).toInt()
        }) }
        // Pictures for recent channels that have none saved yet.
        viewLifecycleOwner.lifecycleScope.launch {
            val logins = pairs.flatMap { listOf(it.first, it.second) }
            if (logins.all { SideBySide.avatars(context)[it] != null }) return@launch
            SideBySide.ensureAvatars(context, logins)
            if (recentList === list) list.avatarViews().forEach { (view, login) -> SideBySide.loadAvatar(view, login) }
        }
    }

    private fun LinearLayout.avatarViews(): List<Pair<ImageView, String>> {
        val result = ArrayList<Pair<ImageView, String>>()
        fun walk(view: View) {
            if (view is ImageView && view.tag is String) result.add(view to view.tag as String)
            if (view is ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i))
        }
        walk(this)
        return result
    }

    /** One recent pair: both profile pictures and names. Tap to open, long-press to remove. */
    private fun pairRow(context: android.content.Context, pair: Pair<String, String>): View {
        val dp = resources.displayMetrics.density
        val outline = TypedValue().also { context.theme.resolveAttribute(com.google.android.material.R.attr.colorOutline, it, true) }.data
        fun border(color: Int, width: Float) = GradientDrawable().apply {
            cornerRadius = 36 * dp
            setStroke((width * dp).toInt(), color)
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            background = border(outline, 1f)
            foreground = SideBySide.selectableBackground(context)
            val pad = (10 * dp).toInt()
            setPadding(pad, (12 * dp).toInt(), pad, (12 * dp).toInt())
            setOnFocusChangeListener { v, hasFocus -> v.background = if (hasFocus) border(SideBySide.GOLD, 2f) else border(outline, 1f) }
            setOnClickListener { SideBySide.showAddDialog(requireActivity(), pair) }
            setOnLongClickListener {
                requireContext().getAlertDialogBuilder()
                    .setMessage("Remove ${pair.first} | ${pair.second} from recent?")
                    .setPositiveButton("Remove") { _, _ -> SideBySide.removeRecent(requireContext(), pair); showRecent() }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
                true
            }
        }
        fun channel(login: String) = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(ImageView(context).apply {
                tag = login
                SideBySide.loadAvatar(this, login)
            }, LinearLayout.LayoutParams((48 * dp).toInt(), (48 * dp).toInt()))
            addView(TextView(context).apply {
                text = login
                setSingleLine()
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                setPadding((10 * dp).toInt(), 0, 0, 0)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        row.addView(channel(pair.first), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(TextView(context).apply {
            text = "|"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setPadding((8 * dp).toInt(), 0, (8 * dp).toInt(), 0)
        })
        row.addView(channel(pair.second), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    override fun onDestroyView() {
        super.onDestroyView()
        recentList = null
    }
}
