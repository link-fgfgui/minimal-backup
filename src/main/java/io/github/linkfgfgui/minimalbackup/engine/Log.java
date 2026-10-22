package io.github.linkfgfgui.minimalbackup.engine;

/**
 * Logging port for the engine. The engine never picks a backend: the platform installs a
 * {@link Sink} (Log4j2 inside the game, stderr in the CLI). Messages use SLF4J-style "{}"
 * placeholders.
 */
public final class Log {
    public enum Level {
        DEBUG, INFO, WARN, ERROR
    }

    /** Installed by the platform entry point before the engine logs anything. */
    public interface Sink {
        void log(Level level, String message);
    }

    private static final Sink SILENT = (level, message) -> {
    };

    private static volatile Sink sink = SILENT;

    public static final Logger LOGGER = new Logger();

    private Log() {
    }

    public static void install(Sink newSink) {
        sink = newSink == null ? SILENT : newSink;
    }

    public static final class Logger {
        public void warn(String message, Object... args) {
            log(Level.WARN, message, args);
        }

        public void info(String message, Object... args) {
            log(Level.INFO, message, args);
        }

        public void debug(String message, Object... args) {
            log(Level.DEBUG, message, args);
        }

        public void error(String message, Object... args) {
            log(Level.ERROR, message, args);
        }

        private void log(Level level, String message, Object... args) {
            sink.log(level, format(message, args));
        }
    }

    static String format(String message, Object... args) {
        if (message == null || args == null || args.length == 0) {
            return message;
        }
        StringBuilder out = new StringBuilder(message.length() + 16);
        int arg = 0;
        int index = 0;
        while (index < message.length()) {
            int placeholder = message.indexOf("{", index);
            if (placeholder < 0 || arg >= args.length) {
                out.append(message, index, message.length());
                break;
            }
            out.append(message, index, placeholder);
            out.append(args[arg++]);
            index = placeholder + 2;
        }
        return out.toString();
    }
}
