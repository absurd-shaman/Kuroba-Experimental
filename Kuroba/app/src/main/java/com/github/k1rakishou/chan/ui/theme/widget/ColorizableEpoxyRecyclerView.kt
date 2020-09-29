package com.github.k1rakishou.chan.ui.theme.widget

import android.content.Context
import android.util.AttributeSet
import android.widget.EdgeEffect
import androidx.recyclerview.widget.RecyclerView
import com.airbnb.epoxy.EpoxyRecyclerView
import com.github.k1rakishou.chan.Chan
import com.github.k1rakishou.chan.R
import com.github.k1rakishou.chan.ui.theme.ThemeEngine
import javax.inject.Inject

class ColorizableEpoxyRecyclerView  @JvmOverloads constructor(
  context: Context,
  attrs: AttributeSet? = null,
  val defStyleAttr: Int = R.attr.recyclerViewStyle
) : EpoxyRecyclerView(context, attrs, defStyleAttr), IColorizableWidget {

  @Inject
  protected lateinit var themeEngine: ThemeEngine

  init {
    if (!isInEditMode) {
      Chan.inject(this)
    }
  }

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()

    applyColors()
  }

  override fun applyColors() {
    if (isInEditMode) {
      return
    }

    edgeEffectFactory = object : RecyclerView.EdgeEffectFactory() {
      override fun createEdgeEffect(view: RecyclerView, direction: Int): EdgeEffect {
        return EdgeEffect(view.context).apply { color = themeEngine.chanTheme.accentColor }
      }
    }
  }

}