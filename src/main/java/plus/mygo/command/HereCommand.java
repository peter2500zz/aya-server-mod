package plus.mygo.command;

import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;

public class HereCommand {
    private static final int GLOW_DURATION_TICKS = 30 * 20;

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
            dispatcher.register(Commands.literal("here")
                .requires(source -> source.getEntity() instanceof LivingEntity)
                .executes(HereCommand::execute)
            )
        );
    }

    private static int execute(CommandContext<CommandSourceStack> context) {
        LivingEntity entity = (LivingEntity) context.getSource().getEntity();
        entity.addEffect(new MobEffectInstance(MobEffects.GLOWING, GLOW_DURATION_TICKS, 0, false, false, true));
        return 1;
    }
}
