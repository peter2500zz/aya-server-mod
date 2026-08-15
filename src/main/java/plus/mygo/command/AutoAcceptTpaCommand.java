package plus.mygo.command;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import plus.mygo.tpa.AutoAcceptTpa;

/**
 * /auto-accept-tpa 指令。
 * <p>
 * 仅限玩家执行。开关「他人请求传送到我身边时无需再问我」这一偏好，默认关闭。
 * 带参数时设置开关（{@code /auto-accept-tpa <true|false>}），不带参数时回报当前状态。
 * <p>
 * 开关只覆盖 {@code /tpa} 一个方向，且只存在于内存中、重启即恢复关闭 ——
 * 语义与状态均由 {@link AutoAcceptTpa} 承载，本类只负责解析参数并转交。
 * <p>
 * 也可以点击 {@code /tpa} 请求提示中的【自动接受】按钮，或自动成交提示中的
 * 【关闭自动接受】按钮，它们会把已填好参数的本指令填入聊天栏，由玩家按回车确认。
 */
public class AutoAcceptTpaCommand {

    /** 指令参数名，用于从 CommandContext 中按名称读取参数值。 */
    private static final String ARG_ENABLED = "enabled";

    /**
     * 向 Fabric 命令系统注册 /auto-accept-tpa 指令。
     * 应在 {@link plus.mygo.AyaServerMod#onInitialize()} 中调用一次，且仅调用一次。
     */
    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
            // 指令名中的连字符合法：Brigadier 的字面量节点是整串 equals 匹配，
            // 且 StringReader.isAllowedInUnquotedString 本就允许 '-'
            dispatcher.register(Commands.literal("auto-accept-tpa")
                // 声明任何来源都可执行，非玩家由 execute 中的 getPlayerOrException() 拦下。
                // 不可改用 requires(isPlayer)：那会让本指令被标记为受限指令，
                // 玩家点击聊天里的按钮时会弹出提权确认框（详见 CLAUDE.md「命令编写规范」）。
                .requires(Commands.hasPermission(Commands.LEVEL_ALL))
                // 不带参数：查询当前状态
                .executes(AutoAcceptTpaCommand::report)
                .then(Commands.argument(ARG_ENABLED, BoolArgumentType.bool())
                    // BoolArgumentType 自带 true / false 两项补全，无需另行提供
                    .executes(AutoAcceptTpaCommand::set)
                )
            )
        );
    }

    /**
     * 指令执行逻辑：设置自动接受开关。
     * <p>
     * 回执与「开启时结清积压请求」都在 {@link AutoAcceptTpa#set} 中完成。
     *
     * @param context Brigadier 提供的指令上下文
     * @return 1 表示执行成功
     * @throws CommandSyntaxException 来源不是玩家时抛出
     */
    private static int set(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        // 来源不是玩家时在此抛出原版本地化错误（permissions.requires.player）
        ServerPlayer player = context.getSource().getPlayerOrException();

        return AutoAcceptTpa.set(player, BoolArgumentType.getBool(context, ARG_ENABLED));
    }

    /**
     * 指令执行逻辑：回报当前的自动接受状态。
     *
     * @param context Brigadier 提供的指令上下文
     * @return 1 表示执行成功
     * @throws CommandSyntaxException 来源不是玩家时抛出
     */
    private static int report(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();

        return AutoAcceptTpa.report(player);
    }
}
