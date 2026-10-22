package io.github.linkfgfgui.minimalbackup.client;

import io.github.linkfgfgui.minimalbackup.config.ConfigHolder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Client config screen: edits the {@code ui.showTombstones} option in
 * {@code minimalbackup-client.toml} and writes it back through the loader's spec, so the change
 * survives a restart.
 *
 * <p>The time-tree key is not here: it is a normal key binding and is rebound in Minecraft's
 * {@code Options -> Controls} screen.
 *
 * <p>Plain vanilla widgets on purpose: the options are client-only. The screen is opened from the
 * time tree; clients without UILib edit the file or use the commands instead.
 */
public final class ConfigScreen extends Screen {
    private final Screen parent;
    private static final String[] BOOLEAN_KEYS = {
            "ui.showTombstones",
    };

    public ConfigScreen(Screen parent) {
        super(Component.translatable("minimalbackup.config.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int left = 12;
        int right = this.width - 112;
        int y = 40;
        for (int i = 0; i < BOOLEAN_KEYS.length; i++) {
            String key = BOOLEAN_KEYS[i];
            addRenderableWidget(Button.builder(label(key),
                    b -> {
                        ConfigHolder.persist(key, !ConfigHolder.values().bool(key));
                        b.setMessage(label(key));
                    }).bounds(right, y + i * 24, 100, 20).build());
        }

        addRenderableWidget(Button.builder(Component.translatable("minimalbackup.ui.done"),
                b -> onClose()).bounds(left, this.height - 28, 80, 20).build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        super.render(graphics, mouseX, mouseY, delta);
        graphics.drawString(this.font, this.title, 12, 16, 0xFFFFFF, false);
        int y = 40;
        for (String key : BOOLEAN_KEYS) {
            graphics.drawString(this.font, label(key), 12, y + 6, 0xC8C8C8, false);
            y += 24;
        }
        graphics.drawString(this.font,
                Component.translatable("minimalbackup.config.hint").getString(), 12,
                this.height - 44, 0x9A9A9A, false);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static Component label(String key) {
        return Component.translatable("minimalbackup.config." + key,
                Component.translatable(ConfigHolder.values().bool(key) ? "minimalbackup.config.on"
                        : "minimalbackup.config.off"));
    }
}
