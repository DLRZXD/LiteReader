package com.litereader.app.parser;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class ParsedBook {
    public String title = "";
    public String author = "";
    public String format = "";
    public boolean isPdf = false;
    public int pdfPageCount = 0;
    public final List<Chapter> chapters = new ArrayList<>();
    public final List<TocEntry> tocEntries = new ArrayList<>();

    public static class Chapter {
        public final int index;
        public final String title;
        public volatile String content;

        // On-demand lazy loading descriptor for EPUB & EPUB Omnibus (.epub) archives
        public final File lazyEpubFile;
        public final String lazyZipEntryName;
        public final String lazyCharsetName;
        public final int lazyChunkIndex;
        public final int lazyTotalChunks;

        public Chapter(int index, String title, String content) {
            this.index = index;
            this.title = title != null ? title : ("第 " + (index + 1) + " 章");
            this.content = content != null ? content : "";
            this.lazyEpubFile = null;
            this.lazyZipEntryName = null;
            this.lazyCharsetName = null;
            this.lazyChunkIndex = 0;
            this.lazyTotalChunks = 1;
        }

        public Chapter(int index, String title, File lazyEpubFile, String lazyZipEntryName,
                       String lazyCharsetName, int lazyChunkIndex, int lazyTotalChunks) {
            this.index = index;
            this.title = title != null ? title : ("第 " + (index + 1) + " 章");
            this.content = null;
            this.lazyEpubFile = lazyEpubFile;
            this.lazyZipEntryName = lazyZipEntryName;
            this.lazyCharsetName = lazyCharsetName != null ? lazyCharsetName : "UTF-8";
            this.lazyChunkIndex = Math.max(0, lazyChunkIndex);
            this.lazyTotalChunks = Math.max(1, lazyTotalChunks);
        }

        public boolean isLazyChapter() {
            return lazyEpubFile != null && lazyZipEntryName != null && !lazyZipEntryName.isEmpty();
        }

        public String getContent() {
            String c = this.content;
            if (c != null) {
                return c;
            }
            if (isLazyChapter()) {
                return BookParserEngine.resolveLazyChapterContent(this);
            }
            return "";
        }
    }

    public static class TocEntry {
        public final String title;
        public final int chapterIndex;
        public final int pageNumber;

        public TocEntry(String title, int chapterIndex, int pageNumber) {
            this.title = title != null ? title : "";
            this.chapterIndex = Math.max(0, chapterIndex);
            this.pageNumber = Math.max(1, pageNumber);
        }
    }
}
