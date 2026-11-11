/*
 * Standalone tests for LayoutConfig: no Minecraft, no LWJGL, no SLF4J.
 *
 *   tools/verify.fish      -- compiles and runs this together with the mod classes
 *
 * Exercises parsing (comments, case-insensitivity, invalid values), precedence
 * (guid: > name: > vid:pid), the "auto" opt-out, setGuid/save round-trips and the
 * default path. Exit code 0 = pass, 1 = failure.
 */

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import pl.psampir.bta.eightbitdo.LayoutConfig;

public final class VerifyLayout {
	private static final String GUID = "03000000c82d00000b31000000010000";
	private static final int VID = 0x2dc8;
	private static final int PID = 0x310b;
	private static final String NAME = "8BitDo Ultimate 2";

	private static int checks;
	private static boolean failed;

	public static void main(String[] args) throws Exception {
		Path dir = Files.createTempDirectory("eightbitdo-layout");
		Path file = dir.resolve("config/eightbitdo.properties");

		System.out.println("== default path");
		check(LayoutConfig.DEFAULT_PATH.equals(Paths.get("config", "eightbitdo.properties")),
				"default path is config/eightbitdo.properties");

		System.out.println();
		System.out.println("== missing file");
		LayoutConfig missing = new LayoutConfig(file);
		check(missing.resolve(GUID, VID, PID, NAME) == null, "missing file resolves to AUTO");
		check(!Files.exists(file), "loading does not create the file");

		System.out.println();
		System.out.println("== parsing: comments, case, precedence");
		Files.createDirectories(file.getParent());
		write(file, """
				# comment
				; also a comment
				! and another
				GUID:03000000C82D00000B31000000010000 = SWITCH
				name:ultimate = Xbox_360
				2DC8:310B = xinput
				""");
		LayoutConfig parsed = new LayoutConfig(file);
		check("switch".equals(parsed.resolve(GUID, VID, PID, NAME)),
				"guid: beats name: and vid:pid (values lowercased)");
		check(parsed.warnings().isEmpty(), "no warnings for a valid file");
		check("switch".equals(parsed.resolve(GUID.toUpperCase(), VID, PID, "unrelated")),
				"GUID lookup is case-insensitive");
		check("switch".equals(parsed.resolve(GUID, 0xffff, 0xffff, null)),
				"guid: matches even with unknown vid/pid and no name");
		check("xinput".equals(parsed.resolve("01000000000000000000000000000000", VID, PID, "unknown pad")),
				"non-matching guid falls through to vid:pid");

		System.out.println();
		System.out.println("== precedence without the guid rule");
		write(file, """
				name:ultimate = Xbox_360
				2dc8:310b = xinput
				""");
		LayoutConfig noGuid = new LayoutConfig(file);
		check("xbox_360".equals(noGuid.resolve(GUID, VID, PID, NAME)), "name: beats vid:pid");
		check("xbox_360".equals(noGuid.resolve(GUID, VID, PID, "my 8bitdo ULTIMATE 2 pad")),
				"name: matches a case-insensitive substring");
		check("xinput".equals(noGuid.resolve(GUID, VID, PID, "8BitDo Pro 2")),
				"vid:pid used when the name does not match");
		check(noGuid.resolve(GUID, 0x1234, 0x5678, "8BitDo Pro 2") == null,
				"no rule at all resolves to AUTO");

		System.out.println();
		System.out.println("== invalid entries are ignored and reported");
		write(file, """
				guid:03000000c82d00000b31000000010000 = bogus
				controller:foo = switch
				name:Ultimate
				2dc8:310b = xinput
				""");
		LayoutConfig invalid = new LayoutConfig(file);
		check("xinput".equals(invalid.resolve(GUID, VID, PID, NAME)),
				"invalid value for the guid rule falls through to vid:pid");
		check(invalid.warnings().size() == 3,
				"three warnings for bad value, unknown key and missing '=' (got "
						+ invalid.warnings().size() + ")");
		for (String warning : invalid.warnings()) {
			System.out.println("    warning: " + warning);
		}

		System.out.println();
		System.out.println("== auto opts a pad out of a broader rule");
		write(file, """
				guid:03000000c82d00000b31000000010000 = auto
				name:Ultimate = switch
				""");
		LayoutConfig auto = new LayoutConfig(file);
		check(auto.resolve(GUID, VID, PID, NAME) == null,
				"guid = auto stops the search instead of applying name:");

		System.out.println();
		System.out.println("== setGuid, save and reload");
		write(file, """
				name:Ultimate = xbox_360
				2dc8:310b = xinput
				""");
		LayoutConfig config = new LayoutConfig(file);
		config.setGuid(GUID, "switch");
		config.save();
		check(Files.exists(file), "save() creates the file");
		List<String> saved = Files.readAllLines(file, StandardCharsets.UTF_8);
		check(saved.stream().anyMatch(line -> line.startsWith("# ")), "save() writes a header");
		check(saved.stream().anyMatch(line -> line.equals("guid:" + GUID + " = switch")),
				"save() writes the guid override");
		check(saved.stream().anyMatch(line -> line.equals("name:Ultimate = xbox_360")),
				"save() keeps the user's other entries and their spelling");
		check(saved.stream().noneMatch(line -> line.contains(".tmp")), "no temp file leaks into the content");

		LayoutConfig reloaded = new LayoutConfig(file);
		check("switch".equals(reloaded.resolve(GUID, VID, PID, NAME)),
				"saved override survives a reload");

		System.out.println();
		System.out.println("== setGuid(null) removes the override");
		reloaded.setGuid(GUID, null);
		reloaded.save();
		LayoutConfig afterRemoval = new LayoutConfig(file);
		check("xbox_360".equals(afterRemoval.resolve(GUID, VID, PID, NAME)),
				"after removal the broader name: rule applies again");
		check(Files.readAllLines(file).stream().noneMatch(line -> line.startsWith("guid:")),
				"the guid line is gone from the file");

		System.out.println();
		System.out.println("== setGuid validation");
		boolean rejectedGuid = false;
		try {
			afterRemoval.setGuid("not-a-guid", "switch");
		} catch (IllegalArgumentException e) {
			rejectedGuid = true;
		}
		check(rejectedGuid, "setGuid rejects a malformed GUID");
		boolean rejectedValue = false;
		try {
			afterRemoval.setGuid(GUID, "gamecube");
		} catch (IllegalArgumentException e) {
			rejectedValue = true;
		}
		check(rejectedValue, "setGuid rejects an unknown layout");

		System.out.println();
		System.out.println("== " + checks + " checks, " + (failed ? "FAIL" : "PASS"));
		System.exit(failed ? 1 : 0);
	}

	private static void write(Path file, String content) throws Exception {
		Files.createDirectories(file.getParent());
		Files.writeString(file, content, StandardCharsets.UTF_8);
	}

	private static void check(boolean condition, String what) {
		checks++;
		if (condition) {
			System.out.println("  ok   " + what);
		} else {
			failed = true;
			System.out.println("  FAIL " + what);
		}
	}
}
