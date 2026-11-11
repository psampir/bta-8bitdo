package pl.psampir.bta.eightbitdo.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.input.InputHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pl.psampir.bta.eightbitdo.EightBitDoSupport;

/**
 * Hooks BTA's GLFW controller setup.
 *
 * <p>{@code initGLFW} builds BTA's bundled controller database and then counts the pads
 * GLFW recognises, logging {@code "{} controllers found!"}. Injecting at the head of it
 * registers the 8BitDo mappings first, so that count includes our pads. BTA then reads
 * its own database into the same GLFW state, which is additive: our entries survive.</p>
 *
 * <p>{@code poll} runs once per rendered frame, which is where hot-plugged pads are picked
 * up; {@link EightBitDoSupport#tick()} only really scans every 20 frames.</p>
 *
 * <p>BTA is unobfuscated, hence {@code remap = false} everywhere and no refmap.</p>
 */
@Mixin(value = InputHandler.class, remap = false)
public abstract class InputHandlerMixin {
	@Inject(method = "initGLFW", at = @At("HEAD"), remap = false)
	private static void eightbitdo$registerMappings(Minecraft minecraft, CallbackInfo ci) {
		EightBitDoSupport.registerMissingGamepads();
	}

	@Inject(method = "poll", at = @At("HEAD"), remap = false)
	private static void eightbitdo$tick(CallbackInfo ci) {
		EightBitDoSupport.tick();
	}
}
