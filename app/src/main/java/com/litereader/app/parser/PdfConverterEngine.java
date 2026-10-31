package com.litereader.app.parser;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.pdf.PdfRenderer;

import com.litereader.app.data.ReadingSettings;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.Inflater;

/**
 * One-Click PDF Conversion, Natural Image Preservation & Full Typography Reflow Engine.
 *
 * Key Capabilities:
 * 1. 100% Natural Image/Photo Rendering (Never Inverted):
 *    - Automatically distinguishes photos/illustrations from text lines using height, mid-tone grayscale
 *      ratio, color saturation, and ink fill density.
 *    - Renders all photos/illustrations in their original natural colors (even in Dark/Black themes)
 *      while recoloring paper and text strokes to match the user's selected theme!
 * 2. Complete Typography Controls for PDF Reflow:
 *    - Supports Font Size (12~38sp), Line Spacing (1.00~2.50), Paragraph Spacing (0.00~2.00em),
 *      Letter Spacing (-0.050~0.300em), Horizontal Margins (0~48dp), First-Line Indentation toggle,
 *      Full Justification (两端对齐) toggle, and 4 distinct Font Styles (Serif, Bold Sans, Monospace, Classic Italic).
 */
public class PdfConverterEngine {

    public static class WordSlice {
        public final int left;
        public final int right;
        public final boolean isSpaceAfter;

        public WordSlice(int left, int right, boolean isSpaceAfter) {
            this.left = left;
            this.right = right;
            this.isSpaceAfter = isSpaceAfter;
        }

        public int width() {
            return Math.max(1, right - left);
        }
    }

    public static class TextLineStrip {
        public final Bitmap sourceBitmap;
        public final int top;
        public final int bottom;
        public final int left;
        public final int right;
        public final boolean isParagraphStart;
        public final boolean isParagraphEnd;
        public final boolean isImageBlock;
        public final List<WordSlice> words = new ArrayList<>();

        public TextLineStrip(Bitmap sourceBitmap, int top, int bottom, int left, int right,
                             boolean isParagraphStart, boolean isParagraphEnd, boolean isImageBlock) {
            this.sourceBitmap = sourceBitmap;
            this.top = top;
            this.bottom = bottom;
            this.left = left;
            this.right = right;
            this.isParagraphStart = isParagraphStart;
            this.isParagraphEnd = isParagraphEnd;
            this.isImageBlock = isImageBlock;
        }

        public int height() {
            return Math.max(1, bottom - top);
        }
    }

    public static class ReflowedPageResult {
        public final List<Bitmap> subPages = new ArrayList<>();
    }

    /**
     * Renders a PDF page at high resolution in Original Mode, crops out white margins,
     * applies theme colors to text/paper, and preserves embedded photos/illustrations in natural colors.
     */
    public static Bitmap renderHighResPage(PdfRenderer renderer, int pageIndex, int screenWidth,
                                           boolean autoCropWhiteMargins, int bgColor, int fgColor) {
        if (renderer == null || pageIndex < 0 || pageIndex >= renderer.getPageCount()) return null;
        int renderWidth = Math.min(2000, Math.max(1000, (int) (screenWidth * 1.65f)));
        Bitmap raw;
        try (PdfRenderer.Page page = renderer.openPage(pageIndex)) {
            int renderHeight = Math.max(100, (int) ((float) renderWidth * page.getHeight() / Math.max(1, page.getWidth())));
            raw = Bitmap.createBitmap(renderWidth, renderHeight, Bitmap.Config.ARGB_8888);
            raw.eraseColor(Color.WHITE);
            page.render(raw, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
        } catch (Exception e) {
            return null;
        }

        Bitmap target = raw;
        if (autoCropWhiteMargins) {
            Rect bounds = detectInkBounds(raw, 2);
            if (bounds.width() > 20 && bounds.height() > 20
                    && (bounds.width() < raw.getWidth() * 0.98f || bounds.height() < raw.getHeight() * 0.98f)) {
                try {
                    Bitmap cropped = Bitmap.createBitmap(
                            raw,
                            bounds.left,
                            bounds.top,
                            Math.max(1, bounds.width()),
                            Math.max(1, bounds.height())
                    );
                    if (cropped != raw) {
                        raw.recycle();
                    }
                    target = cropped;
                } catch (Exception ignored) {
                }
            }
        }

        // Preserve any photo/illustration strips in their natural colors while coloring text & paper
        int w = target.getWidth();
        int h = target.getHeight();
        int[] origPixels = new int[w * h];
        target.getPixels(origPixels, 0, w, 0, 0, w, h);
        Rect fullBounds = new Rect(0, 0, w, h);
        List<TextLineStrip> strips = segmentTextLines(target, origPixels, w, h, fullBounds);

        Bitmap origCopyForPhotos = null;
        boolean hasPhotos = false;
        for (TextLineStrip s : strips) {
            if (s.isImageBlock) {
                hasPhotos = true;
                break;
            }
        }
        if (hasPhotos) {
            origCopyForPhotos = target.copy(Bitmap.Config.ARGB_8888, false);
        }

        applyThemeAndFontToBitmapInPlace(target, bgColor, fgColor, ReadingSettings.FONT_SERIF);

        if (hasPhotos && origCopyForPhotos != null) {
            Canvas c = new Canvas(target);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            for (TextLineStrip s : strips) {
                if (s.isImageBlock) {
                    Rect r = new Rect(s.left, s.top, s.right, s.bottom);
                    c.drawBitmap(origCopyForPhotos, r, r, p);
                }
            }
            origCopyForPhotos.recycle();
        }
        return target;
    }

    /**
     * Full-featured PDF Reflow Engine supporting all typography sliders & switches:
     * - Preserves photos/illustrations in 100% natural un-inverted colors
     * - Applies Font Size, Line Spacing, Paragraph Spacing, Letter Spacing, Horizontal Margin,
     *   First-Line Indentation, Full Justification (两端对齐), and Font Family style.
     */
    public static ReflowedPageResult renderReflowedPdfPage(
            PdfRenderer renderer,
            int pageIndex,
            int viewportWidth,
            int viewportHeight,
            int horizontalPaddingPx,
            float fontSizeSp,
            float lineSpacingMult,
            float paragraphSpacingEm,
            float letterSpacingEm,
            boolean firstLineIndent,
            boolean justifyAlign,
            int bgColor,
            int fgColor,
            int fontFamily) {

        ReflowedPageResult result = new ReflowedPageResult();
        if (renderer == null || pageIndex < 0 || pageIndex >= renderer.getPageCount()) {
            return result;
        }

        int safeViewW = Math.max(320, viewportWidth);
        int safeViewH = Math.max(320, viewportHeight);
        int srcWidth = Math.min(1600, Math.max(1000, (int) (safeViewW * 1.35f)));

        List<Bitmap> tempSourceBitmaps = new ArrayList<>();
        List<TextLineStrip> primaryLines = extractLinesFromSinglePdfPage(renderer, pageIndex, srcWidth, tempSourceBitmaps);

        if (primaryLines.isEmpty()) {
            for (Bitmap b : tempSourceBitmaps) {
                if (b != null && !b.isRecycled()) b.recycle();
            }
            Bitmap fullCropped = renderHighResPage(renderer, pageIndex, safeViewW, true, bgColor, fgColor);
            if (fullCropped != null) {
                result.subPages.add(fullCropped);
            }
            return result;
        }

        float medianBodyLineH = computeStableMedianLineHeight(primaryLines, srcWidth);

        float targetLineHeightPx = (fontSizeSp / 19.0f) * (Math.min(safeViewW, safeViewH) * 0.066f);
        float scale = Math.max(0.90f, Math.min(3.5f, targetLineHeightPx / medianBodyLineH));

        int padX = Math.max(0, Math.min(horizontalPaddingPx, safeViewW / 3));
        int availWidth = Math.max(160, safeViewW - padX * 2);
        int availPageHeight = Math.max(200, safeViewH);

        int lineGapPx = Math.max(2, Math.round(targetLineHeightPx * Math.max(0.05f, (lineSpacingMult - 1.0f) * 0.85f)));
        int paraExtraPx = Math.max(0, Math.round(targetLineHeightPx * paragraphSpacingEm));
        int paraGapPx = lineGapPx + paraExtraPx;
        int letterGapPx = Math.round(targetLineHeightPx * letterSpacingEm);
        int baseWordGapPx = (fontFamily == ReadingSettings.FONT_MONO)
                ? Math.max(3, Math.round(targetLineHeightPx * 0.16f))
                : Math.max(1, Math.round(targetLineHeightPx * 0.06f));
        int indentPx = firstLineIndent ? Math.round(targetLineHeightPx * 2.0f) : 0;

        List<WrappedRow> wrappedRows = buildWrappedRows(
                primaryLines, medianBodyLineH, scale, padX, availWidth,
                lineGapPx, paraGapPx, letterGapPx, baseWordGapPx, indentPx, justifyAlign, fontFamily);

        // Cross-page packing: if the last sub-page of `pageIndex` is not full, pull lines from `pageIndex + 1`
        int totalPrimaryRowsH = 0;
        for (WrappedRow r : wrappedRows) {
            totalPrimaryRowsH += r.rowHeight + r.gapAfter;
        }
        int remainderH = totalPrimaryRowsH % availPageHeight;
        if ((totalPrimaryRowsH < availPageHeight * 0.85f || (remainderH > 0 && remainderH < availPageHeight * 0.82f))
                && pageIndex + 1 < renderer.getPageCount()) {
            List<TextLineStrip> nextLines = extractLinesFromSinglePdfPage(renderer, pageIndex + 1, srcWidth, tempSourceBitmaps);
            if (!nextLines.isEmpty()) {
                float nextMedianH = computeStableMedianLineHeight(nextLines, srcWidth);
                float nextScale = Math.max(0.90f, Math.min(3.5f, targetLineHeightPx / nextMedianH));
                List<WrappedRow> extraRows = buildWrappedRows(
                        nextLines, nextMedianH, nextScale, padX, availWidth,
                        lineGapPx, paraGapPx, letterGapPx, baseWordGapPx, indentPx, justifyAlign, fontFamily);
                int currentSubPageCount = Math.max(1, (int) Math.ceil((double) totalPrimaryRowsH / availPageHeight));
                List<WrappedRow> combined = new ArrayList<>(wrappedRows);
                for (WrappedRow er : extraRows) {
                    combined.add(er);
                    int pagesNeeded = countSubPagesForRows(combined, availPageHeight);
                    if (pagesNeeded > currentSubPageCount) {
                        combined.remove(combined.size() - 1);
                        break;
                    }
                }
                wrappedRows = combined;
            }
        }

        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        int rowIdx = 0;
        while (rowIdx < wrappedRows.size()) {
            List<WrappedRow> pageRows = new ArrayList<>();
            int accumulatedH = 0;
            while (rowIdx < wrappedRows.size()) {
                WrappedRow r = wrappedRows.get(rowIdx);
                int needed = r.rowHeight + (pageRows.isEmpty() ? 0 : pageRows.get(pageRows.size() - 1).gapAfter);
                if (!pageRows.isEmpty() && accumulatedH + needed > availPageHeight) {
                    break;
                }
                accumulatedH += needed;
                pageRows.add(r);
                rowIdx++;
            }

            Bitmap subBmp = Bitmap.createBitmap(safeViewW, safeViewH, Bitmap.Config.ARGB_8888);
            subBmp.eraseColor(Color.WHITE);
            Canvas canvas = new Canvas(subBmp);

            int extraGapPerRow = 0;
            if (pageRows.size() > 3 && accumulatedH >= availPageHeight * 0.65f && accumulatedH < availPageHeight) {
                extraGapPerRow = (availPageHeight - accumulatedH) / pageRows.size();
            }

            // PASS 1: Draw all TEXT items onto subBmp (with optional calligraphic slant for FONT_CLASSIC)
            int drawY = 0;
            Rect srcRect = new Rect();
            Rect dstRect = new Rect();
            for (WrappedRow r : pageRows) {
                if (!r.isImageRow) {
                    for (WrappedItem item : r.items) {
                        if (item.sourceBitmap == null || item.sourceBitmap.isRecycled()) continue;
                        srcRect.set(item.srcLeft, item.srcTop, item.srcRight, item.srcBottom);
                        int itemTop = drawY + Math.max(0, (r.rowHeight - item.dstHeight) / 2);
                        dstRect.set(item.dstX, itemTop, item.dstX + item.dstWidth, itemTop + item.dstHeight);
                        if (fontFamily == ReadingSettings.FONT_CLASSIC) {
                            canvas.save();
                            float cx = dstRect.exactCenterX();
                            float cy = dstRect.exactCenterY();
                            canvas.skew(-0.11f, 0f);
                            canvas.translate(cy * 0.11f, 0f);
                            canvas.drawBitmap(item.sourceBitmap, srcRect, dstRect, paint);
                            canvas.restore();
                        } else {
                            canvas.drawBitmap(item.sourceBitmap, srcRect, dstRect, paint);
                        }
                    }
                }
                drawY += r.rowHeight + r.gapAfter + extraGapPerRow;
            }

            // Color the background to `bgColor` and text strokes to `fgColor` with `fontFamily` contrast curve!
            applyThemeAndFontToBitmapInPlace(subBmp, bgColor, fgColor, fontFamily);

            // PASS 2: Draw all IMAGE / PHOTO blocks in their 100% natural, un-inverted original colors!
            drawY = 0;
            for (WrappedRow r : pageRows) {
                if (r.isImageRow) {
                    for (WrappedItem item : r.items) {
                        if (item.sourceBitmap == null || item.sourceBitmap.isRecycled()) continue;
                        srcRect.set(item.srcLeft, item.srcTop, item.srcRight, item.srcBottom);
                        int itemTop = drawY + Math.max(0, (r.rowHeight - item.dstHeight) / 2);
                        dstRect.set(item.dstX, itemTop, item.dstX + item.dstWidth, itemTop + item.dstHeight);
                        drawNaturalImageBlock(canvas, item.sourceBitmap, srcRect, dstRect, bgColor, paint);
                    }
                }
                drawY += r.rowHeight + r.gapAfter + extraGapPerRow;
            }

            result.subPages.add(subBmp);
        }

        for (Bitmap b : tempSourceBitmaps) {
            if (b != null && !b.isRecycled()) {
                b.recycle();
            }
        }

        if (result.subPages.isEmpty()) {
            Bitmap empty = Bitmap.createBitmap(safeViewW, safeViewH, Bitmap.Config.ARGB_8888);
            empty.eraseColor(bgColor);
            result.subPages.add(empty);
        }
        return result;
    }

    /**
     * Draws an image/photo region onto the canvas in its natural, un-inverted colors while blending
     * any pure-white outer paper margin pixels into `bgColor`.
     */
    private static void drawNaturalImageBlock(Canvas canvas, Bitmap srcBmp, Rect srcRect, Rect dstRect,
                                              int bgColor, Paint paint) {
        int sw = Math.max(1, srcRect.width());
        int sh = Math.max(1, srcRect.height());
        if (srcRect.left < 0 || srcRect.top < 0 || srcRect.right > srcBmp.getWidth() || srcRect.bottom > srcBmp.getHeight()) {
            canvas.drawBitmap(srcBmp, srcRect, dstRect, paint);
            return;
        }
        Bitmap crop = Bitmap.createBitmap(srcBmp, srcRect.left, srcRect.top, sw, sh);
        // Flood-blend only outer paper-white border pixels of the crop into bgColor, keeping the photo untouched
        int[] px = new int[sw * sh];
        crop.getPixels(px, 0, sw, 0, 0, sw, sh);
        for (int y = 0; y < sh; y++) {
            int row = y * sw;
            // Left outer margin
            for (int x = 0; x < sw; x++) {
                if (isPaperWhite(px[row + x])) {
                    px[row + x] = bgColor;
                } else {
                    break;
                }
            }
            // Right outer margin
            for (int x = sw - 1; x >= 0; x--) {
                if (isPaperWhite(px[row + x])) {
                    px[row + x] = bgColor;
                } else {
                    break;
                }
            }
        }
        crop.setPixels(px, 0, sw, 0, 0, sw, sh);
        canvas.drawBitmap(crop, null, dstRect, paint);
        crop.recycle();
    }

    private static boolean isPaperWhite(int argb) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        return r >= 242 && g >= 242 && b >= 242;
    }

