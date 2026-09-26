package com.sortcam.app.ui;

import android.app.Activity;
import android.content.DialogInterface;
import android.graphics.Typeface;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.sortcam.app.R;
import com.sortcam.app.model.Category;

import java.util.List;

public class CategoryManagerDialog {
    public interface Listener {
        List<Category> loadCategories();
        boolean addCategory(String name, String emoji);
        boolean editCategory(Category category, String name, String emoji);
        boolean deleteCategory(Category category);
    }

    private final Activity activity;
    private final Listener listener;
    private LinearLayout rows;

    public CategoryManagerDialog(Activity activity, Listener listener) {
        this.activity = activity;
        this.listener = listener;
    }

    public void show() {
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, 0);

        TextView help = new TextView(activity);
        help.setText("기본 분류도 이름과 아이콘을 바꿀 수 있습니다. 직접 만든 분류는 삭제할 수 있습니다.");
        help.setTextColor(activity.getColor(R.color.sc_muted));
        help.setTextSize(12);
        root.addView(help, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        ScrollView scroll = new ScrollView(activity);
        rows = new LinearLayout(activity);
        rows.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(rows);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(360));
        scrollParams.topMargin = dp(10);
        root.addView(scroll, scrollParams);

        Button add = new Button(activity);
        add.setText("＋ 새 분류 추가");
        add.setAllCaps(false);
        add.setOnClickListener(v -> showEditor(null));
        LinearLayout.LayoutParams addParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52));
        addParams.topMargin = dp(10);
        root.addView(add, addParams);

        rebuildRows();

        new AlertDialog.Builder(activity)
                .setTitle("분류 관리")
                .setView(root)
                .setPositiveButton("닫기", null)
                .show();
    }

    private void rebuildRows() {
        if (rows == null) return;
        rows.removeAllViews();
        List<Category> categories = listener.loadCategories();
        for (Category category : categories) {
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(dp(10), dp(8), dp(6), dp(8));
            row.setBackgroundResource(R.drawable.bg_card);

            TextView label = new TextView(activity);
            label.setText(category.emoji + "  " + category.name + (category.isDefault ? "  · 기본" : ""));
            label.setTextColor(activity.getColor(R.color.sc_text));
            label.setTextSize(15);
            label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            row.addView(label, new LinearLayout.LayoutParams(0, dp(48), 1f));

            Button edit = new Button(activity);
            edit.setText("수정");
            edit.setAllCaps(false);
            edit.setOnClickListener(v -> showEditor(category));
            row.addView(edit, new LinearLayout.LayoutParams(dp(72), dp(48)));

            if (!category.isDefault) {
                Button delete = new Button(activity);
                delete.setText("삭제");
                delete.setAllCaps(false);
                delete.setOnClickListener(v -> confirmDelete(category));
                LinearLayout.LayoutParams deleteParams = new LinearLayout.LayoutParams(dp(72), dp(48));
                deleteParams.leftMargin = dp(4);
                row.addView(delete, deleteParams);
            }

            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            rowParams.bottomMargin = dp(7);
            rows.addView(row, rowParams);
        }
    }

    private void showEditor(Category category) {
        View view = LayoutInflater.from(activity).inflate(R.layout.dialog_category_edit, null, false);
        EditText emoji = view.findViewById(R.id.editCategoryEmoji);
        EditText name = view.findViewById(R.id.editCategoryName);
        if (category == null) {
            emoji.setText("📁");
        } else {
            emoji.setText(category.emoji);
            name.setText(category.name);
        }

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(category == null ? "새 분류" : "분류 수정")
                .setView(view)
                .setNegativeButton("취소", null)
                .setPositiveButton("저장", null)
                .create();

        dialog.setOnShowListener(d -> dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v -> {
            String newName = name.getText().toString().trim();
            String newEmoji = emoji.getText().toString().trim();
            if (newName.isEmpty()) {
                name.setError("분류 이름을 입력하세요.");
                return;
            }
            if (newEmoji.isEmpty()) newEmoji = "📁";
            boolean ok = category == null
                    ? listener.addCategory(newName, newEmoji)
                    : listener.editCategory(category, newName, newEmoji);
            if (ok) {
                dialog.dismiss();
                rebuildRows();
            }
        }));
        dialog.show();
    }

    private void confirmDelete(Category category) {
        new AlertDialog.Builder(activity)
                .setTitle("'" + category.name + "' 분류 삭제")
                .setMessage("이 분류의 사진은 첫 번째 기본 분류로 이동하고 사진 자체는 삭제하지 않습니다.")
                .setNegativeButton("취소", null)
                .setPositiveButton("삭제", (d, which) -> {
                    if (listener.deleteCategory(category)) {
                        Toast.makeText(activity, "분류를 삭제했습니다.", Toast.LENGTH_SHORT).show();
                        rebuildRows();
                    }
                })
                .show();
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
