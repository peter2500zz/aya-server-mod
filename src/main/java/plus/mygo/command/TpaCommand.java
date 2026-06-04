package plus.mygo.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Relative;

import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * /tpa 指令。
 * <p>
 * 仅限玩家执行。将执行指令的玩家立即传送至目标在线玩家所在位置，支持跨维度传送。
 * 参数不接受实体选择器（{@code @a}、{@code @p} 等），仅接受精确玩家名；
 * Tab 补全自动提供当前在线玩家列表（已排除执行者自身）。
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
                .then(Commands.argument(ARG_PLAYER, StringArgumentType.word())
                    // 提供在线玩家名补全列表，不包含选择器语法
                    .suggests(TpaCommand::suggestPlayers)
                    .executes(TpaCommand::execute)
                )
            )
        );
    }

    /**
     * Tab 补全逻辑：返回当前在线玩家名列表（排除执行者自身）。
     * <p>
     * 使用 {@link StringArgumentType} 而非 {@code EntityArgument} 是为了禁止选择器语法；
     * 通过此方法手动提供补全以维持与原版 /tp 相近的使用体验。
     *
     * @param context Brigadier 指令上下文，用于获取服务器玩家列表和执行者信息
     * @param builder Brigadier 补全构建器，负责过滤并返回匹配的补全项
     * @return 异步补全结果
     */
    private static CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestPlayers(
            CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        // getPlayer() 返回可空值（非抛出版本），安全获取执行者名称以便从列表中排除自身
        ServerPlayer executor = context.getSource().getPlayer();
        String executorName = executor != null ? executor.getGameProfile().name() : "";

        // 收集在线玩家名，过滤掉执行者自身，不向执行者提供"传送到自己"的选项
        Iterable<String> names = context.getSource().getServer()
                .getPlayerList().getPlayers().stream()
                .map(p -> p.getGameProfile().name())
                .filter(name -> !name.equals(executorName))
                .toList();

        return SharedSuggestionProvider.suggest(names, builder);
    }

    /**
     * 指令执行逻辑：将执行者传送至目标玩家当前位置。
     *
     * @param context Brigadier 提供的指令上下文
     * @return 1 表示传送成功；0 表示目标玩家不在线
     * @throws CommandSyntaxException 若来源不是玩家（理论上不会发生，requires 已保证）
     */
    private static int execute(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();

        // requires() 已保证来源为玩家，此处不会抛出异常
        ServerPlayer executor = source.getPlayerOrException();

        // 从参数读取纯字符串玩家名，不含选择器前缀
        String targetName = StringArgumentType.getString(context, ARG_PLAYER);
        ServerPlayer target = source.getServer().getPlayerList().getPlayerByName(targetName);

        if (target == null) {
            // 目标玩家不在线或名称有误，向执行者发送失败提示
            source.sendFailure(Component.literal("玩家 " + targetName + " 不在线或不存在。"));
            return 0;
        }

        // ServerPlayer.level() 返回值协变为 ServerLevel，无需额外转型
        ServerLevel targetLevel = target.level();

        // Set.of() 表示所有坐标均为绝对值（非相对偏移）；
        // 保持执行者当前朝向（yaw / pitch）不变，仅移动坐标；
        // 最后一个 boolean 参数：true 表示在传送完成后重置镜头（若执行者正在旁观他人）
        executor.teleportTo(
                targetLevel,
                target.getX(), target.getY(), target.getZ(),
                Set.of(),
                executor.getYRot(), executor.getXRot(),
                true
        );
        return 1;
    }
}
