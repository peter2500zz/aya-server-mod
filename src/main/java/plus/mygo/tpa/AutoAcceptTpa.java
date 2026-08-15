package plus.mygo.tpa;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import plus.mygo.i18n.Messages;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * {@code /auto-accept-tpa} 的开关登记处。
 * <p>
 * 记录哪些玩家声明了「他人请求传送到我身边时无需再问我」，并承载该指令的全部回执文案。
 * {@link plus.mygo.command.AutoAcceptTpaCommand} 只负责解析参数，随后把活儿全交给本类，
 * 与请求流程的消息统一由 {@link TpaRequests} 发出是同一套分工。
 * <p>
 * <b>只覆盖 {@code /tpa} 一个方向。</b>那个方向里移动的是发起者，开关的主人本人不动，
 * 风险为零；而 {@code /tphere} 会把开关的主人瞬间拽到别处（可能是岩浆、虚空或陷阱里），
 * 这种事必须每次亲自过目，不能用一个开关一次性放行。因此本开关为真时，
 * {@code /tphere} 请求照旧进入待回应流程，收到的提示也与平常完全一致。
 * <p>
 * <b>开启的那一刻会结清积压。</b>此前已发给自己、仍在等回应的 {@code /tpa} 请求
 * 会被立即全部接受并传送（见 {@link TpaRequests#acceptAllIncomingTpa}）——
 * 玩家点【自动接受】按钮时看着的往往正是其中一条，若只对之后的请求生效，
 * 会让人以为按钮没生效。
 * <p>
 * <b>纯运行时状态。</b>开关只存在于内存中（{@link #ENABLED}），<b>绝不写入存档</b>：
 * 不使用 NBT、{@code SavedData}、计分板、玩家 {@code Attachment} 或任何文件，
 * 服务器重启后一律恢复为「关闭」。玩家中途退出再进入时开关仍然保留 ——
 * 它跟随的是服务器本次运行，而非玩家的存档数据。
 * 后续维护本类时必须保持这一点：<b>不得为「重启后记住玩家的偏好」之类的需求引入任何持久化</b>。
 * <p>
 * 仅在服务端主线程（指令执行与请求流程）中读写，故用普通 {@link HashSet} 即可，无需同步。
 */
public final class AutoAcceptTpa {

    /** 开启后给玩家的回执文本翻译键。 */
    private static final String KEY_ENABLED = "aya-server-mod.command.auto_accept_tpa.enabled";

    /** 关闭后给玩家的回执文本翻译键。 */
    private static final String KEY_DISABLED = "aya-server-mod.command.auto_accept_tpa.disabled";

    /** 无参查询且当前为开启时的回执文本翻译键。 */
    private static final String KEY_STATUS_ON = "aya-server-mod.command.auto_accept_tpa.status_on";

    /** 无参查询且当前为关闭时的回执文本翻译键。 */
    private static final String KEY_STATUS_OFF = "aya-server-mod.command.auto_accept_tpa.status_off";

    /** 【自动接受】按钮文字的翻译键。 */
    private static final String KEY_BUTTON_ON = "aya-server-mod.button.auto_accept";

    /** 【关闭自动接受】按钮文字的翻译键。 */
    private static final String KEY_BUTTON_OFF = "aya-server-mod.button.auto_accept_off";

    /**
     * 已开启自动接受的玩家 UUID 集合，未收录即为关闭（默认值）。
     * <p>
     * 只增删玩家 UUID，条目数至多等于「本次运行中开启过该开关的玩家数」，占用可忽略。
     * 刻意不在玩家退出时清理：开关跟随服务器本次运行，重连后应当仍然生效。
     */
    private static final Set<UUID> ENABLED = new HashSet<>();

    /** 纯静态工具类，禁止实例化。 */
    private AutoAcceptTpa() {
    }

    /**
     * 查询某玩家是否已开启自动接受。
     *
     * @param player 待查询的玩家
     * @return 已开启返回 {@code true}；默认（从未设置过或已关闭）返回 {@code false}
     */
    public static boolean isEnabled(ServerPlayer player) {
        return ENABLED.contains(player.getUUID());
    }

    /**
     * 设置某玩家的自动接受开关，并向其发送回执（{@code /auto-accept-tpa <true|false>}）。
     * <p>
     * 开启时会紧接着结清积压：此前发给他、仍在等回应的 {@code /tpa} 请求全部当场成交。
     * 回执刻意先于成交消息发出，读起来才是「开启了开关 → 于是这些请求当场通过」的因果顺序。
     *
     * @param player  执行指令的玩家
     * @param enabled {@code true} 开启，{@code false} 关闭
     * @return 1 表示执行成功。重复设置为同一个值同样算成功，只是无事发生
     */
    public static int set(ServerPlayer player, boolean enabled) {
        if (enabled) {
            ENABLED.add(player.getUUID());
            Messages.send(player, KEY_ENABLED, offButton(player));
            TpaRequests.acceptAllIncomingTpa(player);
        } else {
            ENABLED.remove(player.getUUID());
            Messages.send(player, KEY_DISABLED);
        }
        return 1;
    }

    /**
     * 向某玩家回报其当前的开关状态（不带参数的 {@code /auto-accept-tpa}）。
     * <p>
     * 回执附带的按钮永远指向<b>另一个</b>状态，因此这条指令同时也是一键切换的入口。
     *
     * @param player 执行指令的玩家
     * @return 1 表示执行成功
     */
    public static int report(ServerPlayer player) {
        if (isEnabled(player)) {
            Messages.send(player, KEY_STATUS_ON, offButton(player));
        } else {
            Messages.send(player, KEY_STATUS_OFF, onButton(player));
        }
        return 1;
    }

    /**
     * 构造【自动接受】按钮：点击后把开启指令填入聊天栏。
     *
     * @param viewer 按钮的观看者，其客户端语言决定按钮文字的 fallback 语言
     * @return 深绿色的可点击按钮组件
     */
    static MutableComponent onButton(ServerPlayer viewer) {
        // 复用 TpaRequests 的按钮构造：同包可见，避免为十来行样式代码再开一个类
        return TpaRequests.button(viewer, KEY_BUTTON_ON,
                "/auto-accept-tpa true", ChatFormatting.DARK_GREEN);
    }

    /**
     * 构造【关闭自动接受】按钮：点击后把关闭指令填入聊天栏。
     *
     * @param viewer 按钮的观看者，其客户端语言决定按钮文字的 fallback 语言
     * @return 灰色的可点击按钮组件，配色与【撤销】一致 —— 两者都是「收回上一个决定」
     */
    static MutableComponent offButton(ServerPlayer viewer) {
        return TpaRequests.button(viewer, KEY_BUTTON_OFF,
                "/auto-accept-tpa false", ChatFormatting.GRAY);
    }
}
