package pl.psampir.bta.eightbitdo;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Teaches GLFW (= BTA's native controller support) about every attached 8BitDo pad.
 *
 * <p>BTA 8.0.1 loads its bundled {@code controller_mappings.txt} and then asks GLFW how
 * many gamepads it recognised. GLFW only treats a joystick as a gamepad when a mapping
 * matches its <em>entire</em> GUID, firmware revision included, so pads that are missing
 * from that snapshot, or whose revision differs from the snapshot, are reported as
 * {@code 0 controllers found!}. Registering one extra mapping before BTA reads the
 * database fixes that: {@code glfwUpdateGamepadMappings} is additive and later updates
 * do not remove entries.</p>
 *
 * <p>Nothing here touches Minecraft classes, which keeps the class testable from a plain
 * {@code main()} against the real controller (see {@code tools/VerifyMappings.java}).</p>
 */
public final class EightBitDoSupport {
	/** USB vendor id of every 8BitDo device this mod handles. */
	public static final int VENDOR_ID = MappingStore.VENDOR_ID;

	/**
	 * How many {@link #tick()} calls pass between hot-plug scans. BTA calls
	 * {@code InputHandler.poll()} once per rendered frame, so this is a few scans per
	 * second at normal frame rates.
	 */
	public static final int TICKS_PER_SCAN = 20;

	/**
	 * Joysticks with fewer axes than this are not gamepads. The UM 2 receiver, for one,
	 * also exposes its keyboard and mouse HID interfaces as joysticks with a single axis;
	 * GLFW never accepts those as gamepads, so their mappings would only be noise.
	 */
	public static final int MIN_AXES = 2;

	private static final Logger LOGGER = LoggerFactory.getLogger("8BitDo");

	private static final Map<String, MappingStore.Resolution> REGISTERED = new LinkedHashMap<>();
	private static final Set<String> REJECTED = ConcurrentHashMap.newKeySet();
	private static final AtomicInteger TICK_COUNTER = new AtomicInteger();

	private static volatile MappingStore mappingStore;
	private static volatile boolean failureLogged;

	private EightBitDoSupport() {
	}

	/**
	 * Registers a mapping for every attached 8BitDo joystick GLFW does not classify as a
	 * gamepad yet, and for none twice.
	 *
	 * <p>Never throws: a failure is logged once and BTA carries on with whatever the
	 * vanilla database provided.</p>
	 *
	 * @return the number of mappings registered by this call
	 */
	public static int registerMissingGamepads() {
		int added = 0;
		try {
			MappingStore store = store();
			for (int id = 0; id <= GLFW.GLFW_JOYSTICK_LAST; id++) {
				try {
					added += registerController(store, id);
				} catch (Throwable t) {
					logFailureOnce("8BitDo controller support could not inspect joystick " + id, t);
				}
			}
		} catch (Throwable t) {
			logFailureOnce("8BitDo controller support could not register its mappings", t);
		}
		return added;
	}

	/** Logs the first failure only: a broken GLFW call must not spam the game log. */
	private static void logFailureOnce(String message, Throwable t) {
		if (!failureLogged) {
			failureLogged = true;
			LOGGER.error(message + "; BTA falls back to its bundled controller database.", t);
		}
	}

	private static int registerController(MappingStore store, int id) {
		if (!GLFW.glfwJoystickPresent(id)) {
			return 0;
		}
		String guid = GLFW.glfwGetJoystickGUID(id);
		if (guid == null || !MappingStore.isValidGuid(guid)) {
			return 0;
		}
		if (!MappingStore.isEightBitDo(guid)) {
			return 0;
		}
		String key = guid.toLowerCase(Locale.ROOT);
		if (REJECTED.contains(key) || isRegistered(key)) {
			return 0;
		}
		// A controller GLFW already knows needs no help; the user may even have a better
		// mapping from a custom database in place.
		if (GLFW.glfwJoystickIsGamepad(id)) {
			return 0;
		}
		if (axisCount(id) < MIN_AXES) {
			// Not a pad: a receiver's keyboard/mouse interfaces look like joysticks too.
			return 0;
		}

		MappingStore.Resolution resolution = store.resolve(guid, GLFW.glfwGetJoystickName(id));
		ByteBuffer buffer = MemoryUtil.memASCII(resolution.mappingLine());
		boolean accepted;
		try {
			accepted = GLFW.glfwUpdateGamepadMappings(buffer);
		} finally {
			MemoryUtil.memFree(buffer);
		}

		if (!accepted || !GLFW.glfwJoystickIsGamepad(id)) {
			REJECTED.add(key);
			LOGGER.warn("GLFW still does not treat \"{}\" ({}, {}) as a gamepad after"
							+ " registering its mapping.",
					resolution.name(), guid, resolution.kind());
			return 0;
		}

		synchronized (REGISTERED) {
			REGISTERED.put(key, resolution);
		}
		LOGGER.info("Registered controller mapping for \"{}\" ({}, {}), joystick id {}",
				resolution.name(), guid, resolution.kind(), id);
		return 1;
	}

	/**
	 * Called at the head of BTA's controller poll each tick. Scans for newly plugged pads
	 * roughly once per second, so hot-plugging works without restarting the game.
	 */
	public static void tick() {
		if (TICK_COUNTER.incrementAndGet() % TICKS_PER_SCAN == 0) {
			registerMissingGamepads();
		}
	}

	/** The mapping data, loaded from the mod jar on first use. */
	public static MappingStore store() {
		MappingStore store = mappingStore;
		if (store == null) {
			synchronized (EightBitDoSupport.class) {
				store = mappingStore;
				if (store == null) {
					store = MappingStore.loadDefault();
					mappingStore = store;
				}
			}
		}
		return store;
	}

	/** GUIDs of the mappings this mod registered successfully, in registration order. */
	public static Set<String> registeredGuids() {
		synchronized (REGISTERED) {
			return new LinkedHashSet<>(REGISTERED.keySet());
		}
	}

	/** Number of mappings this mod registered successfully. */
	public static int registeredCount() {
		synchronized (REGISTERED) {
			return REGISTERED.size();
		}
	}

	/** Resolved mappings in registration order, for diagnostics and the test harness. */
	public static List<MappingStore.Resolution> resolvedMappings() {
		synchronized (REGISTERED) {
			return new ArrayList<>(REGISTERED.values());
		}
	}

	private static boolean isRegistered(String key) {
		synchronized (REGISTERED) {
			return REGISTERED.containsKey(key);
		}
	}

	/** Number of axes the joystick exposes, 0 if GLFW cannot read them. */
	private static int axisCount(int id) {
		var axes = GLFW.glfwGetJoystickAxes(id);
		return axes == null ? 0 : axes.remaining();
	}
}
