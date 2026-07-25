package io.github.somehussar.crystalgraphics.harness.config;

import com.crystalgraphics.text.msdf.CgMsdfAtlasConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Typed configuration for the text-scene.
 */
public class TextSceneConfig extends HarnessConfig {

    public String kanji = "ブリキノダンス\n" +
        "さあ 憐れんで 血統書 持ち寄って反教典\n" +
        "沈んだ唱導 腹這い幻聴\n" +
        "謁見 席巻 妄信症\n" +
        "踊れ酔え孕め アヴァターラ新大系\n" +
        "斜めの幻聴 錻力と宗教\n" +
        "ラル・ラリ・唱えろ生\n" +
            
        "まあ 逆らって新王都 くぐもった脳系統\n" +
        "墓掘れ説法 釈迦釈迦善行\n" +
        "六感・吶喊・竜胆・錠\n" +
        "どれどれ 震え 蔑さげすんで新体系\n" +
        "欺瞞の延長 詭弁の劣等\n" +
        "ドグ・ラグ・叶えろ\n" +
        "不気味な手 此処に在り\n" +
        "理性の目 咽び泣き\n" +
        "踵返せ遠くに\n" +
        "偲ぶ君の瞳を\n" +
        "さあ 皆舞いな 空洞で\n" +
        "サンスクリット求道系 抉り抜いた鼓動\n" +
        "咲かせ咲かせ\n" +
        "さあ 剽悍な双眸を エーカム そうさ 先頭に\n" +
        "真っ赤に濡れた空 踵鳴らせ\n" +
        "嗚呼 漠然と運命星\n" +
        "重度に負った喘鳴に\n" +
        "優劣等無いさ回れ踊れ\n" +
        "もう 漠然と九番目が龍を薙ぐ\n" +
        "パッパラ・ラル・ラリ ブリキノダンス\n" +
        "さあ 微笑んで急展開 ナラシンハ流体系\n" +
        "積もった信仰 惜別劣等\n" +
        "怨恨 霊堂 脳震盪\n" +
        "パラパラ狂え アヴァターラ半酩酊\n" +
        "次第に昏倒\n" +
        "劣悪情動 崇めろバララーマ\n" +
        "死んでる龍が吼える バガヴァッド・ギーターで\n" +
        "張り詰め心臓\n" +
        "押し引け問答 無に帰す桃源郷\n" +
        "ドウドウ唸れ アヴァターラ封筒へ\n" +
        "クリシュナ誘導 アルジュナ引導\n" +
        "ドグ・ラグ・祝えや\n" +
        "不気味な手 誠なり\n" +
        "理解などとうに無き\n" +
        "鬼神討てよ遠くに\n" +
        "潜む影の手引きを\n" +
        "さあ皆 舞な 衝動で\n" +
        "サンスクリット求道系 雑多に暮れた日々\n" +
        "廃れ 廃れ\n" +
        "さあ剽悍な双眸で\n" +
        "サプタの脳が正統系\n" +
        "真っ赤に塗れた空\n" +
        "響け響け\n" +
        "嗚呼 六芒と流線型\n" +
        "王族嫌悪は衝動性\n" +
        "ピンチにヒットな祝詞 ハバケ・ルドレ\n" +
        "もう 漠然と九番目が狂を急ぐ\n" +
        "巷で噂の ブリキノダンス\n" +
        "さあ 皆舞いな 空洞で\n" +
        "サンスクリット求道系 抉り抜いた鼓動\n" +
        "咲かせ燃やせ\n" +
        "さあ 剽悍な双眸を エーカム そうさ 先頭で\n" +
        "全く以て 鼓動がダンス\n" +
        "王手を盗って遠雷帝\n" +
        "サンスクリット求道系 妄想信者踊る\n" +
        "酷く脆く\n" +
        "もう 漠然と九番目が盲如く\n" +
        "御手々を拝借 ブリキノダンス\n" +
            "日 一 国 会 人 年 大 十 二 本 中 長 出 三 同 時 政 事 自 行\n" +
            "社 見 月 分 後 前 回 生 子 新 場 金 員 九 入 選 立 開 手 米\n" +
            "力 学 問 代 明 動 京 目 通 理 体 田 当 車 意 対 戦 主 題 下\n" +
            "何 文 各 性 度 法 気 公 持 野 信 正 真 道 作 化 表 活 決 記\n" +
            "全 調 最 間 心 界 変 方 先 民 発 口 向 区 助 計 多 組 重 小\n" +
            "務 物 強 山 海 空 地 内 共 東 外 無 業 関 切 総 情 定 名 実\n" +
            "使 言 経 知 点 造 市 特 個 加 美 読 書 聞 飲 食 走 起 歩 帰\n" +
            "来 有 死 買 売 考 思 教 終 始 家 店 校 友 達 犬 猫 魚 馬 鳥\n" +
            "虫 花 木 林 森 川 晴 雨 雪 雲 風 雷 天 球 電 線 の 父 母 兄\n" +
            "姉 弟 妹 奥 旦 祖 親 赤 青 黄 緑 黒 白 茶 紫 銀 円 安 高 低\n" +
            "短 少 古 近 遠 弱 暗 広 狭 早 遅 軽 深 浅 暑 寒 熱 冷 味 不\n" +
            "良 悪 誤 直 平 和 洋 都 府 県 町 村 島 湖 池 港 湾 沖 岸 坂\n" +
            "谷 原 岩 砂 畑 樹 草 葉 根 種 支 建 認 図 解 結 初 別 去 勝\n" +
            "面 委 告 氏 受 職 供 官 構 能 路 労 品 更 権 類 取 頭 顔 声\n" +
            "身 衣 毛 糸 皮 番 号 等 合 利 相 機 格 制 確 運 流 象 集 常\n" +
            "置 打 払 半 細 太 丸 交 科 英 語 曜 週 算 数 急 宿 室 試 験\n" +
            "答 卒 私 位 階 級 倍 億 兆 万 船 橋 鉄 差 座 席 館 役 所 医\n" +
            "歯 薬 局 病 院 符 寝 泣 笑 怒 喜 悲 驚 恋 愛 欲 望 願 祈 福\n" +
            "守 害 苦 痛 疲 危 険 突 漏 落 倒 壊 消 引 退 止 進 挙 投 票\n" +
            "争 論 議 党 堂 園 庭 植 昆 爬 両 哺 乳 恐 竜 石 遺 跡 歴 史\n" +
            "世 紀 未 過 現 在 今 昔 朝 昼 晩 夜 昏 夕 春 秋 夏 冬 乾 湿\n" +
            "豪 暴 台 洪 水 津 波 震 噴 火 災 避 難 警 報 注 策 救 復 旧\n" +
            "興 援 募 際 連 維 条 約 盟 渉 妥 協 紛 兵 器 軍 隊 陸 衛 防\n" +
            "保 障 テ ロ 件 故 認 根 単 針 演 給 昨 油 曲 典 興 渡 己 昨\n" +
            "誰 門 夜 野 明 面 味 毎 末 万 補 報 放 方 訪 望 防 北 役 優\n"+
        "Translate to English";

