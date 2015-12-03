package com.kickstarter.ui.viewholders;

import android.content.Context;
import android.support.annotation.NonNull;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.kickstarter.R;
import com.kickstarter.services.DiscoveryParams;

import butterknife.Bind;
import butterknife.ButterKnife;

public final class SearchTermViewHolder extends KSViewHolder {
  private DiscoveryParams params;

  @Bind(R.id.search_term_text_view) TextView termTextView;
  @Bind(R.id.search_term_view) LinearLayout layout;

  public SearchTermViewHolder(@NonNull final View view) {
    super(view);
    ButterKnife.bind(this, view);
  }

  public void onBind(@NonNull final Object datum) {
    params = (DiscoveryParams) datum;

    final Context context = view.getContext();

    if (params.sort() == DiscoveryParams.Sort.POPULAR) {
      termTextView.setText(context.getString(R.string.___Most_Popular));
    } else {
      // NOTE: This path isn't currently executed, keeping it in case we modify the design to show this view
      // in more situations
      if (params.term() != null) {
        termTextView.setText(params.term());
      } else {
        termTextView.setText("");
      }
    }
  }
}

