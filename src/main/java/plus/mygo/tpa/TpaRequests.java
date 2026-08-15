package plus.mygo.tpa;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
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
import plus.mygo.AyaServerMod;
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
 * 承载 {@code /tpa}、{@code /tphere}、{@code /accept}、{@code /reject}、{@code /cancel}
 * 五条指令共用的状态与逻辑：发起请求、接受、拒绝、撤销，以及超时失效。
 * 指令类只负责参数解析与失败提示，请求流程本身的全部消息由本类发出。
 * <p>
 * <b>只有目标明确接受才会传送。</b>请求必须在 {@link #TIMEOUT_SECONDS} 秒内被接受，
 * 逾期即失效并通知双方，不执行传送。
 * <p>
 * <b>两条发起指令共用同一条请求记录。</b>{@code /tpa} 与 {@code /tphere} 的差别只在于
 * 接受后由谁移动（见 {@link Movement}），请求本身的身份仍是「谁向谁发起」。
 * 因此 jack 对 bob 已有一条待处理请求时，无论那条原本是 {@code /tpa} 还是 {@code /tphere}，
 * 他都无法再向 bob 发起另一条 —— 必须先撤销。这也意味着接受、拒绝、撤销三条指令
 * 完全无需关心方向，方向只在真正执行传送时才被读出。
 * <p>
 * <b>反向请求视同接受。</b>若对方已有一条发给自己的待处理请求，而自己新发的这条
 * 指向完全相同的结果（同一个人移动到同一个地方），则不新建请求，直接当作接受成交。
 * 例如 alice {@code /tpa bob} 待处理时 bob 打 {@code /tphere alice}，反之亦然。
 * 两条<b>同向</b>请求（如双方都 {@code /tpa} 对方）结果相反，不构成同意，仍各自独立存在。
 * <p>
 * <b>有两种目标不必逐条回应。</b>二者是同一件事的两个来源，因而合流于
 * {@link #acceptsAutomatically} 一处判定，命中即当场成交、不新建请求：
 * <ul>
 *   <li><b>Carpet 假人</b> —— 不看聊天栏也不会打指令，让它干等到超时毫无意义，
 *       故<b>两个方向都</b>视为同意。该联动是纯运行时软探测
 *       （见 {@link #resolveCarpetFakePlayer}），<b>不引入编译期依赖、不使用 Mixin</b>，
 *       未装 Carpet 时完全无感；</li>
 *   <li><b>开了 {@code /auto-accept-tpa} 的玩家</b> —— 他已一次性声明「传过来不必问我」。
 *       只覆盖 {@code /tpa} 一个方向：{@code /tphere} 会把他本人拽到别处，
 *       必须每次亲自过目，故照旧进入待回应流程。见 {@link AutoAcceptTpa}。</li>
 * </ul>
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

    /** {@code /tphere} 请求发出后给发起者的回执文本翻译键。 */
    private static final String KEY_TPHERE_REQUEST_SENT = "aya-server-mod.command.tphere.request.sent";

    /** {@code /tphere} 请求发出后给目标的提示文本翻译键。 */
    private static final String KEY_TPHERE_REQUEST_RECEIVED = "aya-server-mod.command.tphere.request.received";

    /** 目标接受后，给发起者的提示文本翻译键。 */
    private static final String KEY_ACCEPTED_REQUESTER = "aya-server-mod.command.tpa.accepted.requester";

    /** 目标接受后，给目标自己的提示文本翻译键。 */
    private static final String KEY_ACCEPTED_TARGET = "aya-server-mod.command.tpa.accepted.target";

    /** 因目标开启了自动接受而当场成交时，给目标自己的提示文本翻译键。 */
    private static final String KEY_AUTO_ACCEPTED = "aya-server-mod.command.tpa.auto_accepted";

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

    /** 【发送请求】按钮文字的翻译键，出现在超时提示中。 */
    private static final String KEY_BUTTON_SEND_REQUEST = "aya-server-mod.button.send_request";

    /** Carpet 模组的 id，与其 fabric.mod.json 中的 id 字段一致。 */
    private static final String CARPET_MOD_ID = "carpet";

    /**
     * Carpet 假人的类名。{@code /player <名字> spawn} 与 {@code /player <名字> shadow}
     * 分别经 {@code createFake} / {@code createShadow} 创建，两者产出的都是该类。
     */
    private static final String CARPET_FAKE_PLAYER_CLASS = "carpet.patches.EntityPlayerMPFake";

    /**
     * Carpet 假人类，未安装 Carpet 时为 {@code null}。
     * <p>
     * 刻意只按类名反射解析，<b>不引入对 Carpet 的编译期依赖</b>，也不使用 Mixin ——
     * 本模组在没有 Carpet 的环境下必须照常工作，因此这只能是一个软联动。
     * 该字段在类初始化时解析一次并缓存，此后 {@link #isCarpetFakePlayer} 只是一次
     * {@code isInstance} 调用。用 {@code isInstance} 而非比较类名，是为了让潜在的子类同样生效。
     */
    private static final Class<?> CARPET_FAKE_PLAYER = resolveCarpetFakePlayer();

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
     * 请求被接受后由谁移动 —— 这是 {@code /tpa} 与 {@code /tphere} 唯一的区别。
     * <p>
     * 请求的「发起者 / 目标」身份与本枚举无关：无论哪个方向，发起者都是打出指令的人，
     * 目标都是被征求同意的人。本枚举只决定传送时谁是移动方、谁是落点。
     */
    public enum Movement {

        /** {@code /tpa}：接受后发起者被传送到目标身边。 */
        REQUESTER_TO_TARGET,

        /** {@code /tphere}：接受后目标被传送到发起者身边。 */
        TARGET_TO_REQUESTER
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
     * 单条待处理请求的状态。
     * <p>
     * 不用 record 是因为 {@link #remainingTicks} 需要逐 tick 递减，必须可变；
     * 双方身份已由 {@link RequestKey} 承载，此处不再重复存放。
     */
    private static final class PendingRequest {

        /** 接受后由谁移动，由发起时用的是 {@code /tpa} 还是 {@code /tphere} 决定，此后不再变化。 */
        private final Movement movement;

        /** 剩余刻数，每服务端 tick 减一；归零即判定超时失效。 */
        private int remainingTicks = TIMEOUT_TICKS;

        /**
         * @param movement 接受后由谁移动
         */
        private PendingRequest(Movement movement) {
            this.movement = movement;
        }
    }

    /**
     * 注册 tick 回调，用于推进倒计时并清理失效请求。
     * 应在 {@link plus.mygo.AyaServerMod#onInitialize()} 中调用一次，且仅调用一次。
     */
    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(TpaRequests::tick);

        if (CARPET_FAKE_PLAYER != null) {
            AyaServerMod.LOGGER.info("检测到 Carpet，其 /player 假人将自动接受传送请求");
        }
    }

    /**
     * 解析 Carpet 假人类，供 {@link #CARPET_FAKE_PLAYER} 初始化。
     * <p>
     * 先问 Fabric Loader 是否装了 Carpet，再按名字取类：两道判断都失败得起，
     * 未装 Carpet 时静默返回 {@code null}，本模组的其余功能不受任何影响。
     *
     * @return Carpet 假人类；未安装 Carpet 或类名对不上时返回 {@code null}
     */
    private static Class<?> resolveCarpetFakePlayer() {
        if (!FabricLoader.getInstance().isModLoaded(CARPET_MOD_ID)) {
            return null;
        }
        try {
            // Fabric 下所有模组共用同一个类加载器，故默认加载器即可找到 Carpet 的类
            return Class.forName(CARPET_FAKE_PLAYER_CLASS);
        } catch (ClassNotFoundException e) {
            // Carpet 在场却找不到该类，说明其内部结构变了。只停用本联动，不影响其余功能
            AyaServerMod.LOGGER.warn("检测到 Carpet，但未找到假人类 {}，假人自动接受功能已停用",
                    CARPET_FAKE_PLAYER_CLASS, e);
            return null;
        }
    }

    /**
     * 判断某玩家是否为 Carpet 用 {@code /player} 召唤出的假人。
     *
     * @param player 待判断的玩家
     * @return 是假人则返回 {@code true}；未安装 Carpet 时恒为 {@code false}
     */
    private static boolean isCarpetFakePlayer(ServerPlayer player) {
        return CARPET_FAKE_PLAYER != null && CARPET_FAKE_PLAYER.isInstance(player);
    }

    /**
     * 判断一条请求是否根本不必征求目标同意，可以当场成交。
     * <p>
     * 两个来源在此合流，它们是同一件事：<b>目标不会（或无从）逐条回应</b>。
     * <ul>
     *   <li><b>Carpet 假人</b> —— 不看聊天栏也不会打指令，让它干等到超时毫无意义，
     *       因此<b>两个方向都</b>视为同意；</li>
     *   <li><b>开了 {@code /auto-accept-tpa} 的玩家</b> —— 他已一次性声明「传过来不必问我」，
     *       但那只覆盖<b>一个方向</b>，见 {@link #hasAutoAcceptOn}。</li>
     * </ul>
     *
     * @param target   请求的目标玩家
     * @param movement 该请求被接受后由谁移动
     * @return 可当场成交则返回 {@code true}
     */
    private static boolean acceptsAutomatically(ServerPlayer target, Movement movement) {
        return isCarpetFakePlayer(target) || hasAutoAcceptOn(target, movement);
    }

    /**
     * 判断目标是否<b>凭自己开的那个开关</b>接受这条请求。
     * <p>
     * 与 {@link #acceptsAutomatically} 的差别只在于不含假人 —— 假人没有开关可关，
     * 因此这同时也是「回执里该不该附一个【关闭自动接受】按钮」的判据。
     * <p>
     * 开关只覆盖 {@link Movement#REQUESTER_TO_TARGET}：那个方向里目标本人不动，风险为零；
     * 反方向会把他瞬间拽到别处，必须每次亲自过目（详见 {@link AutoAcceptTpa}）。
     *
     * @param target   请求的目标玩家
     * @param movement 该请求被接受后由谁移动
     * @return 目标开了自动接受、且方向在覆盖范围内则返回 {@code true}
     */
    private static boolean hasAutoAcceptOn(ServerPlayer target, Movement movement) {
        return movement == Movement.REQUESTER_TO_TARGET && AutoAcceptTpa.isEnabled(target);
    }

    /**
     * 发起一条传送请求。{@code /tpa} 与 {@code /tphere} 共用本方法，仅 {@code movement} 不同。
     *
     * @param requester 发起者，即打出指令的一方
     * @param target    目标玩家，请求将发给它
     * @param movement  请求被接受后由谁移动
     * @return 1 表示请求已发出；0 表示因自我请求或重复请求被拒绝（已向发起者说明原因）
     */
    public static int create(ServerPlayer requester, ServerPlayer target, Movement movement) {
        // 向自己发请求没有意义：既不需要征得同意，传送也是原地不动
        if (requester.getUUID().equals(target.getUUID())) {
            Messages.send(requester, KEY_SELF);
            return 0;
        }

        RequestKey key = new RequestKey(requester.getUUID(), target.getUUID());

        // 互相同意：对方此刻正有一条发给我的待处理请求，且它与我这条指向完全相同的结果
        //（同一个人移动到同一个地方）—— 那么我这条指令本质上就是在接受对方的请求，
        // 直接成交，不再新建请求。
        //
        // 举例：alice /tpa bob 待处理（alice 去 bob 那），此时 bob 打 /tphere alice
        //（也是 alice 去 bob 那），二者意图一致；反之亦然。
        // 而两条同向请求（如双方都 /tpa 对方）结果相反，不构成同意，仍各自独立存在。
        //
        // 此判断刻意排在「已向同一目标发过请求」之前：即便我自己对对方也有一条在途请求，
        // 能促成的传送也应当促成，而不是报错让人先撤销。
        RequestKey reverseKey = new RequestKey(target.getUUID(), requester.getUUID());
        PendingRequest reverse = PENDING.get(reverseKey);
        if (reverse != null && moverOf(reverseKey, reverse.movement).equals(moverOf(key, movement))) {
            PENDING.remove(reverseKey);
            // 消耗的是对方那条请求：在那条请求里，对方是发起者、我是目标
            complete(target, requester, reverse.movement);
            return 1;
        }

        // 已向同一目标发过且尚未失效：不重复打扰对方，也不重置计时，
        // 而是提示发起者先撤销 —— 附上已填好目标玩家名的【撤销】按钮。
        // 注意此处不区分那条请求原本是哪个方向：两个方向共用同一个键，
        // 故提示文案保持中立，玩家点【撤销】后即可改发另一个方向
        if (PENDING.containsKey(key)) {
            Messages.send(requester, KEY_ALREADY_PENDING,
                    target.getDisplayName(),
                    cancelButton(requester, target));
            return 0;
        }

        // 目标不必（或无从）逐条回应时，当场成交，不新建请求。
        // 假人与自动接受开关是同一件事的两个来源，故合流于 acceptsAutomatically 一处判定。
        //
        // 排在「已向同一目标发过请求」之后：那种情况说明发起者对目标还有一条在途请求，
        // 提示他先撤销，比在这里静默丢弃那条记录更清楚，也维持了
        //「同一对玩家之间至多一条待处理请求」这条不变式。
        // 对假人来说这个位置与放在前面没有区别 —— 发给假人的请求从不被记录，
        // 那条检查对它永不命中。唯一的例外是 Carpet 的 /player <名字> shadow
        // 把一名在线玩家原地换成假人，他此前收到的请求仍在表里，
        // 于是发起者会先被要求撤销 —— 这同样说得通。
        if (acceptsAutomatically(target, movement)) {
            completeAutomatically(requester, target, movement);
            return 1;
        }

        PENDING.put(key, new PendingRequest(movement));

        // 两条发起指令的差别仅体现在方向上：一边是「我过去」，一边是「你过来」
        boolean requesterMoves = movement == Movement.REQUESTER_TO_TARGET;

        // 给发起者的回执，附【撤销】按钮
        Messages.send(requester, requesterMoves ? KEY_REQUEST_SENT : KEY_TPHERE_REQUEST_SENT,
                target.getDisplayName(),
                cancelButton(requester, target));

        // 给目标的请求提示，按钮均已填好发起者玩家名。
        // 两个方向的按钮组不同：自动接受只覆盖 /tpa，因此也只有它附带【自动接受】——
        // 把一个点了也不管用的按钮摆进 /tphere 的提示里只会误导人
        if (requesterMoves) {
            Messages.send(target, KEY_REQUEST_RECEIVED,
                    requester.getDisplayName(),
                    acceptButton(target, requester),
                    rejectButton(target, requester),
                    AutoAcceptTpa.onButton(target));
        } else {
            Messages.send(target, KEY_TPHERE_REQUEST_RECEIVED,
                    requester.getDisplayName(),
                    acceptButton(target, requester),
                    rejectButton(target, requester));
        }

        return 1;
    }

    /**
     * 应某玩家开启自动接受，把此刻发给他、仍在等回应的 {@code /tpa} 请求全部接受并传送。
     * <p>
     * <b>刻意跳过 {@code /tphere} 请求</b>：自动接受只覆盖「发起者过来」的方向，
     * 反方向会把他本人拽走，仍须他亲自回应，因此那些请求原封不动地留在表里继续等。
     * <p>
     * 每条成交都走 {@link #complete}，即目标看到的仍是平常那句「已接受 X 的传送请求」——
     * 上一行的开启回执已经说明了原因，此处不必每条都再重复一遍「自动」。
     *
     * @param target 刚开启自动接受的玩家，即这些请求的共同目标
     */
    static void acceptAllIncomingTpa(ServerPlayer target) {
        MinecraftServer server = target.level().getServer();
        UUID targetId = target.getUUID();

        // 先把要成交的请求整体摘出来，再逐条传送：complete() 会发消息也会传送玩家，
        // 不宜在遍历 PENDING 的过程中调用
        List<UUID> requesterIds = new ArrayList<>();

        var iterator = PENDING.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<RequestKey, PendingRequest> entry = iterator.next();
            if (!entry.getKey().targetId().equals(targetId)
                    || entry.getValue().movement != Movement.REQUESTER_TO_TARGET) {
                continue;
            }
            iterator.remove();
            requesterIds.add(entry.getKey().requesterId());
        }

        for (UUID requesterId : requesterIds) {
            ServerPlayer requester = server.getPlayerList().getPlayer(requesterId);
            // tick 回调保证只有双方都在线的请求才留在表中，此处判空仅为防御
            if (requester != null) {
                complete(requester, target, Movement.REQUESTER_TO_TARGET);
            }
        }
    }

    /**
     * 目标接受来自指定发起者的请求（{@code /accept <玩家>}）。
     *
     * @param target    执行 {@code /accept} 的玩家
     * @param requester 被接受的那条请求的发起者
     * @return 1 表示已接受并完成传送；0 表示该发起者并未向目标发送过待处理请求
     */
    public static int accept(ServerPlayer target, ServerPlayer requester) {
        PendingRequest request = PENDING.remove(new RequestKey(requester.getUUID(), target.getUUID()));
        if (request == null) {
            return 0;
        }
        // 方向在发起时就已确定并存进请求里，接受方无需（也无从）指定
        complete(requester, target, request.movement);
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
        // 到期项连同方向一并记下：expire() 要据此为超时方生成回发按钮。
        // 用 Map.entry 复制出不可变快照，避免持有已被 iterator 移除的 Map.Entry 视图
        List<Map.Entry<RequestKey, Movement>> expired = new ArrayList<>();
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
            expired.add(Map.entry(entry.getKey(), entry.getValue().movement));
        }

        for (ServerPlayer player : orphanedPlayers) {
            Messages.send(player, KEY_PARTY_OFFLINE);
        }

        for (Map.Entry<RequestKey, Movement> entry : expired) {
            RequestKey key = entry.getKey();
            ServerPlayer requester = server.getPlayerList().getPlayer(key.requesterId());
            ServerPlayer target = server.getPlayerList().getPlayer(key.targetId());
            // 上面刚校验过双方在线，此处判空仅为防御
            if (requester != null && target != null) {
                expire(requester, target, entry.getValue());
            }
        }
    }

    /**
     * 处理一条超时失效的请求：<b>不执行传送</b>，仅告知双方请求已作废。
     * <p>
     * 给超时未回应的一方额外附上【发送请求】按钮，让他能一键把同样的意图发回去
     * —— 详见 {@link #resendButton}。发起者那边不附按钮：重打一遍原指令即可。
     *
     * @param requester 发起者
     * @param target    目标玩家，即未在期限内回应的一方
     * @param movement  原请求的方向，用于推算回发按钮该填哪条指令
     */
    private static void expire(ServerPlayer requester, ServerPlayer target, Movement movement) {
        Messages.send(requester, KEY_EXPIRED_REQUESTER,
                target.getDisplayName(), String.valueOf(TIMEOUT_SECONDS));
        Messages.send(target, KEY_EXPIRED_TARGET,
                String.valueOf(TIMEOUT_SECONDS),
                requester.getDisplayName(),
                resendButton(target, requester, movement));
    }

    /**
     * 构造超时提示里的【发送请求】按钮：填入一条由超时方发回给原发起者、
     * 且<b>结果与原请求完全一致</b>的指令。
     * <p>
     * 因为新旧两条请求的发起者与目标恰好互换，要让「谁移动到哪」保持不变，
     * 新请求的方向必须与原请求相反：
     * <ul>
     *   <li>原为 {@code /tpa}（发起者过去）→ 按钮填 {@code /tphere <原发起者>}；</li>
     *   <li>原为 {@code /tphere}（目标过去）→ 按钮填 {@code /tpa <原发起者>}。</li>
     * </ul>
     * 若原发起者此时恰好又发来了同样意图的请求，这条指令会被 {@link #create} 判定为
     * 互相同意而直接成交，不会新建请求。
     *
     * @param viewer            按钮的观看者，即超时未回应的目标玩家
     * @param originalRequester 原请求的发起者，新指令将指向他
     * @param movement          原请求的方向
     * @return 可点击的【发送请求】按钮组件
     */
    private static MutableComponent resendButton(
            ServerPlayer viewer, ServerPlayer originalRequester, Movement movement) {
        String command = (movement == Movement.REQUESTER_TO_TARGET ? "/tphere " : "/tpa ")
                + originalRequester.getGameProfile().name();
        return button(viewer, KEY_BUTTON_SEND_REQUEST, command, ChatFormatting.AQUA);
    }

    /**
     * 求出某条请求被接受后实际移动的那名玩家。
     * <p>
     * 用于判断两条方向相反的请求是否指向同一结果：因为它们的发起者与目标互换，
     * 只要移动方相同，落点必然也相同，即构成「互相同意」。
     *
     * @param key      请求的双方身份
     * @param movement 请求的方向
     * @return 接受后被传送的那名玩家的 UUID
     */
    private static UUID moverOf(RequestKey key, Movement movement) {
        return movement == Movement.REQUESTER_TO_TARGET ? key.requesterId() : key.targetId();
    }

    /**
     * 目标接受请求后：按请求记录的方向执行传送并通知双方。
     *
     * @param requester 发起者
     * @param target    目标玩家
     * @param movement  接受后由谁移动，决定谁是移动方、谁是落点
     */
    private static void complete(ServerPlayer requester, ServerPlayer target, Movement movement) {
        teleport(requester, target, movement);

        // 两条回执与方向无关：无论谁移动，都是「目标接受了发起者的请求」
        Messages.send(requester, KEY_ACCEPTED_REQUESTER, target.getDisplayName());
        Messages.send(target, KEY_ACCEPTED_TARGET, requester.getDisplayName());
    }

    /**
     * 未经目标回应就当场成交（判据见 {@link #acceptsAutomatically}）：执行传送并通知双方。
     * <p>
     * 发起者那边的回执与被手动接受时完全一致 —— 对他而言结果没有任何区别，
     * 也没必要让他知道对方是靠开关还是靠手动放行的。
     * <p>
     * 目标那边则分两种：凭自己开的开关放行时，明确告诉他「已自动接受」，
     * 并附上【关闭自动接受】按钮以便随时反悔；假人没有开关可关，沿用平常那句回执
     *（反正它不看聊天栏，这里只求不给它发一句会误导人的话）。
     *
     * @param requester 发起者
     * @param target    目标玩家
     * @param movement  接受后由谁移动
     */
    private static void completeAutomatically(ServerPlayer requester, ServerPlayer target, Movement movement) {
        teleport(requester, target, movement);

        Messages.send(requester, KEY_ACCEPTED_REQUESTER, target.getDisplayName());

        if (hasAutoAcceptOn(target, movement)) {
            Messages.send(target, KEY_AUTO_ACCEPTED,
                    requester.getDisplayName(), AutoAcceptTpa.offButton(target));
        } else {
            Messages.send(target, KEY_ACCEPTED_TARGET, requester.getDisplayName());
        }
    }

    /**
     * 执行传送本身，不发送任何消息 —— 消息由各调用方按成交的缘由自行决定。
     *
     * @param requester 发起者
     * @param target    目标玩家
     * @param movement  接受后由谁移动，决定下面谁是移动方、谁是落点
     */
    private static void teleport(ServerPlayer requester, ServerPlayer target, Movement movement) {
        boolean requesterMoves = movement == Movement.REQUESTER_TO_TARGET;
        ServerPlayer mover = requesterMoves ? requester : target;
        ServerPlayer destination = requesterMoves ? target : requester;

        // ServerPlayer.level() 协变返回 ServerLevel，无需额外转型
        ServerLevel destinationLevel = destination.level();

        // Set.of() 表示所有坐标均为绝对值（非相对偏移）；保持移动方当前朝向不变；
        // 最后一个 boolean 参数 true 表示传送完成后重置镜头
        mover.teleportTo(
                destinationLevel,
                destination.getX(), destination.getY(), destination.getZ(),
                Set.of(),
                mover.getYRot(), mover.getXRot(),
                true
        );

        // 原版传送（含 /tp）不会清空已累积的下落距离，移动方若在坠落途中被传送，
        // 会带着旧的下落距离落地并照常摔伤甚至摔死 —— 而接受的时机不由他决定
        //（/tphere 下移动方甚至就是接受者本人之外的另一方），更不该因此受伤。
        // 这里显式清零，使传送落点始终从零开始计算坠落伤害。
        mover.resetFallDistance();
    }

    /**
     * 构造一个已填好发起者玩家名的【接受】按钮。
     *
     * @param viewer    按钮的观看者，即被征求同意的目标玩家
     * @param requester 该条请求的发起者
     * @return 绿色的可点击【接受】按钮组件
     */
    private static MutableComponent acceptButton(ServerPlayer viewer, ServerPlayer requester) {
        return button(viewer, KEY_BUTTON_ACCEPT,
                "/accept " + requester.getGameProfile().name(), ChatFormatting.GREEN);
    }

    /**
     * 构造一个已填好发起者玩家名的【拒绝】按钮。
     *
     * @param viewer    按钮的观看者，即被征求同意的目标玩家
     * @param requester 该条请求的发起者
     * @return 红色的可点击【拒绝】按钮组件
     */
    private static MutableComponent rejectButton(ServerPlayer viewer, ServerPlayer requester) {
        // 末尾刻意留一个空格：填入聊天栏后光标落在下一个参数位上，
        // 原版的命令提示会自动显示出可选的「原因」，无需在文案里另行解释
        return button(viewer, KEY_BUTTON_REJECT,
                "/reject " + requester.getGameProfile().name() + " ", ChatFormatting.RED);
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
     * <p>
     * 刻意放开到包级可见：{@link AutoAcceptTpa} 的两个按钮要用同一套样式与点击行为，
     * 为这十来行再单开一个工具类不值当。包外仍然不可见。
     *
     * @param viewer  按钮的观看者，其客户端语言决定按钮文字的 fallback 语言
     * @param key     按钮文字的翻译键
     * @param command 点击后填入聊天栏的指令，须含前导斜杠
     * @param color   按钮颜色
     * @return 带颜色、点击事件与悬停提示的按钮组件
     */
    static MutableComponent button(ServerPlayer viewer, String key, String command, ChatFormatting color) {
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
