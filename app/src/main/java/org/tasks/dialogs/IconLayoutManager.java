package org.tasks.dialogs;

import android.content.Context;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import org.tasks.R;

class IconLayoutManager extends GridLayoutManager {

  private int iconSize;

  IconLayoutManager(Context context) {
    super(context, DEFAULT_SPAN_COUNT, RecyclerView.VERTICAL, false);
    this.iconSize = (int) context.getResources().getDimension(R.dimen.icon_picker_size);
  }

  @Override
  public void onLayoutChildren(RecyclerView.Recycler recycler, RecyclerView.State state) {
    int width = getWidth();
    if (getSpanCount() == DEFAULT_SPAN_COUNT && iconSize > 0 && width > 0 && getHeight() > 0) {
      setSpanCount(Math.max(1, (width - getPaddingRight() - getPaddingLeft()) / iconSize));
    }
    super.onLayoutChildren(recycler, state);
  }
}