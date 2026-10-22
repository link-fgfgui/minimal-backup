package io.github.linkfgfgui.minimalbackup.client;

import com.mojang.blaze3d.platform.NativeImage;
import io.github.linkfgfgui.minimalbackup.engine.Snapshot;
import io.github.linkfgfgui.minimalbackup.server.BackupRuntime;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * Snapshot tooltip drawn next to the cursor. UILib's {@code AdvancementHoverComponent} is hidden
 * because it lays out its own icon and description, which does not fit a backup entry.
 */
final class SnapshotTooltip {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());
    private static final Map<String, Cover> COVERS = new HashMap<>();

    private SnapshotTooltip() {
    }

    static void draw(GuiGraphics graphics, Snapshot snapshot, int mouseX, int mouseY,
            int bottomLimit) {
        Font font = Minecraft.getInstance().font;
        List<Line> lines = new ArrayList<>();
        if (!snapshot.displayName().isEmpty()) {
            lines.add(new Line(Component.translatable("minimalbackup.ui.tip.generation",
                    snapshot.generation()), false));
        }
        lines.add(new Line(Component.translatable("minimalbackup.ui.tip.entries",
                snapshot.entryCount()), false));
        lines.add(new Line(Component.translatable("minimalbackup.ui.tip.size",
                bytes(snapshot.logicalBytes()), bytes(snapshot.physicalBytes())), false));
        String by = playerName(snapshot.createdBy());
        if (!by.isEmpty()) {
            lines.add(new Line(Component.translatable("minimalbackup.ui.tip.by", by), false));
        }
        if (snapshot.savedAt() > 0) {
            lines.add(new Line(Component.literal(
                    TIME.format(Instant.ofEpochMilli(snapshot.savedAt()))), false));
        }
        if (snapshot.damaged()) {
            lines.add(new Line(Component.translatable("minimalbackup.ui.tip.damaged"), true));
        }
        if (snapshot.tombstone()) {
            lines.add(new Line(Component.translatable("minimalbackup.ui.tip.deleted"), true));
        }

        Component title = snapshot.displayName().isEmpty()
                ? Component.translatable("minimalbackup.ui.tip.generation", snapshot.generation())
                : Component.literal(snapshot.displayName());
        int textWidth = font.width(title);
        for (Line line : lines) {
            textWidth = Math.max(textWidth, font.width(line.text()));
        }
        int pad = 6;
        int width = Math.max(140, textWidth + pad * 2);

        Cover cover = cover(snapshot.slot());
        int coverWidth = 0;
        int coverHeight = 0;
        if (cover != null) {
            coverWidth = width - pad * 2;
            coverHeight = Math.max(24, coverWidth * cover.height() / Math.max(1, cover.width()));
            if (coverHeight > 64) {
                coverHeight = 64;
                coverWidth = Math.max(40,
                        coverHeight * cover.width() / Math.max(1, cover.height()));
            }
        }
        int height = 5 + 10 + (coverHeight > 0 ? coverHeight + 4 : 2) + lines.size() * 10 + 4;
        if (height > bottomLimit && coverHeight > 0) {
            // Not enough room above the footer for the preview; drop it rather than overlap.
            coverWidth = 0;
            coverHeight = 0;
            height = 5 + 10 + 2 + lines.size() * 10 + 4;
        }

        int screenWidth = Minecraft.getInstance().getWindow().getGuiScaledWidth();
        int screenHeight = Minecraft.getInstance().getWindow().getGuiScaledHeight();
        int x = Math.max(0, Math.min(mouseX + 12, screenWidth - width));
        int y = Math.max(0, Math.min(mouseY - 8, screenHeight - height));
        // Text always renders above fills in vanilla GUI batching, so never overlap the footer.
        y = Math.max(0, Math.min(y, bottomLimit - height));

        graphics.fill(x, y, x + width, y + height, 0xF0100010);
        border(graphics, x, y, width, height, 0xFFB08000);

        int textY = y + 5;
        graphics.drawString(font, title, x + pad, textY, 0xFFFFD700, false);
        textY += 10;
        if (cover != null) {
            graphics.blit(cover.id(), x + pad, textY + 2, coverWidth, coverHeight,
                    0, 0, cover.width(), cover.height(), cover.width(), cover.height());
            textY += coverHeight + 4;
        }
        graphics.fill(x + pad, textY, x + width - pad, textY + 1, 0xFFB08000);
        textY += 4;
        for (Line line : lines) {
            graphics.drawString(font, line.text(), x + pad, textY,
                    line.warn() ? 0xFFFF6060 : 0xFFD0D0D0, false);
            textY += 10;
        }
    }

    private static Cover cover(String slot) {
        Cover cached = COVERS.get(slot);
        if (cached != null) {
            return cached;
        }
        BackupRuntime runtime = BackupRuntime.get();
        if (runtime == null) {
            return null;
        }
        Path file = runtime.snapshotRoot().resolve("thumbnails").resolve(slot + ".png");
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try (InputStream in = Files.newInputStream(file)) {
            NativeImage image = NativeImage.read(in);
            ResourceLocation textureId = ClientCompat.id("minimalbackup",
                    "thumb/" + slot.toLowerCase(Locale.ROOT));
            Minecraft.getInstance().getTextureManager()
                    .register(textureId, new DynamicTexture(image));
            Cover created = new Cover(textureId, image.getWidth(), image.getHeight());
            COVERS.put(slot, created);
            return created;
        } catch (IOException e) {
            return null;
        }
    }

    private static String playerName(String createdBy) {
        if (createdBy == null) {
            return "";
        }
        int bar = createdBy.indexOf('|');
        return bar >= 0 ? createdBy.substring(bar + 1) : createdBy;
    }

    private static String bytes(long value) {
        if (value < 1024) {
            return value + " B";
        }
        double scaled = value;
        String[] units = {"KiB", "MiB", "GiB", "TiB"};
        int index = -1;
        while (scaled >= 1024 && index < units.length - 1) {
            scaled /= 1024;
            index++;
        }
        return String.format(Locale.ROOT, "%.1f %s", scaled, units[index]);
    }

    private static void border(GuiGraphics graphics, int x, int y, int width, int height, int color) {
        graphics.fill(x, y, x + width, y + 1, color);
        graphics.fill(x, y + height - 1, x + width, y + height, color);
        graphics.fill(x, y, x + 1, y + height, color);
        graphics.fill(x + width - 1, y, x + width, y + height, color);
    }

    private record Line(Component text, boolean warn) {
    }

    private record Cover(ResourceLocation id, int width, int height) {
    }
}
