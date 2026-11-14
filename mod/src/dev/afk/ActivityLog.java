package dev.afk;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.event.client.player.ClientPlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;

/**
 * A short diary of what the human did while they had control, so Herobrine can pick up where
 * they left off. Similar actions are merged ("broke 14 oak_log near ..."). Kept on disk in
 * gameDir/afk/activity.json; it never leaves this computer except as context for your own agent.
 */
final class ActivityLog {
    private static final int CAPACITY = 150;
    private static final long MERGE_MS = 90_000;

    private static final class Entry {
        long id;
        long startMs;
        long endMs;
        String kind;
        String subject;
        int count;
        BlockPos pos;
        String text; // for free-form entries and loaded history
    }

    private final ArrayDeque<Entry> entries = new ArrayDeque<>();
    private long nextId = 1;
    private Map<String, Integer> inventoryAtStart;
    private BlockPos lastTravel;
    private int tick;

    void registerEvents() {
        ClientPlayerBlockBreakEvents.AFTER.register((level, player, pos, state) -> {
            if (!Afk.MODE.isAgent()) add("broke", StateSampler.shortId(BuiltInRegistries.BLOCK.getKey(state.getBlock())), pos);
        });
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (player instanceof LocalPlayer && !Afk.MODE.isAgent()) {
                ItemStack stack = player.getItemInHand(hand);
                if (stack.getItem() instanceof BlockItem item) {
                    add("placed", StateSampler.shortId(BuiltInRegistries.BLOCK.getKey(item.getBlock())), hit.getBlockPos().relative(hit.getDirection()));
                }
            }
            return InteractionResult.PASS;
        });
        ScreenEvents.AFTER_INIT.register((mc, screen, w, h) -> {
            if (!Afk.MODE.isAgent() && mc.player != null && screen instanceof AbstractContainerScreen<?> && !(screen instanceof InventoryScreen)) {
                add("opened", screen.getTitle().getString(), mc.player.blockPosition());
            }
        });
        ClientSendMessageEvents.CHAT.register(message -> {
            if (!Afk.MODE.isAgent()) note("chat", "said in chat: \"" + clip(message, 140) + "\"");
        });
        ClientSendMessageEvents.COMMAND.register(command -> {
            if (!Afk.MODE.isAgent() && !command.startsWith("do ") && !command.startsWith("afk")) note("command", "ran /" + clip(command, 100));
        });
    }

    void tick(Minecraft mc) {
        if (mc.player == null || Afk.MODE.isAgent()) return;
        if (++tick % 200 != 0) return; // every 10 s
        BlockPos here = mc.player.blockPosition();
        if (lastTravel == null) {
            lastTravel = here;
        } else if (Math.sqrt(here.distSqr(lastTravel)) > 24) {
            lastTravel = here;
            add("travel", "", here);
        }
    }

    synchronized void add(String kind, String subject, BlockPos pos) {
        long now = System.currentTimeMillis();
        Entry last = entries.peekLast();
        if (last != null && kind.equals(last.kind) && subject.equals(last.subject) && now - last.endMs < MERGE_MS
                && (pos == null || last.pos == null || Math.sqrt(pos.distSqr(last.pos)) < 32 || kind.equals("travel"))) {
            last.count++;
            last.endMs = now;
            if (pos != null) last.pos = pos;
            return;
        }
        Entry e = new Entry();
        e.id = nextId++;
        e.startMs = now;
        e.endMs = now;
        e.kind = kind;
        e.subject = subject;
        e.count = 1;
        e.pos = pos;
        entries.addLast(e);
        while (entries.size() > CAPACITY) entries.removeFirst();
    }

    synchronized void note(String kind, String text) {
        add(kind, text, null);
    }

    /** Called when the human takes control: remember what they carry, to report the difference later. */
    void beginPlayerSession(Minecraft mc) {
        if (mc.player == null) return;
        inventoryAtStart = counts(mc);
        lastTravel = mc.player.blockPosition();
    }

    /** Called when Herobrine takes control: summarise the inventory change since the human took over. */
    void handover(Minecraft mc) {
        if (mc.player == null || inventoryAtStart == null) return;
        Map<String, Integer> now = counts(mc);
        List<String> parts = new ArrayList<>();
        for (String name : new TreeSet<>(union(now, inventoryAtStart))) {
            int diff = now.getOrDefault(name, 0) - inventoryAtStart.getOrDefault(name, 0);
            if (diff != 0) parts.add((diff > 0 ? "+" : "") + diff + " " + name);
        }
        if (!parts.isEmpty()) note("inventory", "inventory change while the human played: " + String.join(", ", parts));
        inventoryAtStart = null;
    }

    private static java.util.Set<String> union(Map<String, Integer> a, Map<String, Integer> b) {
        java.util.Set<String> s = new java.util.HashSet<>(a.keySet());
        s.addAll(b.keySet());
        return s;
    }

    private static Map<String, Integer> counts(Minecraft mc) {
        Map<String, Integer> counts = new TreeMap<>();
        for (ItemStack stack : mc.player.getInventory().getNonEquipmentItems()) {
            if (!stack.isEmpty()) counts.merge(StateSampler.itemId(stack), stack.getCount(), Integer::sum);
        }
        return counts;
    }

    private static String describe(Entry e) {
        if (e.text != null) return e.text;
        String where = e.pos == null ? "" : " near " + e.pos.getX() + " " + e.pos.getY() + " " + e.pos.getZ();
        return switch (e.kind) {
            case "broke" -> "broke " + e.count + " " + e.subject + where;
            case "placed" -> "placed " + e.count + " " + e.subject + where;
            case "opened" -> "opened " + e.subject + (e.count > 1 ? " (" + e.count + " times)" : "") + where;
            case "travel" -> "travelled to" + where.replace(" near", "");
            default -> e.subject;
        };
    }

    synchronized JsonObject since(long sinceId) {
        long now = System.currentTimeMillis();
        JsonArray arr = new JsonArray();
        for (Entry e : entries) {
            if (e.id <= sinceId) continue;
            JsonObject o = new JsonObject();
            o.addProperty("id", e.id);
            o.addProperty("minutesAgo", Math.round((now - e.endMs) / 6000.0) / 10.0);
            o.addProperty("text", describe(e));
            arr.add(o);
        }
        JsonObject out = new JsonObject();
        out.add("entries", arr);
        out.addProperty("lastId", nextId - 1);
        return out;
    }

    synchronized void save() {
        try {
            Path dir = Afk.dataDir();
            Files.createDirectories(dir);
            JsonArray arr = new JsonArray();
            for (Entry e : entries) {
                JsonObject o = new JsonObject();
                o.addProperty("t", e.endMs);
                o.addProperty("text", describe(e));
                arr.add(o);
            }
            Files.writeString(dir.resolve("activity.json"), new Gson().toJson(arr), StandardCharsets.UTF_8);
        } catch (Exception e) {
            Afk.LOG.warn("Could not save activity history", e);
        }
    }

    synchronized void load() {
        try {
            Path file = Afk.dataDir().resolve("activity.json");
            if (!Files.exists(file)) return;
            JsonElement parsed = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            if (!parsed.isJsonArray()) return;
            for (JsonElement el : parsed.getAsJsonArray()) {
                JsonObject o = el.getAsJsonObject();
                Entry e = new Entry();
                e.id = nextId++;
                e.startMs = e.endMs = o.get("t").getAsLong();
                e.kind = "history";
                e.subject = "";
                e.text = o.get("text").getAsString();
                entries.addLast(e);
            }
            while (entries.size() > CAPACITY) entries.removeFirst();
        } catch (Exception e) {
            Afk.LOG.warn("Could not load activity history", e);
        }
    }

    private static String clip(String s, int max) {
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }
}
