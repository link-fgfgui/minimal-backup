package io.github.linkfgfgui.minimalbackup.client;

import com.daqem.uilib.api.client.gui.component.IComponent;
import com.daqem.uilib.api.client.gui.component.advancement.IAdvancement;
import com.daqem.uilib.api.client.gui.component.advancement.IAdvancementTree;
import com.daqem.uilib.client.gui.AbstractScreen;
import com.daqem.uilib.client.gui.background.Backgrounds;
import com.daqem.uilib.client.gui.component.TextComponent;
import com.daqem.uilib.client.gui.component.ItemComponent;
import com.daqem.uilib.client.gui.component.advancement.AdvancementComponent;
import com.daqem.uilib.client.gui.component.advancement.AdvancementIconComponent;
import com.daqem.uilib.client.gui.component.advancement.AdvancementTreeContainerComponent;
import com.daqem.uilib.client.gui.component.texture.TextureComponent;
import com.daqem.uilib.client.gui.texture.Texture;
import com.daqem.uilib.client.gui.text.Text;
import com.mojang.authlib.GameProfile;
import io.github.linkfgfgui.minimalbackup.engine.Snapshot;
import io.github.linkfgfgui.minimalbackup.server.BackupService;
import io.github.linkfgfgui.minimalbackup.server.UiBridge;
import io.github.linkfgfgui.minimalbackup.ui.BackupRequest;
import io.github.linkfgfgui.minimalbackup.ui.BackupResponse;
import io.github.linkfgfgui.minimalbackup.ui.SnapshotMeta;
import io.github.linkfgfgui.minimalbackup.ui.UiText;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.lwjgl.glfw.GLFW;

/**
 * UILib time tree: the same window + background + tree container composition the library's own
 * {@code AdvancementsComponent} uses. The tree is only built once the server has answered LIST,
 * because {@code AdvancementTreeComponent} NPEs while the root is absent.
 */
public final class TimeTreeScreen extends AbstractScreen {
    private static final int TREE_X = 10;
    private static final int TREE_Y = 30;
    private static final int BUTTON_WIDTH = 98;
    private static final int GAP = 4;
    private static final int BORDER_LEFT = 9;
    private static final int BORDER_TOP = 18;
    private static final int BORDER_RIGHT = 9;
    private static final int BORDER_BOTTOM = 9;

    private final List<Snapshot> snapshots = new ArrayList<>();
    private final List<IComponent<?>> dynamic = new ArrayList<>();
    private final Map<String, TextureComponent> selectionRings = new HashMap<>();
    private final Map<AdvancementComponent, Snapshot> nodeByComponent = new HashMap<>();
    private AdvancementTreeContainerComponent treeContainer;
    private final List<UiText.Line> statusLines = new ArrayList<>();
    private final List<UiText.Line> notice = new ArrayList<>();
    private ConfirmDialog dialog;
    private TreeButton configButton;
    private TreeButton createButton;
    private TreeButton restoreButton;
    private TreeButton renameButton;
    private TreeButton verifyButton;
    private TreeButton deleteButton;
    private int footerTop;
    private String selected = "";
    private String pendingSlot = "";
    private BackupRequest.Action pendingAction;

    public TimeTreeScreen() {
        super(Component.translatable("minimalbackup.ui.title"));
    }

    @Override
    public void startScreen() {
        setPauseScreen(false);
        setBackground(Backgrounds.getDefaultBackground(getWidth(), getHeight()));
        // A request that never answered must not block the first refresh.
        pendingAction = null;
        pendingSlot = "";
        rebuildAll();
        request(BackupRequest.of(BackupRequest.Action.LIST));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        // The world is already in the framebuffer here, the GUI is not: a clean cover frame.
        ThumbnailCapture.capturePending();
        super.render(graphics, mouseX, mouseY, delta);
    }

    @Override
    public void onTickScreen(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        // No animation in v0; keep the OP-only buttons in sync if the player is opped/de-opped.
        updateButtons();
    }

    @Override
    public void onResizeScreenRepositionComponents(int width, int height) {
        rebuildAll();
    }

