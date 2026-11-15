package dev.afk;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import net.minecraft.client.Camera;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * The film camera, after the cinematic mode in Icarus: a director picks a shot from a weighted
 * pool (never the same shot twice, never two shots of the same size back to back, keeping to one
 * side of the action), critically damped springs carry the camera and its aim, the lens pushes
 * in slowly over each shot, and there is a breath of handheld drift. It cuts when a shot has run,
 * when the view of the player is blocked, or when the camera is crowded against them.
 *
 * The camera rides on the player: springs smooth its offset from them, not its place in the world,
 * so it never falls behind and ends up inside them. In tight places (tunnels, foliage) it cuts to
 * a first-person shot instead of squeezing in.
 *
 * It only ever shows what an ordinary third-person view could: it is kept out of solid blocks and
 * always has a clear line to the player, so it never works as x-ray.
 *
 * Three uses: Herobrine has control (AGENT), you pressed B while exploring (MANUAL), or a short
 * discovery moment (MOMENT) such as a new biome or your first diamond.
 */
final class CinematicCamera {
    enum Use { OFF, AGENT, MANUAL, MOMENT }

    enum Type {
        //    weight dur        fov0 fov1 smooth size     sided minDist
        BACK (1.2, 6, 9,   70, 64, 0.30, "mid",   true,  2.2),
        SIDE (1.0, 5, 8,   56, 50, 0.45, "mid",   true,  2.6),
        LOW  (1.0, 4, 6,   72, 64, 0.35, "near",  true,  2.0),
        FACE (0.8, 4, 6,   50, 44, 0.35, "close", true,  2.0),
        ORBIT(1.2, 7, 10,  62, 56, 0.55, "mid",   false, 3.2),
        WIDE (1.0, 6, 9,   42, 36, 0.80, "wide",  true,  6.0),
        CRANE(0.8, 6, 8,   58, 50, 0.90, "wide",  true,  4.0),
        TOP  (0.6, 5, 7,   56, 48, 0.70, "wide",  false, 5.0),
        FLYBY(1.6, 4, 7,   52, 44, 0.00, "mid",   true,  2.5),
        WORK (3.0, 5, 8,   60, 52, 0.35, "close", true,  1.7),
        POV  (0.4, 3, 6,   70, 64, 0.00, "pov",   false, 0.0);

        final double weight, durMin, durMax, smooth, minDist;
        final float fov0, fov1;
        final String size;
        final boolean sided;

        Type(double weight, double durMin, double durMax, float fov0, float fov1, double smooth, String size, boolean sided, double minDist) {
            this.weight = weight;
            this.durMin = durMin;
            this.durMax = durMax;
            this.fov0 = fov0;
            this.fov1 = fov1;
            this.smooth = smooth;
            this.size = size;
            this.sided = sided;
            this.minDist = minDist;
        }
    }

    private static final class Shot {
        Type type;
        double t;
        double dur;
        int side;
        double angle;
        double dist;
        Vec3 anchor;
        BlockPos work;
    }

    /** A camera pose. detached=false means "first person": the player's own body is not drawn. */
    record Pose(double x, double y, double z, float yRot, float xRot, float fov, boolean detached) {}

    private static final double CROWDED = 1.35; // closer than this to the player is too close

    private final Random rng = new Random();
    private final ArrayDeque<String[]> pendingMoments = new ArrayDeque<>();
    private final Spring3 anchor = new Spring3();
    private final Spring3 offset = new Spring3();
    private final Spring3 aim = new Spring3();

    private Use use = Use.OFF;
    private boolean exiting;
    private boolean gliding;
    private boolean started;
    private CameraType previousType;

    private Vec3 pos = Vec3.ZERO;
    private float fov = 70;
    private Shot shot;
    private Type lastType;
    private String lastSize = "";
    private int side = 1;
    private double blocked;
    private double crowded;
    private double glideT;
    private double exitT;
    private double momentLeft;
    private long lastNanos;
    private long lastMomentMs;
    private long lastCardMs;
    private volatile boolean momentsEnabled = true;
    private long lastDestroyMs;
    private float heading;
    private Type forceNext;

