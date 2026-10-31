package com.litereader.app.data;

import java.io.File;
import java.util.Locale;

public class Book {
    public long id;
    public String title;
    public String author;
    public String filePath;
    public String folderPath;
    public String folderName;
    public String format; // EPUB, PDF, AZW3, MOBI, TXT, FB2
    public String subExtension; // e.g., .fb2, .fb2.zip, .fbz
    public long fileSize;
    public int currentChapter;
    public int totalChapters;
    public int scrollOffset;
    public float progressPercent; // 0.0 to 100.0
    public long lastReadTime;
    public long addedTime;
    public boolean isFavorite;

    public static String detectFormat(String fileNameOrPath) {
        if (fileNameOrPath == null) return "UNKNOWN";
        String lower = fileNameOrPath.toLowerCase(Locale.ROOT).trim();
        if (lower.endsWith(".fb2.zip") || lower.endsWith(".fbz") || lower.endsWith(".fb2")) {
            return "FB2";
        } else if (lower.endsWith(".epub")) {
            return "EPUB";
        } else if (lower.endsWith(".pdf")) {
            return "PDF";
        } else if (lower.endsWith(".azw3") || lower.endsWith(".kf8")) {
            return "AZW3";
        } else if (lower.endsWith(".mobi") || lower.endsWith(".prc") || lower.endsWith(".azw")) {
            return "MOBI";
        } else if (lower.endsWith(".txt")) {
            return "TXT";
        }
        return "UNKNOWN";
    }

    public static String detectSubExtension(String fileNameOrPath) {
        if (fileNameOrPath == null) return "";
        String lower = fileNameOrPath.toLowerCase(Locale.ROOT).trim();
        if (lower.endsWith(".fb2.zip")) return ".fb2.zip";
        if (lower.endsWith(".fbz")) return ".fbz";
        int dot = lower.lastIndexOf('.');
        return dot >= 0 ? lower.substring(dot) : "";
    }

    public static boolean isSupportedFile(File file) {
        if (file == null || !file.isFile() || file.isHidden()) return false;
        return !"UNKNOWN".equals(detectFormat(file.getName()));
    }

    public String getFormattedSize() {
        if (fileSize < 1024) return fileSize + " B";
        if (fileSize < 1024 * 1024) return String.format(Locale.US, "%.1f KB", fileSize / 1024.0);
        return String.format(Locale.US, "%.2f MB", fileSize / (1024.0 * 1024.0));
    }

    public String getProgressBadge() {
        if (lastReadTime <= 0 && progressPercent <= 0.01f) {
            return "未读";
        }
        return String.format(Locale.US, "%.1f%%", Math.min(100f, Math.max(0f, progressPercent)));
    }
}
