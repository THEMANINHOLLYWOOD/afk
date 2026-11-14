package dev.afk;

import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.client.Minecraft;

/**
 * AFK's look out of the box, applied once: brightness at maximum and the Darkness effect's
 * pulsing turned off (both are ordinary vanilla accessibility settings you can change back).
 */
final class VisualDefaults {
    private VisualDefaults() {
    }

    static void applyOnce(Minecraft mc) {
        try {
            Path flag = Afk.dataDir().resolve("visual-defaults-applied");
            if (Files.exists(flag)) return;
            mc.options.gamma().set(1.0);
            mc.options.darknessEffectScale().set(0.0);
            mc.options.save();
            Files.createDirectories(flag.getParent());
            Files.writeString(flag, "gamma=1.0 darknessEffectScale=0.0\n");
            Afk.LOG.info("Applied AFK visual defaults (max brightness, no darkness pulse)");
        } catch (Exception e) {
            Afk.LOG.warn("Could not apply visual defaults", e);
        }
    }
}
