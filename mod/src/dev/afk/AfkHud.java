package dev.afk;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3x2fStack;

/**
 * The whole interface, kept small: letterbox bars (Icarus style) while the camera is cinematic,
 * Herobrine's commentary inside the bottom bar, a slim plan / memory / sight column on the right,
 * and markers in the world for where it is going. When you play, only a tiny "AFK" tag remains.
 */
final class AfkHud implements HudElement {
    private static final int INK = 0xF4EAD2;
    private static final int DIM = 0xA39C8A;
    private static final int EMBER = 0xFF8A3D;
    private static final int RED = 0xFF5A4E;

    // Brain-provided state (written from the HTTP thread).
    private volatile long thoughtId;
    private volatile String thought = "";
    private volatile long thoughtAtMs;
    private volatile String lastAction = "";
    private volatile String status = "";
    private volatile String goal = "";
    private volatile List<String> plan = List.of();
    private volatile int step;
    private volatile List<String> notes = List.of();
    private volatile String context = "";
    private volatile Vec3 target;
    private volatile String targetLabel = "";
    private volatile Vec3 home;
    private volatile long lastContactMs;

    private volatile String toastLabel = "";
    private volatile String toastSub = "";
    private volatile long toastAtMs;
    private volatile String cardLabel = "";
    private volatile String cardTitle = "";
    private volatile long cardAtMs;

    private float barFrom;
    private float barTo;
    private long barStartMs;

    void touch() {
        lastContactMs = System.currentTimeMillis();
    }

    long lastContactMs() {
        return lastContactMs;
    }

    void setStatus(String newStatus, String newGoal) {
        if (newStatus != null) status = clean(newStatus, 60);
        if (newGoal != null) goal = clean(newGoal, 90);
    }

    void entry(long id, String kind, String text) {
        String t = clean(text, 600);
        if ("action".equals(kind)) {
            lastAction = t;
            return;
        }
        if (id != thoughtId) thoughtAtMs = System.currentTimeMillis();
        thoughtId = id;
        thought = "error".equals(kind) ? "(" + t + ")" : t;
    }

    void setContext(JsonObject o) {
        if (o.has("plan") && o.get("plan").isJsonArray()) plan = strings(o.getAsJsonArray("plan"), 5, 80);
        if (o.has("step") && o.get("step").isJsonPrimitive()) step = o.get("step").getAsInt();
        if (o.has("notes") && o.get("notes").isJsonArray()) notes = strings(o.getAsJsonArray("notes"), 3, 90);
        if (o.has("context") && o.get("context").isJsonPrimitive()) context = clean(o.get("context").getAsString(), 140);
        target = vec(o.get("target"));
        targetLabel = o.has("target") && o.get("target").isJsonObject() && o.getAsJsonObject("target").has("label")
                ? clean(o.getAsJsonObject("target").get("label").getAsString(), 30) : "";
        Vec3 h = vec(o.get("home"));
        if (h != null) home = h;
    }

    void toast(String label, String sub) {
        toastLabel = label;
        toastSub = sub == null ? "" : clean(sub, 80);
        toastAtMs = System.currentTimeMillis();
    }

    void titleCard(String label, String title) {
        cardLabel = label;
        cardTitle = title == null ? "" : title;
        cardAtMs = System.currentTimeMillis();
    }

