package plus.mygo.tpa;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import plus.mygo.i18n.Messages;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * /tpa 传送请求的登记处与计时器。
 * <p>
 * 承载 {@code /tpa}、{@code /accept}、{@code /reject}、{@code /cancel} 四条指令共用的状态与逻辑：
 * 发起请求、接受、拒绝、撤销，以及超时失效。指令类只负责参数解析与失败提示，
 * 请求流程本身的全部消息由本类发出。
 * <p>
 * <b>只有目标明确接受才会传送。</b>请求必须在 {@link #TIMEOUT_SECONDS} 秒内被接受，
 * 逾期即失效并通知双方，不执行传送。
 * <p>
 * <b>请求之间互不排斥。</b>唯一性只落在<b>（发起者, 目标）二元组</b>上：
 * 同一玩家可以同时收到多人的请求，也可以同时向多人发出请求，各自独立计时、独立失效。
 * 因此三条回应指令都必须指定对方是谁 —— 玩家名由聊天里的按钮预先填好，
 * 手动输入时则由 {@link #suggestIncoming} / {@link #suggestOutgoing} 提供经过筛选的补全。
 * 唯一被拒绝的重复情形是向<b>同一</b>目标重复发起，此时提示发起者先撤销。
 * <p>
 * <b>计时与失效。</b>倒计时与在线校验都在服务端 tick 中完成
 * （{@link ServerTickEvents#END_SERVER_TICK}）：每 tick 先确认双方仍在线，任一方离线即作废该请求
 * 并告知仍在线的一方，其后才推进倒计时。
 * 之所以不用玩家断线事件来清理，是因为在开发环境中无法验证该事件对真实玩家连接的触发情况，
 * 而 tick 轮询既可实测又能覆盖任何原因造成的离线；待处理请求数量极少，轮询开销可忽略。
 * 不使用额外线程，因此接受与传送始终发生在服务端主线程上，无需同步。
 * <p>
 * <b>纯运行时状态。</b>待处理请求只存在于内存中（{@link #PENDING}），
 * <b>绝不写入存档</b>：不使用 NBT、{@code SavedData}、计分板或任何文件，
 * 服务器重启后自然清空。请求最长仅存活 {@link #TIMEOUT_SECONDS} 秒，
 * 且任一方离线时立即清理，因此不存在需要持久化的场景。
 * 后续维护本类时必须保持这一点：<b>不得为「重启后恢复未完成的请求」之类的需求引入任何持久化</b>。
 */
public final class TpaRequests {

    /** 请求的回应窗口（秒）：目标须在此时限内接受，逾期请求失效。同时用于向玩家展示。 */
    public static final int TIMEOUT_SECONDS = 30;

    /** 请求超时时长，换算为游戏刻（1 秒 = 20 刻）。 */
    private static final int TIMEOUT_TICKS = TIMEOUT_SECONDS * 20;

    /** 向自己发起请求时的提示文本翻译键。 */
    private static final String KEY_SELF = "aya-server-mod.command.tpa.self";

    /** 向同一目标重复发起请求时的提示文本翻译键。 */
    private static final String KEY_ALREADY_PENDING = "aya-server-mod.command.tpa.already_pending";

    /** 请求发出后给发起者的回执文本翻译键。 */
    private static final String KEY_REQUEST_SENT = "aya-server-mod.command.tpa.request.sent";

    /** 请求发出后给目标的提示文本翻译键。 */
    private static final String KEY_REQUEST_RECEIVED = "aya-server-mod.command.tpa.request.received";

    /** 目标接受后，给发起者的提示文本翻译键。 */
    private static final String KEY_ACCEPTED_REQUESTER = "aya-server-mod.command.tpa.accepted.requester";

    /** 目标接受后，给目标自己的提示文本翻译键。 */
    private static final String KEY_ACCEPTED_TARGET = "aya-server-mod.command.tpa.accepted.target";

    /** 请求超时失效后，给发起者的提示文本翻译键。 */
    private static final String KEY_EXPIRED_REQUESTER = "aya-server-mod.command.tpa.expired.requester";

    /** 请求超时失效后，给目标的提示文本翻译键。 */
    private static final String KEY_EXPIRED_TARGET = "aya-server-mod.command.tpa.expired.target";

    /** 被拒绝（未附原因）时，给发起者的提示文本翻译键。 */
    private static final String KEY_REJECTED_REQUESTER = "aya-server-mod.command.tpa.rejected.requester";

    /** 被拒绝（附有原因）时，给发起者的提示文本翻译键。 */
    private static final String KEY_REJECTED_REQUESTER_REASON = "aya-server-mod.command.tpa.rejected.requester_reason";

    /** 拒绝后给目标自己的提示文本翻译键。 */
    private static final String KEY_REJECTED_TARGET = "aya-server-mod.command.tpa.rejected.target";

    /** 撤销后给发起者的提示文本翻译键。 */
    private static final String KEY_CANCELLED_REQUESTER = "aya-server-mod.command.tpa.cancelled.requester";

    /** 撤销后给目标的提示文本翻译键。 */
    private static final String KEY_CANCELLED_TARGET = "aya-server-mod.command.tpa.cancelled.target";

    /** 任一方离线导致请求作废时，给仍在线一方的提示文本翻译键。 */
    private static final String KEY_PARTY_OFFLINE = "aya-server-mod.command.tpa.party_offline";

    /** 【接受】按钮文字的翻译键。 */
    private static final String KEY_BUTTON_ACCEPT = "aya-server-mod.button.accept";

    /** 【拒绝】按钮文字的翻译键。 */
    private static final String KEY_BUTTON_REJECT = "aya-server-mod.button.reject";

    /** 【撤销】按钮文字的翻译键。 */
    private static final String KEY_BUTTON_CANCEL = "aya-server-mod.button.cancel";

    /**
     * 待处理请求表：（发起者, 目标）→ 请求详情。
     * <p>
     * 以二元组为键，天然实现了「同一对玩家之间至多一条待处理请求」，同时允许一名玩家
     * 同时参与多条互不相干的请求。用 {@link LinkedHashMap} 保持插入顺序，
     * 使补全列表的排序稳定可预期。
     * <p>
     * 仅在服务端主线程（指令执行、补全回调与 tick 回调）中读写，故用普通 Map 即可，无需同步。
     */
    private static final Map<RequestKey, PendingRequest> PENDING = new LinkedHashMap<>();

    /** 纯静态工具类，禁止实例化。 */
    private TpaRequests() {
    }

    /**
     * 请求的唯一标识：谁请求传送到谁那里。
     * <p>
     * 用 record 以自动获得 {@code equals} / {@code hashCode}，从而可直接作为 Map 的键。
     *
     * @param requesterId 发起者 UUID，即将来被传送的一方
     * @param targetId    目标玩家 UUID
     */
    private record RequestKey(UUID requesterId, UUID targetId) {
    }

    /**
     * 单条待处理请求的可变状态。
     * <p>
     * 不用 record 是因为 {@link #remainingTicks} 需要逐 tick 递减，必须可变；
     * 双方身份已由 {@link RequestKey} 承载，此处不再重复存放。
     */
    private static final class PendingRequest {

        /** 剩余刻数，每服务端 tick 减一；归零即判定超时失效。 */
        private int remainingTicks = TIMEOUT_TICKS;
    }

    /**
     * 注册 tick 回调，用于推进倒计时并清理失效请求。
     * 应在 {@link plus.mygo.AyaServerMod#onInitialize()} 中调用一次，且仅调用一次。
     */
    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(TpaRequests::tick);
    }

    /**
     * 发起一条传送请求。
     *
     * @param requester 发起者，即将来被传送的一方
     * @param target    目标玩家，请求将发给它
     * @return 1 表示请求已发出；0 表示因自我请求或重复请求被拒绝（已向发起者说明原因）
     */
    public static int create(ServerPlayer requester, ServerPlayer target) {
        // 向自己发请求没有意义：既不需要征得同意，传送也是原地不动
        if (requester.getUUID().equals(target.getUUID())) {
            Messages.send(requester, KEY_SELF);
            return 0;
        }

        RequestKey key = new RequestKey(requester.getUUID(), target.getUUID());

        // 已向同一目标发过且尚未失效：不重复打扰对方，也不重置计时，
        // 而是提示发起者先撤销 —— 附上已填好目标玩家名的【撤销】按钮
        if (PENDING.containsKey(key)) {
            Messages.send(requester, KEY_ALREADY_PENDING,
                    target.getDisplayName(),
                    cancelButton(requester, target));
            return 0;
        }

        PENDING.put(key, new PendingRequest());

        // 给发起者的回执，附【撤销】按钮
        Messages.send(requester, KEY_REQUEST_SENT,
                target.getDisplayName(),
                cancelButton(requester, target));

        // 给目标的请求提示，附【接受】【拒绝】按钮，两者都已填好发起者玩家名
        Messages.send(target, KEY_REQUEST_RECEIVED,
                requester.getDisplayName(),
                button(target, KEY_BUTTON_ACCEPT,
                        "/accept " + requester.getGameProfile().name(), ChatFormatting.GREEN),
                // 末尾刻意留一个空格：填入聊天栏后光标落在下一个参数位上，
                // 原版的命令提示会自动显示出可选的「原因」，无需在文案里另行解释
                button(target, KEY_BUTTON_REJECT,
                        "/reject " + requester.getGameProfile().name() + " ", ChatFormatting.RED));

        return 1;
    }

    /**
     * 目标接受来自指定发起者的请求（{@code /accept <玩家>}）。
     *
     * @param target    执行 {@code /accept} 的玩家
     * @param requester 被接受的那条请求的发起者
     * @return 1 表示已接受并完成传送；0 表示该发起者并未向目标发送过待处理请求
     */
    public static int accept(ServerPlayer target, ServerPlayer requester) {
        if (PENDING.remove(new RequestKey(requester.getUUID(), target.getUUID())) == null) {
            return 0;
        }
        complete(requester, target);
        return 1;
    }

    /**
     * 目标拒绝来自指定发起者的请求（{@code /reject <玩家> [原因]}）。
     *
     * @param target    执行 {@code /reject} 的玩家
     * @param requester 被拒绝的那条请求的发起者
     * @param reason    玩家给出的拒绝原因；为 {@code null} 表示未填写
     * @return 1 表示已拒绝；0 表示该发起者并未向目标发送过待处理请求
     */
    public static int reject(ServerPlayer target, ServerPlayer requester, String reason) {
        if (PENDING.remove(new RequestKey(requester.getUUID(), target.getUUID())) == null) {
            return 0;
        }

        Messages.send(target, KEY_REJECTED_TARGET, requester.getDisplayName());

        if (reason == null) {
            Messages.send(requester, KEY_REJECTED_REQUESTER, target.getDisplayName());
        } else {
            // 原因为玩家自由输入，作为 literal 传入，不参与翻译
            Messages.send(requester, KEY_REJECTED_REQUESTER_REASON,
                    target.getDisplayName(), Component.literal(reason));
        }
        return 1;
    }

    /**
     * 发起者撤销自己发给指定目标的请求（{@code /cancel <玩家>}）。
     *
     * @param requester 执行 {@code /cancel} 的玩家
     * @param target    该条请求的目标玩家
     * @return 1 表示已撤销；0 表示并不存在这样一条待处理请求
     */
    public static int cancel(ServerPlayer requester, ServerPlayer target) {
        if (PENDING.remove(new RequestKey(requester.getUUID(), target.getUUID())) == null) {
            return 0;
        }

        Messages.send(requester, KEY_CANCELLED_REQUESTER, target.getDisplayName());
        Messages.send(target, KEY_CANCELLED_TARGET, requester.getDisplayName());
        return 1;
    }

    /**
     * {@code /accept} 与 {@code /reject} 的玩家参数补全：只列出<b>此刻正在等待自己回应</b>的发起者。
     * <p>
     * 该补全依赖服务端的运行时状态，客户端无从得知。原版对未注册的自定义
     * {@code SuggestionProvider} 一律在命令树数据包中标记为 {@code ASK_SERVER}
     * （见 {@code SuggestionProviders.getName}），客户端据此回头向服务端索取补全，
     * 因此本方法会在服务端被调用。
     *
     * @param context Brigadier 提供的指令上下文，其来源即请求补全的玩家
     * @param builder 补全结果收集器
     * @return 填入候选玩家名后的补全结果
     */
    public static CompletableFuture<Suggestions> suggestIncoming(
            CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        return suggestCounterparts(context, builder, true);
    }

    /**
     * {@code /cancel} 的玩家参数补全：只列出<b>自己已发出且仍待回应</b>的目标玩家。
     *
     * @param context Brigadier 提供的指令上下文，其来源即请求补全的玩家
     * @param builder 补全结果收集器
     * @return 填入候选玩家名后的补全结果
     */
    public static CompletableFuture<Suggestions> suggestOutgoing(
            CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        return suggestCounterparts(context, builder, false);
    }

    /**
     * 补全的共同实现：在待处理请求表中筛出与执行者相关的那一侧，列出对方的玩家名。
     *
     * @param context  指令上下文
     * @param builder  补全结果收集器
     * @param incoming {@code true} 列出发给执行者的请求之发起者；
     *                 {@code false} 列出执行者发出的请求之目标
     * @return 补全结果；来源不是玩家时返回空结果
     */
    private static CompletableFuture<Suggestions> suggestCounterparts(
            CommandContext<CommandSourceStack> context, SuggestionsBuilder builder, boolean incoming) {
        // 控制台等非玩家来源没有「自己的请求」可言，直接给空补全
        ServerPlayer viewer = context.getSource().getPlayer();
        if (viewer == null) {
            return builder.buildFuture();
        }

        UUID viewerId = viewer.getUUID();
        MinecraftServer server = viewer.level().getServer();

        List<String> names = new ArrayList<>();
        for (RequestKey key : PENDING.keySet()) {
            // 取出这条请求里「执行者自己」的那一侧与「对方」的那一侧
            UUID selfSide = incoming ? key.targetId() : key.requesterId();
            if (!selfSide.equals(viewerId)) {
                continue;
            }
            UUID otherSide = incoming ? key.requesterId() : key.targetId();

            // 补全给的是要填进指令里的玩家名，必须用档案名而非可能带队伍颜色的显示名
            ServerPlayer other = server.getPlayerList().getPlayer(otherSide);
            if (other != null) {
                names.add(other.getGameProfile().name());
            }
        }
        // suggest() 会按玩家已输入的前缀自行过滤，无需在此预筛
        return SharedSuggestionProvider.suggest(names, builder);
    }

    /**
     * 服务端 tick 回调：校验双方是否仍在线、推进倒计时，并对到期请求执行失效处理。
     *
     * @param server 服务器实例，用于按 UUID 解析在线玩家
     */
    private static void tick(MinecraftServer server) {
        // 绝大多数 tick 没有待处理请求，提前返回避免无谓开销
        if (PENDING.isEmpty()) {
            return;
        }

        // 在遍历中只做摘除与记录，发消息留到遍历结束后统一执行，
        // 以免在迭代 PENDING 的过程中间接修改该表
        List<RequestKey> expired = new ArrayList<>();
        List<ServerPlayer> orphanedPlayers = new ArrayList<>();

        var iterator = PENDING.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<RequestKey, PendingRequest> entry = iterator.next();
            ServerPlayer requester = server.getPlayerList().getPlayer(entry.getKey().requesterId());
            ServerPlayer target = server.getPlayerList().getPlayer(entry.getKey().targetId());

            // 任一方离线，请求立即作废；仍在线的一方会收到说明
            if (requester == null || target == null) {
                iterator.remove();
                ServerPlayer remaining = target != null ? target : requester;
                if (remaining != null) {
                    orphanedPlayers.add(remaining);
                }
                continue;
            }

            if (--entry.getValue().remainingTicks > 0) {
                continue;
            }
            iterator.remove();
            expired.add(entry.getKey());
        }

        for (ServerPlayer player : orphanedPlayers) {
            Messages.send(player, KEY_PARTY_OFFLINE);
        }

        for (RequestKey key : expired) {
            ServerPlayer requester = server.getPlayerList().getPlayer(key.requesterId());
            ServerPlayer target = server.getPlayerList().getPlayer(key.targetId());
            // 上面刚校验过双方在线，此处判空仅为防御
            if (requester != null && target != null) {
                expire(requester, target);
            }
        }
    }

    /**
     * 处理一条超时失效的请求：<b>不执行传送</b>，仅告知双方请求已作废。
     *
     * @param requester 发起者
     * @param target    目标玩家，即未在期限内回应的一方
     */
    private static void expire(ServerPlayer requester, ServerPlayer target) {
        Messages.send(requester, KEY_EXPIRED_REQUESTER,
                target.getDisplayName(), String.valueOf(TIMEOUT_SECONDS));
        Messages.send(target, KEY_EXPIRED_TARGET,
                String.valueOf(TIMEOUT_SECONDS), requester.getDisplayName());
    }

    /**
     * 目标接受请求后：把发起者传送到目标身边并通知双方。
     *
     * @param requester 发起者，将被传送
     * @param target    目标玩家，传送的落点
     */
    private static void complete(ServerPlayer requester, ServerPlayer target) {
        // ServerPlayer.level() 协变返回 ServerLevel，无需额外转型
        ServerLevel targetLevel = target.level();

        // Set.of() 表示所有坐标均为绝对值（非相对偏移）；保持发起者当前朝向不变；
        // 最后一个 boolean 参数 true 表示传送完成后重置镜头
        requester.teleportTo(
                targetLevel,
                target.getX(), target.getY(), target.getZ(),
                Set.of(),
                requester.getYRot(), requester.getXRot(),
                true
        );

        // 原版传送（含 /tp）不会清空已累积的下落距离，发起者若在坠落途中被接受传送，
        // 会带着旧的下落距离落地并照常摔伤甚至摔死 —— 而接受的时机不由他决定，
        // 更不该因此受伤。这里显式清零，使传送落点始终从零开始计算坠落伤害。
        requester.resetFallDistance();

        Messages.send(requester, KEY_ACCEPTED_REQUESTER, target.getDisplayName());
        Messages.send(target, KEY_ACCEPTED_TARGET, requester.getDisplayName());
    }

    /**
     * 构造一个已填好目标玩家名的【撤销】按钮。
     *
     * @param viewer 按钮的观看者，即发起者
     * @param target 该条请求的目标玩家
     * @return 可点击的【撤销】按钮组件
     */
    private static MutableComponent cancelButton(ServerPlayer viewer, ServerPlayer target) {
        return button(viewer, KEY_BUTTON_CANCEL,
                "/cancel " + target.getGameProfile().name(), ChatFormatting.GRAY);
    }

    /**
     * 构造一个可点击的按钮组件。
     *
     * @param viewer  按钮的观看者，其客户端语言决定按钮文字的 fallback 语言
     * @param key     按钮文字的翻译键
     * @param command 点击后填入聊天栏的指令，须含前导斜杠
     * @param color   按钮颜色
     * @return 带颜色、点击事件与悬停提示的按钮组件
     */
    private static MutableComponent button(ServerPlayer viewer, String key, String command, ChatFormatting color) {
        return Messages.of(viewer, key).withStyle(style -> style
                .withColor(color)
                // ClickEvent 在 26.2 中是密封接口 + record。
                // 此处用 SuggestCommand（把指令填入聊天栏，由玩家按回车确认）而非 RunCommand：
                // RunCommand 会让客户端弹出「确认执行指令」窗口，多一步操作且措辞吓人；
                // SuggestCommand 不触发该窗口，玩家仍保有一次确认机会，
                // 也正是它让【拒绝】按钮末尾的空格能触发原版的下一参数提示。
                .withClickEvent(new ClickEvent.SuggestCommand(command))
                // 悬停显示指令原文，便于玩家知道也可以手动输入；指令名不翻译，故用 literal
                .withHoverEvent(new HoverEvent.ShowText(Component.literal(command))));
    }
}