    private static class WrappedItem {
        final Bitmap sourceBitmap;
        final int srcLeft, srcTop, srcRight, srcBottom;
        int dstX;
        final int dstWidth, dstHeight;

        WrappedItem(Bitmap sourceBitmap, int srcLeft, int srcTop, int srcRight, int srcBottom,
                    int dstX, int dstWidth, int dstHeight) {
            this.sourceBitmap = sourceBitmap;
            this.srcLeft = srcLeft;
            this.srcTop = srcTop;
            this.srcRight = srcRight;
            this.srcBottom = srcBottom;
            this.dstX = dstX;
            this.dstWidth = dstWidth;
            this.dstHeight = dstHeight;
        }
    }

    private static class WrappedRow {
        final List<WrappedItem> items = new ArrayList<>();
        int rowHeight = 0;
        int gapAfter = 8;
        boolean isImageRow = false;
        boolean isLastRowOfParagraph = false;
    }

    private static int countSubPagesForRows(List<WrappedRow> rows, int availPageHeight) {
        int pages = 0;
        int idx = 0;
        while (idx < rows.size()) {
            int acc = 0;
            boolean any = false;
            while (idx < rows.size()) {
                WrappedRow r = rows.get(idx);
                int needed = r.rowHeight + (any ? rows.get(idx - 1).gapAfter : 0);
                if (any && acc + needed > availPageHeight) break;
                acc += needed;
                any = true;
                idx++;
            }
            pages++;
        }
        return Math.max(1, pages);
    }

    private static List<WrappedRow> buildWrappedRows(
            List<TextLineStrip> lines, float medianBodyLineH, float scale,
            int padX, int availWidth, int lineGapPx, int paraGapPx,
            int letterGapPx, int baseWordGapPx, int indentPx,
            boolean justifyAlign, int fontFamily) {

        List<WrappedRow> wrappedRows = new ArrayList<>();
        WrappedRow currentRow = new WrappedRow();
        currentRow.gapAfter = lineGapPx;
        int cursorX = padX;

        int uniformRowHeight = Math.max(16, Math.round(medianBodyLineH * scale));
        float monoAspectY = (fontFamily == ReadingSettings.FONT_MONO) ? 1.07f : 1.0f;

        for (int lineIdx = 0; lineIdx < lines.size(); lineIdx++) {
            TextLineStrip line = lines.get(lineIdx);

            // Handle Image / Photo / Illustration blocks without slicing or inverting them!
            if (line.isImageBlock || line.words.isEmpty()) {
                if (!currentRow.items.isEmpty()) {
                    currentRow.isLastRowOfParagraph = true;
                    wrappedRows.add(currentRow);
                    currentRow = new WrappedRow();
                    currentRow.gapAfter = lineGapPx;
                }
                float imgScale = Math.min(scale * 1.15f, (float) availWidth / Math.max(1, line.right - line.left));
                int bw = Math.max(1, Math.round((line.right - line.left) * imgScale));
                int bh = Math.max(1, Math.round(line.height() * imgScale));
                WrappedRow imgRow = new WrappedRow();
                imgRow.isImageRow = true;
                imgRow.isLastRowOfParagraph = true;
                imgRow.rowHeight = bh;
                imgRow.gapAfter = paraGapPx;
                imgRow.items.add(new WrappedItem(line.sourceBitmap, line.left, line.top, line.right, line.bottom,
                        padX + Math.max(0, (availWidth - bw) / 2), bw, bh));
                wrappedRows.add(imgRow);
                cursorX = padX;
                continue;
            }

            float lineRatio = (float) line.height() / medianBodyLineH;
            int scaledLineH = (lineRatio >= 0.72f && lineRatio <= 1.35f)
                    ? Math.round(uniformRowHeight * monoAspectY)
                    : Math.max(14, Math.round(line.height() * scale * monoAspectY));

            if (line.isParagraphStart) {
                if (!currentRow.items.isEmpty()) {
                    currentRow.gapAfter = paraGapPx;
                    currentRow.isLastRowOfParagraph = true;
                    wrappedRows.add(currentRow);
                    currentRow = new WrappedRow();
                    currentRow.gapAfter = lineGapPx;
                }
                cursorX = padX + indentPx;
            }

            for (int wIdx = 0; wIdx < line.words.size(); wIdx++) {
                WordSlice ws = line.words.get(wIdx);
                float wordScale = (float) uniformRowHeight / Math.max(1, line.height());
                int dstW = Math.max(2, Math.round(ws.width() * wordScale));
                int dstH = scaledLineH;

                if (dstW > availWidth) {
                    float fitRatio = (float) availWidth / dstW;
                    dstW = availWidth;
                    dstH = Math.max(12, Math.round(dstH * fitRatio));
                }

                if (cursorX + dstW > padX + availWidth && !currentRow.items.isEmpty()) {
                    // Wrap to next row; apply full justification (两端对齐) to the completed row if enabled!
                    if (justifyAlign) {
                        justifyWrappedRow(currentRow, padX, availWidth);
                    }
                    wrappedRows.add(currentRow);
                    currentRow = new WrappedRow();
                    currentRow.gapAfter = lineGapPx;
                    cursorX = padX;
                }

                currentRow.items.add(new WrappedItem(line.sourceBitmap, ws.left, line.top, ws.right, line.bottom,
                        cursorX, dstW, dstH));
                currentRow.rowHeight = Math.max(currentRow.rowHeight, dstH);

                int stepGap = (ws.isSpaceAfter ? baseWordGapPx * 2 : baseWordGapPx) + letterGapPx;
                cursorX += dstW + Math.max(0, stepGap);
            }

            if (line.isParagraphEnd && !currentRow.items.isEmpty()) {
                currentRow.gapAfter = paraGapPx;
                currentRow.isLastRowOfParagraph = true;
                wrappedRows.add(currentRow);
                currentRow = new WrappedRow();
                currentRow.gapAfter = lineGapPx;
                cursorX = padX;
            }
        }

        if (!currentRow.items.isEmpty()) {
            currentRow.isLastRowOfParagraph = true;
            wrappedRows.add(currentRow);
        }
        return wrappedRows;
    }

