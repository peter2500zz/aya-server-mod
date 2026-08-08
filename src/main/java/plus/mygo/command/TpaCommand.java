package plus.mygo.command;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import plus.mygo.i18n.Messages;

import java.util.Set;

/**
 * /tpa 指令。
 * <p>
 * 仅限玩家执行。将执行指令的玩家立即传送至目标在线玩家所在位置，支持跨维度传送。
 * 参数使用 {@link EntityArgument#player()}，提供与原版 /tell 完全一致的玩家补全体验
 * （在线玩家名 + @s/@p 等选择器，但因 player() 限制，选择器最终必须解析为恰好一名玩家）。
 * 传送成功后仅通知执行者与目标玩家，双方各收到一条视角不同的消息，不广播至全服。
 * 通知经 {@link Messages} 发送，各自按其客户端语言渲染，客户端无需安装本 mod。
 */
public class TpaCommand {

    /** 指令参数名，用于从 CommandContext 中按名称读取参数值。 */
    private static final String ARG_PLAYER = "player";

    /** 传送成功后给执行者的提示文本翻译键，参数为目标玩家显示名。 */
    private static final String KEY_SUCCESS_SELF = "aya-server-mod.command.tpa.success.self";

    /** 传送成功后给目标玩家的提示文本翻译键，参数为执行者显示名。 */
    private static final String KEY_SUCCESS_TARGET = "aya-server-mod.command.tpa.success.target";

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
     * 指令执行逻辑：将执行者传送至目标玩家当前位置，并分别通知执行者与目标玩家。
     *
     * @param context Brigadier 提供的指令上下文
     * @return 1 表示传送成功
     * @throws CommandSyntaxException 目标玩家不在线或选择器匹配到多名玩家时，
     *                                由 {@link EntityArgument} 内部抛出（含原版本地化错误提示）
     */
    private static int execute(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();

        // requires() 已保证来源为玩家，此处不会抛出异常
        ServerPlayer executor = source.getPlayerOrException();

        // EntityArgument.getPlayer() 在目标不在线时抛出 CommandSyntaxException，
        // 原版已提供本地化错误消息，无需自行处理 null 情况
        ServerPlayer target = EntityArgument.getPlayer(context, ARG_PLAYER);

        // ServerPlayer.level() 协变返回 ServerLevel，无需额外转型
        ServerLevel targetLevel = target.level();

        // Set.of() 表示所有坐标均为绝对值（非相对偏移）；
        // 保持执行者当前朝向（yaw / pitch）不变，仅移动坐标；
        // 最后一个 boolean 参数：true 表示传送完成后重置镜头（应对旁观模式下视角未归位的情况）
        executor.teleportTo(
                targetLevel,
                target.getX(), target.getY(), target.getZ(),
                Set.of(),
                executor.getYRot(), executor.getXRot(),
                true
        );

        // 通知执行者：「已传送到 <目标> 身边」。
        // 传玩家显示名（Component）而非字符串，以保留队伍颜色与悬停信息
        Messages.sendSuccess(source, KEY_SUCCESS_SELF, target.getDisplayName());

        // 通知目标玩家：「<执行者> 传送到了你身边」。
        // 该消息按目标玩家自己的客户端语言渲染，与执行者的语言无关。
        // 若目标与执行者为同一玩家（传送到自身），跳过以避免自己收到"某人传送到了你身边"
        if (!target.equals(executor)) {
            Messages.send(target, KEY_SUCCESS_TARGET, executor.getDisplayName());
        }

        return 1;
    }
}
