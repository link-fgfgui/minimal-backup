package io.github.linkfgfgui.minimalbackup.client;

import com.daqem.uilib.client.gui.component.AbstractComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * Modal panel for confirmations and for text input. The screen routes mouse, key and char input
 * here while it is open, so the tree and the footer cannot be used behind it.
 */
final class ConfirmDialog extends AbstractComponent<ConfirmDialog> {
    private static final int PANEL_WIDTH = 260;
    private static final int BUTTON_WIDTH = 96;
    private static final int BUTTON_HEIGHT = 18;
    private static final int FIELD_HEIGHT = 18;
    private static final int MAX_INPUT = 48;

    private final Component title;
    private final List<String> lines;
    private final Consumer<String> onConfirm;
    private final Runnable onCancel;
    private final StringBuilder input;
    private final int panelX;
    private final int panelY;
    private final int panelHeight;
    private int cursor;

    ConfirmDialog(int screenWidth, int screenHeight, Component title, Component message,
            Runnable onConfirm, Runnable onCancel) {
        this(screenWidth, screenHeight, title, message, null, value -> onConfirm.run(), onCancel);
    }

    ConfirmDialog(int screenWidth, int screenHeight, Component title, Component message,
            String initialInput, Consumer<String> onConfirm, Runnable onCancel) {
        super(null, 0, 0, screenWidth, screenHeight);
        this.title = title;
        this.onConfirm = onConfirm;
        this.onCancel = onCancel;
        this.input = initialInput == null ? null : new StringBuilder(initialInput);
        this.cursor = initialInput == null ? 0 : initialInput.length();
        this.lines = wrap(Minecraft.getInstance().font, message.getString(), PANEL_WIDTH - 20);
        this.panelHeight = 26 + lines.size() * 10 + (input == null ? 0 : FIELD_HEIGHT + 6) + 30;
        this.panelX = (screenWidth - PANEL_WIDTH) / 2;
        this.panelY = (screenHeight - panelHeight) / 2;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        graphics.fill(0, 0, getWidth(), getHeight(), 0x90000000);
        graphics.fill(panelX - 1, panelY - 1, panelX + PANEL_WIDTH + 1, panelY + panelHeight + 1,
                0xFF4A4A4A);
        graphics.fill(panelX, panelY, panelX + PANEL_WIDTH, panelY + panelHeight, 0xFF141414);

        Font font = Minecraft.getInstance().font;
        graphics.drawString(font, title, panelX + 10, panelY + 8, 0xFFFFD700, false);
        int y = panelY + 24;
        for (String line : lines) {
            graphics.drawString(font, line, panelX + 10, y, 0xFFD8D8D8, false);
            y += 10;
        }

        if (input != null) {
            field(graphics, font, mouseX, mouseY);
        }

        button(graphics, font, cancelX(), buttonY(),
                Component.translatable("minimalbackup.ui.dialog.cancel"), mouseX, mouseY);
        button(graphics, font, confirmX(), buttonY(),
                Component.translatable("minimalbackup.ui.dialog.confirm"), mouseX, mouseY);
    }

    private void field(GuiGraphics graphics, Font font, int mouseX, int mouseY) {
        int x = panelX + 10;
        int y = fieldY();
        int width = PANEL_WIDTH - 20;
        graphics.fill(x, y, x + width, y + FIELD_HEIGHT, 0xFF000000);
        graphics.fill(x, y, x + width, y + 1, 0xFF6A6A6A);
        graphics.fill(x, y + FIELD_HEIGHT - 1, x + width, y + FIELD_HEIGHT, 0xFF6A6A6A);
        graphics.fill(x, y, x + 1, y + FIELD_HEIGHT, 0xFF6A6A6A);
        graphics.fill(x + width - 1, y, x + width, y + FIELD_HEIGHT, 0xFF6A6A6A);

        int maxWidth = width - 10;
        int start = 0;
        while (start < cursor && font.width(input.substring(start, cursor)) > maxWidth) {
            start++;
        }
        graphics.drawString(font, input.substring(start), x + 5, y + 5, 0xFFF0F0F0, false);
        if (System.currentTimeMillis() / 500 % 2 == 0) {
            int caretX = x + 5 + font.width(input.substring(start, cursor));
            graphics.fill(caretX, y + 4, caretX + 1, y + FIELD_HEIGHT - 4, 0xFFFFFFFF);
        }
    }

    boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) {
            return true;
        }
        if (inside(mouseX, mouseY, confirmX(), buttonY())) {
            confirm();
            return true;
        }
        if (inside(mouseX, mouseY, cancelX(), buttonY())) {
            onCancel.run();
            return true;
        }
        return true;
    }

    boolean keyPressed(int keyCode) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            onCancel.run();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            confirm();
            return true;
        }
        if (input == null) {
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_BACKSPACE) {
            if (cursor > 0) {
                input.deleteCharAt(cursor - 1);
                cursor--;
            }
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_DELETE) {
            if (cursor < input.length()) {
                input.deleteCharAt(cursor);
            }
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_LEFT) {
            cursor = Math.max(0, cursor - 1);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_RIGHT) {
            cursor = Math.min(input.length(), cursor + 1);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_HOME) {
            cursor = 0;
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_END) {
            cursor = input.length();
            return true;
        }
        return true;
    }

    boolean charTyped(char c) {
        if (input == null || c < ' ' || c == 127 || input.length() >= MAX_INPUT) {
            return true;
        }
        input.insert(cursor, c);
        cursor++;
        return true;
    }

    private void confirm() {
        onConfirm.accept(input == null ? "" : input.toString().trim());
    }

    private void button(GuiGraphics graphics, Font font, int x, int y, Component label,
            int mouseX, int mouseY) {
        boolean hover = inside(mouseX, mouseY, x, y);
        graphics.fill(x, y, x + BUTTON_WIDTH, y + BUTTON_HEIGHT, hover ? 0xFF3A3A3A : 0xFF242424);
        graphics.fill(x, y, x + BUTTON_WIDTH, y + 1, 0xFF6A6A6A);
        graphics.fill(x, y + BUTTON_HEIGHT - 1, x + BUTTON_WIDTH, y + BUTTON_HEIGHT, 0xFF6A6A6A);
        graphics.fill(x, y, x + 1, y + BUTTON_HEIGHT, 0xFF6A6A6A);
        graphics.fill(x + BUTTON_WIDTH - 1, y, x + BUTTON_WIDTH, y + BUTTON_HEIGHT, 0xFF6A6A6A);
        String text = label.getString();
        graphics.drawString(font, text, x + (BUTTON_WIDTH - font.width(text)) / 2, y + 5,
                hover ? 0xFFFFFFFF : 0xFFD0D0D0, false);
    }

    private int cancelX() {
        return panelX + PANEL_WIDTH / 2 - BUTTON_WIDTH - 4;
    }

    private int confirmX() {
        return panelX + PANEL_WIDTH / 2 + 4;
    }

    private int buttonY() {
        return panelY + panelHeight - BUTTON_HEIGHT - 8;
    }

    private int fieldY() {
        return panelY + 24 + lines.size() * 10 + 2;
    }

    private static boolean inside(double mouseX, double mouseY, int x, int y) {
        return mouseX >= x && mouseX < x + BUTTON_WIDTH && mouseY >= y
                && mouseY < y + BUTTON_HEIGHT;
    }

    /** Breaks at spaces when possible so English stays readable; falls back to chars for CJK. */
    private static List<String> wrap(Font font, String text, int width) {
        List<String> out = new ArrayList<>();
        for (String paragraph : text.split("\n")) {
            String remaining = paragraph.trim();
            while (!remaining.isEmpty()) {
                int fit = remaining.length();
                while (fit > 0 && font.width(remaining.substring(0, fit)) > width) {
                    fit--;
                }
                if (fit <= 0) {
                    fit = 1;
                }
                int cut = fit;
                if (fit < remaining.length()) {
                    int space = remaining.lastIndexOf(' ', fit);
                    if (space > 0) {
                        cut = space;
                    }
                }
                out.add(remaining.substring(0, cut).trim());
                remaining = remaining.substring(cut).trim();
            }
        }
        return out;
    }
}
