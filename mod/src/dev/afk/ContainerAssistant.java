package dev.afk;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import net.minecraft.world.entity.player.StackedItemContents;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;

/**
 * A small "Ask Herobrine" box under every container screen.
 *   Crafting table / inventory: "3 pickaxes", "craft 2 chests", "16 torches" (uses the recipe book, like clicking it yourself)
 *   Chest, barrel, shulker:      "sort", "organize", "sort potions"
 * Anything it doesn't understand goes to Herobrine as a chat message.
 */
final class ContainerAssistant {
    private static final Pattern CRAFT = Pattern.compile("^(?:craft|make|build)?\\s*(\\d+)?\\s*x?\\s*(.+?)\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Map<String, String> SYNONYMS = Map.ofEntries(
            Map.entry("pick", "pickaxe"), Map.entry("picks", "pickaxe"), Map.entry("pickaxes", "pickaxe"),
            Map.entry("axes", "axe"), Map.entry("shovels", "shovel"), Map.entry("swords", "sword"), Map.entry("hoes", "hoe"),
            Map.entry("table", "crafting_table"), Map.entry("workbench", "crafting_table"), Map.entry("torches", "torch"),
            Map.entry("sticks", "stick"), Map.entry("chests", "chest"), Map.entry("plank", "planks"), Map.entry("doors", "door"),
            Map.entry("beds", "bed"), Map.entry("boats", "boat"), Map.entry("ladders", "ladder"), Map.entry("furnaces", "furnace"),
            Map.entry("buckets", "bucket"), Map.entry("slabs", "slab"), Map.entry("stairs", "stairs"), Map.entry("fences", "fence"));
    private static final List<String> TIERS = List.of("netherite", "diamond", "iron", "golden", "stone", "copper", "wooden");

    private EditBox box;
    private Screen boxScreen;

    // A craft in progress: place the recipe, wait for the result, shift-click it out, repeat.
    private RecipeDisplayEntry crafting;
    private String craftingName;
    private int craftWanted;
    private int craftMade;
    private int craftWait;
    private boolean craftPlaced;

    void register() {
        ScreenEvents.AFTER_INIT.register((mc, screen, width, height) -> {
            if (!(screen instanceof AbstractContainerScreen<?>) || mc.player == null) return;
            boolean crafting = screen instanceof CraftingScreen || screen instanceof InventoryScreen;
            int w = 176;
            EditBox field = new EditBox(mc.font, (width - w) / 2, Math.min(height - 20, height / 2 + 90), w, 14,
                    Component.literal("Ask Herobrine"));
            field.setMaxLength(160);
            field.setHint(Component.literal(crafting ? "Ask Herobrine: 3 pickaxes, 16 torches..." : "Ask Herobrine: sort, sort potions...")
                    .withColor(0x8C8676));
            Screens.getWidgets(screen).add(field);
            box = field;
            boxScreen = screen;
            ScreenKeyboardEvents.allowKeyPress(screen).register((s, event) -> {
                if (box == null || boxScreen != s || !box.isFocused()) return true;
                int key = event.key();
                if (key == InputConstants.KEY_ESCAPE) return true;
                if (key == InputConstants.KEY_RETURN || key == InputConstants.KEY_NUMPADENTER) {
                    String text = box.getValue().strip();
                    box.setValue("");
                    if (!text.isEmpty()) handle(Minecraft.getInstance(), s, text);
                    return false;
                }
                box.keyPressed(event);
                return false; // keep keys like the inventory key from closing the screen while typing
            });
        });
    }

    void tick(Minecraft mc) {
        if (boxScreen != null && mc.gui.screen() != boxScreen) {
            box = null;
            boxScreen = null;
        }
        if (crafting == null) return;
        LocalPlayer p = mc.player;
        if (p == null || !(mc.gui.screen() instanceof AbstractContainerScreen<?> screen)) {
            finishCraft("stopped: the screen closed");
            return;
        }
        AbstractContainerMenu menu = screen.getMenu();
        if (!craftPlaced) {
            mc.gameMode.handlePlaceRecipe(menu.containerId, crafting.id(), false);
            craftPlaced = true;
            craftWait = 0;
            return;
        }
        ItemStack result = menu.getSlot(0).getItem();
        if (result.isEmpty()) {
            if (++craftWait > 30) finishCraft(craftMade > 0 ? "ran out of materials" : "couldn't craft it (missing materials, or it needs a crafting table)");
            return;
        }
        int made = result.getCount();
        mc.gameMode.handleContainerInput(menu.containerId, 0, 0, ContainerInput.QUICK_MOVE, p);
        craftMade += made;
        craftPlaced = false;
        if (craftMade >= craftWanted) finishCraft("done");
    }

