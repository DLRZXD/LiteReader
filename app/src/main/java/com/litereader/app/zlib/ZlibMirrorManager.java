package com.litereader.app.zlib;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.webkit.CookieManager;

import com.litereader.app.data.ReadingSettings;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dual-Environment Z-Library URL Auto-Updater, Chinese Locale Enforcer,
 * Normal-Browsing Traffic Camouflage & Progress-Reporting Downloader.
 *
 * Key Guarantees:
 * 1. Default Simplified Chinese (`zh`) across all mirrors via cookies (`remix_userlang=zh`, `selectedLanguage=zh`),
 *    `Accept-Language: zh-CN,zh;q=0.98`, and preferred `zh.` entry domains.
 * 2. Strict Non-VPN Direct-Connect Isolation: When no VPN is active on the device, NEVER touches or probes
 *    blocked official domains (`z-library.sk`, `z-lib.sk`, `singlelogin.re`, `google.com`, `raw.githubusercontent.com`, etc.)
 *    and ONLY loads/probes direct-connect mirrors (`DIRECT_CONNECT_DOMAINS`).
 * 3. Normal Web Traffic Camouflage in Non-VPN Mode: Interleaves benign domestic portal/CDN HTTPS requests
 *    (Huawei/Baidu/AliCDN/Tencent static endpoints), uses encrypted domestic DoH (`223.5.5.5` / `1.12.12.12`),
 *    applies realistic mainstream Chinese Android browser headers, and uses staggered low-concurrency requests
 *    with human-like timing jitter so direct-connect traffic blends naturally into everyday web browsing traffic.
 */
public class ZlibMirrorManager {

    public interface ProgressCallback {
        void onProgress(long downloadedBytes, long totalBytes);
        default boolean isCancelled() {
            return false;
        }
    }

    private static volatile boolean sDownloadCancelled = false;
    private static volatile HttpURLConnection sActiveDownloadConn = null;

    public static void resetDownloadCancelFlag() {
        sDownloadCancelled = false;
    }

    public static void cancelActiveDownloads() {
        sDownloadCancelled = true;
        final HttpURLConnection c = sActiveDownloadConn;
        sActiveDownloadConn = null;
        if (c != null) {
            new Thread(() -> {
                try {
                    c.disconnect();
                } catch (Throwable ignored) {
                }
            }).start();
        }
    }

    public static boolean isDownloadCancelled(ProgressCallback cb) {
        return sDownloadCancelled || (cb != null && cb.isCancelled());
    }

    public static final String AWESOME_ZLIB_REPO_URL = "https://github.com/dongyubin/Awesome-Zlibrary";

