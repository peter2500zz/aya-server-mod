package plus.mygo.i18n;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * 本地化消息的构造与发送门面。
 * <p>
 * 本模组所有面向玩家的文本都应经由本类发出，调用处只写翻译键，不写字面文本。
 * <p>
 * <b>工作原理。</b>发送前先按接收方玩家上报的客户端语言，从服务端内置语言表
 * （{@link ServerLanguage}）中查出对应文本，再以
 * {@link Component#translatableWithFallback(String, String, Object...)}
 * 把「翻译键 + 该文本」一并下发。客户端收到后：
 * <ul>
 *   <li><b>未装本 mod</b>：语言文件里找不到该翻译键，于是渲染服务端给的 fallback ——
 *       而这段 fallback 已按该玩家自己的语言选好，因此显示正确且语言匹配；</li>
 *   <li><b>装了本 mod</b>（或加载了同名资源包）：能查到翻译键，直接用客户端自己的
 *       翻译渲染，fallback 被忽略，玩家可通过资源包自定义文案。</li>
 * </ul>
 * 两种情形下 {@code %s} 占位符的替换逻辑完全一致——原版对翻译文本与 fallback 文本
 * 走的是同一套模板解析，因此参数可以照常传递。
 * <p>
 * <b>参数约定。</b>可变参数 {@code args} 会随消息一起序列化过网络，
 * 因此只应传入 {@link String} 或 {@link Component}：
 * 前者用于数字等纯文本（先自行格式化成字符串），后者用于玩家名等需要保留
 * 队伍颜色与悬停信息的富文本（如 {@code player.getDisplayName()}）。
 */
public final class Messages {

    /** 纯静态工具类，禁止实例化。 */
    private Messages() {
    }

    /**
     * 为指定接收方构造一条本地化消息。
     *
     * @param viewer 消息的接收方，其客户端语言决定 fallback 用哪种语言；
     *               为 {@code null}（例如来源是控制台）时按兜底语言处理
     * @param key    翻译键
     * @param args   模板参数，按约定只传 {@link String} 或 {@link Component}
     * @return 可直接发送的文本组件
     */
    public static Component of(ServerPlayer viewer, String key, Object... args) {
        // ClientInformation 是玩家在握手及每次修改设置时上报的客户端选项快照，
        // language() 即客户端当前选择的语言代码（形如 zh_cn）
        String languageCode = viewer == null ? null : viewer.clientInformation().language();

        String fallback = ServerLanguage.lookup(languageCode, key);
        if (fallback == null) {
            // 语言表整体加载失败或键名写错时的最后退路：不带 fallback 发送。
            // 未装 mod 的客户端会看到裸露的翻译键，但这属于打包/拼写事故，
            // ServerLanguage 已在启动时以 ERROR / WARN 记录，便于定位。
            return Component.translatable(key, args);
        }
        return Component.translatableWithFallback(key, fallback, args);
    }

    /**
     * 向指定玩家直接发送一条系统消息，使用该玩家自己的语言。
     * <p>
     * 用于通知指令执行者<b>以外</b>的玩家（如 {@code /tpa} 中被传送到的目标）。
     *
     * @param target 接收消息的玩家
     * @param key    翻译键
     * @param args   模板参数，约定见 {@link #of}
     */
    public static void send(ServerPlayer target, String key, Object... args) {
        target.sendSystemMessage(of(target, key, args));
    }

    /**
     * 以「指令执行成功」的形式向执行者发送反馈。
     *
     * @param source 指令来源，其对应玩家的语言决定 fallback 语言
     * @param key    翻译键
     * @param args   模板参数，约定见 {@link #of}
     */
    public static void sendSuccess(CommandSourceStack source, String key, Object... args) {
        // getPlayer() 在来源非玩家时返回 null，of() 已处理该情况
        Component message = of(source.getPlayer(), key, args);

        // sendSuccess 接收 Supplier 以便在无人接收时跳过构造；此处消息已构造完毕，直接返回即可。
        // 第二个参数 false：不把该反馈广播给其他管理员（否则他们会收到按执行者语言渲染的文本）
        source.sendSuccess(() -> message, false);
    }

    /**
     * 以「指令执行失败」的形式向执行者发送提示（原版会渲染为红色）。
     *
     * @param source 指令来源，其对应玩家的语言决定 fallback 语言
     * @param key    翻译键
     * @param args   模板参数，约定见 {@link #of}
     */
    public static void sendFailure(CommandSourceStack source, String key, Object... args) {
        source.sendFailure(of(source.getPlayer(), key, args));
    }
}
