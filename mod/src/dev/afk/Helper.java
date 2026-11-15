package dev.afk;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.npc.InventoryCarrier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * One of Herobrine's helpers: a second Herobrine with his own body, in a world running on this
 * computer. He plays by the same rules you do: he walks there, only goes for blocks he can actually
 * see, mines at a player's speed and only gets drops with the right tool, carries what he picks up
 * and hands it over or puts it in a chest. The crew brain gives him jobs; he reports how they went.
 */
final class Helper extends PathfinderMob implements InventoryCarrier {
    private static final double REACH = 4.5;

    private final SimpleContainer pack = new SimpleContainer(27);
    private UUID owner;
    private int number = 2;

    // the current job
    private String jobType = "follow";
    private JsonObject job = new JsonObject();
    private long jobId;
    private int repeatSeconds;
    private long repeatAtTick = -1;
    private String status = "following you";
    private String lastResult = "";
    private int progress;
    private int goal;

    // working state
    private BlockPos target;
    private int targetTicks;
    private int breakTicks;
    private int breakNeeded;
    private BlockPos home;
    private int wanders;
    private int scanCooldown;
    private int stuckTicks;
    private int attackCooldown;
    private final Set<BlockPos> unreachable = new HashSet<>();
    private LivingEntity enemy;

    Helper(EntityType<? extends Helper> type, Level level) {
        super(type, level);
        setPersistenceRequired();
        setCanPickUpLoot(false); // he picks things up into his pack himself
        getNavigation().setCanFloat(true);
    }