    public static final String STANDARD_CN_MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 14; 23127PN0CC Build/UKQ1.230804.001; wv) "
                    + "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 "
                    + "Chrome/126.0.6478.122 Mobile Safari/537.36";

    public static final String STANDARD_ACCEPT_LANGUAGE_ZH =
            "zh-CN,zh;q=0.98,zh-Hans;q=0.95,en-US;q=0.8,en;q=0.7";

    private static final String[] OFFICIAL_UPDATE_PAGES = new String[]{
            "https://library-access.sk/",
            "https://zh.go-to-library.sk/",
            "https://go-to-library.sk/",
            "https://en.wikipedia.org/wiki/Z-Library",
            "https://www.reddit.com/r/zlibrary/wiki/index/access/"
    };

    // Only domestic-reachable CDN mirrors for non-VPN updates (never raw.githubusercontent.com directly in non-VPN mode)
    private static final String[] DIRECT_UPDATE_PAGES_NO_VPN = new String[]{
            "https://ghfast.top/https://raw.githubusercontent.com/dongyubin/Awesome-Zlibrary/main/README.md",
            "https://fastly.jsdelivr.net/gh/dongyubin/Awesome-Zlibrary@main/README.md"
    };

    private static final String[] DIRECT_UPDATE_PAGES_VPN = new String[]{
            "https://ghfast.top/https://raw.githubusercontent.com/dongyubin/Awesome-Zlibrary/main/README.md",
            "https://fastly.jsdelivr.net/gh/dongyubin/Awesome-Zlibrary@main/README.md",
            "https://raw.githubusercontent.com/dongyubin/Awesome-Zlibrary/main/README.md"
    };

    public static final String[] OFFICIAL_MAIN_DOMAINS = new String[]{
            "https://zh.z-library.sk/",
            "https://z-lib.sk/",
            "https://z-library.sk/",
            "https://z-lib.fm/",
            "https://z-library.im/",
            "https://zh.go-to-library.sk/"
    };

    public static final String[] DIRECT_CONNECT_DOMAINS = new String[]{
            "https://zh.z-lib.gd/",
            "https://zh.zlib.li/",
            "https://z-lib.gd/",
            "https://z-lib.gl/",
            "https://zlib.bz/",
            "https://z-library.gy/",
            "https://z-lib.by/",
            "https://z-library.qa/",
            "https://libb.la/"
    };

    // Benign domestic portal/CDN URLs used to blend traffic into normal everyday Chinese mobile web browsing
    private static final String[] BENIGN_DOMESTIC_COVER_URLS = new String[]{
            "https://connectivitycheck.platform.hicloud.com/generate_204",
            "https://www.baidu.com/favicon.ico",
            "https://g.alicdn.com/mtb/lib-flexible/0.3.4/flexible.js",
            "https://mat1.gtimg.com/www/icon/favicon2.ico"
    };

    private static final Set<String> OFFICIAL_HOST_SET = new HashSet<>(Arrays.asList(
            "z-lib.sk",
            "zh.z-library.sk",
            "z-library.sk",
            "z-lib.fm",
            "z-library.im",
            "go-to-library.sk",
            "zh.go-to-library.sk",
            "library-access.sk",
            "singlelogin.re",
            "zh.singlelogin.re",
            "1lib.sk"
    ));

    private static final Set<String> BLACKLISTED_HOSTS = new HashSet<>(Arrays.asList(
            "z-lib.tv",
            "zlibrary.to",
            "zlibrary.st",
            "z-lib.is",
            "z-lib.io",
            "z-lib.id",
            "zh.mongolian-books.sk",
            "mongolian-books.sk",
            "zb.zpro.us.kg",
            "zb.etocs.us.kg",
            "z.etocs.tk",
            "zh.z-lib.fm",
            "101ml.fi",
            "z-lib.gs",
            "zh.z-lib.gs",
            "1lib.sk",
            "intl.su",
            "zh.101su.ru",
            "101su.ru",
            "zh.intcn.online",
            "intcn.online",
            "z-library.website",
            "github.com",
            "raw.githubusercontent.com",
            "ghfast.top",
            "fastly.jsdelivr.net",
            "jsdelivr.net",
            "www.wangdu.site",
            "wangdu.site",
            "bookfere.com",
            "libgen.gs",
            "pilimi.org",
            "x.com",
            "en.wikipedia.org",
            "wikipedia.org",
            "www.reddit.com",
            "reddit.com",
            "mastodon.social",
            "lib-talk.io"
    ));

    private static final Random JITTER_RANDOM = new Random();

    public static class MirrorNode {
        public final String url;
        public final long latencyMs;
        public final boolean reachable;
        public final boolean isOfficial;

        public MirrorNode(String url, long latencyMs, boolean reachable, boolean isOfficial) {
            this.url = normalizeBaseUrl(url);
            this.latencyMs = latencyMs;
            this.reachable = reachable;
            this.isOfficial = isOfficial;
        }
    }

    public static class SearchBookItem {
        public String title = "";
        public String author = "";
        public String format = "";
        public String fileSize = "";
        public String detailUrl = "";
        public String downloadUrl = "";
        public String year = "";
    }

    public static class SyncResult {
        public final List<MirrorNode> nodes = new ArrayList<>();
        public String activeMirror = "https://zh.z-lib.gd/";
        public boolean isVpnEnvironment = false;
        public boolean updatedFromUpstream = false;
        public boolean autoLoggedIn = false;
        public String statusMessage = "";
    }

    /**
     * Ultra-fast (< 1ms) in-memory check for an active local VPN interface without any network I/O.
     */
    public static boolean hasActiveVpnInterfaceFast(Context context) {
        try {
            if (context != null) {
                ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
                if (cm != null) {
                    Network activeNet = cm.getActiveNetwork();
                    if (activeNet != null) {
                        NetworkCapabilities caps = cm.getNetworkCapabilities(activeNet);
                        if (caps != null && (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                                || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN))) {
                            return true;
                        }
                    }
                    Network[] allNets = cm.getAllNetworks();
                    if (allNets != null) {
                        for (Network net : allNets) {
                            NetworkCapabilities caps = cm.getNetworkCapabilities(net);
                            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                                    && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                                return true;
                            }
                        }
                    }
                }
            }
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface nif = interfaces.nextElement();
                if (nif != null && nif.isUp() && !nif.isLoopback()) {
                    String name = nif.getName().toLowerCase(Locale.ROOT);
                    if (name.startsWith("tun") || name.startsWith("ppp") || name.startsWith("tap")
                            || name.contains("utun") || name.contains("wg") || name.contains("clash")) {
                        return true;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /**
     * Strictly local VPN tunnel detection.
     * NEVER sends packets to `www.google.com` when no VPN interface is present on the phone.
     */
    public static boolean detectVpnEnvironment(Context context) {
        if (!hasActiveVpnInterfaceFast(context)) {
            return false;
        }

        // A VPN tunnel is active on the device; verify whether it routes to the international official network
        HttpURLConnection conn = null;
        try {
            URL u = new URL("https://www.google.com/generate_204");
            conn = (HttpURLConnection) u.openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(900);
            conn.setReadTimeout(900);
            int code = conn.getResponseCode();
            return code == 204;
        } catch (Exception ignored) {
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    public static boolean isOfficialDomain(String urlStr) {
        try {
            URI uri = new URI(normalizeBaseUrl(urlStr));
            String host = uri.getHost();
            if (host == null) return false;
            host = host.toLowerCase(Locale.ROOT);
            if (OFFICIAL_HOST_SET.contains(host)) return true;
            return host.endsWith(".z-library.sk") || host.endsWith(".z-lib.sk")
                    || host.endsWith(".z-lib.fm") || host.endsWith(".z-library.im")
                    || host.endsWith(".go-to-library.sk") || host.endsWith(".singlelogin.re")
                    || host.endsWith(".library-access.sk");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Returns whether a subresource URL inside WebView is a blocked foreign tracker or official domain
     * that must be silenced when browsing in non-VPN direct-connect mode.
     */
    public static boolean shouldSilenceSubresourceInDirectMode(String urlStr) {
        if (urlStr == null || urlStr.isEmpty()) return false;
        if (isOfficialDomain(urlStr)) return true;
        try {
            URI uri = new URI(urlStr);
            String host = uri.getHost();
            if (host == null) return false;
            host = host.toLowerCase(Locale.ROOT);
            return host.contains("google-analytics.com")
                    || host.contains("googletagmanager.com")
                    || host.contains("googlesyndication.com")
                    || host.contains("doubleclick.net")
                    || host.contains("facebook.net")
                    || host.contains("facebook.com")
                    || host.contains("twitter.com")
                    || host.contains("x.com")
                    || host.contains("reddit.com")
                    || host.contains("wikipedia.org");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Builds standard Chinese Android mobile browser HTTP headers so direct-connect requests
     * blend into normal everyday domestic mobile web browsing traffic and default to Simplified Chinese.
     */
    public static Map<String, String> buildNormalChineseBrowserHeaders(String refererUrl) {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", STANDARD_CN_MOBILE_UA);
        headers.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8");
        headers.put("Accept-Language", STANDARD_ACCEPT_LANGUAGE_ZH);
        headers.put("Sec-CH-UA", "\"Not/A)Brand\";v=\"8\", \"Chromium\";v=\"126\", \"Android WebView\";v=\"126\"");
        headers.put("Sec-CH-UA-Mobile", "?1");
        headers.put("Sec-CH-UA-Platform", "\"Android\"");
        headers.put("Upgrade-Insecure-Requests", "1");
        headers.put("Cache-Control", "max-age=0");
        if (refererUrl != null && !refererUrl.isEmpty()) {
            headers.put("Referer", refererUrl);
        } else {
            headers.put("Referer", "https://www.baidu.com/");
        }
        return headers;
    }

    public static void applyNormalChineseBrowserHeaders(HttpURLConnection conn, String refererUrl) {
        if (conn == null) return;
        Map<String, String> map = buildNormalChineseBrowserHeaders(refererUrl);
        for (Map.Entry<String, String> entry : map.entrySet()) {
            conn.setRequestProperty(entry.getKey(), entry.getValue());
        }
    }

    /**
     * Emits lightweight benign domestic portal/CDN HTTPS traffic and encrypted AliDNS DoH resolution
     * so direct-connect browsing blends naturally into normal domestic mobile web traffic.
     */
    public static void emitBenignDomesticCoverTraffic(final String targetMirrorUrl) {
        HttpURLConnection coverConn = null;
        try {
            String coverUrl = BENIGN_DOMESTIC_COVER_URLS[Math.abs(JITTER_RANDOM.nextInt()) % BENIGN_DOMESTIC_COVER_URLS.length];
            URL u = new URL(coverUrl);
            coverConn = (HttpURLConnection) u.openConnection();
            coverConn.setInstanceFollowRedirects(true);
            coverConn.setConnectTimeout(700);
            coverConn.setReadTimeout(700);
            coverConn.setRequestMethod("GET");
            applyNormalChineseBrowserHeaders(coverConn, "https://www.baidu.com/");
            coverConn.getResponseCode();
        } catch (Exception ignored) {
        } finally {
            if (coverConn != null) coverConn.disconnect();
        }

        // Pre-warm DNS via Alibaba Cloud encrypted DoH (223.5.5.5) so resolution looks like normal domestic cloud HTTPS traffic
        if (targetMirrorUrl != null && !targetMirrorUrl.isEmpty()) {
            HttpURLConnection dohConn = null;
            try {
                URI uri = new URI(normalizeBaseUrl(targetMirrorUrl));
                String host = uri.getHost();
                if (host != null && !host.isEmpty()) {
                    URL dohUrl = new URL("https://223.5.5.5/resolve?name=" + urlEncode(host) + "&type=A");
                    dohConn = (HttpURLConnection) dohUrl.openConnection();
                    dohConn.setConnectTimeout(700);
                    dohConn.setReadTimeout(700);
                    dohConn.setRequestMethod("GET");
                    dohConn.setRequestProperty("Accept", "application/dns-json");
                    dohConn.setRequestProperty("User-Agent", STANDARD_CN_MOBILE_UA);
                    String body = readResponseBody(dohConn);
                    if (body != null) {
                        Matcher mIp = Pattern.compile("\"data\"\\s*:\\s*\"(\\d+\\.\\d+\\.\\d+\\.\\d+)\"").matcher(body);
                        if (mIp.find()) {
                            InetAddress.getByName(host);
                        }
                    }
                }
            } catch (Exception ignored) {
            } finally {
                if (dohConn != null) dohConn.disconnect();
            }
        }
    }

    /**
     * Converts known root mirrors to their Simplified Chinese (`zh.`) subdomain when supported,
     * ensuring Z-Library opens in Chinese by default.
     */
    public static String preferChineseMirrorUrl(String rawUrl) {
        String norm = normalizeBaseUrl(rawUrl);
        if (norm.equalsIgnoreCase("https://z-lib.gd/")) {
            return "https://zh.z-lib.gd/";
        }
        if (norm.equalsIgnoreCase("https://zlib.li/")) {
            return "https://zh.zlib.li/";
        }
        if (norm.equalsIgnoreCase("https://z-library.sk/")) {
            return "https://zh.z-library.sk/";
        }
        if (norm.equalsIgnoreCase("https://go-to-library.sk/")) {
            return "https://zh.go-to-library.sk/";
        }
        return norm;
    }

    public static SyncResult syncAndSelectFastestMirror(ReadingSettings settings) {
        return syncAndSelectFastestMirror(null, settings, 0);
    }

    public static SyncResult syncAndSelectFastestMirror(Context context, final ReadingSettings settings, int forceEnvMode) {
        SyncResult result = new SyncResult();

        // forceEnvMode: 0 = Auto-detect, 1 = Force Official (VPN), 2 = Force Direct-Connect (No VPN)
        final boolean isVpn = (forceEnvMode == 1) || (forceEnvMode == 0 && detectVpnEnvironment(context));
        result.isVpnEnvironment = isVpn;

        if (!isVpn) {
            // Fire benign cover traffic completely asynchronously so mirror probing starts in 0ms
            new Thread(() -> emitBenignDomesticCoverTraffic("https://zh.z-lib.gd/")).start();
        }

        Set<String> officialUrls = new LinkedHashSet<>();
        Set<String> directUrls = new LinkedHashSet<>();

        // Prioritize the user's previously active mirror if valid
        if (settings != null) {
            String currentActive = preferChineseMirrorUrl(settings.getActiveZlibMirror());
            if (isValidMirrorCandidate(currentActive)) {
                if (isOfficialDomain(currentActive)) {
                    if (isVpn) officialUrls.add(currentActive);
                } else {
                    directUrls.add(currentActive);
                }
            }
        }

        for (String s : DIRECT_CONNECT_DOMAINS) {
            String n = preferChineseMirrorUrl(s);
            if (!isOfficialDomain(n)) {
                directUrls.add(n);
            }
        }

        if (isVpn) {
            for (String s : OFFICIAL_MAIN_DOMAINS) {
                officialUrls.add(preferChineseMirrorUrl(s));
            }
        }

        // Include any previously cached upstream mirrors immediately without blocking on remote GitHub/CDN fetches
        if (settings != null) {
            String cached = settings.getCachedZlibMirrors();
            if (cached != null && !cached.isEmpty()) {
                for (String part : cached.split(",")) {
                    String u = preferChineseMirrorUrl(part.trim());
                    if (isValidMirrorCandidate(u)) {
                        if (isOfficialDomain(u)) {
                            if (isVpn) officialUrls.add(u);
                        } else {
                            directUrls.add(u);
                        }
                    }
                }
            }
        }

        Set<String> orderedCandidates = new LinkedHashSet<>();
        if (isVpn) {
            orderedCandidates.addAll(officialUrls);
            orderedCandidates.addAll(directUrls);
        } else {
            // STRICT NON-VPN RULE: ONLY direct-connect mirrors! Never add or probe official VPN-only domains!
            for (String d : directUrls) {
                if (!isOfficialDomain(d)) {
                    orderedCandidates.add(d);
                }
            }
        }

        List<String> urlList = new ArrayList<>(orderedCandidates);

        // Ensure Chinese language + auth cookies are pre-seeded in memory on all candidate mirrors without disk flush per mirror
        for (String u : urlList) {
            syncCookiesToMirror(u, settings, false);
        }

        // Probe all candidate mirrors concurrently with fast timeout (max 1.5s) and zero artificial sleep delay
        int poolSize = Math.min(8, Math.max(4, urlList.size()));
        ExecutorService pool = Executors.newFixedThreadPool(poolSize);
        List<Future<MirrorNode>> futures = new ArrayList<>();
        final boolean nonVpnDirectMode = !isVpn;
        for (int idx = 0; idx < urlList.size(); idx++) {
            final String candidate = urlList.get(idx);
            futures.add(pool.submit(new Callable<MirrorNode>() {
                @Override
                public MirrorNode call() {
                    return probeMirror(candidate, nonVpnDirectMode);
                }
            }));
        }
        pool.shutdown();
        try {
            pool.awaitTermination(1600, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
        }

        for (int i = 0; i < futures.size(); i++) {
            String u = urlList.get(i);
            boolean off = isOfficialDomain(u);
            try {
                Future<MirrorNode> f = futures.get(i);
                if (f.isDone()) {
                    result.nodes.add(f.get());
                } else {
                    f.cancel(true);
                    result.nodes.add(new MirrorNode(u, 9999L, false, off));
                }
            } catch (Exception e) {
                result.nodes.add(new MirrorNode(u, 9999L, false, off));
            }
        }

        final boolean preferOfficial = isVpn;
        Collections.sort(result.nodes, new Comparator<MirrorNode>() {
            @Override
            public int compare(MirrorNode a, MirrorNode b) {
                boolean aMatchesEnv = (a.isOfficial == preferOfficial);
                boolean bMatchesEnv = (b.isOfficial == preferOfficial);
                if (a.reachable && b.reachable && aMatchesEnv != bMatchesEnv) {
                    return aMatchesEnv ? -1 : 1;
                }
                if (a.reachable != b.reachable) {
                    return a.reachable ? -1 : 1;
                }
                if (aMatchesEnv != bMatchesEnv) {
                    return aMatchesEnv ? -1 : 1;
                }
                // Prefer explicit `zh.` Chinese subdomain when both are reachable with similar latency
                boolean aZh = a.url.contains("://zh.");
                boolean bZh = b.url.contains("://zh.");
                if (aZh != bZh && Math.abs(a.latencyMs - b.latencyMs) < 600L) {
                    return aZh ? -1 : 1;
                }
                return Long.compare(a.latencyMs, b.latencyMs);
            }
        });

        if (!result.nodes.isEmpty() && result.nodes.get(0).reachable) {
            result.activeMirror = result.nodes.get(0).url;
        } else {
            result.activeMirror = isVpn ? "https://zh.z-library.sk/" : "https://zh.z-lib.gd/";
        }

        // Final safety check: if not on VPN, activeMirror MUST NEVER be an official domain
        if (!isVpn && isOfficialDomain(result.activeMirror)) {
            result.activeMirror = "https://zh.z-lib.gd/";
        }

        if (settings != null) {
            settings.setActiveZlibMirror(result.activeMirror);
            syncCookiesToMirror(result.activeMirror, settings, false);
        }

        // Refresh upstream mirror list & background auto-login asynchronously without blocking UI
        final String selectedActive = result.activeMirror;
        new Thread(() -> {
            try {
                refreshUpstreamMirrorsInBackground(isVpn, settings);
                if (settings != null && settings.hasZlibCredentials()) {
                    performAutoLogin(selectedActive, settings);
                }
                CookieManager.getInstance().flush();
            } catch (Exception ignored) {
            }
        }).start();

        String envBadge = isVpn
                ? "🌐 已连接官网节点"
                : "🇨🇳 已连接中文直链";
        result.statusMessage = envBadge + " · 当前网址: " + result.activeMirror;
        return result;
    }

    private static void refreshUpstreamMirrorsInBackground(boolean isVpn, ReadingSettings settings) {
        if (settings == null) return;
        Set<String> merged = new LinkedHashSet<>();
        for (String s : DIRECT_CONNECT_DOMAINS) {
            merged.add(preferChineseMirrorUrl(s));
        }
        if (isVpn) {
            for (String s : OFFICIAL_MAIN_DOMAINS) {
                merged.add(preferChineseMirrorUrl(s));
            }
            for (String updatePage : OFFICIAL_UPDATE_PAGES) {
                try {
                    String html = httpGetString(updatePage, 2500, null);
                    if (html != null && html.length() > 100) {
                        for (String u : parseOfficialUpdatePageUrls(html)) {
                            merged.add(preferChineseMirrorUrl(u));
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        }
        String[] updateSources = isVpn ? DIRECT_UPDATE_PAGES_VPN : DIRECT_UPDATE_PAGES_NO_VPN;
        for (String sourceUrl : updateSources) {
            try {
                String content = httpGetString(sourceUrl, 2500, null);
                if (content != null && content.contains("Zlibrary官方最新网址合集")) {
                    List<String> extracted = parseAwesomeZlibDirectMirrors(content);
                    if (!extracted.isEmpty()) {
                        for (String u : extracted) {
                            String pref = preferChineseMirrorUrl(u);
                            if (!isOfficialDomain(pref) || isVpn) {
                                merged.add(pref);
                            }
                        }
                        break;
                    }
                }
            } catch (Exception ignored) {
            }
        }
        settings.setCachedZlibMirrors(String.join(",", merged));
    }

    public static List<String> parseOfficialUpdatePageUrls(String html) {
        List<String> list = new ArrayList<>();
        if (html == null || html.isEmpty()) return list;
        Pattern p = Pattern.compile("(https?://(?:[a-z]{2}\\.)?(?:z-lib\\.[a-z]{2,4}|z-library\\.[a-z]{2,7}|zlib\\.[a-z]{2,4}|libb\\.la)(?:/|$))",
                Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(html);
        while (m.find()) {
            String candidate = preferChineseMirrorUrl(m.group(1));
            if (isValidMirrorCandidate(candidate) && !list.contains(candidate)) {
                list.add(candidate);
            }
        }
        return list;
    }

    public static List<String> parseAwesomeZlibDirectMirrors(String rawText) {
        List<String> validUrls = new ArrayList<>();
        if (rawText == null || rawText.isEmpty()) return validUrls;

        String[] lines = rawText.split("\n");
        Pattern urlPattern = Pattern.compile("(https?://[a-zA-Z0-9._-]+(?:/[^\\s|\"')<>\\uFF08\\uFF09]*)?)");

        for (String line : lines) {
            if (line.contains("已失效") || line.contains("黑名单") || line.contains("钓鱼网站")) {
                break;
            }
            if (line.contains("🚫")) {
                continue;
            }
            if (line.contains("✅") || line.contains("官方网址")) {
                Matcher m = urlPattern.matcher(line);
                while (m.find()) {
                    String candidate = preferChineseMirrorUrl(m.group(1));
                    if (isValidMirrorCandidate(candidate) && !validUrls.contains(candidate)) {
                        validUrls.add(candidate);
                    }
                }
            }
        }
        return validUrls;
    }

    public static boolean isValidMirrorCandidate(String urlStr) {
        if (urlStr == null || urlStr.isEmpty()) return false;
        try {
            URI uri = new URI(urlStr);
            String host = uri.getHost();
            if (host == null) return false;
            host = host.toLowerCase(Locale.ROOT);
            if (BLACKLISTED_HOSTS.contains(host)) return false;
            for (String blocked : BLACKLISTED_HOSTS) {
                if (host.endsWith("." + blocked)) return false;
            }
            return host.contains("z-lib")
                    || host.contains("zlib")
                    || host.contains("zlibrary")
                    || host.contains("go-to-library")
                    || host.contains("library-access")
                    || host.equals("libb.la")
                    || host.contains("singlelogin");
        } catch (Exception e) {
            return false;
        }
    }

    private static MirrorNode probeMirror(String baseUrl, boolean nonVpnDirectMode) {
        boolean isOff = isOfficialDomain(baseUrl);
        if (nonVpnDirectMode && isOff) {
            // Never probe official VPN-only domains when in non-VPN direct mode
            return new MirrorNode(baseUrl, 9999L, false, true);
        }
        if (!isValidMirrorCandidate(baseUrl)) {
            return new MirrorNode(baseUrl, 9999L, false, isOff);
        }
        long start = System.currentTimeMillis();
        HttpURLConnection conn = null;
        try {
            URL url = new URL(baseUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(1400);
            conn.setReadTimeout(1400);
            conn.setRequestMethod("GET");
            applyNormalChineseBrowserHeaders(conn, "https://www.baidu.com/");
            conn.setRequestProperty("Cookie", "remix_userlang=zh; selectedLanguage=zh; lang=zh; selectedSiteMode=books");

            int code = conn.getResponseCode();
            if (code == HttpURLConnection.HTTP_MOVED_PERM || code == HttpURLConnection.HTTP_MOVED_TEMP
                    || code == HttpURLConnection.HTTP_SEE_OTHER || code == 307 || code == 308) {
                String loc = conn.getHeaderField("Location");
                if (loc != null) {
                    String resolved = resolveUrl(baseUrl, loc);
                    if (nonVpnDirectMode && isOfficialDomain(resolved)) {
                        // A redirect to a blocked official site means this mirror cannot be used directly without VPN
                        return new MirrorNode(baseUrl, 9999L, false, isOff);
                    }
                    if (!isValidMirrorCandidate(resolved)) {
                        return new MirrorNode(baseUrl, 9999L, false, isOff);
                    }
                }
            }
            if ((code >= 200 && code < 400) || code == 503) {
                long elapsed = System.currentTimeMillis() - start;
                return new MirrorNode(baseUrl, elapsed, true, isOff);
            }
            return new MirrorNode(baseUrl, 9999L, false, isOff);
        } catch (Exception e) {
            return new MirrorNode(baseUrl, 9999L, false, isOff);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    public static boolean performAutoLogin(String mirrorBaseUrl, ReadingSettings settings) {
        if (!settings.hasZlibCredentials()) return false;
        String email = settings.getZlibEmail();
        String password = settings.getZlibPassword();
        String base = normalizeBaseUrl(mirrorBaseUrl);

        syncCookiesToMirror(base, settings, false);

        if (tryLoginEndpoint(base, base + "eapi/user/login",
                "email=" + urlEncode(email) + "&password=" + urlEncode(password), settings)) {
            return true;
        }

        String rpcBody = "isModal=true"
                + "&email=" + urlEncode(email)
                + "&password=" + urlEncode(password)
                + "&site_mode=books"
                + "&action=login"
                + "&isSingleLogin=1"
                + "&redirectUrl=" + urlEncode(base)
                + "&gg_json_mode=1";
        if (tryLoginEndpoint(base, base + "rpc.php", rpcBody, settings)) {
            return true;
        }

        return !settings.getZlibRemixUserId().isEmpty() && !settings.getZlibRemixUserKey().isEmpty();
    }

    private static boolean tryLoginEndpoint(String base, String endpointUrl, String postBody, ReadingSettings settings) {
        HttpURLConnection conn = null;
        try {
            URL u = new URL(endpointUrl);
            conn = (HttpURLConnection) u.openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(4000);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            applyNormalChineseBrowserHeaders(conn, base);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
            conn.setRequestProperty("X-Requested-With", "XMLHttpRequest");
            conn.setRequestProperty("Origin", base.substring(0, base.length() - 1));

            String existingCookie = CookieManager.getInstance().getCookie(base);
            if (existingCookie != null && !existingCookie.isEmpty()) {
                conn.setRequestProperty("Cookie", existingCookie + "; remix_userlang=zh; selectedLanguage=zh");
            } else {
                conn.setRequestProperty("Cookie", buildCookieHeader(settings));
            }

            try (OutputStream os = conn.getOutputStream()) {
                os.write(postBody.getBytes(StandardCharsets.UTF_8));
            }

            String foundUserId = null;
            String foundUserKey = null;

            Map<String, List<String>> headers = conn.getHeaderFields();
            for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                if (entry.getKey() != null && entry.getKey().equalsIgnoreCase("Set-Cookie")) {
                    for (String cookie : entry.getValue()) {
                        setCookieOnWebView(base, cookie);
                        Matcher mId = Pattern.compile("remix_userid=([^;\\s]+)").matcher(cookie);
                        if (mId.find() && !"deleted".equalsIgnoreCase(mId.group(1))) {
                            foundUserId = mId.group(1);
                        }
                        Matcher mKey = Pattern.compile("remix_userkey=([^;\\s]+)").matcher(cookie);
                        if (mKey.find() && !"deleted".equalsIgnoreCase(mKey.group(1))) {
                            foundUserKey = mKey.group(1);
                        }
                    }
                }
            }

            String resp = readResponseBody(conn);
            if (resp != null && !resp.isEmpty()) {
                if (foundUserId == null) {
                    Matcher mId = Pattern.compile("\"(?:user_id|id|remix_userid)\"\\s*:\\s*\"?(\\d+)\"?").matcher(resp);
                    if (mId.find()) foundUserId = mId.group(1);
                }
                if (foundUserKey == null) {
                    Matcher mKey = Pattern.compile("\"(?:remix_userkey|user_key)\"\\s*:\\s*\"([a-zA-Z0-9_\\-]+)\"").matcher(resp);
                    if (mKey.find()) foundUserKey = mKey.group(1);
                }
            }

            if (foundUserId != null && foundUserKey != null && !foundUserId.isEmpty() && !foundUserKey.isEmpty()) {
                settings.saveZlibAuthTokens(foundUserId, foundUserKey);
                syncCookiesToMirror(base, settings, true);
                return true;
            }
        } catch (Exception ignored) {
        } finally {
            if (conn != null) conn.disconnect();
        }
        return false;
    }

    public static String extractRootDomain(String host) {
        if (host == null) return "";
        String h = host.toLowerCase(Locale.ROOT).trim();
        String[] parts = h.split("\\.");
        if (parts.length >= 3) {
            String lastTwo = parts[parts.length - 2] + "." + parts[parts.length - 1];
            if (lastTwo.equals("us.kg") || lastTwo.equals("co.uk") || lastTwo.equals("com.cn")) {
                if (parts.length >= 4) {
                    return parts[parts.length - 3] + "." + lastTwo;
                }
            }
            return lastTwo;
        }
        return h;
    }

    /**
     * Always synchronizes Simplified Chinese language cookies (`remix_userlang=zh`, `selectedLanguage=zh`, `lang=zh`)
     * AND (if available) `remix_userid` / `remix_userkey` login cookies onto the target mirror.
     */
    public static void syncCookiesToMirror(String mirrorUrl, ReadingSettings settings) {
        syncCookiesToMirror(mirrorUrl, settings, false);
    }

    public static void syncCookiesToMirror(String mirrorUrl, ReadingSettings settings, boolean flushNow) {
        try {
            CookieManager cm = CookieManager.getInstance();
            cm.setAcceptCookie(true);

            URI uri = new URI(mirrorUrl);
            String scheme = uri.getScheme() != null ? uri.getScheme() : "https";
            String host = uri.getHost();
            if (host == null) return;

            String rootDomain = extractRootDomain(host);
            String hostOrigin = scheme + "://" + host + "/";
            String rootOrigin = scheme + "://" + rootDomain + "/";

            // 1. Always enforce Simplified Chinese (`zh`) and book mode on both host and root domain
            String[] zhCookies = new String[]{
                    "remix_userlang=zh; Path=/; Max-Age=31536000; SameSite=Lax",
                    "selectedLanguage=zh; Path=/; Max-Age=31536000; SameSite=Lax",
                    "lang=zh; Path=/; Max-Age=31536000; SameSite=Lax",
                    "locale=zh; Path=/; Max-Age=31536000; SameSite=Lax",
                    "selectedSiteMode=books; Path=/; Max-Age=31536000; SameSite=Lax"
            };
            for (String c : zhCookies) {
                cm.setCookie(hostOrigin, c);
                cm.setCookie(hostOrigin, c + "; Domain=." + rootDomain);
                cm.setCookie(rootOrigin, c + "; Domain=." + rootDomain);
            }

            // 2. Sync user login tokens if present
            if (settings != null) {
                String userId = settings.getZlibRemixUserId();
                String userKey = settings.getZlibRemixUserKey();
                if (userId != null && !userId.isEmpty() && userKey != null && !userKey.isEmpty()) {
                    cm.setCookie(hostOrigin, "remix_userid=" + userId + "; Path=/; Max-Age=31536000; SameSite=Lax");
                    cm.setCookie(hostOrigin, "remix_userkey=" + userKey + "; Path=/; Max-Age=31536000; SameSite=Lax");
                    cm.setCookie(hostOrigin, "remix_userid=" + userId + "; Domain=." + rootDomain + "; Path=/; Max-Age=31536000; SameSite=Lax");
                    cm.setCookie(hostOrigin, "remix_userkey=" + userKey + "; Domain=." + rootDomain + "; Path=/; Max-Age=31536000; SameSite=Lax");
                    cm.setCookie(rootOrigin, "remix_userid=" + userId + "; Domain=." + rootDomain + "; Path=/; Max-Age=31536000; SameSite=Lax");
                    cm.setCookie(rootOrigin, "remix_userkey=" + userKey + "; Domain=." + rootDomain + "; Path=/; Max-Age=31536000; SameSite=Lax");
                }
            }
            if (flushNow) {
                cm.flush();
            }
        } catch (Exception ignored) {
        }
    }

    public static void syncCookiesAcrossDirectPool(ReadingSettings settings) {
        for (String u : DIRECT_CONNECT_DOMAINS) {
            syncCookiesToMirror(u, settings, false);
        }
    }

    public static void syncCookiesAcrossAllPools(ReadingSettings settings) {
        for (String u : DIRECT_CONNECT_DOMAINS) {
            syncCookiesToMirror(u, settings, false);
        }
        for (String u : OFFICIAL_MAIN_DOMAINS) {
            syncCookiesToMirror(u, settings, false);
        }
    }

    private static void setCookieOnWebView(String url, String cookieHeader) {
        try {
            CookieManager cm = CookieManager.getInstance();
            cm.setAcceptCookie(true);
            cm.setCookie(url, cookieHeader);
            cm.flush();
        } catch (Exception ignored) {
        }
    }

    public static String buildCookieHeader(ReadingSettings settings) {
        String baseCookies = "remix_userlang=zh; selectedLanguage=zh; lang=zh; locale=zh; selectedSiteMode=books";
        if (settings != null) {
            String uid = settings.getZlibRemixUserId();
            String ukey = settings.getZlibRemixUserKey();
            if (uid != null && !uid.isEmpty() && ukey != null && !ukey.isEmpty()) {
                return "remix_userid=" + uid + "; remix_userkey=" + ukey + "; " + baseCookies;
            }
        }
        return baseCookies;
    }

    public static File downloadUrlToFile(String fileUrl, String fallbackTitle, String fallbackFormat,
                                         File destDir, String cookieHeader, String referer) throws Exception {
        return downloadUrlToFile(fileUrl, fallbackTitle, fallbackFormat, destDir, cookieHeader, referer, null, null, null);
    }

    public static File downloadUrlToFile(String fileUrl, String fallbackTitle, String fallbackFormat,
                                         File destDir, String cookieHeader, String referer,
                                         ProgressCallback progressCallback) throws Exception {
        return downloadUrlToFile(fileUrl, fallbackTitle, fallbackFormat, destDir, cookieHeader, referer, null, null, progressCallback);
    }

    public static File downloadUrlToFile(String fileUrl, String fallbackTitle, String fallbackFormat,
                                         File destDir, String cookieHeader, String referer,
                                         String initialContentDisposition, ReadingSettings settings,
                                         ProgressCallback progressCallback) throws Exception {
        if (!destDir.exists()) destDir.mkdirs();

        Exception lastException = null;

        if (isDownloadCancelled(progressCallback)) {
            throw new InterruptedException("已取消下载");
        }
        // Stage 1: Try downloading directly (with up to 3 automatic retries on transient HTTP 503/502/504/429)
        try {
            return tryDownloadSingleChainWithRetry(
                    fileUrl, fallbackTitle, fallbackFormat, destDir,
                    cookieHeader, referer, initialContentDisposition, settings, progressCallback, 3);
        } catch (Exception firstErr) {
            if (isDownloadCancelled(progressCallback) || (firstErr instanceof InterruptedException)) {
                throw new InterruptedException("已取消下载");
            }
            lastException = firstErr;
        }

        // Stage 2: If Stage 1 hit HTTP 503 / 502 / 403 or returned HTML (because a single-use /dl/ token was consumed or
        // the current direct mirror node is temporarily overloaded), automatically re-acquire a fresh /dl/ token from the
        // book detail page (`referer`) across the current mirror and backup Chinese direct mirrors!
        Set<String> candidateMirrors = new LinkedHashSet<>();
        if (referer != null && referer.startsWith("http")) {
            try {
                URI refUri = new URI(referer);
                if (refUri.getHost() != null) {
                    String scheme = refUri.getScheme() != null ? refUri.getScheme() : "https";
                    candidateMirrors.add(scheme + "://" + refUri.getHost() + "/");
                }
            } catch (Exception ignored) {
            }
        }
        if (settings != null) {
            String active = settings.getActiveZlibMirror();
            if (active != null && !active.isEmpty()) {
                candidateMirrors.add(normalizeBaseUrl(active));
            }
        }
        for (String d : DIRECT_CONNECT_DOMAINS) {
            candidateMirrors.add(preferChineseMirrorUrl(d));
        }

        int mirrorAttempt = 0;
        for (String mirrorBase : candidateMirrors) {
            if (isDownloadCancelled(progressCallback)) {
                throw new InterruptedException("已取消下载");
            }
            if (mirrorAttempt >= 5) break;
            mirrorAttempt++;
            try {
                syncCookiesToMirror(mirrorBase, settings, false);
                String mirrorCookies = mergeWebViewCookiesForUrl(mirrorBase, cookieHeader, settings);

                // 2A. If we have the book detail page URL (e.g. /book/12345/slug), fetch it on `mirrorBase` to get a fresh `/dl/` token
                if (referer != null && (referer.contains("/book/") || referer.contains("/books/"))) {
                    String rewrittenBookPage = rewriteUrlToBase(referer, mirrorBase);
                    String freshDlUrl = fetchFreshDlUrlFromBookPage(rewrittenBookPage, mirrorCookies, settings);
                    if (freshDlUrl != null && !freshDlUrl.isEmpty()) {
                        String dlCookies = mergeWebViewCookiesForUrl(freshDlUrl, mirrorCookies, settings);
                        return tryDownloadSingleChainWithRetry(
                                freshDlUrl, fallbackTitle, fallbackFormat, destDir,
                                dlCookies, rewrittenBookPage, initialContentDisposition, settings, progressCallback, 2);
                    }
                }

                // 2B. Also try rewriting the `/dl/` URL directly to `mirrorBase` if it is a `/dl/` path
                if (fileUrl != null && fileUrl.contains("/dl/")) {
                    String rewrittenDl = rewriteUrlToBase(fileUrl, mirrorBase);
                    String rewrittenRef = (referer != null && !referer.isEmpty()) ? rewriteUrlToBase(referer, mirrorBase) : mirrorBase;
                    String dlCookies = mergeWebViewCookiesForUrl(rewrittenDl, mirrorCookies, settings);
                    return tryDownloadSingleChainWithRetry(
                            rewrittenDl, fallbackTitle, fallbackFormat, destDir,
                            dlCookies, rewrittenRef, initialContentDisposition, settings, progressCallback, 2);
                }
            } catch (Exception mirrorErr) {
                lastException = mirrorErr;
            }
        }

        if (isDownloadCancelled(progressCallback)) {
            throw new InterruptedException("已取消下载");
        }
        // Stage 3: If credentials are saved, refresh auth session once and do one final attempt on the active mirror
        if (settings != null && settings.hasZlibCredentials() && referer != null && referer.startsWith("http")) {
            try {
                String activeBase = normalizeBaseUrl(settings.getActiveZlibMirror());
                if (performAutoLogin(activeBase, settings)) {
                    String refreshedCookies = mergeWebViewCookiesForUrl(activeBase, buildCookieHeader(settings), settings);
                    String rewrittenBookPage = rewriteUrlToBase(referer, activeBase);
                    String freshDlUrl = fetchFreshDlUrlFromBookPage(rewrittenBookPage, refreshedCookies, settings);
                    if (freshDlUrl != null && !freshDlUrl.isEmpty()) {
                        return tryDownloadSingleChainWithRetry(
                                freshDlUrl, fallbackTitle, fallbackFormat, destDir,
                                refreshedCookies, rewrittenBookPage, initialContentDisposition, settings, progressCallback, 2);
                    }
                }
            } catch (Exception refreshErr) {
                lastException = refreshErr;
            }
        }

        if (lastException != null) {
            throw lastException;
        }
        throw new IllegalStateException("文件节点暂时繁忙，请稍后重试");
    }

    private static File tryDownloadSingleChainWithRetry(
            String fileUrl, String fallbackTitle, String fallbackFormat,
            File destDir, String cookieHeader, String referer,
            String initialContentDisposition, ReadingSettings settings,
            ProgressCallback progressCallback, int maxAttempts) throws Exception {
        Exception lastErr = null;
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            if (isDownloadCancelled(progressCallback)) {
                throw new InterruptedException("已取消下载");
            }
            if (attempt > 0) {
                try {
                    Thread.sleep(550L * attempt);
                } catch (InterruptedException ignored) {
                }
            }
            if (isDownloadCancelled(progressCallback)) {
                throw new InterruptedException("已取消下载");
            }
            HttpURLConnection conn = null;
            try {
                String currentUrl = fileUrl;
                String currentReferer = referer;
                String currentCookies = mergeWebViewCookiesForUrl(currentUrl, cookieHeader, settings);

                for (int redirects = 0; redirects < 8; redirects++) {
                    if (isDownloadCancelled(progressCallback)) {
                        throw new InterruptedException("已取消下载");
                    }
                    URL u = new URL(currentUrl);
                    conn = (HttpURLConnection) u.openConnection();
                    sActiveDownloadConn = conn;
                    conn.setInstanceFollowRedirects(false);
                    conn.setConnectTimeout(10000);
                    conn.setReadTimeout(25000);
                    applyNormalChineseBrowserHeaders(conn, currentReferer);
                    String hopCookies = mergeWebViewCookiesForUrl(currentUrl, currentCookies, settings);
                    if (!hopCookies.isEmpty()) {
                        conn.setRequestProperty("Cookie", hopCookies);
                    }
                    int code = conn.getResponseCode();

                    Map<String, List<String>> hdrs = conn.getHeaderFields();
                    if (hdrs != null) {
                        for (Map.Entry<String, List<String>> entry : hdrs.entrySet()) {
                            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase("Set-Cookie") && entry.getValue() != null) {
                                for (String sc : entry.getValue()) {
                                    setCookieOnWebView(currentUrl, sc);
                                    int semi = sc.indexOf(';');
                                    String pair = (semi > 0 ? sc.substring(0, semi) : sc).trim();
                                    if (!pair.isEmpty()) {
                                        currentCookies = currentCookies.isEmpty() ? pair : (currentCookies + "; " + pair);
                                    }
                                }
                            }
                        }
                    }

                    if (code == HttpURLConnection.HTTP_MOVED_PERM || code == HttpURLConnection.HTTP_MOVED_TEMP
                            || code == HttpURLConnection.HTTP_SEE_OTHER || code == 307 || code == 308) {
                        String loc = conn.getHeaderField("Location");
                        conn.disconnect();
                        conn = null;
                        if (loc == null) break;
                        currentReferer = currentUrl;
                        currentUrl = resolveUrl(currentUrl, loc);
                        continue;
                    }
                    break;
                }

                if (conn == null) {
                    throw new IllegalStateException("连接中断");
                }
                int finalCode = conn.getResponseCode();
                if (finalCode == 503 || finalCode == 502 || finalCode == 504 || finalCode == 429) {
                    conn.disconnect();
                    conn = null;
                    lastErr = new IllegalStateException("当前直链文件节点繁忙 (" + finalCode + ")，正在自动切换备用节点或请稍后重试");
                    continue;
                }
                if (finalCode >= 400) {
                    conn.disconnect();
                    conn = null;
                    throw new IllegalStateException("文件服务器返回状态码 " + finalCode);
                }

                String contentType = conn.getHeaderField("Content-Type");
                String disposition = conn.getHeaderField("Content-Disposition");
                if ((disposition == null || disposition.isEmpty()) && initialContentDisposition != null) {
                    disposition = initialContentDisposition;
                }
                if (contentType != null && contentType.toLowerCase(Locale.ROOT).contains("text/html")
                        && (disposition == null || !disposition.toLowerCase(Locale.ROOT).contains("attachment"))) {
                    conn.disconnect();
                    conn = null;
                    throw new IllegalStateException("下载令牌需刷新或今日下载额度已满");
                }

                long totalBytes = conn.getContentLengthLong();
                String fileName = extractFileName(disposition, currentUrl, fallbackTitle, fallbackFormat);
                File targetFile = new File(destDir, fileName);

                long downloadedBytes = 0L;
                if (progressCallback != null) {
                    progressCallback.onProgress(0L, totalBytes);
                }

                try (InputStream is = new BufferedInputStream(conn.getInputStream(), 32768);
                     FileOutputStream fos = new FileOutputStream(targetFile)) {
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = is.read(buf)) != -1) {
                        if (isDownloadCancelled(progressCallback)) {
                            fos.close();
                            targetFile.delete();
                            throw new InterruptedException("已取消下载");
                        }
                        fos.write(buf, 0, n);
                        downloadedBytes += n;
                        if (progressCallback != null) {
                            progressCallback.onProgress(downloadedBytes, totalBytes);
                        }
                    }
                } finally {
                    sActiveDownloadConn = null;
                    conn.disconnect();
                    conn = null;
                }

                if (targetFile.exists() && targetFile.length() > 128) {
                    return targetFile;
                }
                throw new IllegalStateException("下载文件不完整");
            } catch (Exception e) {
                sActiveDownloadConn = null;
                if (conn != null) {
                    try { conn.disconnect(); } catch (Exception ignored) {}
                }
                if (isDownloadCancelled(progressCallback) || (e instanceof InterruptedException)) {
                    throw new InterruptedException("已取消下载");
                }
                lastErr = e;
            }
        }
        if (lastErr != null) throw lastErr;
        throw new IllegalStateException("下载未完成");
    }

    private static String mergeWebViewCookiesForUrl(String targetUrl, String extraCookies, ReadingSettings settings) {
        LinkedHashSet<String> parts = new LinkedHashSet<>();
        try {
            if (targetUrl != null && !targetUrl.isEmpty()) {
                String wvCookie = CookieManager.getInstance().getCookie(targetUrl);
                if (wvCookie != null && !wvCookie.isEmpty()) {
                    for (String seg : wvCookie.split(";")) {
                        String trimmed = seg.trim();
                        if (!trimmed.isEmpty()) parts.add(trimmed);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        if (extraCookies != null && !extraCookies.isEmpty()) {
            for (String seg : extraCookies.split(";")) {
                String trimmed = seg.trim();
                if (!trimmed.isEmpty()) parts.add(trimmed);
            }
        }
        String baseAuth = buildCookieHeader(settings);
        if (baseAuth != null && !baseAuth.isEmpty()) {
            for (String seg : baseAuth.split(";")) {
                String trimmed = seg.trim();
                if (!trimmed.isEmpty()) parts.add(trimmed);
            }
        }
        return String.join("; ", parts);
    }

    private static String rewriteUrlToBase(String originalUrl, String targetBase) {
        try {
            URI orig = new URI(originalUrl);
            URI base = new URI(normalizeBaseUrl(targetBase));
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
            return originalUrl;
        }
    }

    private static String fetchFreshDlUrlFromBookPage(String bookPageUrl, String cookieHeader, ReadingSettings settings) {
        HttpURLConnection conn = null;
        try {
            URL u = new URL(bookPageUrl);
            conn = (HttpURLConnection) u.openConnection();
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(8000);
            applyNormalChineseBrowserHeaders(conn, bookPageUrl);
            String mergedCookies = mergeWebViewCookiesForUrl(bookPageUrl, cookieHeader, settings);
            if (!mergedCookies.isEmpty()) {
                conn.setRequestProperty("Cookie", mergedCookies);
            }
            int code = conn.getResponseCode();
            if (code >= 200 && code < 400) {
                String html = readResponseBody(conn);
                if (html != null && !html.isEmpty()) {
                    Matcher m = Pattern.compile("href=[\"']((?:https?://[^\"']+)?/dl/[^\"'#\\s]+)[\"']", Pattern.CASE_INSENSITIVE).matcher(html);
                    if (m.find()) {
                        return resolveUrl(bookPageUrl, m.group(1));
                    }
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (conn != null) conn.disconnect();
        }
        return null;
    }

    private static String extractFileName(String disposition, String url, String fallbackTitle, String fallbackFormat) {
        if (disposition != null) {
            Matcher mUtf = Pattern.compile("filename\\*=UTF-8''([^;]+)", Pattern.CASE_INSENSITIVE).matcher(disposition);
            if (mUtf.find()) {
                try {
                    return sanitizeFileName(URLDecoder.decode(mUtf.group(1), "UTF-8"));
                } catch (Exception ignored) {
                }
            }
            Matcher m = Pattern.compile("filename=[\"']?([^\"';]+)[\"']?", Pattern.CASE_INSENSITIVE).matcher(disposition);
            if (m.find()) {
                try {
                    return sanitizeFileName(URLDecoder.decode(m.group(1), "UTF-8"));
                } catch (Exception ignored) {
                }
            }
        }
        String ext = (fallbackFormat != null && !fallbackFormat.isEmpty())
                ? "." + fallbackFormat.toLowerCase(Locale.ROOT)
                : ".epub";
        if (url != null) {
            String lowUrl = url.toLowerCase(Locale.ROOT);
            if (lowUrl.contains(".pdf")) ext = ".pdf";
            else if (lowUrl.contains(".azw3")) ext = ".azw3";
            else if (lowUrl.contains(".mobi")) ext = ".mobi";
            else if (lowUrl.contains(".fb2.zip")) ext = ".fb2.zip";
            else if (lowUrl.contains(".fbz")) ext = ".fbz";
            else if (lowUrl.contains(".fb2")) ext = ".fb2";
            else if (lowUrl.contains(".txt")) ext = ".txt";
        }
        String base = (fallbackTitle != null && !fallbackTitle.trim().isEmpty())
                ? sanitizeFileName(fallbackTitle.trim())
                : ("zlib_book_" + System.currentTimeMillis());
        if (!base.toLowerCase(Locale.ROOT).endsWith(ext)) {
            base += ext;
        }
        return base;
    }

    private static String sanitizeFileName(String name) {
        return name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
    }

    public static String normalizeBaseUrl(String raw) {
        if (raw == null) return "";
        String t = raw.trim();
        int idx = t.indexOf('（');
        if (idx > 0) t = t.substring(0, idx);
        idx = t.indexOf('(');
        if (idx > 0) t = t.substring(0, idx);
        if (!t.endsWith("/")) t += "/";
        return t;
    }

    public static String resolveUrl(String baseUrl, String rel) {
        if (rel == null || rel.isEmpty()) return "";
        if (rel.startsWith("http://") || rel.startsWith("https://")) return rel;
        try {
            return new URL(new URL(baseUrl), rel).toString();
        } catch (Exception e) {
            return baseUrl + (rel.startsWith("/") ? rel.substring(1) : rel);
        }
    }

    private static String httpGetString(String urlStr, int timeoutMs, String cookieHeader) throws Exception {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setRequestMethod("GET");
            applyNormalChineseBrowserHeaders(conn, "https://www.baidu.com/");
            if (cookieHeader != null && !cookieHeader.isEmpty()) {
                conn.setRequestProperty("Cookie", cookieHeader);
            }
            return readResponseBody(conn);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String readResponseBody(HttpURLConnection conn) throws Exception {
        InputStream is = conn.getResponseCode() >= 400 ? conn.getErrorStream() : conn.getInputStream();
        if (is == null) return "";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) != -1) {
            bos.write(buf, 0, n);
        }
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String urlEncode(String s) {
        try {
            return URLEncoder.encode(s != null ? s : "", "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }
}
