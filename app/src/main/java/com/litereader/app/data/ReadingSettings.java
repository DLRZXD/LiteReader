package com.litereader.app.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Typeface;
import android.util.Base64;

import java.nio.charset.StandardCharsets;

public class ReadingSettings {
    private static final String PREF_NAME = "litereader_prefs";

    // Themes inspired by ReadEra & Readest
    public static final int THEME_DAY_CLASSIC = 0;
    public static final int THEME_READERA_SEPIA = 1;
    public static final int THEME_READEST_SAGE = 2;
    public static final int THEME_READEST_WARM = 3;
    public static final int THEME_READEST_NORD = 4;
    public static final int THEME_AMOLED_NIGHT = 5;

    // Fonts
    public static final int FONT_SANS = 0;
    public static final int FONT_SERIF = 1;
    public static final int FONT_MONO = 2;
    public static final int FONT_CLASSIC = 3;

    // Orientation modes
    public static final int ORIENTATION_AUTO = 0;
    public static final int ORIENTATION_PORTRAIT = 1;
    public static final int ORIENTATION_LANDSCAPE = 2;

    // Browse modes: Horizontal Paging vs Vertical Scroll
    public static final int BROWSE_MODE_PAGED = 0;
    public static final int BROWSE_MODE_SCROLL = 1;

    // Bookshelf layout modes: List (列表) vs Grid (格子)
    public static final int BOOKSHELF_LAYOUT_LIST = 0;
    public static final int BOOKSHELF_LAYOUT_GRID = 1;

    // Page Turn Animation Styles
    public static final int ANIM_COVER = 0;      // 覆盖翻页
    public static final int ANIM_SLIDE = 1;      // 平移滑动
    public static final int ANIM_SIMULATION = 2; // 仿真卷曲翻书
    public static final int ANIM_FADE = 3;       // 柔和淡入
    public static final int ANIM_NONE = 4;       // 无动画(极速)

    private static Context appCtx;
    private final SharedPreferences prefs;

