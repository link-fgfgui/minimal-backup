package io.github.linkfgfgui.minimalbackup;

import io.github.linkfgfgui.minimalbackup.engine.Log;
import java.nio.file.Path;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Loader-independent mod bootstrap.
 *
 * <p>The platform entry points resolve the game directory and hand it to this class. The engine
 * itself never guesses paths: the backup root is always {@code <gameDir>/mbackups}.
 *
 * <p>The engine also receives its logger here: {@link Log} is a port, and this class plugs in the
 * Log4j2 backend Minecraft already configures. No JDK logging (JUL) is involved.
 */
public final class MinimalBackup {
    public static final String MOD_ID = "minimalbackup";
    public static final String VERSION = /*$ mod_version*/ "0.1.0";
    public static final String MINECRAFT = /*$ minecraft*/ "1.21.1";

    private static Path gameDir;

    private MinimalBackup() {
    }

    public static void init(Path resolvedGameDir) {
        if (gameDir != null) {
            return;
        }
        gameDir = resolvedGameDir.toAbsolutePath().normalize();
        installLogSink();
    }

    /** Hands the engine the game's Log4j2 logger; called before anything logs. */
    private static void installLogSink() {
        Logger delegate = LogManager.getLogger(MOD_ID);
        Log.install((level, message) -> {
            switch (level) {
                case ERROR -> delegate.error(message);
                case WARN -> delegate.warn(message);
                case DEBUG -> delegate.debug(message);
                default -> delegate.info(message);
            }
        });
    }

    /**
     * Game directory resolved by the platform entry point. Dedicated servers read level.dat before
     * mod constructors run, so a process working-directory fallback is required for that early
     * window; both the client and the dedicated server run with the game directory as CWD.
     */
    public static Path gameDir() {
        if (gameDir == null) {
            String userDir = System.getProperty("user.dir");
            if (userDir != null && !userDir.isEmpty()) {
                gameDir = Path.of(userDir).toAbsolutePath().normalize();
            }
        }
        if (gameDir == null) {
            throw new IllegalStateException("MinimalBackup has not been initialised by a loader entry point");
        }
        return gameDir;
    }
}
