package com.litereader.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.litereader.app.data.Book;
import com.litereader.app.data.BookShareProvider;
import com.litereader.app.data.BookVaultCrypto;
import com.litereader.app.data.Bookmark;
import com.litereader.app.data.NoteItem;
import com.litereader.app.data.ReaderDatabase;
import com.litereader.app.data.ReadingSettings;
import com.litereader.app.parser.BookCoverExtractor;
import com.litereader.app.parser.SampleBooksGenerator;
import com.litereader.app.zlib.ZlibMirrorManager;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Main Bookshelf & Encrypted Vault Library Manager.
 * Features:
 * - Real Book Cover Extraction & Caching for PDF, EPUB, MOBI, AZW3, FB2, and TXT books
 * - Automatic repair of long English/hash/session_v_ PDF & e-book titles
 * - Selectable List View (☰ 列表) and Grid View (⊞ 格子) bookshelf arrangement
 * - Book Delete (`🗑️ 删除`) and On-Demand Decrypted Share (`📤 分享`)
 */
public class MainActivity extends Activity {

    private static final int REQ_IMPORT_BOOK = 1001;

    private static final int TAB_RECENT = 0;
    private static final int TAB_ALL_BOOKS = 1;
    private static final int TAB_FOLDERS = 2;
    private static final int TAB_BOOKMARKS = 3;
    private static final int TAB_NOTES = 4;

    private ReaderDatabase db;
    private ReadingSettings settings;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final ExecutorService prewarmExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private int currentTab = TAB_ALL_BOOKS;
    private String currentFormatFilter = "ALL";
    private String currentFolderFilter = "ALL";
    private String currentSearchQuery = "";

    private TextView zlibStatusSubheader;
    private TextView listLayoutBtn;
    private TextView gridLayoutBtn;
    private LinearLayout navTabsRow;
    private LinearLayout formatChipsRow;
    private LinearLayout folderBannerRow;
    private TextView folderBannerText;
    private LinearLayout contentContainer;
    private EditText searchInput;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        db = ReaderDatabase.getInstance(this);
        settings = new ReadingSettings(this);

        buildResponsiveUi();

        executor.execute(() -> {
            SampleBooksGenerator.ensureSampleBooks(MainActivity.this, db);
            scanDeviceFoldersAndEncrypt();
            mainHandler.post(this::refreshContentList);
        });

