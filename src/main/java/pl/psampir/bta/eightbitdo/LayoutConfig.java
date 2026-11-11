package pl.psampir.bta.eightbitdo;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Per-device button layout overrides, read from {@code config/eightbitdo.properties}.
 *
 * <p>8BitDo sells several pads that are electrically identical but carry different ABXY
 * shells (Xbox style, Switch style, ...), and BTA cannot tell them apart: the two variants
 * of a family share the USB vendor/product id, the HID name and often the GLFW GUID too.
 * The layout is therefore a manual choice, per device, with {@code auto} as the default
 * that hands the pad back to BTA's own database.</p>
 *
 * <p>File format, one rule per line, {@code key = layout}:</p>
 *
 * <pre>
 * # exact GLFW GUID (32 hex characters)
 * guid:03000000c82d00000b31000000010000 = switch
 * # vendor:product, hexadecimal
 * 2dc8:310b = xbox_360
 * # case-insensitive substring of the controller name
 * name:Ultimate = switch
 * </pre>
 *
 * <p>Precedence is {@code guid:} &gt; {@code name:} &gt; {@code vid:pid}. A matching rule
 * with the value {@code auto} means "no override" and stops the search, so a pad can be
 * opted out of a broader rule.</p>
 *
 * <p>The class is deliberately free of Minecraft, LWJGL and SLF4J: it only needs the JDK,
 * so {@code tools/VerifyLayout.java} exercises it outside the game. Problems found while
 * reading the file are collected in {@link #warnings()} for the caller to log.</p>
 */
public final class LayoutConfig {
	/** File name inside the game's {@code config} directory. */
	public static final String FILE_NAME = "eightbitdo.properties";

	/** Default location, relative to the game working directory. */
	public static final Path DEFAULT_PATH = Paths.get("config", FILE_NAME);

	/** Value that means "use BTA's own controller type". */
	public static final String AUTO = "auto";

	/** Every value the file (and {@link #setGuid}) accepts, in documentation order. */
	public static final List<String> LAYOUTS = List.of(
			AUTO, "xbox_360", "xbox_one", "xinput", "ps3", "ps4", "ps5", "switch", "steam", "steam_deck");

	private static final String GUID_PREFIX = "guid:";
	private static final String NAME_PREFIX = "name:";

	private static final String HEADER = """
			# 8BitDo Controller Support - per-device button layout
			#
			# One rule per line: <key> = <layout>
			#
			#   guid:03000000c82d00000b31000000010000 = switch   exact GLFW GUID
			#   2dc8:310b = xbox_360                              vendor:product, hexadecimal
			#   name:Ultimate = switch                            substring of the controller name
			#
			# Layouts: auto, xbox_360, xbox_one, xinput, ps3, ps4, ps5, switch, steam, steam_deck
			# Precedence: guid: > name: > vid:pid. "auto", or no matching line at all, means
			# BTA's own database decides (for most 8BitDo pads that is the Xbox layout).
			#
			# This changes the button labels BTA draws, never how input is mapped.
			#
			""";

	private static volatile LayoutConfig instance;

	private final Path path;
	private final Map<String, Entry> entries = new LinkedHashMap<>();
	private final List<String> warnings = new ArrayList<>();
	private volatile boolean loaded;

	/** Uses {@link #DEFAULT_PATH}. */
	public LayoutConfig() {
		this(DEFAULT_PATH);
	}

	/** Uses an explicit file, for tests. */
	public LayoutConfig(Path path) {
		this.path = path;
	}

	/** The shared instance the game uses, loaded lazily. */
	public static LayoutConfig get() {
		LayoutConfig config = instance;
		if (config == null) {
			synchronized (LayoutConfig.class) {
				config = instance;
				if (config == null) {
					config = new LayoutConfig(DEFAULT_PATH);
					instance = config;
				}
			}
		}
		return config;
	}

	/** True if the string is one of {@link #LAYOUTS}. */
	public static boolean isKnownLayout(String value) {
		return value != null && LAYOUTS.contains(value.trim().toLowerCase(Locale.ROOT));
	}

	/**
	 * Resolves the layout for a device.
	 *
	 * @param guid GLFW GUID, may be {@code null}
	 * @param vid  USB vendor id
	 * @param pid  USB product id
	 * @param name controller name, may be {@code null}
	 * @return a lowercase layout name, or {@code null} for AUTO
	 */
	public String resolve(String guid, int vid, int pid, String name) {
		ensureLoaded();

		if (isHexGuid(guid)) {
			Entry byGuid = entries.get(GUID_PREFIX + guid.toLowerCase(Locale.ROOT));
			if (byGuid != null) {
				return byGuid.auto() ? null : byGuid.value();
			}
		}

		if (name != null && !name.isBlank()) {
			String haystack = name.toLowerCase(Locale.ROOT);
			for (Map.Entry<String, Entry> candidate : entries.entrySet()) {
				if (!candidate.getKey().startsWith(NAME_PREFIX)) {
					continue;
				}
				String needle = candidate.getKey().substring(NAME_PREFIX.length());
				if (!needle.isEmpty() && haystack.contains(needle)) {
					return candidate.getValue().auto() ? null : candidate.getValue().value();
				}
			}
		}

		String vidPid = vidPidKey(vid, pid);
		if (vidPid != null) {
			Entry byVidPid = entries.get(vidPid);
			if (byVidPid != null) {
				return byVidPid.auto() ? null : byVidPid.value();
			}
		}

		return null;
	}

	/**
	 * Sets or removes the per-GUID override.
	 *
	 * @param valueOrNull a value from {@link #LAYOUTS} (including {@code auto}), or
	 *                    {@code null}/blank to remove the line entirely
	 */
	public void setGuid(String guid, String valueOrNull) {
		ensureLoaded();
		if (!isHexGuid(guid)) {
			throw new IllegalArgumentException("Not a 32 character hexadecimal GUID: " + guid);
		}
		String key = GUID_PREFIX + guid.toLowerCase(Locale.ROOT);
		if (valueOrNull == null || valueOrNull.isBlank() || AUTO.equalsIgnoreCase(valueOrNull.trim())) {
			entries.remove(key);
			return;
		}
		String value = valueOrNull.trim().toLowerCase(Locale.ROOT);
		if (!LAYOUTS.contains(value)) {
			throw new IllegalArgumentException("Unknown layout: " + valueOrNull);
		}
		entries.put(key, new Entry(key, value));
	}

	/** Writes the file atomically, header first, then every entry in file order. */
	public void save() {
		ensureLoaded();
		StringBuilder out = new StringBuilder(HEADER);
		for (Entry entry : entries.values()) {
			out.append(entry.rawKey()).append(" = ").append(entry.value()).append('\n');
		}
		try {
			Path parent = path.toAbsolutePath().getParent();
			if (parent != null) {
				Files.createDirectories(parent);
			}
			Path temp = path.resolveSibling(path.getFileName() + ".tmp");
			Files.writeString(temp, out, StandardCharsets.UTF_8);
			try {
				Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (AtomicMoveNotSupportedException e) {
				Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException e) {
			throw new UncheckedIOException("Could not save " + path, e);
		}
	}

	/** Lines that were ignored while reading the file (bad key, unknown layout, ...). */
	public List<String> warnings() {
		ensureLoaded();
		return Collections.unmodifiableList(new ArrayList<>(warnings));
	}

	/** The file this instance reads and writes. */
	public Path path() {
		return path;
	}

	/** True if the file was found and read. */
	public boolean exists() {
		ensureLoaded();
		return Files.isRegularFile(path);
	}

	private void ensureLoaded() {
		if (loaded) {
			return;
		}
		synchronized (this) {
			if (loaded) {
				return;
			}
			load();
			loaded = true;
		}
	}

	private void load() {
		entries.clear();
		warnings.clear();
		if (!Files.isRegularFile(path)) {
			return;
		}
		List<String> lines;
		try {
			lines = Files.readAllLines(path, StandardCharsets.UTF_8);
		} catch (IOException e) {
			warnings.add("Could not read " + path + ": " + e.getMessage());
			return;
		}
		for (String line : lines) {
			parseLine(line);
		}
	}

	private void parseLine(String line) {
		String trimmed = line.trim();
		if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith(";") || trimmed.startsWith("!")) {
			return;
		}
		int equals = trimmed.indexOf('=');
		if (equals < 0) {
			warnings.add("Ignoring line without '=': " + trimmed);
			return;
		}
		String rawKey = trimmed.substring(0, equals).trim();
		String rawValue = trimmed.substring(equals + 1).trim();
		String key = normaliseKey(rawKey);
		if (key == null) {
			warnings.add("Ignoring unknown key: " + rawKey);
			return;
		}
		String value = rawValue.toLowerCase(Locale.ROOT);
		if (!LAYOUTS.contains(value)) {
			warnings.add("Ignoring unknown layout \"" + rawValue + "\" for " + rawKey);
			return;
		}
		entries.put(key, new Entry(rawKey, value));
	}

	/** Returns the lookup key, or {@code null} when the key is not understood. */
	private static String normaliseKey(String rawKey) {
		String lower = rawKey.toLowerCase(Locale.ROOT);
		if (lower.startsWith(GUID_PREFIX)) {
			String guid = lower.substring(GUID_PREFIX.length()).trim();
			return isHexGuid(guid) ? GUID_PREFIX + guid : null;
		}
		if (lower.startsWith(NAME_PREFIX)) {
			String name = rawKey.substring(NAME_PREFIX.length()).trim();
			return name.isEmpty() ? null : NAME_PREFIX + name.toLowerCase(Locale.ROOT);
		}
		if (lower.matches("[0-9a-f]{1,4}:[0-9a-f]{1,4}")) {
			String[] parts = lower.split(":");
			return vidPidKey(Integer.parseInt(parts[0], 16), Integer.parseInt(parts[1], 16));
		}
		return null;
	}

	private static String vidPidKey(int vid, int pid) {
		if (vid < 0 || vid > 0xffff || pid < 0 || pid > 0xffff) {
			return null;
		}
		return String.format(Locale.ROOT, "%04x:%04x", vid, pid);
	}

	private static boolean isHexGuid(String guid) {
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

	/** A parsed line; {@code rawKey} keeps the user's spelling for the next save. */
	private record Entry(String rawKey, String value) {
		boolean auto() {
			return AUTO.equals(value);
		}
	}
}
