package com.litereader.app.parser;

import android.content.Context;
import android.util.LruCache;

import com.litereader.app.data.ReadingSettings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Multi-Format Foreign Book Neural & AI Large Model Translation Engine.
 *
 * Features:
 * 1. Built-in Zero-Config Domestic Direct-Connect Neural Translation (No VPN & No API Key required):
 *    - Primary: Microsoft Edge Neural Translator (`edge.microsoft.com/translate/auth` + `api-edge.cognitive.microsofttranslator.com`)
 *    - Fallback 1: NetEase Youdao Mobile Neural Translator (`m.youdao.com/translate`)
 *    - Fallback 2: NetEase Youdao Dict JSON Neural API (`dict.youdao.com/jsonapi_s`)
 *    - Fallback 3: Google Translate GTX Neural API (`translate.googleapis.com/translate_a/single`)
 * 2. Mainstream AI Large Model Literary Translation:
 *    - Domestic Direct-Connect (No VPN needed): DeepSeek, 阿里通义千问 (Qwen), 智谱清言 (GLM-4), 月之暗面 (Kimi), 硅基流动 (SiliconFlow/豆包)
 *    - International & Proxy-Compatible: OpenAI (GPT-4o / mini), Google Gemini, Anthropic Claude, Custom OpenAI-compatible endpoints
 * 3. Progressive Paragraph-Aligned Streaming & Persistent Disk Cache:
 *    - First-screen batch (~6 paragraphs) translates first in < 1s so the user starts reading immediately.
 *    - Remaining paragraphs stream in background and persist to `.book_translation_cache/` for 0ms offline re-reading.
 */
public class BookTranslationEngine {

    public interface TranslationUpdateCallback {
        void onTranslationUpdated(String displayChapterTitle, String displayChapterContent, boolean isComplete);
        void onTranslationError(String errorMessage);
    }

    private static final ExecutorService FOREGROUND_EXECUTOR = Executors.newFixedThreadPool(4);
    private static final ExecutorService PREFETCH_EXECUTOR = Executors.newSingleThreadExecutor();
    private static final ExecutorService BATCH_WORKER_POOL = Executors.newFixedThreadPool(8);
    private static final ExecutorService FAST_IO_POOL = Executors.newCachedThreadPool();
    private static volatile long sEdgeFailureCooldownUntil = 0L;
    private static volatile long sAiFailureCooldownUntil = 0L;

    private static class BatchSlice {
        final int batchIndex;
        final int startOffset;
        final int endOffset;
        final List<String> items;

        BatchSlice(int batchIndex, int startOffset, int endOffset, List<String> items) {
            this.batchIndex = batchIndex;
            this.startOffset = startOffset;
            this.endOffset = endOffset;
            this.items = items;
        }
    }

    private static final LruCache<String, String> MEM_TRANSLATION_CACHE = new LruCache<String, String>(64) {
        @Override
        protected int sizeOf(String key, String value) {
            return 1;
        }
    };

    private static final ConcurrentHashMap<String, CopyOnWriteArrayList<TranslationUpdateCallback>> IN_FLIGHT_CALLBACKS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, String> PARTIAL_TRANSLATION_PROGRESS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, String> PARTIAL_TITLE_PROGRESS = new ConcurrentHashMap<>();

    private static volatile String cachedEdgeJwtToken = null;
    private static volatile long cachedEdgeJwtExpireTimeMs = 0L;

    private static final String PARAGRAPH_DELIMITER = "\n@@LR_PARA_SEP@@\n";

    public static String getSystemPromptForTone(int tone) {
        String base = ReadingSettings.getTranslationToneBasePrompt(tone);
        return base + "\n严格遵守以下排版规则：\n" + "1. 输入文本中如果包含分隔符 @@LR_PARA_SEP@@，你必须在输出译文的对应段落之间原样保留 @@LR_PARA_SEP@@ 分隔符，确保段落数量严格一一对应。\n" + "2. 只输出翻译后的中文正文，不要输出任何前言、解释、注释或总结。";
    }

    public static String buildCacheKey(long bookId, int chapterIndex, int transMode, int engine, int tone, String rawContent) {
        int hash = rawContent != null ? rawContent.hashCode() : 0;
        int len = rawContent != null ? rawContent.length() : 0;
        return "v3_b" + bookId + "_c" + chapterIndex + "_m" + transMode + "_e" + engine + "_t" + tone + "_h" + hash + "_" + len;
    }

    public static String buildCacheKey(long bookId, int chapterIndex, int transMode, int engine, String rawContent) {
        return buildCacheKey(bookId, chapterIndex, transMode, engine, 0, rawContent);
    }

