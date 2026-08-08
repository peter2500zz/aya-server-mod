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
 * 仅限玩家执行；控制台与命令方块不可见此指令。
 * <p>
 * 玩家在第一人称下看不到自己的发光轮廓，故额外发送一条确认消息告知效果已生效及其时长。
 */
public class HereCommand {

    /** 发光效果持续时长（秒），同时用于向玩家展示。 */
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
                // 在 Brigadier 层面限制为玩家：控制台及非玩家实体不可见此指令
                .requires(CommandSourceStack::isPlayer)
                .executes(HereCommand::execute)
            )
        );
    }

    /**
     * 指令执行逻辑：对执行者施加发光效果并回执。
     *
     * @param context Brigadier 提供的指令上下文，包含来源（CommandSourceStack）等信息
     * @return 1 表示执行成功（Brigadier 约定：成功返回正整数）
     * @throws CommandSyntaxException 若来源不是玩家（理论上不会发生，requires 已保证）
     */
    private static int execute(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();

        // requires() 已保证来源为玩家，此处不会抛出异常
        ServerPlayer player = source.getPlayerOrException();

        // MobEffectInstance 参数说明：
        //   amplifier=0     → 效果等级 I（amplifier 从 0 起算，0 即一级）
        //   ambient=true   → 显示为好看的信标蓝色边框效果代表非自然 Buff
        //   showParticles=false → 不在实体周围显示状态粒子，避免视觉干扰
        //   showIcon=false   → 隐藏 HUD 图标
        player.addEffect(new MobEffectInstance(MobEffects.GLOWING, GLOW_DURATION_TICKS, 0, true, false, false));

        // 秒数按约定转成 String 传参：可变参数会随消息过网络序列化，只应传 String 或 Component
        Messages.sendSuccess(source, KEY_SUCCESS, String.valueOf(GLOW_DURATION_SECONDS));

        return 1;
    }
}
