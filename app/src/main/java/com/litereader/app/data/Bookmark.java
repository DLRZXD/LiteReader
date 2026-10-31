package com.litereader.app.data;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class Bookmark {
    public long id;
    public long bookId;
    public String bookTitle;
    public String bookPath;
    public int chapterIndex;
    public String chapterTitle;
    public int scrollOffset;
    public float progressPercent;
    public String snippet;
    public long createdAt;

    public String getFormattedTime() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());
        return sdf.format(new Date(createdAt));
    }
}
