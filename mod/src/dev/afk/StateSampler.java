package dev.afk;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * What Herobrine knows, built only from what a player in this body could perceive:
 * inventory, health and coordinates (F3), the block under the crosshair, mobs and players in
 * line of sight (or close enough to hear), ores visible on an exposed face, and the tab list.
 * Nothing here looks through walls.
 */
final class StateSampler {
    private static final int SAMPLE_EVERY_TICKS = 4;
    private static final int SCAN_EVERY_TICKS = 20;
    private static final double SIGHT_RANGE = 32;
    private static final double HEARING_RANGE = 4;
    private static final int ORE_SCAN_RADIUS = 10;

    record SeenOre(String id, BlockPos pos, double distance) {}
    record SeenMob(Entity entity, String id, double distance, boolean hostile) {}
    record Usable(String id, BlockPos pos, double distance) {}

    private volatile String json = "{\"inWorld\":false}";
    private volatile List<SeenOre> seenOres = List.of();
    private volatile List<SeenMob> seenMobs = List.of();
    private volatile List<Usable> usable = List.of();
    private int tickCounter;
    private boolean wasInWorld;
    private boolean wasDead;
    private float lastHealth = -1;
    private boolean loggedFailure;

    String json() {
        return json;
    }

    List<SeenOre> seenOres() {
        return seenOres;
    }

    List<SeenMob> seenMobs() {
        return seenMobs;
    }

    void tick(Minecraft mc) {
        tickCounter++;
        try {
            if (tickCounter % SCAN_EVERY_TICKS == 0) scan(mc);
            if (tickCounter % SAMPLE_EVERY_TICKS == 0) json = sample(mc).toString();
        } catch (Throwable t) {
            if (!loggedFailure) {
                loggedFailure = true;
                Afk.LOG.error("State sampling failed", t);
            }
            JsonObject o = new JsonObject();
            o.addProperty("inWorld", false);
            o.addProperty("error", "state sampling failed: " + t);
            json = o.toString();
        }
    }

    /** Line of sight from the player's eyes to a point, ignoring fluids. */
    static boolean canSee(ClientLevel level, LocalPlayer p, Vec3 target, BlockPos allowHit) {
        Vec3 eye = p.getEyePosition();
        BlockHitResult hit = level.clip(new ClipContext(eye, target, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, p));
        if (hit.getType() == HitResult.Type.MISS) return true;
        return allowHit != null && hit.getBlockPos().equals(allowHit);
    }

    private static final double[][] AIM_OFFSETS = {
            {0, 0, 0}, {0, 0.42, 0}, {0, -0.3, 0}, {0.3, 0.3, 0}, {-0.3, 0.3, 0}, {0, 0.3, 0.3}, {0, 0.3, -0.3},
            {0.35, 0, 0.35}, {-0.35, 0, -0.35}, {0.35, 0, -0.35}, {-0.35, 0, 0.35}};

