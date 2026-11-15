package dev.afk;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;

/**
 * The crafting a helper knows: the survival basics, with the same ingredients a player uses. Small
 * recipes work anywhere; the big ones need a crafting table within a few blocks or one in his pack.
 */
final class HelperRecipes {
    private record Recipe(int makes, boolean table, Map<String, Integer> needs) {}

    private static final Map<String, Recipe> RECIPES = new LinkedHashMap<>();

    static {
        add("stick", 4, false, "#planks", 2);
        add("crafting_table", 1, false, "#planks", 4);
        add("chest", 1, true, "#planks", 8);
        add("barrel", 1, true, "#planks", 6, "#slab", 2);
        add("furnace", 1, true, "#stone", 8);
        add("torch", 4, false, "#coal", 1, "stick", 1);
        add("bread", 1, true, "wheat", 3);
        for (String[] m : new String[][] {{"wooden", "#planks"}, {"stone", "#stone"}, {"iron", "iron_ingot"}, {"golden", "gold_ingot"}, {"diamond", "diamond"}}) {
            add(m[0] + "_pickaxe", 1, true, m[1], 3, "stick", 2);
            add(m[0] + "_axe", 1, true, m[1], 3, "stick", 2);
            add(m[0] + "_shovel", 1, true, m[1], 1, "stick", 2);
            add(m[0] + "_sword", 1, true, m[1], 2, "stick", 1);
            add(m[0] + "_hoe", 1, true, m[1], 2, "stick", 2);
        }
    }

    private HelperRecipes() {
    }

    private static void add(String result, int makes, boolean table, Object... needs) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (int i = 0; i < needs.length; i += 2) m.put((String) needs[i], (Integer) needs[i + 1]);
        RECIPES.put(result, new Recipe(makes, table, m));
    }

    /** Crafts until he has made "count" of the item or runs out. Returns "ok:<what>" or why not. */
    static String craft(Helper h, ServerLevel level, String rawId, int count) {
        String id = rawId.startsWith("minecraft:") ? rawId.substring(10) : rawId;
        if (id.equals("planks") || id.endsWith("_planks")) return planks(h, level, id, count);
        Recipe r = RECIPES.get(id);
        if (r == null) return "I don't know how to craft " + id.replace('_', ' ') + " (I know: planks, " + String.join(", ", RECIPES.keySet()).replace('_', ' ') + ")";
        Item result = Helper.item(id);
        if (result == null) return "no such item: " + id;
        if (r.table && !tableNear(h, level)) return "I need a crafting table nearby or in my pack for " + id.replace('_', ' ');
        int made = 0;
        while (made < count) {
            for (Map.Entry<String, Integer> need : r.needs.entrySet()) {
                if (have(h, need.getKey()) < need.getValue()) {
                    return made > 0 ? "ok:made " + made + " " + id.replace('_', ' ') + ", then ran out of " + label(need.getKey())
                            : "I need " + need.getValue() + " " + label(need.getKey()) + " for " + id.replace('_', ' ');
                }
            }
            for (Map.Entry<String, Integer> need : r.needs.entrySet()) use(h, need.getKey(), need.getValue());
            ItemStack out = new ItemStack(result, r.makes);
            ItemStack left = h.getInventory().addItem(out);
            if (!left.isEmpty()) h.spawnAtLocation(level, left);
            made += r.makes;
        }
        h.swingForAttack(InteractionHand.MAIN_HAND);
        level.playSound(null, h.blockPosition(), SoundEvents.VILLAGER_WORK_TOOLSMITH, SoundSource.NEUTRAL, 0.6f, 1.0f);
        return "ok:made " + made + " " + id.replace('_', ' ');
    }

    /** Planks from logs, one log for four planks, of the same wood. */
    private static String planks(Helper h, ServerLevel level, String id, int count) {
        SimpleContainer pack = h.getInventory();
        int made = 0;
        while (made < count) {
            int slot = -1;
            for (int i = 0; i < pack.getContainerSize(); i++) {
                ItemStack s = pack.getItem(i);
                if (s.isEmpty() || !s.is(ItemTags.LOGS)) continue;
                String wood = woodOf(Helper.itemId(s));
                if (id.equals("planks") || id.equals(wood + "_planks")) {
                    slot = i;
                    break;
                }
            }
            if (slot < 0) return made > 0 ? "ok:made " + made + " planks, then ran out of logs" : "I need logs to make planks";
            String wood = woodOf(Helper.itemId(pack.getItem(slot)));
            Item planks = Helper.item(wood + "_planks");
            if (planks == null) return "I can't make planks from " + Helper.itemId(pack.getItem(slot));
            pack.getItem(slot).shrink(1);
            ItemStack left = pack.addItem(new ItemStack(planks, 4));
            if (!left.isEmpty()) h.spawnAtLocation(level, left);
            made += 4;
        }
        h.swingForAttack(InteractionHand.MAIN_HAND);
        return "ok:made " + made + " planks";
    }

    private static String woodOf(String logId) {
        String w = logId.startsWith("stripped_") ? logId.substring(9) : logId;
        for (String suffix : new String[] {"_log", "_wood", "_stem", "_hyphae"}) {
            if (w.endsWith(suffix)) return w.substring(0, w.length() - suffix.length());
        }
        return w;
    }

    private static boolean tableNear(Helper h, ServerLevel level) {
        if (h.count(Helper.item("crafting_table")) > 0) return true;
        BlockPos me = h.blockPosition();
        for (BlockPos p : BlockPos.betweenClosed(me.offset(-5, -2, -5), me.offset(5, 3, 5))) {
            if (level.getBlockState(p).is(Blocks.CRAFTING_TABLE)) return true;
        }
        return false;
    }

    private static Predicate<ItemStack> family(String key) {
        return switch (key) {
            case "#planks" -> s -> Helper.itemId(s).endsWith("_planks");
            case "#slab" -> s -> s.is(ItemTags.WOODEN_SLABS);
            case "#stone" -> s -> {
                String id = Helper.itemId(s);
                return id.equals("cobblestone") || id.equals("cobbled_deepslate") || id.equals("blackstone");
            };
            case "#coal" -> s -> {
                String id = Helper.itemId(s);
                return id.equals("coal") || id.equals("charcoal");
            };
            default -> s -> Helper.itemId(s).equals(key);
        };
    }

    private static int have(Helper h, String key) {
        Predicate<ItemStack> p = family(key);
        int n = 0;
        SimpleContainer pack = h.getInventory();
        for (int i = 0; i < pack.getContainerSize(); i++) {
            ItemStack s = pack.getItem(i);
            if (!s.isEmpty() && p.test(s)) n += s.getCount();
        }
        return n;
    }

    private static void use(Helper h, String key, int amount) {
        Predicate<ItemStack> p = family(key);
        SimpleContainer pack = h.getInventory();
        for (int i = 0; i < pack.getContainerSize() && amount > 0; i++) {
            ItemStack s = pack.getItem(i);
            if (s.isEmpty() || !p.test(s)) continue;
            int n = Math.min(amount, s.getCount());
            s.shrink(n);
            amount -= n;
        }
    }

    private static String label(String key) {
        return switch (key) {
            case "#planks" -> "planks";
            case "#slab" -> "wooden slabs";
            case "#stone" -> "cobblestone";
            case "#coal" -> "coal";
            default -> key.replace('_', ' ');
        };
    }

    static String known() {
        return "planks, " + String.join(", ", RECIPES.keySet()).replace('_', ' ');
    }

    static String itemKey(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).getPath();
    }
}
