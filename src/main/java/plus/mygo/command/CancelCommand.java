package plus.mygo.command;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import plus.mygo.i18n.Messages;
import plus.mygo.tpa.TpaRequests;

/**
 * /cancel 指令。
 * <p>
 * 仅限玩家执行，无参数。撤销自己此前用 {@code /tpa} 发出、尚未被回应的传送请求，
 * 并通知对方请求已被撤销。
 * 由于每个玩家同时至多只有一条在途请求（见 {@link TpaRequests}），本指令无需指定对象。
 * <p>
 * 也可以点击发起请求时回执里的【撤销】按钮，该按钮会把本指令填入聊天栏，由玩家按回车确认。
 */
public class CancelCommand {

    /** 没有在途请求可撤销时的提示文本翻译键。 */
    private static final String KEY_NO_PENDING = "aya-server-mod.command.tpa.no_pending_outgoing";

    /**
     * 向 Fabric 命令系统注册 /cancel 指令。
     * 应在 {@link plus.mygo.AyaServerMod#onInitialize()} 中调用一次，且仅调用一次。
     */
    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
            dispatcher.register(Commands.literal("cancel")
                // 声明任何来源都可执行，非玩家由 execute 中的 getPlayerOrException() 拦下。
                // 不可改用 requires(isPlayer)：那会让本指令被标记为受限指令，
                // 玩家点击聊天里的按钮时会弹出提权确认框（详见 CLAUDE.md「命令编写规范」）。
                .requires(Commands.hasPermission(Commands.LEVEL_ALL))
                .executes(CancelCommand::execute)
            )
        );
    }

    /**
     * 指令执行逻辑：撤销自己发出的传送请求。
     *
     * @param context Brigadier 提供的指令上下文
     * @return 1 表示已撤销；0 表示没有在途请求
     * @throws CommandSyntaxException 来源不是玩家时抛出，携带原版本地化提示「需要玩家身份才能执行」
     */
    private static int execute(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();

        // 来源不是玩家时在此抛出原版本地化错误（permissions.requires.player）
        ServerPlayer player = source.getPlayerOrException();

        // 撤销成功时的双方通知由 TpaRequests 发出，此处只需处理「无请求可撤销」的情况
        if (TpaRequests.cancel(player) == 0) {
            Messages.sendFailure(source, KEY_NO_PENDING);
            return 0;
        }
        return 1;
    }
}
