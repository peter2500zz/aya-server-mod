package plus.mygo.command;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.server.level.ServerPlayer;
import plus.mygo.i18n.Messages;
import plus.mygo.tpa.TpaRequests;

/**
 * /accept 指令。
 * <p>
 * 仅限玩家执行。接受指定玩家发给自己的传送请求，接受后该玩家立即被传送到自己身边。
 * <p>
 * 请求之间互不排斥（见 {@link TpaRequests}），同一时刻可能有多人在等自己回应，
 * 因此必须指明接受的是谁。玩家名的补全由 {@link TpaRequests#suggestIncoming} 提供，
 * <b>只列出此刻正在等待自己回应的发起者</b>，而非全体在线玩家。
 * <p>
 * 也可以点击请求提示中的【接受】按钮，该按钮会把已填好玩家名的本指令填入聊天栏，
 * 由玩家按回车确认。
 */
public class AcceptCommand {

    /** 指令参数名，用于从 CommandContext 中按名称读取参数值。 */
    private static final String ARG_PLAYER = "player";

    /** 指定玩家并未向自己发送过请求时的提示文本翻译键。 */
    private static final String KEY_NO_PENDING = "aya-server-mod.command.tpa.no_pending_incoming";

    /**
     * 向 Fabric 命令系统注册 /accept 指令。
     * 应在 {@link plus.mygo.AyaServerMod#onInitialize()} 中调用一次，且仅调用一次。
     */
    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
            dispatcher.register(Commands.literal("accept")
                // 声明任何来源都可执行，非玩家由 execute 中的 getPlayerOrException() 拦下。
                // 不可改用 requires(isPlayer)：那会让本指令被标记为受限指令，
                // 玩家点击聊天里的按钮时会弹出提权确认框（详见 CLAUDE.md「命令编写规范」）。
                .requires(Commands.hasPermission(Commands.LEVEL_ALL))
                .then(Commands.argument(ARG_PLAYER, EntityArgument.player())
                    // 覆盖 EntityArgument 自带的「全体在线玩家」补全，改为只列出等待自己回应的人。
                    // 自定义 SuggestionProvider 未在 SuggestionProviders 中注册，
                    // 因而在命令树数据包里被标记为 ASK_SERVER，客户端会回头向服务端索取补全。
                    .suggests(TpaRequests::suggestIncoming)
                    .executes(AcceptCommand::execute)
                )
            )
        );
    }

    /**
     * 指令执行逻辑：接受指定玩家发来的传送请求。
     *
     * @param context Brigadier 提供的指令上下文
     * @return 1 表示已接受并完成传送；0 表示该玩家并未向自己发送过待处理请求
     * @throws CommandSyntaxException 来源不是玩家，或参数玩家不在线时抛出，
     *                                后者由 {@link EntityArgument} 给出原版本地化错误
     */
    private static int execute(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();

        // 来源不是玩家时在此抛出原版本地化错误（permissions.requires.player）
        ServerPlayer target = source.getPlayerOrException();

        // 补全虽然只列出有效对象，但玩家仍可手输任意在线玩家名，
        // 因此下面必须再判一次这条请求是否真的存在
        ServerPlayer requester = EntityArgument.getPlayer(context, ARG_PLAYER);

        // 接受成功时的双方通知由 TpaRequests 发出，此处只需处理「无此请求」的情况
        if (TpaRequests.accept(target, requester) == 0) {
            Messages.sendFailure(source, KEY_NO_PENDING, requester.getDisplayName());
            return 0;
        }
        return 1;
    }
}
