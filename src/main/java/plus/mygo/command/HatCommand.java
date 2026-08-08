package plus.mygo.command;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

/**
 * /hat 指令。
 * <p>
 * 仅限玩家执行，无参数。将执行者的主手物品与头部装备槽物品直接对调，
 * 无视任何条件：不检查物品是否为可佩戴头盔、不检查游戏模式，任意物品都能"戴在头上"。
 */
public class HatCommand {

    /**
     * 向 Fabric 命令系统注册 /hat 指令。
     * 应在 {@link plus.mygo.AyaServerMod#onInitialize()} 中调用一次，且仅调用一次。
     */
    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
            dispatcher.register(Commands.literal("hat")
                // 声明任何来源都可执行，非玩家由 execute 中的 getPlayerOrException() 拦下。
                // 不可改用 requires(isPlayer)：那会让本指令被标记为受限指令，
                // 玩家点击聊天里的按钮时会弹出提权确认框（详见 CLAUDE.md「命令编写规范」）。
                .requires(Commands.hasPermission(Commands.LEVEL_ALL))
                .executes(HatCommand::execute)
            )
        );
    }

    /**
     * 指令执行逻辑：对调执行者的主手与头部槽物品。
     * <p>
     * {@code getItemBySlot} / {@code setItemSlot} 是 {@code LivingEntity} 操作装备槽的标准高层接口；
     * 在 26.2 中玩家的 {@code Inventory} 与 {@code LivingEntity} 共用同一个 {@code EntityEquipment}，
     * 因此对玩家 MAINHAND / HEAD 槽的读写均即时生效，无需直接操作物品栏内部结构。
     *
     * @param context Brigadier 提供的指令上下文
     * @return 1 表示执行成功
     * @throws CommandSyntaxException 来源不是玩家时抛出，携带原版本地化提示「需要玩家身份才能执行」
     */
    private static int execute(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        // 来源不是玩家时在此抛出原版本地化错误（permissions.requires.player）
        ServerPlayer player = context.getSource().getPlayerOrException();

        // 先取出两槽当前物品的引用：两者为不同对象（或同为 ItemStack.EMPTY 单例），
        // 因此随后交换引用是安全的，无需 copy()
        ItemStack head = player.getItemBySlot(EquipmentSlot.HEAD);
        ItemStack mainHand = player.getItemBySlot(EquipmentSlot.MAINHAND);

        // 直接对调：主手物品戴到头上，原头部物品落入主手
        player.setItemSlot(EquipmentSlot.HEAD, mainHand);
        player.setItemSlot(EquipmentSlot.MAINHAND, head);

        return 1;
    }
}
