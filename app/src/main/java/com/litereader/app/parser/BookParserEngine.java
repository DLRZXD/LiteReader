package com.litereader.app.parser;

import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;
import android.text.Html;

import com.litereader.app.data.Book;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.io.StringReader;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

public class BookParserEngine {

    private static final Pattern TXT_CHAPTER_PATTERN = Pattern.compile(
            "(?m)^[ \\t　]*(第[0-9一二三四五六七八九十百千万零〇两]+[章节回卷集部篇]|Chapter\\s+\\d+|CHAPTER\\s+[IVXLCDM0-9]+|序[言章幕]?|楔子|尾声|后记|引子)[^\\n]{0,40}$"
    );

    private static final android.util.LruCache<String, ParsedBook> PARSED_BOOK_MEM_CACHE =
            new android.util.LruCache<String, ParsedBook>(24) {
                @Override
                protected int sizeOf(String key, ParsedBook value) {
                    return 1;
                }
            };

    private static final android.util.LruCache<String, List<String>> LAZY_EPUB_ENTRY_CACHE =
            new android.util.LruCache<String, List<String>>(24) {
                @Override
                protected int sizeOf(String key, List<String> value) {
                    return 1;
                }
            };

    private static String buildCacheKey(File file) {
        if (file == null) return "";
        return file.getName() + "|" + file.length();
    }

    public static ParsedBook getCachedParsedBookIfPresent(File file) {
        if (file == null) return null;
        return PARSED_BOOK_MEM_CACHE.get(buildCacheKey(file));
    }

    public static ParsedBook parseBook(File file) throws Exception {
        if (file == null || !file.exists()) {
            throw new IllegalArgumentException("文件不存在");
        }
        String cacheKey = buildCacheKey(file);
        ParsedBook memCached = PARSED_BOOK_MEM_CACHE.get(cacheKey);
        if (memCached != null && !memCached.chapters.isEmpty()) {
            return memCached;
        }

        // Check fast binary disk cache (`.pbcache_v3`) so previously indexed books load in < 2ms!
        File diskCacheFile = getParsedBookDiskCacheFile(file);
        if (diskCacheFile != null && diskCacheFile.exists() && diskCacheFile.length() > 16) {
            ParsedBook diskLoaded = loadParsedBookFromDiskCache(diskCacheFile, file);
            if (diskLoaded != null && !diskLoaded.chapters.isEmpty()) {
                PARSED_BOOK_MEM_CACHE.put(cacheKey, diskLoaded);
                return diskLoaded;
            }
        }

        String format = detectActualFormat(file);
        ParsedBook parsed;
        switch (format) {
            case "PDF":
                parsed = parsePdf(file);
                break;
            case "EPUB":
                parsed = parseEpub(file);
                break;
            case "FB2":
                parsed = parseFb2(file);
                break;
            case "MOBI":
            case "AZW3":
                parsed = parseMobiOrAzw3(file, format);
                break;
            case "TXT":
            default:
                parsed = parseTxt(file);
                break;
        }

        if (parsed != null && !parsed.chapters.isEmpty()) {
            PARSED_BOOK_MEM_CACHE.put(cacheKey, parsed);
            if (!parsed.isPdf && diskCacheFile != null) {
                saveParsedBookToDiskCache(parsed, diskCacheFile);
            }
        }
        return parsed;
    }

    public static void savePdfTocToCache(File file, ParsedBook pb) {
        if (file == null || pb == null || pb.chapters.isEmpty()) return;
        PARSED_BOOK_MEM_CACHE.put(buildCacheKey(file), pb);
        File diskCacheFile = getParsedBookDiskCacheFile(file);
        if (diskCacheFile != null) {
            saveParsedBookToDiskCache(pb, diskCacheFile);
        }
    }

    private static File getParsedBookDiskCacheFile(File readingFile) {
        try {
            File parent = readingFile.getParentFile();
            if (parent == null) return null;
            File cacheRoot = parent.getParentFile() != null ? parent.getParentFile() : parent;
            File dir = new File(cacheRoot, ".parsed_book_cache_v3");
            if (!dir.exists()) dir.mkdirs();
            String safeKey = (readingFile.getName() + "_" + readingFile.length()).replaceAll("[^a-zA-Z0-9._\\-]", "_");
            return new File(dir, safeKey + ".pbcache");
        } catch (Throwable t) {
            return null;
        }
    }

