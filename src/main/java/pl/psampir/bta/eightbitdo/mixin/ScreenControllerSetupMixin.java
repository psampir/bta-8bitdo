package pl.psampir.bta.eightbitdo.mixin;

import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ButtonElement;
import net.minecraft.client.gui.Screen;
import net.minecraft.client.gui.ScreenControllerSetup;
import net.minecraft.client.input.InputHandler;
import net.minecraft.client.input.controller.ControllerInput;
import net.minecraft.client.input.controller.ControllerType;
import net.minecraft.client.input.glfw.GlfwGamepad;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pl.psampir.bta.eightbitdo.LayoutConfig;

/**
 * Adds a "Layout" button to BTA's controller setup screen.
 *
 * <p>Pads that share a USB id but ship with different ABXY shells cannot be told apart by
 * BTA, so the player picks the glyph set by hand: the button cycles
 * {@code AUTO -> XBOX 360 -> NINTENDO SWITCH -> AUTO} for the selected controller and
 * writes the choice to {@code config/eightbitdo.properties}. Changing it recreates the
 * {@link ControllerInput} so the new type (and its glyphs) apply immediately.</p>
 */
@Mixin(value = ScreenControllerSetup.class, remap = false)
public abstract class ScreenControllerSetupMixin {
	@Unique
	private static final Logger EIGHTBITDO_LOGGER = LoggerFactory.getLogger("8BitDo");

	@Unique
	private static final int EIGHTBITDO_LAYOUT_BUTTON_ID = 100;

	@Unique
	private ButtonElement eightbitdo$layoutButton;

	@Inject(method = "init", at = @At("RETURN"), remap = false)
	private void eightbitdo$addLayoutButton(CallbackInfo ci) {
		Screen self = (Screen) (Object) this;
		ButtonElement button = new ButtonElement(EIGHTBITDO_LAYOUT_BUTTON_ID,
				self.width / 2 - 100, self.height - 64, 200, 20, "");
		button.setListener(clicked -> eightbitdo$cycleLayout());
		this.eightbitdo$layoutButton = button;
		self.buttons.add(button);
		eightbitdo$refreshLayoutButton();
	}

	@Inject(method = "tick", at = @At("RETURN"), remap = false)
	private void eightbitdo$tickLayoutButton(CallbackInfo ci) {
		eightbitdo$refreshLayoutButton();
	}

	@Unique
	private void eightbitdo$cycleLayout() {
		Screen self = (Screen) (Object) this;
		Minecraft mc = self.mc;
		ControllerInput controller = mc.controllerInput;
		if (controller == null) {
			eightbitdo$refreshLayoutButton();
			return;
		}

		GlfwGamepad pad = controller.getController();
		LayoutConfig config = LayoutConfig.get();
		String current = config.resolve(pad.getGuid(), pad.getVendorId(), pad.getDeviceId(), pad.getName());
		String next;
		if (current == null) {
			next = "xbox_360";
		} else if ("xbox_360".equals(current)) {
			next = "switch";
		} else {
			next = null;
		}

		try {
			config.setGuid(pad.getGuid(), next);
			config.save();
			EIGHTBITDO_LOGGER.info("Controller layout for \"{}\" ({}) set to {}",
					pad.getName(), pad.getGuid(), next == null ? LayoutConfig.AUTO : next);
		} catch (Throwable t) {
			EIGHTBITDO_LOGGER.warn("Could not save the controller layout for \"{}\"", pad.getGuid(), t);
		}

		// Recreate the input so the new type (and therefore the glyphs) is live at once.
		int id = pad.getId();
		if (id >= 0 && id < InputHandler.controllers.length) {
			InputHandler.controllers[id] = null;
		}
		mc.controllerInput = null;
		InputHandler.poll();

		eightbitdo$refreshLayoutButton();
	}

	@Unique
	private void eightbitdo$refreshLayoutButton() {
		ButtonElement button = this.eightbitdo$layoutButton;
		if (button == null) {
			return;
		}
		Screen self = (Screen) (Object) this;
		Minecraft mc = self.mc;
		ControllerInput controller = mc.controllerInput;

		boolean present = controller != null;
		button.visible = present;
		button.enabled = present;
		if (!present) {
			button.displayString = "Layout: " + LayoutConfig.AUTO.toUpperCase(Locale.ROOT);
			return;
		}

		GlfwGamepad pad = controller.getController();
		String value = LayoutConfig.get().resolve(pad.getGuid(), pad.getVendorId(),
				pad.getDeviceId(), pad.getName());
		if (value == null) {
			button.displayString = "Layout: " + LayoutConfig.AUTO.toUpperCase(Locale.ROOT)
					+ " (" + controller.getType().getPrintableName() + ")";
		} else {
			button.displayString = "Layout: "
					+ eightbitdo$typeFor(value, controller.getType()).getPrintableName();
		}
	}

	@Unique
	private static ControllerType eightbitdo$typeFor(String value, ControllerType fallback) {
		try {
			return ControllerType.valueOf(value.toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			return fallback;
		}
	}
}