    static volatile Pose lastPose;

    // ------------------------------------------------------------------ control

    void startAgent(Minecraft mc) {
        begin(mc, Use.AGENT);
    }

    void stopAgent() {
        if (use == Use.AGENT) exit();
    }

    void toggleManual(Minecraft mc) {
        if (use == Use.MANUAL && !exiting) {
            exit();
            Afk.HUD.toast("CINEMATIC OFF", "");
        } else if (use == Use.MOMENT && !exiting) {
            use = Use.MANUAL; // already filming a moment: just keep rolling
            Afk.HUD.toast("CINEMATIC CAMERA", "B to leave");
        } else if (use == Use.OFF || exiting) {
            begin(mc, Use.MANUAL);
            Afk.HUD.toast("CINEMATIC CAMERA", "B to leave");
        }
    }

    /** A discovery worth a shot: a title card, and a short cinematic if you are free to watch. */
    void moment(String label, String title, double seconds) {
        moment(label, title, seconds, true);
    }

    /** camera=false shows only the title card: small discoveries don't take the view away from you. */
    void moment(String label, String title, double seconds, boolean camera) {
        if (!momentsEnabled) return;
        pendingMoments.addLast(new String[] {label, title, String.valueOf(seconds), camera ? "1" : "0"});
        while (pendingMoments.size() > 4) pendingMoments.removeFirst();
    }

    boolean isActive() {
        return use != Use.OFF;
    }

    boolean isManual() {
        return use == Use.MANUAL && !exiting;
    }

    boolean momentsEnabled() {
        return momentsEnabled;
    }

    void setMomentsEnabled(boolean on) {
        momentsEnabled = on;
        if (!on) pendingMoments.clear();
    }

    boolean hidesVanillaHud() {
        return use != Use.OFF && !exiting;
    }

    boolean hidesBlockOutline() {
        return use != Use.OFF;
    }

    /** 1 while the letterbox should be fully in, 0 when out. */
    float letterboxTarget() {
        return use != Use.OFF && !exiting ? 1f : 0f;
    }

    Float fovOverride() {
        return use == Use.OFF ? null : fov;
    }

    private void begin(Minecraft mc, Use newUse) {
        if (use == Use.OFF) {
            previousType = mc.options.getCameraType();
            started = false;
        }
        use = newUse;
        exiting = false;
        gliding = true;
        glideT = 0;
        shot = null;
        lastType = null;
        lastSize = "";
        blocked = 0;
        crowded = 0;
        lastNanos = 0;
        setType(mc, CameraType.THIRD_PERSON_BACK);
    }

    private void exit() {
        if (use == Use.OFF) return;
        exiting = true;
        exitT = 0;
    }

    private void finish(Minecraft mc) {
        use = Use.OFF;
        exiting = false;
        shot = null;
        setType(mc, previousType == null ? CameraType.FIRST_PERSON : previousType);
    }

    private static void setType(Minecraft mc, CameraType type) {
        if (mc.options.getCameraType() != type) mc.options.setCameraType(type);
    }

    void tick(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null) {
            if (use != Use.OFF) {
                use = Use.OFF;
                exiting = false;
                shot = null;
            }
            pendingMoments.clear();
            return;
        }
        if (mc.gameMode != null && mc.gameMode.isDestroying()) lastDestroyMs = System.currentTimeMillis();

        if (use == Use.MOMENT && !exiting) {
            momentLeft -= 0.05;
            boolean interrupted = p.hurtTime > 0 || mc.options.keyAttack.isDown() || mc.options.keyUse.isDown() || mc.gui.screen() != null;
            if (momentLeft <= 0 || interrupted) exit();
        }