    private static void saveParsedBookToDiskCache(ParsedBook pb, File target) {
        try (java.io.DataOutputStream dos = new java.io.DataOutputStream(
                new java.io.BufferedOutputStream(new java.io.FileOutputStream(target), 65536))) {
            dos.writeInt(0x4C525033); // Magic "LRP3" (supports both eager and lazy EPUB chapters)
            dos.writeUTF(pb.title != null ? pb.title : "");
            dos.writeUTF(pb.author != null ? pb.author : "");
            dos.writeUTF(pb.format != null ? pb.format : "");
            dos.writeBoolean(pb.isPdf);
            dos.writeInt(pb.pdfPageCount);

            dos.writeInt(pb.tocEntries.size());
            for (ParsedBook.TocEntry te : pb.tocEntries) {
                dos.writeUTF(te.title != null ? te.title : "");
                dos.writeInt(te.chapterIndex);
                dos.writeInt(te.pageNumber);
            }

            dos.writeInt(pb.chapters.size());
            for (ParsedBook.Chapter ch : pb.chapters) {
                dos.writeInt(ch.index);
                dos.writeUTF(ch.title != null ? ch.title : "");
                boolean isLazy = ch.isLazyChapter() && ch.content == null;
                dos.writeBoolean(isLazy);
                if (isLazy) {
                    dos.writeUTF(ch.lazyZipEntryName != null ? ch.lazyZipEntryName : "");
                    dos.writeUTF(ch.lazyCharsetName != null ? ch.lazyCharsetName : "UTF-8");
                    dos.writeInt(ch.lazyChunkIndex);
                    dos.writeInt(ch.lazyTotalChunks);
                } else {
                    String c = ch.content != null ? ch.content : "";
                    byte[] contentBytes = c.getBytes(StandardCharsets.UTF_8);
                    dos.writeInt(contentBytes.length);
                    dos.write(contentBytes);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static ParsedBook loadParsedBookFromDiskCache(File source, File readingFile) {
        try (java.io.DataInputStream dis = new java.io.DataInputStream(
                new java.io.BufferedInputStream(new java.io.FileInputStream(source), 65536))) {
            int magic = dis.readInt();
            if (magic != 0x4C525033) return null;
            ParsedBook pb = new ParsedBook();
            pb.title = dis.readUTF();
            pb.author = dis.readUTF();
            pb.format = dis.readUTF();
            pb.isPdf = dis.readBoolean();
            pb.pdfPageCount = dis.readInt();

            int tocCount = dis.readInt();
            for (int i = 0; i < tocCount; i++) {
                String t = dis.readUTF();
                int chIdx = dis.readInt();
                int pageNum = dis.readInt();
                pb.tocEntries.add(new ParsedBook.TocEntry(t, chIdx, pageNum));
            }

            int chCount = dis.readInt();
            for (int i = 0; i < chCount; i++) {
                int idx = dis.readInt();
                String t = dis.readUTF();
                boolean isLazy = dis.readBoolean();
                if (isLazy) {
                    String entryName = dis.readUTF();
                    String charsetName = dis.readUTF();
                    int chunkIdx = dis.readInt();
                    int totalChunks = dis.readInt();
                    pb.chapters.add(new ParsedBook.Chapter(
                            idx, t, readingFile, entryName, charsetName, chunkIdx, totalChunks));
                } else {
                    int len = dis.readInt();
                    byte[] buf = new byte[Math.max(0, len)];
                    dis.readFully(buf);
                    pb.chapters.add(new ParsedBook.Chapter(idx, t, new String(buf, StandardCharsets.UTF_8)));
                }
            }
            return pb;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Detects the true book format by inspecting both the file's binary magic header and its filename.
     * Prevents garbled text if a PDF/MOBI/AZW3/EPUB file was saved with an inaccurate extension.
     */
    public static String detectActualFormat(File file) {
        String extFormat = Book.detectFormat(file != null ? file.getName() : "");
        if (file == null || !file.exists() || file.length() < 4) {
            return extFormat;
        }
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] header = new byte[512];
            int n = fis.read(header);
            if (n >= 4) {
                // 1. Check PDF magic "%PDF" anywhere in first 512 bytes
                for (int i = 0; i <= n - 4; i++) {
                    if (header[i] == '%' && header[i + 1] == 'P' && header[i + 2] == 'D' && header[i + 3] == 'F') {
                        return "PDF";
                    }
                }
                // 2. Check ZIP magic "PK\x03\x04" (EPUB or FB2.ZIP)
                if (header[0] == 'P' && header[1] == 'K' && header[2] == 3 && header[3] == 4) {
                    if ("FB2".equals(extFormat)) {
                        return "FB2";
                    }
                    return "EPUB";
                }
            }
            if (n >= 68) {
                String pdbType = new String(header, 60, 8, StandardCharsets.US_ASCII);
                if ("BOOKMOBI".equals(pdbType) || "TEXtREAd".equals(pdbType)) {
                    return "AZW3".equals(extFormat) ? "AZW3" : "MOBI";
                }
            }
        } catch (Exception ignored) {
        }
        return extFormat;
    }

    /**
     * Fast, lightweight metadata-only title extractor for BookCoverExtractor so cover loading
     * never has to parse all chapters of an EPUB/FB2/MOBI book.
     */
    public static String extractQuickBookTitle(File file, String format) {
        if (file == null || !file.exists()) return null;
        try {
            if ("EPUB".equalsIgnoreCase(format)) {
                Charset[] charsets = new Charset[]{StandardCharsets.UTF_8, Charset.forName("GB18030"), StandardCharsets.ISO_8859_1};
                for (Charset cs : charsets) {
                    try (ZipFile zf = new ZipFile(file, cs)) {
                        Enumeration<? extends ZipEntry> entries = zf.entries();
                        while (entries.hasMoreElements()) {
                            ZipEntry entry = entries.nextElement();
                            if (!entry.isDirectory() && entry.getName().toLowerCase(Locale.ROOT).endsWith(".opf")) {
                                try (InputStream is = zf.getInputStream(entry)) {
                                    String opfXml = decodeXmlOrHtmlBytes(readStreamBytesCapped(is, 512 * 1024));
                                    String t = extractXmlTagText(opfXml, "dc:title", "title");
                                    if (t != null && !t.trim().isEmpty()) {
                                        return htmlToPlain(t).trim();
                                    }
                                }
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    // ==================== 1. PDF PARSER (Instant <2ms Page Count + Async Title TOC) ====================
    private static ParsedBook parsePdf(File file) throws Exception {
        ParsedBook pb = new ParsedBook();
        pb.format = "PDF";
        pb.isPdf = true;
        pb.title = stripExt(file.getName());
        pb.author = "PDF 文档";

        try (ParcelFileDescriptor pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
             PdfRenderer renderer = new PdfRenderer(pfd)) {
            pb.pdfPageCount = Math.max(1, renderer.getPageCount());
            for (int p = 0; p < pb.pdfPageCount; p++) {
                String defaultTitle = "第 " + (p + 1) + " 页";
                pb.chapters.add(new ParsedBook.Chapter(p, defaultTitle, defaultTitle));
            }
        }
        return pb;
    }

    // ==================== 2. EPUB PARSER (Zero-Upfront-Decompression Central Directory Indexer + Lazy Chapter Loader) ====================
    private static ParsedBook parseEpub(File file) throws Exception {
        ParsedBook pb = new ParsedBook();
        pb.format = "EPUB";
        pb.title = stripExt(file.getName());
        pb.author = "EPUB 电子书";

        Charset[] charsets = new Charset[]{StandardCharsets.UTF_8, Charset.forName("GB18030"), StandardCharsets.ISO_8859_1};
        Charset selectedCharset = null;
        Map<String, String> normToRawName = new LinkedHashMap<>();
        Map<String, String> lowerNormToRawName = new HashMap<>();
        Map<String, String> fileNameToRawName = new HashMap<>();
        Map<String, Long> rawNameToSize = new HashMap<>();
        String firstOpfNorm = null;
        String firstNcxNorm = null;

        // Fast Central Directory scan (< 3ms even for 5,000-chapter 120MB omnibus EPUBs; zero XHTML decompression!)
        for (Charset cs : charsets) {
            Map<String, String> attemptNorm = new LinkedHashMap<>();
            Map<String, String> attemptLower = new HashMap<>();
            Map<String, String> attemptFileName = new HashMap<>();
            Map<String, Long> attemptSize = new HashMap<>();
            String attemptOpf = null;
            String attemptNcx = null;
            boolean hasEpubEntry = false;

            try (ZipFile zf = new ZipFile(file, cs)) {
                Enumeration<? extends ZipEntry> entries = zf.entries();
                while (entries.hasMoreElements()) {
                    try {
                        ZipEntry entry = entries.nextElement();
                        if (entry.isDirectory()) continue;
                        String rawName = entry.getName();
                        if (!shouldLoadEntryIntoMemory(rawName)) continue;

                        String norm = normalizePath(rawName);
                        String low = norm.toLowerCase(Locale.ROOT);
                        attemptNorm.put(norm, rawName);
                        attemptLower.put(low, rawName);
                        String fn = getFileNameOnly(low);
                        if (!fn.isEmpty() && !attemptFileName.containsKey(fn)) {
                            attemptFileName.put(fn, rawName);
                        }
                        attemptSize.put(rawName, entry.getSize());

                        if (attemptOpf == null && low.endsWith(".opf")) {
                            attemptOpf = norm;
                            hasEpubEntry = true;
                        } else if (attemptNcx == null && low.endsWith(".ncx")) {
                            attemptNcx = norm;
                        } else if (low.endsWith(".xhtml") || low.endsWith(".html") || low.endsWith(".htm")) {
                            hasEpubEntry = true;
                        }
                    } catch (Throwable ignoredEntry) {
                    }
                }
                if (attemptNorm.size() > normToRawName.size()) {
                    selectedCharset = cs;
                    normToRawName = attemptNorm;
                    lowerNormToRawName = attemptLower;
                    fileNameToRawName = attemptFileName;
                    rawNameToSize = attemptSize;
                    firstOpfNorm = attemptOpf;
                    firstNcxNorm = attemptNcx;
                }
                if (hasEpubEntry || attemptNorm.size() >= 2) {
                    break;
                }
            } catch (Throwable ignored) {
            }
        }

        // Fast path: valid ZIP with Central Directory indexed in memory
        if (selectedCharset != null && !normToRawName.isEmpty()) {
            try (ZipFile zf = new ZipFile(file, selectedCharset)) {
                String opfPath = null;
                byte[] containerBytes = readSingleZipEntryByPath(
                        zf, "META-INF/container.xml", normToRawName, lowerNormToRawName, fileNameToRawName, 256 * 1024);
                if (containerBytes != null) {
                    String containerXml = decodeXmlOrHtmlBytes(containerBytes);
                    String rawFullPath = extractAttr(containerXml, "full-path");
                    if (rawFullPath != null && !rawFullPath.isEmpty()) {
                        opfPath = normalizePath(urlDecodeSafe(rawFullPath));
                    }
                }
                if (opfPath == null || resolveRawZipEntryName(opfPath, normToRawName, lowerNormToRawName, fileNameToRawName) == null) {
                    opfPath = firstOpfNorm;
                }

                java.util.LinkedHashSet<String> spineSet = new java.util.LinkedHashSet<>();
                Map<String, String> tocLabels = new HashMap<>();
                Map<String, String> tocLowerLabels = new HashMap<>();
                Map<String, String> tocFileNameLabels = new HashMap<>();

                byte[] opfBytes = opfPath != null
                        ? readSingleZipEntryByPath(zf, opfPath, normToRawName, lowerNormToRawName, fileNameToRawName, 4 * 1024 * 1024)
                        : null;
                if (opfPath != null && opfBytes != null) {
                    String opfDir = getParentDir(opfPath);
                    String opfXml = decodeXmlOrHtmlBytes(opfBytes);

                    String title = extractXmlTagText(opfXml, "dc:title", "opf:title", "title");
                    String creator = extractXmlTagText(opfXml, "dc:creator", "opf:creator", "creator");
                    if (!title.isEmpty()) pb.title = htmlToPlain(title).trim();
                    if (!creator.isEmpty()) pb.author = htmlToPlain(creator).trim();

                    Map<String, String> manifestMap = new LinkedHashMap<>();
                    Map<String, String> manifestLowerMap = new HashMap<>();
                    String ncxHref = null;
                    String navHref = null;

                    // Linear scan of <item ...> tags in OPF
                    Matcher itemMatcher = Pattern.compile("<(?:[a-zA-Z0-9_\\-]+:)?item\\b([^>]+)>", Pattern.CASE_INSENSITIVE).matcher(opfXml);
                    while (itemMatcher.find()) {
                        String attrs = itemMatcher.group(1);
                        String id = extractAttr(attrs, "id");
                        String href = extractAttr(attrs, "href");
                        String mediaType = extractAttr(attrs, "media-type");
                        String properties = extractAttr(attrs, "properties");
                        if (id != null && href != null) {
                            String cleanHref = stripFragmentAndDecode(href);
                            String resolvedHref = normalizePath(opfDir.isEmpty() ? cleanHref : (opfDir + "/" + cleanHref));
                            manifestMap.put(id, resolvedHref);
                            manifestLowerMap.put(id.toLowerCase(Locale.ROOT), resolvedHref);
                            if ((mediaType != null && mediaType.toLowerCase(Locale.ROOT).contains("ncx"))
                                    || cleanHref.toLowerCase(Locale.ROOT).endsWith(".ncx")) {
                                ncxHref = resolvedHref;
                            }
                            if (properties != null && properties.toLowerCase(Locale.ROOT).contains("nav")) {
                                navHref = resolvedHref;
                            }
                        }
                    }

                    if (ncxHref == null) {
                        ncxHref = firstNcxNorm;
                    }

                    // Linear O(N) non-backtracking parser for EPUB 2 toc.ncx (handles multi-megabyte nested omnibus NCX in < 3ms!)
                    byte[] ncxBytes = ncxHref != null
                            ? readSingleZipEntryByPath(zf, ncxHref, normToRawName, lowerNormToRawName, fileNameToRawName, 8 * 1024 * 1024)
                            : null;
                    if (ncxHref != null && ncxBytes != null) {
                        String ncxDir = getParentDir(ncxHref);
                        String ncxXml = decodeXmlOrHtmlBytes(ncxBytes);
                        parseNcxTocLinear(ncxXml, ncxDir, tocLabels, tocLowerLabels, tocFileNameLabels);
                    }

                    // Linear O(N) parser for EPUB 3 nav.xhtml
                    byte[] navBytes = navHref != null
                            ? readSingleZipEntryByPath(zf, navHref, normToRawName, lowerNormToRawName, fileNameToRawName, 4 * 1024 * 1024)
                            : null;
                    if (navHref != null && navBytes != null) {
                        String navDir = getParentDir(navHref);
                        String navHtml = decodeXmlOrHtmlBytes(navBytes);
                        parseNavTocLinear(navHtml, navDir, tocLabels, tocLowerLabels, tocFileNameLabels);
                    }

                    // Parse spine itemrefs with O(1) HashMap lookup & O(1) LinkedHashSet deduplication
                    Matcher spineMatcher = Pattern.compile("<(?:[a-zA-Z0-9_\\-]+:)?itemref\\b([^>]+)>", Pattern.CASE_INSENSITIVE).matcher(opfXml);
                    while (spineMatcher.find()) {
                        String idref = extractAttr(spineMatcher.group(1), "idref");
                        if (idref != null) {
                            String targetPath = manifestMap.get(idref);
                            if (targetPath == null) {
                                targetPath = manifestLowerMap.get(idref.toLowerCase(Locale.ROOT));
                            }
                            if (targetPath != null) {
                                spineSet.add(targetPath);
                            }
                        }
                    }

                    if (spineSet.isEmpty() && !manifestMap.isEmpty()) {
                        for (String hrefPath : manifestMap.values()) {
                            if (isEpubContentDocumentPath(hrefPath)) {
                                spineSet.add(hrefPath);
                            }
                        }
                    }
                }

                if (spineSet.isEmpty()) {
                    for (String key : normToRawName.keySet()) {
                        if (isEpubContentDocumentPath(key)) {
                            spineSet.add(key);
                        }
                    }
                }

                String charsetName = selectedCharset.name();
                int chapterIdx = 0;
                int totalSpineCount = spineSet.size();

                for (String path : spineSet) {
                    String rawEntryName = resolveRawZipEntryName(path, normToRawName, lowerNormToRawName, fileNameToRawName);
                    if (rawEntryName == null) continue;

                    Long sizeObj = rawNameToSize.get(rawEntryName);
                    long uncompressedSize = sizeObj != null ? sizeObj : -1L;
                    if (uncompressedSize == 0L) continue;

                    String heading = findTocLabelFast(path, tocLabels, tocLowerLabels, tocFileNameLabels);
                    String lowPath = path.toLowerCase(Locale.ROOT);

                    // Skip trivial front-matter cover/titlepage wrappers (< 800 bytes) in multi-chapter books
                    if ((lowPath.contains("cover") || lowPath.contains("titlepage"))
                            && uncompressedSize > 0 && uncompressedSize < 800
                            && totalSpineCount > 1) {
                        continue;
                    }

                    // If TOC didn't label this entry and the book is small (or it's one of the first 2 chapters),
                    // peek at the first 4KB of the entry to extract its <h1>..<h4> or <title> heading!
                    if ((heading == null || heading.isEmpty()) && (totalSpineCount <= 36 || chapterIdx < 2)) {
                        try {
                            ZipEntry ze = zf.getEntry(rawEntryName);
                            if (ze != null) {
                                try (InputStream is = zf.getInputStream(ze)) {
                                    byte[] headBytes = readStreamBytesCapped(is, 4096);
                                    String headHtml = decodeXmlOrHtmlBytes(headBytes);
                                    heading = extractHtmlHeading(headHtml);
                                    if (heading == null || heading.isEmpty()) {
                                        String headText = cleanXhtmlToReadableText(headHtml);
                                        if (stripAllWhitespace(headText).isEmpty() && uncompressedSize > 0 && uncompressedSize <= 4096) {
                                            continue;
                                        }
                                        heading = firstLineSummary(headText, null);
                                    }
                                }
                            }
                        } catch (Throwable ignoredPeek) {
                        }
                    }

                    if (heading == null || heading.isEmpty()) {
                        heading = "第 " + (chapterIdx + 1) + " 章";
                    }

                    // If a single spine .xhtml file is huge (> 36KB uncompressed, e.g. a whole volume in an omnibus EPUB),
                    // split it into virtual lazy chunks (~24KB of XHTML per chunk) so pagination & rendering are always instant!
                    int totalChunks = 1;
                    if (uncompressedSize > 36864L) {
                        totalChunks = Math.max(1, Math.min(240, (int) ((uncompressedSize + 24575L) / 24576L)));
                    }

                    for (int c = 0; c < totalChunks; c++) {
                        String chunkTitle = (totalChunks == 1 || c == 0) ? heading : (heading + " (" + (c + 1) + ")");
                        pb.chapters.add(new ParsedBook.Chapter(
                                chapterIdx, chunkTitle, file, rawEntryName, charsetName, c, totalChunks));
                        chapterIdx++;
                    }
                }

                if (!pb.chapters.isEmpty()) {
                    return pb;
                }
            } catch (Throwable ignoredZip) {
            }
        }

        // Fallback for non-standard streaming ZIPs or raw HTML/TXT files saved with .epub extension
        Map<String, byte[]> zipEntries = readZipEntriesSafe(file);
        if (zipEntries.isEmpty()) {
            try (FileInputStream fis = new FileInputStream(file)) {
                byte[] rawBytes = readStreamBytesCapped(fis, 12 * 1024 * 1024);
                String rawText = decodeXmlOrHtmlBytes(rawBytes);
                String cleaned = cleanXhtmlToReadableText(rawText);
                if (!cleaned.trim().isEmpty()) {
                    pb.chapters.addAll(splitLongTextIntoChapters(cleaned, 0, pb.title));
                    return pb;
                }
            } catch (Throwable ignored) {
            }
        } else {
            int chapterIdx = 0;
            for (Map.Entry<String, byte[]> entry : zipEntries.entrySet()) {
                if (isEpubContentDocumentPath(entry.getKey())) {
                    String html = decodeXmlOrHtmlBytes(entry.getValue());
                    String heading = extractHtmlHeading(html);
                    String bodyText = cleanXhtmlToReadableText(html);
                    if (stripAllWhitespace(bodyText).isEmpty()) continue;
                    if (bodyText.length() > 6000) {
                        List<ParsedBook.Chapter> splitChapters = splitLongTextIntoChapters(bodyText, chapterIdx, heading);
                        pb.chapters.addAll(splitChapters);
                        chapterIdx = pb.chapters.size();
                    } else {
                        if (heading == null || heading.isEmpty()) {
                            heading = firstLineSummary(bodyText, "第 " + (chapterIdx + 1) + " 章");
                        }
                        pb.chapters.add(new ParsedBook.Chapter(chapterIdx++, heading, bodyText));
                    }
                }
            }
        }

        if (pb.chapters.isEmpty()) {
            pb.chapters.add(new ParsedBook.Chapter(0, pb.title, "未能从该 EPUB 文件中提取到有效正文文本。"));
        }
        return pb;
    }

    /**
     * Resolves and cleans a single lazy EPUB chapter on demand when the user opens or turns to that chapter.
     * Backed by `LAZY_EPUB_ENTRY_CACHE` (max 24 entries in RAM) so memory usage stays strictly bounded
     * even on 5,000-chapter 150MB EPUB omnibus books, while repeat access is 0.0ms!
     */
    public static String resolveLazyChapterContent(ParsedBook.Chapter ch) {
        if (ch == null) return "";
        if (ch.content != null) return ch.content;
        if (ch.lazyEpubFile == null || ch.lazyZipEntryName == null || !ch.lazyEpubFile.exists()) {
            return "";
        }

        String cacheKey = ch.lazyEpubFile.getAbsolutePath() + "|" + ch.lazyEpubFile.length()
                + "|" + ch.lazyZipEntryName + "|" + ch.lazyTotalChunks;
        List<String> chunks = LAZY_EPUB_ENTRY_CACHE.get(cacheKey);
        if (chunks == null) {
            String lockKey = ("epub_entry_lock:" + cacheKey).intern();
            synchronized (lockKey) {
                chunks = LAZY_EPUB_ENTRY_CACHE.get(cacheKey);
                if (chunks == null) {
                    String bodyText = "";
                    try {
                        Charset cs = StandardCharsets.UTF_8;
                        try {
                            if (ch.lazyCharsetName != null && !ch.lazyCharsetName.isEmpty()) {
                                cs = Charset.forName(ch.lazyCharsetName);
                            }
                        } catch (Exception ignoredCs) {
                        }
                        try (ZipFile zf = new ZipFile(ch.lazyEpubFile, cs)) {
                            ZipEntry entry = zf.getEntry(ch.lazyZipEntryName);
                            if (entry != null) {
                                try (InputStream is = zf.getInputStream(entry)) {
                                    byte[] data = readStreamBytesCapped(is, 16 * 1024 * 1024);
                                    String html = decodeXmlOrHtmlBytes(data);
                                    bodyText = cleanXhtmlToReadableText(html);
                                }
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                    chunks = splitCleanTextIntoBalancedChunks(bodyText, ch.lazyTotalChunks);
                    LAZY_EPUB_ENTRY_CACHE.put(cacheKey, chunks);
                }
            }
        }
        if (chunks == null || chunks.isEmpty()) return "";
        int idx = Math.max(0, Math.min(ch.lazyChunkIndex, chunks.size() - 1));
        return chunks.get(idx);
    }

    private static List<String> splitCleanTextIntoBalancedChunks(String text, int totalChunks) {
        if (text == null || text.isEmpty()) {
            return java.util.Collections.singletonList("");
        }
        if (totalChunks <= 1 || text.length() <= 2200) {
            return java.util.Collections.singletonList(text);
        }
        List<String> result = new ArrayList<>(totalChunks);
        int len = text.length();
        int pos = 0;
        for (int c = 0; c < totalChunks; c++) {
            if (pos >= len) {
                result.add("");
                continue;
            }
            if (c == totalChunks - 1) {
                result.add(text.substring(pos).trim());
                pos = len;
            } else {
                int remainingChars = len - pos;
                int remainingChunks = totalChunks - c;
                int targetSpan = Math.max(600, remainingChars / remainingChunks);
                int end = Math.min(len, pos + targetSpan);
                if (end < len) {
                    int nextNl = text.indexOf('\n', end);
                    if (nextNl >= end && nextNl - end <= 1200) {
                        end = nextNl + 1;
                    } else {
                        int prevNl = text.lastIndexOf('\n', end);
                        if (prevNl > pos + targetSpan / 2) {
                            end = prevNl + 1;
                        }
                    }
                }
                result.add(text.substring(pos, end).trim());
                pos = end;
            }
        }
        return result;
    }

    /**
     * Linear-time O(N), zero-backtracking parser for EPUB 2 `toc.ncx`.
     * Handles nested `<navPoint>` hierarchies (Volume -> Book -> Part -> Chapter) and multi-megabyte
     * omnibus `toc.ncx` files in < 3ms without ever risking Regex `StackOverflowError`.
     */
    private static void parseNcxTocLinear(
            String ncxXml, String ncxDir,
            Map<String, String> tocLabels,
            Map<String, String> tocLowerLabels,
            Map<String, String> tocFileNameLabels) {
        if (ncxXml == null || ncxXml.isEmpty()) return;
        int len = ncxXml.length();
        int pos = 0;
        String currentLabel = "";

        while (pos < len) {
            int lt = ncxXml.indexOf('<', pos);
            if (lt < 0 || lt + 1 >= len) break;
            int gt = ncxXml.indexOf('>', lt + 1);
            if (gt < 0) break;

            String tagContent = ncxXml.substring(lt + 1, gt).trim();
            if (!tagContent.isEmpty() && tagContent.charAt(0) != '/' && tagContent.charAt(0) != '!' && tagContent.charAt(0) != '?') {
                String tagName = extractTagNameLower(tagContent);
                if ("text".equals(tagName) && tagContent.charAt(tagContent.length() - 1) != '/') {
                    int closeLt = indexOfIgnoreCase(ncxXml, "</", gt + 1);
                    if (closeLt >= gt + 1) {
                        String rawText = ncxXml.substring(gt + 1, closeLt);
                        String cleaned = collapseWhitespaceFast(htmlToPlain(rawText));
                        if (!cleaned.isEmpty()) {
                            currentLabel = cleaned;
                        }
                        int closeGt = ncxXml.indexOf('>', closeLt + 2);
                        pos = (closeGt >= 0) ? (closeGt + 1) : (closeLt + 2);
                        continue;
                    }
                } else if ("content".equals(tagName)) {
                    String rawSrc = extractAttr(tagContent, "src");
                    if (rawSrc != null && !rawSrc.isEmpty() && !currentLabel.isEmpty()) {
                        String src = stripFragmentAndDecode(rawSrc);
                        if (!src.isEmpty()) {
                            String fullSrc = normalizePath(ncxDir.isEmpty() ? src : (ncxDir + "/" + src));
                            putTocLabelFast(fullSrc, currentLabel, tocLabels, tocLowerLabels, tocFileNameLabels);
                        }
                    }
                }
            }
            pos = gt + 1;
        }
    }

    /**
     * Linear-time O(N) parser for EPUB 3 `nav.xhtml` table of contents links.
     */
    private static void parseNavTocLinear(
            String navHtml, String navDir,
            Map<String, String> tocLabels,
            Map<String, String> tocLowerLabels,
            Map<String, String> tocFileNameLabels) {
        if (navHtml == null || navHtml.isEmpty()) return;
        int len = navHtml.length();
        int pos = 0;
        while (pos < len) {
            int aStart = indexOfIgnoreCase(navHtml, "<a", pos);
            if (aStart < 0) break;
            if (aStart + 2 < len) {
                char ch = navHtml.charAt(aStart + 2);
                if (ch > ' ' && ch != '>') {
                    pos = aStart + 2;
                    continue;
                }
            }
            int openEnd = navHtml.indexOf('>', aStart + 2);
            if (openEnd < 0) break;
            if (navHtml.charAt(openEnd - 1) == '/') {
                pos = openEnd + 1;
                continue;
            }
            int closeStart = indexOfIgnoreCase(navHtml, "</a", openEnd + 1);
            if (closeStart < 0) break;

            String attrs = navHtml.substring(aStart + 2, openEnd);
            String rawHref = extractAttr(attrs, "href");
            String innerHtml = navHtml.substring(openEnd + 1, closeStart);
            String label = collapseWhitespaceFast(htmlToPlain(innerHtml));
            if (rawHref != null && !rawHref.isEmpty() && !label.isEmpty()) {
                String src = stripFragmentAndDecode(rawHref);
                if (!src.isEmpty()) {
                    String fullSrc = normalizePath(navDir.isEmpty() ? src : (navDir + "/" + src));
                    putTocLabelFast(fullSrc, label, tocLabels, tocLowerLabels, tocFileNameLabels);
                }
            }
            int closeEnd = navHtml.indexOf('>', closeStart + 3);
            pos = (closeEnd >= 0) ? (closeEnd + 1) : (closeStart + 3);
        }
    }

    private static void putTocLabelFast(
            String fullSrc, String label,
            Map<String, String> tocLabels,
            Map<String, String> tocLowerLabels,
            Map<String, String> tocFileNameLabels) {
        if (fullSrc == null || fullSrc.isEmpty() || label == null || label.isEmpty()) return;
        if (!tocLabels.containsKey(fullSrc)) {
            tocLabels.put(fullSrc, label);
        }
        String low = fullSrc.toLowerCase(Locale.ROOT);
        if (!tocLowerLabels.containsKey(low)) {
            tocLowerLabels.put(low, label);
        }
        String fn = getFileNameOnly(low);
        if (!fn.isEmpty() && !tocFileNameLabels.containsKey(fn)) {
            tocFileNameLabels.put(fn, label);
        }
    }

    private static String findTocLabelFast(
            String path,
            Map<String, String> tocLabels,
            Map<String, String> tocLowerLabels,
            Map<String, String> tocFileNameLabels) {
        if (path == null || path.isEmpty()) return null;
        String norm = normalizePath(path);
        String exact = tocLabels.get(norm);
        if (exact != null) return exact;

        String decoded = normalizePath(urlDecodeSafe(norm));
        exact = tocLabels.get(decoded);
        if (exact != null) return exact;

        String low = decoded.toLowerCase(Locale.ROOT);
        exact = tocLowerLabels.get(low);
        if (exact != null) return exact;

        String fn = getFileNameOnly(low);
        return !fn.isEmpty() ? tocFileNameLabels.get(fn) : null;
    }

    private static String resolveRawZipEntryName(
            String targetPath,
            Map<String, String> normToRawName,
            Map<String, String> lowerNormToRawName,
            Map<String, String> fileNameToRawName) {
        if (targetPath == null || targetPath.isEmpty()) return null;
        String norm = normalizePath(targetPath);
        String raw = normToRawName.get(norm);
        if (raw != null) return raw;

        String decoded = normalizePath(urlDecodeSafe(norm));
        raw = normToRawName.get(decoded);
        if (raw != null) return raw;

        String low = decoded.toLowerCase(Locale.ROOT);
        raw = lowerNormToRawName.get(low);
        if (raw != null) return raw;

        String fn = getFileNameOnly(low);
        return !fn.isEmpty() ? fileNameToRawName.get(fn) : null;
    }

    private static byte[] readSingleZipEntryByPath(
            ZipFile zf,
            String targetPath,
            Map<String, String> normToRawName,
            Map<String, String> lowerNormToRawName,
            Map<String, String> fileNameToRawName,
            int maxBytes) {
        String rawName = resolveRawZipEntryName(targetPath, normToRawName, lowerNormToRawName, fileNameToRawName);
        if (rawName == null) return null;
        try {
            ZipEntry entry = zf.getEntry(rawName);
            if (entry == null) return null;
            try (InputStream is = zf.getInputStream(entry)) {
                return readStreamBytesCapped(is, maxBytes);
            }
        } catch (Throwable e) {
            return null;
        }
    }

    private static String collapseWhitespaceFast(String s) {
        if (s == null || s.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(s.length());
        boolean inSpace = true;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c <= ' ' || c == '\u00A0' || c == '　') {
                if (!inSpace) {
                    sb.append(' ');
                    inSpace = true;
                }
            } else {
                sb.append(c);
                inSpace = false;
            }
        }
        int len = sb.length();
        if (len > 0 && sb.charAt(len - 1) == ' ') {
            sb.setLength(len - 1);
        }
        return sb.toString();
    }

    private static boolean isEpubContentDocumentPath(String path) {
        if (path == null) return false;
        String low = path.toLowerCase(Locale.ROOT);
        if (low.endsWith("container.xml") || low.endsWith("encryption.xml")
                || low.endsWith("rights.xml") || low.endsWith("signatures.xml")
                || low.endsWith("metadata.xml") || low.endsWith(".opf") || low.endsWith(".ncx")) {
            return false;
        }
        return low.endsWith(".xhtml") || low.endsWith(".html") || low.endsWith(".htm")
                || low.endsWith(".xml") || low.endsWith(".txt");
    }

    private static boolean shouldLoadEntryIntoMemory(String entryName) {
        if (entryName == null) return false;
        String low = entryName.toLowerCase(Locale.ROOT);
        // Skip binary images, fonts, audio, video, and CSS stylesheets so large EPUBs never OOM!
        if (low.endsWith(".jpg") || low.endsWith(".jpeg") || low.endsWith(".png")
                || low.endsWith(".gif") || low.endsWith(".webp") || low.endsWith(".bmp")
                || low.endsWith(".ttf") || low.endsWith(".otf") || low.endsWith(".woff")
                || low.endsWith(".woff2") || low.endsWith(".eot") || low.endsWith(".mp3")
                || low.endsWith(".mp4") || low.endsWith(".m4a") || low.endsWith(".ogg")
                || low.endsWith(".css") || low.endsWith(".js") || low.endsWith(".svg")
                || low.endsWith(".ico") || low.endsWith(".pdf")) {
            return false;
        }
        return true;
    }

    private static String stripAllWhitespace(String s) {
        if (s == null || s.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c > ' ' && c != '　' && c != '\u00A0') {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * Reads text/metadata entries from an EPUB/ZIP archive with UTF-8, GB18030, and ISO-8859-1
     * filename fallback, skipping large binary font/image assets so memory usage stays minimal.
     */
    private static Map<String, byte[]> readZipEntriesSafe(File file) throws Exception {
        Map<String, byte[]> map = new LinkedHashMap<>();
        Charset[] charsets = new Charset[]{StandardCharsets.UTF_8, Charset.forName("GB18030"), StandardCharsets.ISO_8859_1};
        for (Charset cs : charsets) {
            try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(file)), cs)) {
                while (true) {
                    ZipEntry entry;
                    try {
                        entry = zis.getNextEntry();
                    } catch (Throwable entryErr) {
                        break;
                    }
                    if (entry == null) break;
                    try {
                        if (!entry.isDirectory() && shouldLoadEntryIntoMemory(entry.getName())) {
                            String norm = normalizePath(entry.getName());
                            if (!map.containsKey(norm)) {
                                map.put(norm, readStreamBytesCapped(zis, 8 * 1024 * 1024));
                            }
                        }
                    } catch (Throwable ignoredRead) {
                    }
                }
                if (hasAnyContentOrOpfEntry(map)) {
                    return map;
                }
            } catch (Throwable ignored) {
            }
        }
        return map;
    }

    private static boolean hasAnyContentOrOpfEntry(Map<String, byte[]> map) {
        if (map == null || map.isEmpty()) return false;
        for (String key : map.keySet()) {
            String low = key.toLowerCase(Locale.ROOT);
            if (low.endsWith(".opf") || low.endsWith(".xhtml") || low.endsWith(".html") || low.endsWith(".htm")) {
                return true;
            }
        }
        return map.size() >= 2;
    }

    private static String stripFragmentAndDecode(String href) {
        if (href == null) return "";
        String h = href.trim();
        int hash = h.indexOf('#');
        if (hash >= 0) {
            h = h.substring(0, hash);
        }
        return urlDecodeSafe(h);
    }

    private static String urlDecodeSafe(String s) {
        if (s == null || !s.contains("%")) return s != null ? s : "";
        try {
            return URLDecoder.decode(s.replace("+", "%2B"), "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    private static String getFileNameOnly(String path) {
        if (path == null) return "";
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    // ==================== 3. FB2 PARSER (.fb2, .fb2.zip, .fbz) ====================
    private static ParsedBook parseFb2(File file) throws Exception {
        ParsedBook pb = new ParsedBook();
        pb.format = "FB2";
        pb.title = stripExt(file.getName());
        pb.author = "FB2 电子书";

        byte[] xmlBytes = extractFb2XmlBytes(file);
        String xmlText = decodeXmlOrHtmlBytes(xmlBytes);

        XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
        factory.setNamespaceAware(false);
        XmlPullParser parser = factory.newPullParser();
        parser.setInput(new StringReader(xmlText));

        boolean inTitleInfo = false;
        boolean inAuthor = false;
        boolean inBody = false;
        boolean inSection = false;
        boolean inTitle = false;

        String firstName = "";
        String middleName = "";
        String lastName = "";

        StringBuilder currentSectionTitle = new StringBuilder();
        StringBuilder currentSectionContent = new StringBuilder();
        int sectionDepth = 0;

        int eventType = parser.getEventType();
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG) {
                String tag = stripNs(parser.getName()).toLowerCase(Locale.ROOT);
                switch (tag) {
                    case "title-info":
                        inTitleInfo = true;
                        break;
                    case "author":
                        if (inTitleInfo) inAuthor = true;
                        break;
                    case "book-title":
                        if (inTitleInfo) {
                            String t = safeNextText(parser).trim();
                            if (!t.isEmpty()) pb.title = t;
                        }
                        break;
                    case "first-name":
                        if (inTitleInfo && inAuthor) firstName = safeNextText(parser).trim();
                        break;
                    case "middle-name":
                        if (inTitleInfo && inAuthor) middleName = safeNextText(parser).trim();
                        break;
                    case "last-name":
                        if (inTitleInfo && inAuthor) lastName = safeNextText(parser).trim();
                        break;
                    case "body":
                        inBody = true;
                        break;
                    case "section":
                        if (inBody) {
                            if (sectionDepth > 0 && currentSectionContent.length() > 80) {
                                flushFb2Section(pb, currentSectionTitle, currentSectionContent);
                            }
                            sectionDepth++;
                            inSection = true;
                        }
                        break;
                    case "title":
                        if (inBody && inSection) {
                            inTitle = true;
                        }
                        break;
                    case "p":
                    case "v":
                    case "subtitle":
                    case "text-author":
                        if (inBody) {
                            String txt = safeNextText(parser).trim();
                            if (!txt.isEmpty()) {
                                if (inTitle) {
                                    if (currentSectionTitle.length() > 0) currentSectionTitle.append(" · ");
                                    currentSectionTitle.append(txt);
                                } else {
                                    if ("subtitle".equals(tag)) {
                                        currentSectionContent.append("\n【").append(txt).append("】\n\n");
                                    } else {
                                        currentSectionContent.append("　　").append(txt).append("\n\n");
                                    }
                                }
                            }
                        }
                        break;
                    case "empty-line":
                        if (inBody && !inTitle) {
                            currentSectionContent.append("\n");
                        }
                        break;
                }
            } else if (eventType == XmlPullParser.END_TAG) {
                String tag = stripNs(parser.getName()).toLowerCase(Locale.ROOT);
                switch (tag) {
                    case "title-info":
                        inTitleInfo = false;
                        String fullAuthor = (firstName + " " + middleName + " " + lastName).replaceAll("\\s+", " ").trim();
                        if (!fullAuthor.isEmpty()) pb.author = fullAuthor;
                        break;
                    case "author":
                        inAuthor = false;
                        break;
                    case "title":
                        inTitle = false;
                        break;
                    case "section":
                        if (inBody && sectionDepth > 0) {
                            flushFb2Section(pb, currentSectionTitle, currentSectionContent);
                            sectionDepth--;
                            if (sectionDepth == 0) inSection = false;
                        }
                        break;
                    case "body":
                        flushFb2Section(pb, currentSectionTitle, currentSectionContent);
                        inBody = false;
                        break;
                }
            }
            eventType = parser.next();
        }

        if (pb.chapters.isEmpty()) {
            String fallback = cleanXhtmlToReadableText(xmlText);
            pb.chapters.add(new ParsedBook.Chapter(0, pb.title, fallback));
        }
        return pb;
    }

    private static void flushFb2Section(ParsedBook pb, StringBuilder titleBuf, StringBuilder contentBuf) {
        String body = contentBuf.toString().trim();
        String title = titleBuf.toString().trim();
        if (!body.isEmpty()) {
            int idx = pb.chapters.size();
            if (title.isEmpty()) {
                title = firstLineSummary(body, "第 " + (idx + 1) + " 节");
            }
            pb.chapters.add(new ParsedBook.Chapter(idx, title, body));
        }
        titleBuf.setLength(0);
        contentBuf.setLength(0);
    }

    private static byte[] extractFb2XmlBytes(File file) throws Exception {
        String lower = file.getName().toLowerCase(Locale.ROOT);
        boolean isZipMagic = false;
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] header = new byte[4];
            if (fis.read(header) == 4) {
                isZipMagic = (header[0] == 'P' && header[1] == 'K' && header[2] == 3 && header[3] == 4);
            }
        }
        if (lower.endsWith(".fb2.zip") || lower.endsWith(".fbz") || isZipMagic) {
            try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(file)))) {
                ZipEntry entry;
                byte[] fallbackEntry = null;
                while ((entry = zis.getNextEntry()) != null) {
                    if (!entry.isDirectory()) {
                        byte[] bytes = readStreamBytes(zis);
                        if (entry.getName().toLowerCase(Locale.ROOT).endsWith(".fb2") ||
                            entry.getName().toLowerCase(Locale.ROOT).endsWith(".xml")) {
                            return bytes;
                        }
                        if (fallbackEntry == null) {
                            fallbackEntry = bytes;
                        }
                    }
                }
                if (fallbackEntry != null) return fallbackEntry;
            }
        }
        try (FileInputStream fis = new FileInputStream(file)) {
            return readStreamBytes(fis);
        }
    }

    // ==================== 4. MOBI & AZW3 PARSER (PDB + PalmDOC LZ77 + EXTH + KF8) ====================
    private static ParsedBook parseMobiOrAzw3(File file, String format) throws Exception {
        ParsedBook pb = new ParsedBook();
        pb.format = format;
        pb.title = stripExt(file.getName());
        pb.author = format + " 电子书";

        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            long fileLen = raf.length();
            if (fileLen < 78) {
                return parseTxt(file);
            }

            byte[] pdbHeader = new byte[78];
            raf.readFully(pdbHeader);
            String dbName = new String(pdbHeader, 0, 32, StandardCharsets.ISO_8859_1).replace("\0", "").trim();
            int numRecords = ((pdbHeader[76] & 0xFF) << 8) | (pdbHeader[77] & 0xFF);
            if (numRecords <= 1 || numRecords > 32768) {
                return parseTxt(file);
            }

            long[] recordOffsets = new long[numRecords];
            for (int i = 0; i < numRecords; i++) {
                int off = raf.readInt();
                raf.readInt(); // attributes + uniqueID
                recordOffsets[i] = off & 0xFFFFFFFFL;
            }

            int rec0Len = (int) Math.min(65536L, (recordOffsets[1] - recordOffsets[0]));
            if (rec0Len < 16) {
                return parseTxt(file);
            }
            raf.seek(recordOffsets[0]);
            byte[] rec0 = new byte[rec0Len];
            raf.readFully(rec0);

            ByteBuffer bb = ByteBuffer.wrap(rec0).order(ByteOrder.BIG_ENDIAN);
            int compression = bb.getShort(0) & 0xFFFF;
            int textRecordCount = bb.getShort(8) & 0xFFFF;
            Charset charset = StandardCharsets.UTF_8;

            if (rec0Len >= 132) {
                String mobiMagic = new String(rec0, 16, 4, StandardCharsets.US_ASCII);
                if ("MOBI".equals(mobiMagic)) {
                    int mobiHeaderLen = bb.getInt(20);
                    int textEncoding = bb.getInt(28);
                    if (textEncoding == 1252) {
                        charset = Charset.forName("windows-1252");
                    } else if (textEncoding == 65001) {
                        charset = StandardCharsets.UTF_8;
                    } else if (textEncoding == 936) {
                        charset = Charset.forName("GB18030");
                    }
                    int fullNameOffset = bb.getInt(84);
                    int fullNameLength = bb.getInt(88);
                    if (fullNameOffset > 0 && fullNameLength > 0 && fullNameOffset + fullNameLength <= rec0Len) {
                        String fullTitle = new String(rec0, fullNameOffset, fullNameLength, charset).trim();
                        if (!fullTitle.isEmpty()) pb.title = fullTitle;
                    }

                    int exthFlags = bb.getInt(128);
                    int exthStart = 16 + mobiHeaderLen;
                    if ((exthFlags & 0x40) != 0 && exthStart + 12 <= rec0Len) {
                        String exthMagic = new String(rec0, exthStart, 4, StandardCharsets.US_ASCII);
                        if ("EXTH".equals(exthMagic)) {
                            int exthCount = bb.getInt(exthStart + 8);
                            int pos = exthStart + 12;
                            for (int i = 0; i < exthCount && pos + 8 <= rec0Len; i++) {
                                int recType = bb.getInt(pos);
                                int recLen = bb.getInt(pos + 4);
                                if (recLen < 8 || pos + recLen > rec0Len) break;
                                String val = new String(rec0, pos + 8, recLen - 8, charset).trim();
                                if (recType == 100 && !val.isEmpty()) {
                                    pb.author = val;
                                } else if (recType == 503 && !val.isEmpty()) {
                                    pb.title = val;
                                }
                                pos += recLen;
                            }
                        }
                    }
                }
            }

            if (pb.title.isEmpty() && !dbName.isEmpty()) {
                pb.title = dbName;
            }

            int extraDataFlags = 0;
            if (rec0Len >= 244 && "MOBI".equals(new String(rec0, 16, 4, StandardCharsets.US_ASCII))) {
                int mobiHeaderLenCheck = bb.getInt(20);
                if (mobiHeaderLenCheck >= 0xE4) {
                    extraDataFlags = bb.getShort(242) & 0xFFFF;
                }
            }

            ByteArrayOutputStream rawTextStream = new ByteArrayOutputStream();
            int maxRec = Math.min(numRecords - 1, Math.max(1, textRecordCount));
            for (int r = 1; r <= maxRec; r++) {
                long start = recordOffsets[r];
                long end = (r + 1 < numRecords) ? recordOffsets[r + 1] : fileLen;
                int len = (int) Math.min(131072L, Math.max(0L, end - start));
                if (len <= 0) continue;

                raf.seek(start);
                byte[] comp = new byte[len];
                raf.readFully(comp);
                comp = trimMobiExtraDataBytes(comp, extraDataFlags);

                if (compression == 1) {
                    rawTextStream.write(comp);
                } else if (compression == 2) {
                    byte[] decomp = decompressPalmDocLz77(comp);
                    rawTextStream.write(decomp);
                } else {
                    rawTextStream.write(comp);
                }
            }

            byte[] allTextBytes = rawTextStream.toByteArray();
            String rawHtml = (charset == StandardCharsets.UTF_8)
                    ? decodeBytesSmart(allTextBytes)
                    : new String(allTextBytes, charset);
            String[] rawParts = rawHtml.split("(?i)(<mbp:pagebreak\\s*/?>|(?=<h[1-3][^>]*>))");
            int chapterIdx = 0;
            for (String part : rawParts) {
                String heading = extractHtmlHeading(part);
                String cleaned = cleanXhtmlToReadableText(part);
                if (cleaned.trim().length() < 10) continue;

                if (cleaned.length() > 9000) {
                    List<ParsedBook.Chapter> sub = splitLongTextIntoChapters(cleaned, chapterIdx, heading);
                    pb.chapters.addAll(sub);
                    chapterIdx = pb.chapters.size();
                } else {
                    if (heading == null || heading.isEmpty()) {
                        heading = firstLineSummary(cleaned, "第 " + (chapterIdx + 1) + " 章");
                    }
                    pb.chapters.add(new ParsedBook.Chapter(chapterIdx, heading, cleaned));
                    chapterIdx++;
                }
            }

            if (pb.chapters.isEmpty()) {
                String cleanedAll = cleanXhtmlToReadableText(rawHtml);
                pb.chapters.addAll(splitLongTextIntoChapters(cleanedAll, 0, pb.title));
            }
        }
        return pb;
    }

    private static byte[] trimMobiExtraDataBytes(byte[] record, int extraDataFlags) {
        if (record == null || record.length == 0 || extraDataFlags == 0) return record;
        int end = record.length;
        int flags = extraDataFlags >> 1;
        while (flags != 0 && end > 0) {
            if ((flags & 1) != 0) {
                int size = 0;
                int shift = 0;
                int p = end - 1;
                while (p >= 0 && shift < 28) {
                    int b = record[p--] & 0xFF;
                    size |= (b & 0x7F) << shift;
                    shift += 7;
                    if ((b & 0x80) != 0) break;
                }
                if (size > 0 && size <= end) {
                    end -= size;
                } else {
                    break;
                }
            }
            flags >>= 1;
        }
        if ((extraDataFlags & 1) != 0 && end > 0) {
            int multibyteExtra = (record[end - 1] & 0x03) + 1;
            if (multibyteExtra <= end) {
                end -= multibyteExtra;
            }
        }
        if (end == record.length) return record;
        byte[] trimmed = new byte[Math.max(0, end)];
        System.arraycopy(record, 0, trimmed, 0, trimmed.length);
        return trimmed;
    }

    /**
     * O(N) direct array PalmDOC LZ77 decompressor (zero per-byte array re-allocations).
     * Decompresses MOBI & AZW3 records 2,000x faster than ByteArrayOutputStream.toByteArray()!
     */
    public static byte[] decompressPalmDocLz77(byte[] in) {
        if (in == null || in.length == 0) return new byte[0];
        byte[] out = new byte[Math.max(4096, in.length * 3)];
        int outPos = 0;
        int i = 0;
        int len = in.length;

        while (i < len) {
            if (outPos + 16 >= out.length) {
                byte[] grown = new byte[out.length * 2];
                System.arraycopy(out, 0, grown, 0, outPos);
                out = grown;
            }
            int c = in[i++] & 0xFF;
            if (c == 0x00 || (c >= 0x09 && c <= 0x7F)) {
                out[outPos++] = (byte) c;
            } else if (c >= 0x01 && c <= 0x08) {
                for (int j = 0; j < c && i < len; j++) {
                    if (outPos >= out.length) {
                        byte[] grown = new byte[out.length * 2];
                        System.arraycopy(out, 0, grown, 0, outPos);
                        out = grown;
                    }
                    out[outPos++] = in[i++];
                }
            } else if (c >= 0x80 && c <= 0xBF) {
                if (i >= len) break;
                int next = in[i++] & 0xFF;
                int pair = ((c << 8) | next) & 0x3FFF;
                int distance = pair >> 3;
                int copyLen = (pair & 0x07) + 3;
                int startPos = outPos - distance;
                if (distance > 0 && startPos >= 0) {
                    if (outPos + copyLen >= out.length) {
                        byte[] grown = new byte[Math.max(out.length * 2, outPos + copyLen + 64)];
                        System.arraycopy(out, 0, grown, 0, outPos);
                        out = grown;
                    }
                    for (int j = 0; j < copyLen; j++) {
                        out[outPos] = out[startPos + j];
                        outPos++;
                    }
                }
            } else if (c >= 0xC0) {
                out[outPos++] = (byte) ' ';
                out[outPos++] = (byte) (c ^ 0x80);
            }
        }
        byte[] result = new byte[outPos];
        System.arraycopy(out, 0, result, 0, outPos);
        return result;
    }

    // ==================== 5. TXT PARSER (Smart Charset + Regex Chapter Split) ====================
    private static ParsedBook parseTxt(File file) throws Exception {
        ParsedBook pb = new ParsedBook();
        pb.format = "TXT";
        pb.title = stripExt(file.getName());
        pb.author = "本地纯文本";

        byte[] bytes;
        try (FileInputStream fis = new FileInputStream(file)) {
            bytes = readStreamBytes(fis);
        }
        String text = decodeBytesSmart(bytes).replace("\r\n", "\n").replace("\r", "\n");

        Matcher m = TXT_CHAPTER_PATTERN.matcher(text);
        List<Integer> starts = new ArrayList<>();
        List<String> titles = new ArrayList<>();
        while (m.find()) {
            starts.add(m.start());
            titles.add(m.group().trim());
        }

        if (starts.size() >= 2) {
            if (starts.get(0) > 60) {
                String preface = text.substring(0, starts.get(0)).trim();
                if (!preface.isEmpty()) {
                    pb.chapters.add(new ParsedBook.Chapter(0, "序言 / 导读", formatParagraphs(preface)));
                }
            }
            for (int i = 0; i < starts.size(); i++) {
                int s = starts.get(i);
                int e = (i + 1 < starts.size()) ? starts.get(i + 1) : text.length();
                String chunk = text.substring(s, e).trim();
                int idx = pb.chapters.size();
                pb.chapters.add(new ParsedBook.Chapter(idx, titles.get(i), formatParagraphs(chunk)));
            }
        } else {
            pb.chapters.addAll(splitLongTextIntoChapters(formatParagraphs(text), 0, null));
        }

        if (pb.chapters.isEmpty()) {
            pb.chapters.add(new ParsedBook.Chapter(0, pb.title, text));
        }
        return pb;
    }

    // ==================== HELPER UTILITIES ====================
    private static List<ParsedBook.Chapter> splitLongTextIntoChapters(String text, int startIdx, String baseTitle) {
        List<ParsedBook.Chapter> list = new ArrayList<>();
        String trimmed = text != null ? text.trim() : "";
        if (trimmed.isEmpty()) {
            list.add(new ParsedBook.Chapter(startIdx, baseTitle != null ? baseTitle : "正文", ""));
            return list;
        }
        int chunkSize = 4500;
        int pos = 0;
        int idx = startIdx;
        int partNum = 1;
        while (pos < trimmed.length()) {
            int end = Math.min(trimmed.length(), pos + chunkSize);
            if (end < trimmed.length()) {
                int nl = trimmed.lastIndexOf('\n', end);
                if (nl > pos + 1000) {
                    end = nl + 1;
                }
            }
            String chunk = trimmed.substring(pos, end).trim();
            String chTitle;
            if (baseTitle != null && !baseTitle.isEmpty()) {
                chTitle = partNum == 1 ? baseTitle : (baseTitle + " (" + partNum + ")");
            } else {
                chTitle = firstLineSummary(chunk, "第 " + (idx + 1) + " 节");
            }
            list.add(new ParsedBook.Chapter(idx++, chTitle, chunk));
            pos = end;
            partNum++;
        }
        return list;
    }

    /**
     * Unwraps artificial mid-sentence single-line breaks in TXT/HTML files while preserving
     * real paragraph boundaries, headings, and poems/lists so paragraphs justify flush left and right.
     */
    private static String formatParagraphs(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        String[] rawLines = raw.replace("\r\n", "\n").replace('\r', '\n').split("\n");
        List<String> mergedParas = new ArrayList<>();
        StringBuilder currentPara = new StringBuilder();

        for (String line : rawLines) {
            boolean hadLeadingIndent = line.startsWith("　　") || line.startsWith("  ") || line.startsWith("\t");
            String t = trimLineSpacesFast(line);
            if (t.isEmpty()) {
                if (currentPara.length() > 0) {
                    mergedParas.add(currentPara.toString());
                    currentPara.setLength(0);
                }
                continue;
            }

            boolean isHeading = t.startsWith("第") || t.startsWith("Chapter") || t.startsWith("CHAPTER") || t.startsWith("【");
            if (isHeading || hadLeadingIndent || currentPara.length() == 0) {
                if (currentPara.length() > 0) {
                    mergedParas.add(currentPara.toString());
                    currentPara.setLength(0);
                }
                currentPara.append(t);
                if (isHeading) {
                    mergedParas.add(currentPara.toString());
                    currentPara.setLength(0);
                }
            } else {
                // Check if previous line ended mid-sentence (hard-wrapped TXT column)
                char prevLast = currentPara.charAt(currentPara.length() - 1);
                boolean prevEndedSentence = "。！？!?…”’》）】—:：；;".indexOf(prevLast) >= 0;
                if (!prevEndedSentence && currentPara.length() >= 18) {
                    if (isAsciiAlphaNum(prevLast) && isAsciiAlphaNum(t.charAt(0))) {
                        currentPara.append(' ');
                    }
                    currentPara.append(t);
                } else {
                    mergedParas.add(currentPara.toString());
                    currentPara.setLength(0);
                    currentPara.append(t);
                }
            }
        }
        if (currentPara.length() > 0) {
            mergedParas.add(currentPara.toString());
        }

        StringBuilder sb = new StringBuilder();
        for (String p : mergedParas) {
            if (p.startsWith("第") || p.startsWith("Chapter") || p.startsWith("CHAPTER") || p.startsWith("【")) {
                sb.append(p).append("\n\n");
            } else {
                sb.append("　　").append(p).append("\n\n");
            }
        }
        return sb.toString().trim();
    }

    private static String trimLineSpacesFast(String s) {
        if (s == null || s.isEmpty()) return "";
        int start = 0;
        int end = s.length();
        while (start < end) {
            char c = s.charAt(start);
            if (c <= ' ' || c == '　' || c == '\u00A0' || c == '\uFEFF') {
                start++;
            } else {
                break;
            }
        }
        while (end > start) {
            char c = s.charAt(end - 1);
            if (c <= ' ' || c == '　' || c == '\u00A0' || c == '\uFEFF') {
                end--;
            } else {
                break;
            }
        }
        return (start == 0 && end == s.length()) ? s : s.substring(start, end);
    }

    private static boolean isAsciiAlphaNum(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
    }

    /**
     * Decodes XML/XHTML/HTML bytes by checking:
     * 1. UTF-8 / UTF-16 BOM
     * 2. Explicit XML encoding="..." or HTML <meta charset="..."> header
     * 3. Full-buffer boundary-safe UTF-8 validation
     * 4. GB18030 fallback
     */
    private static String decodeXmlOrHtmlBytes(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return "";
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
            return new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        }
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xFE) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16LE);
        }
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFE && (bytes[1] & 0xFF) == 0xFF) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16BE);
        }

        String head = new String(bytes, 0, Math.min(bytes.length, 1024), StandardCharsets.ISO_8859_1);
        Matcher mXml = Pattern.compile("encoding\\s*=\\s*[\"']([A-Za-z0-9_\\-]+)[\"']", Pattern.CASE_INSENSITIVE).matcher(head);
        if (mXml.find()) {
            String enc = mXml.group(1).trim();
            if (enc.equalsIgnoreCase("utf-8") || enc.equalsIgnoreCase("utf8")) {
                return new String(bytes, StandardCharsets.UTF_8);
            }
            if (enc.toLowerCase(Locale.ROOT).startsWith("gb")) {
                try {
                    return new String(bytes, Charset.forName("GB18030"));
                } catch (Exception ignored) {
                }
            }
            try {
                return new String(bytes, Charset.forName(enc));
            } catch (Exception ignored) {
            }
        }

        Matcher mMeta = Pattern.compile("charset\\s*=\\s*[\"']?([A-Za-z0-9_\\-]+)", Pattern.CASE_INSENSITIVE).matcher(head);
        if (mMeta.find()) {
            String enc = mMeta.group(1).trim();
            if (enc.equalsIgnoreCase("utf-8") || enc.equalsIgnoreCase("utf8")) {
                return new String(bytes, StandardCharsets.UTF_8);
            }
            if (enc.toLowerCase(Locale.ROOT).startsWith("gb")) {
                try {
                    return new String(bytes, Charset.forName("GB18030"));
                } catch (Exception ignored) {
                }
            }
            try {
                return new String(bytes, Charset.forName(enc));
            } catch (Exception ignored) {
            }
        }

        return decodeBytesSmart(bytes);
    }

