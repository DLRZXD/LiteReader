package com.litereader.app.parser;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.pdf.PdfDocument;

import com.litereader.app.data.ReaderDatabase;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Generates sample offline books across all supported formats
 * (EPUB, PDF, AZW3, MOBI, TXT, .fb2, .fb2.zip, .fbz) organized into folders
 * so the user can test every format, folder filter, bookmark, and plain-text note immediately.
 */
public class SampleBooksGenerator {

    public static void ensureSampleBooks(Context context, ReaderDatabase db) {
        android.content.SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences("litereader_prefs", Context.MODE_PRIVATE);
        if (prefs.getBoolean("sample_books_seeded_v1", false)) {
            return;
        }
        prefs.edit().putBoolean("sample_books_seeded_v1", true).apply();

        File baseDir = new File(context.getFilesDir(), "SampleLibrary");
        File classicDir = new File(baseDir, "经典文学_Classics");
        File techDir = new File(baseDir, "使用指南_Guides");
        File scifiDir = new File(baseDir, "科幻与哲思_SciFi");

        classicDir.mkdirs();
        techDir.mkdirs();
        scifiDir.mkdirs();

        try {
            // 1. EPUB
            File epubFile = new File(classicDir, "极简阅读之道_ReadEra与Readest设计哲学.epub");
            if (!epubFile.exists()) {
                createSampleEpub(epubFile);
            }
            db.upsertBookFromFile(epubFile, "极简阅读之道：沉浸式本地阅读指南", "LiteReader 编辑部");

            // 2. PDF
            File pdfFile = new File(techDir, "LiteReader离线阅读器与ZLibrary全攻略.pdf");
            if (!pdfFile.exists()) {
                createSamplePdf(pdfFile);
            }
            db.upsertBookFromFile(pdfFile, "LiteReader 离线阅读器与 Z-Library 全攻略", "LiteReader 官方手册");

            // 3. TXT
            File txtFile = new File(classicDir, "陶渊明集_桃花源记与归去来兮辞.txt");
            if (!txtFile.exists()) {
                createSampleTxt(txtFile);
            }
            db.upsertBookFromFile(txtFile, "陶渊明集：桃花源记与归去来兮辞", "陶渊明");

            // 4. FB2 (.fb2, .fb2.zip, .fbz)
            File fb2File = new File(scifiDir, "星际灯塔_银河漫游手记.fb2");
            if (!fb2File.exists()) {
                createSampleFb2(fb2File, false);
            }
            db.upsertBookFromFile(fb2File, "星际灯塔：银河漫游手记 (.fb2)", "艾萨克·林");

            File fb2ZipFile = new File(scifiDir, "量子图书馆之谜.fb2.zip");
            if (!fb2ZipFile.exists()) {
                createSampleFb2(fb2ZipFile, true);
            }
            db.upsertBookFromFile(fb2ZipFile, "量子图书馆之谜 (.fb2.zip)", "安娜·卡列尼娜");

            File fbzFile = new File(scifiDir, "深空旅人笔记.fbz");
            if (!fbzFile.exists()) {
                createSampleFb2(fbzFile, true);
            }
            db.upsertBookFromFile(fbzFile, "深空旅人笔记 (.fbz)", "卡尔·萨根致敬版");

            // 5. MOBI & AZW3
            File mobiFile = new File(classicDir, "苏东坡词选_赤壁怀古.mobi");
            if (!mobiFile.exists()) {
                createSampleMobiOrAzw3(mobiFile, "苏东坡词选：赤壁怀古与定风波", "苏轼",
                        "<h1>卷一：念奴娇·赤壁怀古</h1><p>大江东去，浪淘尽，千古风流人物。故垒西边，人道是，三国周郎赤壁。乱石穿空，惊涛拍岸，卷起千堆雪。江山如画，一时多少豪杰。</p><p>遥想公瑾当年，小乔初嫁了，雄姿英发。羽扇纶巾，谈笑间，樯橹灰飞烟灭。故国神游，多情应笑我，早生华发。人生如梦，一尊还酹江月。</p>" +
                        "<mbp:pagebreak/><h1>卷二：定风波·莫听穿林打叶声</h1><p>三月七日，沙湖道中遇雨。雨具先去，同行皆狼狈，余独不觉。已而遂晴，故作此词。</p><p>莫听穿林打叶声，何妨吟啸且徐行。竹杖芒鞋轻胜马，谁怕？一蓑烟雨任平生。料峭春风吹酒醒，微冷，山头斜照却相迎。回首向来萧瑟处，归去，也无风雨也无晴。</p>" +
                        "<mbp:pagebreak/><h1>卷三：水调歌头·明月几时有</h1><p>丙辰中秋，欢饮达旦，大醉，作此篇，兼怀子由。</p><p>明月几时有？把酒问青天。不知天上宫阙，今夕是何年。我欲乘风归去，又恐琼楼玉宇，高处不胜寒。起舞弄清影，何似在人间。转朱阁，低绮户，照无眠。不应有恨，何事长向别时圆？人有悲欢离合，月有阴晴圆缺，此事古难全。但愿人长久，千里共婵娟。</p>");
            }
            db.upsertBookFromFile(mobiFile, "苏东坡词选：赤壁怀古与定风波", "苏轼");

            File azw3File = new File(techDir, "数字极简主义_专注力重建手册.azw3");
            if (!azw3File.exists()) {
                createSampleMobiOrAzw3(azw3File, "数字极简主义：专注力重建手册", "卡尔·纽波特风格导读",
                        "<h1>第一章：为什么我们需要离线阅读</h1><p>在通知泛滥与算法信息流主导的时代，深度阅读（Deep Reading）已成为最稀缺的认知能力。一款极简的离线阅读器不设开屏广告、不设社交打扰，只保留你与文字本身。</p><p>无论是横屏双手握持，还是竖屏单手翻阅，版面都应当自动贴合你的视线宽度。</p>" +
                        "<mbp:pagebreak/><h1>第二章：纯文本笔记的力量</h1><p>为什么我们坚持将你的每一条读书笔记同步保存为本地纯文本（.txt）？因为专有数据库格式可能随软件更迭而消亡，但纯文本文件在三十年后的任何操作系统上依然可以直接打开、检索和备份。</p>");
            }
            db.upsertBookFromFile(azw3File, "数字极简主义：专注力重建手册", "专注力实验室");

        } catch (Exception ignored) {
        }
    }

