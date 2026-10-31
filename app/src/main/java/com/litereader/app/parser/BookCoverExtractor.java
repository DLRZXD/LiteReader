package com.litereader.app.parser;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.Base64;
import android.util.LruCache;

import com.litereader.app.data.Book;
import com.litereader.app.data.BookVaultCrypto;
import com.litereader.app.data.ReaderDatabase;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * Extracts, caches, and renders real Book Cover Images for PDF, EPUB, MOBI, AZW3, FB2, and TXT books,
 * and automatically repairs ugly English/hash/session filenames on PDF and e-book records.
 */
public class BookCoverExtractor {

    private static final int COVER_TARGET_W = 270;
    private static final int COVER_TARGET_H = 378;

    private static final LruCache<Long, Bitmap> MEMORY_CACHE = new LruCache<Long, Bitmap>(64) {
        @Override
        protected int sizeOf(Long key, Bitmap value) {
            return 1;
        }
    };

    public static Bitmap getCachedCoverFromMemory(long bookId) {
        if (bookId <= 0) return null;
        Bitmap bmp = MEMORY_CACHE.get(bookId);
        if (bmp != null && !bmp.isRecycled()) {
            return bmp;
        }
        return null;
    }

    public static File getCoverCacheFile(Context context, long bookId) {
        File dir = new File(context.getApplicationContext().getFilesDir(), "book_covers");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return new File(dir, "cover_v2_" + bookId + ".png");
    }

    /**
     * Loads the book cover from memory/disk cache, or extracts it from the book file (decrypting if needed),
     * while also repairing any ugly English/hash/session_v_ book title in SQLite.
     */
    public static Bitmap loadOrExtractCover(Context context, Book book, ReaderDatabase db) {
        if (context == null || book == null) return null;

        Bitmap mem = getCachedCoverFromMemory(book.id);
        if (mem != null && !isUglyOrSyntheticTitle(book.title)) {
            return mem;
        }

        File cacheFile = getCoverCacheFile(context, book.id);
        boolean needTitleRepair = isUglyOrSyntheticTitle(book.title);

        if (cacheFile.exists() && cacheFile.length() > 100 && !needTitleRepair) {
            Bitmap diskBmp = BitmapFactory.decodeFile(cacheFile.getAbsolutePath());
            if (diskBmp != null) {
                MEMORY_CACHE.put(book.id, diskBmp);
                return diskBmp;
            }
        }

        File rawFile = book.filePath != null ? new File(book.filePath) : null;
        Bitmap extracted = null;

        try {
            if (rawFile != null && rawFile.exists()) {
                String ext = (book.subExtension != null && !book.subExtension.isEmpty())
                        ? book.subExtension
                        : ("." + (book.format != null ? book.format.toLowerCase(Locale.ROOT) : "pdf"));
                File workingFile = BookVaultCrypto.decryptToTempReadingFile(context, rawFile, ext);

                // 1. If the book's title is a session_v_ / hash / long English slug, repair it from inside the file!
                if (needTitleRepair && db != null) {
                    String repaired = extractCleanBookTitleFromFile(workingFile, book.format, book.title);
                    if (repaired != null && !repaired.isEmpty() && !repaired.equals(book.title)) {
                        book.title = repaired;
                        db.forceUpdateBookTitle(book.id, repaired);
                    }
                }

                // 2. Extract the real cover image according to the actual format
                String actualFormat = BookParserEngine.detectActualFormat(workingFile);
                switch (actualFormat) {
                    case "PDF":
                        extracted = extractPdfCoverBitmap(workingFile);
                        break;
                    case "EPUB":
                        extracted = extractEpubCoverBitmap(workingFile);
                        break;
                    case "FB2":
                        extracted = extractFb2CoverBitmap(workingFile);
                        break;
                    case "MOBI":
                    case "AZW3":
                        extracted = extractMobiCoverBitmap(workingFile);
                        break;
                    default:
                        break;
                }
            }
        } catch (Throwable ignored) {
        }

        if (extracted == null) {
            extracted = renderStylizedBookCover(book.title, book.author, book.format);
        } else {
            extracted = scaleCenterCropCover(extracted, COVER_TARGET_W, COVER_TARGET_H);
        }

        if (extracted != null) {
            MEMORY_CACHE.put(book.id, extracted);
            try (FileOutputStream fos = new FileOutputStream(cacheFile)) {
                extracted.compress(Bitmap.CompressFormat.PNG, 90, fos);
            } catch (Exception ignored) {
            }
        }
        return extracted;
    }

