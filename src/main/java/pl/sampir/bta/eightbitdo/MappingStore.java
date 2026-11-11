package pl.sampir.bta.eightbitdo;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

/**
 * The 8BitDo slice of the SDL GameControllerDB format (also understood by GLFW).
 *
 * <p>This class deliberately has no Minecraft, LWJGL or SLF4J dependency, so it can be
 * loaded and exercised by {@code tools/VerifyMappings.java} outside of the game.</p>
 *
 * <p>A mapping line looks like this:</p>
 *
 * <pre>GUID,name,token,token,...,platform:Linux,</pre>
 *
 * <p>The GUID is 32 hexadecimal characters: bus (bytes 0-3), vendor id (bytes 4-5),
 * product id (bytes 8-9), version (bytes 12-13) and three padding fields. Every 16-bit
 * field is little endian, the same layout {@code net.minecraft.client.input.glfw.GlfwGamepad}
 * decodes when it reports a controller's vendor and device id.</p>
 *
 * <p>GLFW matches mappings on the full GUID, which includes the device's firmware
 * revision. The upstream database usually has one entry per revision, so a live device
 * frequently has no exact match. {@link #resolve(String, String)} therefore degrades in
 * steps: exact GUID, then the same bus/vendor/product, then vendor/product, and finally
 * a standard XInput body.</p>
 */
public final class MappingStore {
	/** Classpath location of the generated mapping data inside the mod jar. */
	public static final String RESOURCE_PATH = "/eightbitdo-mappings.txt";

	/** 8BitDo's USB vendor id. */
	public static final int VENDOR_ID = 0x2dc8;

	/** Platform token preferred when several mappings describe the same device. */
	public static final String PLATFORM_LINUX = "Linux";

	/**
	 * The common XInput-compatible layout, used when no database entry matches a device
	 * at all. This is the body the 8BitDo Ultimate 2 Wireless reports on Linux through
	 * the 8BitDo USB receiver; it also fits every other 8BitDo pad switched to XInput mode.
	 */
	public static final String STANDARD_XINPUT_BODY =
			"a:b0,b:b1,back:b6,dpdown:h0.4,dpleft:h0.8,dpright:h0.2,dpup:h0.1,"
					+ "guide:b8,leftshoulder:b4,leftstick:b9,lefttrigger:a2,leftx:a0,lefty:a1,"
					+ "rightshoulder:b5,rightstick:b10,righttrigger:a5,rightx:a3,righty:a4,"
					+ "start:b7,x:b2,y:b3,";

	/** Name used for the {@link Kind#FALLBACK} mapping when the device name is unknown. */
	public static final String FALLBACK_NAME = "8BitDo Controller (XInput)";

	private static final String PLATFORM_TOKEN = "platform:";

	private final List<Entry> entries;
	private final Map<String, Entry> byGuid;

	private MappingStore(List<Entry> entries) {
		this.entries = Collections.unmodifiableList(entries);
		Map<String, Entry> index = new LinkedHashMap<>();
		for (Entry entry : entries) {
			index.putIfAbsent(entry.guid(), entry);
		}
		this.byGuid = Collections.unmodifiableMap(index);
	}

	/** Loads the mapping data shipped inside the mod jar. */
	public static MappingStore loadDefault() {
		try (InputStream stream = MappingStore.class.getResourceAsStream(RESOURCE_PATH)) {
			if (stream == null) {
				return fromLines(Collections.emptyList());
			}
			BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
			List<String> lines = new ArrayList<>();
			String line;
			while ((line = reader.readLine()) != null) {
				lines.add(line);
			}
			return fromLines(lines);
		} catch (IOException e) {
			throw new IllegalStateException("Could not read " + RESOURCE_PATH, e);
		}
	}

	/** Parses database lines, skipping comments, blanks and malformed entries. */
	public static MappingStore fromLines(Iterable<String> lines) {
		List<Entry> parsed = new ArrayList<>();
		for (String line : lines) {
			Entry entry = parse(line);
			if (entry != null) {
				parsed.add(entry);
			}
		}
		return new MappingStore(parsed);
	}

	/** Parses one database line, or returns {@code null} if it is not usable. */
	public static Entry parse(String line) {
		if (line == null) {
			return null;
		}
		String trimmed = line.trim();
		if (trimmed.isEmpty() || trimmed.startsWith("#")) {
			return null;
		}
		int firstComma = trimmed.indexOf(',');
		if (firstComma < 0) {
			return null;
		}
		String guid = trimmed.substring(0, firstComma).trim();
		if (!isValidGuid(guid)) {
			return null;
		}
		int secondComma = trimmed.indexOf(',', firstComma + 1);
		if (secondComma < 0) {
			return null;
		}
		String name = trimmed.substring(firstComma + 1, secondComma).trim();
		String mapping = trimmed.substring(secondComma + 1);

		String platform = "";
		StringBuilder body = new StringBuilder(mapping.length());
		for (String token : mapping.split(",")) {
			if (token.isEmpty()) {
				continue;
			}
			if (token.startsWith(PLATFORM_TOKEN)) {
				platform = token.substring(PLATFORM_TOKEN.length()).trim();
				continue;
			}
			body.append(token).append(',');
		}
		if (body.length() == 0) {
			return null;
		}
		return new Entry(guid.toLowerCase(Locale.ROOT), name, body.toString(), platform,
				vid(guid), pid(guid), bus(guid), version(guid));
	}

