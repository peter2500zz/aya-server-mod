package plus.mygo.command;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;

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
 * 不传送，并发送原版"重生点不可用"提示；否则传送并输出原版传送成功消息。
 * 查找时传入 {@code useCharge=false}，因此不消耗重生锚充能，{@code /home} 可反复使用。
 */
public class HomeCommand {

    /**
     * 向 Fabric 命令系统注册 /home 指令。
     * 应在 {@link plus.mygo.AyaServerMod#onInitialize()} 中调用一次，且仅调用一次。
     */
    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
            dispatcher.register(Commands.literal("home")
                // 在 Brigadier 层面限制为玩家：控制台及非玩家实体不可见此指令
                .requires(CommandSourceStack::isPlayer)
                .executes(HomeCommand::execute)
            )
        );
    }

    /**
     * 指令执行逻辑：将执行者传送回其重生点。
     *
     * @param context Brigadier 提供的指令上下文
     * @return 1 表示传送成功；0 表示无可用重生点（命令无效）
     * @throws CommandSyntaxException 若来源不是玩家（理论上不会发生，requires 已保证）
     */
    private static int execute(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();

        // requires() 已保证来源为玩家，此处不会抛出异常
        ServerPlayer player = source.getPlayerOrException();

        // 复用原版复活坐标查找逻辑：
        //   useCharge=false → 仅查找坐标、不消耗重生锚充能，使 /home 可反复使用；
        //   DO_NOTHING      → 传送后不附加额外行为（不播放传送门音效等）。
        // 返回的 TeleportTransition 已携带解析后的目标维度、坐标与朝向。
        TeleportTransition transition =
                player.findRespawnPositionAndUseSpawnBlock(false, TeleportTransition.DO_NOTHING);

        // 判定重生点是否可用：
        //   getRespawnConfig() == null       → 玩家从未设置过重生点（此时上面会回退到世界出生点）；
        //   transition.missingRespawnBlock() → 床 / 重生锚已被破坏、被遮挡，或重生锚无充能。
        // 两种情况均视为无效：不传送，发送原版"重生点不可用"提示（该键涵盖"无重生点 / 被阻挡"两义）。
        if (player.getRespawnConfig() == null || transition.missingRespawnBlock()) {
            source.sendFailure(Component.translatable("block.minecraft.spawn.not_valid"));
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

        // 复用原版 /tp 传送至坐标的成功消息键 "commands.teleport.success.location.single"
        //（"Teleported %s to %s, %s, %s"）。坐标用 Locale.ROOT 固定小数点格式、保留两位小数，
        // 避免在使用逗号作小数点的客户端语言下显示异常
        source.sendSuccess(
                () -> Component.translatable(
                        "commands.teleport.success.location.single",
                        player.getDisplayName(),
                        String.format(Locale.ROOT, "%.2f", pos.x),
                        String.format(Locale.ROOT, "%.2f", pos.y),
                        String.format(Locale.ROOT, "%.2f", pos.z)
                ),
                false
        );

        return 1;
    }
}