    /**
     * Detects whether a book title is a corrupted temp filename (`session_v_...`, `v_...`, `zlib_book_...`),
     * URL-encoded string, MD5/UUID hash, or a long string of English/ASCII characters/underscores.
     */
    public static boolean isUglyOrSyntheticTitle(String title) {
        if (title == null) return true;
        String t = title.trim();
        if (t.isEmpty()) return true;
        String low = t.toLowerCase(Locale.ROOT);
        if (low.startsWith("session_v_") || low.startsWith("v_") || low.startsWith("zlib_book_")
                || low.startsWith("shared_book") || low.startsWith("untitled")
                || low.startsWith("microsoft word") || low.endsWith(".lrv")) {
            return true;
        }
        if (t.contains("%E") || t.contains("%e") || t.contains("%20")) {
            return true;
        }
        // Check if the title has zero CJK characters and is a long ASCII/English/digits/underscore filename (> 16 chars)
        int cjkCount = 0;
        int asciiCount = 0;
        int digitOrUnderscore = 0;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF) {
                cjkCount++;
            } else if (c < 128) {
                asciiCount++;
                if ((c >= '0' && c <= '9') || c == '_' || c == '-') {
                    digitOrUnderscore++;
                }
            }
        }
        if (cjkCount == 0 && (t.length() >= 18 || digitOrUnderscore >= 5)) {
            return true;
        }
        return false;
    }

    /**
     * Extracts a clean human-readable book title from inside the file (PDF /Outlines, PDF /Info,
     * PDF first-page heading, EPUB OPF dc:title, MOBI header, etc.) or cleans up URL-encoded/slug filenames.
     */
    public static String extractCleanBookTitleFromFile(File workingFile, String format, String rawTitle) {
        // 1. First try URL-decoding if `rawTitle` contained `%E4%B8...`
        if (rawTitle != null && rawTitle.contains("%")) {
            try {
                String dec = URLDecoder.decode(rawTitle, "UTF-8").trim();
                dec = cleanFileNameArtifacts(dec);
                if (!isUglyOrSyntheticTitle(dec)) {
                    return dec;
                }
            } catch (Exception ignored) {
            }
        }

        if (workingFile != null && workingFile.exists()) {
            String actualFmt = BookParserEngine.detectActualFormat(workingFile);
            if ("PDF".equalsIgnoreCase(actualFmt)) {
                String pdfTitle = PdfConverterEngine.extractBestBookTitleFromPdf(workingFile);
                if (pdfTitle != null && !pdfTitle.isEmpty() && !isUglyOrSyntheticTitle(pdfTitle)) {
                    return pdfTitle;
                }
            } else {
                String quickTitle = BookParserEngine.extractQuickBookTitle(workingFile, actualFmt);
                if (quickTitle != null && !quickTitle.isEmpty() && !isUglyOrSyntheticTitle(quickTitle)) {
                    return quickTitle.trim();
                }
            }
        }

        // 2. Clean up Z-Library / ASCII slug or fallback to a clean Chinese title
        String cleaned = cleanFileNameArtifacts(rawTitle);
        if (!cleaned.isEmpty() && !isUglyOrSyntheticTitle(cleaned)) {
            return cleaned;
        }
        return cleaned.isEmpty() ? "精选藏书" : cleaned;
    }

    public static String cleanFileNameArtifacts(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        if (s.contains("%")) {
            try {
                s = URLDecoder.decode(s, "UTF-8");
            } catch (Exception ignored) {
            }
        }
        // Strip session_v_<uuid>.lrv prefix
        s = s.replaceAll("(?i)^session_v_[0-9a-f]{16,40}(\\.lrv)?", "");
        s = s.replaceAll("(?i)^v_[0-9a-f]{16,40}(\\.lrv)?", "");
        // Strip Z-Library suffixes like (Z-Library), _z-lib.org, - libgen.li, etc.
        s = s.replaceAll("(?i)\\s*[\\(\\[（【]?(?:z-library|z-lib\\.[a-z]+|libgen\\.[a-z]+|1lib\\.[a-z]+)[\\)\\]）】]?", "");
        // Strip file extensions
        s = s.replaceAll("(?i)\\.(?:pdf|epub|mobi|azw3|fb2|fbz|zip|txt|lrv)$", "");
        // Strip leading/trailing 6+ digit numeric IDs (like Z-Library book IDs `12345678_`)
        s = s.replaceAll("^[0-9]{6,}[_\\-\\s]+", "");
        s = s.replaceAll("[_\\-\\s]+[0-9a-fA-F]{8,}$", "");
        s = s.replace('_', ' ').replaceAll("\\s+", " ").trim();
        return s;
    }

    private static Bitmap extractPdfCoverBitmap(File pdfFile) {
        try (ParcelFileDescriptor pfd = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY);
             PdfRenderer renderer = new PdfRenderer(pfd)) {
            if (renderer.getPageCount() <= 0) return null;
            try (PdfRenderer.Page page = renderer.openPage(0)) {
                int w = COVER_TARGET_W * 2;
                int h = Math.max(200, (int) ((float) w * page.getHeight() / Math.max(1, page.getWidth())));
                Bitmap raw = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                raw.eraseColor(Color.WHITE);
                page.render(raw, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);

                Rect bounds = PdfConverterEngine.detectInkBounds(raw, 8);
                if (bounds.width() > w * 0.35f && bounds.height() > h * 0.35f
                        && (bounds.width() < w * 0.96f || bounds.height() < h * 0.96f)) {
                    Bitmap cropped = Bitmap.createBitmap(raw, bounds.left, bounds.top, bounds.width(), bounds.height());
                    if (cropped != raw) raw.recycle();
                    return cropped;
                }
                return raw;
            }
        } catch (Throwable e) {
            return null;
        }
    }

    private static Bitmap extractEpubCoverBitmap(File epubFile) {
        Charset[] charsets = new Charset[]{StandardCharsets.UTF_8, Charset.forName("GB18030"), StandardCharsets.ISO_8859_1};
        for (Charset cs : charsets) {
            try (ZipFile zf = new ZipFile(epubFile, cs)) {
                ZipEntry bestCoverEntry = null;
                ZipEntry fallbackImageEntry = null;
                long bestSize = 0;

                Enumeration<? extends ZipEntry> entries = zf.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    if (entry.isDirectory()) continue;
                    String name = entry.getName().toLowerCase(Locale.ROOT);
                    if (name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png") || name.endsWith(".webp")) {
                        if (name.contains("cover") || name.contains("front") || name.contains("folder")) {
                            bestCoverEntry = entry;
                            break;
                        }
                        if (fallbackImageEntry == null || entry.getSize() > bestSize) {
                            fallbackImageEntry = entry;
                            bestSize = entry.getSize();
                        }
                    }
                }

                ZipEntry target = bestCoverEntry != null ? bestCoverEntry : fallbackImageEntry;
                if (target != null) {
                    try (InputStream is = zf.getInputStream(target)) {
                        byte[] imgBytes = readStreamBytes(is, 6 * 1024 * 1024);
                        Bitmap bmp = decodeSampledBitmap(imgBytes, COVER_TARGET_W, COVER_TARGET_H);
                        if (bmp != null) return bmp;
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private static Bitmap extractFb2CoverBitmap(File fb2File) {
        try {
            byte[] rawBytes;
            String lower = fb2File.getName().toLowerCase(Locale.ROOT);
            if (lower.endsWith(".zip") || lower.endsWith(".fbz")) {
                try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(fb2File)))) {
                    ZipEntry entry = zis.getNextEntry();
                    if (entry == null) return null;
                    rawBytes = readStreamBytes(zis, 10 * 1024 * 1024);
                }
            } else {
                try (FileInputStream fis = new FileInputStream(fb2File)) {
                    rawBytes = readStreamBytes(fis, 10 * 1024 * 1024);
                }
            }
            String xml = new String(rawBytes, StandardCharsets.ISO_8859_1);
            Matcher m = Pattern.compile("<binary\\b[^>]*>(.*?)</binary>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE).matcher(xml);
            while (m.find()) {
                String b64 = m.group(1).replaceAll("\\s+", "");
                if (b64.length() > 200) {
                    byte[] imgBytes = Base64.decode(b64, Base64.DEFAULT);
                    Bitmap bmp = decodeSampledBitmap(imgBytes, COVER_TARGET_W, COVER_TARGET_H);
                    if (bmp != null) return bmp;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static Bitmap extractMobiCoverBitmap(File mobiFile) {
        try (RandomAccessFile raf = new RandomAccessFile(mobiFile, "r")) {
            long fileLen = raf.length();
            if (fileLen < 78) return null;
            byte[] pdbHeader = new byte[78];
            raf.readFully(pdbHeader);
            int numRecords = ((pdbHeader[76] & 0xFF) << 8) | (pdbHeader[77] & 0xFF);
            if (numRecords <= 1 || numRecords > 32768) return null;

            long[] offsets = new long[numRecords];
            for (int i = 0; i < numRecords; i++) {
                offsets[i] = raf.readInt() & 0xFFFFFFFFL;
                raf.readInt();
            }

            Bitmap bestBmp = null;
            int bestArea = 0;
            for (int i = 1; i < numRecords; i++) {
                long start = offsets[i];
                long end = (i + 1 < numRecords) ? offsets[i + 1] : fileLen;
                int len = (int) Math.min(4 * 1024 * 1024L, end - start);
                if (len < 256) continue;

                raf.seek(start);
                byte[] magic = new byte[4];
                raf.readFully(magic);
                boolean isJpeg = (magic[0] & 0xFF) == 0xFF && (magic[1] & 0xFF) == 0xD8 && (magic[2] & 0xFF) == 0xFF;
                boolean isPng = (magic[0] & 0xFF) == 0x89 && magic[1] == 'P' && magic[2] == 'N' && magic[3] == 'G';
                if (isJpeg || isPng) {
                    raf.seek(start);
                    byte[] imgBytes = new byte[len];
                    raf.readFully(imgBytes);
                    Bitmap bmp = decodeSampledBitmap(imgBytes, COVER_TARGET_W, COVER_TARGET_H);
                    if (bmp != null) {
                        int area = bmp.getWidth() * bmp.getHeight();
                        if (area > bestArea) {
                            if (bestBmp != null) bestBmp.recycle();
                            bestBmp = bmp;
                            bestArea = area;
                        } else {
                            bmp.recycle();
                        }
                        if (bestArea >= 180 * 240) {
                            return bestBmp;
                        }
                    }
                }
            }
            return bestBmp;
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * Renders a refined hardbound-style book cover when a book (e.g. TXT) has no embedded image.
     */
    public static Bitmap renderStylizedBookCover(String rawTitle, String rawAuthor, String format) {
        Bitmap bmp = Bitmap.createBitmap(COVER_TARGET_W, COVER_TARGET_H, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);

        String title = (rawTitle != null && !rawTitle.trim().isEmpty()) ? rawTitle.trim() : "无题藏书";
        String author = (rawAuthor != null && !rawAuthor.trim().isEmpty()) ? rawAuthor.trim() : "经典藏书";
        String fmt = (format != null && !format.trim().isEmpty()) ? format.trim().toUpperCase(Locale.ROOT) : "BOOK";

        int[][] palettes = new int[][]{
                {0xFF243E36, 0xFF182B25, 0xFFF4ECD8},
                {0xFF6B3A2A, 0xFF4A2519, 0xFFFAF3E6},
                {0xFF283A52, 0xFF1A2638, 0xFFF2F5F8},
                {0xFF4A3B59, 0xFF2F243A, 0xFFF6F2F8},
                {0xFF3E4A36, 0xFF273021, 0xFFF4F6EE}
        };
        int pIdx = Math.abs(title.hashCode()) % palettes.length;
        int topColor = palettes[pIdx][0];
        int botColor = palettes[pIdx][1];
        int creamColor = palettes[pIdx][2];

        Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bgPaint.setShader(new LinearGradient(0, 0, COVER_TARGET_W, COVER_TARGET_H,
                topColor, botColor, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, COVER_TARGET_W, COVER_TARGET_H, bgPaint);

        // Bookbinding left spine crease & gold border frame
        Paint spinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        spinePaint.setColor(0x33000000);
        canvas.drawRect(0, 0, 18, COVER_TARGET_H, spinePaint);
        spinePaint.setColor(0x33FFFFFF);
        canvas.drawLine(18, 0, 18, COVER_TARGET_H, spinePaint);

        Paint framePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        framePaint.setStyle(Paint.Style.STROKE);
        framePaint.setStrokeWidth(2.2f);
        framePaint.setColor(0x55E8D8B8);
        canvas.drawRoundRect(new RectF(28, 22, COVER_TARGET_W - 18, COVER_TARGET_H - 22), 8f, 8f, framePaint);

        // Cream title plaque in upper-center
        Paint plaquePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        plaquePaint.setColor(creamColor);
        RectF plaqueRect = new RectF(40, 44, COVER_TARGET_W - 30, 262);
        canvas.drawRoundRect(plaqueRect, 6f, 6f, plaquePaint);

        framePaint.setColor(topColor);
        framePaint.setStrokeWidth(1.5f);
        canvas.drawRoundRect(new RectF(45, 49, COVER_TARGET_W - 35, 257), 4f, 4f, framePaint);

        // Draw wrapped book title inside plaque
        TextPaint titlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        titlePaint.setColor(topColor);
        titlePaint.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
        titlePaint.setTextSize(title.length() > 16 ? 21f : (title.length() > 10 ? 24f : 27f));

        String displayTitle = title.length() > 28 ? (title.substring(0, 27) + "…") : title;
        int availTitleW = (int) (plaqueRect.width() - 28);
        StaticLayout titleLayout = StaticLayout.Builder
                .obtain(displayTitle, 0, displayTitle.length(), titlePaint, availTitleW)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setLineSpacing(0f, 1.2f)
                .setMaxLines(4)
                .build();

        canvas.save();
        float titleY = plaqueRect.top + Math.max(18f, (plaqueRect.height() - titleLayout.getHeight()) / 2f);
        canvas.translate(plaqueRect.left + 14f, titleY);
        titleLayout.draw(canvas);
        canvas.restore();

        // Author text near bottom
        TextPaint authorPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        authorPaint.setColor(0xFFE8DCC4);
        authorPaint.setTypeface(Typeface.SERIF);
        authorPaint.setTextSize(16f);
        String shortAuthor = author.length() > 12 ? (author.substring(0, 11) + "…") : author;
        float aw = authorPaint.measureText(shortAuthor);
        canvas.drawText(shortAuthor, 28 + (COVER_TARGET_W - 46 - aw) / 2f, COVER_TARGET_H - 64, authorPaint);

        // Format seal badge at bottom
        Paint badgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        badgePaint.setColor(0xFFC85A32);
        RectF badgeRect = new RectF(COVER_TARGET_W / 2f - 32, COVER_TARGET_H - 48, COVER_TARGET_W / 2f + 32, COVER_TARGET_H - 28);
        canvas.drawRoundRect(badgeRect, 5f, 5f, badgePaint);

        authorPaint.setColor(Color.WHITE);
        authorPaint.setTypeface(Typeface.DEFAULT_BOLD);
        authorPaint.setTextSize(12.5f);
        float fw = authorPaint.measureText(fmt);
        canvas.drawText(fmt, badgeRect.centerX() - fw / 2f, badgeRect.centerY() + 4.5f, authorPaint);

        return bmp;
    }

    private static Bitmap scaleCenterCropCover(Bitmap src, int targetW, int targetH) {
        if (src == null) return null;
        int sw = src.getWidth();
        int sh = src.getHeight();
        if (sw <= 0 || sh <= 0) return src;
        if (sw == targetW && sh == targetH) return src;

        float scale = Math.max((float) targetW / sw, (float) targetH / sh);
        float scaledW = sw * scale;
        float scaledH = sh * scale;
        float dx = (targetW - scaledW) / 2f;
        float dy = (targetH - scaledH) / 2f;

        Bitmap out = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888);
        out.eraseColor(Color.WHITE);
        Canvas canvas = new Canvas(out);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        canvas.drawBitmap(src, null, new RectF(dx, dy, dx + scaledW, dy + scaledH), p);
        if (out != src) {
            src.recycle();
        }
        return out;
    }

    private static Bitmap decodeSampledBitmap(byte[] data, int reqW, int reqH) {
        if (data == null || data.length == 0) return null;
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, opts);
            int inSampleSize = 1;
            if (opts.outHeight > reqH || opts.outWidth > reqW) {
                int halfH = opts.outHeight / 2;
                int halfW = opts.outWidth / 2;
                while ((halfH / inSampleSize) >= reqH && (halfW / inSampleSize) >= reqW) {
                    inSampleSize *= 2;
                }
            }
            opts.inJustDecodeBounds = false;
            opts.inSampleSize = Math.max(1, inSampleSize);
            return BitmapFactory.decodeByteArray(data, 0, data.length, opts);
        } catch (Throwable e) {
            return null;
        }
    }

    private static byte[] readStreamBytes(InputStream is, int maxBytes) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) != -1) {
            bos.write(buf, 0, n);
            if (bos.size() >= maxBytes) break;
        }
        return bos.toByteArray();
    }
}
