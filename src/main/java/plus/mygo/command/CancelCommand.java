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
 * /cancel 指令。
 * <p>
 * 仅限玩家执行。撤销自己此前用 {@code /tpa} 发给指定玩家、尚未被回应的传送请求，
 * 并通知对方请求已被撤销。
 * <p>
 * 请求之间互不排斥（见 {@link TpaRequests}），自己同一时刻可能有多条在途请求，
 * 因此必须指明撤销的是发给谁的那一条。玩家名的补全由 {@link TpaRequests#suggestOutgoing} 提供，
 * <b>只列出自己已发出且仍待回应的目标玩家</b>，而非全体在线玩家。
 * <p>
 * 也可以点击发起请求时回执里的【撤销】按钮，该按钮会把已填好玩家名的本指令填入聊天栏，
 * 由玩家按回车确认。
 */
public class CancelCommand {

    /** 指令参数名，用于从 CommandContext 中按名称读取参数值。 */
    private static final String ARG_PLAYER = "player";

    /** 自己并未向指定玩家发送过请求时的提示文本翻译键。 */
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
                .then(Commands.argument(ARG_PLAYER, EntityArgument.player())
                    // 覆盖 EntityArgument 自带的「全体在线玩家」补全，改为只列出自己已发出请求的目标。
                    // 自定义 SuggestionProvider 未在 SuggestionProviders 中注册，
                    // 因而在命令树数据包里被标记为 ASK_SERVER，客户端会回头向服务端索取补全。
                    .suggests(TpaRequests::suggestOutgoing)
                    .executes(CancelCommand::execute)
                )
            )
        );
    }

    /**
     * 指令执行逻辑：撤销自己发给指定玩家的传送请求。
     *
     * @param context Brigadier 提供的指令上下文
     * @return 1 表示已撤销；0 表示自己并未向该玩家发送过待处理请求
     * @throws CommandSyntaxException 来源不是玩家，或参数玩家不在线时抛出，
     *                                后者由 {@link EntityArgument} 给出原版本地化错误
     */
    private static int execute(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();

        // 来源不是玩家时在此抛出原版本地化错误（permissions.requires.player）
        ServerPlayer requester = source.getPlayerOrException();

        // 补全虽然只列出有效对象，但玩家仍可手输任意在线玩家名，
        // 因此下面必须再判一次这条请求是否真的存在
        ServerPlayer target = EntityArgument.getPlayer(context, ARG_PLAYER);

        // 撤销成功时的双方通知由 TpaRequests 发出，此处只需处理「无此请求」的情况
        if (TpaRequests.cancel(requester, target) == 0) {
            Messages.sendFailure(source, KEY_NO_PENDING, target.getDisplayName());
            return 0;
        }
        return 1;
    }
}
