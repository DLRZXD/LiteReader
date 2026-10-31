package com.litereader.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.pdf.PdfRenderer;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.DisplayCutout;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.text.InputType;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.litereader.app.data.Book;
import com.litereader.app.data.BookVaultCrypto;
import com.litereader.app.data.Bookmark;
import com.litereader.app.data.NoteItem;
import com.litereader.app.data.ReaderDatabase;
import com.litereader.app.data.ReadingSettings;
import com.litereader.app.parser.BookParserEngine;
import com.litereader.app.parser.BookTranslationEngine;
import com.litereader.app.parser.ChineseTradToSimpConverter;
import com.litereader.app.parser.ParsedBook;
import com.litereader.app.parser.PdfConverterEngine;
import com.litereader.app.ui.PagedReaderView;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Minimalist Offline Reader Activity with Full Typography Sliders, Brightness Control,
 * Natural PDF Image Rendering, Dynamic Portrait/Landscape Scrollable Settings Sheet, and Full-Screen Mode.
 */
public class ReaderActivity extends Activity {

    private ReaderDatabase db;
    private ReadingSettings settings;
    private Book book;
    private ParsedBook parsedBook;
    private File decryptedSessionFile;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Runnable pendingPdfReRenderRunnable;

    private FrameLayout rootFrame;
    private PagedReaderView pagedReaderView;
    private ScrollView textScrollView;
    private LinearLayout pageContentBox;
    private TextView bodyTextView;
    private LinearLayout pdfScrollPagesContainer;

    private LinearLayout scrollModeBottomFooter;
    private TextView scrollFooterLeftChapter;
    private TextView scrollFooterRightProgress;

    private LinearLayout topOverlayContainer;
    private LinearLayout topBar;
    private ScrollView settingsScrollContainer;
    private LinearLayout settingsPanel;
    private LinearLayout bottomBar;
    private TextView topTranslateBtn;
    private TextView topTradToSimpBtn;
    private TextView tradToSimpSettingBtn;
    private TextView topPdfConvertBtn;
    private TextView pdfReflowSettingBtn;

    private TextView topTitleView;
    private TextView bottomProgressLabel;
    private SeekBar chapterSeekBar;

    private final List<TextView> browseModeButtons = new ArrayList<>();
    private final List<TextView> animModeButtons = new ArrayList<>();
    private final List<TextView> fontFamilyButtons = new ArrayList<>();

    private int currentChapterIndex = 0;
    private int pendingOffsetOrChar = 0;
    private boolean barsVisible = false;
    private int topCameraSafeOffsetPx = 0;

    private ParcelFileDescriptor pdfDescriptor;
    private PdfRenderer pdfRenderer;

    private float scrollTouchDownX;
    private float scrollTouchDownY;
    private int touchSlop;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        db = ReaderDatabase.getInstance(this);
        settings = new ReadingSettings(this);
        touchSlop = ViewConfiguration.get(this).getScaledTouchSlop();
        topCameraSafeOffsetPx = dp(42);

        settings.setOrientationMode(ReadingSettings.ORIENTATION_AUTO);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        if (settings.isCustomBrightnessEnabled()) {
            applyScreenBrightness(settings.getScreenBrightness());
        }

        long bookId = getIntent().getLongExtra("book_id", -1L);
        book = db.getBookById(bookId);
        if (book == null) {
            Toast.makeText(this, "未找到该书籍记录", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        if (getIntent().getBooleanExtra("convert_pdf_reflow", false)) {
            settings.setPdfReflowEnabledForBook(book.id, true);
        }

        int jumpChapter = getIntent().getIntExtra("jump_chapter", -1);
        int jumpOffset = getIntent().getIntExtra("jump_offset", -1);
        currentChapterIndex = jumpChapter >= 0 ? jumpChapter : book.currentChapter;
        pendingOffsetOrChar = jumpOffset >= 0 ? jumpOffset : book.scrollOffset;

        buildReaderUi();
        applyVisualSettings();
        applyImmersiveFullScreen(settings.isFullScreenReading());
        loadAndParseBook();
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyImmersiveFullScreen(!barsVisible && settings.isFullScreenReading());
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            applyImmersiveFullScreen(!barsVisible && settings.isFullScreenReading());
        }
    }

    private void applyScreenBrightness(int percent) {
        try {
            Window window = getWindow();
            if (window == null) return;
            WindowManager.LayoutParams lp = window.getAttributes();
            lp.screenBrightness = Math.max(0.02f, Math.min(1.0f, percent / 100.0f));
            window.setAttributes(lp);
        } catch (Exception ignored) {
        }
    }

