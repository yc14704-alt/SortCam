package com.sortcam.app.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.sortcam.app.R;
import com.sortcam.app.model.Category;

import java.util.ArrayList;
import java.util.List;

public class CategoryAdapter extends RecyclerView.Adapter<CategoryAdapter.Holder> {
    public interface Listener {
        void onCategorySelected(Category category);
    }

    private final List<Category> items = new ArrayList<>();
    private final Listener listener;
    private long selectedId = -1L;

    public CategoryAdapter(Listener listener) {
        this.listener = listener;
    }

    public void submit(List<Category> categories, long selectedId) {
        items.clear();
        items.addAll(categories);
        this.selectedId = selectedId;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_category, parent, false);
        return new Holder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        Category item = items.get(position);
        h.emoji.setText(item.emoji);
        h.name.setText(item.name);
        h.itemView.setSelected(item.id == selectedId);
        h.itemView.setContentDescription(item.name + " 분류");
        h.itemView.setOnClickListener(v -> {
            long old = selectedId;
            selectedId = item.id;
            if (old != selectedId) notifyDataSetChanged();
            listener.onCategorySelected(item);
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class Holder extends RecyclerView.ViewHolder {
        final TextView emoji;
        final TextView name;

        Holder(@NonNull View itemView) {
            super(itemView);
            emoji = itemView.findViewById(R.id.txtCategoryEmoji);
            name = itemView.findViewById(R.id.txtCategoryName);
        }
    }
}
