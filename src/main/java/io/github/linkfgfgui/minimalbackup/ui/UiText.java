package io.github.linkfgfgui.minimalbackup.ui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a {@link BackupResponse} into localized lines for the GUI. The command layer keeps
 * printing {@code response.message()} as chat text; the GUI renders these lines instead, so the
 * same service result reads as prose in both places.
 *
 * <p>No Minecraft types here: the ui package is shared by every loader and by the headless tests.
 * The GUI resolves each key with {@code Component.translatable(key, args)}.
 */
public final class UiText {
    /** {@code key=123} pairs the service puts in its messages, e.g. {@code targets=979}. */
    private static final Pattern PAIR = Pattern.compile("([a-zA-Z]+)=(-?\\d+)");

    private UiText() {
    }

    /** One status line: a translation key plus args, or a literal string when {@code literal}. */
    public record Line(String key, List<Object> args, boolean error, boolean literal) {
        public Line {
            key = key == null ? "" : key;
            args = args == null ? List.of() : List.copyOf(args);
        }
    }

    public static Line key(String key, Object... args) {
        return new Line(key, List.of(args), false, false);
    }

    public static Line error(String key, Object... args) {
        return new Line(key, List.of(args), true, false);
    }

    public static Line literal(String text, boolean error) {
        return new Line("", List.of(text), error, true);
    }

    /** Localized status lines for one request/response pair. */
    public static List<Line> lines(BackupRequest.Action action, String slot, BackupResponse response) {
        List<Line> out = new ArrayList<>();
        if (!response.ok()) {
            switch (action) {
                case VERIFY -> verify(out, slot, response);
                case BASELINE -> baseline(out, response);
                default -> {
                    out.add(error("minimalbackup.ui.line.failed"));
                    if (!response.message().isEmpty()) {
                        out.add(literal(response.message(), true));
                    }
                }
            }
            return out;
        }
        switch (action) {
            case LIST -> out.add(key("minimalbackup.ui.line.list", response.snapshots().size()));
            case CREATE -> out.add(key("minimalbackup.ui.line.created"));
            case RESTORE -> out.add(key("minimalbackup.ui.line.restore", slot));
            case DELETE -> out.add(key("minimalbackup.ui.line.deleted", slot));
            case RENAME -> out.add(key("minimalbackup.ui.line.renamed", slot));
            case VERIFY -> verify(out, slot, response);
            case GC -> compact(out, response);
            case BASELINE -> baseline(out, response);
            case DISARM -> out.add(key("minimalbackup.ui.line.disarm"));
            case STATUS -> split(out, response.message());
            default -> split(out, response.message());
        }
        return out;
    }

    private static void verify(List<Line> out, String slot, BackupResponse response) {
        Map<String, String> stats = pairs(response.message());
        String targets = stats.get("targets");
        String absent = stats.get("absent");
        String failures = stats.get("failures");
        if (targets != null && absent != null && failures != null) {
            out.add(response.ok()
                    ? key("minimalbackup.ui.line.verify_ok", slot, targets, absent)
                    : error("minimalbackup.ui.line.verify_fail", slot, failures));
        } else {
            out.add(response.ok()
                    ? key("minimalbackup.ui.line.verify_ok_plain", slot)
                    : error("minimalbackup.ui.line.verify_fail_plain", slot));
        }
        // Per-file errors follow the summary, indented by the service.
        for (String detail : response.message().split("\n")) {
            String trimmed = detail.trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("Verified ")) {
                out.add(literal(trimmed, true));
            }
        }
    }

    private static void compact(List<Line> out, BackupResponse response) {
        Map<String, String> stats = pairs(response.message());
        String files = stats.get("files");
        String compacted = stats.get("compacted");
        String deleted = stats.get("deleted");
        if (files != null && compacted != null && deleted != null) {
            out.add(key("minimalbackup.ui.line.gc", files, compacted, deleted));
        } else {
            out.add(literal(response.message(), false));
        }
    }

    private static void baseline(List<Line> out, BackupResponse response) {
        Map<String, String> stats = pairs(response.message());
        String regions = stats.get("regions");
        String captured = stats.get("filesCaptured");
        String failures = stats.get("failures");
        if (regions != null && captured != null && failures != null) {
            out.add(response.ok()
                    ? key("minimalbackup.ui.line.baseline", regions, captured, failures)
                    : error("minimalbackup.ui.line.baseline_failed", failures));
        } else {
            out.add(literal(response.message(), !response.ok()));
        }
    }

    private static void split(List<Line> out, String message) {
        for (String line : message.split("\n")) {
            if (!line.isBlank()) {
                out.add(literal(line.trim(), false));
            }
        }
    }

    private static Map<String, String> pairs(String message) {
        Map<String, String> out = new HashMap<>();
        Matcher matcher = PAIR.matcher(message);
        while (matcher.find()) {
            out.put(matcher.group(1), matcher.group(2));
        }
        return out;
    }
}
