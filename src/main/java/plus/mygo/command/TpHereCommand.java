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
 * /tphere 指令。
 * <p>
 * 仅限玩家执行。向目标在线玩家<b>发起一条传送请求</b>，请对方传送到自己身边：目标可在
 * {@link TpaRequests#TIMEOUT_SECONDS} 秒内用 {@code /accept} 接受或 {@code /reject} 拒绝，
 * 逾期请求失效；发起者可用 {@code /cancel} 撤销。<b>接受后被传送的是目标玩家</b>。
 * <p>
 * 与 {@link TpaCommand} 是同一套请求流程的两个方向，<b>共用同一条请求记录</b>：
 * 请求的身份始终是「谁向谁发起」，方向只决定接受后由谁移动。因此对同一名玩家
 * 只能同时存在其中一条待处理请求，想改方向须先 {@code /cancel} 撤销。
 * <p>
 * 参数使用 {@link EntityArgument#player()}，补全为全体在线玩家，与 {@code /tpa} 一致
 * （回应类指令才需要按当前请求状态过滤补全）。
 * <p>
 * 请求的状态管理、计时与全部相关消息由 {@link TpaRequests} 负责，本类只做参数解析与转发。
 */
public class TpHereCommand {

    /** 指令参数名，用于从 CommandContext 中按名称读取参数值。 */
    private static final String ARG_PLAYER = "player";

    /**
     * 向 Fabric 命令系统注册 /tphere 指令。
     * 应在 {@link plus.mygo.AyaServerMod#onInitialize()} 中调用一次，且仅调用一次。
     */
    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
            dispatcher.register(Commands.literal("tphere")
                // 声明任何来源都可执行，非玩家由 execute 中的 getPlayerOrException() 拦下。
                // 不可改用 requires(isPlayer)：那会让本指令被标记为受限指令，
                // 玩家点击聊天里的按钮时会弹出提权确认框（详见 CLAUDE.md「命令编写规范」）。
                .requires(Commands.hasPermission(Commands.LEVEL_ALL))
                .then(Commands.argument(ARG_PLAYER, EntityArgument.player())
                    // EntityArgument.player() 内置与原版 /tell 完全相同的补全逻辑：
                    //   - Tab 补全自动列出在线玩家名，并支持 @s/@p 等选择器
                    //   - 目标玩家不在线时，由原版错误系统返回已本地化的提示
                    .executes(TpHereCommand::execute)
                )
            )
        );
    }

    /**
     * 指令执行逻辑：请求目标玩家传送到自己身边。
     *
     * @param context Brigadier 提供的指令上下文
     * @return 1 表示请求已发出；0 表示被自我请求或重复请求拒绝（原因由 TpaRequests 告知发起者）
     * @throws CommandSyntaxException 来源不是玩家，或目标玩家不在线、选择器匹配到多名玩家时抛出，
     *                                后两者由 {@link EntityArgument} 给出原版本地化错误
     */
    private static int execute(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        // 来源不是玩家时在此抛出原版本地化错误（permissions.requires.player）
        ServerPlayer requester = context.getSource().getPlayerOrException();

        ServerPlayer target = EntityArgument.getPlayer(context, ARG_PLAYER);

        // TARGET_TO_REQUESTER：接受后是目标被传送过来，这是与 /tpa 唯一的差别
        return TpaRequests.create(requester, target, TpaRequests.Movement.TARGET_TO_REQUESTER);
    }
}
