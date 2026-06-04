package plus.mygo.command;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;

/**
 * /die 指令。
 * <p>
 * 仅限玩家执行，无参数。效果等同于 {@code kill @s}：立即杀死执行者。
 * 死亡本身即为执行反馈（死亡界面），无需额外发送消息。
 */
public class DieCommand {

    /**
     * 向 Fabric 命令系统注册 /die 指令。
     * 应在 {@link plus.mygo.AyaServerMod#onInitialize()} 中调用一次，且仅调用一次。
     */
    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
            dispatcher.register(Commands.literal("die")
                // 在 Brigadier 层面限制为玩家：控制台及非玩家实体不可见此指令
                .requires(CommandSourceStack::isPlayer)
                .executes(DieCommand::execute)
            )
        );
    }

    /**
     * 指令执行逻辑：杀死执行者。
     * <p>
     * 复用原版 {@code Entity.kill(ServerLevel)} 实现，行为与 {@code kill @s} 完全一致：
     * 触发死亡事件、生成死亡消息、掉落物品等原版死亡流程均正常执行。
     *
     * @param context Brigadier 提供的指令上下文
     * @return 1 表示执行成功
     * @throws CommandSyntaxException 若来源不是玩家（理论上不会发生，requires 已保证）
     */
    private static int execute(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        // requires() 已保证来源为玩家，此处不会抛出异常
        ServerPlayer executor = context.getSource().getPlayerOrException();

        // Entity.kill(ServerLevel) 是原版 /kill 命令所用的同一方法，
        // ServerPlayer.level() 协变返回 ServerLevel，无需额外转型
        executor.kill(executor.level());

        return 1;
    }
}