    // ------------------------------------------------------------------ render

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui.hud.isHidden() || mc.player == null) return;
        Font font = mc.font;
        int w = g.guiWidth();
        int h = g.guiHeight();
        long now = System.currentTimeMillis();
        float k = bars(now);
        boolean agent = Afk.MODE.isAgent();

        if (k > 0.002f) {
            int barH = Math.round(h * 0.10f * k);
            g.fill(0, 0, w, barH, 0xFF000000);
            g.fill(0, h - barH, w, h, 0xFF000000);
            int a = (int) (255 * clamp((k - 0.45f) / 0.55f));
            if (a > 6) {
                if (agent) {
                    markers(g, font, mc, w, h, a);
                    topBarAgent(g, font, w, barH, a, now);
                    bottomBarAgent(g, font, mc, w, h, barH, a, now);
                    sidePanel(g, font, w, barH, a);
                } else {
                    spaced(g, font, "AFK", 10, (barH - 6) / 2f, 0.62f, argb(EMBER, a), 1.2f);
                    spaced(g, font, "CINEMATIC", 10 + spacedWidth(font, "AFK  ", 0.62f, 1.2f), (barH - 6) / 2f, 0.62f, argb(DIM, a), 1.2f);
                    String hint = Afk.CAMERA.isActive() && !agent ? "B  ·  LEAVE" : "";
                    float hw = spacedWidth(font, hint, 0.55f, 1.2f);
                    spaced(g, font, hint, w - 10 - hw, h - barH + (barH - 5) / 2f, 0.55f, argb(DIM, a), 1.2f);
                }
            }
        }
        if (!agent && k < 0.5f) playerTag(g, font, now);
        toastDraw(g, font, w, h, k, now);
        cardDraw(g, font, w, h, now);
    }

    private float bars(long now) {
        float target = Afk.CAMERA.letterboxTarget();
        float current = barValue(now);
        if (target != barTo) {
            barFrom = current;
            barTo = target;
            barStartMs = now;
        }
        return barValue(now);
    }

    private float barValue(long now) {
        float x = clamp((now - barStartMs) / 1100f);
        float e = 1 - (float) Math.pow(1 - x, 3);
        return barFrom + (barTo - barFrom) * e;
    }

    private void topBarAgent(GuiGraphicsExtractor g, Font font, int w, int barH, int a, long now) {
        float y = (barH - 6) / 2f;
        boolean thinking = status.toLowerCase(Locale.ROOT).startsWith("thinking");
        boolean online = now - lastContactMs < 15_000;
        int pulse = thinking ? (int) (a * (0.55 + 0.45 * Math.sin(now / 180.0))) : a;
        dot(g, 10, (int) y + 1, 2, argb(online ? EMBER : DIM, pulse));
        float x = 16;
        x += spaced(g, font, "HEROBRINE", x, y, 0.66f, argb(INK, a), 1.4f);
        String st = !online ? "WAKING UP" : status.isEmpty() ? "WATCHING" : status.toUpperCase(Locale.ROOT);
        spaced(g, font, "   ·   " + st, x, y, 0.6f, argb(DIM, a), 1.2f);
        if (!goal.isEmpty()) {
            String gText = font.plainSubstrByWidth(goal.toUpperCase(Locale.ROOT), (int) (w * 0.42f / 0.6f));
            float gw = spacedWidth(font, gText, 0.6f, 1.0f);
            spaced(g, font, gText, w - 10 - gw, y, 0.6f, argb(DIM, a), 1.0f);
        }
    }

    private void bottomBarAgent(GuiGraphicsExtractor g, Font font, Minecraft mc, int w, int h, int barH, int a, long now) {
        boolean online = now - lastContactMs < 15_000;
        String text = !online ? "Herobrine is waking up..." : thought.isEmpty() ? "..." : thought;
        float scale = 0.78f;
        List<String> lines = wrap(font, text, (int) (w * 0.66f / scale));
        if (lines.size() > 2) lines = lines.subList(lines.size() - 2, lines.size());
        int fade = (int) (a * clamp((now - thoughtAtMs) / 250f + 0.2f));
        int color = argb(online ? INK : DIM, Math.max(fade, a / 3));
        float lineH = 10 * scale;
        float y0 = h - barH + (barH - lines.size() * lineH) / 2f + 1;
        for (int i = 0; i < lines.size(); i++) {
            float lw = font.width(lines.get(i)) * scale;
            text(g, font, lines.get(i), (w - lw) / 2f, y0 + i * lineH, scale, color);
        }
        LocalPlayer p = mc.player;
        String vitals = "HP " + Math.round(p.getHealth()) + "   FOOD " + p.getFoodData().getFoodLevel();
        float vw = spacedWidth(font, vitals, 0.5f, 1f);
        spaced(g, font, vitals, w - 10 - vw, h - barH + (barH - 5) / 2f, 0.5f, argb(DIM, a), 1f);
    }

    private void sidePanel(GuiGraphicsExtractor g, Font font, int w, int barH, int a) {
        float scale = 0.56f;
        int colW = Math.max(80, Math.min(130, (int) (w * 0.24f)));
        float x = w - 10 - colW;
        float y = barH + 10;
        float lh = 10 * scale;
        int maxW = (int) (colW / scale);
        g.fill((int) x - 5, (int) y, (int) x - 4, (int) (y + 4), argb(EMBER, a * 3 / 4));

        y = section(g, font, "PLAN", x, y, scale, a);
        List<String> p = plan;
        for (int i = 0; i < p.size(); i++) {
            boolean current = i == step;
            String line = font.plainSubstrByWidth(p.get(i), maxW - 8);
            if (current) dot(g, (int) x, (int) (y + 1.5f), 1, argb(EMBER, a));
            text(g, font, line, x + 4, y, scale, argb(current ? INK : DIM, current ? a : a * 3 / 4));
            y += lh;
        }
        if (!lastAction.isEmpty()) {
            text(g, font, font.plainSubstrByWidth("› " + lastAction, maxW), x + 4, y, scale, argb(EMBER, a * 3 / 4));
            y += lh;
        }
        if (!notes.isEmpty()) {
            y = section(g, font, "MEMORY", x, y + 3, scale, a);
            for (String n : notes) {
                for (String l : limit(wrap(font, n, maxW - 4), 2)) {
                    text(g, font, l, x + 4, y, scale, argb(DIM, a * 3 / 4));
                    y += lh;
                }
            }
        }
        List<String> sees = sees();
        if (!sees.isEmpty()) {
            y = section(g, font, "SEES", x, y + 3, scale, a);
            for (String s : sees) {
                text(g, font, font.plainSubstrByWidth(s, maxW - 4), x + 4, y, scale, argb(DIM, a * 3 / 4));
                y += lh;
            }
        }
        if (!context.isEmpty()) {
            y = section(g, font, "CONTEXT", x, y + 3, scale, a);
            for (String l : limit(wrap(font, context, maxW - 4), 3)) {
                text(g, font, l, x + 4, y, scale, argb(DIM, a * 3 / 4));
                y += lh;
            }
        }
    }

    private float section(GuiGraphicsExtractor g, Font font, String title, float x, float y, float scale, int a) {
        spaced(g, font, title, x, y, scale * 0.9f, argb(EMBER, a * 4 / 5), 1.3f);
        return y + 10 * scale + 1;
    }

    private List<String> sees() {
        List<String> out = new ArrayList<>();
        StringBuilder ores = new StringBuilder();
        int n = 0;
        for (StateSampler.SeenOre o : Afk.STATE.seenOres()) {
            if (n++ >= 3) break;
            if (ores.length() > 0) ores.append(", ");
            ores.append(o.id().replace("deepslate_", "").replace("_ore", "")).append(' ').append(Math.round(o.distance())).append('m');
        }
        if (ores.length() > 0) out.add("ore: " + ores);
        for (StateSampler.SeenMob m : Afk.STATE.seenMobs()) {
            if (out.size() >= 3) break;
            if (m.hostile()) out.add(m.id() + " " + Math.round(m.distance()) + "m");
        }
        return out;
    }

    private void playerTag(GuiGraphicsExtractor g, Font font, long now) {
        long since = now - Math.max(Afk.MODE.changedAtMs(), toastAtMs);
        int a = since < 6000 ? 210 : 70;
        int pulse = (int) (a * (0.6 + 0.4 * Math.sin(now / 600.0)));
        dot(g, 8, 8, 1, argb(RED, pulse));
        float x = 13;
        x += spaced(g, font, "AFK", x, 6, 0.55f, argb(EMBER, a), 1.2f);
        spaced(g, font, "  ·  TAB HEROBRINE", x, 6, 0.55f, argb(DIM, a), 1.2f);
    }

    private void toastDraw(GuiGraphicsExtractor g, Font font, int w, int h, float k, long now) {
        long t = now - toastAtMs;
        if (toastLabel.isEmpty() || t > 2800) return;
        float alpha = t < 250 ? t / 250f : t > 2200 ? 1 - (t - 2200) / 600f : 1;
        int a = (int) (230 * clamp(alpha));
        if (a < 8) return;
        float y = Math.round(h * 0.10f * k) + 10;
        float lw = spacedWidth(font, toastLabel, 0.68f, 1.6f);
        spaced(g, font, toastLabel, (w - lw) / 2f, y, 0.68f, argb(INK, a), 1.6f);
        if (!toastSub.isEmpty()) {
            float sw = font.width(toastSub) * 0.58f;
            text(g, font, toastSub, (w - sw) / 2f, y + 8, 0.58f, argb(DIM, a));
        }
    }

    private void cardDraw(GuiGraphicsExtractor g, Font font, int w, int h, long now) {
        long t = now - cardAtMs;
        if (cardLabel.isEmpty() || t > 5200) return;
        float alpha = t < 500 ? t / 500f : t > 3800 ? 1 - (t - 3800) / 1400f : 1;
        int a = (int) (255 * clamp(alpha));
        if (a < 8) return;
        float rise = (1 - clamp(t / 900f)) * 4;
        float y = h * 0.6f + rise;
        float lw = spacedWidth(font, cardLabel, 0.7f, 2f);
        spaced(g, font, cardLabel, (w - lw) / 2f, y, 0.7f, argb(EMBER, a), 2f);
        if (!cardTitle.isEmpty()) {
            String title = cardTitle.toUpperCase(Locale.ROOT);
            float tw = spacedWidth(font, title, 1.5f, 1.5f);
            spaced(g, font, title, (w - tw) / 2f, y + 9, 1.5f, argb(INK, a), 1.5f);
            float lineW = Math.min(w * 0.5f, tw + 20);
            g.fill((int) ((w - lineW) / 2), (int) (y + 25), (int) ((w + lineW) / 2), (int) (y + 25) + 1, argb(INK, a / 3));
        }
    }

    // ------------------------------------------------------------------ world markers

    private void markers(GuiGraphicsExtractor g, Font font, Minecraft mc, int w, int h, int a) {
        CinematicCamera.Pose pose = CinematicCamera.lastPose;
        if (pose == null) return;
        LocalPlayer p = mc.player;
        Vec3 me = p.position();

        // Where Baritone is walking: a dotted line that fades into the distance.
        List<int[]> path = Afk.BARITONE.upcomingPath(80);
        Vec3 prev = null;
        for (int i = 0; i < path.size(); i++) {
            int[] n = path.get(i);
            Vec3 node = new Vec3(n[0] + 0.5, n[1] + 0.08, n[2] + 0.5);
            int pa = (int) (a * (0.85 - 0.6 * i / Math.max(1, path.size())));
            if (prev != null) {
                for (int s = 1; s <= 2; s++) {
                    float[] q = project(prev.lerp(node, s / 3.0), pose, w, h);
                    if (q != null) g.fill((int) q[0], (int) q[1], (int) q[0] + 1, (int) q[1] + 1, argb(EMBER, pa / 2));
                }
            }
            float[] q = project(node, pose, w, h);
            if (q != null) g.fill((int) q[0], (int) q[1], (int) q[0] + 2, (int) q[1] + 2, argb(EMBER, pa));
            prev = node;
        }
        if (!path.isEmpty()) {
            int[] n = path.get(path.size() - 1);
            label(g, font, new Vec3(n[0] + 0.5, n[1] + 1.2, n[2] + 0.5), "DEST", me, pose, w, h, EMBER, a, 2);
        }
        if (target != null) label(g, font, target, targetLabel.isEmpty() ? "TARGET" : targetLabel.toUpperCase(Locale.ROOT), me, pose, w, h, EMBER, a, 2);
        if (home != null) label(g, font, home.add(0, 1.5, 0), "HOME", me, pose, w, h, DIM, a * 3 / 4, 1);

        // What Herobrine can see.
        int n = 0;
        for (StateSampler.SeenOre o : Afk.STATE.seenOres()) {
            if (n++ >= 4) break;
            String name = o.id().replace("deepslate_", "").replace("_ore", "").toUpperCase(Locale.ROOT);
            label(g, font, Vec3.atCenterOf(o.pos()), name, me, pose, w, h, INK, a / 2, 1);
        }
        for (StateSampler.SeenMob m : Afk.STATE.seenMobs()) {
            if (!m.hostile() || m.entity() instanceof Player) continue;
            Vec3 head = m.entity().getPosition(1f).add(0, m.entity().getBbHeight() + 0.5, 0);
            label(g, font, head, m.id().toUpperCase(Locale.ROOT), me, pose, w, h, RED, a * 4 / 5, 1);
        }

        // The block being mined: four corner brackets.
        if (mc.gameMode != null && mc.gameMode.isDestroying() && mc.hitResult instanceof BlockHitResult bhr && bhr.getType() == HitResult.Type.BLOCK) {
            BlockPos b = bhr.getBlockPos();
            float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
            boolean any = false;
            for (int c = 0; c < 8; c++) {
                float[] q = project(new Vec3(b.getX() + (c & 1), b.getY() + ((c >> 1) & 1), b.getZ() + ((c >> 2) & 1)), pose, w, h);
                if (q == null) continue;
                any = true;
                minX = Math.min(minX, q[0]);
                minY = Math.min(minY, q[1]);
                maxX = Math.max(maxX, q[0]);
                maxY = Math.max(maxY, q[1]);
            }
            if (any && maxX - minX < w) brackets(g, (int) minX - 2, (int) minY - 2, (int) maxX + 2, (int) maxY + 2, argb(EMBER, a));
        }
    }

    private void label(GuiGraphicsExtractor g, Font font, Vec3 at, String text, Vec3 me, CinematicCamera.Pose pose, int w, int h, int rgb, int a, int r) {
        float[] q = project(at, pose, w, h);
        if (q == null || a < 8) return;
        int x = (int) q[0];
        int y = (int) q[1];
        diamond(g, x, y, r, argb(rgb, a));
        String s = text + "  " + Math.round(me.distanceTo(at)) + "M";
        spaced(g, font, s, x + r + 3, y - 2, 0.5f, argb(rgb, a), 1f);
    }

    /** World point to GUI coordinates with the camera pose of this frame; null if behind or off screen. */
    static float[] project(Vec3 world, CinematicCamera.Pose cp, int w, int h) {
        double dx = world.x - cp.x(), dy = world.y - cp.y(), dz = world.z - cp.z();
        double yaw = Math.toRadians(cp.yRot());
        double pitch = Math.toRadians(cp.xRot());
        double fx = -Math.sin(yaw) * Math.cos(pitch), fy = -Math.sin(pitch), fz = Math.cos(yaw) * Math.cos(pitch);
        double rx = -Math.cos(yaw), rz = -Math.sin(yaw);
        double ux = -rz * fy, uy = rz * fx - rx * fz, uz = rx * fy;
        double z = dx * fx + dy * fy + dz * fz;
        if (z < 0.2) return null;
        double x = dx * rx + dz * rz;
        double y = dx * ux + dy * uy + dz * uz;
        double f = (h / 2.0) / Math.tan(Math.toRadians(cp.fov()) / 2);
        float sx = (float) (w / 2.0 + x / z * f);
        float sy = (float) (h / 2.0 - y / z * f);
        if (sx < -20 || sy < -20 || sx > w + 20 || sy > h + 20) return null;
        return new float[] {sx, sy, (float) z};
    }

    // ------------------------------------------------------------------ drawing helpers

    private static void text(GuiGraphicsExtractor g, Font font, String s, float x, float y, float scale, int argb) {
        if ((argb >>> 24) < 8 || s.isEmpty()) return;
        Matrix3x2fStack pose = g.pose();
        pose.pushMatrix();
        pose.translate(x, y);
        pose.scale(scale, scale);
        g.text(font, s, 0, 0, argb, true);
        pose.popMatrix();
    }

    /** Letter-spaced text (the Icarus look). Returns the width drawn, in GUI units. */
    private static float spaced(GuiGraphicsExtractor g, Font font, String s, float x, float y, float scale, int argb, float spacing) {
        if (s.isEmpty()) return 0;
        Matrix3x2fStack pose = g.pose();
        pose.pushMatrix();
        pose.translate(x, y);
        pose.scale(scale, scale);
        float cx = 0;
        boolean draw = (argb >>> 24) >= 8;
        for (int i = 0; i < s.length(); i++) {
            String ch = String.valueOf(s.charAt(i));
            if (draw) g.text(font, ch, (int) cx, 0, argb, true);
            cx += font.width(ch) + spacing;
        }
        pose.popMatrix();
        return cx * scale;
    }

    private static float spacedWidth(Font font, String s, float scale, float spacing) {
        float cx = 0;
        for (int i = 0; i < s.length(); i++) cx += font.width(String.valueOf(s.charAt(i))) + spacing;
        return cx * scale;
    }

    private static void dot(GuiGraphicsExtractor g, int x, int y, int r, int argb) {
        g.fill(x - r, y - r, x + r + 1, y + r + 1, argb);
    }

    private static void diamond(GuiGraphicsExtractor g, int x, int y, int r, int argb) {
        for (int i = -r; i <= r; i++) {
            int half = r - Math.abs(i);
            g.fill(x - half, y + i, x + half + 1, y + i + 1, argb);
        }
    }

    private static void brackets(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int c) {
        int l = Math.max(2, Math.min(5, (x1 - x0) / 4));
        g.fill(x0, y0, x0 + l, y0 + 1, c);
        g.fill(x0, y0, x0 + 1, y0 + l, c);
        g.fill(x1 - l, y0, x1, y0 + 1, c);
        g.fill(x1 - 1, y0, x1, y0 + l, c);
        g.fill(x0, y1 - 1, x0 + l, y1, c);
        g.fill(x0, y1 - l, x0 + 1, y1, c);
        g.fill(x1 - l, y1 - 1, x1, y1, c);
        g.fill(x1 - 1, y1 - l, x1, y1, c);
    }

    private static int argb(int rgb, int alpha) {
        return (Math.max(0, Math.min(255, alpha)) << 24) | (rgb & 0xFFFFFF);
    }

    private static float clamp(float v) {
        return Math.max(0, Math.min(1, v));
    }

    private static List<String> limit(List<String> l, int max) {
        return l.size() <= max ? l : l.subList(0, max);
    }

    static List<String> wrap(Font font, String text, int maxWidth) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            if (word.isEmpty()) continue;
            while (font.width(word) > maxWidth) {
                String head = font.plainSubstrByWidth(word, maxWidth);
                if (head.isEmpty()) break;
                if (line.length() > 0) {
                    out.add(line.toString());
                    line.setLength(0);
                }
                out.add(head);
                word = word.substring(head.length());
            }
            String candidate = line.length() == 0 ? word : line + " " + word;
            if (font.width(candidate) <= maxWidth) {
                line.setLength(0);
                line.append(candidate);
            } else {
                out.add(line.toString());
                line.setLength(0);
                line.append(word);
            }
        }
        if (line.length() > 0) out.add(line.toString());
        return out;
    }

    private static List<String> strings(JsonArray arr, int max, int maxLen) {
        List<String> out = new ArrayList<>();
        for (JsonElement el : arr) {
            if (out.size() >= max) break;
            if (el.isJsonPrimitive()) out.add(clean(el.getAsString(), maxLen));
        }
        return List.copyOf(out);
    }

    private static Vec3 vec(JsonElement el) {
        if (el == null || !el.isJsonObject()) return null;
        JsonObject o = el.getAsJsonObject();
        if (!o.has("x") || !o.has("y") || !o.has("z")) return null;
        return new Vec3(o.get("x").getAsDouble() + 0.5, o.get("y").getAsDouble(), o.get("z").getAsDouble() + 0.5);
    }

    private static String clean(String s, int max) {
        if (s == null) return "";
        String flat = s.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ').strip();
        return flat.length() > max ? flat.substring(0, max) + "..." : flat;
    }
}