        autoSyncZlibraryOnStartup();
        handleIncomingIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshContentList();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        refreshContentList();
    }

    private void buildResponsiveUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#F5F2EB"));

        // ==================== 1. HEADER BAR ====================
        LinearLayout headerBox = new LinearLayout(this);
        headerBox.setOrientation(LinearLayout.VERTICAL);
        headerBox.setBackgroundColor(Color.parseColor("#243E36"));
        headerBox.setPadding(dp(12), dp(10), dp(12), dp(10));

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView appTitle = new TextView(this);
        String verName = "5.3.0";
        try {
            verName = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        appTitle.setText("极简阅读 v" + verName + " 🔒");
        appTitle.setTextColor(Color.WHITE);
        appTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16.5f);
        appTitle.setTypeface(Typeface.DEFAULT_BOLD);
        appTitle.setSingleLine(true);
        appTitle.setEllipsize(TextUtils.TruncateAt.END);
        titleRow.addView(appTitle, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        zlibStatusSubheader = new TextView(this);
        zlibStatusSubheader.setText("Z-Lib 智能检测中…");
        zlibStatusSubheader.setTextColor(Color.parseColor("#B8D8CE"));
        zlibStatusSubheader.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        zlibStatusSubheader.setSingleLine(true);
        zlibStatusSubheader.setEllipsize(TextUtils.TruncateAt.END);
        zlibStatusSubheader.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        zlibStatusSubheader.setPadding(dp(10), 0, 0, 0);
        titleRow.addView(zlibStatusSubheader, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        headerBox.addView(titleRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout actionBtnRow = new LinearLayout(this);
        actionBtnRow.setOrientation(LinearLayout.HORIZONTAL);
        actionBtnRow.setGravity(Gravity.CENTER_VERTICAL);
        actionBtnRow.setWeightSum(3f);
        actionBtnRow.setPadding(0, dp(8), 0, 0);

        TextView importBtn = createEqualWeightButton("＋ 导入加密入库", "#36584D", "#FFFFFF", 12f);
        LinearLayout.LayoutParams impLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        impLp.rightMargin = dp(6);
        importBtn.setOnClickListener(v -> openSystemFilePicker());
        actionBtnRow.addView(importBtn, impLp);

        TextView scanBtn = createEqualWeightButton("🔍 扫描并加密", "#36584D", "#FFFFFF", 12f);
        LinearLayout.LayoutParams scanLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        scanLp.rightMargin = dp(6);
        scanBtn.setOnClickListener(v -> triggerManualFolderScan());
        actionBtnRow.addView(scanBtn, scanLp);

        TextView zlibBtn = createEqualWeightButton("🌐 Z-Library 搜书", "#C85A32", "#FFFFFF", 12f);
        LinearLayout.LayoutParams zLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        zlibBtn.setOnClickListener(v -> startActivity(new Intent(MainActivity.this, ZlibActivity.class)));
        actionBtnRow.addView(zlibBtn, zLp);

        headerBox.addView(actionBtnRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(headerBox, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ==================== 2. SEARCH INPUT + LIST/GRID LAYOUT TOGGLE BAR ====================
        LinearLayout searchContainer = new LinearLayout(this);
        searchContainer.setOrientation(LinearLayout.HORIZONTAL);
        searchContainer.setGravity(Gravity.CENTER_VERTICAL);
        searchContainer.setBackgroundColor(Color.parseColor("#EAE4D7"));
        searchContainer.setPadding(dp(10), dp(6), dp(10), dp(6));

        searchInput = new EditText(this);
        searchInput.setHint("搜索书名、作者、目录或笔记…");
        searchInput.setSingleLine(true);
        searchInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        searchInput.setTextColor(Color.parseColor("#2C2623"));
        searchInput.setHintTextColor(Color.parseColor("#8A7E72"));
        searchInput.setBackground(roundRect("#FFFFFF", dp(7)));
        searchInput.setPadding(dp(10), dp(6), dp(10), dp(6));
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                currentSearchQuery = s != null ? s.toString() : "";
                refreshContentList();
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });
        searchContainer.addView(searchInput, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView clearBtn = createPillButton("清空", "#D8CFC0", "#3B2F2F");
        LinearLayout.LayoutParams clrLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clrLp.leftMargin = dp(5);
        clearBtn.setOnClickListener(v -> searchInput.setText(""));
        searchContainer.addView(clearBtn, clrLp);

        // Bookshelf Layout Toggle Buttons (ICON ONLY: ☰ | ⊞, no text)
        listLayoutBtn = createPillButton("☰", "#243E36", "#FFFFFF");
        listLayoutBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        listLayoutBtn.setPadding(dp(10), dp(4), dp(10), dp(4));
        LinearLayout.LayoutParams listLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        listLp.leftMargin = dp(5);
        listLayoutBtn.setOnClickListener(v -> {
            settings.setBookshelfLayoutMode(ReadingSettings.BOOKSHELF_LAYOUT_LIST);
            updateLayoutToggleButtons();
            refreshContentList();
        });
        searchContainer.addView(listLayoutBtn, listLp);

        gridLayoutBtn = createPillButton("⊞", "#D8CFC0", "#3B2F2F");
        gridLayoutBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        gridLayoutBtn.setPadding(dp(10), dp(4), dp(10), dp(4));
        LinearLayout.LayoutParams gridLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        gridLp.leftMargin = dp(4);
        gridLayoutBtn.setOnClickListener(v -> {
            settings.setBookshelfLayoutMode(ReadingSettings.BOOKSHELF_LAYOUT_GRID);
            updateLayoutToggleButtons();
            refreshContentList();
        });
        searchContainer.addView(gridLayoutBtn, gridLp);
        updateLayoutToggleButtons();

        root.addView(searchContainer);

        // ==================== 3. NAVIGATION TABS ROW (Single screen, NO scrolling!) ====================
        navTabsRow = new LinearLayout(this);
        navTabsRow.setOrientation(LinearLayout.HORIZONTAL);
        navTabsRow.setWeightSum(5f);
        navTabsRow.setBackgroundColor(Color.parseColor("#EFECE6"));
        navTabsRow.setPadding(dp(8), dp(6), dp(8), dp(4));
        root.addView(navTabsRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ==================== 4. FILE FORMAT FILTER ROW (Single screen, NO scrolling!) ====================
        formatChipsRow = new LinearLayout(this);
        formatChipsRow.setOrientation(LinearLayout.HORIZONTAL);
        formatChipsRow.setWeightSum(7f);
        formatChipsRow.setBackgroundColor(Color.parseColor("#EFECE6"));
        formatChipsRow.setPadding(dp(8), dp(2), dp(8), dp(6));
        root.addView(formatChipsRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ==================== 5. ACTIVE FOLDER FILTER BANNER ====================
        folderBannerRow = new LinearLayout(this);
        folderBannerRow.setOrientation(LinearLayout.HORIZONTAL);
        folderBannerRow.setGravity(Gravity.CENTER_VERTICAL);
        folderBannerRow.setBackgroundColor(Color.parseColor("#DCE8E2"));
        folderBannerRow.setPadding(dp(12), dp(6), dp(12), dp(6));
        folderBannerRow.setVisibility(View.GONE);

        folderBannerText = new TextView(this);
        folderBannerText.setTextColor(Color.parseColor("#1E3224"));
        folderBannerText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        folderBannerText.setTypeface(Typeface.DEFAULT_BOLD);
        folderBannerText.setSingleLine(true);
        folderBannerText.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        folderBannerRow.addView(folderBannerText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView exitFolderBtn = createPillButton("✕ 全部目录", "#2D5A4C", "#FFFFFF");
        exitFolderBtn.setOnClickListener(v -> {
            currentFolderFilter = "ALL";
            refreshContentList();
        });
        folderBannerRow.addView(exitFolderBtn);
        root.addView(folderBannerRow);

        // ==================== 6. MAIN SCROLLABLE BOOKSHELF CONTENT ====================
        ScrollView mainScroll = new ScrollView(this);
        contentContainer = new LinearLayout(this);
        contentContainer.setOrientation(LinearLayout.VERTICAL);
        contentContainer.setPadding(dp(10), dp(8), dp(10), dp(24));
        mainScroll.addView(contentContainer);
        root.addView(mainScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        renderNavAndFilterBars();
    }

    private void updateLayoutToggleButtons() {
        if (listLayoutBtn == null || gridLayoutBtn == null) return;
        boolean isGrid = (settings.getBookshelfLayoutMode() == ReadingSettings.BOOKSHELF_LAYOUT_GRID);
        listLayoutBtn.setBackground(roundRect(!isGrid ? "#243E36" : "#D8CFC0", dp(6)));
        listLayoutBtn.setTextColor(Color.parseColor(!isGrid ? "#FFFFFF" : "#3B2F2F"));
        gridLayoutBtn.setBackground(roundRect(isGrid ? "#243E36" : "#D8CFC0", dp(6)));
        gridLayoutBtn.setTextColor(Color.parseColor(isGrid ? "#FFFFFF" : "#3B2F2F"));
    }

    private void renderNavAndFilterBars() {
        boolean isWide = getResources().getConfiguration().screenWidthDp >= 520;
        updateLayoutToggleButtons();

        navTabsRow.removeAllViews();
        String[] tabNames = isWide
                ? new String[]{"🕒 最近阅读", "📚 全部书籍", "📁 文件夹", "🔖 书签", "📝 文本笔记"}
                : new String[]{"最近阅读", "全部书籍", "文件夹", "书签", "文本笔记"};

        for (int i = 0; i < tabNames.length; i++) {
            final int tabIdx = i;
            boolean active = (currentTab == tabIdx);
            TextView tab = createEqualWeightButton(
                    tabNames[i],
                    active ? "#243E36" : "#DFD9CE",
                    active ? "#FFFFFF" : "#3B2F2F",
                    isWide ? 12.5f : 11.5f
            );
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i < tabNames.length - 1) {
                lp.rightMargin = dp(4);
            }
            tab.setOnClickListener(v -> {
                currentTab = tabIdx;
                renderNavAndFilterBars();
                refreshContentList();
            });
            navTabsRow.addView(tab, lp);
        }

        formatChipsRow.removeAllViews();
        String[][] formats = new String[][]{
                {"ALL", isWide ? "全部格式" : "全部"},
                {"EPUB", "EPUB"},
                {"PDF", "PDF"},
                {"AZW3", "AZW3"},
                {"MOBI", "MOBI"},
                {"TXT", "TXT"},
                {"FB2", "FB2"}
        };
        for (int i = 0; i < formats.length; i++) {
            final String code = formats[i][0];
            String label = formats[i][1];
            boolean active = code.equalsIgnoreCase(currentFormatFilter);
            TextView chip = createEqualWeightButton(
                    label,
                    active ? "#C85A32" : "#E5E0D5",
                    active ? "#FFFFFF" : "#4A4238",
                    isWide ? 12f : 11f
            );
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i < formats.length - 1) {
                lp.rightMargin = dp(4);
            }
            chip.setOnClickListener(v -> {
                currentFormatFilter = code;
                renderNavAndFilterBars();
                refreshContentList();
            });
            formatChipsRow.addView(chip, lp);
        }
    }

    private void refreshContentList() {
        renderNavAndFilterBars();
        contentContainer.removeAllViews();

        if (!"ALL".equals(currentFolderFilter) && (currentTab == TAB_ALL_BOOKS || currentTab == TAB_RECENT)) {
            folderBannerRow.setVisibility(View.VISIBLE);
            folderBannerText.setText("📂 来源分组: " + currentFolderFilter);
        } else {
            folderBannerRow.setVisibility(View.GONE);
        }

        switch (currentTab) {
            case TAB_RECENT:
                renderBooksGrid(true);
                break;
            case TAB_ALL_BOOKS:
                renderBooksGrid(false);
                break;
            case TAB_FOLDERS:
                renderFolderBrowser();
                break;
            case TAB_BOOKMARKS:
                renderBookmarksList();
                break;
            case TAB_NOTES:
                renderNotesList();
                break;
        }
    }

    private int calculateGridColumns(boolean isGridMode) {
        Configuration config = getResources().getConfiguration();
        int widthDp = config.screenWidthDp;
        if (isGridMode) {
            if (widthDp >= 840) {
                return 5;
            } else if (widthDp >= 560 || config.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                return 4;
            } else {
                return 3;
            }
        } else {
            if (widthDp >= 840) {
                return 3;
            } else if (widthDp >= 560 || config.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                return 2;
            } else {
                return 1;
            }
        }
    }

    private void renderBooksGrid(boolean onlyRecent) {
        List<Book> books = db.queryBooks(currentSearchQuery, currentFormatFilter, currentFolderFilter, onlyRecent);
        if (books.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(onlyRecent
                    ? "暂无最近阅读记录。点击任意书籍即可开始阅读并自动记录进度。"
                    : "没有符合当前筛选条件的书籍。可点击上方「导入加密入库」或「Z-Library 搜书」。");
            empty.setTextColor(Color.parseColor("#6E6259"));
            empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            empty.setPadding(dp(12), dp(28), dp(12), dp(28));
            empty.setGravity(Gravity.CENTER);
            contentContainer.addView(empty);
            return;
        }

        boolean isGridMode = (settings.getBookshelfLayoutMode() == ReadingSettings.BOOKSHELF_LAYOUT_GRID);
        int columns = calculateGridColumns(isGridMode);
        LinearLayout currentRow = null;

        for (int i = 0; i < books.size(); i++) {
            if (i % columns == 0) {
                currentRow = new LinearLayout(this);
                currentRow.setOrientation(LinearLayout.HORIZONTAL);
                currentRow.setWeightSum(columns);
                LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rowLp.bottomMargin = dp(8);
                contentContainer.addView(currentRow, rowLp);
            }

            Book book = books.get(i);
            View card = isGridMode ? createBookGridCellView(book) : createBookListRowCardView(book);
            LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i % columns < columns - 1) {
                cardLp.rightMargin = dp(8);
            }
            currentRow.addView(card, cardLp);
        }

        int remainder = books.size() % columns;
        if (remainder != 0 && currentRow != null) {
            for (int r = remainder; r < columns; r++) {
                View spacer = new View(this);
                LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(0, 1, 1f);
                currentRow.addView(spacer, sLp);
            }
        }
    }

    /**
     * Asynchronously loads or extracts the real Book Cover Image into `coverIv`, repairs `titleTv`
     * if the book had an ugly English/hash/session_v_ title, and pre-warms the decrypted & parsed book
     * cache in the background so tapping the book opens in 0ms!
     */
    private void bindBookCoverAndTitleAsync(final Book book, final ImageView coverIv, final TextView titleTv) {
        prewarmBookForInstantOpenAsync(book);

        Bitmap mem = BookCoverExtractor.getCachedCoverFromMemory(book.id);
        if (mem != null && !BookCoverExtractor.isUglyOrSyntheticTitle(book.title)) {
            coverIv.setImageBitmap(mem);
            return;
        }
        // Set immediate stylized placeholder while background extractor loads/renders the real cover
        String cleanImmediate = BookCoverExtractor.cleanFileNameArtifacts(book.title);
        if (!cleanImmediate.isEmpty() && BookCoverExtractor.isUglyOrSyntheticTitle(book.title)) {
            titleTv.setText(cleanImmediate);
        }
        coverIv.setImageBitmap(BookCoverExtractor.renderStylizedBookCover(
                cleanImmediate.isEmpty() ? book.title : cleanImmediate, book.author, book.format));

        executor.execute(() -> {
            final String oldTitle = book.title;
            final Bitmap realCover = BookCoverExtractor.loadOrExtractCover(MainActivity.this, book, db);
            mainHandler.post(() -> {
                if (realCover != null && !realCover.isRecycled()) {
                    coverIv.setImageBitmap(realCover);
                }
                if (book.title != null && !book.title.equals(oldTitle)) {
                    titleTv.setText(book.title);
                }
            });
        });
    }

    private void prewarmBookForInstantOpenAsync(final Book book) {
        if (book == null || book.filePath == null) return;
        prewarmExecutor.execute(() -> {
            try {
                File rawVaultFile = new File(book.filePath);
                if (!rawVaultFile.exists()) return;
                String ext = (book.subExtension != null && !book.subExtension.isEmpty())
                        ? book.subExtension
                        : ("." + (book.format != null ? book.format.toLowerCase(Locale.ROOT) : "txt"));
                File readyFile = BookVaultCrypto.decryptToTempReadingFile(MainActivity.this, rawVaultFile, ext);
                if (readyFile != null && readyFile.exists() && !"PDF".equalsIgnoreCase(book.format)) {
                    com.litereader.app.parser.BookParserEngine.parseBook(readyFile);
                }
            } catch (Throwable ignored) {
            }
        });
    }

    /**
     * LIST MODE (☰ 列表): Horizontal card with real Book Cover thumbnail on the left,
     * metadata + progress bar + 4 action buttons on the right.
     */
    private View createBookListRowCardView(final Book book) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setBackground(roundRect("#FFFFFF", dp(10)));
        card.setPadding(dp(10), dp(10), dp(10), dp(10));
        card.setGravity(Gravity.CENTER_VERTICAL);

        // Real Book Cover Container (with format badge at bottom-right of cover)
        FrameLayout coverFrame = new FrameLayout(this);
        coverFrame.setBackground(roundRect("#EAE4D7", dp(6)));
        LinearLayout.LayoutParams coverLp = new LinearLayout.LayoutParams(dp(62), dp(86));
        coverLp.rightMargin = dp(10);

        final ImageView coverIv = new ImageView(this);
        coverIv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        coverFrame.addView(coverIv, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView fmtTag = new TextView(this);
        fmtTag.setText(book.format);
        fmtTag.setTextColor(Color.WHITE);
        fmtTag.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f);
        fmtTag.setTypeface(Typeface.DEFAULT_BOLD);
        fmtTag.setPadding(dp(4), dp(1), dp(4), dp(1));
        fmtTag.setBackground(roundRect(getFormatColorHex(book.format), dp(4)));
        FrameLayout.LayoutParams fmtLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.END);
        fmtLp.rightMargin = dp(3);
        fmtLp.bottomMargin = dp(3);
        coverFrame.addView(fmtTag, fmtLp);

        card.addView(coverFrame, coverLp);

        LinearLayout infoCol = new LinearLayout(this);
        infoCol.setOrientation(LinearLayout.VERTICAL);

        final TextView titleTv = new TextView(this);
        titleTv.setText(book.title);
        titleTv.setTextColor(Color.parseColor("#1F2328"));
        titleTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f);
        titleTv.setTypeface(Typeface.DEFAULT_BOLD);
        titleTv.setMaxLines(2);
        titleTv.setEllipsize(TextUtils.TruncateAt.END);
        infoCol.addView(titleTv);

        bindBookCoverAndTitleAsync(book, coverIv, titleTv);

        TextView metaTv = new TextView(this);
        String folderTag = (book.folderName != null && !book.folderName.isEmpty()) ? ("📁 " + book.folderName) : "";
        metaTv.setText(book.author + " · " + book.getFormattedSize() + " · " + folderTag);
        metaTv.setTextColor(Color.parseColor("#6E6259"));
        metaTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        metaTv.setSingleLine(true);
        metaTv.setEllipsize(TextUtils.TruncateAt.END);
        metaTv.setPadding(0, dp(2), 0, dp(4));
        infoCol.addView(metaTv);

        LinearLayout progRow = new LinearLayout(this);
        progRow.setOrientation(LinearLayout.HORIZONTAL);
        progRow.setGravity(Gravity.CENTER_VERTICAL);

        ProgressBar pb = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        pb.setMax(1000);
        pb.setProgress((int) (book.progressPercent * 10));
        LinearLayout.LayoutParams pbLp = new LinearLayout.LayoutParams(0, dp(5), 1f);
        pbLp.rightMargin = dp(8);
        progRow.addView(pb, pbLp);

        TextView progText = new TextView(this);
        progText.setText(book.getProgressBadge() + " (" + (book.currentChapter + 1) + "/" + Math.max(1, book.totalChapters) + "节)");
        progText.setTextColor(Color.parseColor("#2D5A4C"));
        progText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        progText.setTypeface(Typeface.DEFAULT_BOLD);
        progRow.addView(progText);

        infoCol.addView(progRow);

        // Equal-weight 4-button action row: 阅读 | PDF一键转换(或同目录) | 分享(解密) | 删除
        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setWeightSum(4f);
        actionRow.setPadding(0, dp(6), 0, 0);

        TextView readBtn = createEqualWeightButton(book.lastReadTime > 0 ? "▶ 续读" : "📖 阅读", "#243E36", "#FFFFFF", 11f);
        LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        rLp.rightMargin = dp(4);
        readBtn.setOnClickListener(v -> openBookInReader(book.id, -1, -1));
        actionRow.addView(readBtn, rLp);

        if ("PDF".equalsIgnoreCase(book.format)) {
            boolean isReflow = settings.isPdfReflowEnabledForBook(book.id);
            TextView convertBtn = createEqualWeightButton(isReflow ? "✨已重排" : "🔄 转换", "#C85A32", "#FFFFFF", 11f);
            LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            cLp.rightMargin = dp(4);
            convertBtn.setOnClickListener(v -> {
                settings.setPdfReflowEnabledForBook(book.id, true);
                Toast.makeText(MainActivity.this,
                        "✨ 已将《" + book.title + "》一键转换为全屏智能重排模式，支持放大缩小文字！",
                        Toast.LENGTH_SHORT).show();
                Intent intent = new Intent(MainActivity.this, ReaderActivity.class);
                intent.putExtra("book_id", book.id);
                intent.putExtra("convert_pdf_reflow", true);
                startActivity(intent);
            });
            actionRow.addView(convertBtn, cLp);
        } else {
            TextView folderBtn = createEqualWeightButton("📂 同目录", "#EAE4D7", "#3B2F2F", 11f);
            LinearLayout.LayoutParams fLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            fLp.rightMargin = dp(4);
            folderBtn.setOnClickListener(v -> {
                currentFolderFilter = book.folderPath;
                currentTab = TAB_ALL_BOOKS;
                refreshContentList();
            });
            actionRow.addView(folderBtn, fLp);
        }

        TextView shareBtn = createEqualWeightButton("📤 分享", "#2D5A4C", "#FFFFFF", 11f);
        LinearLayout.LayoutParams shLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        shLp.rightMargin = dp(4);
        shareBtn.setOnClickListener(v -> decryptAndShareBook(book));
        actionRow.addView(shareBtn, shLp);

        TextView delBtn = createEqualWeightButton("🗑️ 删除", "#E8DFD1", "#7A3E2E", 11f);
        LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        delBtn.setOnClickListener(v -> confirmAndDeleteBook(book));
        actionRow.addView(delBtn, dLp);

        infoCol.addView(actionRow);
        card.addView(infoCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        card.setOnClickListener(v -> openBookInReader(book.id, -1, -1));
        return card;
    }

    /**
     * GRID MODE (⊞ 格子): Vertical bookshelf cell with prominent Book Cover Image,
     * format + progress overlay badges, 2-line Book Title, and compact action bar.
     */
    private View createBookGridCellView(final Book book) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setBackground(roundRect("#FFFFFF", dp(10)));
        cell.setPadding(dp(7), dp(7), dp(7), dp(8));

        FrameLayout coverFrame = new FrameLayout(this);
        coverFrame.setBackground(roundRect("#EAE4D7", dp(7)));
        LinearLayout.LayoutParams cfLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(136));

        final ImageView coverIv = new ImageView(this);
        coverIv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        coverFrame.addView(coverIv, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Format badge at top-right of cover
        TextView fmtTag = new TextView(this);
        fmtTag.setText(book.format);
        fmtTag.setTextColor(Color.WHITE);
        fmtTag.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f);
        fmtTag.setTypeface(Typeface.DEFAULT_BOLD);
        fmtTag.setPadding(dp(5), dp(1), dp(5), dp(1));
        fmtTag.setBackground(roundRect(getFormatColorHex(book.format), dp(4)));
        FrameLayout.LayoutParams fmtLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END);
        fmtLp.topMargin = dp(4);
        fmtLp.rightMargin = dp(4);
        coverFrame.addView(fmtTag, fmtLp);

        // Progress badge at bottom-right of cover
        TextView progBadge = new TextView(this);
        progBadge.setText(book.getProgressBadge());
        progBadge.setTextColor(Color.WHITE);
        progBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f);
        progBadge.setTypeface(Typeface.DEFAULT_BOLD);
        progBadge.setPadding(dp(5), dp(1), dp(5), dp(1));
        progBadge.setBackground(roundRect("#B318191D", dp(4)));
        FrameLayout.LayoutParams pbLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.END);
        pbLp.bottomMargin = dp(4);
        pbLp.rightMargin = dp(4);
        coverFrame.addView(progBadge, pbLp);

        cell.addView(coverFrame, cfLp);

        final TextView titleTv = new TextView(this);
        titleTv.setText(book.title);
        titleTv.setTextColor(Color.parseColor("#1F2328"));
        titleTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        titleTv.setTypeface(Typeface.DEFAULT_BOLD);
        titleTv.setLines(2);
        titleTv.setMaxLines(2);
        titleTv.setEllipsize(TextUtils.TruncateAt.END);
        titleTv.setPadding(dp(2), dp(5), dp(2), dp(4));
        cell.addView(titleTv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        bindBookCoverAndTitleAsync(book, coverIv, titleTv);

        // Compact 3-button bottom bar for Grid Cell: 阅读 | ⋯ | 🗑️
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setWeightSum(3f);

        TextView readBtn = createEqualWeightButton(book.lastReadTime > 0 ? "续读" : "阅读", "#243E36", "#FFFFFF", 10.5f);
        LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.3f);
        rLp.rightMargin = dp(3);
        readBtn.setOnClickListener(v -> openBookInReader(book.id, -1, -1));
        btnRow.addView(readBtn, rLp);

        TextView moreBtn = createEqualWeightButton("⋯", "#EAE4D7", "#3B2F2F", 10.5f);
        LinearLayout.LayoutParams mLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.85f);
        mLp.rightMargin = dp(3);
        moreBtn.setOnClickListener(v -> showGridBookActionsDialog(book));
        btnRow.addView(moreBtn, mLp);

        TextView delGridBtn = createEqualWeightButton("🗑️", "#E8DFD1", "#7A3E2E", 10.5f);
        LinearLayout.LayoutParams dgLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.85f);
        delGridBtn.setOnClickListener(v -> confirmAndDeleteBook(book));
        btnRow.addView(delGridBtn, dgLp);

        cell.addView(btnRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        cell.setOnClickListener(v -> openBookInReader(book.id, -1, -1));
        cell.setOnLongClickListener(v -> {
            showGridBookActionsDialog(book);
            return true;
        });
        return cell;
    }

    private void showGridBookActionsDialog(final Book book) {
        final List<String> opts = new ArrayList<>();
        opts.add("📖 立即打开阅读");
        if ("PDF".equalsIgnoreCase(book.format)) {
            opts.add("✨ 一键转换为 PDF 全屏重排模式");
        }
        opts.add("📤 临时解密并分享书籍");
        opts.add("✏️ 修改书籍显示名称");
        opts.add("🗑️ 彻底删除书籍");

        new AlertDialog.Builder(this)
                .setTitle("《" + book.title + "》")
                .setItems(opts.toArray(new String[0]), (d, which) -> {
                    String chosen = opts.get(which);
                    if (chosen.startsWith("📖")) {
                        openBookInReader(book.id, -1, -1);
                    } else if (chosen.startsWith("✨")) {
                        settings.setPdfReflowEnabledForBook(book.id, true);
                        Intent intent = new Intent(MainActivity.this, ReaderActivity.class);
                        intent.putExtra("book_id", book.id);
                        intent.putExtra("convert_pdf_reflow", true);
                        startActivity(intent);
                    } else if (chosen.startsWith("📤")) {
                        decryptAndShareBook(book);
                    } else if (chosen.startsWith("✏️")) {
                        showRenameBookDialog(book);
                    } else if (chosen.startsWith("🗑️")) {
                        confirmAndDeleteBook(book);
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private void showRenameBookDialog(final Book book) {
        final EditText input = new EditText(this);
        input.setText(book.title);
        input.setSingleLine(true);
        input.setPadding(dp(18), dp(12), dp(18), dp(12));
        new AlertDialog.Builder(this)
                .setTitle("✏️ 修改书名")
                .setView(input)
                .setPositiveButton("保存", (d, w) -> {
                    String newName = input.getText().toString().trim();
                    if (!newName.isEmpty()) {
                        book.title = newName;
                        db.forceUpdateBookTitle(book.id, newName);
                        refreshContentList();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * Decrypts the encrypted `.lrv` book on-the-fly into a temporary cache file and invokes Android's Share Sheet.
     */
    private void decryptAndShareBook(final Book book) {
        Toast.makeText(this, "正在临时解密《" + book.title + "》以供外部分享…", Toast.LENGTH_SHORT).show();
        executor.execute(() -> {
            try {
                final File decryptedShareFile = BookVaultCrypto.decryptForSharing(MainActivity.this, book);
                final Uri shareUri = BookShareProvider.getShareUriForFile(decryptedShareFile);
                mainHandler.post(() -> {
                    try {
                        Intent shareIntent = new Intent(Intent.ACTION_SEND);
                        String mime = getContentResolver().getType(shareUri);
                        shareIntent.setType(mime != null ? mime : "*/*");
                        shareIntent.putExtra(Intent.EXTRA_STREAM, shareUri);
                        shareIntent.putExtra(Intent.EXTRA_SUBJECT, book.title);
                        shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        startActivity(Intent.createChooser(shareIntent, "分享已解密的书籍《" + book.title + "》"));
                    } catch (Exception e) {
                        Toast.makeText(MainActivity.this, "调起分享面板失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                });
            } catch (final Exception e) {
                mainHandler.post(() -> Toast.makeText(MainActivity.this, "解密分享失败: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    /**
     * Confirms and permanently deletes the book from the encrypted vault and database.
     */
    private void confirmAndDeleteBook(final Book book) {
        new AlertDialog.Builder(this)
                .setTitle("🗑️ 删除加密书籍")
                .setMessage("确认从加密专有书库中彻底删除《" + book.title + "》吗？删除后不可恢复。")
                .setPositiveButton("彻底删除", (d, w) -> {
                    db.deleteBook(book.id);
                    Toast.makeText(MainActivity.this, "已从加密专有库中彻底删除《" + book.title + "》", Toast.LENGTH_SHORT).show();
                    refreshContentList();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void renderFolderBrowser() {
        Map<String, Integer> folders = db.getFolderCounts(currentFormatFilter);
        if (folders.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("暂无文件夹分组数据");
            empty.setPadding(dp(16), dp(24), dp(16), dp(24));
            contentContainer.addView(empty);
            return;
        }

        for (final Map.Entry<String, Integer> entry : folders.entrySet()) {
            final String folderPath = entry.getKey();
            int count = entry.getValue();
            File f = new File(folderPath);

            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setGravity(Gravity.CENTER_VERTICAL);
            card.setBackground(roundRect("#FFFFFF", dp(10)));
            card.setPadding(dp(14), dp(12), dp(14), dp(12));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(8);

            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);

            TextView nameTv = new TextView(this);
            nameTv.setText("📁 " + f.getName() + "  (" + count + " 本 · 已加密入库)");
            nameTv.setTextColor(Color.parseColor("#1F2328"));
            nameTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            nameTv.setTypeface(Typeface.DEFAULT_BOLD);
            col.addView(nameTv);

            TextView pathTv = new TextView(this);
            pathTv.setText("来源分组: " + folderPath);
            pathTv.setTextColor(Color.parseColor("#6E6259"));
            pathTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
            col.addView(pathTv);

            card.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView openBtn = createPillButton("查看书籍 →", "#2D5A4C", "#FFFFFF");
            card.addView(openBtn);

            card.setOnClickListener(v -> {
                currentFolderFilter = folderPath;
                currentTab = TAB_ALL_BOOKS;
                refreshContentList();
            });
            contentContainer.addView(card, lp);
        }
    }

    private void renderBookmarksList() {
        List<Bookmark> bookmarks = db.getBookmarks(null);
        if (bookmarks.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("暂无书签。在阅读器点击屏幕中间唤出顶部菜单，点击「🔖 书签」即可随时记录阅读位置。");
            empty.setTextColor(Color.parseColor("#6E6259"));
            empty.setPadding(dp(16), dp(24), dp(16), dp(24));
            contentContainer.addView(empty);
            return;
        }

        for (final Bookmark bm : bookmarks) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackground(roundRect("#FFFFFF", dp(10)));
            card.setPadding(dp(14), dp(12), dp(14), dp(12));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(8);

            TextView title = new TextView(this);
            title.setText("🔖 《" + bm.bookTitle + "》 · " + bm.chapterTitle);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            title.setTextColor(Color.parseColor("#243E36"));
            card.addView(title);

            if (bm.snippet != null && !bm.snippet.isEmpty()) {
                TextView snip = new TextView(this);
                snip.setText("“" + bm.snippet + "”");
                snip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                snip.setTextColor(Color.parseColor("#4A4238"));
                snip.setPadding(0, dp(4), 0, dp(4));
                card.addView(snip);
            }

            LinearLayout bottomRow = new LinearLayout(this);
            bottomRow.setOrientation(LinearLayout.HORIZONTAL);
            bottomRow.setGravity(Gravity.CENTER_VERTICAL);

            TextView timeTv = new TextView(this);
            timeTv.setText(String.format(Locale.US, "进度 %.1f%%  ·  %s", bm.progressPercent, bm.getFormattedTime()));
            timeTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
            timeTv.setTextColor(Color.parseColor("#8A7E72"));
            bottomRow.addView(timeTv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView jumpBtn = createPillButton("跳转阅读", "#2D5A4C", "#FFFFFF");
            jumpBtn.setOnClickListener(v -> openBookInReader(bm.bookId, bm.chapterIndex, bm.scrollOffset));
            bottomRow.addView(jumpBtn);

            TextView delBtn = createPillButton("删除", "#E8DFD1", "#7A3E2E");
            LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            dLp.leftMargin = dp(6);
            delBtn.setOnClickListener(v -> {
                db.deleteBookmark(bm.id);
                refreshContentList();
            });
            bottomRow.addView(delBtn, dLp);

            card.addView(bottomRow);
            contentContainer.addView(card, lp);
        }
    }

    private void renderNotesList() {
        LinearLayout topActionRow = new LinearLayout(this);
        topActionRow.setOrientation(LinearLayout.HORIZONTAL);
        topActionRow.setGravity(Gravity.CENTER_VERTICAL);
        topActionRow.setPadding(0, 0, 0, dp(10));

        TextView dirInfo = new TextView(this);
        dirInfo.setText("私密纯文本笔记目录: " + db.getNotesDirectory().getAbsolutePath());
        dirInfo.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        dirInfo.setTextColor(Color.parseColor("#5C5346"));
        topActionRow.addView(dirInfo, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView newNoteBtn = createPillButton("＋ 新建纯文本笔记", "#243E36", "#FFFFFF");
        newNoteBtn.setOnClickListener(v -> showEditNoteDialog(null));
        topActionRow.addView(newNoteBtn);
        contentContainer.addView(topActionRow);

        List<NoteItem> notes = db.getNotes(null, currentSearchQuery);
        if (notes.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("暂无本地纯文本笔记。可在阅读时点击「📝 笔记」或点击上方「＋ 新建纯文本笔记」，所有笔记均保存在应用私有纯文本目录。");
            empty.setTextColor(Color.parseColor("#6E6259"));
            empty.setPadding(dp(12), dp(20), dp(12), dp(20));
            contentContainer.addView(empty);
            return;
        }

        for (final NoteItem note : notes) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackground(roundRect("#FFFFFF", dp(10)));
            card.setPadding(dp(14), dp(12), dp(14), dp(12));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(8);

            TextView head = new TextView(this);
            head.setText("📝 《" + note.bookTitle + "》 " + (note.chapterTitle != null ? note.chapterTitle : ""));
            head.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f);
            head.setTypeface(Typeface.DEFAULT_BOLD);
            head.setTextColor(Color.parseColor("#243E36"));
            card.addView(head);

            if (note.excerpt != null && !note.excerpt.isEmpty()) {
                TextView ex = new TextView(this);
                ex.setText("摘录: “" + note.excerpt + "”");
                ex.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
                ex.setTextColor(Color.parseColor("#6E6259"));
                ex.setPadding(0, dp(4), 0, dp(2));
                card.addView(ex);
            }

            TextView body = new TextView(this);
            body.setText(note.content);
            body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            body.setTextColor(Color.parseColor("#1F2328"));
            body.setPadding(0, dp(4), 0, dp(6));
            card.addView(body);

            TextView txtPathTv = new TextView(this);
            txtPathTv.setText("时间: " + note.getFormattedTime());
            txtPathTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            txtPathTv.setTextColor(Color.parseColor("#8A7E72"));
            card.addView(txtPathTv);

            LinearLayout btnRow = new LinearLayout(this);
            btnRow.setOrientation(LinearLayout.HORIZONTAL);
            btnRow.setPadding(0, dp(6), 0, 0);

            TextView editBtn = createPillButton("编辑纯文本", "#2D5A4C", "#FFFFFF");
            editBtn.setOnClickListener(v -> showEditNoteDialog(note));
            btnRow.addView(editBtn);

            if (note.bookId > 0) {
                TextView jumpBtn = createPillButton("打开原书", "#EAE4D7", "#3B2F2F");
                LinearLayout.LayoutParams jLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                jLp.leftMargin = dp(6);
                jumpBtn.setOnClickListener(v -> openBookInReader(note.bookId, note.chapterIndex, 0));
                btnRow.addView(jumpBtn, jLp);
            }

            TextView delBtn = createPillButton("删除", "#E8DFD1", "#7A3E2E");
            LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            dLp.leftMargin = dp(6);
            delBtn.setOnClickListener(v -> {
                db.deleteNote(note);
                refreshContentList();
            });
            btnRow.addView(delBtn, dLp);

            card.addView(btnRow);
            contentContainer.addView(card, lp);
        }
    }

    private void showEditNoteDialog(final NoteItem existing) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(12), dp(18), dp(8));

        final EditText titleEt = new EditText(this);
        titleEt.setHint("关联书名或笔记主题（例如：读书随笔）");
        titleEt.setText(existing != null ? existing.bookTitle : "随身纯文本笔记");
        box.addView(titleEt);

        final EditText contentEt = new EditText(this);
        contentEt.setHint("输入纯文本笔记内容（保存后将实时写入本地 .txt 文件）…");
        contentEt.setMinLines(4);
        contentEt.setGravity(Gravity.TOP | Gravity.START);
        if (existing != null) contentEt.setText(existing.content);
        box.addView(contentEt);

        new AlertDialog.Builder(this)
                .setTitle(existing != null ? "📝 编辑本地纯文本笔记" : "📝 新建本地纯文本笔记")
                .setView(box)
                .setPositiveButton("保存 (.txt)", (d, w) -> {
                    String bTitle = titleEt.getText().toString().trim();
                    String text = contentEt.getText().toString().trim();
                    if (text.isEmpty()) return;
                    db.saveNote(
                            existing != null ? existing.id : null,
                            existing != null ? existing.bookId : 0L,
                            bTitle.isEmpty() ? "随身纯文本笔记" : bTitle,
                            existing != null ? existing.bookPath : "",
                            existing != null ? existing.chapterIndex : 0,
                            existing != null ? existing.chapterTitle : "独立笔记",
                            existing != null ? existing.excerpt : "",
                            text
                    );
                    Toast.makeText(MainActivity.this, "已保存至私密纯文本笔记", Toast.LENGTH_SHORT).show();
                    refreshContentList();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void openBookInReader(long bookId, int jumpChapter, int jumpOffset) {
        Intent intent = new Intent(this, ReaderActivity.class);
        intent.putExtra("book_id", bookId);
        if (jumpChapter >= 0) intent.putExtra("jump_chapter", jumpChapter);
        if (jumpOffset >= 0) intent.putExtra("jump_offset", jumpOffset);
        startActivity(intent);
    }

    private void autoSyncZlibraryOnStartup() {
        executor.execute(() -> {
            final ZlibMirrorManager.SyncResult res = ZlibMirrorManager.syncAndSelectFastestMirror(MainActivity.this, settings, 0);
            mainHandler.post(() -> {
                if (zlibStatusSubheader != null) {
                    String modeTag = res.isVpnEnvironment ? "🌐官网" : "🇨🇳直链";
                    zlibStatusSubheader.setText(modeTag + ": " + res.activeMirror
                            + (settings.hasZlibCredentials() ? " (已绑)" : ""));
                }
            });
        });
    }

    private void triggerManualFolderScan() {
        Toast.makeText(this, "正在扫描外部目录，将书籍加密转入专有文件夹并清理外部原文件…", Toast.LENGTH_SHORT).show();
        executor.execute(() -> {
            final int count = scanDeviceFoldersAndEncrypt();
            mainHandler.post(() -> {
                refreshContentList();
                Toast.makeText(MainActivity.this, "扫描加密完成，共加密收容 " + count + " 本书籍（外部原文件已清理）", Toast.LENGTH_LONG).show();
            });
        });
    }

    private int scanDeviceFoldersAndEncrypt() {
        List<File> roots = new ArrayList<>();
        try {
            File extStorage = Environment.getExternalStorageDirectory();
            if (extStorage != null && extStorage.exists()) {
                roots.add(new File(extStorage, "Download"));
                roots.add(new File(extStorage, "Books"));
                roots.add(new File(extStorage, "Documents"));
                roots.add(new File(extStorage, "eBooks"));
            }
        } catch (Exception ignored) {
        }
        File appZlibDir = getExternalFilesDir("ZLibrary_Downloads");
        if (appZlibDir != null) roots.add(appZlibDir);
        File appImportedDir = getExternalFilesDir("Imported_Books");
        if (appImportedDir != null) roots.add(appImportedDir);

        int count = 0;
        for (File dir : roots) {
            count += scanDirectoryRecursive(dir, 0);
        }
        return count;
    }

    private int scanDirectoryRecursive(File dir, int depth) {
        if (dir == null || !dir.exists() || !dir.isDirectory() || depth > 3) return 0;
        if (".litereader_vault".equals(dir.getName())) return 0;
        File[] files = dir.listFiles();
        if (files == null) return 0;
        int found = 0;
        for (File f : files) {
            if (f.isDirectory() && !f.getName().startsWith(".")) {
                found += scanDirectoryRecursive(f, depth + 1);
            } else if (Book.isSupportedFile(f)) {
                // Encrypt into `.litereader_vault/` and delete external source file!
                if (db.importAndEncryptFileToVault(f, null, null, null, true) != null) {
                    found++;
                }
            }
        }
        return found;
    }

    private void openSystemFilePicker() {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            startActivityForResult(intent, REQ_IMPORT_BOOK);
        } catch (Exception e) {
            Toast.makeText(this, "无法打开系统文件选择器: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_IMPORT_BOOK && resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
            importUriToEncryptedVault(data.getData(), true);
        }
    }

    private void handleIncomingIntent(Intent intent) {
        if (intent != null && Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            importUriToEncryptedVault(intent.getData(), true);
        }
    }

    /**
     * Encrypts an externally selected book URI directly into `.litereader_vault/v_<uuid>.lrv`
     * and deletes the original external book so no other app can read any information about it.
     */
    private void importUriToEncryptedVault(final Uri uri, final boolean openImmediately) {
        executor.execute(() -> {
            try {
                ContentResolver cr = getContentResolver();
                String displayName = queryUriDisplayName(cr, uri);
                if (displayName == null || displayName.isEmpty()) {
                    displayName = "imported_book_" + System.currentTimeMillis() + ".txt";
                }
                if ("UNKNOWN".equals(Book.detectFormat(displayName))) {
                    ToastOnMain("不支持的文件格式: " + displayName + " (支持 EPUB/PDF/AZW3/MOBI/TXT/FB2/.fb2.zip/.fbz)");
                    return;
                }

                Book importedBook;
                try (InputStream is = cr.openInputStream(uri)) {
                    if (is == null) return;
                    importedBook = db.importStreamToEncryptedVault(
                            is, displayName, null, "外部加密导入", "🔒 外部导入加密库");
                }

                // Delete the external source file via SAF DocumentsContract, ContentResolver, and direct path resolution!
                final boolean externalDeleted = BookVaultCrypto.deleteExternalUriAndFile(MainActivity.this, uri);
                final Book finalBook = importedBook;

                mainHandler.post(() -> {
                    refreshContentList();
                    Toast.makeText(MainActivity.this,
                            externalDeleted
                                    ? "🔒 已加密存入专有文件夹，并已彻底删除外部原文件！"
                                    : "🔒 已加密存入专有文件夹！",
                            Toast.LENGTH_LONG).show();
                    if (openImmediately && finalBook != null) {
                        openBookInReader(finalBook.id, -1, -1);
                    }
                });
            } catch (Exception e) {
                ToastOnMain("加密导入失败: " + e.getMessage());
            }
        });
    }

    private String queryUriDisplayName(ContentResolver cr, Uri uri) {
        if ("file".equalsIgnoreCase(uri.getScheme()) && uri.getPath() != null) {
            return new File(uri.getPath()).getName();
        }
        try (Cursor c = cr.query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) return c.getString(idx);
            }
        } catch (Exception ignored) {
        }
        return uri.getLastPathSegment();
    }

    private void ToastOnMain(final String msg) {
        mainHandler.post(() -> Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show());
    }

    private String getFormatColorHex(String format) {
        if (format == null) return "#2D5A4C";
        switch (format.toUpperCase(Locale.ROOT)) {
            case "EPUB": return "#2D5A4C";
            case "PDF": return "#A63A2B";
            case "AZW3": return "#2B5B84";
            case "MOBI": return "#3D6B8E";
            case "FB2": return "#6E4780";
            case "TXT":
            default: return "#5C5346";
        }
    }

    private TextView createEqualWeightButton(String text, String bgHex, String fgHex, float textSizeSp) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(Color.parseColor(fgHex));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setGravity(Gravity.CENTER);
        tv.setSingleLine(true);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        tv.setPadding(dp(2), dp(6), dp(2), dp(6));
        tv.setBackground(roundRect(bgHex, dp(6)));
        return tv;
    }

    private TextView createPillButton(String text, String bgHex, String fgHex) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(Color.parseColor(fgHex));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setSingleLine(true);
        tv.setPadding(dp(10), dp(5), dp(10), dp(5));
        tv.setBackground(roundRect(bgHex, dp(6)));
        return tv;
    }

    private GradientDrawable roundRect(String colorHex, int radiusPx) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(Color.parseColor(colorHex));
        gd.setCornerRadius(radiusPx);
        return gd;
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }
}
