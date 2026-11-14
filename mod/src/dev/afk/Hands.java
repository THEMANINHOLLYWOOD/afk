package dev.afk;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.StackedItemContents;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractCraftingMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import net.minecraft.world.item.equipment.Equippable;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Herobrine's hands: the things a player does with the mouse and inventory, done the way a player
 * does them (look at it, be in reach, click). Two kinds:
 *
 *  - Actions the brain asks for, run one at a time over a few ticks: craft (uses the recipe book,
 *    placing and using a crafting table when needed), use a block, place a block, eat, sleep,
 *    move items in or out of an open container, close it, put on armour.
 *  - Reflexes while Herobrine has control, with no round trip to the brain: hit hostile mobs in
 *    reach, run from a creeper about to blow, eat when hungry, put on armour.
 */
final class Hands {
    private static final double REACH = 4.5;
    private static final double ATTACK_REACH = 3.3;
    /** Mobs that only fight back; hitting them starts a fight that didn't need to happen. */
    private static final Set<String> LEAVE_ALONE = Set.of("enderman", "zombified_piglin", "piglin", "iron_golem", "wolf", "bee", "llama", "polar_bear");
    private static final Set<String> BAD_FOOD = Set.of("rotten_flesh", "spider_eye", "poisonous_potato", "pufferfish", "chicken", "suspicious_stew", "chorus_fruit");

    private abstract static class Task {
        final String label;
        int ticks;

        Task(String label) {
            this.label = label;
        }

        /** One game tick of work. Returns the outcome when finished, or null to keep going. */
        abstract String tick(Minecraft mc, LocalPlayer p);

        void cleanup(Minecraft mc, LocalPlayer p) {
        }
    }

    record Recipe(RecipeDisplayEntry entry, String id, boolean craftable, boolean needsTable) {}

    private final ArrayDeque<Task> queue = new ArrayDeque<>();
    private Task current;
    private boolean pausedBaritone;
    private int tick;
    private int fleeTicks;
    private Entity fleeFrom;
    private boolean fleeKeys;
    private long lastEatMs;

    // ------------------------------------------------------------------ public surface

    String busyLabel() {
        Task t = current;
        if (fleeTicks > 0) return "running from a creeper";
        return t == null ? null : t.label;
    }

    /** Queue actions from the brain. Each is {"do": "...", ...}. Returns what was queued. */
    String enqueue(JsonArray actions) {
        List<String> names = new ArrayList<>();
        for (JsonElement el : actions) {
            if (!el.isJsonObject() || names.size() >= 6) continue;
            JsonObject a = el.getAsJsonObject();
            String what = str(a, "do");
            if (what == null) continue;
            Task task = switch (what.toLowerCase(Locale.ROOT)) {
                case "craft" -> new CraftTask(itemId(a), Math.max(1, Math.min(640, intOr(a, "count", 1))), false);
                case "use" -> new UseTask(pos(a));
                case "place" -> new PlaceTask(itemId(a));
                case "eat" -> new EatTask();
                case "sleep" -> new SleepTask();
                case "stash" -> new MoveTask(true, itemOrAll(a), intOr(a, "count", Integer.MAX_VALUE));
                case "take" -> new MoveTask(false, itemOrAll(a), intOr(a, "count", Integer.MAX_VALUE));
                case "close" -> new CloseTask();
                case "equip" -> new EquipTask();
                default -> throw new IllegalArgumentException("unknown action '" + what + "' (craft, use, place, eat, sleep, stash, take, close, equip)");
            };
            queue.addLast(task);
            names.add(task.label);
        }
        return names.isEmpty() ? "nothing queued" : "queued: " + String.join(", ", names);
    }

    /** A craft the human typed into the box on a crafting screen. */
    void craftForHuman(String itemId, int count) {
        queue.addLast(new CraftTask(itemId, count, true));
    }

    void abortAll(String why) {
        Minecraft mc = Minecraft.getInstance();
        if (current != null && mc.player != null) current.cleanup(mc, mc.player);
        if (current != null || !queue.isEmpty()) Afk.EVENTS.add("hands", "stopped what my hands were doing: " + why);
        current = null;
        queue.clear();
        stopFlee(mc);
        pausedBaritone = false;
    }