    private static void createSampleEpub(File out) throws Exception {
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(out))) {
            // mimetype
            zos.putNextEntry(new ZipEntry("mimetype"));
            zos.write("application/epub+zip".getBytes(StandardCharsets.US_ASCII));
            zos.closeEntry();

            // META-INF/container.xml
            zos.putNextEntry(new ZipEntry("META-INF/container.xml"));
            String container = "<?xml version=\"1.0\"?>\n" +
                    "<container version=\"1.0\" xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\">\n" +
                    "  <rootfiles>\n" +
                    "    <rootfile full-path=\"OEBPS/content.opf\" media-type=\"application/oebps-package+xml\"/>\n" +
                    "  </rootfiles>\n" +
                    "</container>";
            zos.write(container.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            // OEBPS/content.opf
            zos.putNextEntry(new ZipEntry("OEBPS/content.opf"));
            String opf = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                    "<package xmlns=\"http://www.idpf.org/2007/opf\" unique-identifier=\"BookId\" version=\"2.0\">\n" +
                    "  <metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\">\n" +
                    "    <dc:title>极简阅读之道：沉浸式本地阅读指南</dc:title>\n" +
                    "    <dc:creator>LiteReader 编辑部</dc:creator>\n" +
                    "  </metadata>\n" +
                    "  <manifest>\n" +
                    "    <item id=\"ch1\" href=\"ch1.xhtml\" media-type=\"application/xhtml+xml\"/>\n" +
                    "    <item id=\"ch2\" href=\"ch2.xhtml\" media-type=\"application/xhtml+xml\"/>\n" +
                    "    <item id=\"ch3\" href=\"ch3.xhtml\" media-type=\"application/xhtml+xml\"/>\n" +
                    "  </manifest>\n" +
                    "  <spine>\n" +
                    "    <itemref idref=\"ch1\"/>\n" +
                    "    <itemref idref=\"ch2\"/>\n" +
                    "    <itemref idref=\"ch3\"/>\n" +
                    "  </spine>\n" +
                    "</package>";
            zos.write(opf.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            writeZipString(zos, "OEBPS/ch1.xhtml",
                    "<html><body><h1>第一章：致敬 ReadEra 与 Readest 的极简哲学</h1>" +
                    "<p>欢迎使用极简阅读（LiteReader）。这是一款专为深度阅读者打造的轻量级离线 Android 阅读器，汲取了 ReadEra 高效的本地书库管理与 Readest 雅致的护眼排版体验。</p>" +
                    "<p>在书架界面，你可以随时通过顶部的格式筛选器在 EPUB、PDF、AZW3、MOBI、TXT 和 FB2（含 .fb2、.fb2.zip 与 .fbz）之间一键切换，或者开启「文件夹感知」模式，按本地目录结构浏览藏书。</p>" +
                    "<p>每一本书都会自动记录你的阅读章节、滚动位置与百分比进度，并在「最近阅读」中置顶展示，让你随时无缝续读。</p></body></html>");

            writeZipString(zos, "OEBPS/ch2.xhtml",
                    "<html><body><h1>第二章：多主题护眼排版与横竖屏自适应</h1>" +
                    "<p>点击阅读界面中央或顶部设置按钮，即可唤出排版控制面板。我们内置了六款精心调校的阅读主题：日间纯白、ReadEra 经典羊皮纸、Readest 豆沙护眼绿、暖米纸张、Nord 暗夜极光以及 OLED 纯黑夜间模式。</p>" +
                    "<p>你可以自由切换衬线宋/明体、无衬线黑体、等宽体与经典书刻字体，无级调节字号（12sp–38sp）、行距与页边距，并支持跟随系统或手动锁定横屏、竖屏。</p></body></html>");

            writeZipString(zos, "OEBPS/ch3.xhtml",
                    "<html><body><h1>第三章：Z-Library 自动直连更新与一次登录免填</h1>" +
                    "<p>在「Z-Library」标签页中，应用每次打开都会自动从 dongyubin/Awesome-Zlibrary 最新网站发布页同步标有 ✅ 的国内可直连镜像网址，自动剔除黑名单假站并测速优选最快节点。</p>" +
                    "<p>你只需在「账号设置」中输入一次 Z-Library 邮箱与密码，此后无论镜像网址如何轮换变更，应用都会在新域名下自动完成登录注入与会话接力，点击下载即可直接导入本地书架阅读。</p></body></html>");
        }
    }

    private static void createSamplePdf(File out) throws Exception {
        PdfDocument doc = new PdfDocument();
        String[][] pages = new String[][]{
                {
                        "LiteReader 离线阅读器 & Z-Library 指南",
                        "第 1 页 · 极简架构与全格式支持",
                        "1. 支持格式：EPUB、PDF、AZW3、MOBI、TXT、FB2 (.fb2 / .fb2.zip / .fbz)",
                        "2. 横竖屏与多分辨率适配：手机、折叠屏、平板均自动调整网格与排版边距。",
                        "3. 进度记忆：实时保存阅读章节、页码与滚动位置，支持添加书签与摘录。",
                        "4. 本地纯文本笔记：所有读书笔记实时同步生成标准 UTF-8 .txt 纯文本文件。"
                },
                {
                        "Z-Library 智能直连与免重复登录机制",
                        "第 2 页 · 自动同步 Awesome-Zlibrary 发布页",
                        "1. 每次启动自动拉取 GitHub dongyubin/Awesome-Zlibrary 最新列表。",
                        "2. 智能识别表格中标记为 ✅ 的可直连官方/镜像域名，过滤钓鱼黑名单。",
                        "3. 多节点并发测速，自动切换至响应最快的可用直连域名。",
                        "4. 账号密码只需填写一次：域名变化时自动在新域名注入登录凭证与 Cookie。"
                }
        };

        Paint titlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        titlePaint.setColor(Color.parseColor("#1E3224"));
        titlePaint.setTextSize(20f);
        titlePaint.setFakeBoldText(true);

        Paint subPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        subPaint.setColor(Color.parseColor("#2D5A4C"));
        subPaint.setTextSize(14f);
        subPaint.setFakeBoldText(true);

        Paint bodyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bodyPaint.setColor(Color.parseColor("#2C2623"));
        bodyPaint.setTextSize(12.5f);

        Paint bgPaint = new Paint();
        bgPaint.setColor(Color.parseColor("#FAF6F0"));

        for (int i = 0; i < pages.length; i++) {
            PdfDocument.PageInfo info = new PdfDocument.PageInfo.Builder(595, 842, i + 1).create();
            PdfDocument.Page page = doc.startPage(info);
            Canvas canvas = page.getCanvas();
            canvas.drawRect(0, 0, 595, 842, bgPaint);

            String[] lines = pages[i];
            canvas.drawText(lines[0], 48, 80, titlePaint);
            canvas.drawText(lines[1], 48, 115, subPaint);

            int y = 170;
            for (int j = 2; j < lines.length; j++) {
                canvas.drawText(lines[j], 48, y, bodyPaint);
                y += 42;
            }
            doc.finishPage(page);
        }
        try (FileOutputStream fos = new FileOutputStream(out)) {
            doc.writeTo(fos);
        }
        doc.close();
    }

    private static void createSampleTxt(File out) throws Exception {
        String txt = "第一章 桃花源记\n" +
                "晋太元中，武陵人捕鱼为业。缘溪行，忘路之远近。忽逢桃花林，夹岸数百步，中无杂树，芳草鲜美，落英缤纷。渔人甚异之，复前行，欲穷其林。\n" +
                "林尽水源，便得一山，山有小口，仿佛若有光。便舍船，从口入。初极狭，才通人。复行数十步，豁然开朗。土地平旷，屋舍俨然，有良田、美池、桑竹之属。阡陌交通，鸡犬相闻。其中往来种作，男女衣着，悉如外人。黄发垂髫，并怡然自乐。\n\n" +
                "第二章 归去来兮辞\n" +
                "归去来兮，田园将芜胡不归？既自以心为形役，奚惆悵而独悲？悟已往之不谏，知来者之可追。实迷途其未远，觉今是而昨非。舟遥遥以轻飏，风飘飘而吹衣。问征夫以前路，恨晨光之熹微。\n" +
                "乃瞻衡宇，载欣载奔。僮仆欢迎，稚子候门。三径就荒，松菊犹存。携幼入室，有酒盈樽。引壶觞以自酌，眄庭柯以怡颜。倚南窗以寄傲，审容膝之易安。\n\n" +
                "第三章 五柳先生传\n" +
                "先生不知何许人也，亦不详其姓字，宅边有五柳树，因以为号焉。闲静少言，不慕荣利。好读书，不求甚解；每有会意，便欣然忘食。性嗜酒，家贫不能常得。亲旧知其如此，或置酒而招之；造饮辄尽，期在必醉。既醉而退，曾不吝情去留。\n";
        try (FileOutputStream fos = new FileOutputStream(out)) {
            fos.write(txt.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void createSampleFb2(File out, boolean zipWrap) throws Exception {
        String fb2Xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<FictionBook xmlns=\"http://www.gribuser.ru/xml/fictionbook/2.0\">\n" +
                "  <description>\n" +
                "    <title-info>\n" +
                "      <book-title>" + out.getName() + "</book-title>\n" +
                "      <author><first-name>艾萨克</first-name><last-name>林</last-name></author>\n" +
                "    </title-info>\n" +
                "  </description>\n" +
                "  <body>\n" +
                "    <section>\n" +
                "      <title><p>第一卷：边缘星系的静默灯塔</p></title>\n" +
                "      <p>在旋臂最外侧的第四十二号观测站，所有的全息屏幕都已调成了护眼的暖色纸张模式。值守员打开了保存完好的 FictionBook 档案库。</p>\n" +
                "      <p>无论文件是以原生 .fb2、压缩归档 .fb2.zip 还是 .fbz 形态封存，解析引擎都能瞬间还原出清晰的章节脉络与段落层次。</p>\n" +
                "    </section>\n" +
                "    <section>\n" +
                "      <title><p>第二卷：跨越光年的读书笔记</p></title>\n" +
                "      <p>他在页边记下一行纯文本笔记：唯有极简与开放的格式，能够穿越漫长的岁月而不失真。</p>\n" +
                "    </section>\n" +
                "  </body>\n" +
                "</FictionBook>";

        if (zipWrap) {
            try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(out))) {
                writeZipString(zos, "book.fb2", fb2Xml);
            }
        } else {
            try (FileOutputStream fos = new FileOutputStream(out)) {
                fos.write(fb2Xml.getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    private static void createSampleMobiOrAzw3(File out, String title, String author, String htmlContent) throws Exception {
        byte[] textBytes = htmlContent.getBytes(StandardCharsets.UTF_8);
        byte[] titleBytes = title.getBytes(StandardCharsets.UTF_8);
        byte[] authorBytes = author.getBytes(StandardCharsets.UTF_8);

        // Build EXTH header with Author (type 100) and Updated Title (type 503)
        int exthRec1Len = 8 + authorBytes.length;
        int exthRec2Len = 8 + titleBytes.length;
        int exthRawLen = 12 + exthRec1Len + exthRec2Len;
        int exthPaddedLen = (exthRawLen + 3) & ~3;
        ByteBuffer exthBuf = ByteBuffer.allocate(exthPaddedLen).order(ByteOrder.BIG_ENDIAN);
        exthBuf.put("EXTH".getBytes(StandardCharsets.US_ASCII));
        exthBuf.putInt(exthPaddedLen);
        exthBuf.putInt(2); // 2 records
        exthBuf.putInt(100);
        exthBuf.putInt(exthRec1Len);
        exthBuf.put(authorBytes);
        exthBuf.putInt(503);
        exthBuf.putInt(exthRec2Len);
        exthBuf.put(titleBytes);

        // Build Record 0: 16-byte PalmDOC + 232-byte MOBI header + EXTH + FullName
        int mobiHeaderLen = 232;
        int fullNameOffset = 16 + mobiHeaderLen + exthPaddedLen;
        int rec0Len = fullNameOffset + titleBytes.length;
        ByteBuffer rec0 = ByteBuffer.allocate(rec0Len).order(ByteOrder.BIG_ENDIAN);
        rec0.putShort(0, (short) 1); // Compression = 1 (uncompressed)
        rec0.putInt(4, textBytes.length);
        rec0.putShort(8, (short) 1); // 1 text record
        rec0.putShort(10, (short) 4096);

        rec0.position(16);
        rec0.put("MOBI".getBytes(StandardCharsets.US_ASCII));
        rec0.putInt(20, mobiHeaderLen);
        rec0.putInt(24, 2); // MOBI type book
        rec0.putInt(28, 65001); // UTF-8
        rec0.putInt(84, fullNameOffset);
        rec0.putInt(88, titleBytes.length);
        rec0.putInt(128, 0x40); // exthFlags bit 0x40

        rec0.position(16 + mobiHeaderLen);
        rec0.put(exthBuf.array());
        rec0.position(fullNameOffset);
        rec0.put(titleBytes);

        // PDB Header (78 bytes) + 2 record info entries (16 bytes) = 94 bytes
        int numRecords = 2;
        int headerSize = 78 + (numRecords * 8);
        int rec0Offset = headerSize;
        int rec1Offset = rec0Offset + rec0Len;

        ByteBuffer pdb = ByteBuffer.allocate(rec1Offset + textBytes.length).order(ByteOrder.BIG_ENDIAN);
        byte[] nameAscii = "LiteReaderBook".getBytes(StandardCharsets.US_ASCII);
        pdb.position(0);
        pdb.put(nameAscii, 0, Math.min(31, nameAscii.length));
        pdb.position(60);
        pdb.put("BOOKMOBI".getBytes(StandardCharsets.US_ASCII));
        pdb.putShort(76, (short) numRecords);

        // Record list
        pdb.position(78);
        pdb.putInt(rec0Offset);
        pdb.putInt(0);
        pdb.putInt(rec1Offset);
        pdb.putInt(1);

        pdb.position(rec0Offset);
        pdb.put(rec0.array());
        pdb.position(rec1Offset);
        pdb.put(textBytes);

        try (FileOutputStream fos = new FileOutputStream(out)) {
            fos.write(pdb.array());
        }
    }

    private static void writeZipString(ZipOutputStream zos, String name, String content) throws Exception {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(content.getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
    }
}