	/**
	 * Resolves the mapping to register for a live device.
	 *
	 * @param guid         GUID reported by {@code glfwGetJoystickGUID}
	 * @param fallbackName device name reported by {@code glfwGetJoystickName}, may be {@code null}
	 */
	public Resolution resolve(String guid, String fallbackName) {
		if (!isValidGuid(guid)) {
			throw new IllegalArgumentException("Not a 32 character hexadecimal GUID: " + guid);
		}
		String key = guid.toLowerCase(Locale.ROOT);
		int deviceVid = vid(key);
		int devicePid = pid(key);
		int deviceBus = bus(key);
		int deviceVersion = version(key);

		Entry exact = byGuid.get(key);
		if (exact != null) {
			return new Resolution(guid, exact.name(), exact.body(), Kind.EXACT);
		}

		Entry sameBus = best(candidate -> candidate.vid() == deviceVid
				&& candidate.pid() == devicePid
				&& candidate.bus() == deviceBus, deviceVersion);
		if (sameBus != null) {
			return new Resolution(guid, sameBus.name(), sameBus.body(), Kind.SAME_BUS_VENDOR_PRODUCT);
		}

		Entry sameVendorProduct = best(candidate -> candidate.vid() == deviceVid
				&& candidate.pid() == devicePid, deviceVersion);
		if (sameVendorProduct != null) {
			return new Resolution(guid, sameVendorProduct.name(), sameVendorProduct.body(),
					Kind.SAME_VENDOR_PRODUCT);
		}

		String name = sanitiseName(fallbackName);
		return new Resolution(guid, name == null || name.isEmpty() ? FALLBACK_NAME : name,
				STANDARD_XINPUT_BODY, Kind.FALLBACK);
	}

	/**
	 * Among matching entries prefers Linux, then the exact firmware revision, then the
	 * order the entry appeared in the database.
	 */
	private Entry best(Predicate<Entry> filter, int deviceVersion) {
		Entry best = null;
		int bestScore = Integer.MAX_VALUE;
		for (Entry candidate : entries) {
			if (!filter.test(candidate)) {
				continue;
			}
			int score = 0;
			if (!PLATFORM_LINUX.equalsIgnoreCase(candidate.platform())) {
				score += 2;
			}
			if (candidate.version() != deviceVersion) {
				score += 1;
			}
			if (score < bestScore) {
				best = candidate;
				bestScore = score;
			}
		}
		return best;
	}

	/** Number of parsed mapping entries. */
	public int size() {
		return entries.size();
	}

	/** All parsed entries, in database order. */
	public List<Entry> entries() {
		return entries;
	}

	// ---------------------------------------------------------------- GUID helpers

	/** True if the string is 32 hexadecimal characters. */
	public static boolean isValidGuid(String guid) {
		if (guid == null || guid.length() != 32) {
			return false;
		}
		for (int i = 0; i < 32; i++) {
			if (Character.digit(guid.charAt(i), 16) < 0) {
				return false;
			}
		}
		return true;
	}

	/** True if the GUID's vendor id is 8BitDo's ({@code 0x2dc8}). */
	public static boolean isEightBitDo(String guid) {
		return isValidGuid(guid) && vid(guid) == VENDOR_ID;
	}

	/** Vendor id, GUID bytes 4-5, little endian. */
	public static int vid(String guid) {
		return u16(guid, 4);
	}

	/** Product id, GUID bytes 8-9, little endian. */
	public static int pid(String guid) {
		return u16(guid, 8);
	}

	/** Bus type, GUID bytes 0-3, little endian (3 = USB, 5 = Bluetooth). */
	public static int bus(String guid) {
		return u16(guid, 0);
	}

	/** Firmware revision, GUID bytes 12-13, little endian. */
	public static int version(String guid) {
		return u16(guid, 12);
	}

	private static int u16(String guid, int byteOffset) {
		if (!isValidGuid(guid)) {
			return -1;
		}
		int low = Integer.parseInt(guid.substring(byteOffset * 2, byteOffset * 2 + 2), 16);
		int high = Integer.parseInt(guid.substring(byteOffset * 2 + 2, byteOffset * 2 + 4), 16);
		return (high << 8) | low;
	}

	private static String sanitiseName(String name) {
		// A comma would split the mapping line in the wrong place.
		return name == null ? null : name.replace(',', ' ').trim();
	}

	/** One database line. {@code body} excludes the {@code platform:} token, which GLFW ignores. */
	public record Entry(String guid, String name, String body, String platform,
						int vid, int pid, int bus, int version) {
	}

	/** How a mapping was found. */
	public enum Kind {
		/** The live GUID is in the database verbatim. */
		EXACT,
		/** Same bus, vendor and product, different firmware revision. */
		SAME_BUS_VENDOR_PRODUCT,
		/** Same vendor and product on another bus. */
		SAME_VENDOR_PRODUCT,
		/** Nothing matched; {@link #STANDARD_XINPUT_BODY} is used. */
		FALLBACK
	}

	/** A ready-to-register mapping. */
	public record Resolution(String guid, String name, String body, Kind kind) {
		/** The line handed to {@code glfwUpdateGamepadMappings}. */
		public String mappingLine() {
			return guid + "," + name + "," + body;
		}
	}
}
