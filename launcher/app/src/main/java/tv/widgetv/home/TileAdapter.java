package tv.widgetv.home;

import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/** Banner tiles for both the dock and the grid. Focus is drawn by MainActivity, not by the views. */
final class TileAdapter extends RecyclerView.Adapter<TileAdapter.Holder> {
    static final float FOCUS_SCALE = 1.08f;

    final List<AppEntry> items = new ArrayList<>();
    int focused = -1;
    private final int cardWidth, cardHeight;

    /** cardWidth -1 fills the column; otherwise the card is that wide, centred in its column. */
    TileAdapter(int cardWidth, int cardHeight) {
        this.cardWidth = cardWidth;
        this.cardHeight = cardHeight;
    }

    void setItems(List<AppEntry> apps) {
        items.clear();
        items.addAll(apps);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_tile, parent, false);
        Holder h = new Holder(v);
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) h.card.getLayoutParams();
        lp.height = cardHeight;
        if (cardWidth > 0) {
            lp.width = cardWidth;
            lp.gravity = Gravity.CENTER_HORIZONTAL;
        }
        h.card.setClipToOutline(true);
        boolean ar = "ar".equals(java.util.Locale.getDefault().getLanguage());
        h.name.setTypeface(Fonts.get(parent.getContext(), ar ? R.font.noto_naskh : R.font.alexandria));
        return h;
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        AppEntry e = items.get(position);
        h.art.setImageBitmap(e.art);
        h.name.setText(e.label);
        boolean on = position == focused;
        h.card.animate().cancel();
        h.name.animate().cancel();
        h.card.setScaleX(on ? FOCUS_SCALE : 1f);
        h.card.setScaleY(on ? FOCUS_SCALE : 1f);
        h.name.setAlpha(on ? 1f : 0f);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static void setLook(View item, boolean on) {
        float s = on ? FOCUS_SCALE : 1f;
        item.findViewById(R.id.card).animate().scaleX(s).scaleY(s).setDuration(220).start();
        item.findViewById(R.id.name).animate().alpha(on ? 1f : 0f).setDuration(220).start();
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final View card;
        final ImageView art;
        final TextView name;

        Holder(View v) {
            super(v);
            card = v.findViewById(R.id.card);
            art = v.findViewById(R.id.art);
            name = v.findViewById(R.id.name);
        }
    }
}
