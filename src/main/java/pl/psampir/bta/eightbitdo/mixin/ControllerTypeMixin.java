package pl.psampir.bta.eightbitdo.mixin;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.input.controller.ControllerType;
import net.minecraft.client.input.glfw.GlfwGamepad;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import pl.psampir.bta.eightbitdo.LayoutConfig;

/**
 * Applies {@code config/eightbitdo.properties} on top of BTA's controller type database.
 *
 * <p>{@link ControllerType#fromController} is the single place BTA decides which glyph set
 * a pad gets; {@code ControllerInput} calls it once in its constructor and keeps the
 * result. Overriding the return value therefore changes labels only, never input: BTA
 * reads buttons and axes by GLFW index and uses the type purely for drawing.</p>
 */
@Mixin(value = ControllerType.class, remap = false)
public abstract class ControllerTypeMixin {
	@Unique
	private static final Logger EIGHTBITDO_LOGGER = LoggerFactory.getLogger("8BitDo");

	/** GUID + layout pairs already announced, so a re-created controller does not re-log. */
	@Unique
	private static final Map<String, String> EIGHTBITDO_LOGGED = new ConcurrentHashMap<>();

	@Unique
	private static boolean eightbitdo$warningsLogged;

	@Inject(method = "fromController", at = @At("HEAD"), cancellable = true, remap = false)
	private static void eightbitdo$applyLayout(GlfwGamepad controller, CallbackInfoReturnable<ControllerType> cir) {
		try {
			LayoutConfig config = LayoutConfig.get();
			if (!eightbitdo$warningsLogged) {
				eightbitdo$warningsLogged = true;
				for (String warning : config.warnings()) {
					EIGHTBITDO_LOGGER.warn("{}: {}", LayoutConfig.FILE_NAME, warning);
				}
			}

			String value = config.resolve(controller.getGuid(), controller.getVendorId(),
					controller.getDeviceId(), controller.getName());
			if (value == null) {
				return;
			}

			ControllerType type;
			try {
				type = ControllerType.valueOf(value.toUpperCase(Locale.ROOT));
			} catch (IllegalArgumentException e) {
				EIGHTBITDO_LOGGER.warn("Ignoring unknown controller layout \"{}\" for \"{}\"",
						value, controller.getGuid());
				return;
			}

			String key = controller.getGuid().toLowerCase(Locale.ROOT) + "=" + value;
			if (EIGHTBITDO_LOGGED.put(key, value) == null) {
				EIGHTBITDO_LOGGER.info("Applying controller layout \"{}\" for \"{}\" ({})",
						value, controller.getName(), controller.getGuid());
			}
			cir.setReturnValue(type);
		} catch (Throwable t) {
			EIGHTBITDO_LOGGER.warn("Could not apply the 8BitDo controller layout override", t);
		}
	}
}