    /**
     * Distributes any remaining horizontal slack `(availWidth - usedWidth)` evenly across the gaps
     * between items in `row` so the right edge of the row aligns 100% flush with `padX + availWidth`.
     */
    private static void justifyWrappedRow(WrappedRow row, int padX, int availWidth) {
        int count = row.items.size();
        if (count <= 1 || row.isImageRow || row.isLastRowOfParagraph) return;

        WrappedItem first = row.items.get(0);
        WrappedItem last = row.items.get(count - 1);
        int rightEdge = last.dstX + last.dstWidth;
        int targetRight = padX + availWidth;
        int slack = targetRight - rightEdge;

        // Justify any non-final wrapped row that fills at least 52% of availWidth
        if (slack <= 0 || slack > availWidth * 0.48f) return;

        float extraPerGap = (float) slack / (count - 1);
        int baseStartX = first.dstX;
        for (int i = 1; i < count; i++) {
            WrappedItem item = row.items.get(i);
            item.dstX = Math.round(item.dstX + extraPerGap * i);
        }
        // Ensure the very last item aligns flush with targetRight
        last.dstX = targetRight - last.dstWidth;
        if (first.dstX != baseStartX) {
            first.dstX = baseStartX;
        }
    }

    /**
     * Colors paper pixels to `bgColor` and text strokes to `fgColor` with distinct gamma curves
     * for each `fontFamily` (without any 1-pixel shift that could cause double-edge ghosting!).
     */
    private static void applyThemeAndFontToBitmapInPlace(Bitmap bmp, int bgColor, int fgColor, int fontFamily) {
        if (bmp == null || bmp.isRecycled()) return;
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        int[] pixels = new int[w * h];
        bmp.getPixels(pixels, 0, w, 0, 0, w, h);

        int bgR = Color.red(bgColor);
        int bgG = Color.green(bgColor);
        int bgB = Color.blue(bgColor);

        int fgR = Color.red(fgColor);
        int fgG = Color.green(fgColor);
        int fgB = Color.blue(fgColor);

        double gamma;
        switch (fontFamily) {
            case ReadingSettings.FONT_SANS:
                gamma = 0.56;
                break;
            case ReadingSettings.FONT_CLASSIC:
                gamma = 1.50;
                break;
            case ReadingSettings.FONT_MONO:
                gamma = 0.85;
                break;
            case ReadingSettings.FONT_SERIF:
            default:
                gamma = 1.12;
                break;
        }

        int[] lut = new int[256];
        for (int lum = 0; lum < 256; lum++) {
            if (lum >= 236) {
                lut[lum] = bgColor;
            } else {
                double norm = lum / 236.0;
                double t = Math.pow(norm, gamma);
                int outR = (int) Math.round(fgR + (bgR - fgR) * t);
                int outG = (int) Math.round(fgG + (bgG - fgG) * t);
                int outB = (int) Math.round(fgB + (bgB - fgB) * t);
                outR = Math.max(0, Math.min(255, outR));
                outG = Math.max(0, Math.min(255, outG));
                outB = Math.max(0, Math.min(255, outB));
                lut[lum] = 0xFF000000 | (outR << 16) | (outG << 8) | outB;
            }
        }

        for (int i = 0; i < pixels.length; i++) {
            int c = pixels[i];
            int r = (c >> 16) & 0xFF;
            int g = (c >> 8) & 0xFF;
            int b = c & 0xFF;
            int lum = (r * 77 + g * 150 + b * 29) >> 8;
            pixels[i] = lut[lum];
        }

        bmp.setPixels(pixels, 0, w, 0, 0, w, h);
    }

