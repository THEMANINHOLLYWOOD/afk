package dev.afk;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayDeque;

/** Small ring buffer of things the brain should hear about (Baritone output, damage, deaths). */
final class EventLog {
    private record Entry(long id, long timeMs, String kind, String text) {}

    private final ArrayDeque<Entry> entries = new ArrayDeque<>();
    private final int capacity;
    private long nextId = 1;

    EventLog(int capacity) {
        this.capacity = capacity;
    }

    synchronized void add(String kind, String text) {
        if (text == null || text.isBlank()) return;
        String clipped = text.length() > 300 ? text.substring(0, 300) + "..." : text;
        long now = System.currentTimeMillis();
        Entry last = entries.peekLast();
        // Baritone repeats some lines every tick; keep one copy.
        if (last != null && last.kind.equals(kind) && last.text.equals(clipped) && now - last.timeMs < 3000) return;
        entries.addLast(new Entry(nextId++, now, kind, clipped));
        while (entries.size() > capacity) entries.removeFirst();
    }

    /** Events with id greater than sinceId, plus the newest id so the caller can ask for "after this" next time. */
    synchronized JsonObject since(long sinceId) {
        long now = System.currentTimeMillis();
        JsonArray arr = new JsonArray();
        for (Entry e : entries) {
            if (e.id <= sinceId) continue;
            JsonObject o = new JsonObject();
            o.addProperty("id", e.id);
            o.addProperty("secondsAgo", Math.round((now - e.timeMs) / 1000.0));
            o.addProperty("kind", e.kind);
            o.addProperty("text", e.text);
            arr.add(o);
        }
        JsonObject out = new JsonObject();
        out.add("events", arr);
        out.addProperty("lastEventId", nextId - 1);
        return out;
    }
}