    /** UILib draws the hover panel at its own origin; pin it to the cursor before drawing. */
    @Override
    public void renderTooltips(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        if (dialog != null) {
            return;
        }
        if (treeContainer != null) {
            for (AdvancementComponent component
                    : treeContainer.getTreeComponent().getAllAdvancementComponents()) {
                if (component.getIconComponent().isTotalHovered(mouseX, mouseY)) {
                    Snapshot snapshot = nodeByComponent.get(component);
                    if (snapshot != null) {
                        SnapshotTooltip.draw(graphics, snapshot, mouseX, mouseY,
                                getHeight() - 48);
                    }
                    return;
                }
            }
        }
        super.renderTooltips(graphics, mouseX, mouseY, delta);
    }

    private void rebuildAll() {
        for (IComponent<?> component : new ArrayList<>(getComponents())) {
            removeComponent(component);
        }
        dynamic.clear();
        selectionRings.clear();
        nodeByComponent.clear();
        dialog = null;
        addText(10, 8, this.title);
        configButton = addButton(getWidth() - 10 - 60, 6, 60,
                Component.translatable("minimalbackup.config.title"),
                () -> Minecraft.getInstance().setScreen(new ConfigScreen(this)));

        int width = Math.min(getWidth() - TREE_X * 2, 480);
        int height = Math.max(96, getHeight() - TREE_Y - 52);
        int innerWidth = width - BORDER_LEFT - BORDER_RIGHT;
        int innerHeight = height - BORDER_TOP - BORDER_BOTTOM;
        if (snapshots.isEmpty()) {
            treeContainer = null;
            addText(TREE_X + BORDER_LEFT, TREE_Y + BORDER_TOP + 8,
                    Component.translatable("minimalbackup.ui.empty"));
        } else {
            SnapshotTree tree = buildTree();
            treeContainer = new AdvancementTreeContainerComponent(
                    TREE_X + BORDER_LEFT, TREE_Y + BORDER_TOP, innerWidth, innerHeight, tree);
            addComponent(treeContainer);
            wireTreeClicks(treeContainer, tree);
        }
        addFooter(height);
        rebuildStatus();
    }

    private void addFooter(int treeHeight) {
        int first = TREE_Y + treeHeight + 6;
        footerTop = first;
        int second = first + 22;
        createButton = addButton(10, first,
                Component.translatable("minimalbackup.ui.create"), this::askCreate);
        restoreButton = addButton(10 + (BUTTON_WIDTH + GAP), first,
                Component.translatable("minimalbackup.ui.restore"), this::askRestore);
        renameButton = addButton(10 + (BUTTON_WIDTH + GAP) * 2, first,
                Component.translatable("minimalbackup.ui.rename"), this::askRename);
        verifyButton = addButton(10 + (BUTTON_WIDTH + GAP) * 3, first,
                Component.translatable("minimalbackup.ui.verify"), this::askVerify);
        deleteButton = addButton(10, second,
                Component.translatable("minimalbackup.ui.delete"), this::askDelete);
        addButton(10 + (BUTTON_WIDTH + GAP), second,
                Component.translatable("minimalbackup.ui.refresh"),
                () -> request(BackupRequest.of(BackupRequest.Action.LIST)));
        addButton(10 + (BUTTON_WIDTH + GAP) * 2, second,
                Component.translatable("gui.done"), this::onClose);
        updateButtons();
    }

    /** Create plus every selected-snapshot action needs OP 2, including read-only verify. */
    private void updateButtons() {
        boolean has = !selected.isEmpty();
        boolean canModify = canModify();
        if (createButton != null) {
            createButton.setEnabled(canModify);
        }
        for (TreeButton button : new TreeButton[]{
                restoreButton, renameButton, verifyButton, deleteButton}) {
            if (button != null) {
                button.setEnabled(canModify && has);
            }
        }
    }

    /** The client's synced vanilla permission level; the server re-checks it on every packet. */
    private static boolean canModify() {
        Minecraft client = Minecraft.getInstance();
        return client.player != null
                && client.player.hasPermissions(BackupService.REQUIRED_PERMISSION);
    }

    /**
     * Hides the UI for a few ticks so the cover screenshot is the world, not this screen, then
     * reopens the tree once the server has created the snapshot.
     */
    private void askCreate() {
        if (!canModify()) {
            return;
        }
        openNameDialog(Component.translatable("minimalbackup.ui.dialog.create.title"),
                Component.translatable("minimalbackup.ui.dialog.create.message"),
                Component.translatable("minimalbackup.ui.create.default",
                        ClientOptions.defaultBackupName()).getString(),
                this::createWithCover);
    }

