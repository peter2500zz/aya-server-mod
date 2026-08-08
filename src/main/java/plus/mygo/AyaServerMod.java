package plus.mygo;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import plus.mygo.command.BackCommand;
import plus.mygo.command.DieCommand;
import plus.mygo.command.HatCommand;
import plus.mygo.command.HereCommand;
import plus.mygo.command.HomeCommand;
import plus.mygo.command.TpaCommand;
import plus.mygo.i18n.ServerLanguage;

/**
 * Mod 主入口。
 * <p>
 * 服务端加载完成后由 Fabric Loader 调用 {@link #onInitialize()}。
 * 本类仅负责加载服务端翻译表并统一注册所有指令，不包含任何业务逻辑。
 */
public class AyaServerMod implements ModInitializer {

    /** Mod ID，须与 fabric.mod.json 中的 id 字段保持一致。 */
    public static final String MOD_ID = "aya-server-mod";

    /** 模组专属 Logger，日志前缀即为 MOD_ID，便于在服务端控制台中定位本模组输出。 */
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    /**
     * Fabric 生命周期回调：服务端初始化完成时由框架调用。
     * 先加载服务端翻译表，再按顺序注册所有指令；每条指令的注册逻辑封装在各自的 Command 类中。
     */
    @Override
    public void onInitialize() {
        // 必须先于指令注册完成：指令执行时会同步查表取 fallback 文本。
        // 此处仅读取 jar 内的 classpath 资源，不依赖服务器实例，故可在初始化阶段安全调用。
        ServerLanguage.load();

        BackCommand.register();
        DieCommand.register();
        HatCommand.register();
        HereCommand.register();
        HomeCommand.register();
        TpaCommand.register();
    }
}
