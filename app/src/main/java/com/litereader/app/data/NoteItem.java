package com.litereader.app.data;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class NoteItem {
    public long id;
    public long bookId;
    public String bookTitle;
    public String bookPath;
    public int chapterIndex;
    public String chapterTitle;
    public String excerpt;
    public String content;
    public String txtFilePath;
    public long updatedAt;

    public String getFormattedTime() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());
        return sdf.format(new Date(updatedAt));
    }
}