    private void askVerify() {
        if (!canModify()) {
            return;
        }
        request(new BackupRequest(BackupRequest.Action.VERIFY, selected, "", false));
    }

    private void askRename() {
        if (!canModify()) {
            return;
        }
        String slot = selected;
        String current = snapshots.stream()
                .filter(snapshot -> snapshot.slot().equals(slot))
                .map(Snapshot::displayName)
                .filter(name -> !name.isEmpty())
                .findFirst()
                .orElseGet(() -> Component.translatable("minimalbackup.ui.create.default",
                        ClientOptions.defaultBackupName()).getString());
        openNameDialog(Component.translatable("minimalbackup.ui.dialog.rename.title"),
                Component.translatable("minimalbackup.ui.dialog.rename.message"),
                current,
                name -> request(new BackupRequest(BackupRequest.Action.RENAME, slot, name, false)));
    }

    /** Human label for a slot: its name when set, otherwise the generation. */
    private String label(String slot) {
        return snapshots.stream()
                .filter(snapshot -> snapshot.slot().equals(slot))
                .map(snapshot -> snapshot.displayName().isEmpty()
                        ? Component.translatable("minimalbackup.ui.tip.generation",
                                snapshot.generation()).getString()
                        : snapshot.displayName())
                .findFirst()
                .orElse(slot);
    }

    private void createWithCover(String name) {
        if (!canModify() || Minecraft.getInstance().player == null) {
            return;
        }
        request(new BackupRequest(BackupRequest.Action.CREATE, "", name, false));
    }

    private void rebuildStatus() {
        for (IComponent<?> component : new ArrayList<>(dynamic)) {
            removeComponent(component);
        }
        dynamic.clear();
        int maxWidth = Math.max(40, getWidth() - 20);
        for (int i = 0; i < statusLines.size() && i < 3; i++) {
            UiText.Line line = statusLines.get(i);
            Component text = line.literal()
                    ? Component.literal(String.valueOf(line.args().get(0)))
                    : Component.translatable(line.key(), line.args().toArray());
            Text styled = new Text(getFont(), Component.literal(fit(text.getString(), maxWidth)));
            styled.setTextColor(line.error() ? 0xFF7070 : 0xD8D8D8);
            TextComponent component = new TextComponent(10, 18 + i * 9, styled);
            dynamic.add(component);
            addComponent(component);
        }
    }

    private String fit(String text, int maxWidth) {
        if (getFont().width(text) <= maxWidth) {
            return text;
        }
        String cut = text;
        while (!cut.isEmpty() && getFont().width(cut + "...") > maxWidth) {
            cut = cut.substring(0, cut.length() - 1);
        }
        return cut + "...";
    }

    private void setStatus(List<UiText.Line> lines) {
        statusLines.clear();
        statusLines.addAll(lines);
        queue(this::rebuildStatus);
    }

    private void wireTreeClicks(AdvancementTreeContainerComponent container, SnapshotTree tree) {
        List<AdvancementComponent> components =
                container.getTreeComponent().getAllAdvancementComponents();
        List<Snapshot> order = preOrder(tree.root);
        for (int i = 0; i < components.size() && i < order.size(); i++) {
            Snapshot snapshot = order.get(i);
            //? if 1.20.1 {
            /*components.get(i).setOnClickEvent(
                    (clicked, screen, mouseX, mouseY, button) -> select(snapshot));
            *///?} else {
            components.get(i).setOnClickEvent((clicked, screen, mouseX, mouseY, button) -> {
                select(snapshot);
                return true;
            });
            //?}
            nodeByComponent.put(components.get(i), snapshot);
            installAvatar(components.get(i), snapshot);
        }
    }

