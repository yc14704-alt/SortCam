package com.sortcam.app.ui;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Size;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.sortcam.app.R;
import com.sortcam.app.model.Category;
import com.sortcam.app.model.PhotoRecord;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PhotoAdapter extends RecyclerView.Adapter<PhotoAdapter.Holder> {
    public interface Listener {
        void onOpen(PhotoRecord media);
        void onMore(PhotoRecord media);
    }

    private final Context context;
    private final Listener listener;
    private final List<PhotoRecord> mediaItems = new ArrayList<>();
    private final Map<Long, Category> categories = new HashMap<>();
    private final ExecutorService thumbnailExecutor = Executors.newFixedThreadPool(3);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public PhotoAdapter(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
    }

    public void submit(List<PhotoRecord> newMedia, List<Category> newCategories) {
        mediaItems.clear();
        mediaItems.addAll(newMedia);
        categories.clear();
        for (Category c : newCategories) categories.put(c.id, c);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_photo, parent, false);
        return new Holder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        PhotoRecord p = mediaItems.get(position);
        Category c = categories.get(p.categoryId);
        h.category.setText(c == null ? "📁 미분류" : c.emoji + " " + c.name);
        h.typeBadge.setText(p.isVideo() ? "▶ 동영상" : "📷 사진");

        String tags = p.tags == null ? "" : p.tags.trim();
        if (p.isVideo() && p.durationMs > 0) {
            String duration = formatDuration(p.durationMs);
            h.meta.setText((tags.isEmpty() ? "#동영상" : tags) + " · " + duration);
        } else {
            h.meta.setText(tags.isEmpty() ? (p.isVideo() ? "#동영상" : "#사진") : tags);
        }

        Uri uri = Uri.parse(p.uri);
        h.image.setImageResource(R.drawable.ic_app_icon);
        h.image.setTag(p.uri);
        thumbnailExecutor.execute(() -> {
            try {
                android.graphics.Bitmap bitmap = context.getContentResolver()
                        .loadThumbnail(uri, new Size(420, 420), null);
                mainHandler.post(() -> {
                    if (p.uri.equals(h.image.getTag())) h.image.setImageBitmap(bitmap);
                });
            } catch (Exception ignored) {
                // Keep the placeholder if the media thumbnail cannot be loaded.
            }
        });

        h.itemView.setOnClickListener(v -> listener.onOpen(p));
        h.more.setOnClickListener(v -> listener.onMore(p));
    }

    private String formatDuration(long durationMs) {
        long totalSeconds = Math.max(0, durationMs / 1000L);
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        return String.format(Locale.KOREA, "%d:%02d", minutes, seconds);
    }

    @Override
    public int getItemCount() {
        return mediaItems.size();
    }

    public void close() {
        thumbnailExecutor.shutdownNow();
    }

    static class Holder extends RecyclerView.ViewHolder {
        final ImageView image;
        final TextView category;
        final TextView meta;
        final TextView typeBadge;
        final TextView more;

        Holder(@NonNull View itemView) {
            super(itemView);
            image = itemView.findViewById(R.id.imgThumb);
            category = itemView.findViewById(R.id.txtPhotoCategory);
            meta = itemView.findViewById(R.id.txtPhotoMeta);
            typeBadge = itemView.findViewById(R.id.txtMediaTypeBadge);
            more = itemView.findViewById(R.id.btnPhotoMore);
        }
    }
}
