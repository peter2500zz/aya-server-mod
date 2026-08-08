package plus.mygo.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import plus.mygo.i18n.Messages;
import plus.mygo.tpa.TpaRequests;

/**
 * /deny 指令。
 * <p>
 * 仅限玩家执行。拒绝当前发给自己的传送请求，可选附带一句原因转达给发起者。
 * 由于每个玩家同时至多只有一条待处理请求（见 {@link TpaRequests}），本指令无需指定对象。
 * <p>
 * 用法：{@code /deny} 或 {@code /deny <原因>}。
 * 也可以点击请求提示中的【拒绝】按钮，该按钮会把不带原因的 {@code /deny} 填入聊天栏，由玩家按回车确认。
 */
public class DenyCommand {

    /** 可选原因的参数名，用于从 CommandContext 中按名称读取参数值。 */
    private static final String ARG_REASON = "reason";

    /** 没有待处理请求时的提示文本翻译键。 */
    private static final String KEY_NO_PENDING = "aya-server-mod.command.tpa.no_pending_incoming";

    /**
     * 向 Fabric 命令系统注册 /deny 指令。
     * 应在 {@link plus.mygo.AyaServerMod#onInitialize()} 中调用一次，且仅调用一次。
     */
    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
            dispatcher.register(Commands.literal("deny")
                // 声明任何来源都可执行，非玩家由 execute 中的 getPlayerOrException() 拦下。
                // 不可改用 requires(isPlayer)：那会让本指令被标记为受限指令，
                // 玩家点击聊天里的按钮时会弹出提权确认框（详见 CLAUDE.md「命令编写规范」）。
                .requires(Commands.hasPermission(Commands.LEVEL_ALL))
                // 不带参数：拒绝但不说明原因
                .executes(context -> execute(context, null))
                // greedyString 吞掉其后全部文本，因此原因可以包含空格，且无需引号
                .then(Commands.argument(ARG_REASON, StringArgumentType.greedyString())
                    .executes(context -> execute(context, StringArgumentType.getString(context, ARG_REASON)))
                )
            )
        );
    }

    /**
     * 指令执行逻辑：拒绝当前待处理的传送请求。
     *
     * @param context Brigadier 提供的指令上下文
     * @param reason  玩家给出的拒绝原因；{@code null} 表示未填写
     * @return 1 表示已拒绝；0 表示没有待处理请求
     * @throws CommandSyntaxException 来源不是玩家时抛出，携带原版本地化提示「需要玩家身份才能执行」
     */
    private static int execute(CommandContext<CommandSourceStack> context, String reason)
            throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();

        // 来源不是玩家时在此抛出原版本地化错误（permissions.requires.player）
        ServerPlayer player = source.getPlayerOrException();

        // 拒绝成功时的双方通知由 TpaRequests 发出，此处只需处理「无请求可拒绝」的情况
        if (TpaRequests.deny(player, reason) == 0) {
            Messages.sendFailure(source, KEY_NO_PENDING);
            return 0;
        }
        return 1;
    }
}
