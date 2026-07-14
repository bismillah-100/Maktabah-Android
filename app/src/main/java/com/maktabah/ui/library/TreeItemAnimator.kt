package com.maktabah.ui.library

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.view.View
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.RecyclerView

class TreeItemAnimator : DefaultItemAnimator() {

    private val pendingAdds = mutableListOf<RecyclerView.ViewHolder>()
    private val pendingRemoves = mutableListOf<RecyclerView.ViewHolder>()
    var collapsingParentY: Float? = null

    init {
        val duration = 250L
        addDuration = duration
        removeDuration = duration
        moveDuration = duration
        changeDuration = duration
        supportsChangeAnimations = false
    }

    // Menjaga agar hanya ada satu invalidate terjadwal per RecyclerView dalam satu waktu,
    // supaya updateListener yang terpanggil puluhan kali per detik (per frame animasi,
    // per item yang beranimasi) tidak membanjiri message queue dengan Runnable duplikat.
    private val pendingInvalidate = mutableSetOf<RecyclerView>()

    private fun invalidateDecoration(view: View) {
        val rv = view.parent as? RecyclerView ?: return
        // invalidateItemDecorations() melempar IllegalStateException kalau dipanggil
        // saat RecyclerView sedang di tengah layout/scroll pass (assertNotInLayoutOrScroll).
        // Callback animator ini (onAnimationCancel khususnya) bisa terpicu DARI DALAM
        // dispatchLayout milik RecyclerView sendiri -- misalnya saat toggle expand/collapse
        // yang cepat membuat DefaultItemAnimator meng-cancel animasi item lain di tengah
        // layout pass. Maka invalidate harus selalu di-defer ke frame berikutnya lewat post{},
        // tidak pernah dipanggil sinkron dari sini.
        if (!pendingInvalidate.add(rv)) return
        rv.post {
            pendingInvalidate.remove(rv)
            if (rv.isAttachedToWindow) {
                rv.invalidateItemDecorations()
                rv.invalidate()
            }
        }
    }

    private fun setClipTop(view: View, top: Int) {
        val topToUse = top.coerceAtLeast(0)
        view.clipBounds = android.graphics.Rect(0, topToUse, view.width, view.height.coerceAtLeast(topToUse))
    }

    private fun groupHeights(holders: List<RecyclerView.ViewHolder>): Map<Float, Int> {
        val result = mutableMapOf<Float, Int>()
        for (holder in holders) {
            val parentBottom = holder.itemView.tag as? Float ?: continue
            val height = (holder.itemView.bottom - parentBottom).toInt().coerceAtLeast(0)
            val current = result[parentBottom] ?: 0
            if (height > current) {
                result[parentBottom] = height
            }
        }
        return result
    }

    override fun animateAdd(holder: RecyclerView.ViewHolder): Boolean {
        if (holder.itemView.tag != null) {
            if (holder.itemView.translationY == 0f) {
                holder.itemView.translationY = -10000f // hide only if it's fresh
            }
            pendingAdds.add(holder)
            return true
        }
        return super.animateAdd(holder)
    }

    override fun animateRemove(holder: RecyclerView.ViewHolder): Boolean {
        if (holder.itemView.tag != null) {
            pendingRemoves.add(holder)
            return true
        }
        val collapseY = collapsingParentY
        if (collapseY != null) {
            holder.itemView.tag = collapseY
            pendingRemoves.add(holder)
            return true
        }
        return super.animateRemove(holder)
    }

    override fun runPendingAnimations() {
        val hasAdds = pendingAdds.isNotEmpty()
        val hasRemoves = pendingRemoves.isNotEmpty()

        if (hasAdds || hasRemoves) {
            val addHeights = groupHeights(pendingAdds)
            val removeHeights = groupHeights(pendingRemoves)

            for (holder in pendingAdds) {
                val view = holder.itemView
                val parentBottom = view.tag as? Float ?: 0f
                val isFresh = view.translationY <= -9999f
                val trueHeight = addHeights[parentBottom] ?: (view.bottom - parentBottom).toInt().coerceAtLeast(0)
                val startTranslationY = if (trueHeight > 0) -trueHeight.toFloat() else 0f
                if (isFresh) {
                    view.translationY = startTranslationY
                }

                val targetY = view.top

                val cTop = (parentBottom - (targetY + (if (isFresh) startTranslationY else view.translationY))).toInt()
                setClipTop(view, cTop)
                invalidateDecoration(view)

                val updateListener = android.animation.ValueAnimator.AnimatorUpdateListener {
                    val currentTransY = view.translationY
                    val currentClipTop = (parentBottom - (targetY + currentTransY)).toInt()
                    setClipTop(view, currentClipTop)
                    invalidateDecoration(view)
                }

                view.animate()
                    .translationY(0f)
                    .setDuration(addDuration)
                    .setUpdateListener(updateListener)
                    .setListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationStart(animator: Animator) {
                            dispatchAddStarting(holder)
                        }
                        override fun onAnimationCancel(animator: Animator) {
                            view.translationY = 0f
                            view.clipBounds = null
                            invalidateDecoration(view)
                        }
                        override fun onAnimationEnd(animator: Animator) {
                            view.animate().setListener(null)
                            view.animate().setUpdateListener(null)
                            view.clipBounds = null
                            invalidateDecoration(view)
                            dispatchAddFinished(holder)
                        }
                    }).start()
            }
            pendingAdds.clear()

            for (holder in pendingRemoves) {
                val view = holder.itemView
                val parentBottom = view.tag as? Float ?: 0f
                val trueHeight = removeHeights[parentBottom] ?: (view.bottom - parentBottom).toInt().coerceAtLeast(0)
                val targetTranslationY = if (trueHeight > 0) -trueHeight.toFloat() else 0f

                val updateListener = android.animation.ValueAnimator.AnimatorUpdateListener {
                    val currentTransY = view.translationY
                    val currentClipTop = (parentBottom - (view.top + currentTransY)).toInt()
                    setClipTop(view, currentClipTop)
                    invalidateDecoration(view)
                }

                view.animate()
                    .translationY(targetTranslationY)
                    .setDuration(removeDuration)
                    .setUpdateListener(updateListener)
                    .setListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationStart(animator: Animator) {
                            dispatchRemoveStarting(holder)
                        }
                        override fun onAnimationCancel(animator: Animator) {
                            view.translationY = 0f
                            view.clipBounds = null
                            invalidateDecoration(view)
                        }
                        override fun onAnimationEnd(animator: Animator) {
                            view.animate().setListener(null)
                            view.animate().setUpdateListener(null)
                            view.translationY = 0f
                            view.clipBounds = null
                            invalidateDecoration(view)
                            dispatchRemoveFinished(holder)
                        }
                    }).start()
            }
            pendingRemoves.clear()
        }
        collapsingParentY = null
        super.runPendingAnimations()
    }
}