    /** Replaces the 3D item head with a 2D face cropped from the skin, plus the selection ring. */
    private void installAvatar(AdvancementComponent component, Snapshot snapshot) {
        AdvancementIconComponent icon = component.getIconComponent();
        for (IComponent<?> child : new ArrayList<>(icon.getChildren())) {
            if (child instanceof ItemComponent) {
                icon.removeChild(child);
            }
        }
        icon.addChild(new TextureComponent(faceTexture(snapshot.createdBy()), 5, 5, 16, 16));
        TextureComponent ring = new TextureComponent(new Texture(
                ClientCompat.id("minimalbackup", "textures/gui/selection.png"),
                0, 0, 30, 30, 30, 30), -2, -2, 30, 30);
        ring.setVisible(snapshot.slot().equals(selected));
        icon.addChild(ring);
        selectionRings.put(snapshot.slot(), ring);
        // UILib's own hover panel lays out an item icon and raw description; we draw our own.
        component.getHoverComponent().setVisible(false);
    }

    private static Texture faceTexture(String createdBy) {
        if (createdBy != null && createdBy.indexOf('|') > 0) {
            String[] parts = createdBy.split("\\|", 2);
            if (parts.length == 2 && !parts[1].isEmpty()) {
                try {
                    UUID id = UUID.fromString(parts[0]);
                    //? if 1.20.1 {
                    /*ResourceLocation texture = Minecraft.getInstance().getSkinManager()
                            .getInsecureSkinLocation(new GameProfile(id, parts[1]));
                    if (texture != null) {
                        return new Texture(texture, 8, 8, 8, 8, 64, 64);
                    }
                    *///?} else {
                    net.minecraft.client.resources.PlayerSkin skin = Minecraft.getInstance()
                            .getSkinManager().getInsecureSkin(new GameProfile(id, parts[1]));
                    if (skin != null && skin.texture() != null) {
                        return new Texture(skin.texture(), 8, 8, 8, 8, 64, 64);
                    }
                    //?}
                } catch (RuntimeException ignored) {
                    // fall through to the creeper face
                }
            }
        }
        return new Texture(ClientCompat.id("minecraft",
                "textures/entity/creeper/creeper.png"), 8, 8, 8, 8, 64, 32);
    }

    private void select(Snapshot snapshot) {
        selected = snapshot.slot();
        notice.clear();
        for (Map.Entry<String, TextureComponent> entry : selectionRings.entrySet()) {
            entry.getValue().setVisible(entry.getKey().equals(selected));
        }
        // The gold ring is the selection feedback; no status text needed.
        setStatus(List.of());
        updateButtons();
    }

    /** Clears the selection; the action buttons go back to disabled. */
    private void clearSelection() {
        selected = "";
        notice.clear();
        for (TextureComponent ring : selectionRings.values()) {
            ring.setVisible(false);
        }
        setStatus(List.of());
        updateButtons();
    }

    private void request(BackupRequest request) {
        if (pendingAction != null) {
            // One request at a time: every create ends in a full world save on the server thread,
            // so a stack of them stalls the tick (a 100-create burst tripped the watchdog).
            return;
        }
        pendingAction = request.action();
        pendingSlot = request.slot();
        UiBridge.sendRequest(request);
        if (request.action() != BackupRequest.Action.LIST) {
            setStatus(List.of(UiText.key("minimalbackup.ui.working")));
        }
    }

    /** Called by the loader client handler with the service result. */
    public void acceptResponse(BackupResponse response) {
        BackupRequest.Action action = pendingAction;
        String slot = pendingSlot;
        pendingAction = null;
        pendingSlot = "";
        if (action == BackupRequest.Action.LIST && response.ok()) {
            snapshots.clear();
            boolean showTombstones = io.github.linkfgfgui.minimalbackup.config.ConfigHolder
                    .values().bool("ui.showTombstones");
            for (SnapshotMeta meta : response.snapshots()) {
                if (meta.tombstone() && !showTombstones) {
                    continue;
                }
                snapshots.add(meta.toSnapshot());
            }
            snapshots.sort(Comparator.comparingInt(Snapshot::generation));
            if (!selected.isEmpty() && snapshots.stream().noneMatch(s -> s.slot().equals(selected))) {
                selected = "";
            }
            // A refresh follows every action; keep the action result instead of "N snapshot(s)".
            statusLines.clear();
            if (notice.isEmpty()) {
                statusLines.addAll(UiText.lines(BackupRequest.Action.LIST, "", response));
            } else {
                statusLines.addAll(notice);
                notice.clear();
            }
            rebuildAll();
            return;
        }
        if (action == null) {
            setStatus(List.of(UiText.literal(response.message(), !response.ok())));
            return;
        }
        setStatus(UiText.lines(action, slot, response));
        if (response.ok() && action != BackupRequest.Action.RESTORE
                && action != BackupRequest.Action.STATUS
                && action != BackupRequest.Action.DISARM) {
            notice.clear();
            notice.addAll(statusLines);
            request(BackupRequest.of(BackupRequest.Action.LIST));
        }
    }

