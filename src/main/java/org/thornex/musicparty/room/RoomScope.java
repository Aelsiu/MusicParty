package org.thornex.musicparty.room;

import org.springframework.beans.factory.ObjectFactory;
import org.springframework.beans.factory.config.Scope;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class RoomScope implements Scope {
    private static class Runtime {
        final Map<String, Object> beans = new LinkedHashMap<>();
        final Map<String, Runnable> destroy = new LinkedHashMap<>();
        boolean closed;
    }
    private final Map<String, Runtime> rooms = new ConcurrentHashMap<>();
    private final Set<String> deleted = ConcurrentHashMap.newKeySet();
    private final ThreadLocal<String> destroying = new ThreadLocal<>();
    @Override public Object get(String name, ObjectFactory<?> factory) {
        String id = RoomContext.require();
        if (deleted.contains(id)) throw new IllegalStateException("Room has been deleted");
        Runtime runtime = rooms.computeIfAbsent(id, k -> new Runtime());
        synchronized (runtime) {
            if (deleted.contains(id) || (runtime.closed && !id.equals(destroying.get()))) throw new IllegalStateException("Room has been deleted");
            Object existing = runtime.beans.get(name);
            if (existing != null) return existing;
            if (runtime.closed) throw new IllegalStateException("Room has been deleted");
            Object bean = factory.getObject();
            runtime.beans.put(name, bean);
            return bean;
        }
    }
    @Override public Object remove(String name) {
        Runtime r = rooms.get(RoomContext.require());
        if (r == null) return null;
        synchronized (r) { r.destroy.remove(name); return r.beans.remove(name); }
    }
    @Override public void registerDestructionCallback(String name, Runnable callback) {
        Runtime r = rooms.get(RoomContext.require());
        synchronized (r) { r.destroy.put(name, callback); }
    }
    @Override public Object resolveContextualObject(String key) { return "roomId".equals(key) ? RoomContext.current() : null; }
    @Override public String getConversationId() { return RoomContext.require(); }
    public Set<String> activeIds() { return Set.copyOf(rooms.keySet()); }
    public boolean active(String id) { Runtime r=rooms.get(id);return !deleted.contains(id) && (r==null || !r.closed); }
    public void destroy(String id) {
        Runtime r = rooms.get(id);
        List<Runnable> callbacks = new ArrayList<>();
        if (r != null) synchronized (r) {
            if (r.closed) return;
            r.closed = true; callbacks.addAll(r.destroy.values());
        }
        // Run destructors outside the bean map lock, services can finish in-flight work.
        Collections.reverse(callbacks);
        destroying.set(id);
        try (var ignored = RoomContext.enter(id)) {
            for (Runnable callback : callbacks) { try { callback.run(); } catch (RuntimeException ignoredError) { /* Finish remaining cleanup. */ } }
        } finally {
            destroying.remove(); deleted.add(id);
            if (r != null) synchronized (r) { r.beans.clear(); r.destroy.clear(); }
            rooms.remove(id);
        }
    }
    @jakarta.annotation.PreDestroy public void shutdown() { for (String id : activeIds()) destroy(id); }
}