    @SuppressWarnings("deprecation")
    private void applyImmersiveFullScreen(boolean fullScreen) {
        try {
            Window window = getWindow();
            if (window == null) return;
            int bg = ReadingSettings.getBackgroundColor(settings.getTheme());
            window.getDecorView().setBackgroundColor(bg);
            window.setStatusBarColor(bg);
            window.setNavigationBarColor(bg);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.setNavigationBarContrastEnforced(false);
                window.setStatusBarContrastEnforced(false);
            }

            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                WindowManager.LayoutParams lp = window.getAttributes();
                lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
                window.setAttributes(lp);
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.setDecorFitsSystemWindows(false);
                WindowInsetsController controller = window.getInsetsController();
                if (controller != null) {
                    if (fullScreen) {
                        controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                        controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                    } else {
                        controller.show(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                    }
                }
            } else {
                View decor = window.getDecorView();
                if (fullScreen) {
                    decor.setSystemUiVisibility(
                            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    );
                } else {
                    decor.setSystemUiVisibility(
                            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    );
                }
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * Dynamically updates the height of `settingsScrollContainer` based on the current Portrait or Landscape
     * screen height so the settings menu NEVER overflows the screen and is ALWAYS smoothly scrollable!
     */
    private void updateSettingsSheetMaxHeight() {
        if (settingsScrollContainer == null) return;
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int screenW = (rootFrame != null && rootFrame.getWidth() > 0) ? rootFrame.getWidth() : dm.widthPixels;
        int screenH = (rootFrame != null && rootFrame.getHeight() > 0) ? rootFrame.getHeight() : dm.heightPixels;
        boolean isLandscape = screenW > screenH;

        // Reserve space for topBar (~100dp) and bottomBar (~52dp) so the ScrollView sits cleanly between them
        int reservedPx = topCameraSafeOffsetPx + dp(isLandscape ? 118 : 136);
        int maxAllowed = Math.max(dp(180), screenH - reservedPx);
        int targetHeight = Math.min(maxAllowed, (int) (screenH * (isLandscape ? 0.56f : 0.60f)));

        ViewGroup.LayoutParams lp = settingsScrollContainer.getLayoutParams();
        if (lp != null && lp.height != targetHeight) {
            lp.height = targetHeight;
            settingsScrollContainer.setLayoutParams(lp);
            settingsScrollContainer.requestLayout();
        }
    }

    private void buildReaderUi() {
        rootFrame = new FrameLayout(this);
        rootFrame.setOnApplyWindowInsetsListener((v, insets) -> {
            int cutoutTop = 0;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && insets != null) {
                DisplayCutout cutout = insets.getDisplayCutout();
                if (cutout != null) {
                    cutoutTop = cutout.getSafeInsetTop();
                }
            }
            boolean isLandscape = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
            int minTop = isLandscape ? dp(18) : dp(42);
            topCameraSafeOffsetPx = Math.max(minTop, cutoutTop + dp(10));
            if (topBar != null) {
                topBar.setPadding(dp(10), topCameraSafeOffsetPx, dp(10), dp(8));
            }
            updateSettingsSheetMaxHeight();
            applyScrollBoxPadding();
            return insets;
        });

        // ==================== LAYER 1A: HORIZONTAL PAGED VIEW (左右翻页模式) ====================
        pagedReaderView = new PagedReaderView(this);
        pagedReaderView.setGestureListener(new PagedReaderView.ReaderGestureListener() {
            @Override
            public boolean isMenuVisible() {
                return barsVisible;
            }

            @Override
            public void onToggleMenu() {
                setBarsVisible(!barsVisible);
            }

            @Override
            public void onRetractMenu() {
                setBarsVisible(false);
            }

            @Override
            public void onRequestPrevPage() {
                handlePrevPageAction();
            }

            @Override
            public void onRequestNextPage() {
                handleNextPageAction();
            }

            @Override
            public void onViewportSizeChanged(int width, int height) {
                updateSettingsSheetMaxHeight();
                applyImmersiveFullScreen(!barsVisible && settings.isFullScreenReading());
                if (parsedBook != null && parsedBook.isPdf) {
                    renderCurrentChapter(false);
                }
            }
        });
        rootFrame.addView(pagedReaderView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // ==================== LAYER 1B: VERTICAL SCROLL VIEW (上下滚动模式) ====================
        textScrollView = new ScrollView(this);
        textScrollView.setFillViewport(true);
        textScrollView.setVerticalScrollBarEnabled(false);
        textScrollView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        textScrollView.setOnScrollChangeListener((v, scrollX, scrollY, oldScrollX, oldScrollY) -> updateProgressBarLabel());
        textScrollView.setOnTouchListener((v, event) -> handleScrollTouchGesture(event));

        pageContentBox = new LinearLayout(this);
        pageContentBox.setOrientation(LinearLayout.VERTICAL);

        pdfScrollPagesContainer = new LinearLayout(this);
        pdfScrollPagesContainer.setOrientation(LinearLayout.VERTICAL);
        pdfScrollPagesContainer.setVisibility(View.GONE);
        pageContentBox.addView(pdfScrollPagesContainer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        bodyTextView = new TextView(this);
        bodyTextView.setTextIsSelectable(false);
        bodyTextView.setText("正在解析《" + book.title + "》(" + book.format + ")…");
        pageContentBox.addView(bodyTextView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        textScrollView.addView(pageContentBox);
        rootFrame.addView(textScrollView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Bottom Status Bar for Scroll Mode
        scrollModeBottomFooter = new LinearLayout(this);
        scrollModeBottomFooter.setOrientation(LinearLayout.HORIZONTAL);
        scrollModeBottomFooter.setGravity(Gravity.CENTER_VERTICAL);
        scrollModeBottomFooter.setPadding(dp(16), dp(4), dp(16), dp(8));

        scrollFooterLeftChapter = new TextView(this);
        scrollFooterLeftChapter.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        scrollFooterLeftChapter.setSingleLine(true);
        scrollFooterLeftChapter.setEllipsize(TextUtils.TruncateAt.END);
        scrollModeBottomFooter.addView(scrollFooterLeftChapter, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        scrollFooterRightProgress = new TextView(this);
        scrollFooterRightProgress.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        scrollFooterRightProgress.setSingleLine(true);
        scrollFooterRightProgress.setPadding(dp(12), 0, 0, 0);
        scrollModeBottomFooter.addView(scrollFooterRightProgress, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout.LayoutParams scrollFooterLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        rootFrame.addView(scrollModeBottomFooter, scrollFooterLp);

        // ==================== LAYER 2: TOP FLOATING MENU ====================
        topOverlayContainer = new LinearLayout(this);
        topOverlayContainer.setOrientation(LinearLayout.VERTICAL);
        topOverlayContainer.setClickable(true);

        topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.VERTICAL);
        topBar.setPadding(dp(10), topCameraSafeOffsetPx, dp(10), dp(8));

        LinearLayout topTitleRow = new LinearLayout(this);
        topTitleRow.setOrientation(LinearLayout.HORIZONTAL);
        topTitleRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView backBtn = createBarButton("← 返回书架");
        backBtn.setOnClickListener(v -> {
            persistCurrentProgress();
            finish();
        });
        topTitleRow.addView(backBtn);

        topTitleView = new TextView(this);
        topTitleView.setText(formatBookText(book.title));
        topTitleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        topTitleView.setTypeface(Typeface.DEFAULT_BOLD);
        topTitleView.setSingleLine(true);
        topTitleView.setEllipsize(TextUtils.TruncateAt.END);
        topTitleView.setPadding(dp(10), 0, dp(6), 0);
        topTitleRow.addView(topTitleView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        topTranslateBtn = createBarButton("🌐 翻译");
        LinearLayout.LayoutParams transBtnLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        transBtnLp.rightMargin = dp(6);
        topTranslateBtn.setOnClickListener(v -> showTranslationControlDialog());
        topTitleRow.addView(topTranslateBtn, transBtnLp);

        topTradToSimpBtn = createBarButton("繁→简");
        LinearLayout.LayoutParams tradBtnLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tradBtnLp.rightMargin = dp(6);
        topTradToSimpBtn.setOnClickListener(v -> toggleTradToSimpConversion());
        topTitleRow.addView(topTradToSimpBtn, tradBtnLp);

        topPdfConvertBtn = createBarButton("🔄 一键转换");
        GradientDrawable convGd = new GradientDrawable();
        convGd.setColor(Color.parseColor("#C85A32"));
        convGd.setCornerRadius(dp(6));
        topPdfConvertBtn.setBackground(convGd);
        topPdfConvertBtn.setVisibility("PDF".equalsIgnoreCase(book.format) ? View.VISIBLE : View.GONE);
        topPdfConvertBtn.setOnClickListener(v -> showPdfConversionDialog());
        topTitleRow.addView(topPdfConvertBtn);

        topBar.addView(topTitleRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout topActionsRow = new LinearLayout(this);
        topActionsRow.setOrientation(LinearLayout.HORIZONTAL);
        topActionsRow.setWeightSum(5f);
        topActionsRow.setPadding(0, dp(6), 0, 0);

        String[] actionLabels = new String[]{"📑 目录", "🔍 搜索", "🔖 书签", "📝 笔记", "Aa 设置"};
        View.OnClickListener[] actionClicks = new View.OnClickListener[]{
                v -> showTocAndBookmarksDialog(),
                v -> showInBookSearchDialog(),
                v -> addCurrentBookmark(),
                v -> showAddPlainTextNoteDialog(),
                v -> {
                    boolean show = settingsScrollContainer.getVisibility() != View.VISIBLE;
                    updateSettingsSheetMaxHeight();
                    settingsScrollContainer.setVisibility(show ? View.VISIBLE : View.GONE);
                    updateSettingsHighlights();
                }
        };
        for (int i = 0; i < actionLabels.length; i++) {
            TextView btn = createBarButton(actionLabels[i]);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i < actionLabels.length - 1) lp.rightMargin = dp(4);
            btn.setOnClickListener(actionClicks[i]);
            topActionsRow.addView(btn, lp);
        }
        topBar.addView(topActionsRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        topOverlayContainer.addView(topBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout.LayoutParams topOverlayLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP);
        rootFrame.addView(topOverlayContainer, topOverlayLp);

        // ==================== LAYER 3: BOTTOM FLOATING PROGRESS BAR + SETTINGS SHEET ====================
        LinearLayout bottomOverlayWrapper = new LinearLayout(this);
        bottomOverlayWrapper.setOrientation(LinearLayout.VERTICAL);
        bottomOverlayWrapper.setClickable(false);

        settingsScrollContainer = new ScrollView(this);
        settingsScrollContainer.setFillViewport(false);
        settingsScrollContainer.setVerticalScrollBarEnabled(true);
        settingsScrollContainer.setScrollbarFadingEnabled(false);
        settingsScrollContainer.setVisibility(View.GONE);
        settingsPanel = buildSettingsDrawer();
        settingsScrollContainer.addView(settingsPanel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        int initSheetHeight = (int) (getResources().getDisplayMetrics().heightPixels * 0.56f);
        bottomOverlayWrapper.addView(settingsScrollContainer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, initSheetHeight));

        bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setGravity(Gravity.CENTER_VERTICAL);
        bottomBar.setClickable(true);
        bottomBar.setPadding(dp(10), dp(8), dp(10), dp(12));

        TextView prevBtn = createBarButton("◀ 上一页/节");
        prevBtn.setOnClickListener(v -> navigateChapter(-1, false));
        bottomBar.addView(prevBtn);

        chapterSeekBar = new SeekBar(this);
        chapterSeekBar.setMax(100);
        chapterSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && parsedBook != null && !parsedBook.chapters.isEmpty()) {
                    currentChapterIndex = Math.min(parsedBook.chapters.size() - 1, Math.max(0, progress));
                    pendingOffsetOrChar = 0;
                    renderCurrentChapter(false);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                persistCurrentProgress();
            }
        });
        bottomBar.addView(chapterSeekBar, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        bottomProgressLabel = new TextView(this);
        bottomProgressLabel.setText("0.0%");
        bottomProgressLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        bottomProgressLabel.setSingleLine(true);
        bottomProgressLabel.setPadding(dp(4), 0, dp(6), 0);
        bottomBar.addView(bottomProgressLabel);

        TextView nextBtn = createBarButton("下一页/节 ▶");
        nextBtn.setOnClickListener(v -> navigateChapter(1, false));
        bottomBar.addView(nextBtn);

        bottomOverlayWrapper.addView(bottomBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout.LayoutParams bottomOverlayLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        rootFrame.addView(bottomOverlayWrapper, bottomOverlayLp);

        updatePdfConvertButtonsState();
        setBarsVisible(false);
        setContentView(rootFrame);
    }

    private void scheduleDebouncedPdfReRender() {
        if (parsedBook == null || !parsedBook.isPdf) return;
        if (pendingPdfReRenderRunnable != null) {
            mainHandler.removeCallbacks(pendingPdfReRenderRunnable);
        }
        pendingPdfReRenderRunnable = () -> renderCurrentChapter(false);
        mainHandler.postDelayed(pendingPdfReRenderRunnable, 140L);
    }

    /**
     * Builds the Settings Sheet:
     * - TOP SECTION: Mode row (左右翻页 / 上下滚动 / PDF重排), Theme swatches, Font pills, and Page-Turn Animation
     * - MIDDLE SECTION: 6 Sliders + (- / +) rows (亮度, 字号, 行距, 段间距, 字间距, 左右留白)
     * - BOTTOM SECTION: 3 Toggle Switches (全屏阅读, 首行缩进, 两端对齐)
     */
    private LinearLayout buildSettingsDrawer() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(12), dp(16), dp(18));
        GradientDrawable sheetBg = new GradientDrawable();
        sheetBg.setColor(Color.parseColor("#18191D"));
        sheetBg.setCornerRadii(new float[]{dp(18), dp(18), dp(18), dp(18), 0, 0, 0, 0});
        panel.setBackground(sheetBg);

        // ==================== PART 1: TOP QUICK ROWS (Browse Mode, PDF Reflow, Themes, Fonts) ====================

        // Row 1: Browse Mode (2 cols) + Trad->Simp (1 col) + PDF Reflow (1 col) = 4 equal-weight items
        LinearLayout modeRow = new LinearLayout(this);
        modeRow.setOrientation(LinearLayout.HORIZONTAL);
        modeRow.setWeightSum(4f);
        modeRow.setPadding(0, 0, 0, dp(6));

        browseModeButtons.clear();
        String[] browseNames = new String[]{"左右翻页", "上下滚动"};
        for (int m = 0; m <= 1; m++) {
            final int modeVal = m;
            TextView btn = createSelectablePill(browseNames[m], settings.getBrowseMode() == modeVal);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.rightMargin = dp(5);
            btn.setOnClickListener(v -> {
                settings.setBrowseMode(modeVal);
                updateSettingsHighlights();
                applyVisualSettings();
                renderCurrentChapter(false);
            });
            browseModeButtons.add(btn);
            modeRow.addView(btn, lp);
        }

        boolean isSimpActive = isTradToSimpActive();
        tradToSimpSettingBtn = createSelectablePill(isSimpActive ? "✨已转简" : "繁→简", isSimpActive);
        LinearLayout.LayoutParams tsLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tsLp.rightMargin = dp(5);
        tradToSimpSettingBtn.setOnClickListener(v -> toggleTradToSimpConversion());
        modeRow.addView(tradToSimpSettingBtn, tsLp);

        boolean isReflowActive = book != null && settings.isPdfReflowEnabledForBook(book.id);
        pdfReflowSettingBtn = createSelectablePill(isReflowActive ? "✨PDF重排" : "PDF重排", isReflowActive);
        LinearLayout.LayoutParams piLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        pdfReflowSettingBtn.setOnClickListener(v -> togglePdfReflowConversion());
        modeRow.addView(pdfReflowSettingBtn, piLp);

        panel.addView(modeRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Row 2: Themes Row (6 equal-weight items)
        LinearLayout themeRow = new LinearLayout(this);
        themeRow.setOrientation(LinearLayout.HORIZONTAL);
        themeRow.setWeightSum(6f);
        themeRow.setPadding(0, 0, 0, dp(6));
        String[] shortThemeNames = new String[]{"纯白", "羊皮纸", "护眼绿", "暖米", "暗夜蓝", "纯黑"};
        for (int t = 0; t <= 5; t++) {
            final int themeId = t;
            TextView swatch = new TextView(this);
            swatch.setText(shortThemeNames[t]);
            swatch.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
            swatch.setSingleLine(true);
            swatch.setGravity(Gravity.CENTER);
            swatch.setPadding(dp(2), dp(6), dp(2), dp(6));
            GradientDrawable gd = new GradientDrawable();
            gd.setColor(ReadingSettings.getBackgroundColor(themeId));
            gd.setCornerRadius(dp(6));
            gd.setStroke(dp(1), Color.parseColor("#6E7681"));
            swatch.setBackground(gd);
            swatch.setTextColor(ReadingSettings.getTextColor(themeId));

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (t < 5) lp.rightMargin = dp(4);
            swatch.setOnClickListener(v -> {
                settings.setTheme(themeId);
                applyVisualSettings();
                if (parsedBook != null && parsedBook.isPdf) {
                    renderCurrentChapter(false);
                }
            });
            themeRow.addView(swatch, lp);
        }
        panel.addView(themeRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Row 3: Font Family Row (4 equal-weight items)
        LinearLayout fontRow = new LinearLayout(this);
        fontRow.setOrientation(LinearLayout.HORIZONTAL);
        fontRow.setWeightSum(4f);
        fontRow.setPadding(0, 0, 0, dp(6));

        fontFamilyButtons.clear();
        for (int f = 0; f <= 3; f++) {
            final int fontId = f;
            TextView fBtn = createSelectablePill(ReadingSettings.getFontName(fontId), settings.getFontFamily() == fontId);
            LinearLayout.LayoutParams fLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (f < 3) fLp.rightMargin = dp(6);
            fBtn.setOnClickListener(v -> {
                settings.setFontFamily(fontId);
                updateSettingsHighlights();
                applyVisualSettings();
                if (parsedBook != null && parsedBook.isPdf) {
                    renderCurrentChapter(false);
                }
            });
            fontFamilyButtons.add(fBtn);
            fontRow.addView(fBtn, fLp);
        }
        panel.addView(fontRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Row 4: Page-Turn Animation Row (5 equal-weight items)
        LinearLayout animRow = new LinearLayout(this);
        animRow.setOrientation(LinearLayout.HORIZONTAL);
        animRow.setWeightSum(5f);
        animRow.setPadding(0, 0, 0, dp(4));

        animModeButtons.clear();
        String[] shortAnimNames = new String[]{"覆盖翻页", "平移滑动", "仿真翻书", "柔和渐变", "无动画"};
        for (int a = 0; a <= 4; a++) {
            final int animVal = a;
            TextView btn = createSelectablePill(shortAnimNames[a], settings.getPageTurnAnim() == animVal);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (a < 4) lp.rightMargin = dp(4);
            btn.setOnClickListener(v -> {
                settings.setPageTurnAnim(animVal);
                updateSettingsHighlights();
                applyVisualSettings();
            });
            animModeButtons.add(btn);
            animRow.addView(btn, lp);
        }
        panel.addView(animRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        panel.addView(createSheetDivider());

        // ==================== PART 2: 6 SLIDER + (- / +) CONTROLS ====================

        // 1. 亮度 (Brightness: 1% .. 100%)
        int initBright = settings.getScreenBrightness();
        panel.addView(createSliderControlRow(
                "亮度",
                99,
                Math.max(0, Math.min(99, initBright - 1)),
                progress -> (progress + 1) + "%",
                (progress, finished) -> {
                    int pct = progress + 1;
                    settings.setCustomBrightnessEnabled(true);
                    settings.setScreenBrightness(pct);
                    applyScreenBrightness(pct);
                }
        ));

        // 2. 字号 (Font Size: 12 .. 38, step 1)
        int initSizeIdx = Math.round(settings.getFontSizeSp()) - 12;
        panel.addView(createSliderControlRow(
                "字号",
                26,
                Math.max(0, Math.min(26, initSizeIdx)),
                progress -> String.valueOf(12 + progress),
                (progress, finished) -> {
                    float sp = 12f + progress;
                    settings.setFontSizeSp(sp);
                    applyVisualSettings();
                    if (finished) {
                        if (parsedBook != null && parsedBook.isPdf) renderCurrentChapter(false);
                    } else {
                        scheduleDebouncedPdfReRender();
                    }
                }
        ));

        // 3. 行距 (Line Spacing: 1.00 .. 2.50, step 0.05 -> 30 steps)
        int initLineIdx = Math.round((settings.getLineSpacingMultiplier() - 1.00f) / 0.05f);
        panel.addView(createSliderControlRow(
                "行距",
                30,
                Math.max(0, Math.min(30, initLineIdx)),
                progress -> String.format(Locale.US, "%.2f", 1.00f + progress * 0.05f),
                (progress, finished) -> {
                    float mult = 1.00f + progress * 0.05f;
                    settings.setLineSpacingMultiplier(mult);
                    applyVisualSettings();
                    if (finished) {
                        if (parsedBook != null && parsedBook.isPdf) renderCurrentChapter(false);
                    } else {
                        scheduleDebouncedPdfReRender();
                    }
                }
        ));

        // 4. 段间距 (Paragraph Spacing: 0.00 em .. 2.00 em, step 0.05 em -> 40 steps)
        int initParaIdx = Math.round(settings.getParagraphSpacingEm() / 0.05f);
        panel.addView(createSliderControlRow(
                "段间距",
                40,
                Math.max(0, Math.min(40, initParaIdx)),
                progress -> String.format(Locale.US, "%.2f em", progress * 0.05f),
                (progress, finished) -> {
                    float em = progress * 0.05f;
                    settings.setParagraphSpacingEm(em);
                    applyVisualSettings();
                    if (finished) {
                        if (parsedBook != null && parsedBook.isPdf) renderCurrentChapter(false);
                    } else {
                        scheduleDebouncedPdfReRender();
                    }
                }
        ));

        // 5. 字间距 (Letter Spacing: -0.050 em .. 0.300 em, step 0.005 em -> 70 steps)
        int initLetterIdx = Math.round((settings.getLetterSpacingEm() + 0.050f) / 0.005f);
        panel.addView(createSliderControlRow(
                "字间距",
                70,
                Math.max(0, Math.min(70, initLetterIdx)),
                progress -> String.format(Locale.US, "%.3f em", -0.050f + progress * 0.005f),
                (progress, finished) -> {
                    float em = -0.050f + progress * 0.005f;
                    if (Math.abs(em) < 0.002f) em = 0.000f;
                    settings.setLetterSpacingEm(em);
                    applyVisualSettings();
                    if (finished) {
                        if (parsedBook != null && parsedBook.isPdf) renderCurrentChapter(false);
                    } else {
                        scheduleDebouncedPdfReRender();
                    }
                }
        ));

        // 6. 左右留白 (Horizontal Margin: 0 dp .. 48 dp, step 1 dp -> 48 steps)
        int initMarginIdx = settings.getMarginDp();
        panel.addView(createSliderControlRow(
                "左右留白",
                48,
                Math.max(0, Math.min(48, initMarginIdx)),
                progress -> progress + " dp",
                (progress, finished) -> {
                    settings.setMarginDp(progress);
                    applyVisualSettings();
                    if (finished) {
                        if (parsedBook != null && parsedBook.isPdf) renderCurrentChapter(false);
                    } else {
                        scheduleDebouncedPdfReRender();
                    }
                }
        ));

        panel.addView(createSheetDivider());

        // ==================== PART 3: 3 TOGGLE SWITCHES ====================
        panel.addView(createToggleSwitchRow(
                "全屏阅读",
                "隐藏系统状态栏与导航栏，正文用满整屏",
                settings.isFullScreenReading(),
                checked -> {
                    settings.setFullScreenReading(checked);
                    applyImmersiveFullScreen(checked && !barsVisible);
                }
        ));

        panel.addView(createToggleSwitchRow(
                "首行缩进",
                "中文长文的常见排版",
                settings.isFirstLineIndent(),
                checked -> {
                    settings.setFirstLineIndent(checked);
                    applyVisualSettings();
                    if (parsedBook != null) {
                        renderCurrentChapter(false);
                    }
                }
        ));

        panel.addView(createToggleSwitchRow(
                "两端对齐",
                "中文更整齐，西文可能出现稀疏间隙",
                settings.isJustifyAlign(),
                checked -> {
                    settings.setJustifyAlign(checked);
                    applyVisualSettings();
                    if (parsedBook != null && parsedBook.isPdf) {
                        renderCurrentChapter(false);
                    }
                }
        ));

        return panel;
    }

    private interface SliderValueFormatter {
        String format(int progress);
    }

    private interface SliderChangeListener {
        void onChanged(int progress, boolean finished);
    }

    private View createSliderControlRow(
            String labelText,
            final int maxProgress,
            int initialProgress,
            final SliderValueFormatter formatter,
            final SliderChangeListener listener) {

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(0, dp(3), 0, dp(6));

        LinearLayout topHeader = new LinearLayout(this);
        topHeader.setOrientation(LinearLayout.HORIZONTAL);
        topHeader.setGravity(Gravity.CENTER_VERTICAL);

        TextView labelTv = new TextView(this);
        labelTv.setText(labelText);
        labelTv.setTextColor(Color.parseColor("#E6EDF3"));
        labelTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        topHeader.addView(labelTv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final TextView valueTv = new TextView(this);
        valueTv.setText(formatter.format(initialProgress));
        valueTv.setTextColor(Color.parseColor("#9BA3AE"));
        valueTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        topHeader.addView(valueTv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        container.addView(topHeader, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout controlRow = new LinearLayout(this);
        controlRow.setOrientation(LinearLayout.HORIZONTAL);
        controlRow.setGravity(Gravity.CENTER_VERTICAL);
        controlRow.setPadding(0, dp(1), 0, 0);

        TextView minusBtn = new TextView(this);
        minusBtn.setText("−");
        minusBtn.setTextColor(Color.parseColor("#D0D7DE"));
        minusBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f);
        minusBtn.setGravity(Gravity.CENTER);
        minusBtn.setPadding(dp(12), dp(2), dp(12), dp(2));

        final SeekBar seekBar = new SeekBar(this);
        seekBar.setMax(maxProgress);
        seekBar.setProgress(initialProgress);

        TextView plusBtn = new TextView(this);
        plusBtn.setText("+");
        plusBtn.setTextColor(Color.parseColor("#D0D7DE"));
        plusBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f);
        plusBtn.setGravity(Gravity.CENTER);
        plusBtn.setPadding(dp(12), dp(2), dp(12), dp(2));

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                valueTv.setText(formatter.format(progress));
                if (fromUser) {
                    listener.onChanged(progress, false);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {}

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                int p = sb.getProgress();
                valueTv.setText(formatter.format(p));
                listener.onChanged(p, true);
            }
        });

        minusBtn.setOnClickListener(v -> {
            int next = Math.max(0, seekBar.getProgress() - 1);
            if (next != seekBar.getProgress()) {
                seekBar.setProgress(next);
                valueTv.setText(formatter.format(next));
                listener.onChanged(next, true);
            }
        });

        plusBtn.setOnClickListener(v -> {
            int next = Math.min(maxProgress, seekBar.getProgress() + 1);
            if (next != seekBar.getProgress()) {
                seekBar.setProgress(next);
                valueTv.setText(formatter.format(next));
                listener.onChanged(next, true);
            }
        });

        controlRow.addView(minusBtn, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        controlRow.addView(seekBar, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        controlRow.addView(plusBtn, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        container.addView(controlRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return container;
    }

    private interface ToggleChangeListener {
        void onToggled(boolean isChecked);
    }

    private View createToggleSwitchRow(String titleText, String subtitleText,
                                       boolean initialChecked, final ToggleChangeListener listener) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(7), 0, dp(7));

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);

        TextView titleTv = new TextView(this);
        titleTv.setText(titleText);
        titleTv.setTextColor(Color.parseColor("#E6EDF3"));
        titleTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        textCol.addView(titleTv);

        TextView subTv = new TextView(this);
        subTv.setText(subtitleText);
        subTv.setTextColor(Color.parseColor("#8B949E"));
        subTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        subTv.setPadding(0, dp(2), 0, 0);
        textCol.addView(subTv);

        row.addView(textCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final Switch sw = new Switch(this);
        sw.setChecked(initialChecked);
        sw.setOnCheckedChangeListener((buttonView, isChecked) -> listener.onToggled(isChecked));
        row.addView(sw, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        row.setOnClickListener(v -> sw.setChecked(!sw.isChecked()));
        return row;
    }

    private View createSheetDivider() {
        View v = new View(this);
        v.setBackgroundColor(Color.parseColor("#2D3139"));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        lp.topMargin = dp(5);
        lp.bottomMargin = dp(5);
        v.setLayoutParams(lp);
        return v;
    }

    private boolean isTradToSimpActive() {
        return book != null && settings.isTradToSimpEnabledForBook(book.id);
    }

    private String formatBookText(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        return isTradToSimpActive() ? ChineseTradToSimpConverter.convertToSimplified(raw) : raw;
    }

    private void toggleTradToSimpConversion() {
        if (book == null) return;
        if (parsedBook != null && parsedBook.isPdf) {
            new AlertDialog.Builder(this)
                    .setTitle("✨ 繁体 PDF 转换简体电子书")
                    .setMessage("当前书籍为 PDF 格式。是否提取全书文字并自动转换为「简体中文 TXT 流式电子书」以便自由排版阅读？")
                    .setPositiveButton("立即转换并打开", (d, w) -> {
                        settings.setTradToSimpEnabledForBook(book.id, true);
                        convertAndSavePdfToVaultAsTextBook();
                    })
                    .setNegativeButton("取消", null)
                    .show();
            return;
        }
        boolean nextSimp = !settings.isTradToSimpEnabledForBook(book.id);
        settings.setTradToSimpEnabledForBook(book.id, nextSimp);
        updatePdfConvertButtonsState();
        if (parsedBook != null) {
            String dispTitle = formatBookText(parsedBook.title);
            topTitleView.setText(dispTitle);
            db.forceUpdateBookTitle(book.id, dispTitle);
        }
        if (settings.getBrowseMode() == ReadingSettings.BROWSE_MODE_PAGED) {
            pendingOffsetOrChar = pagedReaderView.getCurrentCharOffset();
        }
        renderCurrentChapter(false);
        Toast.makeText(this,
                nextSimp
                        ? "✨ 已一键转换为简体中文（书名、目录与全书正文已同步转简）"
                        : "📖 已恢复原版繁体显示",
                Toast.LENGTH_SHORT).show();
    }

    private void updatePdfConvertButtonsState() {
        if (book == null) return;
        boolean isReflow = settings.isPdfReflowEnabledForBook(book.id);
        if (topPdfConvertBtn != null) {
            topPdfConvertBtn.setText(isReflow ? "✨已转重排" : "🔄 一键转换");
        }
        if (pdfReflowSettingBtn != null) {
            pdfReflowSettingBtn.setText(isReflow ? "✨PDF重排" : "PDF重排");
            styleSelectablePill(pdfReflowSettingBtn, isReflow);
        }
        boolean isSimp = settings.isTradToSimpEnabledForBook(book.id);
        if (topTradToSimpBtn != null) {
            topTradToSimpBtn.setText(isSimp ? "✨已转简体" : "繁→简");
            GradientDrawable gd = new GradientDrawable();
            gd.setCornerRadius(dp(6));
            if (isSimp) {
                gd.setColor(Color.parseColor("#2D5A4C"));
                topTradToSimpBtn.setTextColor(Color.WHITE);
            } else {
                gd.setColor(Color.parseColor("#2C313A"));
                gd.setStroke(dp(1), Color.parseColor("#484F58"));
                topTradToSimpBtn.setTextColor(Color.parseColor("#E6EDF3"));
            }
            topTradToSimpBtn.setBackground(gd);
        }
        if (tradToSimpSettingBtn != null) {
            tradToSimpSettingBtn.setText(isSimp ? "✨已转简" : "繁→简");
            styleSelectablePill(tradToSimpSettingBtn, isSimp);
        }
        int transMode = settings.getBookTranslationMode(book.id);
        if (topTranslateBtn != null) {
            if (transMode == ReadingSettings.TRANS_MODE_CHINESE_ONLY) {
                topTranslateBtn.setText("✨ 译文");
            } else if (transMode == ReadingSettings.TRANS_MODE_BILINGUAL) {
                topTranslateBtn.setText("📖 双语");
            } else {
                topTranslateBtn.setText("🌐 翻译");
            }
            GradientDrawable tgd = new GradientDrawable();
            tgd.setCornerRadius(dp(6));
            if (transMode != ReadingSettings.TRANS_MODE_OFF) {
                tgd.setColor(Color.parseColor("#1F6FEB"));
                topTranslateBtn.setTextColor(Color.WHITE);
            } else {
                tgd.setColor(Color.parseColor("#2C313A"));
                tgd.setStroke(dp(1), Color.parseColor("#484F58"));
                topTranslateBtn.setTextColor(Color.parseColor("#E6EDF3"));
            }
            topTranslateBtn.setBackground(tgd);
        }
    }

    private void showTranslationControlDialog() {
        if (book == null) return;
        final boolean isPdf = (parsedBook != null && parsedBook.isPdf);
        final int curMode = settings.getBookTranslationMode(book.id);
        final int curEngine = settings.getTranslationEngine();

        final AlertDialog dialog = new AlertDialog.Builder(this).create();

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(16));
        scroll.addView(root);

        // Header Title + Current Engine Badge
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, 0, 0, dp(12));

        TextView titleTv = new TextView(this);
        titleTv.setText("🌐 外语书籍翻译");
        titleTv.setTypeface(Typeface.DEFAULT_BOLD);
        titleTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f);
        titleTv.setTextColor(Color.parseColor("#1F2328"));
        header.addView(titleTv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView engineBadge = new TextView(this);
        String badgeText = (curEngine == ReadingSettings.ENGINE_BUILTIN_DIRECT)
                ? ReadingSettings.getEngineShortName(curEngine)
                : (ReadingSettings.getEngineShortName(curEngine) + " · " + ReadingSettings.getTranslationToneShortName(settings.getTranslationTone()));
        engineBadge.setText(badgeText);
        engineBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        engineBadge.setTextColor(Color.parseColor("#1F6FEB"));
        engineBadge.setPadding(dp(8), dp(3), dp(8), dp(3));
        GradientDrawable badgeGd = new GradientDrawable();
        badgeGd.setColor(Color.parseColor("#E8F0FE"));
        badgeGd.setCornerRadius(dp(12));
        engineBadge.setBackground(badgeGd);
        header.addView(engineBadge);

        root.addView(header);

        // 5 Prominent Clickable Card Buttons with streamlined text & clear active visual states
        root.addView(createTransActionCard(
                "✨ 纯中文译文",
                isPdf ? "自动提取 PDF 文本并开启简体中文流式翻译阅读" : "全书替换为简体中文，排版自然 · 首屏秒出",
                curMode == ReadingSettings.TRANS_MODE_CHINESE_ONLY,
                v -> {
                    dialog.dismiss();
                    if (isPdf) {
                        applyTranslationModeToPdf(ReadingSettings.TRANS_MODE_CHINESE_ONLY);
                    } else {
                        settings.setBookTranslationMode(book.id, ReadingSettings.TRANS_MODE_CHINESE_ONLY);
                        updatePdfConvertButtonsState();
                        if (settings.getBrowseMode() == ReadingSettings.BROWSE_MODE_PAGED) {
                            pendingOffsetOrChar = pagedReaderView.getCurrentCharOffset();
                        }
                        setBarsVisible(false);
                        renderCurrentChapter(false);
                    }
                }
        ));

        root.addView(createTransActionCard(
                "📖 双语对照阅读",
                isPdf ? "自动提取 PDF 文本并开启段落双语对照翻译阅读" : "原文与中文译文逐段对照显示",
                curMode == ReadingSettings.TRANS_MODE_BILINGUAL,
                v -> {
                    dialog.dismiss();
                    if (isPdf) {
                        applyTranslationModeToPdf(ReadingSettings.TRANS_MODE_BILINGUAL);
                    } else {
                        settings.setBookTranslationMode(book.id, ReadingSettings.TRANS_MODE_BILINGUAL);
                        updatePdfConvertButtonsState();
                        if (settings.getBrowseMode() == ReadingSettings.BROWSE_MODE_PAGED) {
                            pendingOffsetOrChar = pagedReaderView.getCurrentCharOffset();
                        }
                        setBarsVisible(false);
                        renderCurrentChapter(false);
                    }
                }
        ));

        root.addView(createTransActionCard(
                "🔄 恢复外语原文",
                "关闭翻译，查看原版外语文本",
                curMode == ReadingSettings.TRANS_MODE_OFF,
                v -> {
                    dialog.dismiss();
                    settings.setBookTranslationMode(book.id, ReadingSettings.TRANS_MODE_OFF);
                    updatePdfConvertButtonsState();
                    if (settings.getBrowseMode() == ReadingSettings.BROWSE_MODE_PAGED) {
                        pendingOffsetOrChar = pagedReaderView.getCurrentCharOffset();
                    }
                    setBarsVisible(false);
                    renderCurrentChapter(false);
                    Toast.makeText(ReaderActivity.this, "📖 已恢复外语原文阅读模式", Toast.LENGTH_SHORT).show();
                }
        ));

        root.addView(createTransActionCard(
                "⚙️ 翻译引擎与 AI 模型设置",
                "切换直连神经翻译 / DeepSeek / 通义千问 / Gemini / OpenAI 及 4 种翻译语气风格",
                false,
                v -> {
                    dialog.dismiss();
                    showAiEngineConfigDialog();
                }
        ));

        root.addView(createTransActionCard(
                "🧹 重新翻译本章",
                "清除当前章节译文缓存，重新请求翻译",
                false,
                v -> {
                    dialog.dismiss();
                    if (parsedBook != null && !parsedBook.chapters.isEmpty()) {
                        ParsedBook.Chapter ch = parsedBook.chapters.get(currentChapterIndex);
                        BookTranslationEngine.clearChapterTranslationCache(ReaderActivity.this, book.id, currentChapterIndex, ch.getContent());
                        if (settings.getBookTranslationMode(book.id) == ReadingSettings.TRANS_MODE_OFF) {
                            settings.setBookTranslationMode(book.id, ReadingSettings.TRANS_MODE_CHINESE_ONLY);
                        }
                        updatePdfConvertButtonsState();
                        setBarsVisible(false);
                        renderCurrentChapter(false);
                    }
                }
        ));

        dialog.setView(scroll);
        dialog.show();
    }

    private View createTransActionCard(String title, String subtitle, boolean isActive, View.OnClickListener clickListener) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setClickable(true);
        card.setFocusable(true);
        card.setPadding(dp(16), dp(12), dp(16), dp(12));

        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.bottomMargin = dp(10);
        card.setLayoutParams(cardLp);

        GradientDrawable gd = new GradientDrawable();
        gd.setCornerRadius(dp(10));
        if (isActive) {
            gd.setColor(Color.parseColor("#EBF3FE"));
            gd.setStroke(dp(2), Color.parseColor("#1F6FEB"));
        } else {
            gd.setColor(Color.parseColor("#F6F8FA"));
            gd.setStroke(dp(1), Color.parseColor("#D0D7DE"));
        }
        card.setBackground(gd);

        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f);
        titleView.setTextColor(isActive ? Color.parseColor("#0969DA") : Color.parseColor("#24292F"));
        topRow.addView(titleView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (isActive) {
            TextView activeBadge = new TextView(this);
            activeBadge.setText("当前生效 ✓");
            activeBadge.setTypeface(Typeface.DEFAULT_BOLD);
            activeBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
            activeBadge.setTextColor(Color.WHITE);
            activeBadge.setPadding(dp(7), dp(2), dp(7), dp(2));
            GradientDrawable agd = new GradientDrawable();
            agd.setColor(Color.parseColor("#1F6FEB"));
            agd.setCornerRadius(dp(10));
            activeBadge.setBackground(agd);
            topRow.addView(activeBadge);
        }
        card.addView(topRow);

        if (subtitle != null && !subtitle.isEmpty()) {
            TextView subView = new TextView(this);
            subView.setText(subtitle);
            subView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
            subView.setTextColor(isActive ? Color.parseColor("#57606A") : Color.parseColor("#656D76"));
            subView.setPadding(0, dp(4), 0, 0);
            card.addView(subView);
        }

        card.setOnClickListener(clickListener);
        return card;
    }

    private void showAiEngineConfigDialog() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(16), dp(20), dp(16));
        scroll.addView(box);

        // Header Title
        TextView titleTv = new TextView(this);
        titleTv.setText("⚙️ 翻译引擎与 AI 模型设置");
        titleTv.setTypeface(Typeface.DEFAULT_BOLD);
        titleTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16.5f);
        titleTv.setTextColor(Color.parseColor("#1F2328"));
        titleTv.setPadding(0, 0, 0, dp(14));
        box.addView(titleTv);

        // 1. Engine Selector
        TextView engineLabel = new TextView(this);
        engineLabel.setText("翻译引擎：");
        engineLabel.setTypeface(Typeface.DEFAULT_BOLD);
        engineLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        engineLabel.setTextColor(Color.parseColor("#24292F"));
        box.addView(engineLabel);

        final String[] engineNames = new String[5];
        for (int i = 0; i < 5; i++) {
            engineNames[i] = ReadingSettings.getEngineDisplayName(i);
        }
        final Spinner engineSpinner = new Spinner(this);
        ArrayAdapter<String> engAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, engineNames);
        engineSpinner.setAdapter(engAdapter);
        int curEng = settings.getTranslationEngine();
        if (curEng > 4) curEng = ReadingSettings.ENGINE_BUILTIN_DIRECT;
        engineSpinner.setSelection(curEng, false);
        box.addView(engineSpinner);

        final TextView builtinNotice = new TextView(this);
        builtinNotice.setText("✅ 当前已启用免配置神经网络翻译，免填 Key，即点即译！");
        builtinNotice.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        builtinNotice.setTextColor(Color.parseColor("#1A7F37"));
        builtinNotice.setPadding(dp(12), dp(10), dp(12), dp(10));
        GradientDrawable bGd = new GradientDrawable();
        bGd.setColor(Color.parseColor("#EBF9ED"));
        bGd.setCornerRadius(dp(8));
        builtinNotice.setBackground(bGd);
        LinearLayout.LayoutParams bnLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bnLp.topMargin = dp(10);
        box.addView(builtinNotice, bnLp);

        // 2. AI Large Model Fields Container
        final LinearLayout aiFieldsContainer = new LinearLayout(this);
        aiFieldsContainer.setOrientation(LinearLayout.VERTICAL);
        aiFieldsContainer.setPadding(0, dp(8), 0, 0);
        box.addView(aiFieldsContainer);

        // Model Dropdown Selector
        TextView modelLabel = new TextView(this);
        modelLabel.setText("模型名称：");
        modelLabel.setTypeface(Typeface.DEFAULT_BOLD);
        modelLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        modelLabel.setTextColor(Color.parseColor("#24292F"));
        modelLabel.setPadding(0, dp(6), 0, 0);
        aiFieldsContainer.addView(modelLabel);

        final Spinner modelSpinner = new Spinner(this);
        aiFieldsContainer.addView(modelSpinner);

        // API Key Input
        TextView keyLabel = new TextView(this);
        keyLabel.setText("API 密钥 (API Key)：");
        keyLabel.setTypeface(Typeface.DEFAULT_BOLD);
        keyLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        keyLabel.setTextColor(Color.parseColor("#24292F"));
        keyLabel.setPadding(0, dp(10), 0, 0);
        aiFieldsContainer.addView(keyLabel);

        final EditText apiKeyInput = new EditText(this);
        apiKeyInput.setHint("输入 API Key (如 sk-...)");
        apiKeyInput.setSingleLine(true);
        apiKeyInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        aiFieldsContainer.addView(apiKeyInput);

        // Base URL Input
        TextView urlLabel = new TextView(this);
        urlLabel.setText("接口地址 (可选官方或免翻墙国内中转反代)：");
        urlLabel.setTypeface(Typeface.DEFAULT_BOLD);
        urlLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        urlLabel.setTextColor(Color.parseColor("#24292F"));
        urlLabel.setPadding(0, dp(10), 0, 0);
        aiFieldsContainer.addView(urlLabel);

        final Spinner baseUrlSpinner = new Spinner(this);
        aiFieldsContainer.addView(baseUrlSpinner);

        final EditText baseUrlInput = new EditText(this);
        baseUrlInput.setHint("接口地址 (Base URL)");
        baseUrlInput.setSingleLine(true);
        aiFieldsContainer.addView(baseUrlInput);

        // 3. Translation Tone / Style Selector (Placed at the very bottom!)
        TextView toneLabel = new TextView(this);
        toneLabel.setText("翻译语气 / 风格：");
        toneLabel.setTypeface(Typeface.DEFAULT_BOLD);
        toneLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        toneLabel.setTextColor(Color.parseColor("#24292F"));
        toneLabel.setPadding(0, dp(14), 0, 0);
        box.addView(toneLabel);

        final String[] toneNames = new String[]{
                ReadingSettings.getTranslationToneDisplayName(0),
                ReadingSettings.getTranslationToneDisplayName(1),
                ReadingSettings.getTranslationToneDisplayName(2),
                ReadingSettings.getTranslationToneDisplayName(3)
        };
        final Spinner toneSpinner = new Spinner(this);
        ArrayAdapter<String> toneAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, toneNames);
        toneSpinner.setAdapter(toneAdapter);
        toneSpinner.setSelection(settings.getTranslationTone(), false);
        box.addView(toneSpinner);

        final int[] currentEditingEngine = new int[]{settings.getTranslationEngine()};

        final Runnable refreshBaseUrlSpinnerOptions = () -> {
            int eng = currentEditingEngine[0];
            String[] urlPresets = ReadingSettings.getPresetBaseUrlsForEngine(eng);
            ArrayAdapter<String> uAdapter = new ArrayAdapter<>(ReaderActivity.this, android.R.layout.simple_spinner_dropdown_item, urlPresets);
            baseUrlSpinner.setAdapter(uAdapter);

            String savedUrl = settings.getAiBaseUrl(eng);
            int matchIdx = -1;
            for (int i = 0; i < urlPresets.length; i++) {
                if (urlPresets[i].equals(savedUrl) || urlPresets[i].startsWith(savedUrl + " ")) {
                    matchIdx = i;
                    break;
                }
            }
            if (matchIdx >= 0) {
                baseUrlSpinner.setSelection(matchIdx, false);
                baseUrlInput.setText(savedUrl);
            } else {
                int customIdx = urlPresets.length - 1; // "自定义接口地址..."
                baseUrlSpinner.setSelection(customIdx, false);
                baseUrlInput.setText(savedUrl);
            }
        };

        baseUrlSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String selected = (String) parent.getItemAtPosition(position);
                if (selected != null && !"自定义接口地址...".equals(selected)) {
                    String cleanUrl = selected.contains(" ") ? selected.split(" ")[0].trim() : selected.trim();
                    baseUrlInput.setText(cleanUrl);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        final Runnable refreshModelSpinnerOptions = () -> {
            int eng = currentEditingEngine[0];
            String[] presets = ReadingSettings.getPresetModelsForEngine(eng);
            ArrayAdapter<String> mAdapter = new ArrayAdapter<>(ReaderActivity.this, android.R.layout.simple_spinner_dropdown_item, presets);
            modelSpinner.setAdapter(mAdapter);

            String savedModel = settings.getAiModelName(eng);
            int matchIdx = 0;
            for (int i = 0; i < presets.length; i++) {
                if (presets[i].equals(savedModel) || presets[i].startsWith(savedModel + " ")) {
                    matchIdx = i;
                    break;
                }
            }
            modelSpinner.setSelection(matchIdx, false);
            refreshBaseUrlSpinnerOptions.run();
        };

        final Runnable loadEngineFields = () -> {
            int eng = currentEditingEngine[0];
            if (eng == ReadingSettings.ENGINE_BUILTIN_DIRECT) {
                aiFieldsContainer.setVisibility(View.GONE);
                builtinNotice.setVisibility(View.VISIBLE);
            } else {
                aiFieldsContainer.setVisibility(View.VISIBLE);
                builtinNotice.setVisibility(View.GONE);
                apiKeyInput.setText(settings.getAiApiKey(eng));
                baseUrlInput.setText(settings.getAiBaseUrl(eng));
                refreshModelSpinnerOptions.run();
            }
        };

        engineSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                int prev = currentEditingEngine[0];
                if (prev != ReadingSettings.ENGINE_BUILTIN_DIRECT) {
                    settings.setAiApiKey(prev, apiKeyInput.getText().toString());
                    settings.setAiBaseUrl(prev, baseUrlInput.getText().toString());
                    Object selModel = modelSpinner.getSelectedItem();
                    if (selModel != null) {
                        settings.setAiModelName(prev, selModel.toString().trim());
                    }
                }
                currentEditingEngine[0] = position;
                loadEngineFields.run();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        loadEngineFields.run();

        new AlertDialog.Builder(this)
                .setView(scroll)
                .setPositiveButton("保存并启用", (d, w) -> {
                    int sel = engineSpinner.getSelectedItemPosition();
                    settings.setTranslationEngine(sel);
                    int selTone = toneSpinner.getSelectedItemPosition();
                    settings.setTranslationTone(selTone);
                    if (sel != ReadingSettings.ENGINE_BUILTIN_DIRECT) {
                        settings.setAiApiKey(sel, apiKeyInput.getText().toString());
                        settings.setAiBaseUrl(sel, baseUrlInput.getText().toString());
                        Object selModel = modelSpinner.getSelectedItem();
                        String chosenModel = (selModel != null) ? selModel.toString().trim() : ReadingSettings.getDefaultAiModelName(sel);
                        settings.setAiModelName(sel, chosenModel);
                    }
                    if (book != null && settings.getBookTranslationMode(book.id) == ReadingSettings.TRANS_MODE_OFF) {
                        settings.setBookTranslationMode(book.id, ReadingSettings.TRANS_MODE_CHINESE_ONLY);
                    }
                    // Clear chapter translation cache so new model and tone prompts take effect immediately
                    if (parsedBook != null && !parsedBook.chapters.isEmpty()) {
                        ParsedBook.Chapter ch = parsedBook.chapters.get(currentChapterIndex);
                        BookTranslationEngine.clearChapterTranslationCache(ReaderActivity.this, book.id, currentChapterIndex, ch.getContent());
                    }
                    updatePdfConvertButtonsState();
                    setBarsVisible(false);
                    if (parsedBook != null && parsedBook.isPdf) {
                        int currentTransMode = settings.getBookTranslationMode(book.id);
                        if (currentTransMode == ReadingSettings.TRANS_MODE_OFF) {
                            currentTransMode = ReadingSettings.TRANS_MODE_CHINESE_ONLY;
                        }
                        applyTranslationModeToPdf(currentTransMode);
                    } else {
                        renderCurrentChapter(false);
                    }
                    Toast.makeText(ReaderActivity.this,
                            "✅ 已启用「" + ReadingSettings.getEngineShortName(sel) + " · " + ReadingSettings.getTranslationToneShortName(selTone) + "」正在翻译当前章节…",
                            Toast.LENGTH_SHORT).show();
                })
                .setNeutralButton("⚡ 测试连接", (d, w) -> {
                    int sel = engineSpinner.getSelectedItemPosition();
                    settings.setTranslationEngine(sel);
                    int selTone = toneSpinner.getSelectedItemPosition();
                    settings.setTranslationTone(selTone);
                    if (sel != ReadingSettings.ENGINE_BUILTIN_DIRECT) {
                        settings.setAiApiKey(sel, apiKeyInput.getText().toString());
                        settings.setAiBaseUrl(sel, baseUrlInput.getText().toString());
                        Object selModel = modelSpinner.getSelectedItem();
                        String chosenModel = (selModel != null) ? selModel.toString().trim() : ReadingSettings.getDefaultAiModelName(sel);
                        settings.setAiModelName(sel, chosenModel);
                    }
                    Toast.makeText(ReaderActivity.this, "正在测试「" + ReadingSettings.getEngineShortName(sel) + " · " + ReadingSettings.getTranslationToneShortName(selTone) + "」连接…", Toast.LENGTH_SHORT).show();
                    new Thread(() -> {
                        try {
                            final String testResult = BookTranslationEngine.testTranslationConnection(settings);
                            mainHandler.post(() -> new AlertDialog.Builder(ReaderActivity.this)
                                    .setTitle("✅ 连接成功 · " + ReadingSettings.getEngineShortName(sel))
                                    .setMessage("测试原文：\nThe art of reading is to skip judiciously and savour the finest passages of human thought.\n\n测试译文：\n" + testResult)
                                    .setPositiveButton("太棒了", null)
                                    .show());
                        } catch (final Exception ex) {
                            final String rawMsg = ex.getMessage() != null ? ex.getMessage() : "连接超时或网络异常";
                            final StringBuilder sb = new StringBuilder();
                            boolean is429 = rawMsg.contains("429") || rawMsg.toLowerCase().contains("quota") || rawMsg.toLowerCase().contains("rate");
                            if (is429) {
                                sb.append("🎉 网络与反代已100%连通Google官方！\n\n");
                                sb.append("⚠️ 当前提示：触发了Google官方免费Key临时频次限额 (HTTP 429 Quota Exceeded)\n");
                                sb.append("• 限制原因：Google免费Key每分钟/短时间限额20次请求，翻页或测试过快时会触发。\n");
                                sb.append("• 恢复时间：Google官方提示需冷却约10-12分钟后自动恢复。\n\n");
                                sb.append("💡 建议：看书时遇到429限流，LiteReader会自动无缝接力翻译，阅读绝不留白。您也可切换至DeepSeek或Qwen无频次限制。");
                            } else {
                                sb.append("错误信息：\n").append(rawMsg).append("\n\n");
                                sb.append("💡 提示：请确认API Key是否正确；若使用自建反代(gemini.aivoice.top)可直接免翻墙连通。");
                            }
                            mainHandler.post(() -> new AlertDialog.Builder(ReaderActivity.this)
                                    .setTitle(is429 ? ("⚠️ 免费额度暂时受限 (HTTP 429) · " + ReadingSettings.getEngineShortName(sel)) : ("⚠️ 连接测试未通过 · " + ReadingSettings.getEngineShortName(sel)))
                                    .setMessage(sb.toString())
                                    .setPositiveButton("返回修改", (d2, w2) -> showAiEngineConfigDialog())
                                    .setNegativeButton("我知道了", null)
                                    .show());
                        }
                    }).start();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void togglePdfReflowConversion() {
        if (book == null) return;
        boolean nextReflow = !settings.isPdfReflowEnabledForBook(book.id);
        settings.setPdfReflowEnabledForBook(book.id, nextReflow);
        updatePdfConvertButtonsState();
        renderCurrentChapter(false);
        Toast.makeText(this,
                nextReflow
                        ? "✨ 已开启 PDF 重排模式：插图原色显示，支持全部背景色、字体、字号、间距与两端对齐！"
                        : "📄 已切换为 PDF 原版全屏去白边模式：支持双指捏合放大！",
                Toast.LENGTH_SHORT).show();
    }

    private void showPdfConversionDialog() {
        if (book == null) return;
        boolean isReflow = settings.isPdfReflowEnabledForBook(book.id);
        String[] options = new String[]{
                (isReflow ? "✅ " : "✨ ") + "一键转换为「全屏智能文字重排模式」（图片原色正常显示，支持切换字体、背景色、字号、行距、段间距、字间距与两端对齐）",
                (!isReflow ? "✅ " : "📄 ") + "切换为「原版全屏去白边模式」（自动裁除四周白边铺满屏幕，图片原色显示，支持双指捏合放大）",
                "💾 提取 PDF 文本层并另存为独立 TXT 流式电子书（存入专有加密库）"
        };

        new AlertDialog.Builder(this)
                .setTitle("🔄 PDF 一键转换与全屏设置")
                .setItems(options, (d, which) -> {
                    if (which == 0) {
                        settings.setPdfReflowEnabledForBook(book.id, true);
                        updatePdfConvertButtonsState();
                        setBarsVisible(false);
                        renderCurrentChapter(false);
                    } else if (which == 1) {
                        settings.setPdfReflowEnabledForBook(book.id, false);
                        updatePdfConvertButtonsState();
                        setBarsVisible(false);
                        renderCurrentChapter(false);
                    } else if (which == 2) {
                        convertAndSavePdfToVaultAsTextBook();
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private void applyTranslationModeToPdf(final int targetTransMode) {
        if (book == null || decryptedSessionFile == null || !decryptedSessionFile.exists()) {
            Toast.makeText(this, "正在准备 PDF 源文件，请稍候重试", Toast.LENGTH_SHORT).show();
            return;
        }
        settings.setBookTranslationMode(book.id, targetTransMode);
        updatePdfConvertButtonsState();
        Toast.makeText(this, "🔄 正在提取 PDF 文本并开启「" + ReadingSettings.getEngineShortName(settings.getTranslationEngine()) + "」流式翻译阅读…", Toast.LENGTH_SHORT).show();
        executor.execute(() -> {
            List<ParsedBook.Chapter> textChapters = PdfConverterEngine.extractTextChaptersFromPdf(decryptedSessionFile);
            if (textChapters.isEmpty()) {
                mainHandler.post(() -> {
                    settings.setPdfReflowEnabledForBook(book.id, true);
                    updatePdfConvertButtonsState();
                    renderCurrentChapter(false);
                    Toast.makeText(ReaderActivity.this,
                            "该 PDF 为特殊或扫描字库，已为您开启「智能排版文字重排阅读」",
                            Toast.LENGTH_LONG).show();
                });
                return;
            }

            try {
                boolean convSimp = isTradToSimpActive();
                StringBuilder fullTxt = new StringBuilder();
                for (ParsedBook.Chapter ch : textChapters) {
                    String t = convSimp ? ChineseTradToSimpConverter.convertToSimplified(ch.title) : ch.title;
                    String c = convSimp ? ChineseTradToSimpConverter.convertToSimplified(ch.getContent()) : ch.getContent();
                                                            fullTxt.append(t).append("\n\n").append(c).append("\n\n");
                }
                byte[] txtBytes = fullTxt.toString().getBytes(StandardCharsets.UTF_8);
                String rawBookTitle = convSimp ? ChineseTradToSimpConverter.convertToSimplified(book.title) : book.title;
                String suffix = (targetTransMode == ReadingSettings.TRANS_MODE_BILINGUAL) ? " (双语流式译本)" : " (中文流式译本)";
                String newTitle = rawBookTitle + suffix;
                final Book newBook = db.importStreamToEncryptedVault(
                        new ByteArrayInputStream(txtBytes),
                        newTitle + ".txt",
                        newTitle,
                        book.author,
                        book.folderName
                );
                mainHandler.post(() -> {
                    if (newBook != null) {
                        settings.setBookTranslationMode(newBook.id, targetTransMode);
                        book = newBook;
                        updatePdfConvertButtonsState();
                        loadAndParseBook();
                        Toast.makeText(ReaderActivity.this,
                                "✅ 已生成流式电子书，正在开启「" + (targetTransMode == ReadingSettings.TRANS_MODE_BILINGUAL ? "双语对照" : "纯中文译文") + "」翻译！",
                                Toast.LENGTH_LONG).show();
                    }
                });
            } catch (final Exception e) {
                mainHandler.post(() -> Toast.makeText(ReaderActivity.this,
                        "提取转换失败: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    private void convertAndSavePdfToVaultAsTextBook() {
        if (decryptedSessionFile == null || !decryptedSessionFile.exists()) {
            Toast.makeText(this, "正在准备 PDF 源文件，请稍候重试", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, "🔄 正在提取 PDF 文本流并转换加密入库…", Toast.LENGTH_SHORT).show();
        executor.execute(() -> {
            List<ParsedBook.Chapter> textChapters = PdfConverterEngine.extractTextChaptersFromPdf(decryptedSessionFile);
            if (textChapters.isEmpty()) {
                mainHandler.post(() -> {
                    settings.setPdfReflowEnabledForBook(book.id, true);
                    updatePdfConvertButtonsState();
                    renderCurrentChapter(false);
                    Toast.makeText(ReaderActivity.this,
                            "该 PDF 为扫描或特殊字库，已自动开启「✨ 全屏智能文字重排模式」，支持全部排版与字体调节！",
                            Toast.LENGTH_LONG).show();
                });
                return;
            }

            try {
                boolean convSimp = isTradToSimpActive();
                StringBuilder fullTxt = new StringBuilder();
                for (ParsedBook.Chapter ch : textChapters) {
                    String t = convSimp ? ChineseTradToSimpConverter.convertToSimplified(ch.title) : ch.title;
                    String c = convSimp ? ChineseTradToSimpConverter.convertToSimplified(ch.getContent()) : ch.getContent();
                                                            fullTxt.append(t).append("\n\n").append(c).append("\n\n");
                }
                byte[] txtBytes = fullTxt.toString().getBytes(StandardCharsets.UTF_8);
                String rawBookTitle = convSimp ? ChineseTradToSimpConverter.convertToSimplified(book.title) : book.title;
                String newTitle = rawBookTitle + (convSimp ? " (简体流式版)" : " (转换流式版)");
                final Book newBook = db.importStreamToEncryptedVault(
                        new ByteArrayInputStream(txtBytes),
                        newTitle + ".txt",
                        newTitle,
                        book.author,
                        book.folderName
                );
                mainHandler.post(() -> {
                    if (newBook != null) {
                        Toast.makeText(ReaderActivity.this,
                                "✅ 已成功转换并加密保存《" + newBook.title + "》，正在打开流式版！",
                                Toast.LENGTH_LONG).show();
                        book = newBook;
                        loadAndParseBook();
                    }
                });
            } catch (final Exception e) {
                mainHandler.post(() -> Toast.makeText(ReaderActivity.this,
                        "转换保存失败: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    private boolean handleScrollTouchGesture(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            scrollTouchDownX = event.getX();
            scrollTouchDownY = event.getY();
        } else if (action == MotionEvent.ACTION_UP) {
            float dx = Math.abs(event.getX() - scrollTouchDownX);
            float dy = Math.abs(event.getY() - scrollTouchDownY);
            if (dx <= touchSlop && dy <= touchSlop) {
                if (barsVisible) {
                    setBarsVisible(false);
                    return true;
                }
                int w = Math.max(1, textScrollView.getWidth());
                float x = event.getX();
                if (x < w / 3.0f) {
                    handlePrevPageAction();
                } else if (x > (w * 2.0f) / 3.0f) {
                    handleNextPageAction();
                } else {
                    setBarsVisible(true);
                }
                return true;
            }
        }
        return false;
    }

    private void handlePrevPageAction() {
        if (barsVisible) {
            setBarsVisible(false);
        }
        if (parsedBook == null || parsedBook.chapters.isEmpty()) return;

        if (settings.getBrowseMode() == ReadingSettings.BROWSE_MODE_PAGED) {
            if (pagedReaderView.hasPrevPageInChapter()) {
                pagedReaderView.turnToPrevPageInChapter();
                updateProgressBarLabel();
                persistCurrentProgress();
            } else if (currentChapterIndex > 0) {
                pagedReaderView.playExternalPageTransition(-1, () -> {
                    currentChapterIndex--;
                    pendingOffsetOrChar = 0;
                    renderCurrentChapter(true);
                });
            } else {
                Toast.makeText(this, "已经是全书第一页", Toast.LENGTH_SHORT).show();
            }
        } else {
            int scrollY = textScrollView.getScrollY();
            int step = (int) (textScrollView.getHeight() * 0.86f);
            if (scrollY > dp(8)) {
                int targetY = Math.max(0, scrollY - step);
                if (settings.getPageTurnAnim() == ReadingSettings.ANIM_NONE) {
                    textScrollView.scrollTo(0, targetY);
                } else {
                    textScrollView.smoothScrollTo(0, targetY);
                }
                updateProgressBarLabel();
                persistCurrentProgress();
            } else {
                navigateChapter(-1, true);
            }
        }
    }

    private void handleNextPageAction() {
        if (barsVisible) {
            setBarsVisible(false);
        }
        if (parsedBook == null || parsedBook.chapters.isEmpty()) return;

        if (settings.getBrowseMode() == ReadingSettings.BROWSE_MODE_PAGED) {
            if (pagedReaderView.hasNextPageInChapter()) {
                pagedReaderView.turnToNextPageInChapter();
                updateProgressBarLabel();
                persistCurrentProgress();
            } else if (currentChapterIndex + 1 < parsedBook.chapters.size()) {
                pagedReaderView.playExternalPageTransition(1, () -> {
                    currentChapterIndex++;
                    pendingOffsetOrChar = 0;
                    renderCurrentChapter(false);
                });
            } else {
                Toast.makeText(this, "已读完全书最后一页 🎉", Toast.LENGTH_SHORT).show();
            }
        } else {
            int scrollY = textScrollView.getScrollY();
            int viewH = textScrollView.getHeight();
            int contentH = pageContentBox.getHeight();
            int maxScroll = Math.max(0, contentH - viewH);
            int step = (int) (viewH * 0.86f);
            if (scrollY + dp(12) < maxScroll) {
                int targetY = Math.min(maxScroll, scrollY + step);
                if (settings.getPageTurnAnim() == ReadingSettings.ANIM_NONE) {
                    textScrollView.scrollTo(0, targetY);
                } else {
                    textScrollView.smoothScrollTo(0, targetY);
                }
                updateProgressBarLabel();
                persistCurrentProgress();
            } else {
                navigateChapter(1, false);
            }
        }
    }

    private void updateSettingsHighlights() {
        for (int i = 0; i < browseModeButtons.size(); i++) {
            styleSelectablePill(browseModeButtons.get(i), settings.getBrowseMode() == i);
        }
        for (int i = 0; i < animModeButtons.size(); i++) {
            styleSelectablePill(animModeButtons.get(i), settings.getPageTurnAnim() == i);
        }
        for (int i = 0; i < fontFamilyButtons.size(); i++) {
            styleSelectablePill(fontFamilyButtons.get(i), settings.getFontFamily() == i);
        }
        updatePdfConvertButtonsState();
    }

    private void applyScrollBoxPadding() {
        if (pageContentBox == null) return;
        Configuration cfg = getResources().getConfiguration();
        int baseMarginDp = settings.getMarginDp();
        int horizontalMarginDp = baseMarginDp;
        if (cfg.screenWidthDp >= 840) {
            horizontalMarginDp = baseMarginDp + 48;
        } else if (cfg.orientation == Configuration.ORIENTATION_LANDSCAPE || cfg.screenWidthDp >= 560) {
            horizontalMarginDp = baseMarginDp + 20;
        }
        int hMarginPx = dp(horizontalMarginDp);
        boolean isPdfBook = (parsedBook != null && parsedBook.isPdf) || (book != null && "PDF".equalsIgnoreCase(book.format));
        boolean isLandscape = cfg.orientation == Configuration.ORIENTATION_LANDSCAPE;
        int topPad = Math.max(isLandscape ? dp(18) : dp(42), topCameraSafeOffsetPx);
        if (isPdfBook) {
            pageContentBox.setPadding(0, topPad, 0, dp(34));
        } else {
            pageContentBox.setPadding(hMarginPx, topPad, hMarginPx, dp(36));
        }
    }

    private void applyVisualSettings() {
        int theme = settings.getTheme();
        int bg = ReadingSettings.getBackgroundColor(theme);
        int fg = ReadingSettings.getTextColor(theme);
        int secFg = ReadingSettings.getSecondaryTextColor(theme);
        int barBg = ReadingSettings.getToolbarColor(theme);

        try {
            Window window = getWindow();
            if (window != null) {
                window.getDecorView().setBackgroundColor(bg);
                window.setStatusBarColor(bg);
                window.setNavigationBarColor(bg);
            }
        } catch (Exception ignored) {
        }

        rootFrame.setBackgroundColor(bg);
        textScrollView.setBackgroundColor(bg);
        pageContentBox.setBackgroundColor(bg);
        scrollModeBottomFooter.setBackgroundColor(bg);
        topBar.setBackgroundColor(barBg);
        bottomBar.setBackgroundColor(barBg);

        topTitleView.setTextColor(fg);
        bottomProgressLabel.setTextColor(fg);
        bodyTextView.setTextColor(fg);
        scrollFooterLeftChapter.setTextColor(secFg);
        scrollFooterRightProgress.setTextColor(secFg);

        Typeface tf = ReadingSettings.getTypeface(settings.getFontFamily());
        bodyTextView.setTypeface(tf, Typeface.NORMAL);
        scrollFooterLeftChapter.setTypeface(tf, Typeface.NORMAL);
        scrollFooterRightProgress.setTypeface(tf, Typeface.NORMAL);

        float sizeSp = settings.getFontSizeSp();
        bodyTextView.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        bodyTextView.setLineSpacing(0f, settings.getLineSpacingMultiplier());
        bodyTextView.setLetterSpacing(settings.getLetterSpacingEm());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            bodyTextView.setBreakStrategy(android.graphics.text.LineBreaker.BREAK_STRATEGY_HIGH_QUALITY);
            bodyTextView.setJustificationMode(settings.isJustifyAlign()
                    ? android.graphics.text.LineBreaker.JUSTIFICATION_MODE_INTER_WORD
                    : android.graphics.text.LineBreaker.JUSTIFICATION_MODE_NONE);
        }

        Configuration cfg = getResources().getConfiguration();
        int baseMarginDp = settings.getMarginDp();
        int horizontalMarginDp = baseMarginDp;
        if (cfg.screenWidthDp >= 840) {
            horizontalMarginDp = baseMarginDp + 48;
        } else if (cfg.orientation == Configuration.ORIENTATION_LANDSCAPE || cfg.screenWidthDp >= 560) {
            horizontalMarginDp = baseMarginDp + 20;
        }
        int hMarginPx = dp(horizontalMarginDp);
        applyScrollBoxPadding();

        pagedReaderView.applyStyle(
                bg, fg, secFg, tf,
                sizeSp,
                settings.getLineSpacingMultiplier(),
                settings.getParagraphSpacingEm(),
                settings.getLetterSpacingEm(),
                settings.isFirstLineIndent(),
                settings.isJustifyAlign(),
                hMarginPx,
                settings.getPageTurnAnim()
        );

        boolean isPaged = (settings.getBrowseMode() == ReadingSettings.BROWSE_MODE_PAGED);
        pagedReaderView.setVisibility(isPaged ? View.VISIBLE : View.GONE);
        textScrollView.setVisibility(isPaged ? View.GONE : View.VISIBLE);
        scrollModeBottomFooter.setVisibility(isPaged ? View.GONE : View.VISIBLE);
    }

    private void loadAndParseBook() {
        try {
            // Fast 0ms synchronous check: if the decrypted file & in-memory ParsedBook already exist, render immediately!
            File rawVaultFile = new File(book.filePath);
            String ext = (book.subExtension != null && !book.subExtension.isEmpty())
                    ? book.subExtension
                    : ("." + book.format.toLowerCase(Locale.ROOT));
            if (rawVaultFile.exists()) {
                File sessionDir = new File(getApplicationContext().getCacheDir(), ".reading_session");
                long expectedPlainLen = Math.max(0L, rawVaultFile.length() - 24L);
                File readyCandidate = BookVaultCrypto.isEncryptedVaultFile(rawVaultFile)
                        ? new File(sessionDir, "ready_" + rawVaultFile.getName() + "_" + expectedPlainLen + ext)
                        : rawVaultFile;
                if (readyCandidate.exists()) {
                    ParsedBook memPb = BookParserEngine.getCachedParsedBookIfPresent(readyCandidate);
                    if (memPb != null && !memPb.isPdf && !memPb.chapters.isEmpty()) {
                        decryptedSessionFile = readyCandidate;
                        parsedBook = memPb;
                        topTitleView.setText(formatBookText(book.title));
                        topPdfConvertBtn.setVisibility(View.GONE);
                        updatePdfConvertButtonsState();
                        applyVisualSettings();
                        chapterSeekBar.setMax(Math.max(0, memPb.chapters.size() - 1));
                        if (currentChapterIndex < 0 || currentChapterIndex >= memPb.chapters.size()) {
                            currentChapterIndex = 0;
                        }
                        renderCurrentChapter(false);
                        return;
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        pagedReaderView.setStatusMessage("正在打开《" + book.title + "》…");
        bodyTextView.setText("正在打开《" + book.title + "》…");

        executor.execute(() -> {
            try {
                File rawVaultFile = new File(book.filePath);
                String ext = (book.subExtension != null && !book.subExtension.isEmpty())
                        ? book.subExtension
                        : ("." + book.format.toLowerCase(Locale.ROOT));
                final File f = BookVaultCrypto.decryptToTempReadingFile(ReaderActivity.this, rawVaultFile, ext);
                decryptedSessionFile = f;

                final ParsedBook pb = BookParserEngine.parseBook(f);
                // Preserve existing valid book title instead of using decrypted temp file name (`session_v_...`)
                if (com.litereader.app.parser.BookCoverExtractor.isUglyOrSyntheticTitle(pb.title)
                        && !com.litereader.app.parser.BookCoverExtractor.isUglyOrSyntheticTitle(book.title)) {
                    pb.title = book.title;
                } else if (!com.litereader.app.parser.BookCoverExtractor.isUglyOrSyntheticTitle(pb.title)
                        && com.litereader.app.parser.BookCoverExtractor.isUglyOrSyntheticTitle(book.title)) {
                    book.title = pb.title;
                    db.forceUpdateBookTitle(book.id, pb.title);
                } else if (!com.litereader.app.parser.BookCoverExtractor.isUglyOrSyntheticTitle(book.title)) {
                    pb.title = book.title;
                }

                if (pb.isPdf) {
                    if (pdfRenderer != null) {
                        try { pdfRenderer.close(); } catch (Exception ignored) {}
                    }
                    if (pdfDescriptor != null) {
                        try { pdfDescriptor.close(); } catch (Exception ignored) {}
                    }
                    pdfDescriptor = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
                    pdfRenderer = new PdfRenderer(pdfDescriptor);
                }
                db.updateBookMetadata(book.id, pb.title, pb.author, pb.chapters.size());
                mainHandler.post(() -> {
                    parsedBook = pb;
                    topTitleView.setText(formatBookText(pb.title));
                    topPdfConvertBtn.setVisibility(pb.isPdf ? View.VISIBLE : View.GONE);
                    updatePdfConvertButtonsState();
                    applyVisualSettings();
                    chapterSeekBar.setMax(Math.max(0, pb.chapters.size() - 1));
                    if (currentChapterIndex < 0 || currentChapterIndex >= pb.chapters.size()) {
                        currentChapterIndex = 0;
                    }
                    // Render the first page immediately (< 50ms)!
                    renderCurrentChapter(false);

                    // For PDF books whose Title TOC has not been extracted yet, populate TOC asynchronously in background
                    if (pb.isPdf && pb.tocEntries.isEmpty() && pdfRenderer != null) {
                        new Thread(() -> {
                            try {
                                PdfConverterEngine.populatePdfChaptersAndTitleToc(f, pdfRenderer, pb);
                                BookParserEngine.savePdfTocToCache(f, pb);
                                mainHandler.post(this::updateProgressBarLabel);
                            } catch (Throwable ignored) {
                            }
                        }).start();
                    }
                });
            } catch (final Throwable e) {
                final String errMsg = "打开书籍时出错: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
                mainHandler.post(() -> {
                    bodyTextView.setText(errMsg);
                    pagedReaderView.setStatusMessage(errMsg);
                });
            }
        });
    }

    private void renderCurrentChapter(final boolean jumpToLastPage) {
        if (parsedBook == null || parsedBook.chapters.isEmpty()) return;
        currentChapterIndex = Math.max(0, Math.min(parsedBook.chapters.size() - 1, currentChapterIndex));
        final ParsedBook.Chapter ch = parsedBook.chapters.get(currentChapterIndex);
        chapterSeekBar.setProgress(currentChapterIndex);

        if (parsedBook.isPdf && pdfRenderer != null) {
            bodyTextView.setVisibility(View.GONE);
            pdfScrollPagesContainer.setVisibility(View.VISIBLE);

            final int pageIdx = currentChapterIndex;
            final boolean isReflowMode = settings.isPdfReflowEnabledForBook(book.id);
            final float fontSizeSp = settings.getFontSizeSp();
            final float lineSpacing = settings.getLineSpacingMultiplier();
            final float paraSpacingEm = settings.getParagraphSpacingEm();
            final float letterSpacingEm = settings.getLetterSpacingEm();
            final boolean firstIndent = settings.isFirstLineIndent();
            final boolean justify = settings.isJustifyAlign();
            final int marginPx = dp(settings.getMarginDp());
            final int theme = settings.getTheme();
            final int bgColor = ReadingSettings.getBackgroundColor(theme);
            final int fgColor = ReadingSettings.getTextColor(theme);
            final int fontFamily = settings.getFontFamily();

            DisplayMetrics dm = getResources().getDisplayMetrics();
            final int viewW = pagedReaderView.getWidth() > 0 ? pagedReaderView.getWidth() : dm.widthPixels;
            int rawH = pagedReaderView.getHeight() > 0 ? pagedReaderView.getHeight() : dm.heightPixels;
            final int viewH = pagedReaderView.getAvailableContentHeightPx(rawH);

            executor.execute(() -> {
                final List<Bitmap> renderedSubPages = renderPdfPageBitmaps(
                        pageIdx, viewW, viewH, marginPx, fontSizeSp, lineSpacing,
                        paraSpacingEm, letterSpacingEm, firstIndent, justify,
                        isReflowMode, bgColor, fgColor, fontFamily);
                mainHandler.post(() -> {
                    pagedReaderView.setPdfPageBitmaps(
                            parsedBook.title,
                            pageIdx,
                            parsedBook.chapters.size(),
                            ch.title,
                            renderedSubPages,
                            isReflowMode,
                            jumpToLastPage
                    );

                    if (settings.getBrowseMode() == ReadingSettings.BROWSE_MODE_SCROLL) {
                        pdfScrollPagesContainer.removeAllViews();
                        for (Bitmap b : renderedSubPages) {
                            if (b == null || b.isRecycled()) continue;
                            ImageView iv = new ImageView(ReaderActivity.this);
                            iv.setAdjustViewBounds(true);
                            iv.setScaleType(ImageView.ScaleType.FIT_XY);
                            iv.setImageBitmap(b);
                            LinearLayout.LayoutParams ivLp = new LinearLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                            pdfScrollPagesContainer.addView(iv, ivLp);
                        }
                    }

                    textScrollView.post(() -> {
                        if (jumpToLastPage) {
                            int maxScroll = Math.max(0, pageContentBox.getHeight() - textScrollView.getHeight());
                            textScrollView.scrollTo(0, maxScroll);
                        } else {
                            textScrollView.scrollTo(0, 0);
                        }
                        updateProgressBarLabel();
                        persistCurrentProgress();
                    });
                });
            });
            return;
        }

        pdfScrollPagesContainer.setVisibility(View.GONE);
        bodyTextView.setVisibility(View.VISIBLE);

        final String rawChapterContent = ch.getContent();
        final int transMode = settings.getBookTranslationMode(book.id);
        final int transEngine = settings.getTranslationEngine();
        final String displayBookTitle = formatBookText(parsedBook.title);

        String initialChTitle = formatBookText(ch.title);
        String initialChContent = formatBookText(rawChapterContent);

        if (transMode != ReadingSettings.TRANS_MODE_OFF) {
            String cachedTrans = BookTranslationEngine.getCachedChapterTranslationIfPresent(
                    this, book.id, currentChapterIndex, transMode, transEngine, settings.getTranslationTone(), rawChapterContent);
            if (cachedTrans != null && !cachedTrans.isEmpty()) {
                initialChContent = formatBookText(cachedTrans);
            } else {
                final int requestedChIdx = currentChapterIndex;
                final int focusOffset = (pendingOffsetOrChar > 0)
                        ? pendingOffsetOrChar
                        : ((settings.getBrowseMode() == ReadingSettings.BROWSE_MODE_PAGED && pagedReaderView != null)
                            ? pagedReaderView.getCurrentCharOffset() : 0);
                BookTranslationEngine.translateChapterAsync(
                        this, settings, book.id, requestedChIdx, ch.title, rawChapterContent, transMode, focusOffset,
                        new BookTranslationEngine.TranslationUpdateCallback() {
                            @Override
                            public void onTranslationUpdated(final String trTitle, final String trContent, final boolean isComplete) {
                                mainHandler.post(() -> {
                                    if (isFinishing() || isDestroyed() || parsedBook == null) return;
                                    if (currentChapterIndex != requestedChIdx) return;
                                    if (settings.getBookTranslationMode(book.id) != transMode) return;

                                    int keepCharOffset = (settings.getBrowseMode() == ReadingSettings.BROWSE_MODE_PAGED)
                                            ? pagedReaderView.getCurrentCharOffset()
                                            : 0;
                                    String dispTitle = formatBookText(trTitle != null && !trTitle.isEmpty() ? trTitle : ch.title);
                                    String dispBody = formatBookText(trContent);
                                    pagedReaderView.setChapterContent(
                                            displayBookTitle,
                                            currentChapterIndex,
                                            parsedBook.chapters.size(),
                                            dispTitle,
                                            dispBody,
                                            keepCharOffset,
                                            false
                                    );
                                    if (settings.getBrowseMode() == ReadingSettings.BROWSE_MODE_SCROLL) {
                                        String fullDisplay = PagedReaderView.buildDisplayTextWithIndent(
                                                dispTitle, dispBody, settings.isFirstLineIndent());
                                        bodyTextView.setText(fullDisplay);
                                    }
                                    updateProgressBarLabel();
                                    if (isComplete) {
                                        // Pre-translate the next chapter in background so turning to the next chapter is 0ms!
                                        int nextIdx = requestedChIdx + 1;
                                        if (nextIdx >= 0 && nextIdx < parsedBook.chapters.size()) {
                                            ParsedBook.Chapter nextCh = parsedBook.chapters.get(nextIdx);
                                            BookTranslationEngine.translateChapterAsync(
                                                    ReaderActivity.this, settings, book.id, nextIdx,
                                                    nextCh.title, nextCh.getContent(), transMode, null);
                                        }
                                    }
                                });
                            }

                            @Override
                            public void onTranslationError(final String errorMessage) {
                                mainHandler.post(() -> {
                                    if (!isFinishing() && !isDestroyed()) {
                                        Toast.makeText(ReaderActivity.this,
                                                "翻译提示: " + errorMessage,
                                                Toast.LENGTH_LONG).show();
                                    }
                                });
                            }
                        });
            }
        }

        pagedReaderView.setChapterContent(
                displayBookTitle,
                currentChapterIndex,
                parsedBook.chapters.size(),
                initialChTitle,
                initialChContent,
                pendingOffsetOrChar,
                jumpToLastPage
        );

        // Only populate `bodyTextView` when vertical scroll mode is active (saves main-thread layout time in paged mode!)
        if (settings.getBrowseMode() == ReadingSettings.BROWSE_MODE_SCROLL) {
            String fullDisplay = PagedReaderView.buildDisplayTextWithIndent(
                    initialChTitle, initialChContent, settings.isFirstLineIndent());
            bodyTextView.setText(fullDisplay);
        }

        // Asynchronously pre-warm the adjacent next chapter raw text in memory (pre-translation runs after current chapter completes so 100% of bandwidth serves the current page first!)
        final int nextChIdx = currentChapterIndex + 1;
        if (nextChIdx >= 0 && nextChIdx < parsedBook.chapters.size()) {
            final ParsedBook.Chapter nextCh = parsedBook.chapters.get(nextChIdx);
            executor.execute(() -> {
                nextCh.getContent();
            });
        }

        final int targetOffset = pendingOffsetOrChar;
        pendingOffsetOrChar = 0;
        if (settings.getBrowseMode() == ReadingSettings.BROWSE_MODE_SCROLL) {
            textScrollView.post(() -> {
                if (jumpToLastPage) {
                    int maxScroll = Math.max(0, pageContentBox.getHeight() - textScrollView.getHeight());
                    textScrollView.scrollTo(0, maxScroll);
                } else {
                    textScrollView.scrollTo(0, Math.max(0, targetOffset));
                }
                updateProgressBarLabel();
                persistCurrentProgress();
            });
        } else {
            updateProgressBarLabel();
            persistCurrentProgress();
        }
    }

    private synchronized List<Bitmap> renderPdfPageBitmaps(
            int pageIndex, int viewW, int viewH, int marginPx,
            float fontSizeSp, float lineSpacing, float paraSpacingEm, float letterSpacingEm,
            boolean firstIndent, boolean justify, boolean isReflowMode,
            int bgColor, int fgColor, int fontFamily) {
        if (pdfRenderer == null || pageIndex < 0 || pageIndex >= pdfRenderer.getPageCount()) {
            return Collections.emptyList();
        }
        try {
            if (isReflowMode) {
                PdfConverterEngine.ReflowedPageResult res = PdfConverterEngine.renderReflowedPdfPage(
                        pdfRenderer, pageIndex, viewW, viewH, marginPx,
                        fontSizeSp, lineSpacing, paraSpacingEm, letterSpacingEm,
                        firstIndent, justify, bgColor, fgColor, fontFamily);
                return res.subPages;
            } else {
                Bitmap cropped = PdfConverterEngine.renderHighResPage(
                        pdfRenderer, pageIndex, viewW, settings.isPdfAutoCrop(), bgColor, fgColor);
                List<Bitmap> list = new ArrayList<>(1);
                if (cropped != null) list.add(cropped);
                return list;
            }
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private void navigateChapter(int delta, boolean jumpToLastPage) {
        if (parsedBook == null || parsedBook.chapters.isEmpty()) return;
        int next = currentChapterIndex + delta;
        if (next < 0) {
            Toast.makeText(this, "已经是第一页/章", Toast.LENGTH_SHORT).show();
            return;
        }
        if (next >= parsedBook.chapters.size()) {
            Toast.makeText(this, "已读完全书最后一页/章 🎉", Toast.LENGTH_SHORT).show();
            return;
        }
        currentChapterIndex = next;
        pendingOffsetOrChar = 0;
        renderCurrentChapter(jumpToLastPage);
    }

    private float computeOverallProgressPercent() {
        if (parsedBook == null || parsedBook.chapters.isEmpty()) return 0f;
        int totalCh = parsedBook.chapters.size();
        float intraChapter;
        if (settings.getBrowseMode() == ReadingSettings.BROWSE_MODE_PAGED) {
            intraChapter = pagedReaderView.getIntraChapterProgress();
        } else {
            int scrollY = textScrollView != null ? textScrollView.getScrollY() : 0;
            int contentH = pageContentBox != null ? pageContentBox.getHeight() : 1;
            int viewH = textScrollView != null ? textScrollView.getHeight() : 1;
            if (contentH > viewH) {
                intraChapter = Math.min(1f, Math.max(0f, (float) scrollY / (float) (contentH - viewH)));
            } else {
                intraChapter = 1f;
            }
        }
        float overall = ((currentChapterIndex + intraChapter) / (float) totalCh) * 100f;
        return Math.min(100f, Math.max(0f, overall));
    }

    private void updateProgressBarLabel() {
        if (parsedBook == null || bottomProgressLabel == null || parsedBook.chapters.isEmpty()) return;
        float pct = computeOverallProgressPercent();
        int idx = Math.max(0, Math.min(parsedBook.chapters.size() - 1, currentChapterIndex));
        String chTitle = formatBookText(parsedBook.chapters.get(idx).title);

        if (settings.getBrowseMode() == ReadingSettings.BROWSE_MODE_PAGED) {
            bottomProgressLabel.setText(String.format(Locale.US, "%d/%d页 · %.1f%%",
                    pagedReaderView.getCurrentPageIndex() + 1,
                    pagedReaderView.getPageCount(),
                    pct));
        } else {
            int viewH = Math.max(1, textScrollView.getHeight());
            int contentH = Math.max(viewH, pageContentBox.getHeight());
            int totalScrollPages = Math.max(1, (int) Math.ceil((double) contentH / viewH));
            int curScrollPage = Math.min(totalScrollPages, Math.max(1, (textScrollView.getScrollY() / viewH) + 1));

            String rightText = String.format(Locale.US, "%d/%d页 · %.1f%%", curScrollPage, totalScrollPages, pct);
            bottomProgressLabel.setText(rightText);
            if (scrollFooterLeftChapter != null) {
                scrollFooterLeftChapter.setText(chTitle);
            }
            if (scrollFooterRightProgress != null) {
                scrollFooterRightProgress.setText(rightText);
            }
        }
    }

    private void persistCurrentProgress() {
        if (book == null || parsedBook == null || parsedBook.chapters.isEmpty()) return;
        int offset = (settings.getBrowseMode() == ReadingSettings.BROWSE_MODE_PAGED)
                ? pagedReaderView.getCurrentCharOffset()
                : (textScrollView != null ? textScrollView.getScrollY() : 0);
        float pct = computeOverallProgressPercent();
        db.saveReadingProgress(book.id, currentChapterIndex, parsedBook.chapters.size(), offset, pct);
    }

    private void setBarsVisible(boolean visible) {
        this.barsVisible = visible;
        topOverlayContainer.setVisibility(visible ? View.VISIBLE : View.GONE);
        bottomBar.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (!visible && settingsScrollContainer != null) {
            settingsScrollContainer.setVisibility(View.GONE);
        }
        applyImmersiveFullScreen(!visible && settings.isFullScreenReading());
    }

    private void addCurrentBookmark() {
        if (parsedBook == null || parsedBook.chapters.isEmpty()) return;
        ParsedBook.Chapter ch = parsedBook.chapters.get(currentChapterIndex);
        int offset = (settings.getBrowseMode() == ReadingSettings.BROWSE_MODE_PAGED)
                ? pagedReaderView.getCurrentCharOffset()
                : textScrollView.getScrollY();
        float pct = computeOverallProgressPercent();
        String dispChTitle = formatBookText(ch.title);
        String snippet = getCurrentSnippet(formatBookText(ch.getContent()));
        db.addBookmark(book, currentChapterIndex, dispChTitle, offset, pct, snippet);
        Toast.makeText(this, "🔖 已添加书签 (" + dispChTitle + ")", Toast.LENGTH_SHORT).show();
    }

    private void showAddPlainTextNoteDialog() {
        if (parsedBook == null || parsedBook.chapters.isEmpty()) return;
        final ParsedBook.Chapter ch = parsedBook.chapters.get(currentChapterIndex);
        final String defaultExcerpt = getCurrentSnippet(formatBookText(ch.getContent()));

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(12), dp(18), dp(8));

        final EditText excerptEt = new EditText(this);
        excerptEt.setHint("原文摘录（可选）");
        excerptEt.setText(defaultExcerpt);
        excerptEt.setMaxLines(3);
        box.addView(excerptEt);

        final EditText noteEt = new EditText(this);
        noteEt.setHint("输入纯文本读书笔记（自动同步保存为本地 .txt 文件）…");
        noteEt.setMinLines(4);
        noteEt.setGravity(Gravity.TOP | Gravity.START);
        box.addView(noteEt);

        new AlertDialog.Builder(this)
                .setTitle("📝 添加本地纯文本笔记")
                .setView(box)
                .setPositiveButton("保存为纯文本 (.txt)", (d, w) -> {
                    String content = noteEt.getText().toString().trim();
                    if (content.isEmpty()) {
                        Toast.makeText(ReaderActivity.this, "请输入笔记内容", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    NoteItem item = db.saveNote(
                            null,
                            book.id,
                            book.title,
                            book.filePath,
                            currentChapterIndex,
                            ch.title,
                            excerptEt.getText().toString().trim(),
                            content
                    );
                    Toast.makeText(ReaderActivity.this, "已保存至本地纯文本: " + item.txtFilePath, Toast.LENGTH_LONG).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private String getCurrentSnippet(String fullContent) {
        if (fullContent == null || fullContent.isEmpty()) return "";
        int charOffset = (settings.getBrowseMode() == ReadingSettings.BROWSE_MODE_PAGED)
                ? pagedReaderView.getCurrentCharOffset()
                : 0;
        int start = Math.max(0, Math.min(fullContent.length() - 1, charOffset));
        String sub = fullContent.substring(start);
        String cleaned = sub.replace("　", " ").replaceAll("\\s+", " ").trim();
        return cleaned.length() > 75 ? cleaned.substring(0, 75) + "…" : cleaned;
    }

    private void showTocAndBookmarksDialog() {
        if (parsedBook == null || parsedBook.chapters.isEmpty()) return;
        List<Bookmark> bms = db.getBookmarks(book.id);
        final List<String> items = new ArrayList<>();
        final List<Integer> targetChapters = new ArrayList<>();
        final List<Integer> targetOffsets = new ArrayList<>();

        for (Bookmark b : bms) {
            items.add("🔖 [书签] " + formatBookText(b.chapterTitle) + " (" + String.format(Locale.US, "%.1f%%", b.progressPercent) + ")");
            targetChapters.add(b.chapterIndex);
            targetOffsets.add(b.scrollOffset);
        }

        if (parsedBook.isPdf && !parsedBook.tocEntries.isEmpty()) {
            // Find the active TOC entry covering `currentChapterIndex`
            int activeTocIdx = 0;
            for (int i = 0; i < parsedBook.tocEntries.size(); i++) {
                if (parsedBook.tocEntries.get(i).chapterIndex <= currentChapterIndex) {
                    activeTocIdx = i;
                } else {
                    break;
                }
            }
            for (int i = 0; i < parsedBook.tocEntries.size(); i++) {
                ParsedBook.TocEntry te = parsedBook.tocEntries.get(i);
                String prefix = (i == activeTocIdx) ? "▶ " : "    ";
                items.add(prefix + formatBookText(te.title));
                targetChapters.add(te.chapterIndex);
                targetOffsets.add(0);
            }
        } else {
            for (ParsedBook.Chapter ch : parsedBook.chapters) {
                String prefix = (ch.index == currentChapterIndex) ? "▶ " : "    ";
                items.add(prefix + formatBookText(ch.title));
                targetChapters.add(ch.index);
                targetOffsets.add(0);
            }
        }

        int tocCount = (parsedBook.isPdf && !parsedBook.tocEntries.isEmpty())
                ? parsedBook.tocEntries.size()
                : parsedBook.chapters.size();

        new AlertDialog.Builder(this)
                .setTitle("📑 目录与本书书签 (" + tocCount + " 章)")
                .setItems(items.toArray(new String[0]), (d, which) -> {
                    currentChapterIndex = targetChapters.get(which);
                    pendingOffsetOrChar = targetOffsets.get(which);
                    setBarsVisible(false);
                    renderCurrentChapter(false);
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private void showInBookSearchDialog() {
        if (parsedBook == null || parsedBook.chapters.isEmpty()) return;
        final EditText input = new EditText(this);
        input.setHint("输入要在全书检索的关键词…");
        input.setSingleLine(true);

        new AlertDialog.Builder(this)
                .setTitle("🔍 书内全文搜索")
                .setView(input)
                .setPositiveButton("搜索", (d, w) -> {
                    final String rawKw = input.getText().toString().trim();
                    if (rawKw.isEmpty()) return;
                    final boolean simpMode = isTradToSimpActive();
                    final String kw = simpMode ? ChineseTradToSimpConverter.convertToSimplified(rawKw) : rawKw;
                    Toast.makeText(ReaderActivity.this, "正在检索“" + rawKw + "”…", Toast.LENGTH_SHORT).show();
                    executor.execute(() -> {
                        final List<String> hitLabels = new ArrayList<>();
                        final List<Integer> hitChapters = new ArrayList<>();
                        final List<Integer> hitCharOffsets = new ArrayList<>();
                        for (ParsedBook.Chapter ch : parsedBook.chapters) {
                            if (hitLabels.size() >= 100) break;
                            String dispTitle = simpMode ? ChineseTradToSimpConverter.convertToSimplified(ch.title) : ch.title;
                            if (dispTitle != null && (dispTitle.contains(kw) || (ch.title != null && ch.title.contains(rawKw)))) {
                                hitLabels.add("📑 [章节标题] " + dispTitle);
                                hitChapters.add(ch.index);
                                hitCharOffsets.add(0);
                                continue;
                            }
                            String rawText = ch.getContent();
                            String text = simpMode ? ChineseTradToSimpConverter.convertToSimplified(rawText) : rawText;
                            int idx = text.indexOf(kw);
                            if (idx < 0 && !rawKw.equals(kw)) {
                                idx = rawText.indexOf(rawKw);
                            }
                            if (idx >= 0) {
                                int s = Math.max(0, idx - 15);
                                int e = Math.min(text.length(), idx + kw.length() + 25);
                                String snippet = text.substring(s, e).replace("\n", " ");
                                hitLabels.add(dispTitle + ": …" + snippet + "…");
                                hitChapters.add(ch.index);
                                hitCharOffsets.add(idx);
                            }
                        }
                        mainHandler.post(() -> {
                            if (hitLabels.isEmpty()) {
                                Toast.makeText(ReaderActivity.this, "全书未找到“" + kw + "”", Toast.LENGTH_SHORT).show();
                                return;
                            }
                            new AlertDialog.Builder(ReaderActivity.this)
                                    .setTitle("找到 " + hitLabels.size() + " 处匹配")
                                    .setItems(hitLabels.toArray(new String[0]), (d2, which) -> {
                                        currentChapterIndex = hitChapters.get(which);
                                        pendingOffsetOrChar = hitCharOffsets.get(which);
                                        setBarsVisible(false);
                                        renderCurrentChapter(false);
                                    })
                                    .show();
                        });
                    });
                })
                .setNegativeButton("取消", null)
                .show();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        pendingOffsetOrChar = (settings.getBrowseMode() == ReadingSettings.BROWSE_MODE_PAGED)
                ? pagedReaderView.getCurrentCharOffset()
                : textScrollView.getScrollY();
        boolean isLandscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE;
        topCameraSafeOffsetPx = isLandscape ? dp(18) : dp(42);
        if (topBar != null) {
            topBar.setPadding(dp(10), topCameraSafeOffsetPx, dp(10), dp(8));
        }
        updateSettingsSheetMaxHeight();
        updateSettingsHighlights();
        applyVisualSettings();
        applyImmersiveFullScreen(!barsVisible && settings.isFullScreenReading());
        rootFrame.post(() -> {
            updateSettingsSheetMaxHeight();
            applyImmersiveFullScreen(!barsVisible && settings.isFullScreenReading());
            if (parsedBook != null) {
                renderCurrentChapter(false);
            }
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        persistCurrentProgress();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            if (pdfRenderer != null) pdfRenderer.close();
            if (pdfDescriptor != null) pdfDescriptor.close();
            BookVaultCrypto.cleanupSingleSessionFile(decryptedSessionFile);
            BookVaultCrypto.cleanupReadingSessionCache(this);
        } catch (Exception ignored) {
        }
    }

    private TextView createBarButton(String label) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setSingleLine(true);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        tv.setTextColor(Color.parseColor("#FFFFFF"));
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(Color.parseColor("#2D5A4C"));
        gd.setCornerRadius(dp(6));
        tv.setBackground(gd);
        tv.setPadding(dp(4), dp(6), dp(4), dp(6));
        tv.setGravity(Gravity.CENTER);
        return tv;
    }

    private TextView createSelectablePill(String label, boolean active) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setSingleLine(true);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        tv.setPadding(dp(2), dp(6), dp(2), dp(6));
        tv.setGravity(Gravity.CENTER);
        styleSelectablePill(tv, active);
        return tv;
    }

    private void styleSelectablePill(TextView tv, boolean active) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(Color.parseColor(active ? "#C85A32" : "#2D3139"));
        gd.setCornerRadius(dp(6));
        tv.setBackground(gd);
        tv.setTextColor(Color.parseColor(active ? "#FFFFFF" : "#D0D7DE"));
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }
}
