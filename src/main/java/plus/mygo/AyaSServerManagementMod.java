package plus.mygo;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import plus.mygo.command.HereCommand;

public class AyaSServerManagementMod implements ModInitializer {
	public static final String MOD_ID = "aya-server-mod";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		HereCommand.register();
	}
}