    private void askRestore() {
        if (!canModify()) {
            return;
        }
        String slot = selected;
        openDialog(Component.translatable("minimalbackup.ui.dialog.restore.title"),
                Component.translatable("minimalbackup.ui.dialog.restore.message", label(slot)),
                () -> request(new BackupRequest(BackupRequest.Action.RESTORE, slot, "", true)));
    }

    private void askDelete() {
        if (!canModify()) {
            return;
        }
        String slot = selected;
        openDialog(Component.translatable("minimalbackup.ui.dialog.delete.title"),
                Component.translatable("minimalbackup.ui.dialog.delete.message"),
                () -> request(new BackupRequest(BackupRequest.Action.DELETE, slot, "", false)));
    }

    private void openDialog(Component title, Component message, Runnable onConfirm) {
        closeDialog();
        dialog = new ConfirmDialog(getWidth(), getHeight(), title, message,
                () -> {
                    closeDialog();
                    onConfirm.run();
                }, this::closeDialog);
        addComponent(dialog);
    }

    private void openNameDialog(Component title, Component message, String initial,
            Consumer<String> onConfirm) {
        closeDialog();
        dialog = new ConfirmDialog(getWidth(), getHeight(), title, message, initial,
                value -> {
                    closeDialog();
                    onConfirm.accept(value);
                }, this::closeDialog);
        addComponent(dialog);
    }

    private void closeDialog() {
        if (dialog != null) {
            removeComponent(dialog);
            dialog = null;
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (dialog != null) {
            return dialog.mouseClicked(mouseX, mouseY, button);
        }
        // UILib 0.3.6 (1.20.1) returns false after a component handled the click, so remember
        // whether the cursor was on a node before the screen dispatches the event.
        boolean nodeClick = overTreeNode(mouseX, mouseY);
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        // A click on empty space above the footer drops the selection.
        boolean configClick = configButton != null && configButton.isTotalHovered(mouseX, mouseY);
        if (!nodeClick && !configClick && button == 0 && !selected.isEmpty()
                && mouseY < footerTop) {
            clearSelection();
            return true;
        }
        return false;
    }

    private boolean overTreeNode(double mouseX, double mouseY) {
        if (treeContainer == null) {
            return false;
        }
        for (AdvancementComponent component
                : treeContainer.getTreeComponent().getAllAdvancementComponents()) {
            if (component.isTotalHovered(mouseX, mouseY)) {
                return true;
            }
        }
        return false;
    }

    //? if 1.20.1 {
    /*@Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (dialog != null) {
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }
    *///?} else {
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amountX, double amountY) {
        if (dialog != null) {
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, amountX, amountY);
    }
    //?}

