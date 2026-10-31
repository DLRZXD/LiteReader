package com.litereader.app.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Build;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.TypedValue;
import android.view.DisplayCutout;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowInsets;
import android.view.animation.DecelerateInterpolator;

import com.litereader.app.data.ReadingSettings;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Custom Horizontal Paging & Full-Screen Reader View with Complete Typography Engine:
 * - Supports Font Size (sp), Line Spacing, Paragraph Spacing (em), Letter Spacing (em),
 *   Horizontal Margins (dp), First-Line Indentation toggle, and CJK Character-Level Full Justification (两端对齐).
 * - No double-tap zoom (`doubleTapDetector` removed) so rapid page-turning never alters font scale.
 * - Full-screen mode toggle support (`fullScreenReading`).
 */
public class PagedReaderView extends View {

    public interface ReaderGestureListener {
        boolean isMenuVisible();
        void onToggleMenu();
        void onRetractMenu();
        void onRequestPrevPage();
        void onRequestNextPage();
        void onViewportSizeChanged(int width, int height);
    }

    public static class RenderedLine {
        public final String text;
        public final int startCharOffset;
        public final int endCharOffset;
        public final boolean endsParagraph;
        public final int lineHeightPx;
        public final int extraParaGapPx;

        public RenderedLine(String text, int startCharOffset, int endCharOffset,
                            boolean endsParagraph, int lineHeightPx, int extraParaGapPx) {
            this.text = text;
            this.startCharOffset = startCharOffset;
            this.endCharOffset = endCharOffset;
            this.endsParagraph = endsParagraph;
            this.lineHeightPx = lineHeightPx;
            this.extraParaGapPx = extraParaGapPx;
        }
    }

    public static class PageSlice {
        public final int startCharOffset;
        public final int endCharOffset;
        public final List<RenderedLine> lines;

        public PageSlice(int startCharOffset, int endCharOffset, List<RenderedLine> lines) {
            this.startCharOffset = startCharOffset;
            this.endCharOffset = endCharOffset;
            this.lines = lines;
        }
    }

    private final TextPaint bodyPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint charMeasurePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint footerPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bgPaint = new Paint();
    private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint curlBackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pdfPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

    private ReaderGestureListener gestureListener;

    private String bookTitle = "";
    private String chapterTitle = "";
    private String fullChapterText = "";
    private int chapterIndex = 0;
    private int totalChapters = 1;

    private boolean isPdfMode = false;
    private boolean isPdfReflowMode = false;
    private final List<Bitmap> pdfSubPages = new ArrayList<>();

    private final List<PageSlice> pages = new ArrayList<>();
    private int currentPageIndex = 0;
    private int pendingCharOffset = 0;

    private int bgColor = Color.parseColor("#F4ECD8");
    private int textColor = Color.parseColor("#3B2F2F");
    private int secondaryColor = Color.parseColor("#6E6259");
    private float fontSizeSp = 20f;
    private float lineSpacingMult = 1.60f;
    private float paragraphSpacingEm = 0.55f;
    private float letterSpacingEm = 0.000f;
    private boolean firstLineIndent = true;
    private boolean justifyAlign = true;
    private int horizontalMarginPx = 42;
    private int topSafeOffsetPx = 110;
    private int bottomFooterPx = 64;
    private int pageAnimStyle = ReadingSettings.ANIM_COVER;

    // Two-finger Pinch-to-Zoom & Pan state (for Original PDF Page Mode only)
    private float zoomScale = 1.0f;
    private float panX = 0f;
    private float panY = 0f;
    private final ScaleGestureDetector scaleDetector;
    private boolean isScalingGesture = false;
    private boolean isPanningZoomed = false;
    private float lastTouchX;
    private float lastTouchY;

    // Animation state
    private Bitmap outgoingBitmap;
    private Bitmap incomingBitmap;
    private ValueAnimator pageAnimator;
    private float animProgress = 1f;
    private boolean isAnimating = false;
    private int animDirection = 1;

    // Touch tracking
    private float downX;
    private float downY;
    private final int touchSlop;

    public PagedReaderView(Context context) {
        super(context);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        updateMetrics();

        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScaleBegin(ScaleGestureDetector detector) {
                if (!isPdfMode || isPdfReflowMode) {
                    return false;
                }
                isScalingGesture = true;
                return true;
            }

            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                if (!isPdfMode || isPdfReflowMode) {
                    return false;
                }
                float factor = detector.getScaleFactor();
                float prevScale = zoomScale;
                zoomScale = Math.max(1.0f, Math.min(5.0f, zoomScale * factor));
                if (Math.abs(zoomScale - prevScale) > 0.001f) {
                    float fx = detector.getFocusX() - getWidth() / 2f;
                    float fy = detector.getFocusY() - getHeight() / 2f;
                    float ratio = zoomScale / prevScale;
                    panX = fx - (fx - panX) * ratio;
                    panY = fy - (fy - panY) * ratio;
                    clampPanBounds();
                    invalidate();
                }
                return true;
            }

