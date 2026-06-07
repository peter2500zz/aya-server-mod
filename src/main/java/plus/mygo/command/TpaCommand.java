package plus.mygo.command;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Relative;

import java.util.Set;

/**
 * /tpa 指令。
 * <p>
 * 仅限玩家执行。将执行指令的玩家立即传送至目标在线玩家所在位置，支持跨维度传送。
 * 参数使用 {@link EntityArgument#player()}，提供与原版 /tell 完全一致的玩家补全体验
 * （在线玩家名 + @s/@p 等选择器，但因 player() 限制，选择器最终必须解析为恰好一名玩家）。
 * 传送成功后仅通知执行者与目标玩家（收到相同消息），不广播至全服。
 * 通知文本复用原版翻译键，因此客户端无需安装本 mod 即可正确显示。
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

        // 构造传送通知：复用原版 /tp 的翻译键 "commands.teleport.success.entity.single"
        //（"Teleported %s to %s"）。该键为原版自带，客户端无需安装本 mod 即可正确渲染，
        // 并自动跟随客户端语言设置；执行者与目标玩家收到完全相同的消息。
        Component message = Component.translatable(
                "commands.teleport.success.entity.single",
                executor.getDisplayName(),
                target.getDisplayName()
        );

        // 通知执行者：sendSuccess 第二个参数 false 表示不向其他管理员广播此反馈
        source.sendSuccess(() -> message, false);

        // 通知目标玩家；若目标与执行者为同一玩家（传送到自身），跳过以避免重复提示
        if (!target.equals(executor)) {
            target.sendSystemMessage(message);
        }

        return 1;
    }
}