        String[] next = pendingMoments.peekFirst();
        if (next == null) return;
        long now = System.currentTimeMillis();
        if (now - lastCardMs < 20_000) { // one title card at a time, with room to breathe
            while (pendingMoments.size() > 2) pendingMoments.removeFirst();
            return;
        }
        if (use == Use.AGENT || use == Use.MANUAL) {
            pendingMoments.removeFirst();
            lastCardMs = now;
            Afk.HUD.titleCard(next[0], next[1]);
            forceNext = rng.nextBoolean() ? Type.ORBIT : Type.WIDE;
            if (shot != null) shot.t = shot.dur; // cut to it
            return;
        }
        boolean calm = mc.gui.screen() == null && !p.isDeadOrDying() && p.hurtTime == 0 && Afk.STATE.seenMobs().stream()
                .noneMatch(m -> m.hostile() && m.distance() < 10);
        if (use == Use.OFF && next[3].equals("0")) {
            pendingMoments.removeFirst();
            lastCardMs = now;
            Afk.HUD.titleCard(next[0], next[1]);
        } else if (use == Use.OFF && calm && now - lastMomentMs > 25_000) {
            pendingMoments.removeFirst();
            lastMomentMs = now;
            lastCardMs = now;
            Afk.HUD.titleCard(next[0], next[1]);
            begin(mc, Use.MOMENT);
            momentLeft = Double.parseDouble(next[2]);
            forceNext = rng.nextBoolean() ? Type.ORBIT : (rng.nextBoolean() ? Type.LOW : Type.WIDE);
        } else if (use == Use.OFF && !calm && pendingMoments.size() > 2) {
            pendingMoments.removeFirst(); // busy: don't let old moments pile up
        }
    }

    // ------------------------------------------------------------------ per frame

    /** Called from the camera mixin right after vanilla placed the camera. Returns the pose to use, or null. */
    Pose direct(Camera camera, float partial) {
        if (use == Use.OFF) return null;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        ClientLevel level = mc.level;
        if (p == null || level == null) return null;

        long now = System.nanoTime();
        double dt = lastNanos == 0 ? 0.016 : Math.min(0.1, Math.max(0, (now - lastNanos) / 1e9));
        lastNanos = now;
        double time = now / 1e9;

        Vec3 feet = p.getPosition(partial);
        Vec3 subj = feet.add(0, 1.15, 0);
        Vec3 eye = p.getEyePosition(partial);
        float viewYaw = p.getViewYRot(partial);
        float viewPitch = p.getViewXRot(partial);
        float baseFov = mc.options.fov().get();

        Vec3 vel = p.getDeltaMovement();
        double speed = Math.hypot(vel.x, vel.z);
        float wantHeading = speed > 0.04 ? (float) Math.toDegrees(Math.atan2(-vel.x, vel.z)) : viewYaw;

        if (!started) {
            // Start just behind the head, then pull back: no frame is spent inside the player.
            started = true;
            heading = viewYaw;
            anchor.snap(subj);
            offset.snap(eye.add(direction(viewYaw, viewPitch).scale(-1.1)).add(0, 0.25, 0).subtract(subj));
            aim.snap(eye.add(direction(viewYaw, viewPitch).scale(6)).subtract(subj));
            fov = baseFov;
        }
        heading += wrap(wantHeading - heading) * (float) (1 - Math.exp(-dt * (speed > 0.04 ? 2.2 : 0.8)));
        anchor.to(subj, 0.07, dt);
        Vec3 a = anchor.value;

        if (exiting) {
            exitT += dt;
            setType(mc, CameraType.THIRD_PERSON_BACK);
            offset.to(eye.subtract(a), 0.16, dt);
            aim.to(eye.add(direction(viewYaw, viewPitch).scale(8)).subtract(a), 0.10, dt);
            fov += (baseFov - fov) * (float) (1 - Math.exp(-dt * 8));
            pos = clearPath(level, p, subj, a.add(offset.value));
            if (exitT > 0.7 || pos.distanceTo(eye) < 0.95) {
                finish(mc);
                return new Pose(eye.x, eye.y, eye.z, viewYaw, viewPitch, baseFov, false);
            }
            return look(pos, a.add(aim.value), true);
        }

        if (shot == null) {
            shot = pick(mc, level, p, subj, feet, speed);
            if (!gliding) cut(level, p, subj, feet, a);
        }
        shot.t += dt;
        boolean flybyPassed = shot.type == Type.FLYBY && shot.t > 1 && shot.anchor.distanceTo(subj) < 1.7;
        if ((shot.t > shot.dur && use != Use.MOMENT) || flybyPassed) {
            shot = pick(mc, level, p, subj, feet, speed);
            cut(level, p, subj, feet, a);
        }

        if (shot.type == Type.POV) {
            setType(mc, CameraType.FIRST_PERSON);
            gliding = false;
            double u = Math.min(1, shot.t / shot.dur);
            fov += ((baseFov - 4 * (float) u) - fov) * (float) (1 - Math.exp(-dt * 6));
            pos = eye;
            offset.snap(eye.subtract(a));
            aim.snap(eye.add(direction(viewYaw, viewPitch).scale(6)).subtract(a));
            // Leave the first-person shot once there is room for a camera again.
            if (shot.t > 2.5 && openness(level, subj) > 0.5 && use != Use.MOMENT) shot.t = shot.dur;
            return new Pose(eye.x, eye.y, eye.z, viewYaw, viewPitch, fov, false);
        }
        setType(mc, CameraType.THIRD_PERSON_BACK);

        Vec3 desired = clearPath(level, p, subj, shotPos(shot, a, a.add(0, -1.15, 0)));
        Vec3 desiredAim = shotAim(shot, a, time);
        double u = Math.min(1, shot.t / shot.dur);
        double k = u * u * (3 - 2 * u);
        float targetFov = (float) (shot.type.fov0 + (shot.type.fov1 - shot.type.fov0) * k);

        if (gliding) {
            glideT += dt;
            offset.to(desired.subtract(a), 0.45, dt);
            aim.to(desiredAim.subtract(a), 0.30, dt);
            if (glideT > 1.6 || a.add(offset.value).distanceTo(desired) < 0.25) gliding = false;
        } else {
            offset.to(desired.subtract(a), shot.type.smooth, dt);
            aim.to(desiredAim.subtract(a), 0.22, dt);
        }
        fov += (targetFov - fov) * (float) (1 - Math.exp(-dt * (gliding ? 3 : 6)));

        // Never inside a block, never looking through a wall.
        pos = clearPath(level, p, subj, a.add(offset.value));
        offset.value = pos.subtract(a);

        if (!gliding) {
            crowded = pos.distanceTo(subj) < CROWDED ? crowded + dt : 0;
            blocked = visible(level, p, pos, subj) ? 0 : blocked + dt;
            if (crowded > 0.2 || blocked > 0.45) {
                shot = pick(mc, level, p, subj, feet, speed);
                cut(level, p, subj, feet, a);
                if (shot.type == Type.POV) return new Pose(eye.x, eye.y, eye.z, viewYaw, viewPitch, fov, false);
                pos = clearPath(level, p, subj, a.add(offset.value));
            }
        }
        return look(pos, a.add(aim.value), true);
    }

    private Pose look(Vec3 from, Vec3 at, boolean detached) {
        Vec3 d = at.subtract(from);
        float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
        float pitch = (float) Math.toDegrees(Math.atan2(-d.y, Math.hypot(d.x, d.z)));
        return new Pose(from.x, from.y, from.z, yaw, pitch, fov, detached);
    }

    static void recordPose(Camera camera) {
        Vec3 c = camera.position();
        lastPose = new Pose(c.x, c.y, c.z, camera.yRot(), camera.xRot(), camera.getFov(), camera.isDetached());
    }

    // ------------------------------------------------------------------ shots

    /** A hard cut: the camera is at the new shot's place at once. */
    private void cut(ClientLevel level, LocalPlayer p, Vec3 subj, Vec3 feet, Vec3 a) {
        if (shot.type == Type.POV) return;
        Vec3 at = clearPath(level, p, subj, shotPos(shot, a, a.add(0, -1.15, 0)));
        offset.snap(at.subtract(a));
        aim.snap(shotAim(shot, a, System.nanoTime() / 1e9).subtract(a));
        fov = shot.type.fov0;
        blocked = 0;
        crowded = 0;
        gliding = false;
    }

    private Shot pick(Minecraft mc, ClientLevel level, LocalPlayer p, Vec3 subj, Vec3 feet, double speed) {
        boolean moving = speed > 0.08;
        boolean mining = System.currentTimeMillis() - lastDestroyMs < 2000;
        BlockPos work = mining && mc.hitResult instanceof BlockHitResult bhr && bhr.getType() == HitResult.Type.BLOCK ? bhr.getBlockPos() : null;
        BlockPos fp = BlockPos.containing(feet);
        boolean sky = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, fp.getX(), fp.getZ()) <= fp.getY() + 1;
        double open = openness(level, subj);
        boolean enclosed = open < 0.35;
        List<int[]> path = Afk.MODE.isAgent() ? Afk.BARITONE.upcomingPath(24) : List.of();

        Map<Type, Double> w = new EnumMap<>(Type.class);
        for (Type t : Type.values()) {
            double v = t.weight;
            switch (t) {
                case WORK -> v *= work != null ? 1 : 0;
                case FACE -> v *= mining || !moving ? 1.3 : 0.4;
                case LOW -> v *= moving ? 0.7 : 1.1;
                case SIDE -> v *= moving ? 1.5 : 0.8;
                case ORBIT -> v *= enclosed ? 0 : (moving ? 0.6 : 1.5);
                case WIDE -> v *= enclosed ? 0 : 1;
                case CRANE -> v *= enclosed || !sky ? 0 : 1;
                case TOP -> v *= sky && !enclosed ? 1 : 0;
                case FLYBY -> v *= moving && path.size() > 10 ? 1 : (moving && !enclosed ? 0.5 : 0);
                case POV -> v *= enclosed ? 7 : (mining && !sky ? 3 : 1);
                default -> { }
            }
            if (use == Use.MOMENT) {
                if (t == Type.ORBIT || t == Type.WIDE || t == Type.CRANE || t == Type.LOW) v *= 2;
                if (t == Type.POV) v = 0;
            }
            if (t == lastType && t != Type.POV) v = 0;
            if (t == lastType && t == Type.POV && !enclosed) v = 0;
            if (lastType != null && t.size.equals(lastSize) && t != Type.POV) v *= 0.3;
            w.put(t, v);
        }

        Vec3 a = anchor.value;
        List<Type> tried = new ArrayList<>();
        for (int attempt = 0; attempt < 10; attempt++) {
            Type type = forceNext != null && w.getOrDefault(forceNext, 0.0) > 0 && attempt == 0 ? forceNext : roll(w, tried);
            if (type == null) break;
            tried.add(type);
            Shot s = build(type, level, p, subj, feet, speed, work, path);
            if (type == Type.POV) {
                accept(s);
                return s;
            }
            Vec3 start = clearPath(level, p, subj, shotPos(s, a, a.add(0, -1.15, 0)));
            s.t = s.dur;
            Vec3 end = clearPath(level, p, subj, shotPos(s, a, a.add(0, -1.15, 0)));
            s.t = 0;
            if (start.distanceTo(subj) >= type.minDist && end.distanceTo(subj) >= type.minDist * 0.75) {
                accept(s);
                return s;
            }
        }
        Shot fallback = build(Type.POV, level, p, subj, feet, speed, work, path); // no room for a camera: first person
        accept(fallback);
        return fallback;
    }

    private void accept(Shot s) {
        forceNext = null;
        if (lastType != null && lastType.sided && s.type.sided) s.side = side;
        else side = s.side;
        lastType = s.type;
        lastSize = s.type.size;
    }

    private Type roll(Map<Type, Double> w, List<Type> tried) {
        double total = 0;
        for (Map.Entry<Type, Double> e : w.entrySet()) if (!tried.contains(e.getKey())) total += e.getValue();
        if (total <= 0) return null;
        double r = rng.nextDouble() * total;
        for (Map.Entry<Type, Double> e : w.entrySet()) {
            if (tried.contains(e.getKey())) continue;
            r -= e.getValue();
            if (r <= 0) return e.getKey();
        }
        return null;
    }

    private Shot build(Type type, ClientLevel level, LocalPlayer p, Vec3 subj, Vec3 feet, double speed, BlockPos work, List<int[]> path) {
        Shot s = new Shot();
        s.type = type;
        s.dur = type.durMin + rng.nextDouble() * (type.durMax - type.durMin);
        s.side = rng.nextBoolean() ? 1 : -1;
        s.angle = rng.nextDouble() * Math.PI * 2;
        s.dist = type == Type.WIDE ? 12 + rng.nextDouble() * 8 : type == Type.TOP ? 11 + rng.nextDouble() * 3 : 0;
        s.work = work;
        if (type == Type.FLYBY) {
            Vec3 ahead;
            if (path.size() > 10) {
                int[] n = path.get(Math.min(path.size() - 1, 12));
                ahead = new Vec3(n[0] + 0.5, n[1], n[2] + 0.5);
            } else {
                ahead = feet.add(forward(heading).scale(Math.max(4, speed * 20 * s.dur * 0.45)));
            }
            Vec3 spot = ahead.add(right(heading).scale(2.8 * s.side)).add(0, 1.4, 0);
            for (int i = 0; i < 4 && level.getBlockState(BlockPos.containing(spot)).isSolidRender(); i++) spot = spot.add(0, 1, 0);
            s.anchor = spot;
        }
        return s;
    }

    /** Where the shot wants the camera, given the (smoothed) middle of the player and their feet. */
    private Vec3 shotPos(Shot s, Vec3 subj, Vec3 feet) {
        Vec3 f = forward(heading);
        Vec3 r = right(heading);
        double u = Math.min(1, s.t / s.dur);
        int sd = s.side;
        return switch (s.type) {
            case BACK -> subj.add(f.scale(-3.6)).add(r.scale(1.0 * sd)).add(0, 0.8, 0);
            case SIDE -> subj.add(r.scale(5.0 * sd)).add(f.scale(1.3 - 2.6 * u)).add(0, 0.35, 0);
            case LOW -> feet.add(f.scale(3.2)).add(r.scale(1.5 * sd)).add(0, 0.35, 0);
            case FACE -> subj.add(f.scale(3.0)).add(r.scale(0.7 * sd)).add(0, 0.45, 0);
            case ORBIT -> {
                double ang = s.angle + s.t * 0.22 * sd;
                yield subj.add(Math.cos(ang) * 6.0, 2.2, Math.sin(ang) * 6.0);
            }
            case WIDE -> subj.add(r.scale(s.dist * sd)).add(f.scale(s.dist * 0.3)).add(0, 3 + s.dist * 0.12, 0);
            case CRANE -> subj.add(f.scale(-6.5)).add(r.scale(4.0 * sd)).add(0, 2.5 + u * 6.5, 0);
            case TOP -> subj.add(f.scale(0.02)).add(0, s.dist, 0);
            case FLYBY -> s.anchor;
            case WORK -> {
                if (s.work == null) yield subj.add(f.scale(-3.6)).add(0, 0.8, 0);
                Vec3 bc = Vec3.atCenterOf(s.work);
                Vec3 d = new Vec3(bc.x - subj.x, 0, bc.z - subj.z);
                d = d.lengthSqr() < 1e-4 ? f : d.normalize();
                Vec3 perp = new Vec3(-d.z, 0, d.x);
                yield subj.lerp(bc, 0.5).add(perp.scale(2.6 * sd)).add(d.scale(-0.4)).add(0, 0.6, 0);
            }
            case POV -> subj.add(0, 0.47, 0);
        };
    }

    private Vec3 shotAim(Shot s, Vec3 subj, double time) {
        double dist = Math.max(1.5, pos.distanceTo(subj));
        Vec3 base = switch (s.type) {
            case FACE -> subj.add(0, 0.4, 0);
            case LOW -> subj.add(0, 0.25, 0);
            case TOP -> subj;
            case WORK -> s.work == null ? subj : subj.lerp(Vec3.atCenterOf(s.work), 0.62);
            default -> subj.add(forward(heading).scale(Math.min(2.5, dist * 0.12)));
        };
        return base.add(Math.sin(time * 0.7) * dist * 0.004, Math.sin(time * 0.9 + 1.3) * dist * 0.003, 0);
    }

    // ------------------------------------------------------------------ geometry helpers

    private static Vec3 forward(float yawDeg) {
        double h = Math.toRadians(yawDeg);
        return new Vec3(-Math.sin(h), 0, Math.cos(h));
    }

    private static Vec3 right(float yawDeg) {
        double h = Math.toRadians(yawDeg);
        return new Vec3(-Math.cos(h), 0, -Math.sin(h));
    }

    private static Vec3 direction(float yawDeg, float pitchDeg) {
        double h = Math.toRadians(yawDeg);
        double v = Math.toRadians(pitchDeg);
        return new Vec3(-Math.sin(h) * Math.cos(v), -Math.sin(v), Math.cos(h) * Math.cos(v));
    }

    private static float wrap(float deg) {
        deg %= 360f;
        if (deg >= 180f) deg -= 360f;
        if (deg < -180f) deg += 360f;
        return deg;
    }

    /** Moves `to` toward `from` until the straight line between them is clear and it sits in open air. */
    private static Vec3 clearPath(ClientLevel level, LocalPlayer p, Vec3 from, Vec3 to) {
        BlockHitResult hit = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
        Vec3 out = to;
        if (hit.getType() != HitResult.Type.MISS) {
            Vec3 back = from.subtract(to);
            double len = back.length();
            out = len < 1e-4 ? from : hit.getLocation().add(back.scale(0.35 / len));
        }
        for (int i = 0; i < 12 && level.getBlockState(BlockPos.containing(out)).isSolidRender(); i++) {
            out = out.lerp(from, 0.25);
        }
        return out;
    }

    private static boolean visible(ClientLevel level, LocalPlayer p, Vec3 from, Vec3 to) {
        return level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p)).getType() == HitResult.Type.MISS;
    }

    /** Fraction of open space in a small box around the player: low means a tunnel, a cave crawl or thick foliage. */
    private static double openness(ClientLevel level, Vec3 c) {
        int open = 0;
        int total = 0;
        BlockPos center = BlockPos.containing(c);
        for (int dx = -3; dx <= 3; dx++) {
            for (int dy = -1; dy <= 3; dy += 2) {
                for (int dz = -3; dz <= 3; dz++) {
                    total++;
                    BlockPos at = center.offset(dx, dy, dz);
                    if (level.getBlockState(at).getCollisionShape(level, at).isEmpty()) open++;
                }
            }
        }
        return open / (double) total;
    }

    /** A critically damped spring ("SmoothDamp") on a 3D value. */
    private static final class Spring3 {
        Vec3 value = Vec3.ZERO;
        private double vx, vy, vz;

        void snap(Vec3 to) {
            value = to;
            vx = vy = vz = 0;
        }

        void to(Vec3 target, double smooth, double dt) {
            if (smooth <= 0) {
                snap(target);
                return;
            }
            double w = 2 / smooth;
            double x = w * dt;
            double e = 1 / (1 + x + 0.48 * x * x + 0.235 * x * x * x);
            double nx = value.x - target.x, ny = value.y - target.y, nz = value.z - target.z;
            double sx = (vx + w * nx) * dt, sy = (vy + w * ny) * dt, sz = (vz + w * nz) * dt;
            vx = (vx - w * sx) * e;
            vy = (vy - w * sy) * e;
            vz = (vz - w * sz) * e;
            value = new Vec3(target.x + (nx + sx) * e, target.y + (ny + sy) * e, target.z + (nz + sz) * e);
        }
    }
}
