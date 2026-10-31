package com.litereader.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.util.Base64;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.litereader.app.data.Book;
import com.litereader.app.data.ReaderDatabase;
import com.litereader.app.data.ReadingSettings;
import com.litereader.app.zlib.ZlibMirrorManager;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Z-Library Smart Dual-Network Browser, Simplified Chinese Default Enforcer,
 * Non-VPN Direct-Connect Traffic Camouflage & Live Encrypted Vault Importer.
 */
public class ZlibActivity extends Activity {

    private ReadingSettings settings;
    private ReaderDatabase db;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private TextView statusBanner;
    private TextView accountBadge;
    private TextView envModeBtn;
    private Spinner mirrorSpinner;
    private EditText searchInput;
    private Spinner formatSpinner;
    private WebView smartWebView;
    private ProgressBar progressBar;

    private int currentEnvFilterMode = 0; // 0 = 自动判断(未翻墙必走直链), 1 = 强制官网(需翻墙), 2 = 强制直链(免翻墙)
    private volatile boolean isCurrentVpnActive = false;
    private final List<String> availableMirrors = new ArrayList<>();
    private String currentlyLoadedMirrorBase = "";
    private volatile boolean userHasInitiatedSearchOrNav = false;
    private int autoFailoverIndex = 0;

    // Live Download Progress Dialog widgets
    private AlertDialog downloadDialog;
    private ProgressBar downloadDialogBar;
    private TextView downloadDialogStatus;
    private TextView downloadDialogPercent;
    private volatile boolean isDownloadingNow = false;
    private volatile boolean isDownloadCancelledByUser = false;
    private volatile long activeDownloadSessionId = 0L;
    private volatile String lastClickedBookTitle = null;
    private volatile String lastClickedBookPageUrl = null;
    private volatile String lastClickedDlUrl = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        settings = new ReadingSettings(this);
        db = ReaderDatabase.getInstance(this);

        buildUi();
        detectNetworkAndLoadBestUrl(0);
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#F6F4EF"));

