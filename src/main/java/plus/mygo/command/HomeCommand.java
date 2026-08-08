package plus.mygo.command;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;
import plus.mygo.i18n.Messages;

import java.util.Locale;
import java.util.Set;

/**
 * /home 指令。
 * <p>
 * 仅限玩家执行，无参数。将执行者传送回其设置的重生点（床或重生锚，支持跨维度）。
 * 重生坐标的查找复用原版复活逻辑 {@code ServerPlayer.findRespawnPositionAndUseSpawnBlock}：
 * 该方法内部按重生方块类型分派到床 / 重生锚各自的站立点查找算法。
 * <p>
 * 若玩家从未设置重生点，或床 / 重生锚已被破坏、被遮挡、（重生锚）无充能，则命令无效：
 * 不传送，并按具体原因发送对应提示；否则传送并回执目标坐标。
 * 查找时传入 {@code useCharge=false}，因此不消耗重生锚充能，{@code /home} 可反复使用。
 */
public class HomeCommand {

    /** 传送成功后的提示文本翻译键，参数为重生点的 x / y / z 坐标。 */
    private static final String KEY_SUCCESS = "aya-server-mod.command.home.success";

    /** 玩家从未设置重生点时的提示文本翻译键。 */
    private static final String KEY_NOT_SET = "aya-server-mod.command.home.not_set";

    /** 重生方块已被破坏、被遮挡或重生锚无充能时的提示文本翻译键。 */
    private static final String KEY_OBSTRUCTED = "aya-server-mod.command.home.obstructed";

    /**
     * 向 Fabric 命令系统注册 /home 指令。
     * 应在 {@link plus.mygo.AyaServerMod#onInitialize()} 中调用一次，且仅调用一次。
     */
    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
            dispatcher.register(Commands.literal("home")
                // 声明任何来源都可执行，非玩家由 execute 中的 getPlayerOrException() 拦下。
                // 不可改用 requires(isPlayer)：那会让本指令被标记为受限指令，
                // 玩家点击聊天里的按钮时会弹出提权确认框（详见 CLAUDE.md「命令编写规范」）。
                .requires(Commands.hasPermission(Commands.LEVEL_ALL))
                .executes(HomeCommand::execute)
            )
        );
    }

    /**
     * 指令执行逻辑：将执行者传送回其重生点。
     *
     * @param context Brigadier 提供的指令上下文
     * @return 1 表示传送成功；0 表示无可用重生点（命令无效）
     * @throws CommandSyntaxException 来源不是玩家时抛出，携带原版本地化提示「需要玩家身份才能执行」
     */
    private static int execute(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();

        // 来源不是玩家时在此抛出原版本地化错误（permissions.requires.player）
        ServerPlayer player = source.getPlayerOrException();

        // 先判断玩家是否设置过重生点。未设置时原版查找逻辑会回退到世界出生点，
        // 而 /home 的语义是"回自己的家"，不应把人送到世界出生点，故在此直接拒绝，
        // 顺带省去一次无谓的重生点查找。
        if (player.getRespawnConfig() == null) {
            Messages.sendFailure(source, KEY_NOT_SET);
            return 0;
        }

        // 复用原版复活坐标查找逻辑：
        //   useCharge=false → 仅查找坐标、不消耗重生锚充能，使 /home 可反复使用；
        //   DO_NOTHING      → 传送后不附加额外行为（不播放传送门音效等）。
        // 返回的 TeleportTransition 已携带解析后的目标维度、坐标与朝向。
        TeleportTransition transition =
                player.findRespawnPositionAndUseSpawnBlock(false, TeleportTransition.DO_NOTHING);

        // 重生方块已被破坏、被遮挡，或重生锚无充能：坐标不可用，不传送
        if (transition.missingRespawnBlock()) {
            Messages.sendFailure(source, KEY_OBSTRUCTED);
            return 0;
        }

        // 目标维度来自 transition，故天然支持跨维度回家
        ServerLevel targetLevel = transition.newLevel();
        Vec3 pos = transition.position();

        // Set.of() 表示所有坐标均为绝对值；采用重生点记录的朝向；最后的 boolean=true 重置镜头
        player.teleportTo(
                targetLevel,
                pos.x, pos.y, pos.z,
                Set.of(),
                transition.yRot(), transition.xRot(),
                true
        );

        // 回执重生点坐标。坐标为浮点数，用 Locale.ROOT 固定小数点格式并保留两位小数，
        // 避免在服务器默认区域设置使用逗号作小数点时格式化出 "12,50" 这类文本
        Messages.sendSuccess(source, KEY_SUCCESS,
                String.format(Locale.ROOT, "%.2f", pos.x),
                String.format(Locale.ROOT, "%.2f", pos.y),
                String.format(Locale.ROOT, "%.2f", pos.z));

        return 1;
    }
}
