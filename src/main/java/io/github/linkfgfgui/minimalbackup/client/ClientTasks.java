package io.github.linkfgfgui.minimalbackup.client;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** Tiny client-tick scheduler used to hide the UI for a cover screenshot and reopen it. */
public final class ClientTasks {
    private static final List<Entry> TASKS = new ArrayList<>();

    private ClientTasks() {
    }

    public static void after(int ticks, Runnable action) {
        TASKS.add(new Entry(Math.max(0, ticks), action));
    }

    public static void tick() {
        if (TASKS.isEmpty()) {
            return;
        }
        Iterator<Entry> iterator = TASKS.iterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next();
            if (--entry.ticks <= 0) {
                iterator.remove();
                entry.action.run();
            }
        }
    }

    private static final class Entry {
        private int ticks;
        private final Runnable action;

        private Entry(int ticks, Runnable action) {
            this.ticks = ticks;
            this.action = action;
        }
    }
}
