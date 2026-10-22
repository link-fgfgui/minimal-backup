package io.github.linkfgfgui.minimalbackup.client;

import com.daqem.uilib.client.gui.component.AbstractComponent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * Flat button drawn with vanilla fills. UILib 0.3.6 (1.20.1) and 9.0.0 (1.21.1) ship
 * incompatible {@code ButtonComponent} classes, so the time tree keeps its own widget.
 */
final class TreeButton extends AbstractComponent<TreeButton> {
    static final int HEIGHT = 18;
    private final Component label;
    private boolean enabled = true;

    TreeButton(int x, int y, int width, Component label, Runnable action) {
        super(null, x, y, width, HEIGHT);
        this.label = label;
        //? if 1.20.1 {
        /*setOnClickEvent((clicked, screen, mouseX, mouseY, button) -> {
            if (enabled) {
                action.run();
            }
        });
        *///?} else {
        setOnClickEvent((clicked, screen, mouseX, mouseY, button) -> {
            if (!enabled) {
                return false;
            }
            action.run();
            return true;
        });
        //?}
    }

    void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        boolean hover = enabled && isTotalHovered(mouseX, mouseY);
        graphics.fill(0, 0, getWidth(), getHeight(), hover ? 0xFF3A3A3A : 0xFF242424);
        graphics.fill(0, 0, getWidth(), 1, 0xFF6A6A6A);
        graphics.fill(0, getHeight() - 1, getWidth(), getHeight(), 0xFF6A6A6A);
        graphics.fill(0, 0, 1, getHeight(), 0xFF6A6A6A);
        graphics.fill(getWidth() - 1, 0, getWidth(), getHeight(), 0xFF6A6A6A);

        Font font = Minecraft.getInstance().font;
        String text = label.getString();
        int color = !enabled ? 0xFF707070 : hover ? 0xFFFFFFFF : 0xFFD0D0D0;
        graphics.drawString(font, text, (getWidth() - font.width(text)) / 2,
                (getHeight() - font.lineHeight) / 2 + 1, color, false);
    }
}