    /** Where on a block the crosshair can land from here (grass and other blocks in the way count), or null. */
    static BlockHitResult aimAt(ClientLevel level, LocalPlayer p, BlockPos pos, double reach) {
        Vec3 eye = p.getEyePosition();
        Vec3 center = Vec3.atCenterOf(pos);
        for (double[] o : AIM_OFFSETS) {
            Vec3 target = center.add(o[0], o[1], o[2]);
            Vec3 dir = target.subtract(eye);
            double len = dir.length();
            if (len > reach + 0.8 || len < 1e-3) continue;
            Vec3 end = eye.add(dir.scale((len + 0.6) / len)); // a little past, so thin blocks are reached too
            BlockHitResult hit = level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, p));
            if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(pos) && hit.getLocation().distanceTo(eye) <= reach) return hit;
        }
        return null;
    }

    static boolean isOre(String id) {
        return id.endsWith("_ore") || id.equals("ancient_debris");
    }

    /** Blocks Herobrine can right-click to do something useful. */
    static boolean isUsable(String id) {
        return id.equals("crafting_table") || id.equals("furnace") || id.equals("blast_furnace") || id.equals("smoker")
                || id.equals("chest") || id.equals("barrel") || id.endsWith("_bed") || id.endsWith("shulker_box");
    }

    private void scan(Minecraft mc) {
        LocalPlayer p = mc.player;
        ClientLevel level = mc.level;
        if (p == null || level == null) {
            seenOres = List.of();
            seenMobs = List.of();
            usable = List.of();
            return;
        }
        // Ores with an exposed face that the eye can actually reach.
        List<SeenOre> ores = new ArrayList<>();
        List<Usable> useful = new ArrayList<>();
        BlockPos center = BlockPos.containing(p.getEyePosition());
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dx = -ORE_SCAN_RADIUS; dx <= ORE_SCAN_RADIUS; dx++) {
            for (int dy = -ORE_SCAN_RADIUS; dy <= ORE_SCAN_RADIUS; dy++) {
                for (int dz = -ORE_SCAN_RADIUS; dz <= ORE_SCAN_RADIUS; dz++) {
                    m.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    BlockState state = level.getBlockState(m);
                    if (state.isAir()) continue;
                    String id = shortId(BuiltInRegistries.BLOCK.getKey(state.getBlock()));
                    if (isUsable(id)) {
                        BlockPos at = m.immutable();
                        if (canSee(level, p, Vec3.atCenterOf(at), at) || aimAt(level, p, at, 12) != null) useful.add(new Usable(id, at, Math.sqrt(at.distToCenterSqr(p.getEyePosition()))));
                        continue;
                    }
                    if (!isOre(id)) continue;
                    BlockPos pos = m.immutable();
                    if (!exposed(level, pos)) continue;
                    if (!canSee(level, p, Vec3.atCenterOf(pos), pos)) continue;
                    ores.add(new SeenOre(id, pos, Math.sqrt(pos.distToCenterSqr(p.getEyePosition()))));
                }
            }
        }
        ores.sort(Comparator.comparingDouble(SeenOre::distance));
        seenOres = List.copyOf(ores.subList(0, Math.min(12, ores.size())));
        useful.sort(Comparator.comparingDouble(Usable::distance));
        usable = List.copyOf(useful.subList(0, Math.min(8, useful.size())));

        List<SeenMob> mobs = new ArrayList<>();
        for (Entity e : level.entitiesForRendering()) {
            if (e == p || !(e instanceof LivingEntity) || !e.isAlive()) continue;
            double d = e.distanceTo(p);
            if (d > SIGHT_RANGE) continue;
            boolean visible = d <= HEARING_RANGE || canSee(level, p, e.getEyePosition(), null);
            if (!visible) continue;
            mobs.add(new SeenMob(e, shortId(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType())), d, e instanceof Enemy));
        }
        mobs.sort(Comparator.comparingDouble(SeenMob::distance));
        seenMobs = List.copyOf(mobs);
    }

    private static boolean exposed(ClientLevel level, BlockPos pos) {
        for (Direction d : Direction.values()) {
            BlockState n = level.getBlockState(pos.relative(d));
            if (!n.isSolidRender()) return true;
        }
        return false;
    }

    private JsonObject sample(Minecraft mc) {
        JsonObject o = new JsonObject();
        LocalPlayer p = mc.player;
        ClientLevel level = mc.level;
        boolean inWorld = p != null && level != null;
        o.addProperty("inWorld", inWorld);
        o.add("control", Afk.MODE.toJson());
        if (inWorld != wasInWorld) {
            Afk.EVENTS.add("world", inWorld ? "joined a world" : "left the world");
            if (inWorld) Afk.ACTIVITY.beginPlayerSession(mc);
            wasInWorld = inWorld;
            lastHealth = -1;
            wasDead = false;
        }
        if (!inWorld) return o;

        BlockPos pos = p.blockPosition();
        JsonObject position = new JsonObject();
        position.addProperty("x", pos.getX());
        position.addProperty("y", pos.getY());
        position.addProperty("z", pos.getZ());
        o.add("pos", position);
        o.addProperty("facing", p.getDirection().getName());
        o.addProperty("dimension", shortId(level.dimension().identifier()));
        o.addProperty("biome", level.getBiome(pos).getRegisteredName().replace("minecraft:", ""));

        long day = Math.floorMod(level.getOverworldClockTime(), 24000L);
        String phase = day < 11500 ? "day" : day < 13000 ? "dusk" : day < 23000 ? "night" : "dawn";
        o.addProperty("timeOfDay", phase);
        if (phase.equals("day")) o.addProperty("minutesUntilDusk", Math.round((11500 - day) / 1200.0 * 10) / 10.0);
        if (phase.equals("night") || phase.equals("dusk")) o.addProperty("minutesUntilDawn", Math.round((23000 - day) / 1200.0 * 10) / 10.0);
        int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, pos.getX(), pos.getZ());
        o.addProperty("skyAbove", top <= pos.getY() + 1);
        o.addProperty("light", level.getMaxLocalRawBrightness(pos));

        float health = p.getHealth();
        boolean dead = p.isDeadOrDying();
        o.addProperty("health", Math.round(health * 10) / 10.0);
        o.addProperty("maxHealth", Math.round(p.getMaxHealth()));
        o.addProperty("food", p.getFoodData().getFoodLevel());
        o.addProperty("armor", p.getArmorValue());
        o.addProperty("dead", dead);
        if (p.isInLava()) o.addProperty("inLava", true);
        if (p.isOnFire()) o.addProperty("onFire", true);
        if (p.isUnderWater()) o.addProperty("airPercent", Math.round(100.0 * p.getAirSupply() / Math.max(1, p.getMaxAirSupply())));
        trackHealth(health, dead, pos);

        if (mc.hitResult instanceof BlockHitResult bhr && bhr.getType() == HitResult.Type.BLOCK) {
            BlockState s = level.getBlockState(bhr.getBlockPos());
            o.addProperty("lookingAt", shortId(BuiltInRegistries.BLOCK.getKey(s.getBlock())));
        }

        ItemStack held = p.getMainHandItem();
        o.addProperty("held", held.isEmpty() ? "empty hand" : describe(held));
        Map<String, Integer> counts = new TreeMap<>();
        JsonArray tools = new JsonArray();
        int freeSlots = 0;
        for (ItemStack stack : p.getInventory().getNonEquipmentItems()) {
            if (stack.isEmpty()) {
                freeSlots++;
                continue;
            }
            counts.merge(itemId(stack), stack.getCount(), Integer::sum);
            if (stack.isDamageableItem()) tools.add(describe(stack));
        }
        JsonObject inventory = new JsonObject();
        counts.forEach(inventory::addProperty);
        o.add("inventory", inventory);
        o.addProperty("freeSlots", freeSlots);
        o.add("tools", tools);

        JsonArray ores = new JsonArray();
        for (SeenOre ore : seenOres) {
            ores.add(ore.id + " " + Math.round(ore.distance) + "m @ " + ore.pos.getX() + " " + ore.pos.getY() + " " + ore.pos.getZ());
        }
        o.add("oresInSight", ores);
        if (!usable.isEmpty()) {
            JsonArray useful = new JsonArray();
            for (Usable u : usable) useful.add(u.id + " " + Math.round(u.distance) + "m @ " + u.pos.getX() + " " + u.pos.getY() + " " + u.pos.getZ());
            o.add("usableInSight", useful);
        }
        if (p.containerMenu != p.inventoryMenu) {
            JsonObject open = new JsonObject();
            if (mc.gui.screen() != null) open.addProperty("title", mc.gui.screen().getTitle().getString());
            Map<String, Integer> inside = new TreeMap<>();
            int free = 0;
            for (net.minecraft.world.inventory.Slot slot : p.containerMenu.slots) {
                if (slot.container == p.getInventory()) continue;
                if (slot.hasItem()) inside.merge(itemId(slot.getItem()), slot.getItem().getCount(), Integer::sum);
                else free++;
            }
            JsonObject contents = new JsonObject();
            inside.forEach(contents::addProperty);
            open.add("contents", contents);
            open.addProperty("freeSlots", free);
            o.add("openContainer", open);
        }
        String busy = Afk.HANDS.busyLabel();
        if (busy != null) o.addProperty("handsBusy", busy);
        JsonArray hostiles = new JsonArray();
        JsonArray creatures = new JsonArray();
        JsonArray players = new JsonArray();
        for (SeenMob mob : seenMobs) {
            String line = mob.id + " " + Math.round(mob.distance) + "m";
            if (mob.entity instanceof Player pl) players.add(pl.getName().getString() + " " + Math.round(mob.distance) + "m");
            else if (mob.hostile && hostiles.size() < 8) hostiles.add(line);
            else if (creatures.size() < 6) creatures.add(line);
        }
        o.add("hostilesInSight", hostiles);
        o.add("creaturesInSight", creatures);
        if (!players.isEmpty()) o.add("playersInSight", players);
        if (p.connection != null) {
            JsonArray online = new JsonArray();
            for (PlayerInfo info : p.connection.getOnlinePlayers()) {
                if (online.size() >= 12) break;
                String name = info.getProfile().name();
                if (!name.equals(p.getName().getString())) online.add(name);
            }
            o.add("playersOnline", online);
        }
        o.add("baritone", Afk.BARITONE.status());
        JsonArray crew = Agents.snapshot();
        if (!crew.isEmpty()) o.add("crew", crew.deepCopy());
        o.addProperty("helpersPossible", Agents.available());
        return o;
    }

    private void trackHealth(float health, boolean dead, BlockPos pos) {
        String where = pos.getX() + " " + pos.getY() + " " + pos.getZ();
        if (dead && !wasDead) {
            Afk.EVENTS.add("death", "died at " + where);
            Afk.ACTIVITY.note("death", "died at " + where);
        } else if (!dead && wasDead) {
            Afk.EVENTS.add("world", "respawned at " + where);
        } else if (!dead && lastHealth >= 0 && health <= lastHealth - 1.0f) {
            Afk.EVENTS.add("damage", "took damage: health " + fmt(lastHealth) + " -> " + fmt(health));
        }
        wasDead = dead;
        lastHealth = health;
    }

    static String describe(ItemStack stack) {
        String id = itemId(stack);
        if (stack.isDamageableItem()) {
            return id + " (" + (stack.getMaxDamage() - stack.getDamageValue()) + "/" + stack.getMaxDamage() + ")";
        }
        return stack.getCount() > 1 ? id + " x" + stack.getCount() : id;
    }

    static String itemId(ItemStack stack) {
        return shortId(BuiltInRegistries.ITEM.getKey(stack.getItem()));
    }

    static String shortId(Identifier id) {
        return "minecraft".equals(id.getNamespace()) ? id.getPath() : id.toString();
    }

    private static String fmt(float v) {
        return String.valueOf(Math.round(v * 10) / 10.0);
    }
}
