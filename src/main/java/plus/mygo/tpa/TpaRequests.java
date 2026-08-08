package plus.mygo.tpa;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import plus.mygo.i18n.Messages;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * /tpa 传送请求的登记处与计时器。
 * <p>
 * 承载 {@code /tpa}、{@code /confirm}、{@code /deny}、{@code /cancel} 四条指令共用的状态与逻辑：
 * 发起请求、接受、拒绝、撤销，以及超时自动接受。指令类只负责参数解析与「无待处理请求」时的提示，
 * 请求流程本身的全部消息由本类发出。
 * <p>
 * <b>并发约束。</b>同一时刻：
 * <ul>
 *   <li>每个<b>目标</b>最多只有一个待处理请求 —— 因此 {@code /confirm} 与 {@code /deny} 永远无歧义；</li>
 *   <li>每个<b>发起者</b>最多只有一个在途请求 —— 因此无参的 {@code /cancel} 永远无歧义。</li>
 * </ul>
 * 违反上述任一约束的新请求会被拒绝并提示发起者，且<b>不会打扰目标</b>。
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
 * <p>
 * <b>注意。</b>按需求设定，超时的处理是<b>自动接受</b>而非自动拒绝：目标在
 * {@link #TIMEOUT_SECONDS} 秒内未回应，请求即被视为同意并执行传送。
 */
public final class TpaRequests {

    /** 请求超时时长（秒），同时用于向玩家展示。 */
    public static final int TIMEOUT_SECONDS = 10;

    /** 请求超时时长，换算为游戏刻（1 秒 = 20 刻）。 */
    private static final int TIMEOUT_TICKS = TIMEOUT_SECONDS * 20;

    /** 向自己发起请求时的提示文本翻译键。 */
    private static final String KEY_SELF = "aya-server-mod.command.tpa.self";

    /** 目标已有待处理请求时，给发起者的提示文本翻译键。 */
    private static final String KEY_TARGET_BUSY = "aya-server-mod.command.tpa.target_busy";

    /** 发起者已有在途请求时的提示文本翻译键。 */
    private static final String KEY_ALREADY_PENDING = "aya-server-mod.command.tpa.already_pending";

    /** 请求发出后给发起者的回执文本翻译键。 */
    private static final String KEY_REQUEST_SENT = "aya-server-mod.command.tpa.request.sent";

    /** 请求发出后给目标的提示文本翻译键。 */
    private static final String KEY_REQUEST_RECEIVED = "aya-server-mod.command.tpa.request.received";

    /** 目标主动接受后，给发起者的提示文本翻译键。 */
    private static final String KEY_ACCEPTED_REQUESTER = "aya-server-mod.command.tpa.accepted.requester";

    /** 目标主动接受后，给目标自己的提示文本翻译键。 */
    private static final String KEY_ACCEPTED_TARGET = "aya-server-mod.command.tpa.accepted.target";

    /** 超时自动接受后，给发起者的提示文本翻译键。 */
    private static final String KEY_AUTO_ACCEPTED_REQUESTER = "aya-server-mod.command.tpa.auto_accepted.requester";

    /** 超时自动接受后，给目标的提示文本翻译键。 */
    private static final String KEY_AUTO_ACCEPTED_TARGET = "aya-server-mod.command.tpa.auto_accepted.target";

    /** 被拒绝（未附原因）时，给发起者的提示文本翻译键。 */
    private static final String KEY_DENIED_REQUESTER = "aya-server-mod.command.tpa.denied.requester";

    /** 被拒绝（附有原因）时，给发起者的提示文本翻译键。 */
    private static final String KEY_DENIED_REQUESTER_REASON = "aya-server-mod.command.tpa.denied.requester_reason";

    /** 拒绝后给目标自己的提示文本翻译键。 */
    private static final String KEY_DENIED_TARGET = "aya-server-mod.command.tpa.denied.target";

    /** 撤销后给发起者的提示文本翻译键。 */
    private static final String KEY_CANCELLED_REQUESTER = "aya-server-mod.command.tpa.cancelled.requester";

    /** 撤销后给目标的提示文本翻译键。 */
    private static final String KEY_CANCELLED_TARGET = "aya-server-mod.command.tpa.cancelled.target";

    /** 任一方离线导致请求作废时，给仍在线一方的提示文本翻译键。 */
    private static final String KEY_PARTY_OFFLINE = "aya-server-mod.command.tpa.party_offline";

    /** 【接受】按钮文字的翻译键。 */
    private static final String KEY_BUTTON_ACCEPT = "aya-server-mod.button.accept";

    /** 【拒绝】按钮文字的翻译键。 */
    private static final String KEY_BUTTON_DENY = "aya-server-mod.button.deny";

    /** 【撤销】按钮文字的翻译键。 */
    private static final String KEY_BUTTON_CANCEL = "aya-server-mod.button.cancel";

    /**
     * 待处理请求表：目标玩家 UUID → 请求详情。
     * <p>
     * 以目标为键天然实现了「每个目标至多一个待处理请求」的约束。
     * 仅在服务端主线程（指令执行与 tick 回调）中读写，故用普通 HashMap 即可，无需同步。
     */
    private static final Map<UUID, PendingRequest> PENDING = new HashMap<>();

    /** 纯静态工具类，禁止实例化。 */
    private TpaRequests() {
    }

    /**
     * 单条待处理请求。
     * <p>
     * 不用 record 是因为 {@link #remainingTicks} 需要逐 tick 递减，必须可变。
     */
    private static final class PendingRequest {

        /** 发起者 UUID。存 UUID 而非 ServerPlayer 引用，避免玩家退出后持有失效实体。 */
        private final UUID requesterId;

        /** 剩余刻数，每服务端 tick 减一；归零即触发自动接受。 */
        private int remainingTicks;

        /**
         * @param requesterId 发起者 UUID
         */
        private PendingRequest(UUID requesterId) {
            this.requesterId = requesterId;
            this.remainingTicks = TIMEOUT_TICKS;
        }
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
     * @return 1 表示请求已发出；0 表示因自我请求或并发约束被拒绝（已向发起者说明原因）
     */
    public static int create(ServerPlayer requester, ServerPlayer target) {
        // 向自己发请求没有意义：既不需要征得同意，传送也是原地不动
        if (requester.getUUID().equals(target.getUUID())) {
            Messages.send(requester, KEY_SELF);
            return 0;
        }

        // 目标已有待处理请求：拒绝新请求，且不打扰目标（否则会被请求轰炸）
        if (PENDING.containsKey(target.getUUID())) {
            Messages.send(requester, KEY_TARGET_BUSY, target.getDisplayName());
            return 0;
        }

        // 发起者已有在途请求：拒绝，否则无参的 /cancel 将无从判断该撤销哪一条
        if (findByRequester(requester.getUUID()) != null) {
            Messages.send(requester, KEY_ALREADY_PENDING);
            return 0;
        }

        PENDING.put(target.getUUID(), new PendingRequest(requester.getUUID()));

        // 给发起者的回执，附【撤销】按钮
        Messages.send(requester, KEY_REQUEST_SENT,
                target.getDisplayName(),
                String.valueOf(TIMEOUT_SECONDS),
                button(requester, KEY_BUTTON_CANCEL, "/cancel", ChatFormatting.GRAY));

        // 给目标的请求提示，附【接受】【拒绝】按钮
        Messages.send(target, KEY_REQUEST_RECEIVED,
                requester.getDisplayName(),
                String.valueOf(TIMEOUT_SECONDS),
                button(target, KEY_BUTTON_ACCEPT, "/confirm", ChatFormatting.GREEN),
                button(target, KEY_BUTTON_DENY, "/deny", ChatFormatting.RED));

        return 1;
    }

    /**
     * 目标接受当前待处理的请求（{@code /confirm}）。
     *
     * @param target 执行 {@code /confirm} 的玩家
     * @return 1 表示已接受并完成传送；0 表示该玩家没有待处理请求
     */
    public static int accept(ServerPlayer target) {
        PendingRequest request = PENDING.remove(target.getUUID());
        if (request == null) {
            return 0;
        }
        complete(target, request, false);
        return 1;
    }

    /**
     * 目标拒绝当前待处理的请求（{@code /deny}）。
     *
     * @param target 执行 {@code /deny} 的玩家
     * @param reason 玩家给出的拒绝原因；为 {@code null} 表示未填写
     * @return 1 表示已拒绝；0 表示该玩家没有待处理请求
     */
    public static int deny(ServerPlayer target, String reason) {
        PendingRequest request = PENDING.remove(target.getUUID());
        if (request == null) {
            return 0;
        }

        Messages.send(target, KEY_DENIED_TARGET, displayNameOf(target, request.requesterId));

        ServerPlayer requester = resolve(target, request.requesterId);
        if (requester != null) {
            if (reason == null) {
                Messages.send(requester, KEY_DENIED_REQUESTER, target.getDisplayName());
            } else {
                // 原因为玩家自由输入，作为 literal 传入，不参与翻译
                Messages.send(requester, KEY_DENIED_REQUESTER_REASON,
                        target.getDisplayName(), Component.literal(reason));
            }
        }
        return 1;
    }

    /**
     * 发起者撤销自己发出的请求（{@code /cancel}）。
     *
     * @param requester 执行 {@code /cancel} 的玩家
     * @return 1 表示已撤销；0 表示该玩家没有在途请求
     */
    public static int cancel(ServerPlayer requester) {
        UUID targetId = findByRequester(requester.getUUID());
        if (targetId == null) {
            return 0;
        }
        PENDING.remove(targetId);

        Messages.send(requester, KEY_CANCELLED_REQUESTER, displayNameOf(requester, targetId));

        // 目标可能已离线，此时无须通知
        ServerPlayer target = resolve(requester, targetId);
        if (target != null) {
            Messages.send(target, KEY_CANCELLED_TARGET, requester.getDisplayName());
        }
        return 1;
    }

    /**
     * 服务端 tick 回调：校验双方是否仍在线、推进倒计时，并对到期请求执行自动接受。
     *
     * @param server 服务器实例，用于按 UUID 解析在线玩家
     */
    private static void tick(MinecraftServer server) {
        // 绝大多数 tick 没有待处理请求，提前返回避免无谓开销
        if (PENDING.isEmpty()) {
            return;
        }

        // 在遍历中只做摘除与记录，发消息与传送留到遍历结束后统一执行，
        // 以免在迭代 PENDING 的过程中间接修改该表
        List<Map.Entry<UUID, PendingRequest>> expired = new ArrayList<>();
        List<ServerPlayer> orphanedPlayers = new ArrayList<>();

        Iterator<Map.Entry<UUID, PendingRequest>> iterator = PENDING.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, PendingRequest> entry = iterator.next();
            ServerPlayer target = server.getPlayerList().getPlayer(entry.getKey());
            ServerPlayer requester = server.getPlayerList().getPlayer(entry.getValue().requesterId);

            // 任一方离线，请求立即作废；仍在线的一方会收到说明
            if (target == null || requester == null) {
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
            expired.add(entry);
        }

        for (ServerPlayer player : orphanedPlayers) {
            Messages.send(player, KEY_PARTY_OFFLINE);
        }

        for (Map.Entry<UUID, PendingRequest> entry : expired) {
            ServerPlayer target = server.getPlayerList().getPlayer(entry.getKey());
            // 上面刚校验过双方在线，此处判空仅为防御
            if (target != null) {
                complete(target, entry.getValue(), true);
            }
        }
    }

    /**
     * 执行传送并通知双方。{@code /confirm} 与超时自动接受共用此逻辑。
     *
     * @param target    目标玩家，发起者将被传送到它所在位置
     * @param request   已从 {@link #PENDING} 中摘除的请求
     * @param automatic {@code true} 表示因超时自动接受，{@code false} 表示目标主动接受；
     *                  两者发送的提示文案不同
     */
    private static void complete(ServerPlayer target, PendingRequest request, boolean automatic) {
        ServerPlayer requester = resolve(target, request.requesterId);
        if (requester == null) {
            // 发起者已离线，传送无从谈起，仅告知目标
            Messages.send(target, KEY_PARTY_OFFLINE);
            return;
        }

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

        if (automatic) {
            Messages.send(requester, KEY_AUTO_ACCEPTED_REQUESTER,
                    target.getDisplayName(), String.valueOf(TIMEOUT_SECONDS));
            Messages.send(target, KEY_AUTO_ACCEPTED_TARGET,
                    String.valueOf(TIMEOUT_SECONDS), requester.getDisplayName());
        } else {
            Messages.send(requester, KEY_ACCEPTED_REQUESTER, target.getDisplayName());
            Messages.send(target, KEY_ACCEPTED_TARGET, requester.getDisplayName());
        }
    }

    /**
     * 构造一个可点击的按钮组件。
     *
     * @param viewer  按钮的观看者，其客户端语言决定按钮文字的 fallback 语言
     * @param key     按钮文字的翻译键
     * @param command 点击后执行的指令，须含前导斜杠
     * @param color   按钮颜色
     * @return 带颜色、点击事件与悬停提示的按钮组件
     */
    private static MutableComponent button(ServerPlayer viewer, String key, String command, ChatFormatting color) {
        return Messages.of(viewer, key).withStyle(style -> style
                .withColor(color)
                // ClickEvent 在 26.2 中是密封接口 + record，RunCommand 表示「点击后以玩家身份执行该指令」
                .withClickEvent(new ClickEvent.RunCommand(command))
                // 悬停显示指令原文，便于玩家知道也可以手动输入；指令名不翻译，故用 literal
                .withHoverEvent(new HoverEvent.ShowText(Component.literal(command))));
    }

    /**
     * 反查某发起者的在途请求所对应的目标。
     * <p>
     * 待处理请求表以目标为键，因此按发起者查找需遍历。待处理请求数量极少
     * （每目标至多一条，且仅存活 {@link #TIMEOUT_SECONDS} 秒），遍历开销可忽略，
     * 故不额外维护反向索引，以免引入两表同步的一致性负担。
     *
     * @param requesterId 发起者 UUID
     * @return 对应的目标玩家 UUID；该发起者没有在途请求时返回 {@code null}
     */
    private static UUID findByRequester(UUID requesterId) {
        for (Map.Entry<UUID, PendingRequest> entry : PENDING.entrySet()) {
            if (entry.getValue().requesterId.equals(requesterId)) {
                return entry.getKey();
            }
        }
        return null;
    }

    /**
     * 借助任一在线玩家取得服务器实例，按 UUID 解析另一名在线玩家。
     *
     * @param context  任一在线玩家，仅用于取得服务器实例
     * @param playerId 待解析的玩家 UUID
     * @return 对应的在线玩家；不在线时返回 {@code null}
     */
    private static ServerPlayer resolve(ServerPlayer context, UUID playerId) {
        // ServerPlayer 未公开 server 字段的读取方法，故经由所在世界取服务器实例：
        // ServerPlayer.level() 协变返回 ServerLevel，其 getServer() 必非空
        MinecraftServer server = context.level().getServer();
        return server.getPlayerList().getPlayer(playerId);
    }

    /**
     * 取得某玩家的显示名，用于消息参数；玩家已离线时退化为不可翻译的占位文本。
     *
     * @param context  任一在线玩家，仅用于取得服务器实例
     * @param playerId 目标玩家 UUID
     * @return 显示名组件，或离线时的占位文本
     */
    private static Component displayNameOf(ServerPlayer context, UUID playerId) {
        ServerPlayer player = resolve(context, playerId);
        return player == null ? Component.literal("?") : player.getDisplayName();
    }
}
