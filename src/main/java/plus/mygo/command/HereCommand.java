package plus.mygo.command;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import plus.mygo.i18n.Messages;

/**
 * /here 指令。
 * <p>
 * 执行后对执行者自身施加 30 秒发光效果（{@link MobEffects#GLOWING}），
 * 使其对周围玩家可见，包括透过方块。
 * 仅限玩家执行：非玩家来源执行时会收到原版的「需要玩家身份」提示。
 * <p>
 * 玩家在第一人称下看不到自己的发光轮廓，故额外发送一条确认消息告知效果已生效。
 */
public class HereCommand {

    /** 发光效果持续时长（秒），仅用于换算游戏刻，不出现在提示文本中。 */
    private static final int GLOW_DURATION_SECONDS = 30;

    /** 发光效果持续时长，换算为游戏刻（1 秒 = 20 刻）。 */
    private static final int GLOW_DURATION_TICKS = GLOW_DURATION_SECONDS * 20;

    /** 施加发光效果成功后的提示文本翻译键。 */
    private static final String KEY_SUCCESS = "aya-server-mod.command.here.success";

    /**
     * 向 Fabric 命令系统注册 /here 指令。
     * 应在 {@link plus.mygo.AyaServerMod#onInitialize()} 中调用一次，且仅调用一次。
     */
    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
            dispatcher.register(Commands.literal("here")
                // 声明任何来源都可执行，非玩家由 execute 中的 getPlayerOrException() 拦下。
                // 不可改用 requires(isPlayer)：那会让本指令被标记为受限指令，
                // 玩家点击聊天里的按钮时会弹出提权确认框（详见 CLAUDE.md「命令编写规范」）。
                .requires(Commands.hasPermission(Commands.LEVEL_ALL))
                .executes(HereCommand::execute)
            )
        );
    }

    /**
     * 指令执行逻辑：对执行者施加发光效果并回执。
     *
     * @param context Brigadier 提供的指令上下文，包含来源（CommandSourceStack）等信息
     * @return 1 表示执行成功（Brigadier 约定：成功返回正整数）
     * @throws CommandSyntaxException 来源不是玩家时抛出，携带原版本地化提示「需要玩家身份才能执行」
     */
    private static int execute(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();

        // 来源不是玩家时在此抛出原版本地化错误（permissions.requires.player）
        ServerPlayer player = source.getPlayerOrException();

        // MobEffectInstance 参数说明：
        //   amplifier=0     → 效果等级 I（amplifier 从 0 起算，0 即一级）
        //   ambient=true   → 显示为好看的信标蓝色边框效果代表非自然 Buff
        //   showParticles=false → 不在实体周围显示状态粒子，避免视觉干扰
        //   showIcon=false   → 隐藏 HUD 图标
        player.addEffect(new MobEffectInstance(MobEffects.GLOWING, GLOW_DURATION_TICKS, 0, true, false, false));

        Messages.sendSuccess(source, KEY_SUCCESS);

        return 1;
    }
}
