package plus.mygo.command;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.server.level.ServerPlayer;
import plus.mygo.tpa.TpaRequests;

/**
 * /tpa 指令。
 * <p>
 * 仅限玩家执行。向目标在线玩家<b>发起一条传送请求</b>，而非立即传送：目标可在
 * {@link TpaRequests#TIMEOUT_SECONDS} 秒内用 {@code /confirm} 接受或 {@code /deny} 拒绝，
 * 未回应则自动接受；发起者可用 {@code /cancel} 撤销。
 * <p>
 * 参数使用 {@link EntityArgument#player()}，提供与原版 /tell 完全一致的玩家补全体验
 * （在线玩家名 + @s/@p 等选择器，但因 player() 限制，选择器最终必须解析为恰好一名玩家）。
 * <p>
 * 请求的状态管理、计时与全部相关消息由 {@link TpaRequests} 负责，本类只做参数解析与转发。
 */
public class TpaCommand {

    /** 指令参数名，用于从 CommandContext 中按名称读取参数值。 */
    private static final String ARG_PLAYER = "player";

    /**
     * 向 Fabric 命令系统注册 /tpa 指令。
     * 应在 {@link plus.mygo.AyaServerMod#onInitialize()} 中调用一次，且仅调用一次。
     */
    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
            dispatcher.register(Commands.literal("tpa")
                // 在 Brigadier 层面限制为玩家：控制台及非玩家实体不可见此指令
                .requires(CommandSourceStack::isPlayer)
                .then(Commands.argument(ARG_PLAYER, EntityArgument.player())
                    // EntityArgument.player() 内置与原版 /tell 完全相同的补全逻辑：
                    //   - Tab 补全自动列出在线玩家名，并支持 @s/@p 等选择器
                    //   - 目标玩家不在线时，由原版错误系统（EntityArgument.NO_PLAYERS_FOUND）
                    //     返回已本地化的提示，无需自定义错误消息
                    .executes(TpaCommand::execute)
                )
            )
        );
    }

    /**
     * 指令执行逻辑：向目标玩家发起传送请求。
     *
     * @param context Brigadier 提供的指令上下文
     * @return 1 表示请求已发出；0 表示被并发约束或自我请求拒绝（原因由 TpaRequests 告知发起者）
     * @throws CommandSyntaxException 目标玩家不在线或选择器匹配到多名玩家时，
     *                                由 {@link EntityArgument} 内部抛出（含原版本地化错误提示）
     */
    private static int execute(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        // requires() 已保证来源为玩家，此处不会抛出异常
        ServerPlayer requester = context.getSource().getPlayerOrException();

        // EntityArgument.getPlayer() 在目标不在线时抛出 CommandSyntaxException，
        // 原版已提供本地化错误消息，无需自行处理 null 情况
        ServerPlayer target = EntityArgument.getPlayer(context, ARG_PLAYER);

        return TpaRequests.create(requester, target);
    }
}