    private String text = kanji;//"CrystalGraphics font demo - mouse wheel zoom بيانات الاستفسار";
    private int atlasSize = 512;
    private int fontSizePx = 24;
    private boolean dumpBitmapAtlas = true;
    private float poseScale = 1.0f;
    private int guiScale = 1;
    private List<Float> scales = new ArrayList<Float>();
    private String outputFilename = null;
    private boolean mtsdf = CgMsdfAtlasConfig.DEFAULT_MTSDF;

    public String getText() { return text; }
    public int getAtlasSize() { return atlasSize; }
    public int getFontSizePx() { return fontSizePx; }
    public boolean isDumpBitmapAtlas() { return dumpBitmapAtlas; }
    public float getPoseScale() { return poseScale; }
    public int getGuiScale() { return guiScale; }
    public String getOutputFilename() { return outputFilename; }
    public boolean isMtsdf() { return mtsdf; }

    public CgMsdfAtlasConfig buildMsdfAtlasConfig() {
        return CgMsdfAtlasConfig.defaultConfig().withPageSize(atlasSize).withMtsdf(mtsdf);
    }

    /**
     * Returns the list of scales for multi-scale comparison rendering.
     * If {@code --scales} was specified, returns those values.
     * Otherwise, returns a single-element list containing {@code poseScale}.
     */
    public List<Float> getEffectiveScales() {
        if (!scales.isEmpty()) {
            return scales;
        }
        List<Float> single = new ArrayList<Float>();
        single.add(poseScale);
        return single;
    }

