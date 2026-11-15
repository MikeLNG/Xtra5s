package com.github.andreyasadchy.xtra.custom

import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.util.getAlertDialogBuilder
import com.google.android.material.button.MaterialButton

/**
 * Xtra5s custom: the "Side-By-Side" tab on the Following page. A big "+" opens the popup where
 * two channels are typed in (see [SideBySide.showAddDialog]); recent pairs are listed below it.
 * Tap a pair to open the popup with it filled in, long-press to remove it.
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
        pairs.forEach { pair ->
            list.addView(MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = "${pair.first}   |   ${pair.second}"
                isAllCaps = false
                setOnClickListener { SideBySide.showAddDialog(requireActivity(), pair) }
                setOnLongClickListener {
                    requireContext().getAlertDialogBuilder()
                        .setMessage("Remove ${pair.first} | ${pair.second} from recent?")
                        .setPositiveButton("Remove") { _, _ -> SideBySide.removeRecent(requireContext(), pair); showRecent() }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                    true
                }
            }, LinearLayout.LayoutParams((360 * dp).toInt().coerceAtMost(resources.displayMetrics.widthPixels - (32 * dp).toInt()), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = (4 * dp).toInt()
            })
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        recentList = null
    }
}
