#!/usr/bin/env fish
#
# Compiles the mod's Minecraft-free classes together with the verification harnesses,
# checks the mixin targets in BTA with javap, runs the LayoutConfig tests, and finally
# exercises the mapping code against the controller attached to this machine.
#
# Nothing is written outside the project: classes land in tools/build/ (gitignored).
#
#   tools/verify.fish
#
# Requirements: a Java 17+ JDK and PrismLauncher's libraries directory (LWJGL 3.3.3,
# natives, slf4j-api). Set PRISM_LIBS to override the default location.

set -l root (path resolve (status dirname)/..)
set -l classes $root/tools/build/classes

set -l prism_libs $PRISM_LIBS
if test -z "$prism_libs"
    set prism_libs $HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/libraries
end

set -l java $JAVA_HOME/bin/java
set -l javac $JAVA_HOME/bin/javac
if not test -x "$javac"
    # Fall back to the Temurin JDKs the PrismLauncher flatpak already downloaded.
    for bin in $HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java/java-runtime-gamma/bin \
        $HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java/java-runtime-delta/bin
        if test -x "$bin/javac"
            set java $bin/java
            set javac $bin/javac
            break
        end
    end
end
if not test -x "$javac"
    set java (command -s java)
    set javac (command -s javac)
end
if test -z "$javac"; or not test -x "$javac"
    echo "verify: no JDK found, set JAVA_HOME" >&2
    exit 1
end

set -l lwjgl_ver 3.3.3
set -l lwjgl_jars \
    $prism_libs/org/lwjgl/lwjgl/$lwjgl_ver/lwjgl-$lwjgl_ver.jar \
    $prism_libs/org/lwjgl/lwjgl-glfw/$lwjgl_ver/lwjgl-glfw-$lwjgl_ver.jar \
    $prism_libs/org/lwjgl/lwjgl-glfw-natives-linux/$lwjgl_ver/lwjgl-glfw-natives-linux-$lwjgl_ver.jar \
    $prism_libs/org/lwjgl/lwjgl-natives-linux/$lwjgl_ver/lwjgl-natives-linux-$lwjgl_ver.jar

set -l slf4j (path filter -f $prism_libs/org/slf4j/slf4j-api/*/slf4j-api-*.jar | sort -V | tail -n 1)

for jar in $lwjgl_jars
    if not test -f $jar
        echo "verify: missing $jar" >&2
        exit 1
    end
end
if test -z "$slf4j"
    echo "verify: no slf4j-api jar under $prism_libs/org/slf4j" >&2
    exit 1
end

set -l classpath (string join : $lwjgl_jars)
set -l run_classpath $classpath:$slf4j:$classes:$root/src/main/resources

set -l prism_data $HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher
set -l bta_jar $BTA_JAR
if test -z "$bta_jar"
    # Prism keeps the jar mod in the global libraries now; older instances had a
    # jarmods folder, and the patched minecraft.jar also contains the BTA classes.
    for candidate in \
        "$prism_data/libraries/net/betterthanadventure/bta-client/v8.0.1/bta-client-v8.0.1-client.jar" \
        "$prism_data/instances/BTA 8.0.1/jarmods/bta-v8.0.1.jar" \
        "$prism_data/instances/BTA 8.0.1/minecraft/bin/minecraft.jar" \
        "$prism_data/instances/BTA/jarmods/bta-v8.0.1.jar"
        if test -f "$candidate"
            set bta_jar "$candidate"
            break
        end
    end
end
if test -f "$bta_jar"
    set -gx BTA_JAR "$bta_jar"
    echo "verify: BTA database from $bta_jar"
else
    echo "verify: no BTA jar found (set BTA_JAR to include the 'BTA database afterwards' step)"
end

mkdir -p $classes
find $classes -name '*.class' -delete 2>/dev/null

if test -n "$BTA_JAR"
    set -l javap (path dirname $javac)/javap
    echo "verify: checking mixin targets in BTA"
    set -l members ($javap -p -classpath "$BTA_JAR" net.minecraft.client.input.InputHandler 2>/dev/null)
    if not string match -q -r "static void initGLFW\(" "$members"
        echo "verify: InputHandler no longer declares static initGLFW -- InputHandlerMixin target is stale" >&2
        exit 1
    end
    if not string match -q -r "static void poll\(\)" "$members"
        echo "verify: InputHandler no longer declares static poll() -- InputHandlerMixin target is stale" >&2
        exit 1
    end
    echo "  InputHandler.initGLFW(Minecraft) and InputHandler.poll() found"

    set -l controller_type ($javap -p -classpath "$BTA_JAR" net.minecraft.client.input.controller.ControllerType 2>/dev/null)
    if not string match -q -r "static .*fromController\(net\.minecraft\.client\.input\.glfw\.GlfwGamepad\)" "$controller_type"
        echo "verify: ControllerType no longer declares static fromController(GlfwGamepad) -- ControllerTypeMixin target is stale" >&2
        exit 1
    end
    echo "  ControllerType.fromController(GlfwGamepad) found"

    set -l setup_screen ($javap -p -classpath "$BTA_JAR" net.minecraft.client.gui.ScreenControllerSetup 2>/dev/null)
    if not string match -q -r "public void init\(\)" "$setup_screen"
        echo "verify: ScreenControllerSetup no longer declares init() -- ScreenControllerSetupMixin target is stale" >&2
        exit 1
    end
    if not string match -q -r "public void tick\(\)" "$setup_screen"
        echo "verify: ScreenControllerSetup no longer declares tick() -- ScreenControllerSetupMixin target is stale" >&2
        exit 1
    end
    echo "  ScreenControllerSetup.init() and ScreenControllerSetup.tick() found -- mixin targets valid"
end

echo "verify: compiling layout config + its tests"
$javac -encoding UTF-8 -Xlint:all -d $classes \
    $root/src/main/java/pl/psampir/bta/eightbitdo/LayoutConfig.java \
    $root/tools/VerifyLayout.java
or exit 1

echo "verify: running layout tests"
$java -cp $classes VerifyLayout
or exit 1

echo "verify: compiling mod classes + harness"
$javac -encoding UTF-8 -Xlint:all -d $classes -cp $classpath:$slf4j \
    $root/src/main/java/pl/psampir/bta/eightbitdo/MappingStore.java \
    $root/src/main/java/pl/psampir/bta/eightbitdo/EightBitDoSupport.java \
    $root/tools/VerifyMappings.java
or exit 1

echo "verify: running harness"
$java -cp $run_classpath VerifyMappings
