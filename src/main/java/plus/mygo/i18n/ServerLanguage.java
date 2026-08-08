package plus.mygo.i18n;

import net.minecraft.locale.Language;
import plus.mygo.AyaServerMod;

import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 服务端内置翻译表。
 * <p>
 * 本模组是纯服务端 mod，不能假定客户端安装了本 mod，因此客户端的语言文件里
 * 不存在本模组的翻译键。为解决这一点，服务端自行加载一份内置语言表：
 * 发送消息前按<b>接收方玩家的客户端语言</b>查出对应文本，作为 {@code fallback}
 * 随翻译键一同下发（具体发送逻辑见 {@link Messages}）。
 * <p>
 * 语言文件放在标准资源包路径 {@code assets/aya-server-mod/lang/<语言代码>.json}，
 * 因此同一份文件身兼两职：
 * <ul>
 *   <li><b>服务端</b>在本类中按 classpath 资源读取，用于生成 fallback 文本；</li>
 *   <li><b>客户端</b>若恰好装了本 mod（mod jar 本身即资源包），原版资源系统会自动
 *       加载它，此时客户端用自己的语言渲染翻译键，服务端下发的 fallback 被忽略。</li>
 * </ul>
 * JSON 解析复用原版 {@link Language#loadFromJson}，行为与原版语言文件完全一致，
 * 且无需引入任何额外的 JSON 依赖。
 */
public final class ServerLanguage {

    /**
     * 兜底语言代码。
     * 当玩家的客户端语言未收录在 {@link #BUNDLED_LANGUAGES} 中时（例如 ja_jp），
     * 回退到该语言取文本，避免玩家看到裸露的翻译键。
     */
    public static final String FALLBACK_LANGUAGE = "en_us";

    /**
     * 内置并完整维护的语言代码列表。
     * 顺序无关；新增语言时在此登记，并在 {@code assets/aya-server-mod/lang/} 下补齐同名 JSON。
     */
    private static final List<String> BUNDLED_LANGUAGES = List.of("en_us", "zh_cn");

    /** 语言文件在 jar 内的资源路径模板，{@code %s} 处填入语言代码。 */
    private static final String LANG_RESOURCE_PATTERN = "/assets/" + AyaServerMod.MOD_ID + "/lang/%s.json";

    /**
     * 已加载的翻译表：语言代码 → （翻译键 → 文本）。
     * <p>
     * 仅在 {@link #load()} 中写入一次（模组初始化阶段，早于服务器启动与任何指令执行），
     * 此后全程只读，因此无需额外同步。
     */
    private static final Map<String, Map<String, String>> TABLES = new HashMap<>();

    /** 纯静态工具类，禁止实例化。 */
    private ServerLanguage() {
    }

    /**
     * 加载全部内置语言文件。应在 {@link AyaServerMod#onInitialize()} 中调用一次，且仅调用一次。
     * <p>
     * 单个语言文件缺失或损坏不会中断模组加载：仅记录日志并跳过该语言，
     * 其查询会自动回退到 {@link #FALLBACK_LANGUAGE}。
     */
    public static void load() {
        for (String languageCode : BUNDLED_LANGUAGES) {
            Map<String, String> table = readTable(languageCode);
            if (table != null) {
                TABLES.put(languageCode, Map.copyOf(table));
            }
        }

        // 兜底语言缺失属于打包事故：所有未命中的查询都将返回 null，
        // 玩家会看到裸露的翻译键，因此按 ERROR 级别记录以便及时发现。
        if (!TABLES.containsKey(FALLBACK_LANGUAGE)) {
            AyaServerMod.LOGGER.error("兜底语言 {} 加载失败，未安装本模组的客户端将看到翻译键原文", FALLBACK_LANGUAGE);
            return;
        }

        AyaServerMod.LOGGER.info("已加载 {} 种语言的翻译表：{}", TABLES.size(), TABLES.keySet());
        verifyAgainstFallback();
    }

    /**
     * 按接收方语言查询翻译文本。
     * <p>
     * 查询顺序：玩家语言 → {@link #FALLBACK_LANGUAGE} → {@code null}。
     *
     * @param languageCode 接收方的客户端语言代码（形如 {@code zh_cn}），大小写不敏感；
     *                     为 {@code null} 时直接按兜底语言查询
     * @param key          翻译键
     * @return 对应语言的文本；两级查询均未命中时返回 {@code null}，
     *         由调用方（{@link Messages}）决定降级策略
     */
    public static String lookup(String languageCode, String key) {
        if (languageCode != null) {
            // 原版语言代码统一为小写（zh_cn / en_us），客户端上报值理论上已是小写；
            // 此处再规范一次以防个别客户端上报大写。必须显式传 Locale.ROOT：
            // 默认 Locale 在土耳其语环境下会把 'I' 转成 'ı' 而非 'i'，导致查表失败。
            Map<String, String> table = TABLES.get(languageCode.toLowerCase(Locale.ROOT));
            if (table != null) {
                String text = table.get(key);
                if (text != null) {
                    return text;
                }
            }
        }

        // 玩家语言未收录，或该语言表缺少此键：回退到兜底语言
        Map<String, String> fallbackTable = TABLES.get(FALLBACK_LANGUAGE);
        return fallbackTable == null ? null : fallbackTable.get(key);
    }

    /**
     * 从 classpath 读取并解析单个语言文件。
     *
     * @param languageCode 语言代码，用于拼接资源路径
     * @return 该语言的「翻译键 → 文本」映射；文件不存在或解析失败时返回 {@code null}
     */
    private static Map<String, String> readTable(String languageCode) {
        String resourcePath = String.format(LANG_RESOURCE_PATTERN, languageCode);

        // getResourceAsStream 在资源不存在时返回 null，而非抛异常，故需显式判空
        try (InputStream stream = ServerLanguage.class.getResourceAsStream(resourcePath)) {
            if (stream == null) {
                AyaServerMod.LOGGER.error("语言文件不存在：{}", resourcePath);
                return null;
            }

            Map<String, String> table = new HashMap<>();
            // 原版解析器：内部用 Gson 读取 JSON 对象，并对每个键值对调用一次 BiConsumer；
            // 同时会按原版规则清洗不受支持的格式化占位符，与原版语言文件行为完全一致
            Language.loadFromJson(stream, table::put);
            return table;
        } catch (Exception e) {
            // 捕获 IOException 与解析期抛出的运行时异常（如 JSON 语法错误）：
            // 单个语言损坏不应导致整个模组加载失败
            AyaServerMod.LOGGER.error("语言文件解析失败：{}", resourcePath, e);
            return null;
        }
    }

    /**
     * 以兜底语言为基准校验其余语言表的完整性，缺失的键按 WARN 记录。
     * <p>
     * 该校验只在启动时执行一次，纯诊断用途，不影响运行：某语言缺键时该键的查询
     * 会自动回退到兜底语言，玩家仍能看到可读文本，只是语言不匹配。
     */
    private static void verifyAgainstFallback() {
        Map<String, String> reference = TABLES.get(FALLBACK_LANGUAGE);

        for (Map.Entry<String, Map<String, String>> entry : TABLES.entrySet()) {
            String languageCode = entry.getKey();
            if (languageCode.equals(FALLBACK_LANGUAGE)) {
                continue;
            }

            // 基准语言有、该语言没有的键 → 该语言不完整
            for (String key : reference.keySet()) {
                if (!entry.getValue().containsKey(key)) {
                    AyaServerMod.LOGGER.warn("语言 {} 缺少翻译键：{}", languageCode, key);
                }
            }

            // 该语言有、基准语言没有的键 → 多半是基准语言漏了，或键名拼写不一致
            for (String key : entry.getValue().keySet()) {
                if (!reference.containsKey(key)) {
                    AyaServerMod.LOGGER.warn("语言 {} 含有基准语言 {} 中不存在的翻译键：{}",
                            languageCode, FALLBACK_LANGUAGE, key);
                }
            }
        }
    }
}
