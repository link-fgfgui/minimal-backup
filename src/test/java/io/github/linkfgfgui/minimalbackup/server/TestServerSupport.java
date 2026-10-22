package io.github.linkfgfgui.minimalbackup.server;

import java.util.Objects;

/** Minimal assertion helpers for the hook-side tests (engine tests have their own). */
final class TestServerSupport {
    private TestServerSupport() {
    }

    static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    static void eq(Object expected, Object actual, String message) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(message + ": expected=" + expected + " actual=" + actual);
        }
    }

    static void eq(long expected, long actual, String message) {
        if (expected != actual) {
            throw new AssertionError(message + ": expected=" + expected + " actual=" + actual);
        }
    }

    static void isNull(Object value, String message) {
        if (value != null) {
            throw new AssertionError(message + ": expected null, got " + value);
        }
    }
}