            @Override
            public void onScaleEnd(ScaleGestureDetector detector) {
                if (zoomScale < 1.04f) {
                    resetZoom();
                }
            }
        });
    }

    public void resetZoom() {
        zoomScale = 1.0f;
        panX = 0f;
        panY = 0f;
        invalidate();
    }

    private void clampPanBounds() {
        if (zoomScale <= 1.01f) {
            panX = 0f;
            panY = 0f;
            return;
        }
        int w = Math.max(1, getWidth());
        int h = Math.max(1, getHeight());
        float maxPanX = (w * (zoomScale - 1f)) / 2f;
        float maxPanY = (h * (zoomScale - 1f)) / 2f;
        panX = Math.max(-maxPanX, Math.min(maxPanX, panX));
        panY = Math.max(-maxPanY, Math.min(maxPanY, panY));
    }

    private void updateMetrics() {
        topSafeOffsetPx = dp(42);
        bottomFooterPx = dp(30);
        footerPaint.setTextSize(sp(11.5f));
    }

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets insets) {
        if (insets != null) {
            int cutoutTop = 0;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                DisplayCutout cutout = insets.getDisplayCutout();
                if (cutout != null) {
                    cutoutTop = cutout.getSafeInsetTop();
                }
            }
            boolean isLandscape = getWidth() > getHeight() && getWidth() > 0;
            int minTop = isLandscape ? dp(18) : dp(42);
            int newTopOffset = Math.max(minTop, cutoutTop + dp(10));
            if (newTopOffset != topSafeOffsetPx) {
                topSafeOffsetPx = newTopOffset;
                if (getWidth() > 0 && getHeight() > 0 && !isPdfMode) {
                    pendingCharOffset = getCurrentCharOffset();
                    repaginateChapter();
                }
                invalidate();
            }
        }
        return super.onApplyWindowInsets(insets);
    }

    public int getAvailableContentHeightPx(int totalScreenHeight) {
        return Math.max(dp(180), totalScreenHeight - topSafeOffsetPx - bottomFooterPx);
    }

    public void setGestureListener(ReaderGestureListener listener) {
        this.gestureListener = listener;
    }

    public void applyStyle(int bgColor, int textColor, int secondaryColor,
                           Typeface typeface, float fontSizeSp, float lineSpacingMult,
                           float paragraphSpacingEm, float letterSpacingEm,
                           boolean firstLineIndent, boolean justifyAlign,
                           int horizontalMarginPx, int pageAnimStyle) {
        boolean layoutChanged = (Math.abs(this.fontSizeSp - fontSizeSp) > 0.01f)
                || (Math.abs(this.lineSpacingMult - lineSpacingMult) > 0.005f)
                || (Math.abs(this.paragraphSpacingEm - paragraphSpacingEm) > 0.005f)
                || (Math.abs(this.letterSpacingEm - letterSpacingEm) > 0.001f)
                || (this.firstLineIndent != firstLineIndent)
                || (this.justifyAlign != justifyAlign)
                || (this.horizontalMarginPx != horizontalMarginPx)
                || (bodyPaint.getTypeface() != typeface);

        int savedCharOffset = getCurrentCharOffset();

        this.bgColor = bgColor;
        this.textColor = textColor;
        this.secondaryColor = secondaryColor;
        this.fontSizeSp = fontSizeSp;
        this.lineSpacingMult = lineSpacingMult;
        this.paragraphSpacingEm = paragraphSpacingEm;
        this.letterSpacingEm = letterSpacingEm;
        this.firstLineIndent = firstLineIndent;
        this.justifyAlign = justifyAlign;
        this.horizontalMarginPx = horizontalMarginPx;
        this.pageAnimStyle = pageAnimStyle;

        bgPaint.setColor(bgColor);
        bodyPaint.setColor(textColor);
        bodyPaint.setTypeface(typeface);
        bodyPaint.setTextSize(sp(fontSizeSp));
        bodyPaint.setLetterSpacing(letterSpacingEm);

        charMeasurePaint.setColor(textColor);
        charMeasurePaint.setTypeface(typeface);
        charMeasurePaint.setTextSize(sp(fontSizeSp));
        charMeasurePaint.setLetterSpacing(0f);

        footerPaint.setColor(secondaryColor);
        footerPaint.setTypeface(typeface);
        footerPaint.setTextSize(sp(11.5f));

        if (layoutChanged && getWidth() > 0 && getHeight() > 0 && !isPdfMode) {
            pendingCharOffset = savedCharOffset;
            repaginateChapter();
        }
        invalidate();
    }

    private String statusMessage = "正在打开书籍…";
    private boolean pendingJumpToLastPage = false;

    public void setStatusMessage(String message) {
        this.statusMessage = message != null ? message : "";
        this.pages.clear();
        invalidate();
    }

    public void setChapterContent(String bookTitle, int chapterIndex, int totalChapters,
                                  String chapterTitle, String content, int initialCharOffset,
                                  boolean jumpToLastPage) {
        this.isPdfMode = false;
        this.isPdfReflowMode = false;
        this.pdfSubPages.clear();
        this.pages.clear();
        this.statusMessage = "";
        resetZoom();

        this.bookTitle = bookTitle != null ? bookTitle : "";
        this.chapterIndex = chapterIndex;
        this.totalChapters = Math.max(1, totalChapters);
        this.chapterTitle = chapterTitle != null ? chapterTitle : "";
        this.fullChapterText = content != null ? content : "";
        this.pendingCharOffset = Math.max(0, initialCharOffset);
        this.pendingJumpToLastPage = jumpToLastPage;

        if (getWidth() > 0 && getHeight() > 0) {
            repaginateChapter();
            if (jumpToLastPage && !pages.isEmpty()) {
                currentPageIndex = pages.size() - 1;
            }
            this.pendingJumpToLastPage = false;
        }
        invalidate();
        post(() -> {
            if (!isPdfMode && pages.isEmpty() && getWidth() > 0 && getHeight() > 0) {
                repaginateChapter();
                if (pendingJumpToLastPage && !pages.isEmpty()) {
                    currentPageIndex = pages.size() - 1;
                }
                pendingJumpToLastPage = false;
                invalidate();
            }
        });
    }

    public void setPdfPageBitmaps(String bookTitle, int pageIndex, int totalPages,
                                  String pageTitle, List<Bitmap> subBitmaps,
                                  boolean isReflowMode, boolean jumpToLastSubPage) {
        this.isPdfMode = true;
        this.isPdfReflowMode = isReflowMode;
        this.statusMessage = "";
        this.bookTitle = bookTitle != null ? bookTitle : "";
        this.chapterIndex = pageIndex;
        this.totalChapters = Math.max(1, totalPages);
        this.chapterTitle = pageTitle != null ? pageTitle : ("第 " + (pageIndex + 1) + " 页");

        this.pdfSubPages.clear();
        if (subBitmaps != null) {
            this.pdfSubPages.addAll(subBitmaps);
        }

        this.pages.clear();
        int count = Math.max(1, this.pdfSubPages.size());
        for (int i = 0; i < count; i++) {
            this.pages.add(new PageSlice(i, i + 1, new ArrayList<>()));
        }
        this.currentPageIndex = jumpToLastSubPage ? (count - 1) : 0;
        resetZoom();
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w > 0 && h > 0) {
            boolean isLandscape = w > h;
            int cutoutTop = 0;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && getRootWindowInsets() != null) {
                DisplayCutout cutout = getRootWindowInsets().getDisplayCutout();
                if (cutout != null) cutoutTop = cutout.getSafeInsetTop();
            }
            topSafeOffsetPx = Math.max(isLandscape ? dp(18) : dp(42), cutoutTop + dp(10));

            if (!isPdfMode) {
                int charOffset = getCurrentCharOffset();
                if (charOffset > 0) {
                    pendingCharOffset = charOffset;
                }
                if (!fullChapterText.isEmpty() || !chapterTitle.isEmpty()) {
                    repaginateChapter();
                    if (pendingJumpToLastPage && !pages.isEmpty()) {
                        currentPageIndex = pages.size() - 1;
                    }
                    pendingJumpToLastPage = false;
                }
                invalidate();
            } else if (oldw > 0 && oldh > 0 && (w != oldw || h != oldh) && gestureListener != null) {
                gestureListener.onViewportSizeChanged(w, h);
            }
        }
    }

    /**
     * Formats paragraphs according to `firstLineIndent` and breaks lines using precision CJK + Latin
     * character-level wrapping with kinsoku shori (punctuation rules), then groups lines into pages
     * accounting for both `lineSpacingMult` and `paragraphSpacingEm`.
     */
    private void repaginateChapter() {
        pages.clear();
        int contentWidth = Math.max(dp(120), getWidth() - (horizontalMarginPx * 2));
        int contentHeight = Math.max(dp(120), getHeight() - topSafeOffsetPx - bottomFooterPx);

        String displayString = buildDisplayTextWithIndent(chapterTitle, fullChapterText, firstLineIndent);
        if (displayString.trim().isEmpty()) {
            pages.add(new PageSlice(0, 0, new ArrayList<>()));
            currentPageIndex = 0;
            return;
        }

        bodyPaint.setLetterSpacing(letterSpacingEm);
        float fontSizePx = bodyPaint.getTextSize();
        float extraLetterPx = fontSizePx * letterSpacingEm;
        int lineHeightPx = Math.max(dp(14), Math.round(fontSizePx * lineSpacingMult));
        int paraExtraGapPx = Math.max(0, Math.round(fontSizePx * paragraphSpacingEm));

        List<RenderedLine> allLines = wrapTextIntoLines(
                displayString, contentWidth, extraLetterPx, lineHeightPx, paraExtraGapPx);

        int idx = 0;
        while (idx < allLines.size()) {
            List<RenderedLine> pageLines = new ArrayList<>();
            int usedHeight = 0;
            int pageStartChar = allLines.get(idx).startCharOffset;
            int pageEndChar = pageStartChar;

            while (idx < allLines.size()) {
                RenderedLine rl = allLines.get(idx);
                int prevGap = pageLines.isEmpty() ? 0 : pageLines.get(pageLines.size() - 1).extraParaGapPx;
                int needed = prevGap + rl.lineHeightPx;
                if (!pageLines.isEmpty() && usedHeight + needed > contentHeight) {
                    break;
                }
                usedHeight += needed;
                pageLines.add(rl);
                pageEndChar = rl.endCharOffset;
                idx++;
            }
            pages.add(new PageSlice(pageStartChar, pageEndChar, pageLines));
        }

        if (pages.isEmpty()) {
            pages.add(new PageSlice(0, displayString.length(), new ArrayList<>()));
        }

        int matchedPage = 0;
        for (int i = 0; i < pages.size(); i++) {
            PageSlice ps = pages.get(i);
            if (pendingCharOffset >= ps.startCharOffset && pendingCharOffset < ps.endCharOffset) {
                matchedPage = i;
                break;
            } else if (pendingCharOffset >= ps.endCharOffset) {
                matchedPage = i;
            }
        }
        currentPageIndex = Math.max(0, Math.min(pages.size() - 1, matchedPage));
        pendingCharOffset = 0;
    }

    /**
     * Precision CJK + Latin greedy line wrapper with punctuation kinsoku rules so every wrapped
     * mid-paragraph line fills `contentWidth` tightly and justifies flush left and right.
     */
    private List<RenderedLine> wrapTextIntoLines(String displayString, int contentWidth,
                                                 float extraLetterPx, int lineHeightPx, int paraExtraGapPx) {
        List<RenderedLine> lines = new ArrayList<>();
        int totalLen = displayString.length();
        float[] widths = new float[totalLen];
        charMeasurePaint.getTextWidths(displayString, widths);

        int paraStart = 0;
        while (paraStart < totalLen) {
            int nl = displayString.indexOf('\n', paraStart);
            int paraEnd = (nl >= 0) ? nl : totalLen;
            int nextParaStart = (nl >= 0) ? (nl + 1) : totalLen;

            while (paraEnd > paraStart && (displayString.charAt(paraEnd - 1) == '\r'
                    || displayString.charAt(paraEnd - 1) == ' '
                    || displayString.charAt(paraEnd - 1) == '\t')) {
                paraEnd--;
            }

            if (paraEnd <= paraStart) {
                paraStart = nextParaStart;
                continue;
            }

            int lineStart = paraStart;
            while (lineStart < paraEnd) {
                // Skip leading ASCII spaces on wrapped continuation lines (preserve full-width '　' at paraStart)
                if (lineStart > paraStart) {
                    while (lineStart < paraEnd && displayString.charAt(lineStart) == ' ') {
                        lineStart++;
                    }
                    if (lineStart >= paraEnd) break;
                }

                float wSum = 0f;
                int cur = lineStart;
                int lastAsciiBreak = -1;

                while (cur < paraEnd) {
                    char c = displayString.charAt(cur);
                    float cw = widths[cur] + (cur > lineStart ? extraLetterPx : 0f);
                    if (cur > lineStart && wSum + cw > contentWidth) {
                        break;
                    }
                    wSum += cw;
                    if (c == ' ' || c == '-') {
                        lastAsciiBreak = cur + 1;
                    }
                    cur++;
                }

                if (cur >= paraEnd) {
                    // Final line of paragraph
                    String lineStr = displayString.substring(lineStart, paraEnd);
                    lines.add(new RenderedLine(lineStr, lineStart, nextParaStart, true, lineHeightPx, paraExtraGapPx));
                    break;
                }

                int breakPos = cur;
                // If breaking inside an ASCII English word, break at the last space if it's near the end of the line
                if (isAsciiWordChar(displayString.charAt(breakPos - 1))
                        && isAsciiWordChar(displayString.charAt(breakPos))
                        && lastAsciiBreak > lineStart + 4
                        && (breakPos - lastAsciiBreak) <= 14) {
                    breakPos = lastAsciiBreak;
                } else {
                    // CJK Kinsoku Shori: Do not let closing punctuation start the next line!
                    char nextChar = displayString.charAt(breakPos);
                    if (isForbiddenLineStartPunctuation(nextChar) && breakPos + 1 <= paraEnd) {
                        breakPos++;
                        // If followed by another closing quote/bracket (e.g. 。” or ！）), pull it too
                        if (breakPos < paraEnd && isForbiddenLineStartPunctuation(displayString.charAt(breakPos))) {
                            breakPos++;
                        }
                    } else if (breakPos - 1 > lineStart && isForbiddenLineEndOpeningPunctuation(displayString.charAt(breakPos - 1))) {
                        // Do not leave opening quote/bracket alone at the very end of a line
                        breakPos--;
                    }
                }

                if (breakPos <= lineStart) {
                    breakPos = lineStart + 1;
                }

                boolean isNowParaEnd = (breakPos >= paraEnd);
                String lineStr = displayString.substring(lineStart, breakPos);
                lines.add(new RenderedLine(
                        lineStr,
                        lineStart,
                        isNowParaEnd ? nextParaStart : breakPos,
                        isNowParaEnd,
                        lineHeightPx,
                        isNowParaEnd ? paraExtraGapPx : 0
                ));
                lineStart = breakPos;
            }

            paraStart = nextParaStart;
        }
        return lines;
    }

    private static boolean isAsciiWordChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
    }

    private static boolean isForbiddenLineStartPunctuation(char c) {
        return "，。！？；：、”’）》】』」〉》,.!?;:)]}%".indexOf(c) >= 0;
    }

    private static boolean isForbiddenLineEndOpeningPunctuation(char c) {
        return "“‘（《【『「〈([".indexOf(c) >= 0;
    }

    private static boolean isRightPaddedCjkPunctuation(char c) {
        return "，。！？；：、”’）》】』」〉".indexOf(c) >= 0;
    }

    private static boolean isLeftPaddedCjkPunctuation(char c) {
        return "“‘（《【『「〈".indexOf(c) >= 0;
    }

    public static String buildDisplayTextWithIndent(String chapterTitle, String rawBody, boolean firstLineIndent) {
        StringBuilder sb = new StringBuilder();
        if (chapterTitle != null && !chapterTitle.trim().isEmpty()) {
            sb.append("【").append(chapterTitle.trim()).append("】\n");
        }
        if (rawBody != null && !rawBody.isEmpty()) {
            String[] paras = rawBody.split("\\n+");
            for (String p : paras) {
                String stripped = p.replaceAll("^[ \\t　]+", "").trim();
                if (stripped.isEmpty()) continue;
                boolean isHeading = stripped.startsWith("【") || stripped.startsWith("第") || stripped.startsWith("Chapter");
                if (firstLineIndent && !isHeading) {
                    sb.append("　　").append(stripped).append("\n");
                } else {
                    sb.append(stripped).append("\n");
                }
            }
        }
        return sb.toString();
    }

    public int getCurrentPageIndex() {
        return currentPageIndex;
    }

    public int getPageCount() {
        return Math.max(1, pages.size());
    }

    public int getCurrentCharOffset() {
        if (pages.isEmpty() || currentPageIndex < 0 || currentPageIndex >= pages.size()) {
            return pendingCharOffset;
        }
        return pages.get(currentPageIndex).startCharOffset;
    }

    public float getIntraChapterProgress() {
        if (pages.isEmpty()) return 0f;
        return (float) (currentPageIndex + 1) / (float) pages.size();
    }

    public boolean hasPrevPageInChapter() {
        return currentPageIndex > 0;
    }

    public boolean hasNextPageInChapter() {
        return currentPageIndex + 1 < pages.size();
    }

    public void turnToPrevPageInChapter() {
        if (currentPageIndex > 0) {
            resetZoom();
            animatePageTransition(currentPageIndex - 1, -1, null);
        }
    }

    public void turnToNextPageInChapter() {
        if (currentPageIndex + 1 < pages.size()) {
            resetZoom();
            animatePageTransition(currentPageIndex + 1, 1, null);
        }
    }

    public void playExternalPageTransition(int direction, Runnable stateUpdater) {
        resetZoom();
        if (getWidth() <= 0 || getHeight() <= 0 || pageAnimStyle == ReadingSettings.ANIM_NONE) {
            stateUpdater.run();
            invalidate();
            return;
        }
        cancelRunningAnimation();
        outgoingBitmap = renderSnapshotBitmap();
        stateUpdater.run();
        incomingBitmap = renderSnapshotBitmap();
        startAnimator(direction);
    }

    private void animatePageTransition(final int targetPageIdx, int direction, final Runnable afterUpdate) {
        if (getWidth() <= 0 || getHeight() <= 0 || pageAnimStyle == ReadingSettings.ANIM_NONE) {
            currentPageIndex = targetPageIdx;
            if (afterUpdate != null) afterUpdate.run();
            invalidate();
            return;
        }
        cancelRunningAnimation();
        outgoingBitmap = renderSnapshotBitmap();
        currentPageIndex = targetPageIdx;
        if (afterUpdate != null) afterUpdate.run();
        incomingBitmap = renderSnapshotBitmap();
        startAnimator(direction);
    }

    private void startAnimator(int direction) {
        this.animDirection = direction;
        this.isAnimating = true;
        this.animProgress = 0f;

        pageAnimator = ValueAnimator.ofFloat(0f, 1f);
        pageAnimator.setDuration(220);
        pageAnimator.setInterpolator(new DecelerateInterpolator());
        pageAnimator.addUpdateListener(animation -> {
            animProgress = (float) animation.getAnimatedValue();
            invalidate();
        });
        pageAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                isAnimating = false;
                animProgress = 1f;
                invalidate();
            }
        });
        pageAnimator.start();
    }

    private void cancelRunningAnimation() {
        if (pageAnimator != null && pageAnimator.isRunning()) {
            pageAnimator.cancel();
        }
        isAnimating = false;
    }

    private Bitmap renderSnapshotBitmap() {
        int w = Math.max(1, getWidth());
        int h = Math.max(1, getHeight());
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        drawCurrentPageContent(c, w, h);
        return bmp;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        if (!isAnimating || outgoingBitmap == null || incomingBitmap == null || pageAnimStyle == ReadingSettings.ANIM_NONE) {
            drawCurrentPageContent(canvas, w, h);
            return;
        }

        switch (pageAnimStyle) {
            case ReadingSettings.ANIM_SLIDE:
                drawSlideAnimation(canvas, w, h);
                break;
            case ReadingSettings.ANIM_SIMULATION:
                drawSimulationCurlAnimation(canvas, w, h);
                break;
            case ReadingSettings.ANIM_FADE:
                drawFadeAnimation(canvas, w, h);
                break;
            case ReadingSettings.ANIM_COVER:
            default:
                drawCoverAnimation(canvas, w, h);
                break;
        }
    }

    private void drawCoverAnimation(Canvas canvas, int w, int h) {
        if (animDirection > 0) {
            canvas.drawBitmap(incomingBitmap, 0, 0, null);
            float offsetX = -animProgress * w;
            canvas.save();
            canvas.translate(offsetX, 0);
            canvas.drawBitmap(outgoingBitmap, 0, 0, null);
            drawVerticalEdgeShadow(canvas, w, w + dp(18), h, 0x66000000, 0x00000000);
            canvas.restore();
        } else {
            canvas.drawBitmap(outgoingBitmap, 0, 0, null);
            float offsetX = (-1f + animProgress) * w;
            canvas.save();
            canvas.translate(offsetX, 0);
            canvas.drawBitmap(incomingBitmap, 0, 0, null);
            drawVerticalEdgeShadow(canvas, w, w + dp(18), h, 0x66000000, 0x00000000);
            canvas.restore();
        }
    }

    private void drawSlideAnimation(Canvas canvas, int w, int h) {
        if (animDirection > 0) {
            float outX = -animProgress * w;
            float inX = (1f - animProgress) * w;
            canvas.drawBitmap(outgoingBitmap, outX, 0, null);
            canvas.drawBitmap(incomingBitmap, inX, 0, null);
        } else {
            float outX = animProgress * w;
            float inX = (-1f + animProgress) * w;
            canvas.drawBitmap(outgoingBitmap, outX, 0, null);
            canvas.drawBitmap(incomingBitmap, inX, 0, null);
        }
    }

    private void drawSimulationCurlAnimation(Canvas canvas, int w, int h) {
        float t = animDirection > 0 ? (1f - animProgress) : animProgress;
        Bitmap topPage = animDirection > 0 ? outgoingBitmap : incomingBitmap;
        Bitmap underPage = animDirection > 0 ? incomingBitmap : outgoingBitmap;

        float foldX = Math.max(0f, Math.min(w, w * t));
        float curlWidth = Math.max(dp(16), Math.min(w * 0.24f, (w - foldX) * 0.55f));

        canvas.drawBitmap(underPage, 0, 0, null);

        canvas.save();
        canvas.clipRect(0, 0, foldX, h);
        canvas.drawBitmap(topPage, 0, 0, null);
        canvas.restore();

        float flapLeft = Math.max(0f, foldX - curlWidth);
        curlBackPaint.setColor(bgColor);
        curlBackPaint.setAlpha(235);
        canvas.drawRect(flapLeft, 0, foldX, h, curlBackPaint);

        drawVerticalEdgeShadow(canvas, flapLeft - dp(10), flapLeft, h, 0x00000000, 0x44000000);
        drawVerticalEdgeShadow(canvas, foldX, Math.min(w, foldX + dp(24)), h, 0x77000000, 0x00000000);
    }

    private void drawFadeAnimation(Canvas canvas, int w, int h) {
        canvas.drawBitmap(outgoingBitmap, 0, 0, null);
        Paint alphaPaint = new Paint();
        alphaPaint.setAlpha((int) (255 * animProgress));
        canvas.drawBitmap(incomingBitmap, 0, 0, alphaPaint);
    }

    private void drawVerticalEdgeShadow(Canvas canvas, float left, float right, int h, int startColor, int endColor) {
        if (right <= left) return;
        LinearGradient grad = new LinearGradient(left, 0, right, 0, startColor, endColor, Shader.TileMode.CLAMP);
        shadowPaint.setShader(grad);
        canvas.drawRect(left, 0, right, h, shadowPaint);
    }

    private void drawCurrentPageContent(Canvas canvas, int w, int h) {
        canvas.drawRect(0, 0, w, h, bgPaint);

        int contentTop = topSafeOffsetPx;
        int contentBottom = h - bottomFooterPx;
        int availH = Math.max(dp(100), contentBottom - contentTop);

        if (isPdfMode) {
            Bitmap bmp = null;
            if (!pdfSubPages.isEmpty()) {
                int idx = Math.max(0, Math.min(pdfSubPages.size() - 1, currentPageIndex));
                bmp = pdfSubPages.get(idx);
            }

            if (bmp != null && !bmp.isRecycled()) {
                canvas.save();
                canvas.clipRect(0, contentTop, w, contentBottom);
                if (!isPdfReflowMode && zoomScale > 1.005f) {
                    canvas.translate(w / 2f + panX, h / 2f + panY);
                    canvas.scale(zoomScale, zoomScale);
                    canvas.translate(-w / 2f, -h / 2f);
                }

                int bw = bmp.getWidth();
                int bh = bmp.getHeight();
                if (isPdfReflowMode) {
                    Rect dst = new Rect(0, contentTop, w, contentBottom);
                    canvas.drawBitmap(bmp, null, dst, pdfPaint);
                } else {
                    float padPx = dp(4);
                    float maxW = Math.max(1f, w - padPx * 2);
                    float maxH = Math.max(1f, availH);
                    float scale = Math.min(maxW / Math.max(1, bw), maxH / Math.max(1, bh));
                    float drawW = bw * scale;
                    float drawH = bh * scale;
                    float left = (w - drawW) / 2f;
                    float top = contentTop + (availH - drawH) / 2f;
                    RectF dst = new RectF(left, top, left + drawW, top + drawH);
                    canvas.drawBitmap(bmp, null, dst, pdfPaint);
                }
                canvas.restore();
            }
        } else {
            int contentWidth = Math.max(dp(100), w - (horizontalMarginPx * 2));
            if (pages.isEmpty() && (!fullChapterText.isEmpty() || !chapterTitle.isEmpty())) {
                repaginateChapter();
                if (pendingJumpToLastPage && !pages.isEmpty()) {
                    currentPageIndex = pages.size() - 1;
                }
                pendingJumpToLastPage = false;
            }
            if (!pages.isEmpty() && !pages.get(Math.max(0, Math.min(pages.size() - 1, currentPageIndex))).lines.isEmpty()) {
                int idx = Math.max(0, Math.min(pages.size() - 1, currentPageIndex));
                List<RenderedLine> pageLines = pages.get(idx).lines;
                Paint.FontMetrics fm = bodyPaint.getFontMetrics();
                float baselineOffset = -fm.ascent;

                canvas.save();
                canvas.clipRect(0, contentTop, w, contentBottom);
                int drawY = contentTop;
                for (int i = 0; i < pageLines.size(); i++) {
                    RenderedLine rl = pageLines.get(i);
                    float baselineY = drawY + baselineOffset;
                    if (justifyAlign && !rl.endsParagraph && rl.text.length() > 1) {
                        drawJustifiedLine(canvas, rl.text, horizontalMarginPx, baselineY, contentWidth);
                    } else {
                        canvas.drawText(rl.text, horizontalMarginPx, baselineY, bodyPaint);
                    }
                    drawY += rl.lineHeightPx + rl.extraParaGapPx;
                }
                canvas.restore();
            } else if (statusMessage != null && !statusMessage.isEmpty()) {
                Paint.FontMetrics fm = bodyPaint.getFontMetrics();
                float tw = bodyPaint.measureText(statusMessage);
                float tx = Math.max(horizontalMarginPx, (w - tw) / 2f);
                float ty = contentTop + availH / 2f - (fm.ascent + fm.descent) / 2f;
                canvas.drawText(statusMessage, tx, ty, bodyPaint);
            }
        }

        // Bottom Status Bar ONLY:
        // Bottom-Left (左下角): Chapter title (章节)
        // Bottom-Right (右下角): Page count & percentage progress (页数，百分之进度)
        int sideMargin = isPdfMode ? dp(14) : Math.max(dp(12), horizontalMarginPx);
        float footerBaselineY = h - dp(10);

        float overallPct = ((chapterIndex + getIntraChapterProgress()) / (float) Math.max(1, totalChapters)) * 100f;
        overallPct = Math.max(0f, Math.min(100f, overallPct));

        String bottomRight;
        if (isPdfMode) {
            int subTotal = Math.max(1, pdfSubPages.size());
            if (subTotal > 1) {
                bottomRight = String.format(Locale.US, "%d/%d页(%d/%d) · %.1f%%",
                        chapterIndex + 1, totalChapters, currentPageIndex + 1, subTotal, overallPct);
            } else {
                bottomRight = String.format(Locale.US, "%d/%d页 · %.1f%%",
                        chapterIndex + 1, totalChapters, overallPct);
            }
        } else {
            bottomRight = String.format(Locale.US, "%d/%d页 · %.1f%%",
                    currentPageIndex + 1, Math.max(1, pages.size()), overallPct);
        }
        float brWidth = footerPaint.measureText(bottomRight);
        canvas.drawText(bottomRight, w - sideMargin - brWidth, footerBaselineY, footerPaint);

        String bottomLeft = (chapterTitle != null && !chapterTitle.isEmpty())
                ? chapterTitle
                : ("第 " + (chapterIndex + 1) + " 节");
        float maxLeftWidth = Math.max(dp(60), w - (sideMargin * 2) - brWidth - dp(16));
        while (bottomLeft.length() > 4 && footerPaint.measureText(bottomLeft) > maxLeftWidth) {
            bottomLeft = bottomLeft.substring(0, bottomLeft.length() - 2) + "…";
        }
        canvas.drawText(bottomLeft, sideMargin, footerBaselineY, footerPaint);
    }

    /**
     * Draws a wrapped mid-paragraph line flush-left AND flush-right across `targetWidth` (两端对齐):
     * - Compensates for built-in right blank glyph padding on CJK closing punctuation (`，。！？；：、”’）》】`)
     *   and left blank glyph padding on CJK opening punctuation (`“‘（《【`) so the visual ink edges
     *   align 100% flush with `startX` on the left and `startX + targetWidth` on the right.
     * - Distributes any positive or negative slack evenly across all `len - 1` character gaps.
     */
    private void drawJustifiedLine(Canvas canvas, String rawLine, float startX, float baselineY, int targetWidth) {
        if (rawLine == null) return;
        int endTrim = rawLine.length();
        while (endTrim > 0 && (rawLine.charAt(endTrim - 1) == ' ' || rawLine.charAt(endTrim - 1) == '\t')) {
            endTrim--;
        }
        String line = endTrim < rawLine.length() ? rawLine.substring(0, endTrim) : rawLine;
        int len = line.length();
        if (len <= 1) {
            canvas.drawText(line, startX, baselineY, bodyPaint);
            return;
        }

        float[] widths = new float[len];
        charMeasurePaint.getTextWidths(line, widths);

        // Compensate for left bearing on first character if it is left-padded opening CJK punctuation
        float firstCharLeftShift = 0f;
        char firstChar = line.charAt(0);
        if (isLeftPaddedCjkPunctuation(firstChar)) {
            Rect firstBounds = new Rect();
            charMeasurePaint.getTextBounds(line, 0, 1, firstBounds);
            if (firstBounds.left > 0 && firstBounds.left < widths[0] * 0.65f) {
                firstCharLeftShift = firstBounds.left;
            }
        }

        // Compensate for right blank advance padding on CJK closing punctuation at the end of the line
        float lastCharEffectiveWidth = widths[len - 1];
        char lastChar = line.charAt(len - 1);
        if (isRightPaddedCjkPunctuation(lastChar)) {
            Rect lastBounds = new Rect();
            charMeasurePaint.getTextBounds(line, len - 1, len, lastBounds);
            if (lastBounds.right > 0 && lastBounds.right < widths[len - 1]) {
                lastCharEffectiveWidth = Math.max(lastBounds.right, widths[len - 1] * 0.45f);
            } else {
                lastCharEffectiveWidth = widths[len - 1] * 0.52f;
            }
        }

        float rawCharsSum = -firstCharLeftShift;
        for (int i = 0; i < len - 1; i++) {
            rawCharsSum += widths[i];
        }
        rawCharsSum += lastCharEffectiveWidth;

        if (rawCharsSum <= targetWidth * 0.50f) {
            canvas.drawText(line, startX, baselineY, bodyPaint);
            return;
        }

        float gap = (targetWidth - rawCharsSum) / (len - 1);
        float x = startX - firstCharLeftShift;
        for (int i = 0; i < len; i++) {
            if (i == len - 1) {
                // Pin the last character so its visual right edge lands exactly at startX + targetWidth
                x = (startX + targetWidth) - lastCharEffectiveWidth;
            }
            canvas.drawText(line, i, i + 1, x, baselineY, charMeasurePaint);
            x += widths[i] + gap;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (isPdfMode && !isPdfReflowMode) {
            scaleDetector.onTouchEvent(event);
        }

        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            downX = event.getX();
            downY = event.getY();
            lastTouchX = downX;
            lastTouchY = downY;
            isScalingGesture = false;
            isPanningZoomed = false;
            return true;
        } else if (action == MotionEvent.ACTION_POINTER_DOWN) {
            if (isPdfMode && !isPdfReflowMode) {
                isScalingGesture = true;
            }
            return true;
        } else if (action == MotionEvent.ACTION_MOVE) {
            if (isScalingGesture || scaleDetector.isInProgress()) {
                return true;
            }
            if (isPdfMode && !isPdfReflowMode && zoomScale > 1.03f) {
                float moveDx = event.getX() - lastTouchX;
                float moveDy = event.getY() - lastTouchY;
                if (!isPanningZoomed && (Math.abs(event.getX() - downX) > touchSlop || Math.abs(event.getY() - downY) > touchSlop)) {
                    isPanningZoomed = true;
                }
                if (isPanningZoomed) {
                    panX += moveDx;
                    panY += moveDy;
                    clampPanBounds();
                    invalidate();
                }
                lastTouchX = event.getX();
                lastTouchY = event.getY();
                return true;
            }
        } else if (action == MotionEvent.ACTION_UP) {
            if (isScalingGesture || isPanningZoomed) {
                isScalingGesture = false;
                isPanningZoomed = false;
                return true;
            }

            float upX = event.getX();
            float upY = event.getY();
            float dx = upX - downX;
            float dy = upY - downY;

            if (gestureListener == null) return true;

            if (zoomScale <= 1.05f && Math.abs(dx) > touchSlop * 2 && Math.abs(dx) > Math.abs(dy) * 1.4f) {
                if (gestureListener.isMenuVisible()) {
                    gestureListener.onRetractMenu();
                }
                if (dx < 0) {
                    gestureListener.onRequestNextPage();
                } else {
                    gestureListener.onRequestPrevPage();
                }
                return true;
            }

            if (Math.abs(dx) <= touchSlop && Math.abs(dy) <= touchSlop) {
                int w = Math.max(1, getWidth());
                float leftBoundary = w / 3.0f;
                float rightBoundary = (w * 2.0f) / 3.0f;

                if (gestureListener.isMenuVisible()) {
                    gestureListener.onRetractMenu();
                    return true;
                }

                if (upX < leftBoundary) {
                    gestureListener.onRequestPrevPage();
                } else if (upX > rightBoundary) {
                    gestureListener.onRequestNextPage();
                } else {
                    gestureListener.onToggleMenu();
                }
                return true;
            }
        }
        return super.onTouchEvent(event);
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private float sp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, getResources().getDisplayMetrics());
    }
}