    @Override
    public void onClose() {
        TreeUi.closed(this);
        super.onClose();
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (dialog != null) {
            return dialog.keyPressed(keyCode);
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char c, int modifiers) {
        if (dialog != null) {
            return dialog.charTyped(c);
        }
        return super.charTyped(c, modifiers);
    }

    private TreeButton addButton(int x, int y, Component label, Runnable action) {
        return addButton(x, y, BUTTON_WIDTH, label, action);
    }

    private TreeButton addButton(int x, int y, int width, Component label, Runnable action) {
        TreeButton component = new TreeButton(x, y, width, label, action);
        addComponent(component);
        return component;
    }

    private void addText(int x, int y, Component text) {
        addComponent(new TextComponent(x, y, new Text(getFont(), text)));
    }

    private void queue(Runnable action) {
        Minecraft.getInstance().execute(() -> {
            if (Minecraft.getInstance().screen == this) {
                action.run();
            }
        });
    }

    private SnapshotTree buildTree() {
        Map<String, SnapshotAdvancement> nodes = new LinkedHashMap<>();
        for (Snapshot snapshot : snapshots) {
            nodes.put(snapshot.slot(), new SnapshotAdvancement(snapshot));
        }
        SnapshotAdvancement root = null;
        for (SnapshotAdvancement node : nodes.values()) {
            String parent = node.snapshot.parentSlot();
            SnapshotAdvancement parentNode = parent.isEmpty() ? null : nodes.get(parent);
            if (parentNode != null && parentNode != node) {
                parentNode.addChild(node);
            } else if (root == null) {
                root = node;
            }
        }
        if (root == null && !nodes.isEmpty()) {
            root = nodes.values().iterator().next();
        }
        if (root != null) {
            for (SnapshotAdvancement node : nodes.values()) {
                if (node != root && node.getParent().isEmpty()) {
                    root.addChild(node);
                }
            }
        }
        return new SnapshotTree(root);
    }

    private static List<Snapshot> preOrder(SnapshotAdvancement root) {
        List<Snapshot> out = new ArrayList<>();
        walk(root, out);
        return out;
    }

    private static void walk(SnapshotAdvancement node, List<Snapshot> out) {
        if (node == null) {
            return;
        }
        out.add(node.snapshot);
        for (IAdvancement child : node.getChildren()) {
            if (child instanceof SnapshotAdvancement snapshotChild) {
                walk(snapshotChild, out);
            }
        }
    }

    private static final class SnapshotTree implements IAdvancementTree {
        private final SnapshotAdvancement root;

        private SnapshotTree(SnapshotAdvancement root) {
            this.root = root;
        }

        @Override
        public Optional<IAdvancement> getRoot() {
            return Optional.ofNullable(root);
        }

        @Override
        public ResourceLocation getBackgroundTexture() {
            return ClientCompat.id("minecraft",
                    "textures/gui/advancements/backgrounds/stone.png");
        }

        @Override
        public Component getName() {
            return Component.translatable("minimalbackup.ui.title");
        }

        @Override
        public ItemStack getIcon() {
            return new ItemStack(Items.CLOCK);
        }
    }

    private static final class SnapshotAdvancement implements IAdvancement {
        private final Snapshot snapshot;
        private final List<IAdvancement> children = new ArrayList<>();
        private IAdvancement parent;

        private SnapshotAdvancement(Snapshot snapshot) {
            this.snapshot = snapshot;
        }

        @Override
        public Optional<IAdvancement> getParent() {
            return Optional.ofNullable(parent);
        }

        @Override
        public List<IAdvancement> getChildren() {
            return children;
        }

        @Override
        public void addChild(IAdvancement advancement) {
            if (advancement == null || advancement == this || children.contains(advancement)) {
                return;
            }
            IAdvancement cursor = this;
            while (cursor != null) {
                if (cursor == advancement) {
                    return;
                }
                cursor = cursor.getParent().orElse(null);
            }
            children.add(advancement);
            if (advancement instanceof SnapshotAdvancement child) {
                child.parent = this;
            }
        }

        @Override
        public ItemStack getIcon() {
            // The 3D item is removed by installAvatar; the 2D face is drawn inside the frame.
            return new ItemStack(Items.PAPER);
        }

        @Override
        public Component getName() {
            return Component.literal(snapshot.slot() + " gen=" + snapshot.generation()
                    + (snapshot.displayName().isEmpty() ? "" : " " + snapshot.displayName()));
        }

        @Override
        public List<Component> getDescription() {
            return List.of(
                    Component.literal("entries=" + snapshot.entryCount()),
                    Component.literal("logical=" + snapshot.logicalBytes()
                            + " physical=" + snapshot.physicalBytes()),
                    Component.literal(snapshot.damaged() ? "damaged" : "ok")
            );
        }

        @Override
        public boolean isObtained() {
            return !snapshot.tombstone();
        }

        //? if 1.20.1 {
        /*@Override
        public net.minecraft.advancements.FrameType getFrameType() {
            return snapshot.damaged()
                    ? net.minecraft.advancements.FrameType.CHALLENGE
                    : net.minecraft.advancements.FrameType.TASK;
        }
        *///?} else {
        @Override
        public net.minecraft.advancements.AdvancementType getFrameType() {
            return snapshot.damaged()
                    ? net.minecraft.advancements.AdvancementType.CHALLENGE
                    : net.minecraft.advancements.AdvancementType.TASK;
        }
        //?}
    }
}
