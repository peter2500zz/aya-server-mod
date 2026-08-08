package plus.mygo.command;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import plus.mygo.i18n.Messages;

import java.util.Optional;
import java.util.Set;

/**
 * /back 指令。
 * <p>
 * 仅限玩家执行，无参数。将执行者传送回其上一次死亡的地点（含维度，支持跨维度）。
 * 死亡地点由原版自动记录在玩家数据中（last death location，亦用于恢复指南针指向）：
 * 若玩家从未死亡、或死亡所在维度已不存在，则不传送并给出对应的失败提示。
 */
public class BackCommand {

    /** 传送成功后的提示文本翻译键，无参数。 */
    private static final String KEY_SUCCESS = "aya-server-mod.command.back.success";

    /** 玩家从未死亡时的提示文本翻译键。 */
    private static final String KEY_NO_DEATH = "aya-server-mod.command.back.no_death";

    /** 死亡地点所在维度已不存在时的提示文本翻译键。 */
    private static final String KEY_DIMENSION_MISSING = "aya-server-mod.command.back.dimension_missing";

    /**
     * 向 Fabric 命令系统注册 /back 指令。
     * 应在 {@link plus.mygo.AyaServerMod#onInitialize()} 中调用一次，且仅调用一次。
     */
    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
            dispatcher.register(Commands.literal("back")
                // 声明任何来源都可执行，非玩家由 execute 中的 getPlayerOrException() 拦下。
                // 不可改用 requires(isPlayer)：那会让本指令被标记为受限指令，
                // 玩家点击聊天里的按钮时会弹出提权确认框（详见 CLAUDE.md「命令编写规范」）。
                .requires(Commands.hasPermission(Commands.LEVEL_ALL))
                .executes(BackCommand::execute)
            )
        );
    }

    /**
     * 指令执行逻辑：将执行者传送回上一次死亡地点。
     *
     * @param context Brigadier 提供的指令上下文
     * @return 1 表示传送成功；0 表示无可用的死亡地点（命令无效）
     * @throws CommandSyntaxException 来源不是玩家时抛出，携带原版本地化提示「需要玩家身份才能执行」
     */
    private static int execute(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();

        // 来源不是玩家时在此抛出原版本地化错误（permissions.requires.player）
        ServerPlayer player = source.getPlayerOrException();

        // 上次死亡地点由原版记录在玩家数据中，类型为 Optional<GlobalPos>；
        // 玩家从未死亡时为空
        Optional<GlobalPos> lastDeath = player.getLastDeathLocation();
        if (lastDeath.isEmpty()) {
            // 无死亡记录：不传送，并明确告知原因（静默失败会让玩家误以为指令损坏）
            Messages.sendFailure(source, KEY_NO_DEATH);
            return 0;
        }

        GlobalPos deathPos = lastDeath.get();

        // 将死亡地点记录的维度键解析为实际的 ServerLevel；
        // 若该维度已不存在（如被移除的自定义维度），getLevel 返回 null，则无法传送
        ServerLevel targetLevel = source.getServer().getLevel(deathPos.dimension());
        if (targetLevel == null) {
            Messages.sendFailure(source, KEY_DIMENSION_MISSING);
            return 0;
        }

        // GlobalPos.pos() 为死亡时所在的方块坐标（整数）
        BlockPos pos = deathPos.pos();

        // 传送至死亡方块的水平中心（x+0.5 / z+0.5），避免卡在方块边角；
        // Set.of() 表示所有坐标均为绝对值；保持玩家当前朝向；最后的 boolean=true 重置镜头；
        // 因 targetLevel 来自死亡维度，故天然支持跨维度回溯
        player.teleportTo(
                targetLevel,
                pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5,
                Set.of(),
                player.getYRot(), player.getXRot(),
                true
        );

        // 原版传送（含 /tp）不会清空已累积的下落距离，玩家若在坠落途中执行本指令，
        // 会带着旧的下落距离落地并照常摔伤甚至摔死。这里显式清零，
        // 使传送落点始终从零开始计算坠落伤害。
        player.resetFallDistance();

        Messages.sendSuccess(source, KEY_SUCCESS);

        return 1;
    }
}