    private static List<TextLineStrip> extractLinesFromSinglePdfPage(
            PdfRenderer renderer, int pageIndex, int srcWidth, List<Bitmap> outBitmaps) {
        if (renderer == null || pageIndex < 0 || pageIndex >= renderer.getPageCount()) {
            return Collections.emptyList();
        }
        try (PdfRenderer.Page page = renderer.openPage(pageIndex)) {
            int srcHeight = Math.max(120, (int) ((float) srcWidth * page.getHeight() / Math.max(1, page.getWidth())));
            Bitmap srcBmp = Bitmap.createBitmap(srcWidth, srcHeight, Bitmap.Config.ARGB_8888);
            srcBmp.eraseColor(Color.WHITE);
            page.render(srcBmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            outBitmaps.add(srcBmp);

            int[] pixels = new int[srcWidth * srcHeight];
            srcBmp.getPixels(pixels, 0, srcWidth, 0, 0, srcWidth, srcHeight);
            Rect inkBounds = detectInkBoundsFromPixels(pixels, srcWidth, srcHeight, 2);
            return segmentTextLines(srcBmp, pixels, srcWidth, srcHeight, inkBounds);
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private static float computeStableMedianLineHeight(List<TextLineStrip> lines, int srcWidth) {
        if (lines == null || lines.isEmpty()) return Math.max(18f, srcWidth * 0.024f);
        List<Integer> heights = new ArrayList<>();
        for (TextLineStrip l : lines) {
            if (!l.isImageBlock) {
                int h = l.height();
                if (h >= 8 && h <= srcWidth * 0.075f) {
                    heights.add(h);
                }
            }
        }
        if (heights.isEmpty()) {
            return Math.max(18f, srcWidth * 0.024f);
        }
        Collections.sort(heights);
        int mid = heights.size() / 2;
        float median = heights.get(mid);
        float minExpected = srcWidth * 0.017f;
        float maxExpected = srcWidth * 0.033f;
        return Math.max(minExpected, Math.min(maxExpected, median));
    }

    public static Rect detectInkBounds(Bitmap bmp, int paddingPx) {
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        int[] pixels = new int[w * h];
        bmp.getPixels(pixels, 0, w, 0, 0, w, h);
        return detectInkBoundsFromPixels(pixels, w, h, paddingPx);
    }

    private static Rect detectInkBoundsFromPixels(int[] pixels, int w, int h, int paddingPx) {
        int ignoreBorderX = Math.max(2, (int) (w * 0.015f));
        int ignoreBorderY = Math.max(2, (int) (h * 0.015f));

        int minX = w, minY = h, maxX = 0, maxY = 0;
        int minRowPixels = Math.max(2, w / 260);

        for (int y = ignoreBorderY; y < h - ignoreBorderY; y++) {
            int rowInkCount = 0;
            int rowFirstX = w;
            int rowLastX = 0;
            int rowOffset = y * w;
            for (int x = ignoreBorderX; x < w - ignoreBorderX; x++) {
                if (isInkPixel(pixels[rowOffset + x])) {
                    rowInkCount++;
                    if (x < rowFirstX) rowFirstX = x;
                    if (x > rowLastX) rowLastX = x;
                }
            }
            if (rowInkCount >= minRowPixels) {
                if (y < minY) minY = y;
                if (y > maxY) maxY = y;
                if (rowFirstX < minX) minX = rowFirstX;
                if (rowLastX > maxX) maxX = rowLastX;
            }
        }

        if (minX >= maxX || minY >= maxY) {
            return new Rect(0, 0, w, h);
        }

        int left = Math.max(0, minX - paddingPx);
        int top = Math.max(0, minY - paddingPx);
        int right = Math.min(w, maxX + paddingPx);
        int bottom = Math.min(h, maxY + paddingPx);
        return new Rect(left, top, right, bottom);
    }

    private static List<TextLineStrip> segmentTextLines(
            Bitmap srcBmp, int[] pixels, int w, int h, Rect bounds) {
        List<TextLineStrip> rawLines = new ArrayList<>();
        int leftBound = bounds.left;
        int rightBound = bounds.right;
        int topBound = bounds.top;
        int bottomBound = bounds.bottom;

        int noiseThreshold = Math.max(1, (rightBound - leftBound) / 360);
        int inLineStart = -1;
        int zeroGapTolerance = Math.max(1, h / 900);
        int zeroGapCount = 0;

        List<int[]> lineRanges = new ArrayList<>();
        for (int y = topBound; y < bottomBound; y++) {
            int inkCount = 0;
            int rowOffset = y * w;
            for (int x = leftBound; x < rightBound; x++) {
                if (isInkPixel(pixels[rowOffset + x])) {
                    inkCount++;
                }
            }
            if (inkCount > noiseThreshold) {
                if (inLineStart < 0) {
                    inLineStart = y;
                }
                zeroGapCount = 0;
            } else {
                if (inLineStart >= 0) {
                    zeroGapCount++;
                    if (zeroGapCount > zeroGapTolerance) {
                        int lineEnd = y - zeroGapCount;
                        if (lineEnd - inLineStart >= 4) {
                            lineRanges.add(new int[]{inLineStart, lineEnd});
                        }
                        inLineStart = -1;
                        zeroGapCount = 0;
                    }
                }
            }
        }
        if (inLineStart >= 0 && bottomBound - inLineStart >= 4) {
            lineRanges.add(new int[]{inLineStart, bottomBound});
        }

        if (lineRanges.isEmpty()) return rawLines;

        // Compute median line height and median inter-line vertical gap across the page
        List<Integer> candidateHeights = new ArrayList<>();
        List<Integer> candidateGaps = new ArrayList<>();
        for (int i = 0; i < lineRanges.size(); i++) {
            int[] lr = lineRanges.get(i);
            int lh = lr[1] - lr[0];
            if (lh >= 8 && lh <= w * 0.08f) {
                candidateHeights.add(lh);
            }
            if (i > 0) {
                int gap = lr[0] - lineRanges.get(i - 1)[1];
                if (gap >= 0 && gap <= w * 0.12f) {
                    candidateGaps.add(gap);
                }
            }
        }
        float medianTextH;
        if (!candidateHeights.isEmpty()) {
            Collections.sort(candidateHeights);
            medianTextH = candidateHeights.get(candidateHeights.size() / 2);
        } else {
            medianTextH = w * 0.025f;
        }
        float medianLineGap;
        if (!candidateGaps.isEmpty()) {
            Collections.sort(candidateGaps);
            medianLineGap = candidateGaps.get(candidateGaps.size() / 2);
        } else {
            medianLineGap = medianTextH * 0.65f;
        }
        // Only treat vertical gap as a true paragraph break if it significantly exceeds the page's normal line gap!
        float paraVerticalGapThreshold = Math.max(medianLineGap * 1.65f, medianTextH * 1.35f);

        int contentSpanW = Math.max(1, rightBound - leftBound);

        for (int i = 0; i < lineRanges.size(); i++) {
            int lTop = Math.max(0, lineRanges.get(i)[0] - 1);
            int lBot = Math.min(h, lineRanges.get(i)[1] + 1);

            int lineLeft = rightBound;
            int lineRight = leftBound;
            for (int y = lTop; y < lBot; y++) {
                int rowOffset = y * w;
                for (int x = leftBound; x < rightBound; x++) {
                    if (isInkPixel(pixels[rowOffset + x])) {
                        if (x < lineLeft) lineLeft = x;
                        if (x > lineRight) lineRight = x;
                    }
                }
            }
            if (lineLeft >= lineRight) continue;

            int stripH = lBot - lTop;
            int stripW = lineRight - lineLeft + 1;

            // Skip isolated tiny page numbers at the very bottom or running headers at the very top
            if (lineRanges.size() > 4 && (i == 0 || i == lineRanges.size() - 1)) {
                if (stripW < contentSpanW * 0.18f && stripH < medianTextH * 0.90f) {
                    continue;
                }
            }

            boolean isImg = isStripAnImageBlock(pixels, w, lineLeft, lTop, lineRight + 1, lBot, medianTextH);

            boolean isParaStart = ((lineLeft - leftBound) > medianTextH * 1.25f)
                    || (i > 0 && (lTop - lineRanges.get(i - 1)[1]) > paraVerticalGapThreshold);

            boolean isParaEnd = ((rightBound - lineRight) > contentSpanW * 0.16f)
                    || (i + 1 < lineRanges.size() && (lineRanges.get(i + 1)[0] - lBot) > paraVerticalGapThreshold);

            TextLineStrip strip = new TextLineStrip(
                    srcBmp, lTop, lBot, lineLeft, lineRight + 1, isParaStart, isParaEnd, isImg);
            if (!isImg) {
                segmentLineWords(pixels, w, strip, medianTextH);
            }
            rawLines.add(strip);
        }

        // Propagate paragraph boundaries: if line i+1 is a paragraph start, then line i must be a paragraph end
        for (int i = 0; i + 1 < rawLines.size(); i++) {
            if (rawLines.get(i + 1).isParagraphStart && !rawLines.get(i).isParagraphEnd) {
                TextLineStrip cur = rawLines.get(i);
                TextLineStrip updated = new TextLineStrip(
                        cur.sourceBitmap, cur.top, cur.bottom, cur.left, cur.right,
                        cur.isParagraphStart, true, cur.isImageBlock);
                updated.words.addAll(cur.words);
                rawLines.set(i, updated);
            }
        }

        return rawLines;
    }

    /**
     * Determines whether a horizontal strip is a photo / illustration / figure block rather than a text line.
     */
    private static boolean isStripAnImageBlock(int[] pixels, int stride,
                                               int left, int top, int right, int bottom, float medianTextH) {
        int stripH = bottom - top;
        int stripW = Math.max(1, right - left);
        if (stripH > medianTextH * 2.15f) {
            return true;
        }

        int totalSampled = 0;
        int nonWhite = 0;
        int midTone = 0;
        int colored = 0;

        int stepY = Math.max(1, stripH / 24);
        int stepX = Math.max(1, stripW / 120);
        for (int y = top; y < bottom; y += stepY) {
            int row = y * stride;
            for (int x = left; x < right; x += stepX) {
                int c = pixels[row + x];
                int r = (c >> 16) & 0xFF;
                int g = (c >> 8) & 0xFF;
                int b = c & 0xFF;
                int lum = (r * 77 + g * 150 + b * 29) >> 8;
                totalSampled++;
                if (lum < 236) {
                    nonWhite++;
                    if (lum >= 65 && lum <= 215) {
                        midTone++;
                    }
                }
                int maxC = Math.max(r, Math.max(g, b));
                int minC = Math.min(r, Math.min(g, b));
                if (maxC - minC > 28) {
                    colored++;
                }
            }
        }
        if (totalSampled == 0) return false;
        float fillRatio = (float) nonWhite / totalSampled;
        float midToneRatio = nonWhite > 0 ? ((float) midTone / nonWhite) : 0f;
        float colorRatio = (float) colored / totalSampled;

        return fillRatio > 0.52f || (stripH > medianTextH * 1.45f && (midToneRatio > 0.45f || colorRatio > 0.08f));
    }

    /**
     * Segments a single text line into individual character/word slices (`WordSlice`) using
     * minimum-ink valley detection so that even slightly touching CJK characters are split cleanly
     * into 1-character slices and respond accurately to Letter Spacing and Full Justification (`两端对齐`).
     */
    private static void segmentLineWords(int[] pixels, int stride, TextLineStrip line, float avgLineH) {
        int lTop = line.top;
        int lBot = line.bottom;
        int lLeft = line.left;
        int lRight = line.right;
        int targetCharWidth = Math.max(11, Math.round(avgLineH * 0.98f));
        int maxCharWidth = Math.max(16, Math.round(avgLineH * 1.28f));
        int spaceGapThreshold = Math.max(3, Math.round(avgLineH * 0.25f));

        int width = Math.max(1, lRight - lLeft);
        int[] colInks = new int[width];
        for (int x = lLeft; x < lRight; x++) {
            int cInk = 0;
            for (int y = lTop; y < lBot; y++) {
                if (isInkPixel(pixels[y * stride + x])) {
                    cInk++;
                }
            }
            colInks[x - lLeft] = cInk;
        }

        int segStart = -1;
        for (int x = lLeft; x < lRight; x++) {
            int colInk = colInks[x - lLeft];
            if (colInk > 0) {
                if (segStart < 0) {
                    segStart = x;
                } else if (x - segStart >= maxCharWidth) {
                    // Find minimum-ink valley in [segStart + targetCharWidth * 0.72 .. x]
                    int searchFrom = Math.max(segStart + 4, segStart + Math.round(targetCharWidth * 0.72f));
                    int bestSplitX = x;
                    int minInk = Integer.MAX_VALUE;
                    for (int sx = searchFrom; sx <= x; sx++) {
                        int ik = colInks[sx - lLeft];
                        if (ik <= minInk) {
                            minInk = ik;
                            bestSplitX = sx;
                        }
                    }
                    addTrimmedWordSlice(line, colInks, lLeft, segStart, bestSplitX, false);
                    segStart = bestSplitX;
                }
            } else {
                if (segStart >= 0) {
                    int segEnd = x;
                    int lookAhead = x;
                    while (lookAhead < lRight && colInks[lookAhead - lLeft] == 0) {
                        lookAhead++;
                    }
                    int totalGap = lookAhead - segEnd;
                    boolean isWordSpace = totalGap >= spaceGapThreshold;
                    addTrimmedWordSlice(line, colInks, lLeft, segStart, segEnd, isWordSpace);
                    segStart = -1;
                    x = lookAhead - 1;
                }
            }
        }

        if (segStart >= 0 && lRight > segStart) {
            addTrimmedWordSlice(line, colInks, lLeft, segStart, lRight, false);
        }
    }

    private static void addTrimmedWordSlice(TextLineStrip line, int[] colInks, int baseLeft,
                                            int rawStart, int rawEnd, boolean isSpaceAfter) {
        int s = rawStart;
        int e = rawEnd;
        while (s < e && colInks[s - baseLeft] == 0) s++;
        while (e > s && colInks[e - 1 - baseLeft] == 0) e--;
        if (e > s) {
            line.words.add(new WordSlice(s, e, isSpaceAfter));
        }
    }

    private static boolean isInkPixel(int argb) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        return (r < 236 || g < 236 || b < 236);
    }

    private static final Pattern CHAPTER_HEADING_PATTERN = Pattern.compile(
            "^[ \\t　]*(第[0-9一二三四五六七八九十百千万零〇两]+[章节回卷集部篇讲课季幕][^\\n]{0,36}"
                    + "|卷[0-9一二三四五六七八九十百千万零〇两]+[^\\n]{0,32}"
                    + "|(?:Chapter|CHAPTER|Part|PART|Section|SECTION|Book|BOOK)\\s+[0-9IVXLCDM]+[^\\n]{0,36}"
                    + "|(?:序言|序章|前言|导言|导论|引言|楔子|引子|内容简介|作者简介|后记|跋|尾声|附录|参考文献|致谢)(?:[：:\\s—·][^\\n]{0,25})?"
                    + "|[一二三四五六七八九十]+[、.．]\\s*[^\\n]{2,32}"
                    + "|\\d+(?:\\.\\d+){0,2}[、.．\\s]\\s*[^0-9\\n][^\\n]{1,32})\\s*$"
    );

    private static final Pattern PRINTED_TOC_LINE_PATTERN = Pattern.compile(
            "^[ \\t　]*(.{2,42}?)(?:[ \\t.…·—\\-_]{2,}|[ \\t　]+)(\\d{1,4})\\s*$"
    );

    /**
     * Extracts the best human-readable book title from inside a PDF file (checking `/Outlines`,
     * `/Info` dictionary `/Title`, or the first prominent heading on the opening pages).
     */
    public static String extractBestBookTitleFromPdf(File pdfFile) {
        if (pdfFile == null || !pdfFile.exists() || pdfFile.length() <= 0 || pdfFile.length() > 90 * 1024 * 1024L) {
            return null;
        }
        try {
            byte[] pdfBytes = new byte[(int) Math.min(pdfFile.length(), 16 * 1024 * 1024L)];
            try (FileInputStream fis = new FileInputStream(pdfFile)) {
                int offset = 0;
                while (offset < pdfBytes.length) {
                    int r = fis.read(pdfBytes, offset, pdfBytes.length - offset);
                    if (r == -1) break;
                    offset += r;
                }
            }
            String rawIso = new String(pdfBytes, StandardCharsets.ISO_8859_1);
            List<String> decompressedStreams = decompressAllPdfStreams(pdfBytes);

            Map<Integer, String> globalToUnicodeMap = new HashMap<>();
            for (String streamText : decompressedStreams) {
                if (streamText.contains("beginbfchar") || streamText.contains("beginbfrange")) {
                    parseToUnicodeCMap(streamText, globalToUnicodeMap);
                }
            }

            // 1. Check /Info dictionary /Title
            Pattern infoDictPat = Pattern.compile("<<(?s:(?!<<).)*?/(?:Producer|CreationDate|Creator|Author)(?s:.*?)>>");
            List<String> sources = new ArrayList<>();
            sources.add(rawIso);
            sources.addAll(decompressedStreams);
            for (String src : sources) {
                Matcher im = infoDictPat.matcher(src);
                while (im.find()) {
                    String dict = im.group(0);
                    if (dict.contains("/Title")) {
                        String candidate = cleanHeadingTitle(extractTitleFromOutlineDict(dict, globalToUnicodeMap));
                        if (isGoodPdfBookTitle(candidate)) {
                            return candidate;
                        }
                    }
                }
            }

            // 2. Check first page / opening text streams for prominent Chinese/book title
            for (int sIdx = 0; sIdx < Math.min(6, decompressedStreams.size()); sIdx++) {
                String streamText = decompressedStreams.get(sIdx);
                if (streamText.contains("BT") && streamText.contains("ET")
                        && !streamText.contains("beginbfchar") && !streamText.contains("beginbfrange")) {
                    String pageText = extractTextFromBtEtStream(streamText, globalToUnicodeMap);
                    if (pageText != null && !pageText.trim().isEmpty()) {
                        for (String line : pageText.split("\n")) {
                            String cl = cleanHeadingTitle(line);
                            if (isGoodPdfBookTitle(cl) && hasCjkCharacters(cl) && cl.length() <= 26) {
                                return cl;
                            }
                        }
                    }
                }
            }

            // 3. Check first Outline bookmark title
            List<ParsedBook.TocEntry> outlines = extractPdfOutlineBookmarks(rawIso, decompressedStreams, globalToUnicodeMap, 100);
            for (ParsedBook.TocEntry e : outlines) {
                if (isGoodPdfBookTitle(e.title) && !e.title.startsWith("第") && !e.title.equals("目录") && !e.title.equals("前言")) {
                    return e.title;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static boolean hasCjkCharacters(String s) {
        if (s == null) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF) return true;
        }
        return false;
    }

    private static boolean isGoodPdfBookTitle(String s) {
        if (s == null) return false;
        String t = s.trim();
        if (t.length() < 2 || t.length() > 38) return false;
        String low = t.toLowerCase(Locale.ROOT);
        if (low.startsWith("session_v_") || low.startsWith("v_") || low.startsWith("microsoft word")
                || low.startsWith("untitled") || low.endsWith(".doc") || low.endsWith(".docx")
                || low.endsWith(".pdf") || low.endsWith(".indd") || low.contains("acrobat")) {
            return false;
        }
        return !BookCoverExtractor.isUglyOrSyntheticTitle(t);
    }

    /**
     * Populates `pb.tocEntries` with the PDF's real Chapter/Section Title Table of Contents (`标题目录`)
     * and sets each page's `pb.chapters.get(i).title` to the active chapter title (instead of raw page numbers).
     * Scans at most the first 3.5MB of the PDF file so even 80MB+ PDFs extract their TOC in milliseconds!
     */
    public static void populatePdfChaptersAndTitleToc(File pdfFile, PdfRenderer renderer, ParsedBook pb) {
        int totalPages = Math.max(1, pb.pdfPageCount);
        List<String> pageTexts = new ArrayList<>(Collections.nCopies(totalPages, ""));
        List<ParsedBook.TocEntry> discoveredToc = new ArrayList<>();

        try {
            if (pdfFile != null && pdfFile.exists() && pdfFile.length() > 0) {
                int capBytes = (int) Math.min(pdfFile.length(), 3500 * 1024L);
                byte[] pdfBytes = new byte[capBytes];
                try (FileInputStream fis = new FileInputStream(pdfFile)) {
                    int offset = 0;
                    while (offset < pdfBytes.length) {
                        int r = fis.read(pdfBytes, offset, pdfBytes.length - offset);
                        if (r == -1) break;
                        offset += r;
                    }
                }

                String rawIso = new String(pdfBytes, StandardCharsets.ISO_8859_1);
                List<String> decompressedStreams = decompressAllPdfStreams(pdfBytes);

                // 1. Build CMap for decoding text streams
                Map<Integer, String> globalToUnicodeMap = new HashMap<>();
                for (String streamText : decompressedStreams) {
                    if (streamText.contains("beginbfchar") || streamText.contains("beginbfrange")) {
                        parseToUnicodeCMap(streamText, globalToUnicodeMap);
                    }
                }

                // 2. Extract readable text blocks per page stream
                List<String> rawPageTexts = new ArrayList<>();
                for (String streamText : decompressedStreams) {
                    if (streamText.contains("BT") && streamText.contains("ET")
                            && !streamText.contains("beginbfchar") && !streamText.contains("beginbfrange")) {
                        String pageText = extractTextFromBtEtStream(streamText, globalToUnicodeMap);
                        if (pageText != null && !pageText.trim().isEmpty()) {
                            rawPageTexts.add(pageText.trim());
                        }
                    }
                }
                for (int i = 0; i < totalPages && i < rawPageTexts.size(); i++) {
                    pageTexts.set(i, rawPageTexts.get(i));
                }

                // 3. STRATEGY A: Extract PDF Embedded Bookmarks / Outline Tree (/Outlines -> /Title)
                List<ParsedBook.TocEntry> outlineEntries = extractPdfOutlineBookmarks(
                        rawIso, decompressedStreams, globalToUnicodeMap, totalPages);
                if (!outlineEntries.isEmpty()) {
                    discoveredToc.addAll(outlineEntries);
                }

                // 4. STRATEGY B: Parse Printed "目录 / Contents" pages in the first 25 pages if Outlines were absent
                if (discoveredToc.isEmpty() && !rawPageTexts.isEmpty()) {
                    List<ParsedBook.TocEntry> printedToc = extractPrintedTocEntries(rawPageTexts, totalPages);
                    if (printedToc.size() >= 2) {
                        discoveredToc.addAll(printedToc);
                    }
                }

                // 5. STRATEGY C: Detect Chapter/Section Headings directly from page text streams
                if (discoveredToc.isEmpty() && !rawPageTexts.isEmpty()) {
                    List<ParsedBook.TocEntry> streamHeadings = extractHeadingsFromPageTexts(rawPageTexts, totalPages);
                    if (!streamHeadings.isEmpty()) {
                        discoveredToc.addAll(streamHeadings);
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        // 6. STRATEGY D: For pure scanned image PDFs (no Outlines & no text streams), detect chapter start pages visually
        if (discoveredToc.isEmpty()) {
            discoveredToc.addAll(buildVisualOrLogicalChapterToc(renderer, pb.title, totalPages));
        }

        // Deduplicate & sort TOC entries by page index
        List<ParsedBook.TocEntry> cleanToc = normalizeAndDeduplicateToc(discoveredToc, totalPages, pb.title);
        pb.tocEntries.clear();
        pb.tocEntries.addAll(cleanToc);

        // Assign each PDF page the active chapter/section title from `pb.tocEntries`
        pb.chapters.clear();
        int tocCursor = 0;
        String currentChapterTitle = !pb.tocEntries.isEmpty() ? pb.tocEntries.get(0).title : pb.title;
        for (int p = 0; p < totalPages; p++) {
            while (tocCursor + 1 < pb.tocEntries.size() && pb.tocEntries.get(tocCursor + 1).chapterIndex <= p) {
                tocCursor++;
                currentChapterTitle = pb.tocEntries.get(tocCursor).title;
            }
            if (tocCursor < pb.tocEntries.size() && pb.tocEntries.get(tocCursor).chapterIndex <= p) {
                currentChapterTitle = pb.tocEntries.get(tocCursor).title;
            }
            String pageBody = (p < pageTexts.size() && !pageTexts.get(p).isEmpty())
                    ? pageTexts.get(p)
                    : currentChapterTitle;
            pb.chapters.add(new ParsedBook.Chapter(p, currentChapterTitle, pageBody));
        }
    }

    /**
     * Extracts PDF Outline Bookmarks (`/Title (...)` or `/Title <...>`) from both uncompressed PDF objects
     * and decompressed `/Type /ObjStm` streams, resolving target page object references (`/Dest` / `/D`).
     */
    private static List<ParsedBook.TocEntry> extractPdfOutlineBookmarks(
            String rawIso, List<String> decompressedStreams,
            Map<Integer, String> toUnicode, int totalPages) {

        List<ParsedBook.TocEntry> entries = new ArrayList<>();

        // 1. Map Page Object Number (`N 0 obj << /Type /Page ... >>`) -> 0-based page index
        Map<Integer, Integer> pageObjToIdx = new HashMap<>();
        Matcher pageObjMatcher = Pattern.compile("(\\d+)\\s+0\\s+obj\\b(.*?)endobj", Pattern.DOTALL).matcher(rawIso);
        int seqPageIdx = 0;
        while (pageObjMatcher.find()) {
            String body = pageObjMatcher.group(2);
            if (Pattern.compile("/Type\\s*/Page\\b(?![a-zA-Z])").matcher(body).find()) {
                try {
                    int objId = Integer.parseInt(pageObjMatcher.group(1));
                    pageObjToIdx.put(objId, Math.min(totalPages - 1, seqPageIdx++));
                } catch (Exception ignored) {
                }
            }
        }
        // Also check `/Kids [ ... ]` inside `/Type /Pages` if page objects were inside compressed streams
        if (pageObjToIdx.isEmpty()) {
            List<String> allSources = new ArrayList<>();
            allSources.add(rawIso);
            allSources.addAll(decompressedStreams);
            int kidIdx = 0;
            Pattern kidsPat = Pattern.compile("/Type\\s*/Pages\\b.*?/Kids\\s*\\[(.*?)\\]", Pattern.DOTALL);
            Pattern refPat = Pattern.compile("(\\d+)\\s+0\\s+R");
            for (String src : allSources) {
                Matcher km = kidsPat.matcher(src);
                while (km.find()) {
                    Matcher rm = refPat.matcher(km.group(1));
                    while (rm.find()) {
                        try {
                            int objId = Integer.parseInt(rm.group(1));
                            if (!pageObjToIdx.containsKey(objId)) {
                                pageObjToIdx.put(objId, Math.min(totalPages - 1, kidIdx++));
                            }
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
        }

        // 2. Scan both uncompressed PDF and decompressed ObjStm streams for Outline Item dictionaries containing `/Title`
        List<String> scanSources = new ArrayList<>();
        scanSources.add(rawIso);
        scanSources.addAll(decompressedStreams);

        Pattern dictWithTitlePattern = Pattern.compile("<<(?s:(?!<<).)*?/Title\\s*(?:<([0-9A-Fa-f\\s]+)>|\\((?:\\\\.|[^\\\\()])*\\))(?s:.*?)>>");
        for (String src : scanSources) {
            if (!src.contains("/Title")) continue;
            Matcher dm = dictWithTitlePattern.matcher(src);
            while (dm.find()) {
                String dict = dm.group(0);
                // Skip document Info dictionary (which has /Author, /Producer, /Creator, /CreationDate)
                if (dict.contains("/Producer") || dict.contains("/CreationDate") || dict.contains("/Creator")) {
                    continue;
                }
                // An outline item typically has /Parent, /Prev, /Next, /First, /Last, /Dest, /A, or /Count
                if (!dict.contains("/Parent") && !dict.contains("/Dest") && !dict.contains("/A")
                        && !dict.contains("/Next") && !dict.contains("/Prev") && !dict.contains("/First")) {
                    continue;
                }

                String title = extractTitleFromOutlineDict(dict, toUnicode);
                title = cleanHeadingTitle(title);
                if (!isValidTocTitle(title)) continue;

                int pageIdx = resolveOutlineDictPageIndex(dict, pageObjToIdx, totalPages);
                entries.add(new ParsedBook.TocEntry(title, pageIdx, Math.max(1, pageIdx + 1)));
            }
        }

        // If some outline entries had unresolved (-1) page indices, interpolate monotonically across 0..totalPages-1
        if (!entries.isEmpty()) {
            int n = entries.size();
            int lastKnownPage = 0;
            for (int i = 0; i < n; i++) {
                ParsedBook.TocEntry e = entries.get(i);
                int p = e.chapterIndex;
                if (p <= 0 && i > 0) {
                    // Find next known page
                    int nextKnownIdx = -1;
                    int nextKnownPage = totalPages - 1;
                    for (int j = i + 1; j < n; j++) {
                        if (entries.get(j).chapterIndex > lastKnownPage) {
                            nextKnownIdx = j;
                            nextKnownPage = entries.get(j).chapterIndex;
                            break;
                        }
                    }
                    int spanSteps = (nextKnownIdx > i) ? (nextKnownIdx - i + 1) : (n - i);
                    p = Math.min(totalPages - 1, lastKnownPage + Math.max(1, (nextKnownPage - lastKnownPage) / Math.max(1, spanSteps)));
                }
                lastKnownPage = Math.max(lastKnownPage, p);
                entries.set(i, new ParsedBook.TocEntry(e.title, p, p + 1));
            }
        }
        return entries;
    }

    private static String extractTitleFromOutlineDict(String dict, Map<Integer, String> toUnicode) {
        int titlePos = dict.indexOf("/Title");
        if (titlePos < 0) return "";
        int i = titlePos + 6;
        while (i < dict.length() && Character.isWhitespace(dict.charAt(i))) {
            i++;
        }
        if (i >= dict.length()) return "";

        char c = dict.charAt(i);
        if (c == '<' && (i + 1 >= dict.length() || dict.charAt(i + 1) != '<')) {
            int close = dict.indexOf('>', i + 1);
            if (close > i) {
                String hex = dict.substring(i + 1, close).replaceAll("\\s+", "");
                return decodeOutlineHexBytes(hexToBytes(hex), toUnicode);
            }
        } else if (c == '(') {
            byte[] rawBytes = extractBalancedPdfLiteralBytes(dict, i);
            return decodeOutlineHexBytes(rawBytes, toUnicode);
        }
        return "";
    }

    private static byte[] extractBalancedPdfLiteralBytes(String src, int openParenIdx) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(64);
        int depth = 0;
        for (int i = openParenIdx; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '(' && (i == openParenIdx || src.charAt(i - 1) != '\\')) {
                depth++;
                if (depth == 1) continue;
            } else if (c == ')' && src.charAt(i - 1) != '\\') {
                depth--;
                if (depth <= 0) break;
            }
            if (c == '\\' && i + 1 < src.length()) {
                char next = src.charAt(++i);
                switch (next) {
                    case 'n': bos.write('\n'); break;
                    case 'r': bos.write('\r'); break;
                    case 't': bos.write('\t'); break;
                    case 'b': bos.write('\b'); break;
                    case 'f': bos.write('\f'); break;
                    case '(': bos.write('('); break;
                    case ')': bos.write(')'); break;
                    case '\\': bos.write('\\'); break;
                    case '\r':
                        if (i + 1 < src.length() && src.charAt(i + 1) == '\n') i++;
                        break;
                    case '\n':
                        break;
                    default:
                        if (next >= '0' && next <= '7') {
                            StringBuilder oct = new StringBuilder().append(next);
                            while (oct.length() < 3 && i + 1 < src.length()
                                    && src.charAt(i + 1) >= '0' && src.charAt(i + 1) <= '7') {
                                oct.append(src.charAt(++i));
                            }
                            bos.write(Integer.parseInt(oct.toString(), 8) & 0xFF);
                        } else {
                            bos.write(next & 0xFF);
                        }
                        break;
                }
            } else {
                bos.write(c & 0xFF);
            }
        }
        return bos.toByteArray();
    }

    private static String decodeOutlineHexBytes(byte[] bytes, Map<Integer, String> toUnicode) {
        if (bytes == null || bytes.length == 0) return "";
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFE && (bytes[1] & 0xFF) == 0xFF) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16BE);
        }
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xFE) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16LE);
        }
        if (isValidUtf8Bytes(bytes)) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        try {
            return new String(bytes, Charset.forName("GB18030"));
        } catch (Exception e) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private static boolean isValidUtf8Bytes(byte[] data) {
        int i = 0;
        int len = data.length;
        boolean hasMultiByte = false;
        while (i < len) {
            int b = data[i] & 0xFF;
            if (b <= 0x7F) {
                i++;
            } else if ((b & 0xE0) == 0xC0) {
                if (b < 0xC2 || i + 1 >= len || (data[i + 1] & 0xC0) != 0x80) return false;
                hasMultiByte = true;
                i += 2;
            } else if ((b & 0xF0) == 0xE0) {
                if (i + 2 >= len || (data[i + 1] & 0xC0) != 0x80 || (data[i + 2] & 0xC0) != 0x80) return false;
                hasMultiByte = true;
                i += 3;
            } else if ((b & 0xF8) == 0xF0) {
                if (b > 0xF4 || i + 3 >= len || (data[i + 1] & 0xC0) != 0x80
                        || (data[i + 2] & 0xC0) != 0x80 || (data[i + 3] & 0xC0) != 0x80) return false;
                hasMultiByte = true;
                i += 4;
            } else {
                return false;
            }
        }
        return hasMultiByte || len > 0;
    }

    private static int resolveOutlineDictPageIndex(String dict, Map<Integer, Integer> pageObjToIdx, int totalPages) {
        // 1. Direct page object reference inside /Dest [ N 0 R ... ] or /D [ N 0 R ... ]
        Matcher refMatcher = Pattern.compile("(?:/Dest|/D)\\s*\\[\\s*(\\d+)\\s+0\\s+R").matcher(dict);
        if (refMatcher.find()) {
            try {
                int objNum = Integer.parseInt(refMatcher.group(1));
                Integer idx = pageObjToIdx.get(objNum);
                if (idx != null) return Math.max(0, Math.min(totalPages - 1, idx));
            } catch (Exception ignored) {
            }
        }
        // 2. Direct integer page number inside /Dest [ pageNum /XYZ ... ] or /D [ pageNum /Fit ... ]
        Matcher numMatcher = Pattern.compile("(?:/Dest|/D)\\s*\\[\\s*(\\d{1,4})\\s+/(?:XYZ|Fit)").matcher(dict);
        if (numMatcher.find()) {
            try {
                int p = Integer.parseInt(numMatcher.group(1));
                return Math.max(0, Math.min(totalPages - 1, p));
            } catch (Exception ignored) {
            }
        }
        return 0;
    }

    /**
     * Parses printed "目录 / Contents" pages in the first 25 pages of the PDF (e.g. "第一章 绪论 ...... 1").
     */
    private static List<ParsedBook.TocEntry> extractPrintedTocEntries(List<String> pageTexts, int totalPages) {
        List<ParsedBook.TocEntry> rawToc = new ArrayList<>();
        int maxScanPages = Math.min(pageTexts.size(), 25);
        int lastTocPageIdx = 0;

        for (int p = 0; p < maxScanPages; p++) {
            String text = pageTexts.get(p);
            if (text == null || text.isEmpty()) continue;
            String[] lines = text.split("\n");
            int matchesOnPage = 0;
            List<ParsedBook.TocEntry> pageCandidates = new ArrayList<>();
            for (String line : lines) {
                String trimmed = line.replace("　", " ").trim();
                Matcher m = PRINTED_TOC_LINE_PATTERN.matcher(trimmed);
                if (m.find()) {
                    String title = cleanHeadingTitle(m.group(1));
                    int printedPage = SafeParseInt(m.group(2), -1);
                    if (isValidTocTitle(title) && printedPage >= 1 && printedPage <= totalPages) {
                        pageCandidates.add(new ParsedBook.TocEntry(title, printedPage - 1, printedPage));
                        matchesOnPage++;
                    }
                }
            }
            if (matchesOnPage >= 2 || (text.contains("目录") && matchesOnPage >= 1)) {
                rawToc.addAll(pageCandidates);
                lastTocPageIdx = p;
            }
        }

        if (rawToc.isEmpty()) return rawToc;

        // Calculate front-matter offset: printed page 1 starts right after the TOC pages
        int minPrintedPage = Integer.MAX_VALUE;
        for (ParsedBook.TocEntry e : rawToc) {
            if (e.pageNumber < minPrintedPage) minPrintedPage = e.pageNumber;
        }
        int bodyStartOffset = (minPrintedPage == 1 && lastTocPageIdx >= 1) ? (lastTocPageIdx + 1) : 0;

        List<ParsedBook.TocEntry> adjusted = new ArrayList<>(rawToc.size());
        for (ParsedBook.TocEntry e : rawToc) {
            int targetIdx = Math.max(0, Math.min(totalPages - 1, (e.pageNumber - 1) + bodyStartOffset));
            adjusted.add(new ParsedBook.TocEntry(e.title, targetIdx, targetIdx + 1));
        }
        return adjusted;
    }

    /**
     * Scans each page's extracted text for chapter/section headings (`第X章`, `Chapter X`, `一、`, `1.1`, or prominent top titles).
     */
    private static List<ParsedBook.TocEntry> extractHeadingsFromPageTexts(List<String> pageTexts, int totalPages) {
        List<ParsedBook.TocEntry> headings = new ArrayList<>();
        for (int p = 0; p < pageTexts.size() && p < totalPages; p++) {
            String text = pageTexts.get(p);
            if (text == null || text.isEmpty()) continue;
            String[] lines = text.split("\n");
            int inspectLines = Math.min(lines.length, 8);
            String matchedHeading = null;

            for (int l = 0; l < inspectLines; l++) {
                String line = cleanHeadingTitle(lines[l]);
                if (line.isEmpty()) continue;
                if (CHAPTER_HEADING_PATTERN.matcher(line).matches()) {
                    matchedHeading = line;
                    break;
                }
            }

            // If no explicit numbered heading matched on this page, check if the first line is a standalone short title
            if (matchedHeading == null && lines.length >= 2) {
                String first = cleanHeadingTitle(lines[0]);
                if (isValidTocTitle(first) && first.length() >= 2 && first.length() <= 22
                        && !first.endsWith("。") && !first.endsWith("，") && !first.endsWith("；")
                        && !first.matches("^\\d+$")) {
                    // Include if this is page 0 or if we haven't added a heading in the last 4 pages
                    if (headings.isEmpty() || (p - headings.get(headings.size() - 1).chapterIndex) >= 4) {
                        matchedHeading = first;
                    }
                }
            }

            if (matchedHeading != null && isValidTocTitle(matchedHeading)) {
                headings.add(new ParsedBook.TocEntry(matchedHeading, p, p + 1));
            }
        }
        return headings;
    }

    /**
     * Fallback for pure image/scanned PDFs that have neither embedded `/Outlines` nor text streams:
     * Inspects page layout (top title sink / blank header drop) or constructs a clean chapter-title structure
     * so the Table of Contents always shows chapter/section titles (`标题目录`) rather than raw page numbers.
     */
    private static List<ParsedBook.TocEntry> buildVisualOrLogicalChapterToc(
            PdfRenderer renderer, String bookTitle, int totalPages) {
        List<ParsedBook.TocEntry> toc = new ArrayList<>();
        String cleanBookName = (bookTitle != null && !bookTitle.trim().isEmpty()) ? bookTitle.trim() : "全书正文";

        if (totalPages <= 1) {
            toc.add(new ParsedBook.TocEntry(cleanBookName + " · 全文", 0, 1));
            return toc;
        }

        // Fast visual scan for chapter opening pages (pages where top ink starts significantly lower than normal body pages,
        // or where the first strip is a centered title line with a wider gap below it)
        List<Integer> visualChapterPages = new ArrayList<>();
        visualChapterPages.add(0);
        if (renderer != null && totalPages <= 180) {
            int thumbW = 240;
            for (int p = 1; p < totalPages; p++) {
                try (PdfRenderer.Page page = renderer.openPage(p)) {
                    int thumbH = Math.max(160, (int) ((float) thumbW * page.getHeight() / Math.max(1, page.getWidth())));
                    Bitmap bmp = Bitmap.createBitmap(thumbW, thumbH, Bitmap.Config.ARGB_8888);
                    bmp.eraseColor(Color.WHITE);
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                    Rect bounds = detectInkBounds(bmp, 0);
                    bmp.recycle();
                    float topRatio = (float) bounds.top / thumbH;
                    // Chapter start pages in typeset books typically have a sunk header (top ink starts 16%~42% down the page)
                    if (topRatio >= 0.16f && topRatio <= 0.45f
                            && (p - visualChapterPages.get(visualChapterPages.size() - 1)) >= 3) {
                        visualChapterPages.add(p);
                    }
                } catch (Exception ignored) {
                }
            }
        }

        if (visualChapterPages.size() >= 2) {
            for (int i = 0; i < visualChapterPages.size(); i++) {
                int p = visualChapterPages.get(i);
                String secTitle = (i == 0)
                        ? ("封面与导读 · 《" + cleanBookName + "》")
                        : ("第 " + toChineseNumeral(i) + " 章 · 《" + cleanBookName + "》");
                toc.add(new ParsedBook.TocEntry(secTitle, p, p + 1));
            }
            return toc;
        }

        // Logical chapter grouping when all pages have uniform top margins
        int step = Math.max(5, Math.min(20, totalPages / 10));
        int chNum = 1;
        toc.add(new ParsedBook.TocEntry("扉页与目录 · 《" + cleanBookName + "》", 0, 1));
        for (int p = Math.min(2, totalPages - 1); p < totalPages; p += step) {
            toc.add(new ParsedBook.TocEntry("第 " + toChineseNumeral(chNum++) + " 章 · 正文篇目", p, p + 1));
        }
        return toc;
    }

    private static List<ParsedBook.TocEntry> normalizeAndDeduplicateToc(
            List<ParsedBook.TocEntry> raw, int totalPages, String fallbackBookTitle) {
        List<ParsedBook.TocEntry> result = new ArrayList<>();
        String lastTitle = "";
        for (ParsedBook.TocEntry e : raw) {
            String t = cleanHeadingTitle(e.title);
            if (!isValidTocTitle(t)) continue;
            if (t.equals(lastTitle)) continue;
            int idx = Math.max(0, Math.min(totalPages - 1, e.chapterIndex));
            result.add(new ParsedBook.TocEntry(t, idx, idx + 1));
            lastTitle = t;
        }
        if (result.isEmpty()) {
            result.add(new ParsedBook.TocEntry(
                    (fallbackBookTitle != null && !fallbackBookTitle.isEmpty()) ? fallbackBookTitle : "第一章 正文",
                    0, 1));
        }
        return result;
    }

    private static String cleanHeadingTitle(String raw) {
        if (raw == null) return "";
        String t = raw.replace('\0', ' ')
                .replace("　", " ")
                .replaceAll("[\\r\\n\\t]+", " ")
                .replaceAll("[.…·]{3,}\\s*\\d*$", "")
                .replaceAll("\\s+", " ")
                .trim();
        if (t.length() > 42) {
            t = t.substring(0, 42) + "…";
        }
        return t;
    }

    private static boolean isValidTocTitle(String title) {
        if (title == null || title.length() < 2) return false;
        // Reject strings that are purely page numbers like "第 1 / 100 页" or "Page 1"
        if (title.matches("^第\\s*\\d+\\s*(?:/\\s*\\d+\\s*)?页$")) return false;
        if (title.matches("^(?:Page|PAGE|P\\.)\\s*\\d+$")) return false;
        if (title.matches("^[0-9\\s./\\-_]+$")) return false;
        int validChars = 0;
        for (int i = 0; i < title.length(); i++) {
            char c = title.charAt(i);
            if ((c >= 0x4E00 && c <= 0x9FFF) || Character.isLetterOrDigit(c)) {
                validChars++;
            }
        }
        return validChars >= 1;
    }

    private static int SafeParseInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return def;
        }
    }

    private static String toChineseNumeral(int num) {
        String[] digits = {"零", "一", "二", "三", "四", "五", "六", "七", "八", "九", "十"};
        if (num >= 1 && num <= 10) return digits[num];
        if (num > 10 && num < 20) return "十" + digits[num - 10];
        if (num >= 20 && num < 100) {
            int tens = num / 10;
            int rem = num % 10;
            return digits[tens] + "十" + (rem == 0 ? "" : digits[rem]);
        }
        return String.valueOf(num);
    }

    public static List<ParsedBook.Chapter> extractTextChaptersFromPdf(File pdfFile) {
        List<ParsedBook.Chapter> chapters = new ArrayList<>();
        if (pdfFile == null || !pdfFile.exists() || pdfFile.length() <= 0 || pdfFile.length() > 80 * 1024 * 1024L) {
            return chapters;
        }

        try {
            byte[] pdfBytes = new byte[(int) pdfFile.length()];
            try (FileInputStream fis = new FileInputStream(pdfFile)) {
                int offset = 0;
                while (offset < pdfBytes.length) {
                    int r = fis.read(pdfBytes, offset, pdfBytes.length - offset);
                    if (r == -1) break;
                    offset += r;
                }
            }

            List<String> decompressedStreams = decompressAllPdfStreams(pdfBytes);
            Map<Integer, String> globalToUnicodeMap = new HashMap<>();
            for (String streamText : decompressedStreams) {
                if (streamText.contains("beginbfchar") || streamText.contains("beginbfrange")) {
                    parseToUnicodeCMap(streamText, globalToUnicodeMap);
                }
            }

            List<String> extractedPages = new ArrayList<>();
            for (String streamText : decompressedStreams) {
                if (streamText.contains("BT") && streamText.contains("ET")
                        && !streamText.contains("beginbfchar") && !streamText.contains("beginbfrange")) {
                    String pageText = extractTextFromBtEtStream(streamText, globalToUnicodeMap);
                    if (isReadableTextBlock(pageText)) {
                        extractedPages.add(pageText.trim());
                    }
                }
            }

            if (extractedPages.isEmpty()) {
                return chapters;
            }

            StringBuilder bookTextAccumulator = new StringBuilder();
            for (int i = 0; i < extractedPages.size(); i++) {
                String pageRaw = extractedPages.get(i).trim();
                if (pageRaw.length() < 12) continue;
                if (bookTextAccumulator.length() > 0) {
                    char lastCh = bookTextAccumulator.charAt(bookTextAccumulator.length() - 1);
                    boolean isSentenceEnd = (lastCh == '.' || lastCh == '!' || lastCh == '?' || lastCh == '。' || lastCh == '！' || lastCh == '？' || lastCh == '”' || lastCh == '"');
                    if (bookTextAccumulator.toString().endsWith("-") && Character.isLetter(pageRaw.charAt(0))) {
                        bookTextAccumulator.setLength(bookTextAccumulator.length() - 1);
                        bookTextAccumulator.append(pageRaw);
                    } else if (isSentenceEnd) {
                        bookTextAccumulator.append("\n\n").append(pageRaw);
                    } else {
                        bookTextAccumulator.append(' ').append(pageRaw);
                    }
                } else {
                    bookTextAccumulator.append(pageRaw);
                }
            }

            String fullFormatted = formatPdfExtractedParagraphs(bookTextAccumulator.toString());
            if (!fullFormatted.isEmpty()) {
                String[] allParagraphs = fullFormatted.split("\n");
                List<String> curChapterParas = new ArrayList<>();
                int curCharCount = 0;
                int chIdx = 0;
                String curTitle = "第 一 章";

                for (int i = 0; i < allParagraphs.length; i++) {
                    String p = allParagraphs[i].trim();
                    if (p.isEmpty()) continue;
                    String rawP = p.replace("　", "").trim();
                    boolean isMajorHeading = (rawP.startsWith("Chapter") || rawP.startsWith("CHAPTER")
                            || rawP.startsWith("Part") || rawP.startsWith("PART")
                            || (rawP.startsWith("第") && (rawP.contains("章") || rawP.contains("节"))))
                            && rawP.length() <= 40;

                    if ((isMajorHeading && !curChapterParas.isEmpty()) || (curCharCount >= 3800 && curChapterParas.size() >= 8)) {
                        String bodyText = String.join("\n", curChapterParas);
                        chapters.add(new ParsedBook.Chapter(chIdx++, curTitle, bodyText));
                        curChapterParas.clear();
                        curCharCount = 0;
                        curTitle = isMajorHeading ? rawP : ("第 " + toChineseNumeral(chIdx + 1) + " 章");
                    }
                    if (isMajorHeading && curChapterParas.isEmpty()) {
                        curTitle = rawP;
                    }
                    curChapterParas.add(p);
                    curCharCount += p.length();
                }

                if (!curChapterParas.isEmpty()) {
                    String bodyText = String.join("\n", curChapterParas);
                    chapters.add(new ParsedBook.Chapter(chIdx++, curTitle, bodyText));
                }
            }
        } catch (Throwable ignored) {
        }
        return chapters;
    }

    private static List<String> decompressAllPdfStreams(byte[] pdfBytes) {
        List<String> results = new ArrayList<>();
        String iso = new String(pdfBytes, StandardCharsets.ISO_8859_1);
        int searchPos = 0;
        while (searchPos < iso.length()) {
            int streamIdx = iso.indexOf("stream", searchPos);
            if (streamIdx < 0) break;

            if (streamIdx >= 3 && iso.startsWith("end", streamIdx - 3)) {
                searchPos = streamIdx + 6;
                continue;
            }

            int dataStart = streamIdx + 6;
            if (dataStart < pdfBytes.length && pdfBytes[dataStart] == '\r') dataStart++;
            if (dataStart < pdfBytes.length && pdfBytes[dataStart] == '\n') dataStart++;

            int endStreamIdx = iso.indexOf("endstream", dataStart);
            if (endStreamIdx < 0) break;

            int dataLen = endStreamIdx - dataStart;
            while (dataLen > 0 && (pdfBytes[dataStart + dataLen - 1] == '\n' || pdfBytes[dataStart + dataLen - 1] == '\r')) {
                dataLen--;
            }

            if (dataLen > 2 && dataLen < 4 * 1024 * 1024) {
                byte[] inflated = tryInflate(pdfBytes, dataStart, dataLen);
                if (inflated != null && inflated.length > 0) {
                    results.add(new String(inflated, StandardCharsets.ISO_8859_1));
                }
            }
            searchPos = endStreamIdx + 9;
        }
        return results;
    }

    private static byte[] tryInflate(byte[] src, int offset, int length) {
        for (boolean nowrap : new boolean[]{false, true}) {
            Inflater inflater = new Inflater(nowrap);
            try {
                inflater.setInput(src, offset, length);
                ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.min(262144, length * 3));
                byte[] buf = new byte[8192];
                int maxOut = 3 * 1024 * 1024;
                while (!inflater.finished() && bos.size() < maxOut) {
                    int count = inflater.inflate(buf);
                    if (count == 0) {
                        if (inflater.needsInput() || inflater.needsDictionary()) break;
                    } else {
                        bos.write(buf, 0, count);
                    }
                }
                if (bos.size() > 0) {
                    return bos.toByteArray();
                }
            } catch (Exception ignored) {
            } finally {
                inflater.end();
            }
        }
        return null;
    }

    private static void parseToUnicodeCMap(String cmapText, Map<Integer, String> map) {
        Matcher bfCharBlock = Pattern.compile("beginbfchar(.*?)endbfchar", Pattern.DOTALL).matcher(cmapText);
        Pattern pairPattern = Pattern.compile("<([0-9A-Fa-f]+)>\\s*<([0-9A-Fa-f]+)>");
        while (bfCharBlock.find()) {
            Matcher m = pairPattern.matcher(bfCharBlock.group(1));
            while (m.find()) {
                try {
                    int code = Integer.parseInt(m.group(1), 16);
                    String uni = decodeHexToUnicode(m.group(2));
                    if (!uni.isEmpty()) map.put(code, uni);
                } catch (Exception ignored) {
                }
            }
        }

        Matcher bfRangeBlock = Pattern.compile("beginbfrange(.*?)endbfrange", Pattern.DOTALL).matcher(cmapText);
        Pattern rangePattern = Pattern.compile("<([0-9A-Fa-f]+)>\\s*<([0-9A-Fa-f]+)>\\s*<([0-9A-Fa-f]+)>");
        while (bfRangeBlock.find()) {
            Matcher m = rangePattern.matcher(bfRangeBlock.group(1));
            while (m.find()) {
                try {
                    int start = Integer.parseInt(m.group(1), 16);
                    int end = Integer.parseInt(m.group(2), 16);
                    int target = Integer.parseInt(m.group(3), 16);
                    if (end >= start && (end - start) <= 2048) {
                        for (int c = start; c <= end; c++) {
                            int u = target + (c - start);
                            map.put(c, new String(Character.toChars(u)));
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static String extractTextFromBtEtStream(String stream, Map<Integer, String> toUnicode) {
        StringBuilder sb = new StringBuilder();
        Matcher btMatcher = Pattern.compile("BT\\b(.*?)\\bET", Pattern.DOTALL).matcher(stream);
        Pattern tokenPattern = Pattern.compile(
                "<([0-9A-Fa-f\\s]+)>|\\(((\\\\.|[^\\\\()])*)\\)|\\b(Td|TD|T\\*|'|\"|Tj|TJ)\\b"
        );

        while (btMatcher.find()) {
            String block = btMatcher.group(1);
            Matcher tm = tokenPattern.matcher(block);
            while (tm.find()) {
                String hex = tm.group(1);
                String lit = tm.group(2);
                String op = tm.group(4);
                if (hex != null) {
                    sb.append(decodePdfHexToken(hex, toUnicode));
                } else if (lit != null) {
                    sb.append(decodePdfLiteralString(lit, toUnicode));
                } else if (op != null) {
                    if ("Td".equals(op) || "TD".equals(op) || "T*".equals(op) || "'".equals(op) || "\"".equals(op)) {
                        if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n') {
                            sb.append('\n');
                        }
                    }
                }
            }
            sb.append("\n\n");
        }
        return sb.toString();
    }

    private static String decodePdfHexToken(String hexRaw, Map<Integer, String> toUnicode) {
        String hex = hexRaw.replaceAll("\\s+", "");
        if (hex.isEmpty()) return "";

        if (hex.toUpperCase().startsWith("FEFF")) {
            return decodeHexToUnicode(hex.substring(4));
        }

        if (!toUnicode.isEmpty() && hex.length() % 4 == 0) {
            StringBuilder mapped = new StringBuilder();
            int hitCount = 0;
            int total = hex.length() / 4;
            for (int i = 0; i + 4 <= hex.length(); i += 4) {
                int code = Integer.parseInt(hex.substring(i, i + 4), 16);
                String u = toUnicode.get(code);
                if (u != null) {
                    mapped.append(u);
                    hitCount++;
                } else if (code >= 0x20 && code <= 0x9FFF) {
                    mapped.append((char) code);
                }
            }
            if (hitCount > 0 && hitCount * 2 >= total) {
                return mapped.toString();
            }
        }

        if (hex.length() % 4 == 0) {
            return decodeHexToUnicode(hex);
        }

        byte[] raw = hexToBytes(hex);
        return new String(raw, StandardCharsets.UTF_8);
    }

    private static String decodePdfLiteralString(String lit, Map<Integer, String> toUnicode) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(lit.length());
        for (int i = 0; i < lit.length(); i++) {
            char c = lit.charAt(i);
            if (c == '\\' && i + 1 < lit.length()) {
                char next = lit.charAt(++i);
                switch (next) {
                    case 'n': bos.write('\n'); break;
                    case 'r': bos.write('\r'); break;
                    case 't': bos.write('\t'); break;
                    case 'b': bos.write('\b'); break;
                    case 'f': bos.write('\f'); break;
                    case '(': bos.write('('); break;
                    case ')': bos.write(')'); break;
                    case '\\': bos.write('\\'); break;
                    default:
                        if (next >= '0' && next <= '7') {
                            StringBuilder oct = new StringBuilder().append(next);
                            while (oct.length() < 3 && i + 1 < lit.length()
                                    && lit.charAt(i + 1) >= '0' && lit.charAt(i + 1) <= '7') {
                                oct.append(lit.charAt(++i));
                            }
                            bos.write(Integer.parseInt(oct.toString(), 8) & 0xFF);
                        } else {
                            bos.write(next & 0xFF);
                        }
                        break;
                }
            } else {
                bos.write(c & 0xFF);
            }
        }
        byte[] bytes = bos.toByteArray();
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFE && (bytes[1] & 0xFF) == 0xFF) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16BE);
        }
        if (bytes.length % 2 == 0 && !toUnicode.isEmpty()) {
            StringBuilder mapped = new StringBuilder();
            int hits = 0;
            for (int i = 0; i + 1 < bytes.length; i += 2) {
                int code = ((bytes[i] & 0xFF) << 8) | (bytes[i + 1] & 0xFF);
                String u = toUnicode.get(code);
                if (u != null) {
                    mapped.append(u);
                    hits++;
                }
            }
            if (hits > 0 && hits * 2 >= (bytes.length / 2)) {
                return mapped.toString();
            }
        }
        try {
            return new String(bytes, Charset.forName("GB18030"));
        } catch (Exception e) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private static String decodeHexToUnicode(String hex) {
        byte[] bytes = hexToBytes(hex);
        if (bytes.length == 0) return "";
        if (bytes.length % 2 == 0) {
            return new String(bytes, StandardCharsets.UTF_16BE);
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static byte[] hexToBytes(String hex) {
        int len = hex.length() & ~1;
        byte[] out = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            try {
                out[i / 2] = (byte) Integer.parseInt(hex.substring(i, i + 2), 16);
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    private static boolean isReadableTextBlock(String s) {
        if (s == null) return false;
        String t = s.trim();
        if (t.length() < 15) return false;
        int readable = 0;
        int controlOrWeird = 0;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if ((c >= 0x4E00 && c <= 0x9FFF)
                    || (c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || "，。！？；：“”‘’、（）：《》—…,.!?;:'\"()- \n\t".indexOf(c) >= 0) {
                readable++;
            } else if (c < 0x20 || (c >= 0x7F && c < 0xA0) || c == '\uFFFD') {
                controlOrWeird++;
            }
        }
        return readable >= 12 && readable > controlOrWeird * 3 && ((float) readable / t.length()) >= 0.65f;
    }

    public static String formatPdfExtractedParagraphs(String raw) {
        if (raw == null || raw.trim().isEmpty()) return "";
        String[] lines = raw.split("\\r?\\n");
        List<String> cleanedLines = new ArrayList<>();
        for (String line : lines) {
            String t = line.replace(' ', ' ').trim();
            // Filter out standalone header/footer page numbers like "12", "iv"
            if (t.length() <= 8 && t.matches("^[0-9ivxldcIVXLDC\\s\\-\\.·—]+$") && cleanedLines.size() <= 2) {
                continue;
            }
            if (!t.isEmpty()) {
                cleanedLines.add(t);
            }
        }
        if (cleanedLines.isEmpty()) return "";

        List<String> paragraphs = new ArrayList<>();
        StringBuilder curPara = new StringBuilder();

        for (int i = 0; i < cleanedLines.size(); i++) {
            String l = cleanedLines.get(i);
            if (curPara.length() == 0) {
                curPara.append(l);
                continue;
            }

            String prev = curPara.toString().trim();
            char lastCh = prev.charAt(prev.length() - 1);
            boolean isPrevEndPunct = (lastCh == '.' || lastCh == '!' || lastCh == '?' || lastCh == '。' || lastCh == '！' || lastCh == '？' || lastCh == '”' || lastCh == '"' || lastCh == '’');
            boolean isHeadingOrSection = l.startsWith("Chapter") || l.startsWith("CHAPTER") || l.startsWith("Part") || l.startsWith("PART")
                    || l.startsWith("Section") || l.startsWith("SECTION") || l.startsWith("©") || l.startsWith("Copyright");
            boolean isHyphenated = prev.endsWith("-") && l.length() > 0 && Character.isLetter(l.charAt(0));

            if (isHyphenated) {
                // De-hyphenate broken words across line wraps: e.g. "interna-" + "tional" -> "international"
                curPara.setLength(curPara.length() - 1);
                curPara.append(l);
            } else if (isHeadingOrSection || (isPrevEndPunct && (prev.length() < 38 || Character.isUpperCase(l.charAt(0)) || l.startsWith("“") || l.startsWith("\"")))) {
                paragraphs.add(curPara.toString().trim());
                curPara.setLength(0);
                curPara.append(l);
            } else {
                // If it is regular prose, stitch lines seamlessly with a space for Western text
                char firstCh = l.charAt(0);
                boolean isPrevCjk = (lastCh >= 0x4E00 && lastCh <= 0x9FFF);
                boolean isCurCjk = (firstCh >= 0x4E00 && firstCh <= 0x9FFF);
                if (isPrevCjk && isCurCjk) {
                    curPara.append(l);
                } else {
                    curPara.append(' ').append(l);
                }
            }
        }
        if (curPara.length() > 0) {
            paragraphs.add(curPara.toString().trim());
        }

        StringBuilder result = new StringBuilder();
        for (String p : paragraphs) {
            String cleanP = p.replaceAll("\\s+", " ").trim();
            if (!cleanP.isEmpty()) {
                result.append("　　").append(cleanP).append("\n");
            }
        }
        return result.toString().trim();
    }
}
