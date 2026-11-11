/*
 * Standalone verification harness: exercises the mod's real classes (MappingStore and
 * EightBitDoSupport) against the controller attached to this machine, with no Minecraft
 * on the classpath.
 *
 *   tools/verify.fish            -- compiles and runs everything
 *
 * The harness initialises GLFW the way BTA does, prints every joystick GLFW sees, calls
 * EightBitDoSupport.registerMissingGamepads() (the exact method the mixin calls at the
 * head of InputHandler.initGLFW), and checks that the 8BitDo pad flips from
 * isGamepad=false to isGamepad=true. A second call must register nothing and log nothing.
 */

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWGamepadState;
import org.lwjgl.system.MemoryUtil;
import pl.psampir.bta.eightbitdo.EightBitDoSupport;
import pl.psampir.bta.eightbitdo.MappingStore;

public final class VerifyMappings {
	/** GUID of the pad this mod was written for (8BitDo Ultimate 2 Wireless, UM 2 receiver). */
	private static final String REFERENCE_GUID = "03000000c82d00000b31000000010000";

	private static boolean failed;

	public static void main(String[] args) {
		String referenceGuid = args.length > 0 ? args[0] : REFERENCE_GUID;
		GLFW.glfwSetErrorCallback((code, description) ->
				System.out.println("  GLFW error " + code + ": " + MemoryUtil.memUTF8(description)));

		System.out.println("== environment");
		System.out.println("  lwjgl / glfw   : " + GLFW.GLFW_VERSION_MAJOR + "." + GLFW.GLFW_VERSION_MINOR
				+ "." + GLFW.GLFW_VERSION_REVISION);
		if (!GLFW.glfwInit()) {
			System.out.println("  glfwInit       : FAILED");
			System.exit(2);
		}
		System.out.println("  glfwInit       : true (" + GLFW.glfwGetVersionString() + ")");

		MappingStore store = EightBitDoSupport.store();
		long linuxEntries = store.entries().stream()
				.filter(entry -> MappingStore.PLATFORM_LINUX.equalsIgnoreCase(entry.platform()))
				.count();
		System.out.println("  mapping store  : " + store.size() + " 8BitDo entries from "
				+ MappingStore.RESOURCE_PATH + " (" + linuxEntries + " for Linux)");
		if (store.size() == 0) {
			fail("the mapping resource is empty or missing");
		}

		System.out.println();
		System.out.println("== reference GUID resolution (" + referenceGuid + ")");
		System.out.println("  bus/vid/pid/version: "
				+ MappingStore.bus(referenceGuid) + " / 0x"
				+ Integer.toHexString(MappingStore.vid(referenceGuid)) + " / 0x"
				+ Integer.toHexString(MappingStore.pid(referenceGuid)) + " / 0x"
				+ Integer.toHexString(MappingStore.version(referenceGuid)));
		System.out.println("  isEightBitDo       : " + MappingStore.isEightBitDo(referenceGuid));
		MappingStore.Resolution reference = store.resolve(referenceGuid, "Reference pad");
		System.out.println("  match              : " + reference.kind());
		System.out.println("  mapping line       : " + reference.mappingLine());
		if (!MappingStore.isEightBitDo(referenceGuid)) {
			fail("the reference GUID is not recognised as 8BitDo");
		}

		System.out.println();
		System.out.println("== joysticks before registering");
		dump();

		List<Integer> pads = eightBitDoIds();
		if (pads.isEmpty()) {
			System.out.println("  No 8BitDo controller found. Connect one and run again.");
			GLFW.glfwTerminate();
			System.exit(3);
		}

		System.out.println();
		System.out.println("== registering");
		int added = EightBitDoSupport.registerMissingGamepads();
		System.out.println("  registerMissingGamepads() -> " + added + " mapping(s)");
		for (MappingStore.Resolution resolution : EightBitDoSupport.resolvedMappings()) {
			System.out.println("  guid           : " + resolution.guid());
			System.out.println("  match          : " + resolution.kind());
			System.out.println("  name           : " + resolution.name());
			System.out.println("  body           : " + resolution.body());
			System.out.println("  mapping line   : " + resolution.mappingLine());
		}

		System.out.println();
		System.out.println("== joysticks after registering");
		dump();
		int padsThatFlip = 0;
		for (int id : pads) {
			if (axes(id) < EightBitDoSupport.MIN_AXES) {
				System.out.println("  note: joystick " + id + " has " + axes(id)
						+ " axis/axes, not a pad -- the mod rightly ignores it");
				if (EightBitDoSupport.registeredGuids().contains(
						GLFW.glfwGetJoystickGUID(id).toLowerCase(java.util.Locale.ROOT))) {
					fail("joystick " + id + " has no axes but a mapping was registered for it");
				}
				continue;
			}
			padsThatFlip++;
			if (!GLFW.glfwJoystickIsGamepad(id)) {
				fail("joystick " + id + " is still not a gamepad after registration");
			}
		}
		if (padsThatFlip == 0) {
			fail("no usable 8BitDo pad was found");
		}
		System.out.println("  gamepad state  : " + gamepadState(padsThatFlip > 0 ? firstPad(pads) : -1));

		System.out.println();
		System.out.println("== BTA's bundled database loaded afterwards (what initGLFW does next)");
		String btaJar = System.getenv("BTA_JAR");
		if (btaJar == null || btaJar.isEmpty()) {
			System.out.println("  skipped, set BTA_JAR to bta-v8.0.1.jar to include this step");
		} else {
			try {
				String mappings = readMappingDatabase(btaJar);
				ByteBuffer buffer = MemoryUtil.memASCII(mappings);
				boolean accepted;
				try {
					accepted = GLFW.glfwUpdateGamepadMappings(buffer);
				} finally {
					MemoryUtil.memFree(buffer);
				}
				System.out.println("  glfwUpdateGamepadMappings(BTA db) -> " + accepted
						+ " (" + mappings.length() + " chars from " + btaJar + ")");
				for (int id : pads) {
					if (axes(id) < EightBitDoSupport.MIN_AXES) {
						continue;
					}
					if (!GLFW.glfwJoystickIsGamepad(id)) {
						fail("joystick " + id + " lost its gamepad mapping when BTA loaded its database");
					}
				}
				System.out.println("  pad still a gamepad after BTA's database: "
						+ GLFW.glfwJoystickIsGamepad(firstPad(pads)));
			} catch (IOException e) {
				fail("could not read " + btaJar + ": " + e);
			}
		}

		System.out.println();
		System.out.println("== idempotence");
		int again = EightBitDoSupport.registerMissingGamepads();
		System.out.println("  registerMissingGamepads() -> " + again + " mapping(s) (expected 0)");
		System.out.println("  registered GUIDs: " + EightBitDoSupport.registeredGuids());
		if (again != 0) {
			fail("a second call registered " + again + " mapping(s) again");
		}
		if (EightBitDoSupport.registeredCount() != EightBitDoSupport.registeredGuids().size()) {
			fail("registry bookkeeping is inconsistent");
		}

		GLFW.glfwTerminate();

		System.out.println();
		if (failed) {
			System.out.println("== RESULT: FAIL");
			System.exit(1);
		}
		System.out.println("== RESULT: PASS -- " + padsThatFlip
				+ " usable 8BitDo controller(s) recognised as gamepads, no duplicate registration");
	}

