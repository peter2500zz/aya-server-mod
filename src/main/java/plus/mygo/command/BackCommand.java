package plus.mygo.command;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.Optional;
import java.util.Set;

/**
 * /back 指令。
 * <p>
 * 仅限玩家执行，无参数。将执行者传送回其上一次死亡的地点（含维度，支持跨维度）。
 * 死亡地点由原版自动记录在玩家数据中（last death location，亦用于恢复指南针指向）：
 * 若玩家从未死亡、或死亡所在维度已不存在，则命令无效——不传送、不输出任何文本。
 */
public class BackCommand {

    /**
     * 向 Fabric 命令系统注册 /back 指令。
     * 应在 {@link plus.mygo.AyaServerMod#onInitialize()} 中调用一次，且仅调用一次。
     */
    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
            dispatcher.register(Commands.literal("back")
                // 在 Brigadier 层面限制为玩家：控制台及非玩家实体不可见此指令
                .requires(CommandSourceStack::isPlayer)
                .executes(BackCommand::execute)
            )
        );
    }

    /**
     * 指令执行逻辑：将执行者传送回上一次死亡地点。
     *
     * @param context Brigadier 提供的指令上下文
     * @return 1 表示传送成功；0 表示无可用的死亡地点（命令无效）
     * @throws CommandSyntaxException 若来源不是玩家（理论上不会发生，requires 已保证）
     */
    private static int execute(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();

        // requires() 已保证来源为玩家，此处不会抛出异常
        ServerPlayer player = source.getPlayerOrException();

        // 上次死亡地点由原版记录在玩家数据中，类型为 Optional<GlobalPos>；
        // 玩家从未死亡时为空
        Optional<GlobalPos> lastDeath = player.getLastDeathLocation();
        if (lastDeath.isEmpty()) {
            // 无死亡记录：命令无效，不传送、不输出文本
            return 0;
        }

        GlobalPos deathPos = lastDeath.get();

        // 将死亡地点记录的维度键解析为实际的 ServerLevel；
        // 若该维度已不存在（如被移除的自定义维度），getLevel 返回 null，则无法传送
        ServerLevel targetLevel = source.getServer().getLevel(deathPos.dimension());
        if (targetLevel == null) {
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

        // 复用原版 /tp 传送至坐标的成功消息键 "commands.teleport.success.location.single"
        //（"Teleported %s to %s, %s, %s"），打印死亡点的方块坐标。
        // 该键为原版自带，客户端无需安装本 mod 即可正确渲染
        source.sendSuccess(
                () -> Component.translatable(
                        "commands.teleport.success.location.single",
                        player.getDisplayName(),
                        String.valueOf(pos.getX()),
                        String.valueOf(pos.getY()),
                        String.valueOf(pos.getZ())
                ),
                false
        );

        return 1;
    }
}