    private void finishCraft(String how) {
        if (crafting != null) {
            Afk.CHAT.showHerobrine("Crafted " + craftMade + " " + craftingName + (how.equals("done") ? "." : " — " + how + "."));
            Afk.EVENTS.add("craft", "crafted " + craftMade + " " + craftingName + " (" + how + ")");
        }
        crafting = null;
    }

    private void handle(Minecraft mc, Screen screen, String text) {
        String t = text.toLowerCase(Locale.ROOT);
        if (!(screen instanceof AbstractContainerScreen<?> cs)) return;
        if (t.matches("^(sort|organi[sz]e|tidy|clean up)( (it|this|chest|everything|inventory))?$")) {
            int moves = sort(mc, cs.getMenu(), screen instanceof InventoryScreen, false);
            Afk.CHAT.showHerobrine(moves == 0 ? "Already tidy." : "Sorted (" + moves + " moves).");
            return;
        }
        if (t.matches("^(sort|organi[sz]e|group) (the )?potions?$")) {
            int moves = sort(mc, cs.getMenu(), screen instanceof InventoryScreen, true);
            Afk.CHAT.showHerobrine(moves == 0 ? "Potions are already in order." : "Potions grouped by effect (" + moves + " moves).");
            return;
        }
        if (screen instanceof CraftingScreen || screen instanceof InventoryScreen) {
            Matcher m = CRAFT.matcher(text.strip());
            if (m.matches()) {
                int count = m.group(1) == null ? 1 : Integer.parseInt(m.group(1));
                String what = m.group(2);
                String outcome = craftByWords(mc, what, count);
                if (outcome != null) return;
            }
            Afk.CHAT.route(text + " [craftable here: " + String.join(", ", craftableNames(mc, 40)) + "]", "the crafting screen");
            return;
        }
        Afk.CHAT.route(text, screen.getTitle().getString() + " screen");
    }

    // ------------------------------------------------------------------ crafting

    private record Option(RecipeDisplayEntry entry, String id, boolean craftable) {}