	private static void dump() {
		for (int id = 0; id <= GLFW.GLFW_JOYSTICK_LAST; id++) {
			if (!GLFW.glfwJoystickPresent(id)) {
				continue;
			}
			String guid = GLFW.glfwGetJoystickGUID(id);
			String tag = MappingStore.isEightBitDo(guid) ? " (8BitDo)" : "";
			System.out.println("  id=" + id
					+ " guid=" + guid
					+ " name=" + GLFW.glfwGetJoystickName(id)
					+ " axes=" + axes(id)
					+ " buttons=" + buttons(id)
					+ " isGamepad=" + GLFW.glfwJoystickIsGamepad(id)
					+ " gamepadName=" + GLFW.glfwGetGamepadName(id)
					+ tag);
		}
	}

	private static int firstPad(List<Integer> ids) {
		for (int id : ids) {
			if (axes(id) >= EightBitDoSupport.MIN_AXES) {
				return id;
			}
		}
		return -1;
	}

	private static int axes(int id) {
		var buffer = GLFW.glfwGetJoystickAxes(id);
		return buffer == null ? 0 : buffer.remaining();
	}

	private static int buttons(int id) {
		var buffer = GLFW.glfwGetJoystickButtons(id);
		return buffer == null ? 0 : buffer.remaining();
	}

	private static List<Integer> eightBitDoIds() {
		List<Integer> ids = new ArrayList<>();
		for (int id = 0; id <= GLFW.GLFW_JOYSTICK_LAST; id++) {
			if (GLFW.glfwJoystickPresent(id) && MappingStore.isEightBitDo(GLFW.glfwGetJoystickGUID(id))) {
				ids.add(id);
			}
		}
		return ids;
	}

	private static String gamepadState(int id) {
		GLFWGamepadState state = GLFWGamepadState.malloc();
		try {
			if (!GLFW.glfwGetGamepadState(id, state)) {
				return "unreadable";
			}
			StringBuilder axes = new StringBuilder("axes=[");
			for (int i = 0; i <= GLFW.GLFW_GAMEPAD_AXIS_LAST; i++) {
				if (i > 0) {
					axes.append(", ");
				}
				axes.append(String.format(java.util.Locale.ROOT, "%.2f", state.axes(i)));
			}
			return "readable " + axes.append(']');
		} finally {
			state.free();
		}
	}

	private static String readMappingDatabase(String path) throws IOException {
		Path file = Path.of(path);
		if (path.endsWith(".jar") || path.endsWith(".zip")) {
			try (ZipFile zip = new ZipFile(file.toFile())) {
				ZipEntry entry = zip.getEntry("controller_mappings.txt");
				if (entry == null) {
					throw new IOException(path + " has no controller_mappings.txt");
				}
				try (InputStream stream = zip.getInputStream(entry)) {
					return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
				}
			}
		}
		return Files.readString(file, StandardCharsets.UTF_8);
	}

	private static void fail(String reason) {
		failed = true;
		System.out.println("  FAIL: " + reason);
	}
}
