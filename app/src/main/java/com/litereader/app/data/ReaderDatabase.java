package com.litereader.app.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class ReaderDatabase extends SQLiteOpenHelper {
    private static final String DB_NAME = "litereader.db";
    private static final int DB_VERSION = 1;
    private static ReaderDatabase instance;
    private final Context appContext;

    public static synchronized ReaderDatabase getInstance(Context context) {
        if (instance == null) {
            instance = new ReaderDatabase(context.getApplicationContext());
        }
        return instance;
    }

    private ReaderDatabase(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
        this.appContext = context;
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS books (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "title TEXT NOT NULL," +
                "author TEXT," +
                "file_path TEXT UNIQUE NOT NULL," +
                "folder_path TEXT," +
                "folder_name TEXT," +
                "format TEXT," +
                "sub_extension TEXT," +
                "file_size INTEGER DEFAULT 0," +
                "current_chapter INTEGER DEFAULT 0," +
                "total_chapters INTEGER DEFAULT 1," +
                "scroll_offset INTEGER DEFAULT 0," +
                "progress_percent REAL DEFAULT 0," +
                "last_read_time INTEGER DEFAULT 0," +
                "added_time INTEGER DEFAULT 0," +
                "is_favorite INTEGER DEFAULT 0" +
                ")");

        db.execSQL("CREATE TABLE IF NOT EXISTS bookmarks (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "book_id INTEGER NOT NULL," +
                "book_title TEXT," +
                "book_path TEXT," +
                "chapter_index INTEGER DEFAULT 0," +
                "chapter_title TEXT," +
                "scroll_offset INTEGER DEFAULT 0," +
                "progress_percent REAL DEFAULT 0," +
                "snippet TEXT," +
                "created_at INTEGER DEFAULT 0" +
                ")");

        db.execSQL("CREATE TABLE IF NOT EXISTS notes (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "book_id INTEGER NOT NULL," +
                "book_title TEXT," +
                "book_path TEXT," +
                "chapter_index INTEGER DEFAULT 0," +
                "chapter_title TEXT," +
                "excerpt TEXT," +
                "content TEXT NOT NULL," +
                "txt_file_path TEXT," +
                "updated_at INTEGER DEFAULT 0" +
                ")");

        db.execSQL("CREATE TABLE IF NOT EXISTS deleted_tombstones (" +
                "tombstone_key TEXT PRIMARY KEY," +
                "deleted_at INTEGER DEFAULT 0" +
                ")");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        onCreate(db);
    }

    private void ensureTombstoneTable(SQLiteDatabase db) {
        try {
            db.execSQL("CREATE TABLE IF NOT EXISTS deleted_tombstones (" +
                    "tombstone_key TEXT PRIMARY KEY," +
                    "deleted_at INTEGER DEFAULT 0" +
                    ")");
        } catch (Exception ignored) {
        }
    }

    public synchronized boolean isBookTombstoned(String title, String format, String rawSourceName) {
        SQLiteDatabase db = getReadableDatabase();
        ensureTombstoneTable(db);
        String key1 = "title:" + (title != null ? title.trim().toLowerCase(Locale.ROOT) : "")
                + "|" + (format != null ? format.trim().toUpperCase(Locale.ROOT) : "");
        try (Cursor c = db.rawQuery("SELECT 1 FROM deleted_tombstones WHERE tombstone_key = ? LIMIT 1", new String[]{key1})) {
            if (c.moveToFirst()) return true;
        } catch (Exception ignored) {
        }
        if (rawSourceName != null && !rawSourceName.isEmpty()) {
            String key2 = "file:" + rawSourceName.trim().toLowerCase(Locale.ROOT);
            try (Cursor c = db.rawQuery("SELECT 1 FROM deleted_tombstones WHERE tombstone_key = ? LIMIT 1", new String[]{key2})) {
                if (c.moveToFirst()) return true;
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    private synchronized void addBookTombstone(String title, String format, String rawSourceName) {
        SQLiteDatabase db = getWritableDatabase();
        ensureTombstoneTable(db);
        long now = System.currentTimeMillis();
        if (title != null && !title.trim().isEmpty()) {
            ContentValues cv = new ContentValues();
            cv.put("tombstone_key", "title:" + title.trim().toLowerCase(Locale.ROOT)
                    + "|" + (format != null ? format.trim().toUpperCase(Locale.ROOT) : ""));
            cv.put("deleted_at", now);
            db.insertWithOnConflict("deleted_tombstones", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
        }
        if (rawSourceName != null && !rawSourceName.trim().isEmpty()) {
            ContentValues cv2 = new ContentValues();
            cv2.put("tombstone_key", "file:" + rawSourceName.trim().toLowerCase(Locale.ROOT));
            cv2.put("deleted_at", now);
            db.insertWithOnConflict("deleted_tombstones", null, cv2, SQLiteDatabase.CONFLICT_REPLACE);
        }
    }

    /**
     * Imports a file into the proprietary encrypted vault (`.litereader_vault/v_<uuid>.lrv`),
     * optionally deletes the original external source file so no other app can read it,
     * and records the book metadata in the private SQLite database.
     */
    public synchronized Book importAndEncryptFileToVault(File sourceFile, String customTitle, String customAuthor,
                                                         String customFolderLabel, boolean deleteExternalSource) {
        if (sourceFile == null || !sourceFile.exists()) return null;

        // If already registered by exact path, return existing
        Book existingByPath = getBookByPath(sourceFile.getAbsolutePath());
        if (existingByPath != null) {
            return existingByPath;
        }

        String originalName = sourceFile.getName();
        String format = com.litereader.app.parser.BookParserEngine.detectActualFormat(sourceFile);
        if ("UNKNOWN".equals(format)) return null;

        String subExt = Book.detectSubExtension(originalName);
        if (subExt.isEmpty()) {
            subExt = "." + format.toLowerCase(Locale.ROOT);
        }
        String rawTitle = originalName;
        if (!subExt.isEmpty() && rawTitle.toLowerCase(Locale.ROOT).endsWith(subExt)) {
            rawTitle = rawTitle.substring(0, rawTitle.length() - subExt.length());
        }
        String finalTitle = (customTitle != null && !customTitle.trim().isEmpty()) ? customTitle.trim() : rawTitle;

        // If this book was previously deleted by the user, do NOT re-import it during background folder scans!
        if (isBookTombstoned(finalTitle, format, rawTitle) || isBookTombstoned(rawTitle, format, originalName)) {
            if (deleteExternalSource) {
                BookVaultCrypto.secureDeleteFile(sourceFile);
            }
            return null;
        }

        File parent = sourceFile.getParentFile();
        String folderPath = (customFolderLabel != null && !customFolderLabel.isEmpty())
                ? customFolderLabel
                : (parent != null ? parent.getAbsolutePath() : "加密保险箱");
        String folderName = (customFolderLabel != null && !customFolderLabel.isEmpty())
                ? customFolderLabel
                : (parent != null ? parent.getName() : "加密保险箱");

        // Avoid re-importing if a book with the same title & format already exists in the vault
        Book existingDup = getBookByTitleAndFormat(finalTitle, format);
        if (existingDup != null && new File(existingDup.filePath).exists()) {
            if (deleteExternalSource && !sourceFile.getAbsolutePath().equals(existingDup.filePath)) {
                BookVaultCrypto.secureDeleteFile(sourceFile);
            }
            return existingDup;
        }

        long origSize = sourceFile.length();
        File encryptedVaultFile;
        try {
            encryptedVaultFile = BookVaultCrypto.encryptFileToVault(appContext, sourceFile, deleteExternalSource);
        } catch (Exception e) {
            return null;
        }

        return insertVaultBookRecord(
                encryptedVaultFile, finalTitle,
                (customAuthor != null && !customAuthor.trim().isEmpty()) ? customAuthor.trim() : "本地加密藏书",
                folderPath, folderName, format, subExt, origSize
        );
    }

    /**
     * Encrypts an InputStream (e.g. from SAF Document picker or Z-Library stream) directly into
     * `.litereader_vault/v_<uuid>.lrv` and records it in SQLite.
     */
    public synchronized Book importStreamToEncryptedVault(InputStream in, String originalFileName,
                                                          String customTitle, String customAuthor, String folderLabel) throws Exception {
        String format = Book.detectFormat(originalFileName);
        if ("UNKNOWN".equals(format)) {
            format = "EPUB";
        }
        String subExt = Book.detectSubExtension(originalFileName);
        if (subExt.isEmpty()) {
            subExt = "." + format.toLowerCase(Locale.ROOT);
        }
        String rawTitle = originalFileName != null ? originalFileName : "未命名书籍";
        if (!subExt.isEmpty() && rawTitle.toLowerCase(Locale.ROOT).endsWith(subExt)) {
            rawTitle = rawTitle.substring(0, rawTitle.length() - subExt.length());
        }
        String finalTitle = (customTitle != null && !customTitle.trim().isEmpty()) ? customTitle.trim() : rawTitle;

        File vaultFile = BookVaultCrypto.encryptStreamToVault(appContext, in);
        long payloadSize = Math.max(0L, vaultFile.length() - 24L);
        String fLabel = (folderLabel != null && !folderLabel.isEmpty()) ? folderLabel : "加密私密书库";

        return insertVaultBookRecord(
                vaultFile, finalTitle,
                (customAuthor != null && !customAuthor.trim().isEmpty()) ? customAuthor.trim() : "加密藏书",
                fLabel, fLabel, format, subExt, payloadSize
        );
    }

    public synchronized Book upsertBookFromFile(File file, String customTitle, String customAuthor) {
        return importAndEncryptFileToVault(file, customTitle, customAuthor, null, true);
    }

    private Book insertVaultBookRecord(File vaultFile, String title, String author,
                                       String folderPath, String folderName,
                                       String format, String subExt, long fileSize) {
        ContentValues cv = new ContentValues();
        cv.put("title", title);
        cv.put("author", author);
        cv.put("file_path", vaultFile.getAbsolutePath());
        cv.put("folder_path", folderPath);
        cv.put("folder_name", folderName);
        cv.put("format", format);
        cv.put("sub_extension", subExt);
        cv.put("file_size", fileSize);
        cv.put("current_chapter", 0);
        cv.put("total_chapters", 1);
        cv.put("scroll_offset", 0);
        cv.put("progress_percent", 0f);
        cv.put("last_read_time", 0L);
        cv.put("added_time", System.currentTimeMillis());
        cv.put("is_favorite", 0);

        SQLiteDatabase db = getWritableDatabase();
        long id = db.insertWithOnConflict("books", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
        return getBookById(id);
    }

    public void updateBookMetadata(long bookId, String title, String author, int totalChapters) {
        ContentValues cv = new ContentValues();
        if (title != null && !title.trim().isEmpty()) {
            String t = title.trim();
            String low = t.toLowerCase(Locale.ROOT);
            // Never overwrite a valid book title with a synthetic session_v_ / v_ / zlib_book_ temp name
            if (!low.startsWith("session_v_") && !low.startsWith("v_") && !low.startsWith("zlib_book_")
                    && !low.endsWith(".lrv")) {
                cv.put("title", t);
            }
        }
        if (author != null && !author.trim().isEmpty()) cv.put("author", author.trim());
        if (totalChapters > 0) cv.put("total_chapters", totalChapters);
        if (cv.size() > 0) {
            getWritableDatabase().update("books", cv, "id = ?", new String[]{String.valueOf(bookId)});
        }
    }

    public void forceUpdateBookTitle(long bookId, String newTitle) {
        if (newTitle == null || newTitle.trim().isEmpty()) return;
        ContentValues cv = new ContentValues();
        cv.put("title", newTitle.trim());
        getWritableDatabase().update("books", cv, "id = ?", new String[]{String.valueOf(bookId)});
    }

    public void saveReadingProgress(long bookId, int chapterIndex, int totalChapters, int scrollOffset, float progressPercent) {
        ContentValues cv = new ContentValues();
        cv.put("current_chapter", Math.max(0, chapterIndex));
        cv.put("total_chapters", Math.max(1, totalChapters));
        cv.put("scroll_offset", Math.max(0, scrollOffset));
        cv.put("progress_percent", Math.max(0f, Math.min(100f, progressPercent)));
        cv.put("last_read_time", System.currentTimeMillis());
        getWritableDatabase().update("books", cv, "id = ?", new String[]{String.valueOf(bookId)});
    }

    public Book getBookById(long id) {
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor c = db.rawQuery("SELECT * FROM books WHERE id = ? LIMIT 1", new String[]{String.valueOf(id)})) {
            if (c.moveToFirst()) return readBookCursor(c);
        }
        return null;
    }

    public Book getBookByPath(String path) {
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor c = db.rawQuery("SELECT * FROM books WHERE file_path = ? LIMIT 1", new String[]{path})) {
            if (c.moveToFirst()) return readBookCursor(c);
        }
        return null;
    }

    public Book getBookByTitleAndFormat(String title, String format) {
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor c = db.rawQuery("SELECT * FROM books WHERE title = ? AND format = ? LIMIT 1",
                new String[]{title, format})) {
            if (c.moveToFirst()) return readBookCursor(c);
        }
        return null;
    }

    public List<Book> queryBooks(String searchQuery, String formatFilter, String folderFilter, boolean onlyRecent) {
        List<Book> list = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT * FROM books WHERE 1=1");
        List<String> args = new ArrayList<>();

        if (onlyRecent) {
            sql.append(" AND last_read_time > 0");
        }
        if (formatFilter != null && !formatFilter.isEmpty() && !"ALL".equalsIgnoreCase(formatFilter)) {
            sql.append(" AND format = ?");
            args.add(formatFilter.toUpperCase(Locale.ROOT));
        }
        if (folderFilter != null && !folderFilter.isEmpty() && !"ALL".equalsIgnoreCase(folderFilter)) {
            sql.append(" AND folder_path = ?");
            args.add(folderFilter);
        }
        if (searchQuery != null && !searchQuery.trim().isEmpty()) {
            String like = "%" + searchQuery.trim() + "%";
            sql.append(" AND (title LIKE ? OR author LIKE ? OR folder_name LIKE ? OR sub_extension LIKE ?)");
            args.add(like);
            args.add(like);
            args.add(like);
            args.add(like);
        }

        if (onlyRecent) {
            sql.append(" ORDER BY last_read_time DESC");
        } else {
            sql.append(" ORDER BY CASE WHEN last_read_time > 0 THEN 0 ELSE 1 END, last_read_time DESC, added_time DESC");
        }

        SQLiteDatabase db = getReadableDatabase();
        try (Cursor c = db.rawQuery(sql.toString(), args.toArray(new String[0]))) {
            while (c.moveToNext()) {
                Book b = readBookCursor(c);
                if (new File(b.filePath).exists()) {
                    list.add(b);
                }
            }
        }
        return list;
    }

    public Map<String, Integer> getFolderCounts(String formatFilter) {
        Map<String, Integer> map = new LinkedHashMap<>();
        String sql = "SELECT folder_path, COUNT(*) as cnt FROM books";
        String[] args = null;
        if (formatFilter != null && !formatFilter.isEmpty() && !"ALL".equalsIgnoreCase(formatFilter)) {
            sql += " WHERE format = ?";
            args = new String[]{formatFilter.toUpperCase(Locale.ROOT)};
        }
        sql += " GROUP BY folder_path ORDER BY cnt DESC, folder_path ASC";
        try (Cursor c = getReadableDatabase().rawQuery(sql, args)) {
            while (c.moveToNext()) {
                String folder = c.getString(0);
                int cnt = c.getInt(1);
                if (folder != null) {
                    map.put(folder, cnt);
                }
            }
        }
        return map;
    }

    /**
     * Permanently deletes the book record (and any duplicate records with the same title/format),
     * records a permanent deletion tombstone so background scans never re-import it,
     * and wipes its encrypted `.lrv` file and cached cover image.
     */
    public synchronized void deleteBook(long bookId) {
        SQLiteDatabase db = getWritableDatabase();
        ensureTombstoneTable(db);
        Book b = getBookById(bookId);
        if (b != null) {
            addBookTombstone(b.title, b.format, b.filePath != null ? new File(b.filePath).getName() : null);
            if (b.filePath != null) {
                File f = new File(b.filePath);
                if (f.exists()) {
                    BookVaultCrypto.secureDeleteFile(f);
                }
            }
            try {
                File cov = com.litereader.app.parser.BookCoverExtractor.getCoverCacheFile(appContext, b.id);
                if (cov.exists()) cov.delete();
            } catch (Exception ignored) {
            }

            // Also remove any duplicate rows with the same title & format so the book never lingers
            if (b.title != null && b.format != null) {
                try (Cursor c = db.rawQuery("SELECT id, file_path FROM books WHERE title = ? AND format = ?",
                        new String[]{b.title, b.format})) {
                    while (c.moveToNext()) {
                        long dupId = c.getLong(0);
                        String dupPath = c.getString(1);
                        if (dupPath != null) {
                            File df = new File(dupPath);
                            if (df.exists()) BookVaultCrypto.secureDeleteFile(df);
                        }
                        try {
                            File dcov = com.litereader.app.parser.BookCoverExtractor.getCoverCacheFile(appContext, dupId);
                            if (dcov.exists()) dcov.delete();
                        } catch (Exception ignored) {
                        }
                        db.delete("bookmarks", "book_id = ?", new String[]{String.valueOf(dupId)});
                    }
                } catch (Exception ignored) {
                }
                db.delete("books", "title = ? AND format = ?", new String[]{b.title, b.format});
            }
        }
        db.delete("books", "id = ?", new String[]{String.valueOf(bookId)});
        db.delete("bookmarks", "book_id = ?", new String[]{String.valueOf(bookId)});
    }

    public Bookmark addBookmark(Book book, int chapterIndex, String chapterTitle, int scrollOffset, float progressPercent, String snippet) {
        ContentValues cv = new ContentValues();
        cv.put("book_id", book.id);
        cv.put("book_title", book.title);
        cv.put("book_path", book.filePath);
        cv.put("chapter_index", chapterIndex);
        cv.put("chapter_title", chapterTitle != null ? chapterTitle : ("第 " + (chapterIndex + 1) + " 章"));
        cv.put("scroll_offset", scrollOffset);
        cv.put("progress_percent", progressPercent);
        cv.put("snippet", snippet != null ? snippet : "");
        long now = System.currentTimeMillis();
        cv.put("created_at", now);

        long id = getWritableDatabase().insert("bookmarks", null, cv);
        Bookmark bm = new Bookmark();
        bm.id = id;
        bm.bookId = book.id;
        bm.bookTitle = book.title;
        bm.bookPath = book.filePath;
        bm.chapterIndex = chapterIndex;
        bm.chapterTitle = chapterTitle;
        bm.scrollOffset = scrollOffset;
        bm.progressPercent = progressPercent;
        bm.snippet = snippet;
        bm.createdAt = now;
        return bm;
    }

    public List<Bookmark> getBookmarks(Long bookIdFilter) {
        List<Bookmark> list = new ArrayList<>();
        String sql = "SELECT * FROM bookmarks";
        String[] args = null;
        if (bookIdFilter != null) {
            sql += " WHERE book_id = ?";
            args = new String[]{String.valueOf(bookIdFilter)};
        }
        sql += " ORDER BY created_at DESC";
        try (Cursor c = getReadableDatabase().rawQuery(sql, args)) {
            while (c.moveToNext()) {
                Bookmark bm = new Bookmark();
                bm.id = c.getLong(c.getColumnIndexOrThrow("id"));
                bm.bookId = c.getLong(c.getColumnIndexOrThrow("book_id"));
                bm.bookTitle = c.getString(c.getColumnIndexOrThrow("book_title"));
                bm.bookPath = c.getString(c.getColumnIndexOrThrow("book_path"));
                bm.chapterIndex = c.getInt(c.getColumnIndexOrThrow("chapter_index"));
                bm.chapterTitle = c.getString(c.getColumnIndexOrThrow("chapter_title"));
                bm.scrollOffset = c.getInt(c.getColumnIndexOrThrow("scroll_offset"));
                bm.progressPercent = c.getFloat(c.getColumnIndexOrThrow("progress_percent"));
                bm.snippet = c.getString(c.getColumnIndexOrThrow("snippet"));
                bm.createdAt = c.getLong(c.getColumnIndexOrThrow("created_at"));
                list.add(bm);
            }
        }
        return list;
    }

    public void deleteBookmark(long id) {
        getWritableDatabase().delete("bookmarks", "id = ?", new String[]{String.valueOf(id)});
    }

    public NoteItem saveNote(Long existingNoteId, long bookId, String bookTitle, String bookPath,
                             int chapterIndex, String chapterTitle, String excerpt, String content) {
        long now = System.currentTimeMillis();
        String safeTitle = (bookTitle != null && !bookTitle.isEmpty())
                ? bookTitle.replaceAll("[\\\\/:*?\"<>|]", "_")
                : "通用笔记";
        File notesDir = getNotesDirectory();
        if (!notesDir.exists()) notesDir.mkdirs();
        File bookNoteTxt = new File(notesDir, safeTitle + "_笔记.txt");

        ContentValues cv = new ContentValues();
        cv.put("book_id", bookId);
        cv.put("book_title", bookTitle != null ? bookTitle : "独立纯文本笔记");
        cv.put("book_path", bookPath != null ? bookPath : "");
        cv.put("chapter_index", chapterIndex);
        cv.put("chapter_title", chapterTitle != null ? chapterTitle : "");
        cv.put("excerpt", excerpt != null ? excerpt : "");
        cv.put("content", content != null ? content : "");
        cv.put("txt_file_path", bookNoteTxt.getAbsolutePath());
        cv.put("updated_at", now);

        SQLiteDatabase db = getWritableDatabase();
        long id;
        if (existingNoteId != null && existingNoteId > 0) {
            db.update("notes", cv, "id = ?", new String[]{String.valueOf(existingNoteId)});
            id = existingNoteId;
        } else {
            id = db.insert("notes", null, cv);
        }

        syncPlainTextNoteFiles(bookId, bookNoteTxt);

        NoteItem item = new NoteItem();
        item.id = id;
        item.bookId = bookId;
        item.bookTitle = cv.getAsString("book_title");
        item.bookPath = cv.getAsString("book_path");
        item.chapterIndex = chapterIndex;
        item.chapterTitle = chapterTitle;
        item.excerpt = excerpt;
        item.content = content;
        item.txtFilePath = bookNoteTxt.getAbsolutePath();
        item.updatedAt = now;
        return item;
    }

    public List<NoteItem> getNotes(Long bookIdFilter, String searchQuery) {
        List<NoteItem> list = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT * FROM notes WHERE 1=1");
        List<String> args = new ArrayList<>();
        if (bookIdFilter != null) {
            sql.append(" AND book_id = ?");
            args.add(String.valueOf(bookIdFilter));
        }
        if (searchQuery != null && !searchQuery.trim().isEmpty()) {
            String like = "%" + searchQuery.trim() + "%";
            sql.append(" AND (book_title LIKE ? OR content LIKE ? OR excerpt LIKE ? OR chapter_title LIKE ?)");
            args.add(like);
            args.add(like);
            args.add(like);
            args.add(like);
        }
        sql.append(" ORDER BY updated_at DESC");
        try (Cursor c = getReadableDatabase().rawQuery(sql.toString(), args.toArray(new String[0]))) {
            while (c.moveToNext()) {
                NoteItem n = new NoteItem();
                n.id = c.getLong(c.getColumnIndexOrThrow("id"));
                n.bookId = c.getLong(c.getColumnIndexOrThrow("book_id"));
                n.bookTitle = c.getString(c.getColumnIndexOrThrow("book_title"));
                n.bookPath = c.getString(c.getColumnIndexOrThrow("book_path"));
                n.chapterIndex = c.getInt(c.getColumnIndexOrThrow("chapter_index"));
                n.chapterTitle = c.getString(c.getColumnIndexOrThrow("chapter_title"));
                n.excerpt = c.getString(c.getColumnIndexOrThrow("excerpt"));
                n.content = c.getString(c.getColumnIndexOrThrow("content"));
                n.txtFilePath = c.getString(c.getColumnIndexOrThrow("txt_file_path"));
                n.updatedAt = c.getLong(c.getColumnIndexOrThrow("updated_at"));
                list.add(n);
            }
        }
        return list;
    }

    public void deleteNote(NoteItem note) {
        if (note == null) return;
        getWritableDatabase().delete("notes", "id = ?", new String[]{String.valueOf(note.id)});
        if (note.txtFilePath != null && !note.txtFilePath.isEmpty()) {
            syncPlainTextNoteFiles(note.bookId, new File(note.txtFilePath));
        }
    }

    public File getNotesDirectory() {
        return new File(appContext.getFilesDir(), "PlainTextNotes");
    }

    private void syncPlainTextNoteFiles(long bookId, File targetTxtFile) {
        try {
            List<NoteItem> bookNotes = getNotes(bookId, null);
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
            StringBuilder sb = new StringBuilder();
            sb.append("========================================\n");
            sb.append("极简阅读 LiteReader - 本地纯文本笔记\n");
            sb.append("导出时间: ").append(sdf.format(new Date())).append("\n");
            sb.append("========================================\n\n");

            for (NoteItem n : bookNotes) {
                sb.append("【书名】").append(n.bookTitle).append("\n");
                if (n.chapterTitle != null && !n.chapterTitle.isEmpty()) {
                    sb.append("【章节】").append(n.chapterTitle).append("\n");
                }
                sb.append("【时间】").append(sdf.format(new Date(n.updatedAt))).append("\n");
                if (n.excerpt != null && !n.excerpt.isEmpty()) {
                    sb.append("【原文摘录】\n> ").append(n.excerpt.replace("\n", "\n> ")).append("\n");
                }
                sb.append("【笔记内容】\n").append(n.content).append("\n");
                sb.append("----------------------------------------\n\n");
            }

            if (targetTxtFile != null) {
                File parent = targetTxtFile.getParentFile();
                if (parent != null && !parent.exists()) parent.mkdirs();
                try (FileOutputStream fos = new FileOutputStream(targetTxtFile, false)) {
                    fos.write(sb.toString().getBytes(StandardCharsets.UTF_8));
                }
            }
        } catch (Exception ignored) {
        }
    }

    private Book readBookCursor(Cursor c) {
        Book b = new Book();
        b.id = c.getLong(c.getColumnIndexOrThrow("id"));
        b.title = c.getString(c.getColumnIndexOrThrow("title"));
        b.author = c.getString(c.getColumnIndexOrThrow("author"));
        b.filePath = c.getString(c.getColumnIndexOrThrow("file_path"));
        b.folderPath = c.getString(c.getColumnIndexOrThrow("folder_path"));
        b.folderName = c.getString(c.getColumnIndexOrThrow("folder_name"));
        b.format = c.getString(c.getColumnIndexOrThrow("format"));
        b.subExtension = c.getString(c.getColumnIndexOrThrow("sub_extension"));
        b.fileSize = c.getLong(c.getColumnIndexOrThrow("file_size"));
        b.currentChapter = c.getInt(c.getColumnIndexOrThrow("current_chapter"));
        b.totalChapters = c.getInt(c.getColumnIndexOrThrow("total_chapters"));
        b.scrollOffset = c.getInt(c.getColumnIndexOrThrow("scroll_offset"));
        b.progressPercent = c.getFloat(c.getColumnIndexOrThrow("progress_percent"));
        b.lastReadTime = c.getLong(c.getColumnIndexOrThrow("last_read_time"));
        b.addedTime = c.getLong(c.getColumnIndexOrThrow("added_time"));
        b.isFavorite = c.getInt(c.getColumnIndexOrThrow("is_favorite")) == 1;
        return b;
    }
}
