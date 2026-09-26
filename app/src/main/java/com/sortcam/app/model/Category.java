package com.sortcam.app.model;

public class Category {
    public long id;
    public String name;
    public String emoji;
    public boolean isDefault;
    public int sortOrder;

    public Category(long id, String name, String emoji, boolean isDefault, int sortOrder) {
        this.id = id;
        this.name = name;
        this.emoji = emoji;
        this.isDefault = isDefault;
        this.sortOrder = sortOrder;
    }
}
