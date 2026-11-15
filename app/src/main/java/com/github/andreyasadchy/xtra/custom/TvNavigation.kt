package com.github.andreyasadchy.xtra.custom

import android.animation.ValueAnimator
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.ViewCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.ui.channel.ChannelPagerFragment
import com.github.andreyasadchy.xtra.ui.game.GameMediaFragment
import com.github.andreyasadchy.xtra.ui.game.GamePagerFragment
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.card.MaterialCardView
import java.util.WeakHashMap

/**
 * Xtra5 custom: easier remote (D-pad) navigation. Only reacts to focus, which touch doesn't use.
 *  - Channel and game pages: moving down past the first row of a list slides the banner away,
 *    keeping the top bar and the tabs; moving back to the first row (or onto the tabs) brings
 *    it back. (Touch swiping already collapses it; remote movement didn't.)
 *  - Cards (streams, videos, clips, channels, games) show a clear outline while selected.
 *
 * Kept in its own package so upstream Xtra updates can be merged without conflicts.
 * Started from UpstreamUpdates.onMainActivityCreate (no extra upstream hook).
 */
object TvNavigation {

    private const val OUTLINE_DP = 3
    private val focusListeners = WeakHashMap<Fragment, ViewTreeObserver.OnGlobalFocusChangeListener>()

    fun register(activity: MainActivity) {
        activity.supportFragmentManager.registerFragmentLifecycleCallbacks(object : FragmentManager.FragmentLifecycleCallbacks() {
            override fun onFragmentViewCreated(fm: FragmentManager, f: Fragment, v: View, savedInstanceState: android.os.Bundle?) {
                outlineCards(v)
                if (f is ChannelPagerFragment || f is GamePagerFragment || f is GameMediaFragment) watchHeader(f, v)
            }

            override fun onFragmentViewDestroyed(fm: FragmentManager, f: Fragment) {
                val listener = focusListeners.remove(f) ?: return
                f.activity?.window?.decorView?.viewTreeObserver?.takeIf { it.isAlive }?.removeOnGlobalFocusChangeListener(listener)
            }
        }, true)
    }

    // ---- Banner collapse ----

    private fun watchHeader(fragment: Fragment, root: View) {
        val appBar = root.findViewById<AppBarLayout>(R.id.appBar) ?: return
        val toolbar = root.findViewById<View>(R.id.toolbar) ?: return
        val bottomBar = root.findViewById<View>(R.id.toolbarContainer2) ?: return
        var animator: ValueAnimator? = null
        var collapsed = false
        val listener = ViewTreeObserver.OnGlobalFocusChangeListener { _, newFocus ->
            if (newFocus == null || !isInside(newFocus, root)) return@OnGlobalFocusChangeListener
            val collapse = when {
                isInside(newFocus, appBar) -> false
                else -> pastFirstRow(newFocus, root) ?: return@OnGlobalFocusChangeListener
            }
            if (collapse == collapsed) return@OnGlobalFocusChangeListener
            collapsed = collapse
            animator?.cancel()
            if (!collapse) {
                appBar.setExpanded(true, true)
                return@OnGlobalFocusChangeListener
            }
            // Leave the top bar and the tabs showing.
            val target = -(appBar.height - toolbar.height - bottomBar.height).coerceIn(0, appBar.totalScrollRange)
            val coordinator = appBar.parent as? CoordinatorLayout ?: return@OnGlobalFocusChangeListener
            val behavior = (appBar.layoutParams as? CoordinatorLayout.LayoutParams)?.behavior as? AppBarLayout.Behavior ?: return@OnGlobalFocusChangeListener
            val start = behavior.topAndBottomOffset
            if (start <= target) return@OnGlobalFocusChangeListener
            var last = start
            animator = ValueAnimator.ofInt(start, target).apply {
                duration = 200L
                addUpdateListener {
                    val value = it.animatedValue as Int
                    val dy = last - value
                    last = value
                    if (dy > 0) behavior.onNestedPreScroll(coordinator, appBar, appBar, 0, dy, IntArray(2), ViewCompat.TYPE_NON_TOUCH)
                }
                start()
            }
        }
        val observer = fragment.activity?.window?.decorView?.viewTreeObserver ?: return
        observer.addOnGlobalFocusChangeListener(listener)
        focusListeners[fragment] = listener
    }

    private fun isInside(view: View, parent: View): Boolean =
        generateSequence(view) { it.parent as? View }.any { it === parent }

    /** True if [focused] is a list item below the first row, false if in the first row, null if not in a list. */
    private fun pastFirstRow(focused: View, root: View): Boolean? {
        var child: View = focused
        var parent = child.parent
        while (parent != null && parent !is RecyclerView) {
            child = parent as? View ?: return null
            if (child === root) return null
            parent = child.parent
        }
        val list = parent as? RecyclerView ?: return null
        val position = list.getChildAdapterPosition(child).takeIf { it != RecyclerView.NO_POSITION } ?: return null
        val firstRow = when (val lm = list.layoutManager) {
            is GridLayoutManager -> lm.spanCount
            is StaggeredGridLayoutManager -> lm.spanCount
            else -> 1
        }
        return position >= firstRow
    }

    // ---- Card outline ----

    private fun outlineCards(root: View) {
        findLists(root).forEach { list ->
            if (list.getTag(R.id.xtra5_tv_outline) == true) return@forEach
            list.setTag(R.id.xtra5_tv_outline, true)
            list.addOnChildAttachStateChangeListener(object : RecyclerView.OnChildAttachStateChangeListener {
                override fun onChildViewAttachedToWindow(view: View) {
                    val card = view as? MaterialCardView ?: return
                    if (card.getTag(R.id.xtra5_tv_outline) == true) return
                    card.setTag(R.id.xtra5_tv_outline, true)
                    val width = card.strokeWidth
                    val color = card.strokeColorStateList
                    val outline = TypedValue().let { tv ->
                        card.context.theme.resolveAttribute(android.R.attr.textColorPrimary, tv, true)
                        if (tv.resourceId != 0) card.context.getColorStateList(tv.resourceId).defaultColor else tv.data
                    }
                    val previous = card.onFocusChangeListener
                    card.setOnFocusChangeListener { v, hasFocus ->
                        previous?.onFocusChange(v, hasFocus)
                        if (hasFocus) {
                            card.strokeWidth = (OUTLINE_DP * card.resources.displayMetrics.density).toInt()
                            card.strokeColor = outline
                        } else {
                            card.strokeWidth = width
                            color?.let { card.setStrokeColor(it) }
                        }
                    }
                }

                override fun onChildViewDetachedFromWindow(view: View) {}
            })
        }
    }

    private fun findLists(view: View): List<RecyclerView> {
        if (view is RecyclerView) return listOf(view)
        if (view !is ViewGroup) return emptyList()
        return (0 until view.childCount).flatMap { findLists(view.getChildAt(it)) }
    }
}
