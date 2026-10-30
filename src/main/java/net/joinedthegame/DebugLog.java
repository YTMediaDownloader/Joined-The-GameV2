package net.joinedthegame;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * The bot debug log: what every bot is doing, every decision and every goal switch, in its own
 * file ({@code logs/joinedthegame-debug.log}, the previous run's kept as
 * {@code joinedthegame-debug-previous.log}) so it can be sent straight to whoever's fixing things.
 * <p>
 * Only ever on in the testing jar ({@code joinedthegame-x.y.z-debug.jar}, which carries a marker
 * file) and on dev runs (the {@code joinedthegame.debug} system property). The normal jar has
 * none of it: no log, no debug command, nothing to switch on.
 */
public final class DebugLog {
    private DebugLog() {}

    private static final boolean DEV = Boolean.getBoolean("joinedthegame.debug");
    private static final boolean TESTING_JAR = DebugLog.class.getResource("/joinedthegame-debug.marker") != null;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private static BufferedWriter out;
    private static boolean failed;
    private static long lastFlush;

    /** Debug logging on? */
    public static boolean on() {
        return DEV || TESTING_JAR;
    }

    /** The testing (debug) jar is the one installed. */
    public static boolean testingJar() {
        return TESTING_JAR;
    }

    /** One line in the debug log (and in the main log too, on dev runs). */
    public static synchronized void write(String line) {
        if (DEV) JoinedTheGame.LOGGER.info(line);
        if (failed) return;
        try {
            if (out == null) open();
            out.write("[" + LocalTime.now().format(TIME) + "] " + line);
            out.newLine();
            long now = System.currentTimeMillis();
            if (now - lastFlush > 1000) {
                out.flush();
                lastFlush = now;
            }
        } catch (IOException e) {
            failed = true;
            JoinedTheGame.LOGGER.warn("Couldn't write the bot debug log: {}", e.toString());
        }
    }

    private static void open() throws IOException {
        Path dir = net.neoforged.fml.loading.FMLPaths.GAMEDIR.get().resolve("logs");
        Files.createDirectories(dir);
        Path file = dir.resolve("joinedthegame-debug.log");
        if (Files.exists(file)) Files.move(file, dir.resolve("joinedthegame-debug-previous.log"), StandardCopyOption.REPLACE_EXISTING);
        out = Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        out.write("=== Joined the Game debug log" + (TESTING_JAR ? " (testing jar)" : "") + " ===");
        out.newLine();
    }

    /** Server stopping: write out what's left. */
    public static synchronized void close() {
        if (out == null) return;
        try {
            out.flush();
            out.close();
        } catch (IOException ignored) {
        }
        out = null;
    }
}
