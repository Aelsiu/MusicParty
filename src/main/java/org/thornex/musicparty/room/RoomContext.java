package org.thornex.musicparty.room;

import java.util.function.Supplier;

/** Explicit room boundary for servlet, STOMP, Reactor and background work. */
public final class RoomContext implements AutoCloseable {
    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();
    private final String previous;
    private RoomContext(String id) { previous = CURRENT.get(); if (id == null) CURRENT.remove(); else CURRENT.set(id); }
    public static RoomContext enter(String id) { return new RoomContext(id); }
    public static String current() { return CURRENT.get(); }
    public static String require() {
        String id = current();
        if (id == null) throw new IllegalStateException("Room context is required");
        return id;
    }
    public static String topic(String suffix) { return "/topic/rooms/" + require() + suffix; }
    public static Runnable capture(Runnable task) {
        String id = current();
        return () -> { try (var ignored = enter(id)) { task.run(); } };
    }
    public static <T> T call(String id, Supplier<T> task) { try (var ignored = enter(id)) { return task.get(); } }
    @Override public void close() { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); }
}