    public ReadingSettings(Context context) {
        appCtx = context.getApplicationContext();
        this.prefs = appCtx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public int getTheme() {
        return prefs.getInt("reader_theme", THEME_READERA_SEPIA);
    }

    public void setTheme(int theme) {
        prefs.edit().putInt("reader_theme", theme).apply();
    }

    public int getFontFamily() {
        return prefs.getInt("font_family", FONT_SERIF);
    }

    public void setFontFamily(int font) {
        prefs.edit().putInt("font_family", font).apply();
    }

    // Brightness: -1 = follow system, 0..100 = custom screen brightness %
    public int getScreenBrightness() {
        return prefs.getInt("screen_brightness", 65);
    }

    public void setScreenBrightness(int brightnessPercent) {
        int clamped = Math.max(1, Math.min(100, brightnessPercent));
        prefs.edit().putInt("screen_brightness", clamped).apply();
    }

    public boolean isCustomBrightnessEnabled() {
        return prefs.getBoolean("custom_brightness_enabled", false);
    }

    public void setCustomBrightnessEnabled(boolean enabled) {
        prefs.edit().putBoolean("custom_brightness_enabled", enabled).apply();
    }

    // Font Size: 12sp .. 38sp (default 20sp)
    public float getFontSizeSp() {
        return prefs.getFloat("font_size_sp", 20f);
    }

    public void setFontSizeSp(float sizeSp) {
        float clamped = Math.max(12f, Math.min(38f, sizeSp));
        prefs.edit().putFloat("font_size_sp", clamped).apply();
    }

    // Line Spacing Multiplier: 1.00 .. 2.50 (default 1.60)
    public float getLineSpacingMultiplier() {
        return prefs.getFloat("line_spacing", 1.60f);
    }

    public void setLineSpacingMultiplier(float mult) {
        float clamped = Math.max(1.00f, Math.min(2.50f, mult));
        prefs.edit().putFloat("line_spacing", clamped).apply();
    }

    // Paragraph Spacing: 0.00 em .. 2.00 em (default 0.55 em)
    public float getParagraphSpacingEm() {
        return prefs.getFloat("paragraph_spacing_em", 0.55f);
    }

    public void setParagraphSpacingEm(float em) {
        float clamped = Math.max(0.00f, Math.min(2.00f, em));
        prefs.edit().putFloat("paragraph_spacing_em", clamped).apply();
    }

    // Letter Spacing: -0.050 em .. 0.300 em (default 0.000 em)
    public float getLetterSpacingEm() {
        return prefs.getFloat("letter_spacing_em", 0.000f);
    }

    public void setLetterSpacingEm(float em) {
        float clamped = Math.max(-0.050f, Math.min(0.300f, em));
        prefs.edit().putFloat("letter_spacing_em", clamped).apply();
    }

    // Horizontal Margin: 0 dp .. 48 dp (default 14 dp)
    public int getMarginDp() {
        return prefs.getInt("margin_dp", 14);
    }

    public void setMarginDp(int dp) {
        prefs.edit().putInt("margin_dp", Math.max(0, Math.min(48, dp))).apply();
    }

    // Full-Screen Reading toggle (default true)
    public boolean isFullScreenReading() {
        return prefs.getBoolean("fullscreen_reading", true);
    }

    public void setFullScreenReading(boolean fullScreen) {
        prefs.edit().putBoolean("fullscreen_reading", fullScreen).apply();
    }

    // First-Line Indentation toggle (default true)
    public boolean isFirstLineIndent() {
        return prefs.getBoolean("first_line_indent", true);
    }

    public void setFirstLineIndent(boolean indent) {
        prefs.edit().putBoolean("first_line_indent", indent).apply();
    }

    // Full Justification (两端对齐) toggle (default true)
    public boolean isJustifyAlign() {
        return prefs.getBoolean("justify_align", true);
    }

    public void setJustifyAlign(boolean justify) {
        prefs.edit().putBoolean("justify_align", justify).apply();
    }

    public int getOrientationMode() {
        return prefs.getInt("orientation_mode", ORIENTATION_AUTO);
    }

    public void setOrientationMode(int mode) {
        prefs.edit().putInt("orientation_mode", mode).apply();
    }

    public int getBrowseMode() {
        return prefs.getInt("browse_mode", BROWSE_MODE_PAGED);
    }

    public void setBrowseMode(int mode) {
        prefs.edit().putInt("browse_mode", mode).apply();
    }

    public int getBookshelfLayoutMode() {
        return prefs.getInt("bookshelf_layout_mode", BOOKSHELF_LAYOUT_LIST);
    }

    public void setBookshelfLayoutMode(int mode) {
        prefs.edit().putInt("bookshelf_layout_mode", mode).apply();
    }

    public int getPageTurnAnim() {
        return prefs.getInt("page_turn_anim", ANIM_COVER);
    }

    public void setPageTurnAnim(int anim) {
        prefs.edit().putInt("page_turn_anim", anim).apply();
    }

    public boolean isPdfDarkInvert() {
        return prefs.getBoolean("pdf_dark_invert", false);
    }

    public void setPdfDarkInvert(boolean invert) {
        prefs.edit().putBoolean("pdf_dark_invert", invert).apply();
    }

    public boolean isPdfAutoCrop() {
        return prefs.getBoolean("pdf_auto_crop", true);
    }

    public void setPdfAutoCrop(boolean autoCrop) {
        prefs.edit().putBoolean("pdf_auto_crop", autoCrop).apply();
    }

    public boolean isPdfReflowEnabledForBook(long bookId) {
        if (prefs.contains("pdf_reflow_book_" + bookId)) {
            return prefs.getBoolean("pdf_reflow_book_" + bookId, true);
        }
        return prefs.getBoolean("pdf_reflow_default", true);
    }

    public void setPdfReflowEnabledForBook(long bookId, boolean enabled) {
        prefs.edit()
                .putBoolean("pdf_reflow_book_" + bookId, enabled)
                .putBoolean("pdf_reflow_default", enabled)
                .apply();
    }

    public boolean isTradToSimpEnabledForBook(long bookId) {
        return prefs.getBoolean("trad_to_simp_book_" + bookId, false);
    }

    public void setTradToSimpEnabledForBook(long bookId, boolean enabled) {
        prefs.edit().putBoolean("trad_to_simp_book_" + bookId, enabled).apply();
    }

    public static ColorMatrixColorFilter createPdfThemeFilter(int theme, boolean forceDarkInvert) {
        boolean isDark = forceDarkInvert || theme == THEME_READEST_NORD || theme == THEME_AMOLED_NIGHT;
        int bg = getBackgroundColor(theme);
        int fg = getTextColor(theme);
        if (isDark && theme != THEME_READEST_NORD && theme != THEME_AMOLED_NIGHT) {
            bg = Color.parseColor("#121417");
            fg = Color.parseColor("#DCE2EA");
        }

        float bgR = Color.red(bg);
        float bgG = Color.green(bg);
        float bgB = Color.blue(bg);

        float fgR = Color.red(fg);
        float fgG = Color.green(fg);
        float fgB = Color.blue(fg);

        float scaleR = (bgR - fgR) / 255f;
        float scaleG = (bgG - fgG) / 255f;
        float scaleB = (bgB - fgB) / 255f;

        float[] matrix = new float[]{
                scaleR, 0f,     0f,     0f, fgR,
                0f,     scaleG, 0f,     0f, fgG,
                0f,     0f,     scaleB, 0f, fgB,
                0f,     0f,     0f,     1f, 0f
        };
        return new ColorMatrixColorFilter(matrix);
    }

    public static String getBrowseModeName(int mode) {
        return mode == BROWSE_MODE_SCROLL ? "上下滚动" : "左右翻页";
    }

    public static String getPageAnimName(int anim) {
        switch (anim) {
            case ANIM_COVER: return "覆盖翻页";
            case ANIM_SLIDE: return "平移滑动";
            case ANIM_SIMULATION: return "仿真翻书";
            case ANIM_FADE: return "柔和渐变";
            case ANIM_NONE: return "无动画(极速)";
            default: return "覆盖翻页";
        }
    }

    public static String getThemeName(int theme) {
        switch (theme) {
            case THEME_DAY_CLASSIC: return "日间纯白";
            case THEME_READERA_SEPIA: return "羊皮纸 (ReadEra)";
            case THEME_READEST_SAGE: return "豆沙护眼绿 (Readest)";
            case THEME_READEST_WARM: return "暖米纸张 (Readest)";
            case THEME_READEST_NORD: return "暗夜极光 (Nord)";
            case THEME_AMOLED_NIGHT: return "纯黑夜间 (OLED)";
            default: return "羊皮纸";
        }
    }

    public static int getBackgroundColor(int theme) {
        switch (theme) {
            case THEME_DAY_CLASSIC: return Color.parseColor("#FFFFFF");
            case THEME_READERA_SEPIA: return Color.parseColor("#F4ECD8");
            case THEME_READEST_SAGE: return Color.parseColor("#E3EFE3");
            case THEME_READEST_WARM: return Color.parseColor("#FAF6F0");
            case THEME_READEST_NORD: return Color.parseColor("#2E3440");
            case THEME_AMOLED_NIGHT: return Color.parseColor("#080808");
            default: return Color.parseColor("#F4ECD8");
        }
    }

    public static int getTextColor(int theme) {
        switch (theme) {
            case THEME_DAY_CLASSIC: return Color.parseColor("#1F2328");
            case THEME_READERA_SEPIA: return Color.parseColor("#3B2F2F");
            case THEME_READEST_SAGE: return Color.parseColor("#1E3224");
            case THEME_READEST_WARM: return Color.parseColor("#2C2623");
            case THEME_READEST_NORD: return Color.parseColor("#D8DEE9");
            case THEME_AMOLED_NIGHT: return Color.parseColor("#B8C0C8");
            default: return Color.parseColor("#3B2F2F");
        }
    }

    public static int getSecondaryTextColor(int theme) {
        switch (theme) {
            case THEME_READEST_NORD: return Color.parseColor("#9AA5B8");
            case THEME_AMOLED_NIGHT: return Color.parseColor("#788088");
            default: return Color.parseColor("#6E6259");
        }
    }

    public static int getToolbarColor(int theme) {
        switch (theme) {
            case THEME_DAY_CLASSIC: return Color.parseColor("#F5F6F8");
            case THEME_READERA_SEPIA: return Color.parseColor("#EAE0C8");
            case THEME_READEST_SAGE: return Color.parseColor("#D4E4D4");
            case THEME_READEST_WARM: return Color.parseColor("#EFEAE1");
            case THEME_READEST_NORD: return Color.parseColor("#242933");
            case THEME_AMOLED_NIGHT: return Color.parseColor("#141414");
            default: return Color.parseColor("#EAE0C8");
        }
    }

    public static Typeface getTypeface(int fontFamily) {
        switch (fontFamily) {
            case FONT_SERIF:
                return Typeface.SERIF;
            case FONT_MONO:
                return Typeface.MONOSPACE;
            case FONT_CLASSIC:
                return Typeface.create("serif", Typeface.ITALIC);
            case FONT_SANS:
            default:
                return Typeface.SANS_SERIF;
        }
    }

    public static String getFontName(int fontFamily) {
        switch (fontFamily) {
            case FONT_SERIF: return "衬线宋体";
            case FONT_MONO: return "等宽字体";
            case FONT_CLASSIC: return "经典书刻";
            case FONT_SANS:
            default: return "无衬线黑体";
        }
    }

    // Z-Library Persistent One-Time Login & Mirror Settings
    public void saveZlibCredentials(String email, String password) {
        String encodedEmail = Base64.encodeToString(email.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        String encodedPass = Base64.encodeToString(password.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        prefs.edit()
                .putString("zlib_email_b64", encodedEmail)
                .putString("zlib_pass_b64", encodedPass)
                .apply();
    }

    public String getZlibEmail() {
        String b64 = prefs.getString("zlib_email_b64", "");
        if (b64 == null || b64.isEmpty()) return "";
        try {
            return new String(Base64.decode(b64, Base64.NO_WRAP), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    public String getZlibPassword() {
        String b64 = prefs.getString("zlib_pass_b64", "");
        if (b64 == null || b64.isEmpty()) return "";
        try {
            return new String(Base64.decode(b64, Base64.NO_WRAP), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    public boolean hasZlibCredentials() {
        return !getZlibEmail().isEmpty() && !getZlibPassword().isEmpty();
    }

    public void saveZlibAuthTokens(String remixUserId, String remixUserKey) {
        prefs.edit()
                .putString("zlib_remix_userid", remixUserId != null ? remixUserId : "")
                .putString("zlib_remix_userkey", remixUserKey != null ? remixUserKey : "")
                .apply();
    }

    public String getZlibRemixUserId() {
        return prefs.getString("zlib_remix_userid", "");
    }

    public String getZlibRemixUserKey() {
        return prefs.getString("zlib_remix_userkey", "");
    }

    public boolean hasZlibAuthTokens() {
        String uid = getZlibRemixUserId();
        String ukey = getZlibRemixUserKey();
        return uid != null && !uid.isEmpty() && ukey != null && !ukey.isEmpty();
    }

    public void setActiveZlibMirror(String url) {
        prefs.edit().putString("zlib_active_mirror", url).apply();
    }

    public String getActiveZlibMirror() {
        return prefs.getString("zlib_active_mirror", "https://zh.z-lib.gd/");
    }

    public void setCachedZlibMirrors(String joinedUrls) {
        prefs.edit()
                .putString("zlib_cached_mirrors", joinedUrls)
                .putLong("zlib_last_sync", System.currentTimeMillis())
                .apply();
    }

    public String getCachedZlibMirrors() {
        return prefs.getString("zlib_cached_mirrors", "");
    }

    public long getLastZlibSyncTime() {
        return prefs.getLong("zlib_last_sync", 0L);
    }

    // ==================== 5. FOREIGN BOOK NEURAL & AI LLM TRANSLATION SETTINGS ====================
    public static final int TRANS_MODE_OFF = 0;          // 原文模式
    public static final int TRANS_MODE_CHINESE_ONLY = 1; // ✨ 纯中文译文模式（自然排版）
    public static final int TRANS_MODE_BILINGUAL = 2;    // 📖 原文+译文双语对照模式

    public static final int ENGINE_BUILTIN_DIRECT = 0;   // 🚀 免配置·内置国内直连神经网络翻译（默认·免翻墙）
    public static final int ENGINE_DEEPSEEK = 1;         // 🐋 DeepSeek 大模型（国内直连免翻墙）
    public static final int ENGINE_QWEN = 2;             // ☁️ 阿里通义千问 Qwen（国内直连免翻墙）
    public static final int ENGINE_GEMINI = 3;           // ✨ Google Gemini (支持官方或国内反代地址)
    public static final int ENGINE_OPENAI = 4;           // 🤖 OpenAI (支持官方或国内中转地址)

    // 翻译语气 / 风格选择
    public static final int TONE_LITERATURE = 0; // 文学小说类（文学味、画面感、注重韵律）
    public static final int TONE_ACADEMIC = 1;   // 社科 / 历史 / 哲学类（学术规范、严谨典雅、逻辑清晰）
    public static final int TONE_BUSINESS = 2;   // 商业 / 职场 / 自我提升类（通俗易懂、有力干练、易于落地）
    public static final int TONE_TECH = 3;       // 科普 / 技术 / 实用教程类（准确清晰、深入浅出）

    public int getBookTranslationMode(long bookId) {
        return prefs.getInt("book_trans_mode_" + bookId, TRANS_MODE_OFF);
    }

    public void setBookTranslationMode(long bookId, int mode) {
        prefs.edit().putInt("book_trans_mode_" + bookId, Math.max(0, Math.min(2, mode))).apply();
    }

    public int getTranslationEngine() {
        return prefs.getInt("book_trans_engine", ENGINE_BUILTIN_DIRECT);
    }

    public void setTranslationEngine(int engine) {
        prefs.edit().putInt("book_trans_engine", Math.max(0, Math.min(4, engine))).apply();
    }

    public int getTranslationTone() {
        return prefs.getInt("book_trans_tone", TONE_LITERATURE);
    }

    public void setTranslationTone(int tone) {
        prefs.edit().putInt("book_trans_tone", Math.max(0, Math.min(3, tone))).apply();
    }

    public static String getTranslationToneDisplayName(int tone) {
        switch (tone) {
            case TONE_ACADEMIC:
                return "🏛️ 社科 / 历史 / 哲学类";
            case TONE_BUSINESS:
                return "💼 商业 / 职场 / 自我提升类";
            case TONE_TECH:
                return "🔬 科普 / 技术 / 实用教程类";
            case TONE_LITERATURE:
            default:
                return "📖 文学小说类";
        }
    }

    public static String getTranslationToneShortName(int tone) {
        switch (tone) {
            case TONE_ACADEMIC: return "社科/哲学";
            case TONE_BUSINESS: return "商业/职场";
            case TONE_TECH: return "科普/技术";
            case TONE_LITERATURE:
            default: return "文学小说";
        }
    }

    public static String getTranslationToneBasePrompt(int tone) {
        switch (tone) {
            case TONE_ACADEMIC:
                return "请翻译以下社科/历史类文本。要求：语言严谨规范、逻辑清晰，符合中文学术出版物的表达习惯。在准确传达概念的基础上，使长难句结构符合中文的阅读节奏，避免翻译腔和生硬的连接词。";
            case TONE_BUSINESS:
                return "请将以下商业/自驱类内容翻译成中文。要求：语言干练有力、通俗易懂，符合主流商业图书的出版风格。使用地道的中文商务和职场用语，句式精炼，避免啰嗦与生硬直译。";
            case TONE_TECH:
                return "请翻译以下科普/技术文本。要求：专业术语准确无误，行文深入浅出、流畅自然。用符合中文习惯的叙述逻辑重新组织复杂概念，确保读者能够轻松理解，无阅读障碍。";
            case TONE_LITERATURE:
            default:
                return "请将以下小说翻译成中文。要求：行文流畅自然，富有文学色彩与画面感，避免机械直译。请根据上下文灵活处理语序，注重语气与表达的韵律，使其读起来像本土作家创作的文学作品。";
        }
    }

    public String getAiApiKey(int engine) {
        return prefs.getString("ai_api_key_" + engine, "").trim();
    }

    public void setAiApiKey(int engine, String apiKey) {
        prefs.edit().putString("ai_api_key_" + engine, apiKey != null ? apiKey.trim() : "").apply();
    }

    public String getAiBaseUrl(int engine) {
        String saved = prefs.getString("ai_base_url_" + engine, "").trim();
        if (!saved.isEmpty()) return saved;
        return getDefaultAiBaseUrl(engine);
    }

    public void setAiBaseUrl(int engine, String baseUrl) {
        prefs.edit().putString("ai_base_url_" + engine, baseUrl != null ? baseUrl.trim() : "").apply();
    }

    public String getAiModelName(int engine) {
        String saved = prefs.getString("ai_model_name_" + engine, "").trim();
        if (!saved.isEmpty()) return saved;
        return getDefaultAiModelName(engine);
    }

    public void setAiModelName(int engine, String modelName) {
        prefs.edit().putString("ai_model_name_" + engine, modelName != null ? modelName.trim() : "").apply();
    }

    public static String getEngineDisplayName(int engine) {
        switch (engine) {
            case ENGINE_DEEPSEEK:
                return "DeepSeek";
            case ENGINE_QWEN:
                return "Qwen";
            case ENGINE_GEMINI:
                return "Gemini";
            case ENGINE_OPENAI:
                return "OpenAI";
            case ENGINE_BUILTIN_DIRECT:
            default:
                return "免配置神经网络翻译";
        }
    }

    public static String getEngineShortName(int engine) {
        switch (engine) {
            case ENGINE_DEEPSEEK: return "DeepSeek";
            case ENGINE_QWEN: return "Qwen";
            case ENGINE_GEMINI: return "Gemini";
            case ENGINE_OPENAI: return "OpenAI";
            case ENGINE_BUILTIN_DIRECT:
            default: return "免配置翻译";
        }
    }

    public static String getDefaultAiBaseUrl(int engine) {
        switch (engine) {
            case ENGINE_DEEPSEEK:
                return "https://api.deepseek.com/v1/chat/completions";
            case ENGINE_QWEN:
                return "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions";
            case ENGINE_GEMINI:
                return "https://gemini.aivoice.top/v1beta";
            case ENGINE_OPENAI:
                return "https://api.openai.com/v1/chat/completions";
            default:
                return "https://api.deepseek.com/v1/chat/completions";
        }
    }

    public static String[] getPresetBaseUrlsForEngine(int engine) {
        switch (engine) {
            case ENGINE_GEMINI:
                return new String[]{
                        "https://gemini.aivoice.top/v1beta (自建专属国内直连反代·推荐)",
                        "https://generativelanguage.googleapis.com/v1beta (Google官方·需翻墙)",
                        "https://api.openai-proxy.org/v1 (公共中转备用一)",
                        "https://api.chatanywhere.tech/v1 (公共中转备用二)",
                        "自定义接口地址..."
                };
            case ENGINE_OPENAI:
                return new String[]{
                        "https://api.openai.com/v1/chat/completions (官方地址·需翻墙)",
                        "https://api.openai-proxy.org/v1/chat/completions (国内免翻墙中转一·推荐)",
                        "https://api.chatanywhere.tech/v1/chat/completions (国内免翻墙中转二)",
                        "https://api.ai-proxy.org/v1/chat/completions (国内免翻墙中转三)",
                        "自定义接口地址..."
                };
            case ENGINE_DEEPSEEK:
                return new String[]{
                        "https://api.deepseek.com/v1/chat/completions (国内官方直连·免翻墙)",
                        "自定义接口地址..."
                };
            case ENGINE_QWEN:
                return new String[]{
                        "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions (国内阿里官方·免翻墙)",
                        "自定义接口地址..."
                };
            default:
                return new String[]{
                        getDefaultAiBaseUrl(engine),
                        "自定义接口地址..."
                };
        }
    }

    public static String getDefaultAiModelName(int engine) {
        switch (engine) {
            case ENGINE_DEEPSEEK:
                return "deepseek-v4.1 flash";
            case ENGINE_QWEN:
                return "Qwen 3.8 flash";
            case ENGINE_GEMINI:
                return "Gemini 3.8 flash";
            case ENGINE_OPENAI:
                return "GPT-6 Luna";
            default:
                return "deepseek-v4.1 flash";
        }
    }

    public static String[] getPresetModelsForEngine(int engine) {
        switch (engine) {
            case ENGINE_DEEPSEEK:
                return new String[]{
                        "deepseek-v4.1 flash",
                        "deepseek-v4-pro"
                };
            case ENGINE_QWEN:
                return new String[]{
                        "Qwen 3.8 flash",
                        "Qwen 3.8 MAX",
                        "Qwen 3.7 Plus"
                };
            case ENGINE_GEMINI:
                return new String[]{
                        "Gemini 3.8 flash",
                        "Gemini 3.7 flash"
                };
            case ENGINE_OPENAI:
                return new String[]{
                        "GPT-6 Luna",
                        "GPT-6 Sol",
                        "GPT-6 Astra"
                };
            default:
                return new String[]{
                        "deepseek-v4.1 flash",
                        "deepseek-v4-pro"
                };
        }
    }
}