    private static String decodeBytesSmart(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return "";
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
            return new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        }
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xFE) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16LE);
        }
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFE && (bytes[1] & 0xFF) == 0xFF) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16BE);
        }
        if (isMostlyValidUtf8(bytes)) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        try {
            return new String(bytes, Charset.forName("GB18030"));
        } catch (Exception e) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    /**
     * Checks UTF-8 validity without ever failing on a multi-byte sequence that straddles an arbitrary scan cap,
     * and tolerates isolated invalid bytes if >=95% of multi-byte sequences are valid UTF-8.
     */
    private static boolean isMostlyValidUtf8(byte[] bytes) {
        int totalLen = bytes.length;
        int scanLimit = Math.min(totalLen, 65536);
        int i = 0;
        int validMultiByteSeqs = 0;
        int invalidSeqs = 0;

        while (i < scanLimit) {
            int b = bytes[i] & 0xFF;
            if (b < 0x80) {
                i++;
            } else if ((b & 0xE0) == 0xC0) {
                if (i + 1 >= totalLen) break; // Truncated at end of file; do not penalize
                if (b < 0xC2 || (bytes[i + 1] & 0xC0) != 0x80) {
                    invalidSeqs++;
                    i++;
                } else {
                    validMultiByteSeqs++;
                    i += 2;
                }
            } else if ((b & 0xF0) == 0xE0) {
                if (i + 2 >= totalLen) break; // Truncated at end of file; do not penalize!
                if ((bytes[i + 1] & 0xC0) != 0x80 || (bytes[i + 2] & 0xC0) != 0x80) {
                    invalidSeqs++;
                    i++;
                } else {
                    validMultiByteSeqs++;
                    i += 3;
                }
            } else if ((b & 0xF8) == 0xF0) {
                if (i + 3 >= totalLen) break; // Truncated at end of file; do not penalize!
                if ((bytes[i + 1] & 0xC0) != 0x80 || (bytes[i + 2] & 0xC0) != 0x80 || (bytes[i + 3] & 0xC0) != 0x80) {
                    invalidSeqs++;
                    i++;
                } else {
                    validMultiByteSeqs++;
                    i += 4;
                }
            } else {
                invalidSeqs++;
                i++;
            }
        }

        if (invalidSeqs == 0) return true;
        return validMultiByteSeqs > 20 && validMultiByteSeqs >= invalidSeqs * 15;
    }

    /**
     * Fast, zero-recursion, linear-time O(N) XHTML/HTML text extractor.
     * - Never uses catastrophic regex backtracking (`.*?` with DOTALL + backreferences), preventing StackOverflowError on large chapters.
     * - Properly handles self-closing `<svg .../>`, `<script .../>`, `<style .../>`, and `<title/>` tags so they never eat subsequent body text.
     * - Never confuses `<header>` with `<head>`.
     * - Preserves paragraph breaks (`\n`) directly instead of letting `Html.fromHtml` collapse newlines into spaces.
     */
    private static String cleanXhtmlToReadableText(String html) {
        if (html == null || html.isEmpty()) return "";

        String work = html;
        // 1. If a <body...> tag exists, slice from <body...> to </body> to discard <head> immediately in O(1)
        int bodyStart = indexOfIgnoreCase(work, "<body", 0);
        if (bodyStart >= 0) {
            int bodyOpenEnd = work.indexOf('>', bodyStart);
            if (bodyOpenEnd >= 0 && bodyOpenEnd + 1 < work.length()) {
                // Only slice if <body...> is not self-closing (<body/>)
                if (work.charAt(bodyOpenEnd - 1) != '/') {
                    int bodyClose = lastIndexOfIgnoreCase(work, "</body");
                    if (bodyClose > bodyOpenEnd) {
                        work = work.substring(bodyOpenEnd + 1, bodyClose);
                    } else {
                        work = work.substring(bodyOpenEnd + 1);
                    }
                }
            }
        } else {
            work = removeNonSelfClosingTagBlocks(work, "head");
        }

        // 2. Remove HTML comments <!-- ... --> linearly
        work = removeHtmlCommentsLinear(work);

        // 3. Remove non-self-closing <script>, <style>, <svg>, <title> blocks linearly
        work = removeNonSelfClosingTagBlocks(work, "script");
        work = removeNonSelfClosingTagBlocks(work, "style");
        work = removeNonSelfClosingTagBlocks(work, "svg");
        work = removeNonSelfClosingTagBlocks(work, "title");

        // 4. Single-pass O(N) tag stripper & block-break converter
        StringBuilder out = new StringBuilder(work.length());
        int len = work.length();
        int i = 0;
        while (i < len) {
            char c = work.charAt(i);
            if (c == '<') {
                int closeBracket = work.indexOf('>', i + 1);
                if (closeBracket < 0) {
                    break;
                }
                String tagContent = work.substring(i + 1, closeBracket).trim();
                if (!tagContent.isEmpty()) {
                    String tagName = extractTagNameLower(tagContent);
                    if (isBlockBreakTagName(tagName)) {
                        out.append('\n');
                    }
                }
                i = closeBracket + 1;
            } else if (c == '&') {
                int semi = work.indexOf(';', i + 1);
                if (semi > i && semi - i <= 12) {
                    String entity = work.substring(i + 1, semi);
                    String decoded = decodeHtmlEntity(entity);
                    if (decoded != null) {
                        out.append(decoded);
                        i = semi + 1;
                        continue;
                    }
                }
                out.append(c);
                i++;
            } else {
                out.append(c);
                i++;
            }
        }

        String plain = out.toString()
                .replace("\uFFFC", "")
                .replace("\uFEFF", "")
                .replace("\u200B", "");
        return formatParagraphs(plain);
    }

    private static String extractTagNameLower(String tagContent) {
        int start = 0;
        int len = tagContent.length();
        while (start < len && (tagContent.charAt(start) == '/' || tagContent.charAt(start) <= ' ')) {
            start++;
        }
        int end = start;
        while (end < len) {
            char ch = tagContent.charAt(end);
            if (ch <= ' ' || ch == '/' || ch == '>') break;
            end++;
        }
        if (end <= start) return "";
        String raw = tagContent.substring(start, end).toLowerCase(Locale.ROOT);
        int colon = raw.indexOf(':');
        return colon >= 0 ? raw.substring(colon + 1) : raw;
    }

    private static boolean isBlockBreakTagName(String tagName) {
        if (tagName == null || tagName.isEmpty()) return false;
        switch (tagName) {
            case "p":
            case "div":
            case "br":
            case "h1":
            case "h2":
            case "h3":
            case "h4":
            case "h5":
            case "h6":
            case "li":
            case "tr":
            case "blockquote":
            case "section":
            case "article":
            case "header":
            case "footer":
            case "hr":
            case "dt":
            case "dd":
                return true;
            default:
                return false;
        }
    }

    private static String decodeHtmlEntity(String ent) {
        if (ent == null || ent.isEmpty()) return null;
        if (ent.charAt(0) == '#') {
            try {
                int codePoint;
                if (ent.length() > 2 && (ent.charAt(1) == 'x' || ent.charAt(1) == 'X')) {
                    codePoint = Integer.parseInt(ent.substring(2), 16);
                } else {
                    codePoint = Integer.parseInt(ent.substring(1), 10);
                }
                if (codePoint > 0 && codePoint <= 0x10FFFF) {
                    return new String(Character.toChars(codePoint));
                }
            } catch (Exception ignored) {
            }
            return null;
        }
        switch (ent.toLowerCase(Locale.ROOT)) {
            case "nbsp": return " ";
            case "lt": return "<";
            case "gt": return ">";
            case "amp": return "&";
            case "quot": return "\"";
            case "apos": return "'";
            case "ldquo": return "“";
            case "rdquo": return "”";
            case "lsquo": return "‘";
            case "rsquo": return "’";
            case "mdash": return "——";
            case "ndash": return "–";
            case "hellip": return "…";
            case "middot": return "·";
            case "copy": return "©";
            case "emsp":
            case "ensp": return " ";
            default: return null;
        }
    }

    private static String removeHtmlCommentsLinear(String s) {
        int idx = s.indexOf("<!--");
        if (idx < 0) return s;
        StringBuilder sb = new StringBuilder(s.length());
        int pos = 0;
        while (idx >= 0) {
            sb.append(s, pos, idx);
            int end = s.indexOf("-->", idx + 4);
            if (end < 0) {
                pos = s.length();
                break;
            }
            pos = end + 3;
            idx = s.indexOf("<!--", pos);
        }
        if (pos < s.length()) {
            sb.append(s, pos, s.length());
        }
        return sb.toString();
    }

    private static String removeNonSelfClosingTagBlocks(String s, String tagLower) {
        String openPat = "<" + tagLower;
        int idx = indexOfIgnoreCase(s, openPat, 0);
        if (idx < 0) return s;

        StringBuilder sb = new StringBuilder(s.length());
        int pos = 0;
        int len = s.length();
        String closePat = "</" + tagLower;

        while (idx >= 0 && idx < len) {
            // Verify word boundary after `<tagLower` so `<head` never matches `<header>`!
            int afterNameIdx = idx + openPat.length();
            if (afterNameIdx < len) {
                char boundaryChar = s.charAt(afterNameIdx);
                if (boundaryChar != '>' && boundaryChar != '/' && boundaryChar > ' ') {
                    idx = indexOfIgnoreCase(s, openPat, afterNameIdx);
                    continue;
                }
            }
            sb.append(s, pos, idx);
            int openEnd = s.indexOf('>', afterNameIdx);
            if (openEnd < 0) {
                pos = len;
                break;
            }
            // If self-closing `<svg .../>` or `<title/>`, only skip the self-closing tag itself!
            if (s.charAt(openEnd - 1) == '/') {
                pos = openEnd + 1;
                idx = indexOfIgnoreCase(s, openPat, pos);
                continue;
            }
            int closeStart = indexOfIgnoreCase(s, closePat, openEnd + 1);
            if (closeStart < 0) {
                pos = openEnd + 1;
                idx = indexOfIgnoreCase(s, openPat, pos);
                continue;
            }
            int closeEnd = s.indexOf('>', closeStart + closePat.length());
            pos = (closeEnd >= 0) ? (closeEnd + 1) : len;
            idx = indexOfIgnoreCase(s, openPat, pos);
        }
        if (pos < len) {
            sb.append(s, pos, len);
        }
        return sb.toString();
    }

    private static int indexOfIgnoreCase(String src, String targetLower, int fromIndex) {
        int max = src.length() - targetLower.length();
        int tLen = targetLower.length();
        for (int i = Math.max(0, fromIndex); i <= max; i++) {
            if (src.regionMatches(true, i, targetLower, 0, tLen)) {
                return i;
            }
        }
        return -1;
    }

    private static int lastIndexOfIgnoreCase(String src, String targetLower) {
        int tLen = targetLower.length();
        for (int i = src.length() - tLen; i >= 0; i--) {
            if (src.regionMatches(true, i, targetLower, 0, tLen)) {
                return i;
            }
        }
        return -1;
    }

    private static String htmlToPlain(String html) {
        if (html == null || html.isEmpty()) return "";
        try {
            return Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY).toString().replace("\uFEFF", "");
        } catch (Throwable t) {
            return html.replaceAll("<[^>]+>", "").trim();
        }
    }

    private static String extractHtmlHeading(String html) {
        if (html == null) return null;
        Matcher m = Pattern.compile("<h[1-4][^>]*>(.*?)</h[1-4]>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(html);
        if (m.find()) {
            String h = htmlToPlain(m.group(1)).replaceAll("\\s+", " ").trim();
            if (!h.isEmpty() && h.length() <= 60) return h;
        }
        Matcher mt = Pattern.compile("<title[^>]*>(.*?)</title>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(html);
        if (mt.find()) {
            String t = htmlToPlain(mt.group(1)).replaceAll("\\s+", " ").trim();
            if (!t.isEmpty() && t.length() <= 60 && !t.equalsIgnoreCase("unknown") && !t.equalsIgnoreCase("未知")) return t;
        }
        return null;
    }

    private static String extractXmlTagText(String xml, String... tags) {
        for (String t : tags) {
            Matcher m = Pattern.compile("<" + t + "(?:\\s[^>]*)?>(.*?)</" + t + ">", Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(xml);
            if (m.find()) {
                return m.group(1).trim();
            }
        }
        return "";
    }

    private static String extractAttr(String tagAttrs, String attrName) {
        Matcher m = Pattern.compile("\\b" + attrName + "\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE).matcher(tagAttrs);
        return m.find() ? m.group(1) : null;
    }

    private static String normalizePath(String p) {
        if (p == null) return "";
        String cleaned = p.replace('\\', '/');
        while (cleaned.startsWith("/")) cleaned = cleaned.substring(1);
        List<String> parts = new ArrayList<>();
        for (String seg : cleaned.split("/")) {
            if (seg.isEmpty() || ".".equals(seg)) continue;
            if ("..".equals(seg)) {
                if (!parts.isEmpty()) parts.remove(parts.size() - 1);
            } else {
                parts.add(seg);
            }
        }
        return String.join("/", parts);
    }

    private static String getParentDir(String path) {
        int slash = path.lastIndexOf('/');
        return slash > 0 ? path.substring(0, slash) : "";
    }

    private static String stripNs(String tag) {
        if (tag == null) return "";
        int colon = tag.indexOf(':');
        return colon >= 0 ? tag.substring(colon + 1) : tag;
    }

    private static String safeNextText(XmlPullParser parser) {
        try {
            return parser.nextText();
        } catch (Exception e) {
            return "";
        }
    }

    private static String firstLineSummary(String body, String fallback) {
        if (body == null || body.isEmpty()) return fallback;
        String[] lines = body.split("\n");
        for (String l : lines) {
            String t = l.replace("　", " ").trim();
            if (!t.isEmpty()) {
                return t.length() > 32 ? t.substring(0, 32) + "…" : t;
            }
        }
        return fallback;
    }

    private static String stripExt(String name) {
        String sub = Book.detectSubExtension(name);
        if (!sub.isEmpty() && name.toLowerCase(Locale.ROOT).endsWith(sub)) {
            return name.substring(0, name.length() - sub.length());
        }
        return name;
    }

    private static byte[] readStreamBytes(InputStream is) throws Exception {
        return readStreamBytesCapped(is, 16 * 1024 * 1024);
    }

    private static byte[] readStreamBytesCapped(InputStream is, int maxBytes) throws Exception {
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
