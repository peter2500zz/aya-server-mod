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
                // 声明任何来源都可执行，非玩家由 execute 中的 getPlayerOrException() 拦下。
                // 不可改用 requires(isPlayer)：那会让本指令被标记为受限指令，
                // 玩家点击聊天里的按钮时会弹出提权确认框（详见 CLAUDE.md「命令编写规范」）。
                .requires(Commands.hasPermission(Commands.LEVEL_ALL))
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
     * @throws CommandSyntaxException 来源不是玩家时抛出，携带原版本地化提示「需要玩家身份才能执行」
     */
    private static int execute(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        // 来源不是玩家时在此抛出原版本地化错误（permissions.requires.player）
        ServerPlayer executor = context.getSource().getPlayerOrException();

        // Entity.kill(ServerLevel) 是原版 /kill 命令所用的同一方法，
        // ServerPlayer.level() 协变返回 ServerLevel，无需额外转型
        executor.kill(executor.level());

        return 1;
    }
}