    void tick(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null || mc.gameMode == null) {
            queue.clear();
            current = null;
            fleeTicks = 0;
            return;
        }
        tick++;
        if (p.isDeadOrDying()) {
            if (current != null || !queue.isEmpty() || fleeTicks > 0) abortAll("died");
            return;
        }
        if (Afk.MODE.isAgent() && reflexes(mc, p)) return;

        if (current == null) {
            current = queue.pollFirst();
            if (current == null) {
                if (pausedBaritone) {
                    Afk.BARITONE.resumeAfterHands();
                    pausedBaritone = false;
                }
                return;
            }
            if (!pausedBaritone && Afk.BARITONE.pauseForHands()) pausedBaritone = true;
        }
        String outcome;
        try {
            outcome = current.tick(mc, p);
            current.ticks++;
            if (outcome == null && current.ticks > 900) outcome = "gave up after 45 seconds";
        } catch (Throwable t) {
            outcome = "failed: " + t;
            Afk.LOG.warn("Hands task {} failed", current.label, t);
        }
        if (outcome != null) {
            current.cleanup(mc, p);
            Afk.EVENTS.add("hands", current.label + ": " + outcome);
            current = null;
        }
    }

    // ------------------------------------------------------------------ reflexes

    /** Returns true while a reflex needs the body to itself this tick. */
    private boolean reflexes(Minecraft mc, LocalPlayer p) {
        ClientLevel level = mc.level;
        if (fleeTicks > 0) {
            flee(mc, p);
            return true;
        }
        if (mc.gui.screen() != null) return false;

        Entity target = null;
        double best = ATTACK_REACH;
        for (StateSampler.SeenMob mob : Afk.STATE.seenMobs()) {
            Entity e = mob.entity();
            if (!mob.hostile() || !e.isAlive() || LEAVE_ALONE.contains(mob.id())) continue;
            double d = e.distanceTo(p);
            if (e instanceof Creeper creeper && d < 5 && (creeper.getSwellDir() > 0 || creeper.isIgnited())) {
                startFlee(mc, creeper);
                return true;
            }
            if (d < best && StateSampler.canSee(level, p, e.getBoundingBox().getCenter(), null)) {
                best = d;
                target = e;
            }
        }
        if (target != null) {
            if (tick % 20 == 0) selectWeapon(mc, p);
            if (p.getAttackStrengthScale(0.5f) >= 0.95f) {
                lookAt(p, target.getBoundingBox().getCenter());
                mc.gameMode.attack(p, target);
                p.swing(InteractionHand.MAIN_HAND, p.getMainHandItem().getAttackAnimation(), true);
            }
            return false; // keep doing whatever else was going on between swings
        }

        if (current == null && queue.isEmpty()) {
            boolean hungry = p.getFoodData().getFoodLevel() <= 13 || (p.getHealth() < p.getMaxHealth() - 6 && p.getFoodData().getFoodLevel() < 20);
            boolean calm = Afk.STATE.seenMobs().stream().noneMatch(m -> m.hostile() && m.distance() < 10);
            if (hungry && calm && System.currentTimeMillis() - lastEatMs > 8000 && bestFood(p) >= 0) {
                lastEatMs = System.currentTimeMillis();
                queue.addLast(new EatTask());
            } else if (tick % 100 == 0 && p.containerMenu == p.inventoryMenu && wearable(p) != null) {
                queue.addLast(new EquipTask());
            }
        }
        return false;
    }

    private void startFlee(Minecraft mc, Entity from) {
        fleeFrom = from;
        fleeTicks = 36;
        if (!pausedBaritone && Afk.BARITONE.pauseForHands()) pausedBaritone = true;
        Afk.EVENTS.add("hands", "a creeper is about to blow: running");
    }

    private void flee(Minecraft mc, LocalPlayer p) {
        fleeTicks--;
        if (fleeFrom != null) {
            Vec3 away = p.position().subtract(fleeFrom.position());
            p.setYRot((float) Math.toDegrees(Math.atan2(-away.x, away.z)));
            p.setXRot(0);
        }
        fleeKeys = true;
        mc.options.keyUp.setDown(true);
        mc.options.keySprint.setDown(true);
        mc.options.keyJump.setDown(p.horizontalCollision || p.isInWater());
        if (fleeTicks <= 0) stopFlee(mc);
    }

    private void stopFlee(Minecraft mc) {
        fleeTicks = 0;
        fleeFrom = null;
        if (fleeKeys) {
            mc.options.keyUp.setDown(false);
            mc.options.keySprint.setDown(false);
            mc.options.keyJump.setDown(false);
            fleeKeys = false;
        }
    }

    private void selectWeapon(Minecraft mc, LocalPlayer p) {
        Inventory inv = p.getInventory();
        int bestSlot = -1;
        int bestScore = weaponScore(inv.getItem(inv.getSelectedSlot()));
        for (int i = 0; i < 36; i++) {
            int score = weaponScore(inv.getItem(i));
            if (score > bestScore) {
                bestScore = score;
                bestSlot = i;
            }
        }
        if (bestSlot < 0) return;
        int hotbar = toHotbar(mc, p, bestSlot);
        if (hotbar >= 0) inv.setSelectedSlot(hotbar);
    }

    private static int weaponScore(ItemStack stack) {
        if (stack.isEmpty()) return 0;
        String id = StateSampler.itemId(stack);
        int kind = id.endsWith("_sword") ? 100 : id.endsWith("_axe") ? 60 : id.equals("mace") || id.equals("trident") ? 90 : 0;
        if (kind == 0) return 0;
        List<String> tiers = List.of("wooden", "golden", "stone", "copper", "iron", "diamond", "netherite");
        for (int i = 0; i < tiers.size(); i++) if (id.startsWith(tiers.get(i))) return kind + i;
        return kind;
    }

    // ------------------------------------------------------------------ shared helpers

    static void lookAt(LocalPlayer p, Vec3 target) {
        Vec3 eye = p.getEyePosition();
        double dx = target.x - eye.x;
        double dy = target.y - eye.y;
        double dz = target.z - eye.z;
        p.setYRot((float) Math.toDegrees(Math.atan2(-dx, dz)));
        p.setXRot((float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz))));
    }

    /** Right-clicks a block the way a player would: in reach, in view, looking at it. Null on success. */
    private static String useBlock(Minecraft mc, LocalPlayer p, BlockPos pos) {
        Vec3 eye = p.getEyePosition();
        Vec3 center = Vec3.atCenterOf(pos);
        if (eye.distanceTo(center) > REACH + 1.0) return "too far away (" + Math.round(eye.distanceTo(center)) + " blocks): walk next to it first";
        BlockHitResult hit = StateSampler.aimAt(mc.level, p, pos, REACH);
        if (hit == null) return "can't get a clear click on it from here (something is in the way, or it is just out of reach)";
        lookAt(p, hit.getLocation());
        InteractionResult result = mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, hit);
        if (!result.consumesAction()) return "nothing happened";
        p.swing(InteractionHand.MAIN_HAND, p.getMainHandItem().getInteractAnimation(), true);
        return null;
    }

    private static BlockPos findNearby(Minecraft mc, LocalPlayer p, Predicate<BlockState> wanted) {
        Vec3 eye = p.getEyePosition();
        BlockPos c = BlockPos.containing(eye);
        BlockPos best = null;
        double bestD = REACH + 1.0;
        for (BlockPos pos : BlockPos.betweenClosed(c.offset(-5, -4, -5), c.offset(5, 4, 5))) {
            BlockState state = mc.level.getBlockState(pos);
            if (state.isAir() || !wanted.test(state)) continue;
            double d = eye.distanceTo(Vec3.atCenterOf(pos));
            if (d >= bestD) continue;
            if (StateSampler.aimAt(mc.level, p, pos, REACH) != null) {
                best = pos.immutable();
                bestD = d;
            }
        }
        return best;
    }

    private static int menuSlotOf(int inventoryIndex) {
        return inventoryIndex < 9 ? InventoryMenu.USE_ROW_SLOT_START + inventoryIndex : inventoryIndex;
    }

    /** Makes sure the stack at an inventory index is in the hotbar; returns its hotbar slot, or -1. */
    private static int toHotbar(Minecraft mc, LocalPlayer p, int index) {
        if (index < 9) return index;
        if (p.containerMenu != p.inventoryMenu) return -1;
        Inventory inv = p.getInventory();
        int hotbar = 8;
        for (int i = 0; i < 9; i++) {
            if (inv.getItem(i).isEmpty()) {
                hotbar = i;
                break;
            }
        }
        mc.gameMode.handleContainerInput(p.inventoryMenu.containerId, menuSlotOf(index), hotbar, ContainerInput.SWAP, p);
        return hotbar;
    }

    private static int find(LocalPlayer p, String id) {
        Inventory inv = p.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && StateSampler.itemId(s).equals(id)) return i;
        }
        return -1;
    }

    private static int bestFood(LocalPlayer p) {
        Inventory inv = p.getInventory();
        int best = -1;
        int bestNutrition = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty() || BAD_FOOD.contains(StateSampler.itemId(s))) continue;
            FoodProperties food = s.get(DataComponents.FOOD);
            if (food != null && food.nutrition() > bestNutrition) {
                bestNutrition = food.nutrition();
                best = i;
            }
        }
        return best;
    }

    /** An armour piece (or shield) in the inventory whose slot is empty: {inventory index}, or null. */
    private static int[] wearable(LocalPlayer p) {
        Inventory inv = p.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty()) continue;
            Equippable eq = s.get(DataComponents.EQUIPPABLE);
            if (eq == null) continue;
            EquipmentSlot slot = eq.slot();
            boolean armour = slot == EquipmentSlot.HEAD || slot == EquipmentSlot.CHEST || slot == EquipmentSlot.LEGS || slot == EquipmentSlot.FEET;
            boolean shield = slot == EquipmentSlot.OFFHAND && StateSampler.itemId(s).equals("shield");
            if ((armour || shield) && p.getItemBySlot(slot).isEmpty()) return new int[] {i, shield ? 1 : 0};
        }
        return null;
    }

    /** Everything in the recipe book, with whether it can be made right now. */
    static List<Recipe> recipes(Minecraft mc) {
        List<Recipe> out = new ArrayList<>();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) return out;
        StackedItemContents contents = new StackedItemContents();
        p.getInventory().fillStackedContents(contents);
        ContextMap ctx = SlotDisplayContext.fromLevel(mc.level);
        for (RecipeCollection collection : p.getRecipeBook().getCollections()) {
            for (RecipeDisplayEntry entry : collection.getRecipes()) {
                RecipeDisplay display = entry.display();
                boolean shaped = display instanceof ShapedCraftingRecipeDisplay;
                boolean shapeless = display instanceof ShapelessCraftingRecipeDisplay;
                if (!shaped && !shapeless) continue; // smelting and the like are not hand-crafted
                List<ItemStack> results = entry.resultItems(ctx);
                if (results.isEmpty() || results.get(0).isEmpty()) continue;
                boolean table = display instanceof ShapedCraftingRecipeDisplay s ? s.width() > 2 || s.height() > 2
                        : ((ShapelessCraftingRecipeDisplay) display).ingredients().size() > 4;
                out.add(new Recipe(entry, StateSampler.itemId(results.get(0)), entry.canCraft(contents), table));
            }
        }
        return out;
    }

    static List<String> craftableNow(Minecraft mc, int max) {
        List<String> names = new ArrayList<>();
        for (Recipe r : recipes(mc)) {
            if (r.craftable() && !names.contains(r.id()) && names.size() < max) names.add(r.id());
        }
        return names;
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : null;
    }

    private static int intOr(JsonObject o, String key, int fallback) {
        try {
            return o.has(key) ? o.get(key).getAsInt() : fallback;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static String itemId(JsonObject o) {
        String item = str(o, "item");
        if (item == null || item.isBlank()) throw new IllegalArgumentException("this action needs an \"item\"");
        return item.strip().toLowerCase(Locale.ROOT).replace("minecraft:", "").replace(' ', '_');
    }

    private static String itemOrAll(JsonObject o) {
        String item = str(o, "item");
        return item == null || item.isBlank() ? "all" : itemId(o);
    }

    private static BlockPos pos(JsonObject o) {
        if (!o.has("x") || !o.has("y") || !o.has("z")) throw new IllegalArgumentException("this action needs x, y and z");
        return new BlockPos(o.get("x").getAsInt(), o.get("y").getAsInt(), o.get("z").getAsInt());
    }

    private static String at(BlockPos p) {
        return p.getX() + " " + p.getY() + " " + p.getZ();
    }

    // ------------------------------------------------------------------ tasks

    /** Crafts with the recipe book. Uses the 2x2 grid when it fits, otherwise a crafting table: one in reach, or its own. */
    private final class CraftTask extends Task {
        private final String item;
        private final int wanted;
        private final boolean forHuman;
        private Recipe recipe;
        private int made;
        private int phase;
        private int wait;
        private boolean openedTable;
        private BlockPos placedTable;
        private PlaceTask placing;

        CraftTask(String item, int wanted, boolean forHuman) {
            super("craft " + wanted + " " + item);
            this.item = item;
            this.wanted = wanted;
            this.forHuman = forHuman;
        }

        @Override
        String tick(Minecraft mc, LocalPlayer p) {
            if (ticks < 6) return null; // let the inventory settle after whatever came before
            switch (phase) {
                case 0 -> { // find the recipe
                    Recipe known = null;
                    for (Recipe r : recipes(mc)) {
                        if (!r.id().equals(item)) continue;
                        if (known == null || (r.craftable() && !known.craftable())) known = r;
                    }
                    if (known == null) return "I don't know a recipe for " + item + " yet (recipes unlock as you find their ingredients)";
                    if (!known.craftable()) return "missing materials. Craftable right now: " + String.join(", ", craftableNow(mc, 30));
                    recipe = known;
                    phase = 1;
                }
                case 1 -> { // get the right grid open
                    AbstractContainerMenu menu = p.containerMenu;
                    if (!recipe.needsTable() && menu instanceof AbstractCraftingMenu) {
                        phase = 3;
                    } else if (recipe.needsTable() && menu instanceof CraftingMenu) {
                        phase = 3;
                    } else if (!recipe.needsTable()) {
                        if (menu != p.inventoryMenu) p.closeContainer();
                        phase = 3;
                    } else {
                        if (menu != p.inventoryMenu) {
                            p.closeContainer();
                            return null;
                        }
                        BlockPos table = findNearby(mc, p, s -> s.getBlock() == Blocks.CRAFTING_TABLE);
                        if (table == null) {
                            if (find(p, "crafting_table") < 0) return "this needs a crafting table and there is none in reach or in the inventory: craft crafting_table first";
                            if (placing == null) placing = new PlaceTask("crafting_table");
                            String placed = placing.tick(mc, p);
                            placing.ticks++;
                            if (placed == null) return null;
                            placing.cleanup(mc, p);
                            if (placing.placedAt == null) return "couldn't place the crafting table: " + placed;
                            placedTable = placing.placedAt;
                            return null;
                        }
                        if (placing != null) { // the table we just put down
                            if (placing.placedAt != null) placedTable = placing.placedAt;
                            placing.cleanup(mc, p);
                            placing = null;
                        }
                        String problem = useBlock(mc, p, table);
                        if (problem != null) return "couldn't open the crafting table: " + problem;
                        openedTable = true;
                        wait = 0;
                        phase = 2;
                    }
                }
                case 2 -> { // wait for the table to open
                    if (p.containerMenu instanceof CraftingMenu) phase = 3;
                    else if (++wait > 100) return "the crafting table didn't open";
                }
                case 3 -> { // place the recipe
                    mc.gameMode.handlePlaceRecipe(p.containerMenu.containerId, recipe.entry().id(), false);
                    wait = 0;
                    phase = 4;
                }
                case 4 -> { // take the result
                    if (!(p.containerMenu instanceof AbstractCraftingMenu menu)) return made > 0 ? "made " + made + " (the grid closed)" : "the crafting grid closed";
                    Slot result = menu.getResultSlot();
                    if (!result.hasItem()) {
                        if (++wait > 100) { // five seconds: enough for a laggy server
                            boolean still = recipes(mc).stream().anyMatch(r -> r.id().equals(item) && r.craftable());
                            String why = still ? "the server stopped answering" : "ran out of materials";
                            return made > 0 ? "made " + made + ", then " + why : "couldn't make it (" + why + ")";
                        }
                        return null;
                    }
                    if (!StateSampler.itemId(result.getItem()).equals(item)) { // a leftover from an earlier recipe: not ours
                        if (++wait > 100) return made > 0 ? "made " + made : "the grid is busy with something else";
                        return null;
                    }
                    int count = result.getItem().getCount();
                    mc.gameMode.handleContainerInput(menu.containerId, result.index, 0, ContainerInput.QUICK_MOVE, p);
                    made += count;
                    Afk.LOG.info("Crafted {} x{} ({} of {})", item, count, made, wanted);
                    if (made >= wanted) return "made " + made;
                    phase = 3;
                }
                default -> { }
            }
            return null;
        }

        @Override
        void cleanup(Minecraft mc, LocalPlayer p) {
            if (openedTable && p.containerMenu instanceof CraftingMenu) p.closeContainer();
            if (placedTable != null && !forHuman) queue.addFirst(new BreakTask(placedTable, "pick the crafting table back up"));
            if (forHuman) Afk.CHAT.showHerobrine(made > 0 ? "Crafted " + made + " " + item + "." : "Couldn't craft " + item + ".");
        }
    }

    private final class UseTask extends Task {
        private final BlockPos pos;
        private boolean used;

        UseTask(BlockPos pos) {
            super("use block at " + at(pos));
            this.pos = pos;
        }

        @Override
        String tick(Minecraft mc, LocalPlayer p) {
            if (!used) {
                String name = StateSampler.shortId(BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(pos).getBlock()));
                String problem = useBlock(mc, p, pos);
                if (problem != null) return name + ": " + problem;
                used = true;
                return null;
            }
            if (ticks < 8) return null;
            String name = StateSampler.shortId(BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(pos).getBlock()));
            if (p.isSleeping()) return "in bed, sleeping";
            if (p.containerMenu != p.inventoryMenu) return "opened " + name;
            // Chests and the like open a moment later on a busy server: give them five seconds.
            if (StateSampler.isUsable(name) && !name.endsWith("_bed") && ticks < 100) return null;
            return "used " + name;
        }
    }

    private final class SleepTask extends Task {
        private boolean used;

        SleepTask() {
            super("sleep");
        }

        @Override
        String tick(Minecraft mc, LocalPlayer p) {
            if (!used) {
                BlockPos bed = findNearby(mc, p, s -> s.getBlock() instanceof BedBlock);
                if (bed == null) return "no bed in reach: walk next to a bed first";
                String problem = useBlock(mc, p, bed);
                if (problem != null) return "couldn't use the bed: " + problem;
                used = true;
                return null;
            }
            if (ticks < 15) return null;
            return p.isSleeping() ? "in bed, sleeping until morning" : "couldn't sleep (it has to be night, with no monsters nearby)";
        }
    }

    private final class PlaceTask extends Task {
        private final String item;
        BlockPos placedAt;
        private int previousSlot = -1;
        private int phase;
        private int placedTick;

        PlaceTask(String item) {
            super("place " + item);
            this.item = item;
        }

        @Override
        String tick(Minecraft mc, LocalPlayer p) {
            if (phase == 0) {
                int index = find(p, item);
                if (index < 0) return "no " + item + " in the inventory";
                int hotbar = toHotbar(mc, p, index);
                if (hotbar < 0) return "close the open container first";
                previousSlot = p.getInventory().getSelectedSlot();
                p.getInventory().setSelectedSlot(hotbar);
                phase = 1;
                return null;
            }
            if (phase == 1) {
                if (ticks < 3) return null; // let the hotbar change settle
                Vec3 eye = p.getEyePosition();
                BlockPos feet = p.blockPosition();
                for (int ring = 1; ring <= 2; ring++) {
                    for (Direction d : List.of(p.getDirection(), p.getDirection().getClockWise(), p.getDirection().getCounterClockWise(), p.getDirection().getOpposite())) {
                        for (int dy = 0; dy >= -1; dy--) {
                            BlockPos target = feet.relative(d, ring).above(dy);
                            BlockPos support = target.below();
                            if (!mc.level.getBlockState(target).canBeReplaced()) continue;
                            if (!mc.level.getBlockState(support).isFaceSturdy(mc.level, support, Direction.UP)) continue;
                            Vec3 face = Vec3.atCenterOf(support).add(0, 0.5, 0);
                            if (eye.distanceTo(face) > REACH) continue;
                            BlockHitResult view = mc.level.clip(new ClipContext(eye, face, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, p));
                            if (view.getType() == HitResult.Type.BLOCK && !view.getBlockPos().equals(support)) continue;
                            lookAt(p, face);
                            InteractionResult r = mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, new BlockHitResult(face, Direction.UP, support, false));
                            if (r.consumesAction()) {
                                p.swing(InteractionHand.MAIN_HAND, p.getMainHandItem().getInteractAnimation(), true);
                                placedAt = target.immutable();
                                placedTick = ticks;
                                phase = 2;
                                return null;
                            }
                        }
                    }
                }
                return "no free spot next to me";
            }
            if (ticks - placedTick < 6) return null;
            if (mc.level.getBlockState(placedAt).isAir()) {
                placedAt = null;
                return "it didn't stay placed";
            }
            return "placed at " + at(placedAt);
        }

        @Override
        void cleanup(Minecraft mc, LocalPlayer p) {
            if (previousSlot >= 0) p.getInventory().setSelectedSlot(previousSlot);
        }
    }

    /** Breaks one block in reach by hand (used to pick a crafting table back up). */
    private final class BreakTask extends Task {
        private final BlockPos pos;

        BreakTask(BlockPos pos, String label) {
            super(label);
            this.pos = pos;
        }

        @Override
        String tick(Minecraft mc, LocalPlayer p) {
            BlockState state = mc.level.getBlockState(pos);
            if (state.isAir()) return ticks > 0 ? "done" : "already gone";
            Vec3 center = Vec3.atCenterOf(pos);
            if (p.getEyePosition().distanceTo(center) > REACH + 0.5) return "walked away from it";
            lookAt(p, center);
            if (ticks == 0) mc.gameMode.startDestroyBlock(pos, Direction.UP);
            else mc.gameMode.continueDestroyBlock(pos, Direction.UP);
            p.swing(InteractionHand.MAIN_HAND, p.getMainHandItem().getAttackAnimation(), true);
            return ticks > 300 ? "it wouldn't break" : null;
        }

        @Override
        void cleanup(Minecraft mc, LocalPlayer p) {
            mc.gameMode.stopDestroyBlock();
        }
    }

    private final class EatTask extends Task {
        private int previousSlot = -1;
        private int startFood;
        private int startCount;
        private String name = "food";
        private boolean started;

        EatTask() {
            super("eat");
        }

        @Override
        String tick(Minecraft mc, LocalPlayer p) {
            if (!started) {
                if (mc.gui.screen() != null) return "a screen is open";
                if (!p.canEat(false)) return "not hungry";
                int index = bestFood(p);
                if (index < 0) return "no food in the inventory";
                name = StateSampler.itemId(p.getInventory().getItem(index));
                int hotbar = toHotbar(mc, p, index);
                if (hotbar < 0) return "close the open container first";
                previousSlot = p.getInventory().getSelectedSlot();
                p.getInventory().setSelectedSlot(hotbar);
                startFood = p.getFoodData().getFoodLevel();
                startCount = p.getInventory().getItem(hotbar).getCount();
                started = true;
                return null;
            }
            if (ticks < 3) return null;
            // Don't open a chest or table by accident: look away from anything usable.
            if (mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK
                    && mc.level.getBlockState(hit.getBlockPos()).getMenuProvider(mc.level, hit.getBlockPos()) != null) {
                p.setXRot(-60);
                return null;
            }
            mc.options.keyUse.setDown(true);
            boolean ate = p.getMainHandItem().getCount() < startCount || p.getFoodData().getFoodLevel() > startFood;
            if (ate && !p.isUsingItem()) return "ate " + name + " (food " + p.getFoodData().getFoodLevel() + "/20)";
            return ticks > 70 ? "couldn't eat" : null;
        }

        @Override
        void cleanup(Minecraft mc, LocalPlayer p) {
            mc.options.keyUse.setDown(false);
            if (previousSlot >= 0) p.getInventory().setSelectedSlot(previousSlot);
        }
    }

    /** Shift-clicks stacks between the inventory and the open container (chest, furnace, ...). */
    private final class MoveTask extends Task {
        private final boolean stash;
        private final String item;
        private int left;
        private int moved;

        MoveTask(boolean stash, String item, int count) {
            super((stash ? "stash " : "take ") + item);
            this.stash = stash;
            this.item = item;
            this.left = count;
        }

        @Override
        String tick(Minecraft mc, LocalPlayer p) {
            AbstractContainerMenu menu = p.containerMenu;
            if (menu == p.inventoryMenu) return "no container is open: use a chest or furnace first";
            for (int clicks = 0; clicks < 3; clicks++) {
                Slot chosen = null;
                for (Slot slot : menu.slots) {
                    boolean mine = slot.container == p.getInventory();
                    if (mine != stash || !slot.hasItem()) continue;
                    if (stash && slot.getContainerSlot() >= 36) continue; // not armour or offhand
                    if (item.equals("all") || StateSampler.itemId(slot.getItem()).equals(item)) {
                        chosen = slot;
                        break;
                    }
                }
                if (chosen == null || left <= 0) return moved > 0 ? "moved " + moved + " item(s)" : "nothing to move";
                int before = chosen.getItem().getCount();
                if (left < before) {
                    // Part of a stack: pick it up, right-click that many into a free slot on the other side, put the rest back.
                    Slot free = null;
                    for (Slot slot : menu.slots) {
                        boolean mine = slot.container == p.getInventory();
                        if (mine == stash || slot.hasItem() || !slot.mayPlace(chosen.getItem())) continue;
                        if (mine && slot.getContainerSlot() >= 36) continue;
                        free = slot;
                        break;
                    }
                    if (free == null) return moved > 0 ? "moved " + moved + " item(s), then it was full" : "no free slot on the other side";
                    mc.gameMode.handleContainerInput(menu.containerId, chosen.index, 0, ContainerInput.PICKUP, p);
                    for (int i = 0; i < left; i++) mc.gameMode.handleContainerInput(menu.containerId, free.index, 1, ContainerInput.PICKUP, p);
                    mc.gameMode.handleContainerInput(menu.containerId, chosen.index, 0, ContainerInput.PICKUP, p);
                    moved += left;
                    left = 0;
                    return "moved " + moved + " item(s)";
                }
                mc.gameMode.handleContainerInput(menu.containerId, chosen.index, 0, ContainerInput.QUICK_MOVE, p);
                int after = chosen.hasItem() ? chosen.getItem().getCount() : 0;
                if (after >= before) return moved > 0 ? "moved " + moved + " item(s), then it was full" : "no room";
                moved += before - after;
                left -= before - after;
            }
            return null;
        }
    }

    private final class CloseTask extends Task {
        CloseTask() {
            super("close container");
        }

        @Override
        String tick(Minecraft mc, LocalPlayer p) {
            if (p.containerMenu != p.inventoryMenu || mc.gui.screen() != null) p.closeContainer();
            return "closed";
        }
    }

    private final class EquipTask extends Task {
        private int worn;

        EquipTask() {
            super("put on armour");
        }

        @Override
        String tick(Minecraft mc, LocalPlayer p) {
            if (p.containerMenu != p.inventoryMenu) return "close the open container first";
            if (ticks % 3 != 0) return null;
            int[] next = wearable(p);
            if (next == null) return worn > 0 ? "put on " + worn + " piece(s)" : "nothing to put on";
            if (next[1] == 1) {
                mc.gameMode.handleContainerInput(p.inventoryMenu.containerId, menuSlotOf(next[0]), Inventory.SLOT_OFFHAND, ContainerInput.SWAP, p);
            } else {
                mc.gameMode.handleContainerInput(p.inventoryMenu.containerId, menuSlotOf(next[0]), 0, ContainerInput.QUICK_MOVE, p);
            }
            worn++;
            return worn > 6 ? "put on " + worn + " piece(s)" : null;
        }
    }
}