        // 1. Two-row Top Bar
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.VERTICAL);
        topBar.setBackgroundColor(Color.parseColor("#243E36"));
        topBar.setPadding(dp(10), dp(8), dp(10), dp(8));

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setGravity(Gravity.CENTER_VERTICAL);

        TextView backBtn = createActionPill("← 返回书架", "#36584D", "#FFFFFF");
        backBtn.setOnClickListener(v -> finish());
        row1.addView(backBtn);

        TextView titleView = new TextView(this);
        titleView.setText("Z-Library 中文智能搜书");
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        titleView.setPadding(dp(10), 0, dp(6), 0);
        row1.addView(titleView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        accountBadge = createActionPill((settings.hasZlibCredentials() || settings.hasZlibAuthTokens()) ? "🔐 已登录" : "🔑 登录设置(填一次)", "#C85A32", "#FFFFFF");
        accountBadge.setOnClickListener(v -> showCredentialsDialog());
        row1.addView(accountBadge);

        topBar.addView(row1, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setWeightSum(3f);
        row2.setPadding(0, dp(6), 0, 0);

        envModeBtn = createEqualWeightPill("🤖 自动判断网络", "#36584D", "#FFFFFF");
        LinearLayout.LayoutParams e1Lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        e1Lp.rightMargin = dp(5);
        envModeBtn.setOnClickListener(v -> {
            currentEnvFilterMode = (currentEnvFilterMode + 1) % 3;
            updateEnvModeButtonLabel();
            detectNetworkAndLoadBestUrl(currentEnvFilterMode);
        });
        row2.addView(envModeBtn, e1Lp);

        TextView forceDirectBtn = createEqualWeightPill("🇨🇳 切换直链", "#2D5A4C", "#FFFFFF");
        LinearLayout.LayoutParams e2Lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        e2Lp.rightMargin = dp(5);
        forceDirectBtn.setOnClickListener(v -> {
            currentEnvFilterMode = 2;
            updateEnvModeButtonLabel();
            detectNetworkAndLoadBestUrl(2);
        });
        row2.addView(forceDirectBtn, e2Lp);

        TextView forceOfficialBtn = createEqualWeightPill("🌐 切换官网", "#2D5A4C", "#FFFFFF");
        LinearLayout.LayoutParams e3Lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        forceOfficialBtn.setOnClickListener(v -> {
            currentEnvFilterMode = 1;
            updateEnvModeButtonLabel();
            detectNetworkAndLoadBestUrl(1);
        });
        row2.addView(forceOfficialBtn, e3Lp);

        topBar.addView(row2, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(topBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 2. Mirror Sync Banner + Domain Selector
        LinearLayout mirrorBar = new LinearLayout(this);
        mirrorBar.setOrientation(LinearLayout.VERTICAL);
        mirrorBar.setBackgroundColor(Color.parseColor("#EAE4D7"));
        mirrorBar.setPadding(dp(10), dp(5), dp(10), dp(5));

        statusBanner = new TextView(this);
        statusBanner.setText("正在智能检测并连接可用中文网址…");
        statusBanner.setTextColor(Color.parseColor("#2D5A4C"));
        statusBanner.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        statusBanner.setSingleLine(true);
        statusBanner.setEllipsize(TextUtils.TruncateAt.END);
        mirrorBar.addView(statusBanner);

        LinearLayout selectorRow = new LinearLayout(this);
        selectorRow.setOrientation(LinearLayout.HORIZONTAL);
        selectorRow.setGravity(Gravity.CENTER_VERTICAL);
        selectorRow.setPadding(0, dp(2), 0, 0);

        TextView mirrorLabel = new TextView(this);
        mirrorLabel.setText("当前网址: ");
        mirrorLabel.setTextColor(Color.parseColor("#3B2F2F"));
        mirrorLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        selectorRow.addView(mirrorLabel);

        mirrorSpinner = new Spinner(this);
        LinearLayout.LayoutParams spinLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        selectorRow.addView(mirrorSpinner, spinLp);

        TextView syncBtn = createActionPill("↻ 重测可用网址", "#2D5A4C", "#FFFFFF");
        syncBtn.setOnClickListener(v -> detectNetworkAndLoadBestUrl(currentEnvFilterMode));
        selectorRow.addView(syncBtn);

        mirrorBar.addView(selectorRow);
        root.addView(mirrorBar);

        // 3. Quick Search Bar
        LinearLayout searchBar = new LinearLayout(this);
        searchBar.setOrientation(LinearLayout.HORIZONTAL);
        searchBar.setGravity(Gravity.CENTER_VERTICAL);
        searchBar.setPadding(dp(10), dp(5), dp(10), dp(5));
        searchBar.setBackgroundColor(Color.WHITE);

        searchInput = new EditText(this);
        searchInput.setHint("输入书名/作者直达中文搜索，或在下方网页直接搜书…");
        searchInput.setSingleLine(true);
        searchInput.setInputType(InputType.TYPE_CLASS_TEXT);
        searchInput.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        searchInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        searchInput.setBackground(roundRect("#F2EFE9", dp(7)));
        searchInput.setPadding(dp(10), dp(5), dp(10), dp(5));
        searchInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
                    || actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE
                    || actionId == android.view.inputmethod.EditorInfo.IME_ACTION_GO
                    || (event != null && event.getKeyCode() == android.view.KeyEvent.KEYCODE_ENTER
                        && event.getAction() == android.view.KeyEvent.ACTION_DOWN)) {
                executeSearch();
                return true;
            }
            return false;
        });
        LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        searchBar.addView(searchInput, searchLp);

        formatSpinner = new Spinner(this);
        String[] formats = new String[]{"ALL", "EPUB", "PDF", "AZW3", "MOBI", "TXT", "FB2"};
        ArrayAdapter<String> fmtAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, formats);
        formatSpinner.setAdapter(fmtAdapter);
        LinearLayout.LayoutParams fmtLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fmtLp.leftMargin = dp(4);
        searchBar.addView(formatSpinner, fmtLp);

        TextView searchBtn = createActionPill("搜索", "#243E36", "#FFFFFF");
        LinearLayout.LayoutParams sBtnLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sBtnLp.leftMargin = dp(4);
        searchBtn.setOnClickListener(v -> executeSearch());
        searchBar.addView(searchBtn, sBtnLp);

        root.addView(searchBar);

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setIndeterminate(true);
        progressBar.setVisibility(View.VISIBLE);
        root.addView(progressBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)));

        // 4. Smart Auto-Login Z-Library WebView
        smartWebView = new WebView(this);
        smartWebView.setVisibility(View.VISIBLE);
        configureSmartWebView();
        root.addView(smartWebView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        updateAccountBadge();
        setContentView(root);
    }

    private void updateEnvModeButtonLabel() {
        if (currentEnvFilterMode == 0) {
            envModeBtn.setText("🤖 自动判断网络");
        } else if (currentEnvFilterMode == 1) {
            envModeBtn.setText("🌐 当前:强制官网");
        } else {
            envModeBtn.setText("🇨🇳 当前:强制直链");
        }
    }

    private void detectNetworkAndLoadBestUrl(final int forceMode) {
        progressBar.setVisibility(View.VISIBLE);

        // 1. Instant (< 1ms) local check on the UI thread so WebView starts loading immediately in 0ms!
        boolean hasLocalVpn = (forceMode == 1)
                || (forceMode == 0 && ZlibMirrorManager.hasActiveVpnInterfaceFast(this));
        isCurrentVpnActive = hasLocalVpn;

        String savedActive = ZlibMirrorManager.preferChineseMirrorUrl(settings.getActiveZlibMirror());
        final String fastStartUrl;
        if (!hasLocalVpn) {
            // Strictly use Chinese direct-connect domain when no VPN is active
            if (savedActive != null && !savedActive.isEmpty()
                    && ZlibMirrorManager.isValidMirrorCandidate(savedActive)
                    && !ZlibMirrorManager.isOfficialDomain(savedActive)) {
                fastStartUrl = savedActive;
            } else {
                fastStartUrl = "https://zh.z-lib.gd/";
            }
        } else if (forceMode == 1) {
            if (savedActive != null && !savedActive.isEmpty() && ZlibMirrorManager.isOfficialDomain(savedActive)) {
                fastStartUrl = savedActive;
            } else {
                fastStartUrl = "https://zh.z-library.sk/";
            }
        } else {
            fastStartUrl = (savedActive != null && !savedActive.isEmpty() && ZlibMirrorManager.isValidMirrorCandidate(savedActive))
                    ? savedActive
                    : "https://zh.z-library.sk/";
        }

        // Pre-populate dropdown immediately so user has instant UI and failover list at 0ms
        List<ZlibMirrorManager.MirrorNode> initialNodes = new ArrayList<>();
        if (hasLocalVpn) {
            for (String u : ZlibMirrorManager.OFFICIAL_MAIN_DOMAINS) {
                initialNodes.add(new ZlibMirrorManager.MirrorNode(ZlibMirrorManager.preferChineseMirrorUrl(u), 100L, true, true));
            }
        }
        for (String u : ZlibMirrorManager.DIRECT_CONNECT_DOMAINS) {
            String pref = ZlibMirrorManager.preferChineseMirrorUrl(u);
            if (!ZlibMirrorManager.isOfficialDomain(pref)) {
                initialNodes.add(new ZlibMirrorManager.MirrorNode(pref, 120L, true, false));
            }
        }
        populateMirrorSpinner(initialNodes, fastStartUrl);

        String initBadge = hasLocalVpn ? "🌐 正在极速打开官网节点" : "🇨🇳 正在极速打开中文直链";
        statusBanner.setText(initBadge + ": " + fastStartUrl);

        // Start loading the target Z-Library Chinese page immediately in 0ms!
        loadMirrorInWebView(fastStartUrl);

        // 2. Run background mirror verification without interrupting the already-loading WebView
        executor.execute(() -> {
            ZlibMirrorManager.syncCookiesAcrossDirectPool(settings);
            if (isCurrentVpnActive) {
                ZlibMirrorManager.syncCookiesAcrossAllPools(settings);
            }

            final ZlibMirrorManager.SyncResult res = ZlibMirrorManager.syncAndSelectFastestMirror(
                    ZlibActivity.this, settings, forceMode);
            isCurrentVpnActive = res.isVpnEnvironment;

            mainHandler.post(() -> {
                updateAccountBadge();

                // Check whether the currently loading/loaded mirror is reachable in the probe results
                boolean currentStillReachable = false;
                for (ZlibMirrorManager.MirrorNode n : res.nodes) {
                    if (n.url.equals(currentlyLoadedMirrorBase) && n.reachable) {
                        currentStillReachable = true;
                        break;
                    }
                }

                boolean userBusySearching = userHasInitiatedSearchOrNav
                        || (searchInput != null && (searchInput.hasFocus() || !searchInput.getText().toString().trim().isEmpty()));
                boolean mustSwitchForSafety = (!isCurrentVpnActive && ZlibMirrorManager.isOfficialDomain(currentlyLoadedMirrorBase));
                if (!userBusySearching && (mustSwitchForSafety || (!currentStillReachable && !res.activeMirror.isEmpty() && !res.activeMirror.equals(currentlyLoadedMirrorBase)))) {
                    populateMirrorSpinner(res.nodes, res.activeMirror);
                    statusBanner.setText(res.statusMessage);
                    loadMirrorInWebView(res.activeMirror);
                } else {
                    // Keep the already loading/loaded page untouched so we never interrupt the user!
                    String keepUrl = currentlyLoadedMirrorBase.isEmpty() ? res.activeMirror : currentlyLoadedMirrorBase;
                    populateMirrorSpinner(res.nodes, keepUrl);
                    String envBadge = isCurrentVpnActive ? "🌐 已连接官网节点" : "🇨🇳 已连接中文直链";
                    statusBanner.setText(envBadge + " · 当前网址: " + keepUrl);
                }
            });
        });
    }

    /**
     * Loads the target mirror in WebView with:
     * 1) Strict guard preventing any official VPN-only domain from loading when `!isCurrentVpnActive`.
     * 2) Simplified Chinese cookies (`remix_userlang=zh`, `selectedLanguage=zh`) pre-applied.
     * 3) Standard mainstream Chinese mobile browser HTTP headers (`Accept-Language: zh-CN,zh;q=0.98`, etc.).
     */
    private void loadMirrorInWebView(String mirrorUrl) {
        String norm = ZlibMirrorManager.preferChineseMirrorUrl(mirrorUrl);
        if (!isCurrentVpnActive && ZlibMirrorManager.isOfficialDomain(norm)) {
            norm = "https://zh.z-lib.gd/";
        }
        currentlyLoadedMirrorBase = norm;
        ZlibMirrorManager.syncCookiesToMirror(norm, settings, false);
        progressBar.setVisibility(View.VISIBLE);

        if (!isCurrentVpnActive) {
            final String targetForCover = norm;
            executor.execute(() -> ZlibMirrorManager.emitBenignDomesticCoverTraffic(targetForCover));
        }

        Map<String, String> extraHeaders = ZlibMirrorManager.buildNormalChineseBrowserHeaders("https://www.baidu.com/");
        smartWebView.loadUrl(norm, extraHeaders);
    }

    private void populateMirrorSpinner(List<ZlibMirrorManager.MirrorNode> nodes, String activeUrl) {
        availableMirrors.clear();
        List<String> labels = new ArrayList<>();
        int selectedIdx = 0;
        for (int i = 0; i < nodes.size(); i++) {
            ZlibMirrorManager.MirrorNode n = nodes.get(i);
            // In non-VPN mode, strictly exclude any official VPN-only domain from spinner
            if (!isCurrentVpnActive && n.isOfficial) {
                continue;
            }
            int itemIdx = availableMirrors.size();
            availableMirrors.add(n.url);
            boolean isCurrent = n.url.equals(activeUrl);
            String typePrefix = n.isOfficial ? "🌐[官网]" : "🇨🇳[直链]";
            String reachPrefix = (n.reachable || isCurrent) ? "✅" : "备用";
            labels.add(reachPrefix + " " + typePrefix + " " + n.url);
            if (isCurrent) {
                selectedIdx = itemIdx;
                autoFailoverIndex = itemIdx;
            }
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                ZlibActivity.this,
                android.R.layout.simple_spinner_dropdown_item,
                labels
        );
        mirrorSpinner.setAdapter(adapter);
        if (!availableMirrors.isEmpty()) {
            mirrorSpinner.setSelection(selectedIdx, false);
        }

        mirrorSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (position >= 0 && position < availableMirrors.size()) {
                    final String chosen = availableMirrors.get(position);
                    if (!chosen.equals(currentlyLoadedMirrorBase)) {
                        autoFailoverIndex = position;
                        settings.setActiveZlibMirror(chosen);
                        ZlibMirrorManager.syncCookiesToMirror(chosen, settings);
                        loadMirrorInWebView(chosen);
                    }
                }
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });
    }

    private void executeSearch() {
        final String query = searchInput.getText().toString().trim();
        final String fmt = (String) formatSpinner.getSelectedItem();
        if (query.isEmpty()) {
            Toast.makeText(this, "请输入要搜索的书名或作者", Toast.LENGTH_SHORT).show();
            return;
        }

        userHasInitiatedSearchOrNav = true;
        try {
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.hideSoftInputFromWindow(searchInput.getWindowToken(), 0);
            }
            searchInput.clearFocus();
        } catch (Throwable ignored) {
        }

        String activeMirror = currentlyLoadedMirrorBase.isEmpty()
                ? settings.getActiveZlibMirror()
                : currentlyLoadedMirrorBase;
        try {
            String currentWebUrl = smartWebView.getUrl();
            if (currentWebUrl != null && (currentWebUrl.startsWith("https://") || currentWebUrl.startsWith("http://"))) {
                android.net.Uri u = android.net.Uri.parse(currentWebUrl);
                if (u.getScheme() != null && u.getHost() != null) {
                    String webOrigin = u.getScheme() + "://" + u.getHost() + "/";
                    if (isCurrentVpnActive || !ZlibMirrorManager.isOfficialDomain(webOrigin)) {
                        activeMirror = webOrigin;
                        currentlyLoadedMirrorBase = webOrigin;
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        if (!isCurrentVpnActive && ZlibMirrorManager.isOfficialDomain(activeMirror)) {
            activeMirror = "https://zh.z-lib.gd/";
        }
        if (activeMirror == null || activeMirror.trim().isEmpty()) {
            activeMirror = "https://zh.z-lib.gd/";
        }
        String cleanBase = activeMirror.endsWith("/") ? activeMirror : (activeMirror + "/");

        try {
            String encodedQuery = URLEncoder.encode(query, "UTF-8").replace("+", "%20");
            String searchPath = "s/" + encodedQuery;
            if (fmt != null && !"ALL".equalsIgnoreCase(fmt)) {
                searchPath += "?extensions%5B%5D=" + fmt;
            }
            final String fullSearchUrl = cleanBase + searchPath;

            ZlibMirrorManager.syncCookiesToMirror(cleanBase, settings);
            progressBar.setVisibility(View.VISIBLE);
            statusBanner.setText("🔍 正在搜索《" + query + "》…");
            if (!isCurrentVpnActive) {
                final String finalMirror = cleanBase;
                executor.execute(() -> ZlibMirrorManager.emitBenignDomesticCoverTraffic(finalMirror));
            }

            String currentUrl = smartWebView.getUrl();
            if (currentUrl != null && currentUrl.startsWith(cleanBase)) {
                String jsSearch = "(function(){" +
                        "  try {" +
                        "    window.location.href = '" + fullSearchUrl.replace("\\", "\\\\").replace("'", "\\'") + "';" +
                        "    return 'OK';" +
                        "  } catch(e) { return 'ERR'; }" +
                        "})();";
                smartWebView.evaluateJavascript(jsSearch, val -> {
                    if (val == null || !val.contains("OK")) {
                        smartWebView.loadUrl(fullSearchUrl, ZlibMirrorManager.buildNormalChineseBrowserHeaders(cleanBase));
                    }
                });
            } else {
                smartWebView.loadUrl(fullSearchUrl, ZlibMirrorManager.buildNormalChineseBrowserHeaders(cleanBase));
            }
        } catch (Exception e) {
            smartWebView.loadUrl(cleanBase, ZlibMirrorManager.buildNormalChineseBrowserHeaders("https://www.baidu.com/"));
        }
    }

    private void configureSmartWebView() {
        WebSettings ws = smartWebView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setDatabaseEnabled(true);
        ws.setUseWideViewPort(true);
        ws.setLoadWithOverviewMode(true);
        ws.setSupportZoom(true);
        ws.setBuiltInZoomControls(true);
        ws.setDisplayZoomControls(false);
        ws.setSupportMultipleWindows(false);
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        ws.setUserAgentString(ZlibMirrorManager.STANDARD_CN_MOBILE_UA);

        smartWebView.addJavascriptInterface(new LiteZlibBridge(), "LiteZlibBridge");

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(smartWebView, true);

        smartWebView.setWebChromeClient(new WebChromeClient());
        smartWebView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                if (request != null && request.getUrl() != null && !isCurrentVpnActive) {
                    String reqUrl = request.getUrl().toString();
                    // In non-VPN mode, silence any subresource/tracker trying to contact blocked official or foreign domains
                    if (!request.isForMainFrame() && ZlibMirrorManager.shouldSilenceSubresourceInDirectMode(reqUrl)) {
                        return new WebResourceResponse(
                                "text/plain",
                                "UTF-8",
                                204,
                                "No Content",
                                Collections.emptyMap(),
                                new ByteArrayInputStream(new byte[0])
                        );
                    }
                }
                return super.shouldInterceptRequest(view, request);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                String currentViewUrl = view.getUrl();
                if (currentViewUrl != null && !currentViewUrl.contains("/dl/")) {
                    lastClickedBookPageUrl = currentViewUrl;
                }

                // Strict Non-VPN Guard: if a normal page link or login redirect points to a blocked official domain,
                // rewrite its host to the active direct-connect mirror (do not rewrite CDN file-storage subdomains).
                if (!isCurrentVpnActive && ZlibMirrorManager.isOfficialDomain(url)
                        && !url.contains("disposition=") && !url.contains("dln") && !url.contains("fcdn")) {
                    String safeBase = (!currentlyLoadedMirrorBase.isEmpty() && !ZlibMirrorManager.isOfficialDomain(currentlyLoadedMirrorBase))
                            ? currentlyLoadedMirrorBase
                            : "https://zh.z-lib.gd/";
                    String rewritten = rewriteUrlToDirectBase(url, safeBase);
                    ZlibMirrorManager.syncCookiesToMirror(rewritten, settings);
                    view.loadUrl(rewritten, ZlibMirrorManager.buildNormalChineseBrowserHeaders(safeBase));
                    return true;
                }

                ZlibMirrorManager.syncCookiesToMirror(url, settings);
                if (url.contains("/dl/")) {
                    // Let Chromium WebView itself send the `/dl/` request over its existing HTTP/2 + TLS session
                    // so Cloudflare never rejects it with HTTP 503! Chromium will follow the 302 redirect to the
                    // file server and invoke `onDownloadStart` (or `onReceivedHttpError` if the node is busy).
                    lastClickedDlUrl = url;
                    return false;
                }
                if (!isCurrentVpnActive && request.isForMainFrame()) {
                    executor.execute(() -> ZlibMirrorManager.emitBenignDomesticCoverTraffic(url));
                }
                return false;
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse errorResponse) {
                super.onReceivedHttpError(view, request, errorResponse);
                if (request != null && request.isForMainFrame() && errorResponse != null) {
                    int status = errorResponse.getStatusCode();
                    String reqUrl = request.getUrl() != null ? request.getUrl().toString() : "";
                    if (status >= 400 && (reqUrl.contains("/dl/") || (lastClickedDlUrl != null && !isDownloadingNow))) {
                        String targetDl = reqUrl.contains("/dl/") ? reqUrl : lastClickedDlUrl;
                        String refPage = (lastClickedBookPageUrl != null && !lastClickedBookPageUrl.isEmpty())
                                ? lastClickedBookPageUrl : view.getUrl();
                        if (view.canGoBack()) {
                            view.goBack();
                        }
                        downloadFromWebViewUrl(targetDl, refPage, null, -1L);
                    }
                }
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                super.onReceivedError(view, request, error);
                if (request != null && request.isForMainFrame() && !availableMirrors.isEmpty()) {
                    String failingUrl = request.getUrl().toString();
                    if (failingUrl.contains("/dl/")) {
                        String refPage = (lastClickedBookPageUrl != null && !lastClickedBookPageUrl.isEmpty())
                                ? lastClickedBookPageUrl : view.getUrl();
                        downloadFromWebViewUrl(failingUrl, refPage, null, -1L);
                        return;
                    }
                    int nextIdx = autoFailoverIndex + 1;
                    if (nextIdx < availableMirrors.size()) {
                        autoFailoverIndex = nextIdx;
                        String nextUrl = availableMirrors.get(nextIdx);
                        statusBanner.setText("当前直链无法连接，已自动切换至下一可用中文直链: " + nextUrl);
                        mirrorSpinner.setSelection(nextIdx, false);
                        settings.setActiveZlibMirror(nextUrl);
                        loadMirrorInWebView(nextUrl);
                    }
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                progressBar.setVisibility(View.GONE);
                if (url != null && !url.contains("/dl/")) {
                    lastClickedBookPageUrl = url;
                }
                ZlibMirrorManager.syncCookiesToMirror(url, settings);

                boolean hasValidRemixInCookieManager = false;
                String cookies = CookieManager.getInstance().getCookie(url);
                if (cookies != null) {
                    String uid = extractCookieValue(cookies, "remix_userid");
                    String ukey = extractCookieValue(cookies, "remix_userkey");
                    if (uid != null && ukey != null && !uid.isEmpty() && !ukey.isEmpty()
                            && !"deleted".equalsIgnoreCase(uid) && !"deleted".equalsIgnoreCase(ukey)) {
                        settings.saveZlibAuthTokens(uid, ukey);
                        ZlibMirrorManager.syncCookiesAcrossDirectPool(settings);
                        if (isCurrentVpnActive) {
                            ZlibMirrorManager.syncCookiesAcrossAllPools(settings);
                        }
                        hasValidRemixInCookieManager = true;
                    }
                }

                boolean isOff = ZlibMirrorManager.isOfficialDomain(url);
                String typeTag = isOff ? "🌐[官网]" : "🇨🇳[直链]";
                if (hasValidRemixInCookieManager) {
                    statusBanner.setText("✅ 已登录 " + typeTag + " · " + currentlyLoadedMirrorBase);
                    updateAccountBadge();
                } else {
                    statusBanner.setText("已连接 " + typeTag + ": " + currentlyLoadedMirrorBase);
                }

                injectChineseLocaleAndDownloadInterceptorScript(view);
                boolean isSearchOrBookPage = (url != null && (url.contains("/s/") || url.contains("/book/") || url.contains("?q=")));
                if (settings.hasZlibCredentials() && !hasValidRemixInCookieManager && !isSearchOrBookPage) {
                    injectAutoLoginScript(view, false);
                }
            }
        });

        smartWebView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition, String mimetype, long contentLength) {
                String refPage = (lastClickedBookPageUrl != null && !lastClickedBookPageUrl.isEmpty())
                        ? lastClickedBookPageUrl : smartWebView.getUrl();
                downloadFromWebViewUrl(url, refPage, contentDisposition, contentLength);
            }
        });
    }

    private String rewriteUrlToDirectBase(String originalUrl, String directBase) {
        try {
            URI orig = new URI(originalUrl);
            URI base = new URI(ZlibMirrorManager.normalizeBaseUrl(directBase));
            String path = orig.getRawPath();
            String query = orig.getRawQuery();
            StringBuilder sb = new StringBuilder();
            sb.append(base.getScheme() != null ? base.getScheme() : "https")
              .append("://")
              .append(base.getHost());
            if (path != null && !path.isEmpty()) {
                if (!path.startsWith("/")) sb.append("/");
                sb.append(path);
            } else {
                sb.append("/");
            }
            if (query != null && !query.isEmpty()) {
                sb.append("?").append(query);
            }
            return sb.toString();
        } catch (Exception e) {
            return directBase;
        }
    }

    /**
     * Shows or updates a prominent Live Download Progress Dialog on the UI thread.
     */
    private void cancelCurrentBookDownload() {
        isDownloadCancelledByUser = true;
        activeDownloadSessionId++;
        isDownloadingNow = false;
        lastClickedDlUrl = null;
        ZlibMirrorManager.cancelActiveDownloads();
        mainHandler.post(() -> {
            try {
                if (smartWebView != null) {
                    smartWebView.evaluateJavascript(
                            "(function(){ try { window.__liteDownloadCancelled = true; if (window.__liteActiveXhr) { window.__liteActiveXhr.abort(); } } catch(e){} })();",
                            null);
                }
            } catch (Throwable ignored) {}
            dismissDownloadDialog();
            if (progressBar != null) {
                progressBar.setVisibility(View.GONE);
            }
            if (statusBanner != null) {
                statusBanner.setText("⏹ 已取消当前书籍下载");
            }
            Toast.makeText(ZlibActivity.this, "已取消下载", Toast.LENGTH_SHORT).show();
        });
    }

    private void showOrUpdateDownloadDialog(final String titleText, final long downloadedBytes, final long totalBytes) {
        mainHandler.post(() -> {
            if (isFinishing() || isDestroyed() || isDownloadCancelledByUser) return;
            if (downloadDialog == null || !downloadDialog.isShowing()) {
                LinearLayout box = new LinearLayout(ZlibActivity.this);
                box.setOrientation(LinearLayout.VERTICAL);
                box.setPadding(dp(20), dp(16), dp(20), dp(10));

                downloadDialogStatus = new TextView(ZlibActivity.this);
                downloadDialogStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
                downloadDialogStatus.setTextColor(Color.parseColor("#1F2328"));
                box.addView(downloadDialogStatus);

                downloadDialogBar = new ProgressBar(ZlibActivity.this, null, android.R.attr.progressBarStyleHorizontal);
                downloadDialogBar.setMax(100);
                LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(8));
                barLp.topMargin = dp(12);
                barLp.bottomMargin = dp(8);
                box.addView(downloadDialogBar, barLp);

                downloadDialogPercent = new TextView(ZlibActivity.this);
                downloadDialogPercent.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
                downloadDialogPercent.setTypeface(Typeface.DEFAULT_BOLD);
                downloadDialogPercent.setTextColor(Color.parseColor("#2D5A4C"));
                downloadDialogPercent.setGravity(Gravity.END);
                box.addView(downloadDialogPercent);

                downloadDialog = new AlertDialog.Builder(ZlibActivity.this)
                        .setTitle("📥 正在下载并加密入库")
                        .setView(box)
                        .setCancelable(false)
                        .setNegativeButton("取消下载", (d, w) -> cancelCurrentBookDownload())
                        .setNeutralButton("后台下载", (d, w) -> d.dismiss())
                        .create();
                downloadDialog.show();
                try {
                    android.widget.Button cancelBtn = downloadDialog.getButton(AlertDialog.BUTTON_NEGATIVE);
                    if (cancelBtn != null) {
                        cancelBtn.setTextColor(Color.parseColor("#C93B2B"));
                    }
                } catch (Throwable ignored) {}
            }

            downloadDialogStatus.setText(titleText);
            if (totalBytes > 0) {
                downloadDialogBar.setIndeterminate(false);
                int pct = (int) Math.min(100L, (downloadedBytes * 100L) / totalBytes);
                downloadDialogBar.setProgress(pct);
                downloadDialogPercent.setText(String.format(Locale.US, "%.2f MB / %.2f MB (%d%%)",
                        downloadedBytes / (1024.0 * 1024.0),
                        totalBytes / (1024.0 * 1024.0),
                        pct));
            } else if (downloadedBytes > 0) {
                downloadDialogBar.setIndeterminate(true);
                downloadDialogPercent.setText(String.format(Locale.US, "已下载 %.2f MB…",
                        downloadedBytes / (1024.0 * 1024.0)));
            } else {
                downloadDialogBar.setIndeterminate(true);
                downloadDialogPercent.setText("正在连接文件服务器…");
            }
        });
    }

    private void dismissDownloadDialog() {
        mainHandler.post(() -> {
            try {
                if (downloadDialog != null && downloadDialog.isShowing()) {
                    downloadDialog.dismiss();
                }
            } catch (Exception ignored) {
            }
        });
    }

    public class LiteZlibBridge {
        @JavascriptInterface
        public void onTokensCaptured(final String userId, final String userKey) {
            if (userId != null && !userId.isEmpty() && userKey != null && !userKey.isEmpty()
                    && !"deleted".equalsIgnoreCase(userId) && !"deleted".equalsIgnoreCase(userKey)) {
                settings.saveZlibAuthTokens(userId, userKey);
                mainHandler.post(() -> {
                    String currentUrl = smartWebView.getUrl();
                    if (currentUrl != null) {
                        ZlibMirrorManager.syncCookiesToMirror(currentUrl, settings);
                    }
                    ZlibMirrorManager.syncCookiesAcrossDirectPool(settings);
                    if (isCurrentVpnActive) {
                        ZlibMirrorManager.syncCookiesAcrossAllPools(settings);
                    }
                    CookieManager.getInstance().flush();
                    statusBanner.setText("✅ Z-Library 账号已自动登录并同步至中文直链节点");
                    updateAccountBadge();
                });
            }
        }

        @JavascriptInterface
        public void onStatus(final String message) {
            mainHandler.post(() -> {
                if (message != null && !message.isEmpty()) {
                    String cleanMsg = message.replaceAll("\\s*\\([^)]*@[^)]*\\)", "");
                    String savedEm = settings.getZlibEmail();
                    if (savedEm != null && !savedEm.isEmpty()) {
                        cleanMsg = cleanMsg.replace("(" + savedEm + ")", "").replace(savedEm, "");
                    }
                    statusBanner.setText(cleanMsg.trim());
                }
            });
        }

        @JavascriptInterface
        public void onDownloadLinkClicked(final String dlUrl, final String pageTitle) {
            isDownloadCancelledByUser = false;
            ZlibMirrorManager.resetDownloadCancelFlag();
            final long sessionId = ++activeDownloadSessionId;
            if (pageTitle != null && !pageTitle.trim().isEmpty()) {
                lastClickedBookTitle = pageTitle.trim();
            }
            if (dlUrl != null && !dlUrl.isEmpty()) {
                lastClickedDlUrl = dlUrl;
            }
            mainHandler.post(() -> {
                try {
                    smartWebView.evaluateJavascript("window.__liteDownloadCancelled = false;", null);
                } catch (Throwable ignored) {}
                String cur = smartWebView.getUrl();
                if (cur != null && !cur.contains("/dl/")) {
                    lastClickedBookPageUrl = cur;
                }
            });
            showOrUpdateDownloadDialog("准备下载: " + (pageTitle != null ? pageTitle : "") + "\n正在连接文件服务器…", 0L, -1L);
            // Watchdog: if WebView didn't fire `onDownloadStart` within 3.2s (e.g. silent 503 on current node),
            // automatically start multi-mirror failover download!
            if (dlUrl != null && !dlUrl.isEmpty()) {
                mainHandler.postDelayed(() -> {
                    if (!isDownloadCancelledByUser && sessionId == activeDownloadSessionId && !isDownloadingNow && dlUrl.equals(lastClickedDlUrl)) {
                        downloadFromWebViewUrl(dlUrl, lastClickedBookPageUrl, null, -1L);
                    }
                }, 3200);
            }
        }

        @JavascriptInterface
        public void onDownloadProgress(final long loaded, final long total, final String bookTitle) {
            showOrUpdateDownloadDialog("正在下载并准备 AES-256 加密入库:\n" + (bookTitle != null ? bookTitle : ""), loaded, total);
        }

        @JavascriptInterface
        public void onBlobDownloaded(final String base64Data, final String fileName, final String pageTitle) {
            if (isDownloadCancelledByUser) return;
            executor.execute(() -> {
                try {
                    if (isDownloadCancelledByUser) return;
                    showOrUpdateDownloadDialog("正在进行 AES-256 专有加密并存入私密书库…", 100L, 100L);
                    String pureB64 = base64Data;
                    int comma = pureB64.indexOf(',');
                    if (comma >= 0) {
                        pureB64 = pureB64.substring(comma + 1);
                    }
                    byte[] rawBytes = Base64.decode(pureB64, Base64.DEFAULT);
                    Book book = db.importStreamToEncryptedVault(
                            new ByteArrayInputStream(rawBytes),
                            fileName,
                            pageTitle,
                            "Z-Library 下载",
                            "🔒 Z-Library 加密专有库"
                    );
                    isDownloadingNow = false;
                    dismissDownloadDialog();
                    mainHandler.post(() -> {
                        statusBanner.setText("✅ 已加密存入私有专有书库: " + (book != null ? book.title : fileName));
                        showDownloadCompleteDialog(book);
                    });
                } catch (final Exception e) {
                    isDownloadingNow = false;
                    dismissDownloadDialog();
                    mainHandler.post(() -> Toast.makeText(ZlibActivity.this, "加密入库失败: " + e.getMessage(), Toast.LENGTH_LONG).show());
                }
            });
        }
    }

    /**
     * Enforces Simplified Chinese (`zh`) UI on any Z-Library mirror and installs the download click interceptor:
     * 1. Sets `remix_userlang=zh`, `selectedLanguage=zh`, `lang=zh` cookies on both host and root domain.
     * 2. If the mirror still rendered in English/non-Chinese on first load, automatically triggers the Chinese language switch.
     * 3. Strips `target="_blank"` on download buttons and shows the live download progress dialog immediately.
     */
    private void injectChineseLocaleAndDownloadInterceptorScript(WebView view) {
        String js = "(function(){" +
                "  try {" +
                "    var hostParts = location.hostname.split('.');" +
                "    var rootDomain = hostParts.length >= 2 ? hostParts.slice(-2).join('.') : location.hostname;" +
                "    var exp = '; path=/; max-age=31536000; SameSite=Lax';" +
                "    document.cookie = 'remix_userlang=zh' + exp;" +
                "    document.cookie = 'selectedLanguage=zh' + exp;" +
                "    document.cookie = 'lang=zh' + exp;" +
                "    document.cookie = 'remix_userlang=zh; domain=.' + rootDomain + exp;" +
                "    document.cookie = 'selectedLanguage=zh; domain=.' + rootDomain + exp;" +
                "    var htmlLang = (document.documentElement.getAttribute('lang') || '').toLowerCase();" +
                "    var bodyTxt = document.body ? (document.body.innerText || '').slice(0, 600) : '';" +
                "    var hasChineseText = /[\\u4e00-\\u9fa5]{2,}/.test(bodyTxt);" +
                "    if (!hasChineseText && htmlLang.indexOf('zh') === -1 && !sessionStorage.getItem('__liteZhSwitched')) {" +
                "      sessionStorage.setItem('__liteZhSwitched', '1');" +
                "      var zhLink = document.querySelector('a[href*=\"remix_userlang=zh\"], a[data-lang=\"zh\"], a[href*=\"?lang=zh\"], [ onclick*=\"zh\" ]');" +
                "      if (zhLink && zhLink.href) {" +
                "        location.replace(zhLink.href);" +
                "        return;" +
                "      } else {" +
                "        location.reload();" +
                "        return;" +
                "      }" +
                "    }" +
                "  } catch(e) {}" +
                "  try {" +
                "    var savedEm = '" + settings.getZlibEmail().replace("\\", "\\\\").replace("'", "\\'") + "';" +
                "    var savedUser = savedEm.indexOf('@') > 0 ? savedEm.split('@')[0] : savedEm;" +
                "    var hideAcctNodes = document.querySelectorAll('header a, header span, .user-name, .user-email, .profile-name, [class*=\"user\"], [class*=\"profile\"]');" +
                "    for (var i = 0; i < hideAcctNodes.length; i++) {" +
                "      var el = hideAcctNodes[i];" +
                "      if (el && el.children.length === 0 && el.textContent) {" +
                "        var txt = el.textContent.trim();" +
                "        if ((savedEm && txt.indexOf(savedEm) !== -1) || (savedUser && savedUser.length >= 2 && txt === savedUser) || /^[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}$/.test(txt)) {" +
                "          el.textContent = '已登录';" +
                "        }" +
                "      }" +
                "    }" +
                "  } catch(e) {}" +
                "  if (window.__liteDlHookInstalled) return;" +
                "  window.__liteDlHookInstalled = true;" +
                "  document.addEventListener('click', function(e){" +
                "    var a = e.target ? e.target.closest('a') : null;" +
                "    if (!a) return;" +
                "    var href = a.getAttribute('href') || '';" +
                "    var cls = a.className || '';" +
                "    if (href.indexOf('/dl/') !== -1 || cls.indexOf('addDownloadedBook') !== -1 || cls.indexOf('dlButton') !== -1 || a.hasAttribute('download')) {" +
                "      a.removeAttribute('target');" +
                "      var bookTitle = document.title ? document.title.replace(/\\s*[-|].*$/, '').trim() : 'Z-Library 书籍';" +
                "      if (window.LiteZlibBridge && window.LiteZlibBridge.onDownloadLinkClicked) {" +
                "        window.LiteZlibBridge.onDownloadLinkClicked(a.href || href, bookTitle);" +
                "      }" +
                "    }" +
                "  }, true);" +
                "})();";
        view.evaluateJavascript(js, null);
    }

    private void injectAutoLoginScript(WebView view, boolean forceRetryNow) {
        String email = settings.getZlibEmail().replace("\\", "\\\\").replace("'", "\\'");
        String pass = settings.getZlibPassword().replace("\\", "\\\\").replace("'", "\\'");
        String savedUid = settings.getZlibRemixUserId().replace("'", "");
        String savedUkey = settings.getZlibRemixUserKey().replace("'", "");

        String js = "(function(){" +
                "  try {" +
                "    var email = '" + email + "';" +
                "    var pass = '" + pass + "';" +
                "    var savedUid = '" + savedUid + "';" +
                "    var savedUkey = '" + savedUkey + "';" +
                "    var forceRetry = " + (forceRetryNow ? "true" : "false") + ";" +
                "    if (forceRetry) { sessionStorage.removeItem('__liteLoginAttempts'); window.__liteFormSubmitted = false; }" +
                "    var hostParts = location.hostname.split('.');" +
                "    var rootDomain = hostParts.length >= 2 ? hostParts.slice(-2).join('.') : location.hostname;" +
                "    function applyRemixCookies(uid, ukey) {" +
                "      if (!uid || !ukey) return;" +
                "      var exp = '; path=/; max-age=31536000; SameSite=Lax';" +
                "      document.cookie = 'remix_userid=' + uid + exp;" +
                "      document.cookie = 'remix_userkey=' + ukey + exp;" +
                "      document.cookie = 'remix_userlang=zh' + exp;" +
                "      document.cookie = 'remix_userid=' + uid + '; domain=.' + rootDomain + exp;" +
                "      document.cookie = 'remix_userkey=' + ukey + '; domain=.' + rootDomain + exp;" +
                "      document.cookie = 'remix_userlang=zh; domain=.' + rootDomain + exp;" +
                "      if (window.LiteZlibBridge && window.LiteZlibBridge.onTokensCaptured) {" +
                "        window.LiteZlibBridge.onTokensCaptured(String(uid), String(ukey));" +
                "      }" +
                "    }" +
                "    if (savedUid && savedUkey) {" +
                "      applyRemixCookies(savedUid, savedUkey);" +
                "    }" +
                "    function findHeaderLoginButton() {" +
                "      var candidates = document.querySelectorAll('a, button, span');" +
                "      for (var i = 0; i < candidates.length; i++) {" +
                "        var el = candidates[i];" +
                "        var txt = (el.innerText || el.textContent || '').trim();" +
                "        if (txt === '登录' || txt === '登入' || txt === 'Sign in' || txt === 'Log in' || txt === 'Login' || txt === 'Iniciar sesión') {" +
                "          var rect = el.getBoundingClientRect();" +
                "          if (rect.width > 0 && rect.height > 0 && rect.top < 200) {" +
                "            return el;" +
                "          }" +
                "        }" +
                "      }" +
                "      return null;" +
                "    }" +
                "    function setInputValue(input, val) {" +
                "      if (!input) return;" +
                "      try {" +
                "        var nativeSetter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;" +
                "        if (nativeSetter) { nativeSetter.call(input, val); } else { input.value = val; }" +
                "      } catch(e) { input.value = val; }" +
                "      input.setAttribute('value', val);" +
                "      input.dispatchEvent(new Event('input', {bubbles:true}));" +
                "      input.dispatchEvent(new Event('change', {bubbles:true}));" +
                "    }" +
                "    function submitZlibLogin() {" +
                "      var headerLoginBtn = findHeaderLoginButton();" +
                "      if (!headerLoginBtn && document.cookie.indexOf('remix_userid=') !== -1 && !forceRetry) {" +
                "        if (window.LiteZlibBridge) window.LiteZlibBridge.onStatus('✅ Z-Library 已成功登录 · 点击书籍即可直接下载');" +
                "        return;" +
                "      }" +
                "      var attempts = parseInt(sessionStorage.getItem('__liteLoginAttempts') || '0', 10);" +
                "      if (attempts >= 4 && !forceRetry) return;" +
                "      var emEl = document.querySelector('form input[name=\"email\"], input[name=\"email\"], input[type=\"email\"]');" +
                "      var pwEl = document.querySelector('form input[name=\"password\"], input[name=\"password\"], input[type=\"password\"]');" +
                "      if (!emEl || !pwEl) {" +
                "        if (headerLoginBtn && !window.__liteClickedHeaderLogin) {" +
                "          window.__liteClickedHeaderLogin = true;" +
                "          headerLoginBtn.click();" +
                "          setTimeout(submitZlibLogin, 450);" +
                "        }" +
                "        return;" +
                "      }" +
                "      if (window.__liteFormSubmitted) return;" +
                "      window.__liteFormSubmitted = true;" +
                "      sessionStorage.setItem('__liteLoginAttempts', String(attempts + 1));" +
                "      if (window.LiteZlibBridge) window.LiteZlibBridge.onStatus('⚡ 正在自动登录当前 Z-Library 中文节点 (' + location.hostname + ')…');" +
                "      setInputValue(emEl, email);" +
                "      setInputValue(pwEl, pass);" +
                "      var form = emEl.closest('form');" +
                "      var params = new URLSearchParams();" +
                "      if (form) {" +
                "        try {" +
                "          var fd = new FormData(form);" +
                "          fd.forEach(function(v, k){ params.set(k, v); });" +
                "        } catch(e) {}" +
                "      }" +
                "      params.set('email', email);" +
                "      params.set('password', pass);" +
                "      if (!params.has('action')) params.set('action', 'login');" +
                "      if (!params.has('isModal')) params.set('isModal', 'true');" +
                "      if (!params.has('site_mode')) params.set('site_mode', 'books');" +
                "      params.set('isSingleLogin', '1');" +
                "      params.set('gg_json_mode', '1');" +
                "      var actionUrl = (form && form.getAttribute('action')) ? form.getAttribute('action') : '/rpc.php';" +
                "      fetch(actionUrl, {" +
                "        method: 'POST'," +
                "        headers: {'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8', 'X-Requested-With': 'XMLHttpRequest'}," +
                "        body: params.toString()," +
                "        credentials: 'include'" +
                "      }).then(function(r){ return r.text(); }).then(function(txt){" +
                "        try {" +
                "          var d = JSON.parse(txt);" +
                "          var resp = d.response || d.user || d;" +
                "          var uid = resp.user_id || resp.id || resp.remix_userid;" +
                "          var ukey = resp.user_key || resp.remix_userkey;" +
                "          if (uid && ukey) { applyRemixCookies(uid, ukey); }" +
                "          if (resp.forceRedirection) { location.href = resp.forceRedirection; return; }" +
                "        } catch(e) {}" +
                "        if (form) {" +
                "          var btn = form.querySelector('button[type=\"submit\"], input[type=\"submit\"]');" +
                "          if (btn) btn.click();" +
                "        }" +
                "        setTimeout(function(){ location.reload(); }, 500);" +
                "      }).catch(function(){" +
                "        if (form) {" +
                "          var btn = form.querySelector('button[type=\"submit\"], input[type=\"submit\"], button');" +
                "          if (btn) btn.click(); else form.submit();" +
                "        }" +
                "      });" +
                "    }" +
                "    setTimeout(submitZlibLogin, 250);" +
                "  } catch(e) {}" +
                "})();";
        view.evaluateJavascript(js, null);
    }

    /**
     * Streams the book from Z-Library with real-time progress reporting, encrypts it with AES-256
     * into `.litereader_vault/v_<uuid>.lrv`, and deletes the temporary download file.
     */
    private void downloadFromWebViewUrl(final String downloadUrl, final String refererUrl,
                                        final String contentDisposition, final long expectedLength) {
        if (isDownloadingNow) return;
        isDownloadCancelledByUser = false;
        ZlibMirrorManager.resetDownloadCancelFlag();
        final long mySessionId = ++activeDownloadSessionId;
        isDownloadingNow = true;
        lastClickedDlUrl = null;
        progressBar.setVisibility(View.VISIBLE);
        statusBanner.setText("正在下载书籍并加密存入专有书库…");
        showOrUpdateDownloadDialog("正在下载书籍并准备加密存入专有文件夹…", 0L, expectedLength);

        final String cookiesDl = CookieManager.getInstance().getCookie(downloadUrl);
        final String cookiesRef = refererUrl != null ? CookieManager.getInstance().getCookie(refererUrl) : null;

        executor.execute(() -> {
            try {
                File tempDlDir = new File(getCacheDir(), ".temp_zlib_dl");
                if (!tempDlDir.exists()) tempDlDir.mkdirs();

                String cookieHeader = ZlibMirrorManager.buildCookieHeader(settings);
                String mergedWebCookies = "";
                if (cookiesRef != null && !cookiesRef.isEmpty()) {
                    mergedWebCookies = cookiesRef;
                }
                if (cookiesDl != null && !cookiesDl.isEmpty() && !cookiesDl.equals(cookiesRef)) {
                    mergedWebCookies = mergedWebCookies.isEmpty() ? cookiesDl : (mergedWebCookies + "; " + cookiesDl);
                }
                if (!mergedWebCookies.isEmpty()) {
                    cookieHeader = mergedWebCookies + "; " + cookieHeader;
                }

                final File tempFile = ZlibMirrorManager.downloadUrlToFile(
                        downloadUrl, lastClickedBookTitle, "epub", tempDlDir, cookieHeader, refererUrl,
                        contentDisposition, settings,
                        new ZlibMirrorManager.ProgressCallback() {
                            @Override
                            public void onProgress(long downloadedBytes, long totalBytes) {
                                if (isCancelled()) return;
                                long total = totalBytes > 0 ? totalBytes : expectedLength;
                                showOrUpdateDownloadDialog("正在下载书籍到本地加密缓冲…", downloadedBytes, total);
                            }

                            @Override
                            public boolean isCancelled() {
                                return isDownloadCancelledByUser || mySessionId != activeDownloadSessionId;
                            }
                        });

                if (isDownloadCancelledByUser || mySessionId != activeDownloadSessionId) {
                    if (tempFile != null && tempFile.exists()) tempFile.delete();
                    return;
                }

                showOrUpdateDownloadDialog("正在使用 AES-256 加密并移入专有文件夹 (.litereader_vault)…", 100L, 100L);
                String preferredTitle = (lastClickedBookTitle != null && !lastClickedBookTitle.trim().isEmpty())
                        ? lastClickedBookTitle.trim()
                        : null;
                final Book imported = db.importAndEncryptFileToVault(
                        tempFile, preferredTitle, "Z-Library 下载", "🔒 Z-Library 加密专有库", true);

                isDownloadingNow = false;
                dismissDownloadDialog();

                mainHandler.post(() -> {
                    progressBar.setVisibility(View.GONE);
                    if (imported != null) {
                        statusBanner.setText("✅ 已加密入库《" + imported.title + "》(" + imported.format + ")");
                        showDownloadCompleteDialog(imported);
                    } else {
                        statusBanner.setText("下载完成，但未能解析书籍格式");
                    }
                });
            } catch (final Exception e) {
                isDownloadingNow = false;
                dismissDownloadDialog();
                if (isDownloadCancelledByUser || mySessionId != activeDownloadSessionId || (e instanceof InterruptedException)) {
                    mainHandler.post(() -> {
                        progressBar.setVisibility(View.GONE);
                        statusBanner.setText("⏹ 已取消当前书籍下载");
                    });
                    return;
                }
                mainHandler.post(() -> {
                    progressBar.setVisibility(View.GONE);
                    statusBanner.setText("下载未完成: " + e.getMessage());
                    Toast.makeText(ZlibActivity.this,
                            "下载未完成 (" + e.getMessage() + ")",
                            Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void showDownloadCompleteDialog(final Book book) {
        if (book == null || isFinishing()) return;
        new AlertDialog.Builder(this)
                .setTitle("✅ 下载并加密入库成功")
                .setMessage("《" + book.title + "》(" + book.format + ")\n\n"
                        + "• 已通过 AES-256 加密存入私有专有库 (.litereader_vault)\n"
                        + "• 手机内其他软件无法扫描或读取该书籍\n"
                        + "• 如需分享给好友，可在书架点击该书右侧「📤 分享」自动解密导出")
                .setPositiveButton("📖 立即阅读", (d, w) -> {
                    Intent intent = new Intent(ZlibActivity.this, ReaderActivity.class);
                    intent.putExtra("book_id", book.id);
                    startActivity(intent);
                })
                .setNegativeButton("继续搜书", null)
                .show();
    }

    private void showCredentialsDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(12), dp(20), dp(4));

        TextView tip = new TextView(this);
        tip.setText("只需填写一次 Z-Library 账号与密码：\n"
                + "• 自动连接中文直链或官网节点\n"
                + "• 登录凭证自动同步到所有节点，无需重复输入");
        tip.setTextColor(Color.parseColor("#4B5563"));
        tip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        tip.setPadding(0, 0, 0, dp(10));
        box.addView(tip);

        boolean alreadyLogged = settings.hasZlibCredentials() || settings.hasZlibAuthTokens();
        final EditText emailInput = new EditText(this);
        emailInput.setHint(alreadyLogged ? "当前已登录（如需更换账号请输入新邮箱）" : "Z-Library 注册邮箱");
        emailInput.setSingleLine(true);
        box.addView(emailInput);

        final EditText passInput = new EditText(this);
        passInput.setHint(alreadyLogged ? "如需更换账号请输入新密码" : "Z-Library 登录密码");
        passInput.setSingleLine(true);
        passInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        box.addView(passInput);

        new AlertDialog.Builder(this)
                .setTitle("🔐 Z-Library 永久免填自动登录")
                .setView(box)
                .setPositiveButton("保存并自动登录", (d, w) -> {
                    String em = emailInput.getText().toString().trim();
                    String pw = passInput.getText().toString().trim();
                    if (em.isEmpty() && pw.isEmpty() && settings.hasZlibCredentials()) {
                        em = settings.getZlibEmail();
                        pw = settings.getZlibPassword();
                    } else {
                        settings.saveZlibCredentials(em, pw);
                    }
                    updateAccountBadge();
                    if (!em.isEmpty() && !pw.isEmpty()) {
                        statusBanner.setText("正在后台验证并同步登录凭证至当前中文节点…");
                        progressBar.setVisibility(View.VISIBLE);
                        injectAutoLoginScript(smartWebView, true);
                        executor.execute(() -> {
                            String active = currentlyLoadedMirrorBase.isEmpty()
                                    ? settings.getActiveZlibMirror()
                                    : currentlyLoadedMirrorBase;
                            final boolean ok = ZlibMirrorManager.performAutoLogin(active, settings);
                            if (ok) {
                                ZlibMirrorManager.syncCookiesAcrossDirectPool(settings);
                                if (isCurrentVpnActive) {
                                    ZlibMirrorManager.syncCookiesAcrossAllPools(settings);
                                }
                            }
                            mainHandler.post(() -> {
                                progressBar.setVisibility(View.GONE);
                                updateAccountBadge();
                                ZlibMirrorManager.syncCookiesToMirror(active, settings);
                                smartWebView.loadUrl(active, ZlibMirrorManager.buildNormalChineseBrowserHeaders("https://www.baidu.com/"));
                                Toast.makeText(ZlibActivity.this,
                                        ok ? "✅ 已成功登录并同步至中文直链" : "已保存登录设置并在当前网页自动登录",
                                        Toast.LENGTH_LONG).show();
                            });
                        });
                    }
                })
                .setNeutralButton("清除登录状态", (d, w) -> {
                    settings.saveZlibCredentials("", "");
                    settings.saveZlibAuthTokens("", "");
                    CookieManager.getInstance().removeAllCookies(null);
                    updateAccountBadge();
                    Toast.makeText(this, "已清除保存的登录信息", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void updateAccountBadge() {
        if (settings.hasZlibCredentials() || settings.hasZlibAuthTokens()) {
            accountBadge.setText("🔐 已登录");
        } else {
            accountBadge.setText("🔑 登录设置(填一次)");
        }
    }

    private String maskEmail(String email) {
        if (email == null || email.isEmpty()) return "";
        int at = email.indexOf('@');
        if (at <= 2) return email;
        return email.substring(0, 2) + "***" + email.substring(at);
    }

    private String extractCookieValue(String cookies, String key) {
        if (cookies == null) return null;
        String[] parts = cookies.split(";");
        for (String p : parts) {
            String t = p.trim();
            if (t.startsWith(key + "=")) {
                return t.substring((key + "=").length()).trim();
            }
        }
        return null;
    }

    private TextView createActionPill(String text, String bgHex, String fgHex) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(Color.parseColor(fgHex));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setSingleLine(true);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(9), dp(5), dp(9), dp(5));
        tv.setBackground(roundRect(bgHex, dp(6)));
        return tv;
    }

    private TextView createEqualWeightPill(String text, String bgHex, String fgHex) {
        TextView tv = createActionPill(text, bgHex, fgHex);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        return tv;
    }

    private GradientDrawable roundRect(String colorHex, int radiusPx) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(Color.parseColor(colorHex));
        gd.setCornerRadius(radiusPx);
        return gd;
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    @Override
    public void onBackPressed() {
        if (smartWebView != null && smartWebView.canGoBack()) {
            smartWebView.goBack();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        dismissDownloadDialog();
        executor.shutdownNow();
    }
}