    private List<Option> options(Minecraft mc) {
        List<Option> out = new ArrayList<>();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) return out;
        StackedItemContents contents = new StackedItemContents();
        p.getInventory().fillStackedContents(contents);
        ContextMap ctx = SlotDisplayContext.fromLevel(mc.level);
        for (RecipeCollection collection : p.getRecipeBook().getCollections()) {
            for (RecipeDisplayEntry entry : collection.getRecipes()) {
                List<ItemStack> results = entry.resultItems(ctx);
                if (results.isEmpty() || results.get(0).isEmpty()) continue;
                out.add(new Option(entry, StateSampler.itemId(results.get(0)), entry.canCraft(contents)));
            }
        }
        return out;
    }

    private List<String> craftableNames(Minecraft mc, int max) {
        List<String> names = new ArrayList<>();
        for (Option o : options(mc)) {
            if (o.craftable && !names.contains(o.id) && names.size() < max) names.add(o.id);
        }
        return names;
    }

    /** Local fuzzy match ("3 picks" -> best pickaxe you can make). Returns null if nothing fits. */
    private String craftByWords(Minecraft mc, String words, int count) {
        List<String> tokens = new ArrayList<>();
        for (String raw : words.toLowerCase(Locale.ROOT).replaceAll("[^a-z_ ]", " ").split("\\s+")) {
            if (raw.isEmpty() || Set.of("a", "an", "the", "some", "of", "me", "please").contains(raw)) continue;
            String tok = SYNONYMS.getOrDefault(raw, raw.endsWith("s") && raw.length() > 3 && !raw.endsWith("ss") ? raw.substring(0, raw.length() - 1) : raw);
            tokens.add(tok);
        }
        if (tokens.isEmpty()) return null;
        Option best = null;
        int bestScore = Integer.MIN_VALUE;
        for (Option o : options(mc)) {
            List<String> parts = List.of(o.id.split("_"));
            boolean all = tokens.stream().allMatch(tok -> o.id.equals(tok) || parts.contains(tok) || o.id.contains(tok));
            if (!all) continue;
            int score = (o.craftable ? 1000 : 0) - (o.id.length() * 2);
            for (int i = 0; i < TIERS.size(); i++) if (o.id.startsWith(TIERS.get(i) + "_")) score += (TIERS.size() - i) * 10;
            if (o.id.equals(String.join("_", tokens))) score += 500;
            if (score > bestScore) {
                bestScore = score;
                best = o;
            }
        }
        if (best == null) return null;
        if (!best.craftable) {
            Afk.CHAT.showHerobrine("I can't make " + best.id + " with what you're carrying.");
            return "missing";
        }
        startCraft(best, count);
        return "started";
    }

    /** For the brain: craft an exact item id. Client thread. */
    String craftById(String item, int count) {
        Minecraft mc = Minecraft.getInstance();
        if (!(mc.gui.screen() instanceof CraftingScreen) && !(mc.gui.screen() instanceof InventoryScreen)) {
            throw new IllegalStateException("open a crafting table or your inventory first");
        }
        String id = item.replace("minecraft:", "");
        for (Option o : options(mc)) {
            if (o.id.equals(id)) {
                if (!o.craftable) return "missing materials for " + id;
                startCraft(o, count);
                return "crafting " + count + " " + id;
            }
        }
        return "no known recipe for " + id;
    }

    private void startCraft(Option o, int count) {
        crafting = o.entry;
        craftingName = o.id;
        craftWanted = Math.max(1, Math.min(640, count));
        craftMade = 0;
        craftPlaced = false;
        Afk.CHAT.showHerobrine("Crafting " + craftWanted + " " + o.id + "...");
    }

    // ------------------------------------------------------------------ sorting

    /**
     * Sorts the container part of the open screen (or your main inventory) with ordinary clicks:
     * stacks merged, then grouped tools, armour, potions, food, ores, blocks, the rest. Returns clicks used.
     */
    private int sort(Minecraft mc, AbstractContainerMenu menu, boolean playerInventory, boolean potionsFirst) {
        LocalPlayer p = mc.player;
        if (p == null || !menu.getCarried().isEmpty()) return 0;
        List<Integer> slots = new ArrayList<>();
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot s = menu.slots.get(i);
            boolean isPlayer = s.container == p.getInventory();
            if (playerInventory) {
                if (isPlayer && s.getContainerSlot() >= 9 && s.getContainerSlot() < 36) slots.add(i);
            } else if (!isPlayer) {
                slots.add(i);
            }
        }
        if (slots.isEmpty()) return 0;
        int clicks = 0;
        // 1. Merge partial stacks of the same item.
        for (int a = 0; a < slots.size(); a++) {
            for (int b = a + 1; b < slots.size(); b++) {
                ItemStack sa = menu.getSlot(slots.get(a)).getItem();
                ItemStack sb = menu.getSlot(slots.get(b)).getItem();
                if (sa.isEmpty() || sb.isEmpty() || sa.getCount() >= sa.getMaxStackSize() || !ItemStack.isSameItemSameComponents(sa, sb)) continue;
                clicks += click(mc, menu, slots.get(b));
                clicks += click(mc, menu, slots.get(a));
                if (!menu.getCarried().isEmpty()) clicks += click(mc, menu, slots.get(b));
            }
        }
        // 2. Selection sort by category with swaps (never swapping two slots of the same item).
        Comparator<ItemStack> order = Comparator.<ItemStack>comparingInt(s -> category(s, potionsFirst))
                .thenComparing(StateSampler::itemId)
                .thenComparing(s -> s.getHoverName().getString());
        for (int i = 0; i < slots.size(); i++) {
            int bestJ = -1;
            ItemStack best = null;
            for (int j = i; j < slots.size(); j++) {
                ItemStack s = menu.getSlot(slots.get(j)).getItem();
                if (s.isEmpty()) continue;
                if (best == null || order.compare(s, best) < 0) {
                    best = s;
                    bestJ = j;
                }
            }
            if (best == null) break;
            if (bestJ == i) continue;
            ItemStack atI = menu.getSlot(slots.get(i)).getItem();
            if (!atI.isEmpty() && order.compare(atI, best) == 0) continue;
            clicks += click(mc, menu, slots.get(bestJ));
            clicks += click(mc, menu, slots.get(i));
            if (!menu.getCarried().isEmpty()) clicks += click(mc, menu, slots.get(bestJ));
            if (clicks > 600) break;
        }
        return clicks;
    }

    private static int click(Minecraft mc, AbstractContainerMenu menu, int slot) {
        mc.gameMode.handleContainerInput(menu.containerId, slot, 0, ContainerInput.PICKUP, mc.player);
        return 1;
    }

    private static final Map<String, Integer> CACHE = new HashMap<>();

    private static int category(ItemStack s, boolean potionsFirst) {
        String id = StateSampler.itemId(s);
        boolean potion = id.endsWith("potion") || id.equals("tipped_arrow") || s.has(DataComponents.POTION_CONTENTS);
        if (potion) return potionsFirst ? -1 : 2;
        if (id.matches(".*_(pickaxe|axe|shovel|hoe|sword)$") || Set.of("bow", "crossbow", "trident", "mace", "shears", "flint_and_steel", "fishing_rod").contains(id)) return 0;
        if (id.matches(".*_(helmet|chestplate|leggings|boots)$") || id.equals("shield") || id.equals("elytra")) return 1;
        if (s.has(DataComponents.FOOD)) return 3;
        if (id.endsWith("_ore") || id.endsWith("_ingot") || id.startsWith("raw_") || id.endsWith("_nugget")
                || Set.of("diamond", "emerald", "coal", "charcoal", "redstone", "lapis_lazuli", "quartz", "netherite_scrap", "amethyst_shard").contains(id)) return 4;
        if (s.getItem() instanceof BlockItem) return 5;
        return 6;
    }
}