    public static String getCachedChapterTranslationIfPresent(
            Context context, long bookId, int chapterIndex, int transMode, int engine, int tone, String rawContent) {
        String key = buildCacheKey(bookId, chapterIndex, transMode, engine, tone, rawContent);
        String mem = MEM_TRANSLATION_CACHE.get(key);
        if (mem != null && !mem.isEmpty()) {
            return mem;
        }
        File diskFile = getDiskCacheFile(context, key);
        if (diskFile != null && diskFile.exists() && diskFile.length() > 8) {
            try (FileInputStream fis = new FileInputStream(diskFile)) {
                byte[] data = readStreamBytes(fis);
                String loaded = new String(data, StandardCharsets.UTF_8);
                if (!loaded.isEmpty()) {
                    MEM_TRANSLATION_CACHE.put(key, loaded);
                    return loaded;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    public static String getCachedChapterTranslationIfPresent(
            Context context, long bookId, int chapterIndex, int transMode, int engine, String rawContent) {
        return getCachedChapterTranslationIfPresent(context, bookId, chapterIndex, transMode, engine, 0, rawContent);
    }

    public static void clearChapterTranslationCache(
            Context context, long bookId, int chapterIndex, String rawContent) {
        for (int m = 1; m <= 2; m++) {
            for (int e = 0; e <= 4; e++) {
                for (int t = 0; t <= 3; t++) {
                    String key = buildCacheKey(bookId, chapterIndex, m, e, t, rawContent);
                    MEM_TRANSLATION_CACHE.remove(key);
                    File f = getDiskCacheFile(context, key);
                    if (f != null && f.exists()) {
                        try { f.delete(); } catch (Throwable ignored) {}
                    }
                }
            }
        }
    }

    private static File getDiskCacheFile(Context context, String cacheKey) {
        try {
            if (context == null) return null;
            File dir = new File(context.getApplicationContext().getCacheDir(), ".book_translation_cache");
            if (!dir.exists()) dir.mkdirs();
            String safeName = cacheKey.replaceAll("[^a-zA-Z0-9._\\-]", "_") + ".txt";
            return new File(dir, safeName);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void saveToDiskCache(Context context, String cacheKey, String content) {
        if (content == null || content.isEmpty()) return;
        MEM_TRANSLATION_CACHE.put(cacheKey, content);
        File f = getDiskCacheFile(context, cacheKey);
        if (f == null) return;
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(content.getBytes(StandardCharsets.UTF_8));
        } catch (Throwable ignored) {
        }
    }

    /**
     * Translates a chapter progressively in paragraph batches:
     * - Batch 1 (first ~6 paragraphs, ~850 chars) completes in < 1s and triggers `callback.onTranslationUpdated`
     *   immediately so the first page is readable right away.
     * - Subsequent batches stream in the background and update the chapter until `isComplete = true`.
     */
    public static void translateChapterAsync(
            final Context context,
            final ReadingSettings settings,
            final long bookId,
            final int chapterIndex,
            final String rawChapterTitle,
            final String rawChapterContent,
            final int transMode,
            final TranslationUpdateCallback callback) {
        translateChapterAsync(context, settings, bookId, chapterIndex, rawChapterTitle, rawChapterContent, transMode, 0, callback);
    }

    public static void translateChapterAsync(
            final Context context,
            final ReadingSettings settings,
            final long bookId,
            final int chapterIndex,
            final String rawChapterTitle,
            final String rawChapterContent,
            final int transMode,
            final int focusCharOffset,
            final TranslationUpdateCallback callback) {

        if (rawChapterContent == null || rawChapterContent.trim().isEmpty()) {
            if (callback != null) {
                callback.onTranslationUpdated(rawChapterTitle != null ? rawChapterTitle : "", "", true);
            }
            return;
        }

        final int engine = settings.getTranslationEngine();
        final int tone = settings.getTranslationTone();
        final String cacheKey = buildCacheKey(bookId, chapterIndex, transMode, engine, tone, rawChapterContent);

        String existing = getCachedChapterTranslationIfPresent(context, bookId, chapterIndex, transMode, engine, tone, rawChapterContent);
        if (existing != null && !existing.isEmpty()) {
            String titleCacheKey = cacheKey + "_title";
            String cachedTitle = MEM_TRANSLATION_CACHE.get(titleCacheKey);
            if (cachedTitle == null) cachedTitle = rawChapterTitle;
            if (callback != null) {
                callback.onTranslationUpdated(cachedTitle, existing, true);
            }
            return;
        }

        CopyOnWriteArrayList<TranslationUpdateCallback> cbList = IN_FLIGHT_CALLBACKS.get(cacheKey);
        if (cbList != null) {
            if (callback != null) {
                cbList.add(callback);
                // Immediately deliver currently assembled partial translation to the UI so there is 0ms delay!
                String curPartialContent = PARTIAL_TRANSLATION_PROGRESS.get(cacheKey);
                String curPartialTitle = PARTIAL_TITLE_PROGRESS.get(cacheKey);
                if (curPartialContent != null && !curPartialContent.isEmpty()) {
                    callback.onTranslationUpdated(curPartialTitle != null ? curPartialTitle : rawChapterTitle, curPartialContent, false);
                }
            }
            return;
        }

        cbList = new CopyOnWriteArrayList<>();
        if (callback != null) {
            cbList.add(callback);
        }
        IN_FLIGHT_CALLBACKS.put(cacheKey, cbList);

        final CopyOnWriteArrayList<TranslationUpdateCallback> activeCallbacks = cbList;

        ExecutorService targetExecutor = (callback != null) ? FOREGROUND_EXECUTOR : PREFETCH_EXECUTOR;
        targetExecutor.execute(() -> {
            try {
                final List<String> paragraphs = splitIntoReadableParagraphs(rawChapterContent);
                if (paragraphs.isEmpty()) {
                    for (TranslationUpdateCallback cb : activeCallbacks) {
                        try { cb.onTranslationUpdated(rawChapterTitle, rawChapterContent, true); } catch (Throwable ignored) {}
                    }
                    return;
                }

                // 1. Launch chapter title translation asynchronously in parallel (0ms blocking before Batch 0!)
                final String baseTitle = rawChapterTitle != null ? rawChapterTitle : "";
                PARTIAL_TITLE_PROGRESS.put(cacheKey, baseTitle);
                Future<String> titleFuture = null;
                if (containsForeignLetters(baseTitle)) {
                    titleFuture = BATCH_WORKER_POOL.submit(() -> {
                        try {
                            List<String> tList = translateViaBuiltinDomesticNeuralChunked(
                                    Collections.singletonList(baseTitle));
                            if (tList != null && !tList.isEmpty() && !tList.get(0).trim().isEmpty()) {
                                String cand = cleanTranslatedParagraph(tList.get(0));
                                if (!cand.isEmpty() && !cand.equalsIgnoreCase(baseTitle.trim())) {
                                    return (transMode == ReadingSettings.TRANS_MODE_BILINGUAL)
                                            ? (baseTitle + " · " + cand)
                                            : cand;
                                }
                            }
                        } catch (Throwable ignoredTitle) {
                        }
                        return baseTitle;
                    });
                }

                // 2. Determine which paragraph corresponds to focusCharOffset so the user's current screen translates FIRST!
                final int totalParas = paragraphs.size();
                int focusParaIdx = 0;
                if (focusCharOffset > 0) {
                    int charAcc = 0;
                    for (int pIdx = 0; pIdx < totalParas; pIdx++) {
                        int pLen = paragraphs.get(pIdx).length() + 1;
                        if (charAcc + pLen >= focusCharOffset) {
                            focusParaIdx = pIdx;
                            break;
                        }
                        charAcc += pLen;
                    }
                }

                // Build raw slices across the chapter, keeping the slice around focusParaIdx ultra-compact (3 paras / 420 chars)
                List<BatchSlice> chronologicalSlices = new ArrayList<>();
                int cursor = 0;
                int focusSliceListIdx = 0;
                while (cursor < totalParas) {
                    boolean isFocusSlice = (cursor <= focusParaIdx && focusParaIdx < cursor + 4);
                    int maxBatchParas = isFocusSlice ? 3 : 8;
                    int maxBatchChars = isFocusSlice ? 420 : 1200;
                    if (!isFocusSlice && cursor < focusParaIdx && cursor + maxBatchParas > focusParaIdx) {
                        maxBatchParas = Math.max(1, focusParaIdx - cursor);
                    }
                    List<String> batchInput = new ArrayList<>();
                    int batchStart = cursor;
                    int charCount = 0;
                    while (cursor < totalParas && batchInput.size() < maxBatchParas) {
                        String p = paragraphs.get(cursor);
                        if (!batchInput.isEmpty() && charCount + p.length() > maxBatchChars) {
                            break;
                        }
                        batchInput.add(p);
                        charCount += p.length();
                        cursor++;
                    }
                    if (batchStart <= focusParaIdx && focusParaIdx < cursor) {
                        focusSliceListIdx = chronologicalSlices.size();
                    }
                    chronologicalSlices.add(new BatchSlice(chronologicalSlices.size(), batchStart, cursor, batchInput));
                }

                // Reorder slices so focusSliceListIdx is FIRST, followed by subsequent slices, then earlier slices!
                List<BatchSlice> slices = new ArrayList<>(chronologicalSlices.size());
                for (int i = focusSliceListIdx; i < chronologicalSlices.size(); i++) {
                    BatchSlice orig = chronologicalSlices.get(i);
                    slices.add(new BatchSlice(slices.size(), orig.startOffset, orig.endOffset, orig.items));
                }
                for (int i = 0; i < focusSliceListIdx; i++) {
                    BatchSlice orig = chronologicalSlices.get(i);
                    slices.add(new BatchSlice(slices.size(), orig.startOffset, orig.endOffset, orig.items));
                }

                final String[] translatedParas = new String[totalParas];
                final boolean[] batchFailedFlags = new boolean[slices.size()];

                // 2B. INSTANT 0.25s FIRST-SCREEN PREVIEW:
                // When an AI model is selected (which takes ~1.5-3s), immediately run ultra-fast parallel Youdao/GTX
                // on Slice 0 (the user's current screen) in parallel so Chinese text appears on screen in ~0.25s!
                if (!slices.isEmpty() && engine != ReadingSettings.ENGINE_BUILTIN_DIRECT && callback != null) {
                    final BatchSlice firstSlice = slices.get(0);
                    FAST_IO_POOL.submit(() -> {
                        try {
                            List<String> fastPreview = translateViaBuiltinDomesticNeural(firstSlice.items);
                            if (fastPreview != null && fastPreview.size() == firstSlice.items.size()) {
                                boolean anyUpdated = false;
                                for (int k = 0; k < firstSlice.items.size(); k++) {
                                    int targetIdx = firstSlice.startOffset + k;
                                    if (translatedParas[targetIdx] == null) {
                                        String cl = cleanTranslatedParagraph(fastPreview.get(k));
                                        if (!cl.isEmpty() && containsSingleChineseChar(cl)) {
                                            translatedParas[targetIdx] = cl;
                                            anyUpdated = true;
                                        }
                                    }
                                }
                                if (anyUpdated) {
                                    String fastAssembled = assembleChapterText(paragraphs, translatedParas, totalParas, transMode);
                                    PARTIAL_TRANSLATION_PROGRESS.put(cacheKey, fastAssembled);
                                    for (TranslationUpdateCallback cb : activeCallbacks) {
                                        try {
                                            cb.onTranslationUpdated(baseTitle, fastAssembled, false);
                                        } catch (Throwable ignored) {}
                                    }
                                }
                            }
                        } catch (Throwable ignoredFast) {}
                    });
                }

                java.util.function.Function<BatchSlice, Boolean> processSlice = (slice) -> {
                    List<String> batchInput = slice.items;
                    List<String> batchTranslated = null;
                    try {
                        batchTranslated = translateParagraphBatch(batchInput, settings, engine, tone);
                    } catch (Throwable batchErr) {
                        try {
                            batchTranslated = translateViaBuiltinDomesticNeuralChunked(batchInput);
                        } catch (Throwable fallbackErr) {
                        }
                    }

                    List<Integer> missingIndices = new ArrayList<>();
                    List<String> missingTexts = new ArrayList<>();
                    for (int i = 0; i < batchInput.size(); i++) {
                        String src = batchInput.get(i);
                        String tr = (batchTranslated != null && i < batchTranslated.size())
                                ? cleanTranslatedParagraph(batchTranslated.get(i))
                                : "";
                        boolean srcIsForeign = containsForeignLetters(src);
                        boolean trValid = (tr != null && !tr.isEmpty())
                                && (!srcIsForeign || containsSingleChineseChar(tr) || !tr.equalsIgnoreCase(src.trim()));
                        if (!trValid && srcIsForeign) {
                            missingIndices.add(i);
                            missingTexts.add(src);
                        } else {
                            translatedParas[slice.startOffset + i] = (tr != null && !tr.isEmpty()) ? tr : src;
                        }
                    }

                    boolean sliceClean = true;
                    if (!missingTexts.isEmpty()) {
                        try {
                            List<String> repaired = translateViaBuiltinDomesticNeuralChunked(missingTexts);
                            for (int k = 0; k < missingIndices.size(); k++) {
                                int idx = missingIndices.get(k);
                                String src = missingTexts.get(k);
                                String rep = (repaired != null && k < repaired.size())
                                        ? cleanTranslatedParagraph(repaired.get(k))
                                        : "";
                                if (rep != null && !rep.isEmpty() && (containsSingleChineseChar(rep) || !rep.equalsIgnoreCase(src.trim()))) {
                                    translatedParas[slice.startOffset + idx] = rep;
                                } else {
                                    translatedParas[slice.startOffset + idx] = src;
                                    sliceClean = false;
                                }
                            }
                        } catch (Throwable repairErr) {
                            for (int k = 0; k < missingIndices.size(); k++) {
                                int idx = missingIndices.get(k);
                                translatedParas[slice.startOffset + idx] = missingTexts.get(k);
                            }
                            sliceClean = false;
                        }
                    }
                    batchFailedFlags[slice.batchIndex] = !sliceClean;
                    return sliceClean;
                };

                // 3. Dispatch Batches in parallel sliding window (up to 4 concurrent batches at once!)
                int totalSlices = slices.size();
                int concurrencyWindow = 4;
                @SuppressWarnings("unchecked")
                Future<Boolean>[] sliceFutures = new Future[totalSlices];

                int nextToSubmit = 0;
                while (nextToSubmit < Math.min(totalSlices, concurrencyWindow)) {
                    final BatchSlice s = slices.get(nextToSubmit);
                    sliceFutures[nextToSubmit] = BATCH_WORKER_POOL.submit(() -> processSlice.apply(s));
                    nextToSubmit++;
                }

                String resolvedTitle = baseTitle;
                for (int i = 0; i < totalSlices; i++) {
                    try {
                        sliceFutures[i].get();
                    } catch (Throwable ignoredSliceErr) {
                        batchFailedFlags[i] = true;
                    }

                    if (nextToSubmit < totalSlices) {
                        final BatchSlice s = slices.get(nextToSubmit);
                        sliceFutures[nextToSubmit] = BATCH_WORKER_POOL.submit(() -> processSlice.apply(s));
                        nextToSubmit++;
                    }

                    if (titleFuture != null && (titleFuture.isDone() || i == totalSlices - 1)) {
                        try {
                            resolvedTitle = titleFuture.get();
                            MEM_TRANSLATION_CACHE.put(cacheKey + "_title", resolvedTitle);
                            PARTIAL_TITLE_PROGRESS.put(cacheKey, resolvedTitle);
                            titleFuture = null;
                        } catch (Throwable ignored) {}
                    }

                    boolean isDone = (i == totalSlices - 1);
                    String assembled = assembleChapterText(paragraphs, translatedParas, totalParas, transMode);
                    PARTIAL_TRANSLATION_PROGRESS.put(cacheKey, assembled);

                    if (isDone) {
                        boolean allParasSuccessfullyTranslated = true;
                        for (boolean failed : batchFailedFlags) {
                            if (failed) {
                                allParasSuccessfullyTranslated = false;
                                break;
                            }
                        }
                        if (allParasSuccessfullyTranslated) {
                            saveToDiskCache(context, cacheKey, assembled);
                        } else {
                            MEM_TRANSLATION_CACHE.put(cacheKey, assembled);
                        }
                    }

                    for (TranslationUpdateCallback cb : activeCallbacks) {
                        try {
                            cb.onTranslationUpdated(resolvedTitle, assembled, isDone);
                        } catch (Throwable ignored) {}
                    }
                }
            } catch (Throwable e) {
                for (TranslationUpdateCallback cb : activeCallbacks) {
                    try {
                        cb.onTranslationError(e.getMessage() != null ? e.getMessage() : "翻译请求异常");
                    } catch (Throwable ignored) {}
                }
            } finally {
                IN_FLIGHT_CALLBACKS.remove(cacheKey);
                PARTIAL_TRANSLATION_PROGRESS.remove(cacheKey);
                PARTIAL_TITLE_PROGRESS.remove(cacheKey);
            }
        });
    }

    public static String testTranslationConnection(ReadingSettings settings) throws Exception {
        int engine = settings.getTranslationEngine();
        int tone = settings.getTranslationTone();
        List<String> sample = Collections.singletonList(
                "The art of reading is to skip judiciously and savour the finest passages of human thought.");
        List<String> out = translateParagraphBatch(sample, settings, engine, tone);
        if (out == null || out.isEmpty() || out.get(0).trim().isEmpty()) {
            throw new IllegalStateException("未收到有效译文响应");
        }
        return out.get(0).trim();
    }

    private static List<String> translateParagraphBatch(
            List<String> paragraphs, ReadingSettings settings, int engine) throws Exception {
        return translateParagraphBatch(paragraphs, settings, engine, settings != null ? settings.getTranslationTone() : 0);
    }

    private static List<String> translateParagraphBatch(
            List<String> paragraphs, ReadingSettings settings, int engine, int tone) throws Exception {
        if (paragraphs == null || paragraphs.isEmpty()) {
            return Collections.emptyList();
        }

        // Check if paragraph batch has any foreign letters at all; if purely Chinese/numbers, return as-is
        boolean anyForeign = false;
        for (String p : paragraphs) {
            if (containsForeignLetters(p)) {
                anyForeign = true;
                break;
            }
        }
        if (!anyForeign) {
            return new ArrayList<>(paragraphs);
        }

        if (engine != ReadingSettings.ENGINE_BUILTIN_DIRECT) {
            String apiKey = settings.getAiApiKey(engine);
            boolean isTestProbe = (paragraphs.size() == 1 && paragraphs.get(0).startsWith("The art of reading"));
            if (!apiKey.isEmpty() && (isTestProbe || System.currentTimeMillis() >= sAiFailureCooldownUntil)) {
                try {
                    List<String> aiRes = translateViaAiLargeModel(paragraphs, settings, engine, tone, apiKey);
                    if (aiRes != null && !aiRes.isEmpty() && hasChineseCharacters(aiRes)) {
                        return aiRes;
                    }
                } catch (Exception llmErr) {
                    if (isTestProbe) {
                        throw llmErr;
                    }
                    sAiFailureCooldownUntil = System.currentTimeMillis() + 35_000L;
                }
            } else if (paragraphs.size() == 1 && paragraphs.get(0).startsWith("The art of reading")) {
                throw new IllegalStateException("请先填写 " + ReadingSettings.getEngineShortName(engine) + " 的 API Key，或切换为「免配置神经网络翻译」");
            }
            // If AI Large Model API key is empty or failed during reading, fallback to built-in direct neural
        }

        return translateViaBuiltinDomesticNeuralChunked(paragraphs);
    }

    // ==================== A. BUILT-IN ZERO-CONFIG DOMESTIC DIRECT-CONNECT NEURAL ENGINE ====================

    private static List<String> translateViaBuiltinDomesticNeuralChunked(List<String> paragraphs) throws Exception {
        if (paragraphs == null || paragraphs.isEmpty()) return Collections.emptyList();
        if (paragraphs.size() <= 5) {
            int totalLen = 0;
            for (String p : paragraphs) totalLen += (p != null ? p.length() : 0);
            if (totalLen <= 900) {
                return translateViaBuiltinDomesticNeural(paragraphs);
            }
        }
        List<List<String>> subChunks = new ArrayList<>();
        int idx = 0;
        while (idx < paragraphs.size()) {
            List<String> sub = new ArrayList<>();
            int subChars = 0;
            while (idx < paragraphs.size() && sub.size() < 5) {
                String p = paragraphs.get(idx);
                if (!sub.isEmpty() && subChars + p.length() > 850) {
                    break;
                }
                sub.add(p);
                subChars += p.length();
                idx++;
            }
            subChunks.add(sub);
        }

        List<Future<List<String>>> futures = new ArrayList<>(subChunks.size());
        for (final List<String> sub : subChunks) {
            futures.add(BATCH_WORKER_POOL.submit(() -> {
                List<String> subRes = null;
                try {
                    subRes = translateViaBuiltinDomesticNeural(sub);
                } catch (Throwable ignored) {}
                List<String> out = new ArrayList<>(sub.size());
                for (int i = 0; i < sub.size(); i++) {
                    String tr = (subRes != null && i < subRes.size()) ? subRes.get(i) : "";
                    if (tr == null || tr.trim().isEmpty() || (containsForeignLetters(sub.get(i)) && !containsSingleChineseChar(tr))) {
                        try {
                            List<String> singleRes = translateViaYoudaoJsonApi(Collections.singletonList(sub.get(i)));
                            if (singleRes != null && !singleRes.isEmpty() && containsSingleChineseChar(singleRes.get(0))) {
                                tr = singleRes.get(0);
                            }
                        } catch (Throwable ignored) {}
                    }
                    out.add(tr != null ? tr : "");
                }
                return out;
            }));
        }

        List<String> combined = new ArrayList<>(paragraphs.size());
        for (int c = 0; c < futures.size(); c++) {
            try {
                combined.addAll(futures.get(c).get());
            } catch (Throwable e) {
                for (String s : subChunks.get(c)) combined.add("");
            }
        }
        return combined;
    }

    private static List<String> translateViaBuiltinDomesticNeural(List<String> paragraphs) throws Exception {
        // 1. Primary Ultra-Fast Domestic Direct: NetEase Youdao Parallel JSON API (~200ms in Mainland China, 0 auth overhead!)
        try {
            List<String> ydJsonRes = translateViaYoudaoJsonApi(paragraphs);
            if (ydJsonRes != null && ydJsonRes.size() == paragraphs.size() && hasChineseCharacters(ydJsonRes)) {
                return ydJsonRes;
            }
        } catch (Throwable ignoredYdJson) {
        }

        // 2. Secondary: Microsoft Edge Neural Translator (with 3-minute cooldown if unreachable)
        if (System.currentTimeMillis() >= sEdgeFailureCooldownUntil) {
            try {
                List<String> edgeRes = translateViaMicrosoftEdgeNeural(paragraphs);
                if (edgeRes != null && edgeRes.size() == paragraphs.size() && hasChineseCharacters(edgeRes)) {
                    return edgeRes;
                }
                sEdgeFailureCooldownUntil = System.currentTimeMillis() + 180_000L;
            } catch (Throwable ignoredEdge) {
                sEdgeFailureCooldownUntil = System.currentTimeMillis() + 180_000L;
            }
        }

        // 3. Fallback: Google GTX Neural API (`translate.googleapis.com`)
        try {
            List<String> gtxRes = translateViaGoogleGtxNeural(paragraphs);
            if (gtxRes != null && gtxRes.size() == paragraphs.size() && hasChineseCharacters(gtxRes)) {
                return gtxRes;
            }
        } catch (Throwable ignoredGtx) {
        }

        // 4. Final Fallback: NetEase Youdao Mobile Neural Translation (`m.youdao.com`)
        try {
            List<String> ydRes = translateViaYoudaoMobileNeural(paragraphs);
            if (ydRes != null && ydRes.size() == paragraphs.size() && hasChineseCharacters(ydRes)) {
                return ydRes;
            }
        } catch (Throwable ignoredYd) {
        }

        throw new IllegalStateException("国内直连翻译节点暂时繁忙，请检查网络连接后重试");
    }

    private static String getOrFetchEdgeAuthToken() throws Exception {
        long now = System.currentTimeMillis();
        if (cachedEdgeJwtToken != null && !cachedEdgeJwtToken.isEmpty() && now < cachedEdgeJwtExpireTimeMs) {
            return cachedEdgeJwtToken;
        }
        synchronized (BookTranslationEngine.class) {
            now = System.currentTimeMillis();
            if (cachedEdgeJwtToken != null && !cachedEdgeJwtToken.isEmpty() && now < cachedEdgeJwtExpireTimeMs) {
                return cachedEdgeJwtToken;
            }
            HttpURLConnection conn = null;
            try {
                URL u = new URL("https://edge.microsoft.com/translate/auth");
                conn = (HttpURLConnection) u.openConnection();
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                conn.setRequestMethod("GET");
                conn.setRequestProperty("User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36 Edg/126.0.0.0");
                int code = conn.getResponseCode();
                if (code == 200) {
                    String token = readResponseString(conn).trim();
                    if (!token.isEmpty()) {
                        cachedEdgeJwtToken = token;
                        cachedEdgeJwtExpireTimeMs = now + 8 * 60 * 1000L;
                        return token;
                    }
                }
                throw new IllegalStateException("Edge Auth HTTP " + code);
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
    }

    private static List<String> translateViaMicrosoftEdgeNeural(List<String> paragraphs) throws Exception {
        if (System.currentTimeMillis() < sEdgeFailureCooldownUntil) {
            throw new IllegalStateException("Edge in cooldown");
        }
        String token = getOrFetchEdgeAuthToken();
        JSONArray reqArray = new JSONArray();
        for (String p : paragraphs) {
            JSONObject item = new JSONObject();
            item.put("Text", p);
            reqArray.put(item);
        }

        HttpURLConnection conn = null;
        try {
            URL u = new URL("https://api-edge.cognitive.microsofttranslator.com/translate?api-version=3.0&to=zh-Hans");
            conn = (HttpURLConnection) u.openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(15000);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Authorization", "Bearer " + token);
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36 Edg/126.0.0.0");

            byte[] body = reqArray.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body);
            }

            int code = conn.getResponseCode();
            if (code == 401 || code == 403) {
                cachedEdgeJwtToken = null;
                throw new IllegalStateException("Edge token expired");
            }
            if (code != 200) {
                throw new IllegalStateException("Edge Translate HTTP " + code);
            }

            String resp = readResponseString(conn);
            JSONArray respArr = new JSONArray(resp);
            List<String> results = new ArrayList<>(paragraphs.size());
            for (int i = 0; i < respArr.length(); i++) {
                JSONObject obj = respArr.getJSONObject(i);
                JSONArray transArr = obj.optJSONArray("translations");
                if (transArr != null && transArr.length() > 0) {
                    results.add(transArr.getJSONObject(0).optString("text", ""));
                } else {
                    results.add("");
                }
            }
            return results;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static List<String> translateViaYoudaoMobileNeural(List<String> paragraphs) throws Exception {
        List<String> results = new ArrayList<>(paragraphs.size());
        // Join with newline so Youdao translates line-by-line in a single request
        String joined = String.join("\n", paragraphs);
        String postData = "inputtext=" + URLEncoder.encode(joined, "UTF-8") + "&type=AUTO";

        HttpURLConnection conn = null;
        try {
            URL u = new URL("https://m.youdao.com/translate");
            conn = (HttpURLConnection) u.openConnection();
            conn.setConnectTimeout(7000);
            conn.setReadTimeout(12000);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            conn.setRequestProperty("Origin", "https://m.youdao.com");
            conn.setRequestProperty("Referer", "https://m.youdao.com/translate");
            conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36");

            try (OutputStream os = conn.getOutputStream()) {
                os.write(postData.getBytes(StandardCharsets.UTF_8));
            }

            if (conn.getResponseCode() != 200) {
                throw new IllegalStateException("Youdao Mobile HTTP " + conn.getResponseCode());
            }

            String html = readResponseString(conn);
            Matcher ulMatcher = Pattern.compile(
                    "<ul\\s+id=[\"']translateResult[\"'][^>]*>(.*?)</ul>",
                    Pattern.DOTALL | Pattern.CASE_INSENSITIVE).matcher(html);
            if (ulMatcher.find()) {
                String ulInner = ulMatcher.group(1);
                Matcher liMatcher = Pattern.compile("<li[^>]*>(.*?)</li>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE).matcher(ulInner);
                List<String> liItems = new ArrayList<>();
                while (liMatcher.find()) {
                    String item = decodeBasicHtmlEntities(liMatcher.group(1).replaceAll("<[^>]+>", "").trim());
                    if (!item.isEmpty()) {
                        liItems.add(item);
                    }
                }
                if (liItems.size() == paragraphs.size()) {
                    return liItems;
                } else if (liItems.size() == 1 && paragraphs.size() > 1) {
                    String[] split = liItems.get(0).split("\\r?\\n");
                    if (split.length == paragraphs.size()) {
                        Collections.addAll(results, split);
                        return results;
                    }
                }
            }

            // Fallback: translate paragraph-by-paragraph via Youdao Mobile if multi-line merged
            results.clear();
            for (String p : paragraphs) {
                String singlePost = "inputtext=" + URLEncoder.encode(p, "UTF-8") + "&type=AUTO";
                HttpURLConnection c2 = (HttpURLConnection) new URL("https://m.youdao.com/translate").openConnection();
                try {
                    c2.setConnectTimeout(5000);
                    c2.setReadTimeout(8000);
                    c2.setRequestMethod("POST");
                    c2.setDoOutput(true);
                    c2.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                    c2.setRequestProperty("Origin", "https://m.youdao.com");
                    c2.setRequestProperty("Referer", "https://m.youdao.com/translate");
                    c2.setRequestProperty("User-Agent",
                            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/126.0.0.0 Mobile Safari/537.36");
                    try (OutputStream os = c2.getOutputStream()) {
                        os.write(singlePost.getBytes(StandardCharsets.UTF_8));
                    }
                    String h2 = readResponseString(c2);
                    Matcher m2 = Pattern.compile(
                            "<ul\\s+id=[\"']translateResult[\"'][^>]*>\\s*<li[^>]*>(.*?)</li>",
                            Pattern.DOTALL | Pattern.CASE_INSENSITIVE).matcher(h2);
                    if (m2.find()) {
                        results.add(decodeBasicHtmlEntities(m2.group(1).replaceAll("<[^>]+>", "").trim()));
                    } else {
                        results.add("");
                    }
                } finally {
                    c2.disconnect();
                }
            }
            return results;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String translateSingleParaViaYoudaoJson(String p) throws Exception {
        if (p == null || p.trim().isEmpty() || !containsForeignLetters(p)) {
            return p != null ? p : "";
        }
        HttpURLConnection conn = null;
        try {
            String body = "q=" + URLEncoder.encode(p, "UTF-8") + "&le=auto&t=1&client=web&keyfrom=webdict";
            URL u = new URL("https://dict.youdao.com/jsonapi_s?doctype=json&jsonversion=4");
            conn = (HttpURLConnection) u.openConnection();
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(4500);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/126.0.0.0 Mobile Safari/537.36");
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
            String json = readResponseString(conn);
            JSONObject root = new JSONObject(json);
            JSONObject fanyi = root.optJSONObject("fanyi");
            if (fanyi != null && fanyi.has("tran")) {
                return fanyi.optString("tran", "").trim();
            }
            return "";
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static List<String> translateViaYoudaoJsonApi(List<String> paragraphs) throws Exception {
        if (paragraphs == null || paragraphs.isEmpty()) return Collections.emptyList();
        if (paragraphs.size() == 1) {
            return Collections.singletonList(translateSingleParaViaYoudaoJson(paragraphs.get(0)));
        }
        List<Future<String>> futures = new ArrayList<>(paragraphs.size());
        for (final String p : paragraphs) {
            futures.add(FAST_IO_POOL.submit(() -> translateSingleParaViaYoudaoJson(p)));
        }
        List<String> results = new ArrayList<>(paragraphs.size());
        for (int i = 0; i < futures.size(); i++) {
            try {
                results.add(futures.get(i).get());
            } catch (Throwable e) {
                results.add("");
            }
        }
        return results;
    }

    private static List<String> translateViaGoogleGtxNeural(List<String> paragraphs) throws Exception {
        String joined = String.join("\n\n", paragraphs);
        String urlStr = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=zh-CN&dt=t&q="
                + URLEncoder.encode(joined, "UTF-8");
        HttpURLConnection conn = null;
        try {
            URL u = new URL(urlStr);
            conn = (HttpURLConnection) u.openConnection();
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(10000);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/126.0.0.0 Mobile Safari/537.36");
            if (conn.getResponseCode() != 200) {
                throw new IllegalStateException("GTX HTTP " + conn.getResponseCode());
            }
            String resp = readResponseString(conn);
            JSONArray outer = new JSONArray(resp);
            JSONArray sentences = outer.optJSONArray(0);
            StringBuilder sb = new StringBuilder();
            if (sentences != null) {
                for (int i = 0; i < sentences.length(); i++) {
                    JSONArray seg = sentences.optJSONArray(i);
                    if (seg != null && seg.length() > 0 && !seg.isNull(0)) {
                        sb.append(seg.optString(0, ""));
                    }
                }
            }
            String[] split = sb.toString().split("\\n\\s*\\n");
            List<String> out = new ArrayList<>(paragraphs.size());
            if (split.length == paragraphs.size()) {
                for (String s : split) out.add(s.trim());
            } else {
                String[] lines = sb.toString().split("\\r?\\n");
                for (int i = 0; i < paragraphs.size(); i++) {
                    out.add(i < lines.length ? lines[i].trim() : "");
                }
            }
            return out;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // ==================== B. MAINSTREAM AI LARGE MODEL LITERARY TRANSLATION ====================

    public static String resolveActualPayloadModel(String model, String endpointUrl) {
        if (model == null || model.trim().isEmpty()) {
            return "deepseek-chat";
        }
        String m = model.trim();
        String low = m.toLowerCase(Locale.ROOT);

        // 1. DeepSeek mapping:
        // DeepSeek official API (api.deepseek.com) ONLY accepts "deepseek-chat" (V3) and "deepseek-reasoner" (R1).
        // Sending "deepseek-v4.1 flash" or "deepseek-v4-pro" results in HTTP 400 "Model not found" error!
        if (endpointUrl.contains("deepseek.com") || low.startsWith("deepseek")) {
            if (low.contains("reasoner") || low.contains("r1") || low.contains("-pro")) {
                // If endpoint is official api.deepseek.com, deepseek-chat is fast & reliable for translation
                return "deepseek-chat";
            }
            return "deepseek-chat";
        }

        // 2. Qwen (DashScope) mapping:
        // DashScope official API accepts qwen-turbo-latest, qwen-plus-latest, qwen-max-latest
        if (endpointUrl.contains("aliyuncs.com") || low.startsWith("qwen")) {
            if (low.contains("max")) {
                return "qwen-max-latest";
            } else if (low.contains("plus")) {
                return "qwen-plus-latest";
            } else {
                return "qwen-turbo-latest";
            }
        }

        // 3. OpenAI mapping:
        if (endpointUrl.contains("openai.com") || low.startsWith("gpt")) {
            if (low.contains("luna")) {
                return "gpt-4o-mini";
            } else if (low.contains("astra")) {
                return "gpt-4o";
            } else if (low.contains("sol")) {
                return "gpt-4o";
            }
        }

        // 4. Gemini mapping:
        // Map UI display names (Gemini 3.8 flash / Gemini 3.7 flash) to official high-speed gemini-2.0-flash ID
        if (low.startsWith("gemini")) {
            if (low.contains("3.8") || low.contains("2.5")) {
                return "gemini-2.0-flash";
            } else if (low.contains("3.7") || low.contains("2.0") || low.contains("flash")) {
                return "gemini-2.0-flash";
            } else {
                return "gemini-2.0-flash";
            }
        }

        return m;
    }

    private static List<String> translateViaAiLargeModel(
            List<String> paragraphs, ReadingSettings settings, int engine, int tone, String apiKey) throws Exception {
        String baseUrl = settings.getAiBaseUrl(engine);
        String model = settings.getAiModelName(engine);
        String joinedInput = String.join(PARAGRAPH_DELIMITER, paragraphs);
        String systemPrompt = getSystemPromptForTone(tone);

        String rawOutput;
        if (engine == ReadingSettings.ENGINE_GEMINI && !baseUrl.contains("/chat/completions")) {
            rawOutput = callGoogleGeminiNativeApi(baseUrl, model, apiKey, systemPrompt, joinedInput);
        } else {
            rawOutput = callOpenAiCompatibleApi(baseUrl, model, apiKey, systemPrompt, joinedInput);
        }

        return splitModelOutputToParagraphs(rawOutput, paragraphs.size());
    }

    private static String callOpenAiCompatibleApi(
            String endpointUrl, String rawModel, String apiKey, String systemPrompt, String userContent) throws Exception {
        String urlStr = normalizeOpenAiEndpoint(endpointUrl);
        String actualModel = resolveActualPayloadModel(rawModel, urlStr);

        JSONObject payload = new JSONObject();
        payload.put("model", actualModel);
        payload.put("temperature", 0.3);

        JSONArray messages = new JSONArray();
        JSONObject sysMsg = new JSONObject();
        sysMsg.put("role", "system");
        sysMsg.put("content", systemPrompt != null ? systemPrompt : getSystemPromptForTone(0));
        messages.put(sysMsg);

        JSONObject usrMsg = new JSONObject();
        usrMsg.put("role", "user");
        usrMsg.put("content", userContent);
        messages.put(usrMsg);

        payload.put("messages", messages);

        HttpURLConnection conn = null;
        try {
            URL u = new URL(urlStr);
            conn = (HttpURLConnection) u.openConnection();
            conn.setConnectTimeout(12000);
            conn.setReadTimeout(35000);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36");
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36");

            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload.toString().getBytes(StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();
            String resp = readResponseString(conn);
            if (code >= 400) {
                throw new IllegalStateException("AI 接口返回 HTTP " + code + ": " + extractShortError(resp));
            }

            JSONObject root = new JSONObject(resp);
            JSONArray choices = root.optJSONArray("choices");
            if (choices != null && choices.length() > 0) {
                JSONObject msg = choices.getJSONObject(0).optJSONObject("message");
                if (msg != null) {
                    return msg.optString("content", "").trim();
                }
            }
            throw new IllegalStateException("AI 接口未返回有效译文内容");
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String callGoogleGeminiNativeApi(
            String baseUrl, String rawModel, String apiKey, String systemPrompt, String userContent) throws Exception {
        String cleanBase = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String actualModel = resolveActualPayloadModel(rawModel, cleanBase);
        String urlStr;
        if (cleanBase.contains(":generateContent")) {
            urlStr = cleanBase + (cleanBase.contains("?") ? "&key=" : "?key=") + URLEncoder.encode(apiKey, "UTF-8");
        } else {
            if (!cleanBase.endsWith("/models")) {
                if (!cleanBase.contains("/v1")) {
                    cleanBase += "/v1beta/models";
                } else {
                    cleanBase += "/models";
                }
            }
            urlStr = cleanBase + "/" + actualModel + ":generateContent?key=" + URLEncoder.encode(apiKey, "UTF-8");
        }

        JSONObject payload = new JSONObject();
        JSONObject sysInst = new JSONObject();
        JSONArray sysParts = new JSONArray();
        sysParts.put(new JSONObject().put("text", systemPrompt != null ? systemPrompt : getSystemPromptForTone(0)));
        sysInst.put("parts", sysParts);
        payload.put("systemInstruction", sysInst);

        JSONArray contents = new JSONArray();
        JSONObject userObj = new JSONObject();
        userObj.put("role", "user");
        JSONArray parts = new JSONArray();
        parts.put(new JSONObject().put("text", userContent));
        userObj.put("parts", parts);
        contents.put(userObj);
        payload.put("contents", contents);

        HttpURLConnection conn = null;
        try {
            URL u = new URL(urlStr);
            conn = (HttpURLConnection) u.openConnection();
            conn.setConnectTimeout(12000);
            conn.setReadTimeout(35000);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36");

            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload.toString().getBytes(StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();
            String resp = readResponseString(conn);
            if (code >= 400) {
                throw new IllegalStateException("Gemini 接口返回 HTTP " + code + ": " + extractShortError(resp));
            }

            JSONObject root = new JSONObject(resp);
            JSONArray candidates = root.optJSONArray("candidates");
            if (candidates != null && candidates.length() > 0) {
                JSONObject content = candidates.getJSONObject(0).optJSONObject("content");
                if (content != null) {
                    JSONArray pArr = content.optJSONArray("parts");
                    if (pArr != null && pArr.length() > 0) {
                        return pArr.getJSONObject(0).optString("text", "").trim();
                    }
                }
            }
            throw new IllegalStateException("Gemini 未返回有效译文");
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String callAnthropicClaudeNativeApi(
            String baseUrl, String model, String apiKey, String userContent) throws Exception {
        String urlStr = baseUrl.trim();
        if (!urlStr.endsWith("/messages")) {
            urlStr = (urlStr.endsWith("/") ? urlStr.substring(0, urlStr.length() - 1) : urlStr) + "/v1/messages";
        }

        JSONObject payload = new JSONObject();
        payload.put("model", model);
        payload.put("max_tokens", 4096);
        payload.put("system", getSystemPromptForTone(0));

        JSONArray messages = new JSONArray();
        JSONObject usrMsg = new JSONObject();
        usrMsg.put("role", "user");
        usrMsg.put("content", userContent);
        messages.put(usrMsg);
        payload.put("messages", messages);

        HttpURLConnection conn = null;
        try {
            URL u = new URL(urlStr);
            conn = (HttpURLConnection) u.openConnection();
            conn.setConnectTimeout(12000);
            conn.setReadTimeout(35000);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setRequestProperty("x-api-key", apiKey);
            conn.setRequestProperty("anthropic-version", "2023-06-01");

            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload.toString().getBytes(StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();
            String resp = readResponseString(conn);
            if (code >= 400) {
                throw new IllegalStateException("Claude 接口返回 HTTP " + code + ": " + extractShortError(resp));
            }

            JSONObject root = new JSONObject(resp);
            JSONArray contentArr = root.optJSONArray("content");
            if (contentArr != null && contentArr.length() > 0) {
                return contentArr.getJSONObject(0).optString("text", "").trim();
            }
            throw new IllegalStateException("Claude 未返回有效译文");
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String normalizeOpenAiEndpoint(String rawUrl) {
        if (rawUrl == null || rawUrl.trim().isEmpty()) {
            return "https://api.deepseek.com/v1/chat/completions";
        }
        String u = rawUrl.trim();
        if (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        if (u.endsWith("/chat/completions")) {
            return u;
        }
        if (u.endsWith("/v1") || u.endsWith("/v4") || u.endsWith("/compatible-mode/v1")) {
            return u + "/chat/completions";
        }
        return u + "/v1/chat/completions";
    }

    private static List<String> splitModelOutputToParagraphs(String rawOutput, int expectedCount) {
        if (rawOutput == null || rawOutput.isEmpty()) {
            return Collections.emptyList();
        }
        String[] parts = rawOutput.split("(?i)\\s*[@＠]{1,3}\\s*LR_PARA_SEP\\s*[@＠]{1,3}\\s*");
        List<String> list = new ArrayList<>(expectedCount);
        if (parts.length == expectedCount) {
            for (String p : parts) list.add(p.trim());
            return list;
        }
        if (parts.length > 1) {
            for (String p : parts) {
                if (!p.trim().isEmpty()) list.add(p.trim());
            }
            if (list.size() == expectedCount) {
                return list;
            }
            while (list.size() < expectedCount) list.add("");
            return list;
        }
        String[] lines = rawOutput.split("\\r?\\n+");
        for (String line : lines) {
            String t = line.trim();
            if (!t.isEmpty()) list.add(t);
        }
        while (list.size() < expectedCount) list.add("");
        return list;
    }

    // ==================== C. PARAGRAPH SPLITTING & NATURAL TYPOGRAPHY ASSEMBLY ====================

    private static List<String> splitIntoReadableParagraphs(String rawChapterContent) {
        List<String> list = new ArrayList<>();
        if (rawChapterContent == null || rawChapterContent.isEmpty()) return list;
        String[] lines = rawChapterContent.split("\\r?\\n");
        for (String line : lines) {
            String trimmed = line.replace('　', ' ').trim();
            if (!trimmed.isEmpty()) {
                list.add(trimmed);
            }
        }
        return list;
    }

    private static String assembleChapterText(
            List<String> originalParas,
            String[] translatedParas,
            int translatedUpToCount,
            int transMode) {
        StringBuilder sb = new StringBuilder(originalParas.size() * 120);
        int total = originalParas.size();
        for (int i = 0; i < total; i++) {
            String orig = originalParas.get(i);
            String trans = (i < translatedUpToCount && translatedParas[i] != null && !translatedParas[i].isEmpty())
                    ? translatedParas[i]
                    : null;

            if (trans != null) {
                if (transMode == ReadingSettings.TRANS_MODE_BILINGUAL) {
                    sb.append(orig).append("\n").append(trans);
                } else {
                    sb.append(trans);
                }
            } else {
                sb.append(orig);
            }
            if (i < total - 1) {
                sb.append("\n");
            }
        }
        return sb.toString();
    }

    private static String cleanTranslatedParagraph(String s) {
        if (s == null) return "";
        return s.replaceAll("(?i)[@＠]{1,3}\\s*LR_PARA_SEP\\s*[@＠]{1,3}", "")
                .replace("@@LR_PARA_SEP@@", "")
                .replace('\r', ' ')
                .replace('\n', ' ')
                .replace('　', ' ')
                .trim();
    }

    private static boolean containsSingleChineseChar(String s) {
        if (s == null || s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF) {
                return true;
            }
        }
        return false;
    }

    public static boolean containsForeignLetters(String s) {
        if (s == null || s.isEmpty()) return false;
        int len = s.length();
        int foreignCount = 0;
        for (int i = 0; i < len; i++) {
            char c = s.charAt(i);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= 0x00C0 && c <= 0x04FF)   // Latin Extended, Greek, Cyrillic (Russian)
                    || (c >= 0x3040 && c <= 0x30FF)   // Japanese Hiragana & Katakana
                    || (c >= 0xAC00 && c <= 0xD7AF)) { // Korean Hangul
                foreignCount++;
                if (foreignCount >= 2) return true;
            }
        }
        return false;
    }

    private static boolean hasChineseCharacters(List<String> list) {
        if (list == null || list.isEmpty()) return false;
        int zhParas = 0;
        for (String s : list) {
            if (containsSingleChineseChar(s)) {
                zhParas++;
            }
        }
        if (list.size() <= 2) {
            return zhParas >= 1;
        }
        return zhParas * 2 >= list.size();
    }

    private static String decodeBasicHtmlEntities(String s) {
        if (s == null) return "";
        return s.replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&")
                .replace("&nbsp;", " ");
    }

    private static String extractShortError(String body) {
        if (body == null || body.isEmpty()) return "空响应";
        try {
            JSONObject obj = new JSONObject(body);
            JSONObject err = obj.optJSONObject("error");
            if (err != null && err.has("message")) {
                return err.optString("message");
            }
            if (obj.has("message")) {
                return obj.optString("message");
            }
        } catch (Exception ignored) {
        }
        return body.length() > 90 ? body.substring(0, 90) + "…" : body;
    }

    private static String readResponseString(HttpURLConnection conn) throws Exception {
        InputStream is = null;
        try {
            is = conn.getInputStream();
        } catch (Exception e) {
            is = conn.getErrorStream();
        }
        if (is == null) return "";
        byte[] bytes = readStreamBytes(is);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static byte[] readStreamBytes(InputStream is) throws Exception {
        try (BufferedInputStream bis = new BufferedInputStream(is);
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = bis.read(buf)) != -1) {
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        }
    }
}