    static AttributeSupplier.Builder attributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0)
                .add(Attributes.MOVEMENT_SPEED, 0.32)
                .add(Attributes.ATTACK_DAMAGE, 2.0)
                .add(Attributes.FOLLOW_RANGE, 48.0)
                .add(Attributes.STEP_HEIGHT, 1.0);
    }

    @Override
    protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this));
        goalSelector.addGoal(8, new LookAtPlayerGoal(this, Player.class, 8.0f));
        goalSelector.addGoal(9, new RandomLookAroundGoal(this));
    }

    void setup(UUID ownerId, int n) {
        owner = ownerId;
        number = n;
        setCustomName(Component.literal(displayName()));
        setCustomNameVisible(true);
    }

    String displayName() {
        return "Herobrine " + Agents.roman(number);
    }

    int number() {
        return number;
    }

    UUID owner() {
        return owner;
    }

    @Override
    public SimpleContainer getInventory() {
        return pack;
    }

    @Override
    public boolean removeWhenFarAway(double distance) {
        return false;
    }

    // ------------------------------------------------------------------ saving

    @Override
    protected void addAdditionalSaveData(ValueOutput out) {
        super.addAdditionalSaveData(out);
        writeInventoryToTag(out);
        if (owner != null) out.putString("AfkOwner", owner.toString());
        out.putInt("AfkNumber", number);
    }

    @Override
    protected void readAdditionalSaveData(ValueInput in) {
        super.readAdditionalSaveData(in);
        readInventoryFromTag(in);
        String o = in.getStringOr("AfkOwner", "");
        if (!o.isEmpty()) {
            try {
                owner = UUID.fromString(o);
            } catch (IllegalArgumentException ignored) {
            }
        }
        number = in.getIntOr("AfkNumber", 2);
        setup(owner, number);
    }

    // ------------------------------------------------------------------ jobs

    /** Gives him a new job (see the crew brain's list). repeatSeconds > 0 runs it again after it finishes. */
    void assign(JsonObject newJob, int repeat) {
        String type = newJob.has("do") ? newJob.get("do").getAsString().toLowerCase(Locale.ROOT) : "wait";
        jobType = type;
        job = newJob;
        jobId++;
        repeatSeconds = Math.max(0, Math.min(3600, repeat));
        repeatAtTick = -1;
        resetWork();
        goal = newJob.has("count") ? Math.max(1, Math.min(640, newJob.get("count").getAsInt())) : 0;
        progress = 0;
        home = blockPosition();
        status = describeJob();
        lastResult = "";
    }

    void stopWork(String why) {
        jobType = "wait";
        job = new JsonObject();
        repeatSeconds = 0;
        repeatAtTick = -1;
        resetWork();
        getNavigation().stop();
        status = "waiting (" + why + ")";
    }

    private void resetWork() {
        clearBreaking();
        target = null;
        targetTicks = 0;
        wanders = 0;
        unreachable.clear();
        enemy = null;
    }

    private void finish(boolean ok, String result) {
        clearBreaking();
        getNavigation().stop();
        lastResult = (ok ? "done: " : "couldn't finish: ") + result;
        Agents.report(this, jobType, ok, result);
        if (repeatSeconds > 0) {
            repeatAtTick = tickCount + repeatSeconds * 20L;
            status = "repeats in " + repeatSeconds + "s (" + result + ")";
        } else {
            jobType = "wait";
            status = "waiting (" + result + ")";
        }
    }

    String describeJob() {
        return switch (jobType) {
            case "follow" -> "following you";
            case "come" -> "coming to you";
            case "goto" -> "walking to " + coords(job);
            case "gather" -> "gathering " + names(job) + (goal > 0 ? " " + progress + "/" + goal : "");
            case "farm" -> "farming";
            case "guard" -> "on guard";
            case "deposit" -> "putting things in a chest";
            case "take" -> "taking from a chest";
            case "give" -> "bringing you items";
            case "craft" -> "crafting " + str(job, "item", "?");
            case "place" -> "placing " + str(job, "item", "?");
            case "tunnel" -> "digging a tunnel";
            default -> "waiting";
        };
    }

    JsonObject toJson(ServerPlayer you) {
        JsonObject o = new JsonObject();
        o.addProperty("name", displayName());
        o.addProperty("number", number);
        JsonObject p = new JsonObject();
        p.addProperty("x", getBlockX());
        p.addProperty("y", getBlockY());
        p.addProperty("z", getBlockZ());
        o.add("pos", p);
        o.addProperty("health", Math.round(getHealth()));
        o.addProperty("job", jobType);
        o.add("jobArgs", job.deepCopy());
        o.addProperty("status", status);
        if (goal > 0) o.addProperty("progress", progress + "/" + goal);
        if (repeatSeconds > 0) o.addProperty("repeatEverySeconds", repeatSeconds);
        if (!lastResult.isEmpty()) o.addProperty("lastResult", lastResult);
        o.addProperty("holding", itemId(getMainHandItem()));
        JsonObject inv = new JsonObject();
        for (Map.Entry<String, Integer> e : inventoryCounts().entrySet()) inv.addProperty(e.getKey(), e.getValue());
        o.add("inventory", inv);
        if (you != null) o.addProperty("distanceToYou", Math.round(distanceTo(you)));
        return o;
    }

    Map<String, Integer> inventoryCounts() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        ItemStack hand = getMainHandItem();
        if (!hand.isEmpty()) counts.merge(itemId(hand), hand.getCount(), Integer::sum);
        for (int i = 0; i < pack.getContainerSize(); i++) {
            ItemStack s = pack.getItem(i);
            if (!s.isEmpty()) counts.merge(itemId(s), s.getCount(), Integer::sum);
        }
        return counts;
    }

    String status() {
        return status;
    }

    // ------------------------------------------------------------------ the tick

    @Override
    protected void customServerAiStep(ServerLevel level) {
        super.customServerAiStep(level);
        if (attackCooldown > 0) attackCooldown--;
        if (tickCount % 4 == 0) pickUpNearby(level);
        if (tickCount % 40 == 0 && getHealth() < getMaxHealth()) heal(0.5f);

        if (repeatAtTick >= 0) {
            if (tickCount >= repeatAtTick) {
                JsonObject again = job.deepCopy();
                int every = repeatSeconds;
                assign(again, every);
            } else {
                return;
            }
        }
        if (defend(level)) return;

        try {
            switch (jobType) {
                case "follow" -> follow(level, false);
                case "come" -> follow(level, true);
                case "goto" -> gotoJob();
                case "gather" -> gather(level);
                case "farm" -> farm(level);
                case "guard" -> guard(level);
                case "deposit", "take" -> chestJob(level);
                case "give" -> give(level);
                case "craft" -> craftJob(level);
                case "place" -> placeJob(level);
                case "tunnel" -> tunnel(level);
                default -> {
                }
            }
        } catch (RuntimeException e) {
            Afk.LOG.warn("{} hit a problem doing {}", displayName(), jobType, e);
            finish(false, "something went wrong: " + e.getMessage());
        }
    }

    private ServerPlayer ownerPlayer() {
        if (owner == null || level().getServer() == null) return null;
        return level().getServer().getPlayerList().getPlayer(owner);
    }

    // ---- follow / come / goto

    private void follow(ServerLevel level, boolean once) {
        ServerPlayer you = ownerPlayer();
        if (you == null || you.level() != level) {
            status = "waiting (can't see you)";
            return;
        }
        double d = distanceTo(you);
        if (once && d < 3) {
            finish(true, "I'm here");
            return;
        }
        if (d > 12 && getNavigation().isDone()) stuckTicks++;
        else stuckTicks = 0;
        if (d > 48 || stuckTicks > 100) {
            stuckTicks = 0;
            BlockPos spot = Agents.freeSpotNear(level, you.blockPosition());
            if (spot != null) {
                teleportTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5);
                getNavigation().stop();
            }
            return;
        }
        if (d > 4) {
            if (tickCount % 10 == 0) getNavigation().moveTo(you, d > 10 ? 1.25 : 1.0);
        } else if (!once) {
            getNavigation().stop();
        }
        status = once ? "coming to you" : "following you";
    }

    private void gotoJob() {
        BlockPos to = pos(job);
        if (to == null) {
            finish(false, "no coordinates given");
            return;
        }
        double d = Math.sqrt(blockPosition().distSqr(to));
        if (d < 2.5) {
            finish(true, "arrived at " + to.toShortString());
            return;
        }
        if (!walkTo(to, 1.5)) finish(false, "couldn't find a way to " + to.toShortString());
    }

    /** Walks toward a spot; returns false once it is clearly unreachable. */
    private boolean walkTo(BlockPos to, double closeEnough) {
        if (Math.sqrt(blockPosition().distSqr(to)) <= closeEnough) return true;
        targetTicks++;
        if (getNavigation().isDone() || tickCount % 40 == 0) {
            getNavigation().moveTo(getNavigation().createPath(to, 1), 1.0);
        }
        return targetTicks < 20 * 45;
    }

    // ---- gather blocks

    private void gather(ServerLevel level) {
        Predicate<BlockState> wanted = matcher(job);
        if (wanted == null) {
            finish(false, "I don't know what " + names(job) + " is");
            return;
        }
        if (goal > 0 && progress >= goal) {
            if (collectDrops(level)) return; // pick up the last drops before reporting
            finish(true, "got " + progress + " " + names(job));
            return;
        }
        if (target != null && !wanted.test(level.getBlockState(target))) {
            clearBreaking();
            target = null;
        }
        if (target == null) {
            if (collectDrops(level)) return;
            if (scanCooldown-- > 0) return;
            scanCooldown = 10;
            target = findVisible(level, wanted, 16);
            targetTicks = 0;
            if (target == null) {
                if (wander(level)) return;
                finish(progress > 0, progress > 0 ? "got " + progress + " " + names(job) + ", then found no more in sight"
                        : "no " + names(job) + " in sight around here");
                return;
            }
        }
        status = "gathering " + names(job) + (goal > 0 ? " " + progress + "/" + goal : "");
        if (mine(level, target)) {
            progress++;
            BlockPos above = target.above();
            target = wanted.test(level.getBlockState(above)) && canReach(above) ? above : null; // the rest of the tree
            wanders = 0;
        }
    }

    /** Walks into reach, looks, swings and breaks a block at a player's speed. True once it breaks. */
    private boolean mine(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) return true;
        if (!canReach(pos)) {
            clearBreaking();
            if (!walkTo(pos, 1.0)) {
                unreachable.add(pos.immutable());
                target = null;
            }
            return false;
        }
        getNavigation().stop();
        getLookControl().setLookAt(Vec3.atCenterOf(pos));
        if (breakNeeded == 0) {
            equipBestTool(state);
            breakNeeded = breakTime(level, pos, state, getMainHandItem());
            breakTicks = 0;
            if (breakNeeded < 0) {
                unreachable.add(pos.immutable());
                target = null;
                return false;
            }
        }
        breakTicks++;
        if (breakTicks % 5 == 1) swingForAttack(InteractionHand.MAIN_HAND);
        level.destroyBlockProgress(getId(), pos, Math.min(9, breakTicks * 10 / Math.max(1, breakNeeded)));
        if (breakTicks < breakNeeded) return false;

        ItemStack tool = getMainHandItem();
        boolean drops = !state.requiresCorrectToolForDrops() || tool.isCorrectToolForDrops(state);
        BlockEntity be = level.getBlockEntity(pos);
        if (drops) Block.dropResources(state, level, pos, be, this, tool);
        level.destroyBlock(pos, false, this);
        level.destroyBlockProgress(getId(), pos, -1);
        if (!tool.isEmpty()) tool.hurtAndBreak(1, this, EquipmentSlot.MAINHAND);
        breakNeeded = 0;
        breakTicks = 0;
        return true;
    }

    private void clearBreaking() {
        if (target != null && level() instanceof ServerLevel sl) sl.destroyBlockProgress(getId(), target, -1);
        breakNeeded = 0;
        breakTicks = 0;
    }

    /** Same formula as a player: hardness, tool speed, and 3x slower without the right tool. */
    private static int breakTime(ServerLevel level, BlockPos pos, BlockState state, ItemStack tool) {
        float hardness = state.getDestroySpeed(level, pos);
        if (hardness < 0) return -1;
        if (hardness == 0) return 1;
        float speed = tool.isEmpty() ? 1.0f : tool.getDestroySpeed(state);
        boolean right = !state.requiresCorrectToolForDrops() || tool.isCorrectToolForDrops(state);
        double perTick = speed / hardness / (right ? 30.0 : 100.0);
        return (int) Math.ceil(1.0 / perTick);
    }

    private void equipBestTool(BlockState state) {
        int best = -1;
        float bestSpeed = getMainHandItem().isEmpty() ? 1.0f : getMainHandItem().getDestroySpeed(state);
        for (int i = 0; i < pack.getContainerSize(); i++) {
            ItemStack s = pack.getItem(i);
            if (s.isEmpty()) continue;
            float sp = s.getDestroySpeed(state);
            if (sp > bestSpeed + 0.01f) {
                bestSpeed = sp;
                best = i;
            }
        }
        if (best >= 0) swapIntoHand(best);
    }

    private void swapIntoHand(int slot) {
        ItemStack hand = getMainHandItem().copy();
        setItemSlot(EquipmentSlot.MAINHAND, pack.getItem(slot).copy());
        pack.setItem(slot, hand);
    }

    private boolean canReach(BlockPos pos) {
        return getEyePosition().distanceTo(Vec3.atCenterOf(pos)) <= REACH;
    }

    /** The nearest matching block with an open face that he can actually see from where he stands. */
    private BlockPos findVisible(ServerLevel level, Predicate<BlockState> wanted, int radius) {
        BlockPos me = blockPosition();
        List<BlockPos> found = new ArrayList<>();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dy = -6; dy <= 8; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    m.set(me.getX() + dx, me.getY() + dy, me.getZ() + dz);
                    if (!wanted.test(level.getBlockState(m))) continue;
                    if (unreachable.contains(m) || !exposed(level, m)) continue;
                    found.add(m.immutable());
                }
            }
        }
        found.sort((a, b) -> Double.compare(a.distSqr(me), b.distSqr(me)));
        int checks = 0;
        for (BlockPos p : found) {
            if (++checks > 48) break;
            if (sees(level, p)) return p;
        }
        return null;
    }

    private static boolean exposed(ServerLevel level, BlockPos p) {
        for (Direction d : Direction.values()) {
            BlockState n = level.getBlockState(p.relative(d));
            if (!n.canOcclude()) return true;
        }
        return false;
    }

    private boolean sees(ServerLevel level, BlockPos p) {
        Vec3 eye = getEyePosition();
        Vec3 c = Vec3.atCenterOf(p);
        for (Direction d : Direction.values()) {
            Vec3 face = c.add(d.getStepX() * 0.45, d.getStepY() * 0.45, d.getStepZ() * 0.45);
            BlockHitResult hit = level.clip(new ClipContext(eye, face, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this));
            if (hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(p)) return true;
        }
        return false;
    }

    /** Looks somewhere new when nothing is in sight. */
    private boolean wander(ServerLevel level) {
        if (wanders >= 6) return false;
        if (getNavigation().isInProgress()) return true;
        wanders++;
        double angle = random.nextDouble() * Math.PI * 2;
        int dist = 10 + random.nextInt(12);
        BlockPos to = level.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                blockPosition().offset((int) (Math.cos(angle) * dist), 0, (int) (Math.sin(angle) * dist)));
        status = "looking around for " + names(job);
        getNavigation().moveTo(to.getX() + 0.5, to.getY(), to.getZ() + 0.5, 1.0);
        return true;
    }

    // ---- farm: harvest ripe crops in sight and replant them

    private void farm(ServerLevel level) {
        if (target == null) {
            if (collectDrops(level)) return;
            if (scanCooldown-- > 0) return;
            scanCooldown = 10;
            target = findVisible(level, s -> s.getBlock() instanceof CropBlock crop && crop.isMaxAge(s), 12);
            targetTicks = 0;
            if (target == null) {
                finish(true, progress > 0 ? "harvested and replanted " + progress + " crops" : "nothing ripe yet");
                return;
            }
        }
        status = "farming, " + progress + " harvested";
        BlockState crop = level.getBlockState(target);
        if (mine(level, target)) {
            Item seed = seedFor(crop.getBlock());
            BlockPos at = target;
            if (seed != null && takeOne(seed) && level.getBlockState(at).isAir() && level.getBlockState(at.below()).is(Blocks.FARMLAND)) {
                level.setBlock(at, crop.getBlock().defaultBlockState(), Block.UPDATE_ALL);
                level.playSound(null, at, SoundEvents.CROP_PLANTED, SoundSource.BLOCKS, 1.0f, 1.0f);
            }
            progress++;
            target = null;
        }
    }

    private static Item seedFor(Block crop) {
        if (crop == Blocks.WHEAT) return net.minecraft.world.item.Items.WHEAT_SEEDS;
        if (crop == Blocks.CARROTS) return net.minecraft.world.item.Items.CARROT;
        if (crop == Blocks.POTATOES) return net.minecraft.world.item.Items.POTATO;
        if (crop == Blocks.BEETROOTS) return net.minecraft.world.item.Items.BEETROOT_SEEDS;
        return null;
    }

    // ---- guard and self-defence

    private void guard(ServerLevel level) {
        ServerPlayer you = ownerPlayer();
        BlockPos center = pos(job) != null ? pos(job) : you != null ? you.blockPosition() : home;
        int radius = job.has("radius") ? job.get("radius").getAsInt() : 10;
        status = "on guard";
        LivingEntity threat = nearestHostile(level, Vec3.atCenterOf(center), radius);
        if (threat != null) {
            fight(level, threat);
        } else if (Math.sqrt(blockPosition().distSqr(center)) > 4 && tickCount % 20 == 0) {
            getNavigation().moveTo(center.getX() + 0.5, center.getY(), center.getZ() + 0.5, 1.0);
        }
    }

    /** Hits back at anything hostile that gets close to him or to you, whatever the job. */
    private boolean defend(ServerLevel level) {
        if (enemy != null && (!enemy.isAlive() || enemy.distanceTo(this) > 12)) enemy = null;
        if (enemy == null && tickCount % 10 == 0) {
            LivingEntity near = nearestHostile(level, position(), 4);
            ServerPlayer you = ownerPlayer();
            if (near == null && you != null && you.level() == level) near = nearestHostile(level, you.position(), 4);
            enemy = near;
        }
        if (enemy == null) return false;
        if (getHealth() <= 6) {
            retreat(enemy);
            return true;
        }
        fight(level, enemy);
        return true;
    }

    /** Badly hurt: back off toward you instead of trading blows. */
    private void retreat(LivingEntity foe) {
        ServerPlayer you = ownerPlayer();
        Vec3 away = position().subtract(foe.position()).normalize().scale(8).add(position());
        if (you != null && you.level() == level() && distanceTo(you) > 3) away = you.position();
        if (tickCount % 10 == 0) getNavigation().moveTo(away.x, away.y, away.z, 1.3);
        status = "hurt, backing off";
    }

    private void fight(ServerLevel level, LivingEntity foe) {
        equipWeapon();
        getLookControl().setLookAt(foe, 30f, 30f);
        if (isWithinMeleeAttackRange(foe)) {
            getNavigation().stop();
            if (attackCooldown == 0) {
                swingForAttack(InteractionHand.MAIN_HAND);
                doHurtTarget(level, foe);
                attackCooldown = 12;
            }
        } else if (tickCount % 5 == 0) {
            getNavigation().moveTo(foe, 1.2);
        }
        status = "fighting a " + BuiltInRegistries.ENTITY_TYPE.getKey(foe.getType()).getPath().replace('_', ' ');
    }

    private LivingEntity nearestHostile(ServerLevel level, Vec3 around, double radius) {
        LivingEntity best = null;
        double bestD = radius * radius;
        for (Monster m : level.getEntitiesOfClass(Monster.class, new net.minecraft.world.phys.AABB(around, around).inflate(radius))) {
            if (!m.isAlive()) continue;
            double d = m.distanceToSqr(around);
            if (d < bestD && hasLineOfSight(m)) {
                best = m;
                bestD = d;
            }
        }
        return best;
    }

    private void equipWeapon() {
        int best = -1;
        double bestScore = weaponScore(getMainHandItem());
        for (int i = 0; i < pack.getContainerSize(); i++) {
            double s = weaponScore(pack.getItem(i));
            if (s > bestScore) {
                bestScore = s;
                best = i;
            }
        }
        if (best >= 0) swapIntoHand(best);
    }

    private static double weaponScore(ItemStack s) {
        if (s.isEmpty()) return 0;
        String id = itemId(s);
        double base = id.endsWith("_sword") ? 10 : id.endsWith("_axe") ? 8 : id.endsWith("_pickaxe") ? 5 : id.endsWith("_shovel") ? 4 : 0;
        if (base == 0) return 0;
        if (id.startsWith("netherite")) return base + 5;
        if (id.startsWith("diamond")) return base + 4;
        if (id.startsWith("iron")) return base + 3;
        if (id.startsWith("stone")) return base + 2;
        return base + 1;
    }

    // ---- chests, giving, placing

    private void chestJob(ServerLevel level) {
        if (target == null) {
            target = pos(job);
            if (target == null) {
                target = findVisible(level, s -> s.is(Blocks.CHEST) || s.is(Blocks.BARREL) || s.is(Blocks.TRAPPED_CHEST), 16);
            }
            if (target == null) {
                finish(false, "no chest in sight");
                return;
            }
        }
        if (!canReach(target)) {
            if (!walkTo(target, 1.0)) finish(false, "couldn't reach the chest");
            return;
        }
        getNavigation().stop();
        getLookControl().setLookAt(Vec3.atCenterOf(target));
        Container chest = HopperBlockEntity.getContainerAt(level, target);
        if (chest == null) {
            finish(false, "that isn't a chest");
            return;
        }
        level.playSound(null, target, SoundEvents.CHEST_OPEN, SoundSource.BLOCKS, 0.5f, 1.0f);
        swingForAttack(InteractionHand.MAIN_HAND);
        int moved = "deposit".equals(jobType) ? deposit(chest) : take(chest);
        chest.setChanged();
        level.playSound(null, target, SoundEvents.CHEST_CLOSE, SoundSource.BLOCKS, 0.5f, 1.0f);
        finish(true, ("deposit".equals(jobType) ? "put " : "took ") + moved + " items");
    }

    private int deposit(Container chest) {
        Set<String> only = idSet(job, "items");
        int moved = 0;
        for (int i = 0; i < pack.getContainerSize(); i++) {
            ItemStack s = pack.getItem(i);
            if (s.isEmpty() || (only != null && !only.contains(itemId(s)))) continue;
            int before = s.getCount();
            ItemStack left = insert(chest, s.copy());
            pack.setItem(i, left);
            moved += before - left.getCount();
        }
        return moved;
    }

    private int take(Container chest) {
        String want = str(job, "item", "");
        int count = job.has("count") ? job.get("count").getAsInt() : 64;
        int moved = 0;
        for (int i = 0; i < chest.getContainerSize() && moved < count; i++) {
            ItemStack s = chest.getItem(i);
            if (s.isEmpty() || (!want.isEmpty() && !itemId(s).equals(want))) continue;
            int n = Math.min(count - moved, s.getCount());
            ItemStack part = s.split(n);
            ItemStack left = pack.addItem(part);
            if (!left.isEmpty()) {
                s.grow(left.getCount());
                moved += n - left.getCount();
                break;
            }
            moved += n;
        }
        return moved;
    }

    private static ItemStack insert(Container c, ItemStack stack) {
        for (int i = 0; i < c.getContainerSize() && !stack.isEmpty(); i++) {
            ItemStack in = c.getItem(i);
            if (!in.isEmpty() && ItemStack.isSameItemSameComponents(in, stack)) {
                int room = Math.min(c.getMaxStackSize(), in.getMaxStackSize()) - in.getCount();
                int n = Math.min(room, stack.getCount());
                if (n > 0) {
                    in.grow(n);
                    stack.shrink(n);
                }
            }
        }
        for (int i = 0; i < c.getContainerSize() && !stack.isEmpty(); i++) {
            if (c.getItem(i).isEmpty()) {
                c.setItem(i, stack.copy());
                stack.setCount(0);
            }
        }
        return stack;
    }

    private void give(ServerLevel level) {
        ServerPlayer you = ownerPlayer();
        if (you == null || you.level() != level) {
            finish(false, "I can't find you");
            return;
        }
        if (distanceTo(you) > 2.5) {
            if (tickCount % 10 == 0) getNavigation().moveTo(you, 1.2);
            if (getNavigation().isDone() && distanceTo(you) > 6) stuckTicks++;
            else stuckTicks = 0;
            if (stuckTicks > 100) { // no way up to you: catch up like a tamed wolf
                stuckTicks = 0;
                BlockPos spot = Agents.freeSpotNear(level, you.blockPosition());
                if (spot != null) teleportTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5);
            }
            if (++targetTicks > 20 * 60) finish(false, "couldn't get to you");
            return;
        }
        getNavigation().stop();
        getLookControl().setLookAt(you, 30f, 30f);
        Set<String> only = idSet(job, "items");
        int limit = job.has("count") ? job.get("count").getAsInt() : Integer.MAX_VALUE;
        int given = 0;
        for (int i = 0; i < pack.getContainerSize() && given < limit; i++) {
            ItemStack s = pack.getItem(i);
            if (s.isEmpty() || (only != null && !only.contains(itemId(s)))) continue;
            int n = Math.min(limit - given, s.getCount());
            toss(level, s.split(n), you);
            given += n;
        }
        swingForAttack(InteractionHand.MAIN_HAND);
        finish(given > 0, given > 0 ? "handed you " + given + " items" : "I had nothing like that to give");
    }

    private void toss(ServerLevel level, ItemStack stack, ServerPlayer you) {
        Vec3 from = getEyePosition().subtract(0, 0.3, 0);
        ItemEntity item = new ItemEntity(level, from.x, from.y, from.z, stack);
        Vec3 dir = you.position().add(0, 1, 0).subtract(from).normalize().scale(0.3);
        item.setDeltaMovement(dir.x, dir.y + 0.1, dir.z);
        item.setPickUpDelay(10);
        item.setThrower(this);
        level.addFreshEntity(item);
    }

    private void placeJob(ServerLevel level) {
        Item item = item(str(job, "item", ""));
        if (!(item instanceof BlockItem bi)) {
            finish(false, "I can't place that");
            return;
        }
        BlockPos at = pos(job);
        if (at == null) at = blockPosition().relative(getDirection());
        if (!canReach(at)) {
            if (!walkTo(at, 2.0)) finish(false, "couldn't get close enough");
            return;
        }
        if (!level.getBlockState(at).canBeReplaced()) {
            finish(false, "something is already there");
            return;
        }
        if (!takeOne(item)) {
            finish(false, "I don't have any " + str(job, "item", ""));
            return;
        }
        getLookControl().setLookAt(Vec3.atCenterOf(at));
        swingForAttack(InteractionHand.MAIN_HAND);
        BlockState state = bi.getBlock().defaultBlockState();
        level.setBlock(at, state, Block.UPDATE_ALL);
        level.playSound(null, at, state.getSoundType().getPlaceSound(), SoundSource.BLOCKS, 1.0f, 1.0f);
        finish(true, "placed " + str(job, "item", ""));
    }

    // ---- crafting (the basics, with a crafting table in reach or in his pack for the big ones)

    private void craftJob(ServerLevel level) {
        String id = str(job, "item", "");
        int count = Math.max(1, goal);
        String result = HelperRecipes.craft(this, level, id, count);
        if (result.startsWith("ok:")) finish(true, result.substring(3));
        else finish(false, result);
    }

    // ---- tunnel: a 1x2 tunnel straight ahead, taking any ore it uncovers

    private void tunnel(ServerLevel level) {
        int length = job.has("length") ? Math.min(64, job.get("length").getAsInt()) : 16;
        if (goal == 0) goal = length;
        if (progress >= goal) {
            finish(true, "dug " + progress + " blocks of tunnel");
            return;
        }
        Direction dir = job.has("dir") ? Direction.byName(job.get("dir").getAsString()) : null;
        if (dir == null || dir.getAxis().isVertical()) dir = getDirection();
        job.addProperty("dir", dir.getName());
        status = "tunnelling " + progress + "/" + goal;
        if (collectDrops(level)) return;
        BlockPos ore = findVisible(level, s -> idOf(s).endsWith("_ore"), 4);
        if (ore != null && canReach(ore)) {
            mine(level, ore);
            return;
        }
        BlockPos feet = home.relative(dir, progress + 1);
        BlockPos head = feet.above();
        if (!level.getBlockState(head).isAir()) {
            mine(level, head);
            return;
        }
        if (!level.getBlockState(feet).isAir()) {
            mine(level, feet);
            return;
        }
        if (!walkTo(feet, 0.8)) {
            finish(false, "the tunnel is blocked");
            return;
        }
        if (Math.sqrt(blockPosition().distSqr(feet)) <= 1.0) {
            progress++;
            targetTicks = 0;
        }
    }

    // ------------------------------------------------------------------ inventory

    /** Walks over to drops lying near him, like a player does after breaking something. True while he's on his way. */
    private boolean collectDrops(ServerLevel level) {
        ItemEntity nearest = null;
        double best = 6 * 6;
        for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, getBoundingBox().inflate(6, 3, 6))) {
            if (!item.isAlive() || item.getItem().isEmpty() || !pack.canAddItem(item.getItem())) continue;
            if (item.getOwner() == this && item.getAge() < 200) continue;
            double d = distanceToSqr(item);
            if (d < best) {
                best = d;
                nearest = item;
            }
        }
        if (nearest == null) {
            collectTicks = 0;
            return false;
        }
        if (++collectTicks > 20 * 8) { // couldn't get to it: leave it
            collectTicks = 0;
            return false;
        }
        if (tickCount % 10 == 0 || getNavigation().isDone()) getNavigation().moveTo(nearest, 1.1);
        status = "picking up " + itemId(nearest.getItem()).replace('_', ' ');
        return true;
    }

    private int collectTicks;

    private void pickUpNearby(ServerLevel level) {
        for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, getBoundingBox().inflate(1.8, 1.0, 1.8))) {
            if (!item.isAlive() || item.hasPickUpDelay() || item.getItem().isEmpty()) continue;
            if (item.getOwner() == this && item.getAge() < 200) continue; // what he just handed you stays yours
            InventoryCarrier.pickUpItem(level, this, this, item);
        }
    }

    @Override
    public boolean wantsToPickUp(ServerLevel level, ItemStack stack) {
        return pack.canAddItem(stack);
    }

    boolean takeOne(Item item) {
        for (int i = 0; i < pack.getContainerSize(); i++) {
            ItemStack s = pack.getItem(i);
            if (!s.isEmpty() && s.is(item)) {
                s.shrink(1);
                return true;
            }
        }
        ItemStack hand = getMainHandItem();
        if (!hand.isEmpty() && hand.is(item)) {
            hand.shrink(1);
            return true;
        }
        return false;
    }

    int count(Item item) {
        int n = pack.countItem(item);
        if (getMainHandItem().is(item)) n += getMainHandItem().getCount();
        return n;
    }

    /** Drops everything he carries where he stands (when dismissed or killed). */
    void dropAll(ServerLevel level) {
        for (int i = 0; i < pack.getContainerSize(); i++) {
            ItemStack s = pack.removeItemNoUpdate(i);
            if (!s.isEmpty()) spawnAtLocation(level, s);
        }
        ItemStack hand = getMainHandItem();
        if (!hand.isEmpty()) {
            spawnAtLocation(level, hand.copy());
            setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        }
    }

    @Override
    public void die(net.minecraft.world.damagesource.DamageSource source) {
        if (level() instanceof ServerLevel sl) {
            dropAll(sl);
            String by = source.getEntity() != null
                    ? "a " + BuiltInRegistries.ENTITY_TYPE.getKey(source.getEntity().getType()).getPath().replace('_', ' ')
                    : source.getMsgId();
            Agents.report(this, jobType, false, "I was killed by " + by + " at " + blockPosition().toShortString());
        }
        super.die(source);
    }

    @Override
    public void remove(net.minecraft.world.entity.Entity.RemovalReason reason) {
        Afk.LOG.info("{} left the world ({}) at {}", displayName(), reason, blockPosition().toShortString());
        if (reason == net.minecraft.world.entity.Entity.RemovalReason.DISCARDED && !dismissed) {
            Agents.event("result", displayName(), "I was removed from the world (" + reason + ")");
        }
        super.remove(reason);
    }

    private boolean dismissed;

    void markDismissed() {
        dismissed = true;
    }

    void puff(ServerLevel level) {
        level.sendParticles(ParticleTypes.LARGE_SMOKE, getX(), getY() + 1, getZ(), 24, 0.3, 0.6, 0.3, 0.02);
        level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, getX(), getY() + 1, getZ(), 12, 0.3, 0.6, 0.3, 0.01);
        level.playSound(null, blockPosition(), SoundEvents.CHAIN_BREAK, SoundSource.NEUTRAL, 1.0f, 0.7f);
    }

    // ------------------------------------------------------------------ helpers

    static String itemId(ItemStack s) {
        return s.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
    }

    static String idOf(BlockState s) {
        return BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath();
    }

    static Item item(String id) {
        Identifier key = Identifier.tryParse(id.contains(":") ? id : "minecraft:" + id);
        if (key == null) return null;
        return BuiltInRegistries.ITEM.getOptional(key).orElse(null);
    }

    private static String str(JsonObject o, String k, String def) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : def;
    }

    private static BlockPos pos(JsonObject o) {
        if (o.has("x") && o.has("y") && o.has("z")) return new BlockPos(o.get("x").getAsInt(), o.get("y").getAsInt(), o.get("z").getAsInt());
        return null;
    }

    private static String coords(JsonObject o) {
        BlockPos p = pos(o);
        return p == null ? "?" : p.toShortString();
    }

    private static Set<String> idSet(JsonObject o, String k) {
        if (!o.has(k)) return null;
        JsonElement e = o.get(k);
        if (e.isJsonPrimitive() && e.getAsString().equalsIgnoreCase("all")) return null;
        Set<String> out = new HashSet<>();
        if (e.isJsonArray()) for (JsonElement x : e.getAsJsonArray()) out.add(strip(x.getAsString()));
        else out.add(strip(e.getAsString()));
        return out;
    }

    private static String strip(String id) {
        return id.startsWith("minecraft:") ? id.substring(10) : id;
    }

    private static List<String> blocksOf(JsonObject o) {
        List<String> out = new ArrayList<>();
        JsonElement e = o.has("blocks") ? o.get("blocks") : o.get("block");
        if (e == null) return out;
        if (e.isJsonArray()) for (JsonElement x : e.getAsJsonArray()) out.add(strip(x.getAsString().toLowerCase(Locale.ROOT)));
        else out.add(strip(e.getAsString().toLowerCase(Locale.ROOT)));
        return out;
    }

    private static String names(JsonObject o) {
        List<String> b = blocksOf(o);
        return b.isEmpty() ? "blocks" : String.join("/", b).replace('_', ' ');
    }

    /** "logs", "ores", "stone" and exact block ids. Ores still have to be in sight to count. */
    private static Predicate<BlockState> matcher(JsonObject o) {
        List<String> names = blocksOf(o);
        if (names.isEmpty()) return null;
        List<Predicate<BlockState>> any = new ArrayList<>();
        for (String n : names) {
            switch (n) {
                case "log", "logs", "wood", "tree", "trees" -> any.add(s -> s.is(BlockTags.LOGS));
                case "ore", "ores" -> any.add(s -> idOf(s).endsWith("_ore"));
                case "stone", "cobblestone", "rock" -> any.add(s -> s.is(Blocks.STONE) || s.is(Blocks.COBBLESTONE) || s.is(Blocks.DEEPSLATE)
                        || s.is(Blocks.ANDESITE) || s.is(Blocks.DIORITE) || s.is(Blocks.GRANITE));
                case "dirt" -> any.add(s -> s.is(Blocks.DIRT) || s.is(Blocks.GRASS_BLOCK) || s.is(Blocks.COARSE_DIRT));
                case "sand" -> any.add(s -> s.is(Blocks.SAND) || s.is(Blocks.RED_SAND));
                case "crops" -> any.add(s -> s.getBlock() instanceof CropBlock crop && crop.isMaxAge(s));
                default -> {
                    String id = n.endsWith("s") && !n.endsWith("ss") && item(n) == null && blockExists(n.substring(0, n.length() - 1))
                            ? n.substring(0, n.length() - 1) : n;
                    if (!blockExists(id)) return null;
                    any.add(s -> {
                        if (!idOf(s).equals(id)) return false;
                        return !(s.getBlock() instanceof CropBlock crop) || crop.isMaxAge(s);
                    });
                }
            }
        }
        return s -> {
            for (Predicate<BlockState> p : any) if (p.test(s)) return true;
            return false;
        };
    }

    private static boolean blockExists(String id) {
        Identifier key = Identifier.tryParse("minecraft:" + id);
        return key != null && BuiltInRegistries.BLOCK.containsKey(key);
    }

    static JsonArray strings(List<String> list) {
        JsonArray a = new JsonArray();
        list.forEach(a::add);
        return a;
    }
}
