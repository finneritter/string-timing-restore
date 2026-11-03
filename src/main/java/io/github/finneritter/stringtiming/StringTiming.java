package io.github.finneritter.stringtiming;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class StringTiming implements ModInitializer {
	public static final Logger LOGGER = LoggerFactory.getLogger("stringtiming");
	/** Kill switch: -Dstringtiming.disable=true makes every injected path take vanilla. */
	public static final boolean DISABLED = Boolean.getBoolean("stringtiming.disable");

	@Override
	public void onInitialize() {
		LOGGER.info(DISABLED
				? "String Timing Restore: fix DISABLED by -Dstringtiming.disable=true (vanilla 26.3 tripwire)"
				: "String Timing Restore: fix ACTIVE (1.21.11 tripwire timing)");
	}
}
