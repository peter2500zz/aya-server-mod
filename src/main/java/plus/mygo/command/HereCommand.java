package plus.mygo.command;

import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;

/**
 * /here 指令。
 * <p>
 * 执行后对执行者自身施加 30 秒发光效果（{@link MobEffects#GLOWING}），
 * 使其对周围玩家可见，包括透过方块。
 * 仅限生物实体（{@link LivingEntity}）执行；控制台与命令方块自身无法使用。
 */
public class HereCommand {

    /** 发光效果持续时长：30 秒，换算为游戏刻（1 秒 = 20 刻）。 */
    private static final int GLOW_DURATION_TICKS = 30 * 20;

    /**
     * 向 Fabric 命令系统注册 /here 指令。
     * 应在 {@link plus.mygo.AyaServerMod#onInitialize()} 中调用一次，且仅调用一次。
     */
    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
            dispatcher.register(Commands.literal("here")
                // 在 Brigadier 层面限制执行者：仅允许 LivingEntity（含玩家）执行。
                // 非实体来源（控制台、命令方块自身）将在此被拦截，且无法在 Tab 补全中看到此指令。
                .requires(source -> source.getEntity() instanceof LivingEntity)
                .executes(HereCommand::execute)
            )
        );
    }

    /**
     * 指令执行逻辑：对执行者施加发光效果。
     *
     * @param context Brigadier 提供的指令上下文，包含来源（CommandSourceStack）等信息
     * @return 1 表示执行成功（Brigadier 约定：成功返回正整数）
     */
    private static int execute(CommandContext<CommandSourceStack> context) {
        // requires() 已保证 getEntity() 非空且类型为 LivingEntity，此处向下转型安全。
        LivingEntity entity = (LivingEntity) context.getSource().getEntity();

        // MobEffectInstance 参数说明：
        //   amplifier=0     → 效果等级 I（amplifier 从 0 起算，0 即一级）
        //   ambient=false   → 非来自信标/潮涌核心，粒子显示为实心而非半透明
        //   showParticles=false → 不在实体周围显示状态粒子，避免视觉干扰
        //   showIcon=true   → 在执行者 HUD 右上角状态栏显示效果图标，告知其效果已激活
        entity.addEffect(new MobEffectInstance(MobEffects.GLOWING, GLOW_DURATION_TICKS, 0, false, false, true));
        return 1;
    }
}