    /** Returns true if multi-scale comparison mode is active. */
    public boolean isMultiScaleMode() {
        return scales.size() > 1;
    }

    @Override
   public void applySystemProperties() {
        super.applySystemProperties();
        String fs = System.getProperty("harness.font.size.px");
        if (fs != null && !fs.isEmpty()) {
            this.fontSizePx = parseIntStrict(fs, "harness.font.size.px");
        }
        String as = System.getProperty("harness.atlas.size");
        if (as != null && !as.isEmpty()) {
            this.atlasSize = parseIntStrict(as, "harness.atlas.size");
        }
        String mtsdfProp = System.getProperty("harness.mtsdf");
        if (mtsdfProp != null && !mtsdfProp.isEmpty()) {
            this.mtsdf = "true".equalsIgnoreCase(mtsdfProp);
        }
    }

    @Override
   public void applyCliArgs(Map<String, String> args) {
        super.applyCliArgs(args);
        if (args.containsKey("text")) {
            this.text = args.get("text");
        }
        if (args.containsKey("atlas-size")) {
            this.atlasSize = parseIntStrict(args.get("atlas-size"), "--atlas-size");
        }
        if (args.containsKey("font-size-px")) {
            this.fontSizePx = parseIntStrict(args.get("font-size-px"), "--font-size-px");
        }
        if (args.containsKey("dump-bitmap-atlas")) {
            this.dumpBitmapAtlas = "true".equalsIgnoreCase(args.get("dump-bitmap-atlas"));
        }
        if (args.containsKey("pose-scale")) {
            this.poseScale = parseFloatStrict(args.get("pose-scale"), "--pose-scale");
        }
        if (args.containsKey("scales")) {
            this.scales = parseFloatList(args.get("scales"), "--scales");
        }
        if (args.containsKey("output-filename")) {
            this.outputFilename = args.get("output-filename");
        }
        if (args.containsKey("gui-scale")) {
            this.guiScale = parseIntStrict(args.get("gui-scale"), "--gui-scale");
            if (this.guiScale < 1) {
                throw new IllegalArgumentException("--gui-scale must be >= 1, got: " + this.guiScale);
            }
        }
        if (args.containsKey("mtsdf")) {
            this.mtsdf = "true".equalsIgnoreCase(args.get("mtsdf"));
        }
    }

    public static TextSceneConfig create(String[] args) {
        TextSceneConfig cfg = new TextSceneConfig();
        cfg.applySystemProperties();
        cfg.applyCliArgs(HarnessConfig.parseCliArgs(args));
        return cfg;
    }

    static float parseFloatStrict(String value, String paramName) {
        try {
            float f = Float.parseFloat(value);
            if (Float.isNaN(f) || Float.isInfinite(f)) {
                throw new IllegalArgumentException(
                    "Invalid value for " + paramName + ": '" + value + "' (must be a finite number)");
            }
            return f;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                "Invalid value for " + paramName + ": '" + value + "' (must be a valid float)");
        }
    }

    static List<Float> parseFloatList(String value, String paramName) {
        List<Float> result = new ArrayList<Float>();
        String[] parts = value.split(",");
        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                result.add(parseFloatStrict(trimmed, paramName));
            }
        }
        if (result.isEmpty()) {
            throw new IllegalArgumentException(
                "Invalid value for " + paramName + ": '" + value + "' (must contain at least one scale)");
        }
        return result;
    }
}
