package mekanism.cloudworks.client.gui.line;

import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import mekanism.api.qio.resource.QIOResourceCodecRegistry;
import mekanism.client.gui.IGuiWrapper;
import mekanism.client.gui.element.GuiElement;
import mekanism.client.render.MekanismRenderer;
import mekanism.cloudworks.client.gui.CloudworksViewport;
import mekanism.cloudworks.common.network.data.CloudworksGuiData;
import mekanism.cloudworks.common.network.data.CloudworksLineDraft;
import mekanism.cloudworks.common.data.CloudworksChannelNode;
import mekanism.cloudworks.common.service.CloudworksDraftGeometry;
import mekanism.cloudworks.common.blueprint.CloudworksBlueprint;
import mekanism.cloudworks.common.registry.CloudworksRegistries;
import mekanism.cloudworks.common.slot.MachineSlotLayout;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.TextComponentString;
import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

import static mekanism.client.gui.GuiUtils.fill;

/** Graph rendering and coordinate hit testing, shared by edit and observer modes. */
public final class GuiLineCanvas extends GuiElement {
    public static final int CARD_WIDTH = 132;
    private static final ResourceLocation HAZARD_TEXTURE = new ResourceLocation(
            "cloudworks", "textures/cloudworks/gui/hazard-stripe.png");
    private final CloudworksViewport viewport;
    private final CloudworksLineEditorModel model;
    private final List<String> channelCodecs;
    private final Map<UUID, CachedChannelPorts> cachedChannelPorts = new HashMap<>();
    private final Supplier<CloudworksLineDraft> configuration;
    private final Supplier<CloudworksGuiData.Graph> runtime;
    private final BooleanSupplier editable;
    private final Runnable openProperties;
    private final LongSupplier sampleTick;
    private UUID dragging, lastSelected;
    private Port linking;
    private boolean panning;
    private double lastX, lastY, grabX, grabY;
    private long lastClick;
    private final List<UUID> cardOrder = new ArrayList<>();
    private CloudworksBlueprint ghost;
    private java.util.function.BiConsumer<Integer, Integer> placeGhost;
    private java.util.function.BiConsumer<Integer, Integer> savedMoveSelection;
    private boolean boxSelecting, collision, diagnostics, preview;
    private double boxX, boxY, boxEndX, boxEndY, dragStartX, dragStartY;
    private int moveX, moveY;
    private final Map<UUID, int[]> dragOrigins = new HashMap<>();
    private java.util.function.BiConsumer<Integer, Integer> moveSelection;
    private CloudworksDiagnosticCategory diagnosticFilter;
    private int cachedDisplayWidth = -1, cachedDisplayHeight = -1, cachedGuiScale = -1, cachedScaleFactor = 1;

    public void batchMove(java.util.function.BiConsumer<Integer, Integer> handler) { moveSelection = handler; }
    public void ghost(CloudworksBlueprint blueprint, java.util.function.BiConsumer<Integer, Integer> handler) {
        if (ghost == null) savedMoveSelection = moveSelection;
        ghost = blueprint; placeGhost = handler; linking = null; dragging = null;
        panning = boxSelecting = false;
        dragOrigins.clear();
        moveSelection = null;
    }
    public boolean cancelGhost() {
        if (ghost == null) return false;
        ghost = null;
        placeGhost = null;
        moveSelection = savedMoveSelection;
        savedMoveSelection = null;
        return true;
    }
    public boolean hasGhost() { return ghost != null; }
    public boolean interacting() { return dragging != null || linking != null || boxSelecting; }
    public void preview(boolean preview) { this.preview = preview; }
    public void diagnostics(boolean enabled) { diagnostics = enabled; if (!enabled) diagnosticFilter = null; }

    public GuiLineCanvas(IGuiWrapper gui, int x, int y, int width, int height,
            CloudworksViewport viewport, CloudworksLineEditorModel model,
            Supplier<CloudworksLineDraft> configuration, Supplier<CloudworksGuiData.Graph> runtime,
            BooleanSupplier editable, Runnable openProperties, LongSupplier sampleTick) {
        super(gui, x, y, width, height);
        this.viewport = viewport;
        this.model = model;
        this.configuration = configuration;
        this.runtime = runtime;
        this.editable = editable;
        this.openProperties = openProperties;
        this.sampleTick = sampleTick;
        List<String> codecs = new ArrayList<>();
        QIOResourceCodecRegistry.INSTANCE.getCodecs().keySet().forEach(codec -> codecs.add(codec.toString()));
        channelCodecs = Collections.unmodifiableList(codecs);
    }

    public int centerX() { return (int) Math.max(-1_000_000, Math.min(1_000_000, viewport.worldX(width / 2D) - CARD_WIDTH / 2)); }
    public int centerY() { return (int) Math.max(-1_000_000, Math.min(1_000_000, viewport.worldY(height / 2D) - 40)); }
    private double worldX(double x) { return viewport.worldX(x - getX()); }
    private double worldY(double y) { return viewport.worldY(y - getY()); }

    private List<Card> cards() {
        return cards(configuration.get(), true);
    }

    private List<Card> cards(CloudworksLineDraft draft, boolean rememberOrder) {
        List<Card> result = new ArrayList<>();
        if (draft == null) return result;
        for (CloudworksLineDraft.Machine machine : draft.machines) {
            List<Port> ports = new ArrayList<>();
            MachineSlotLayout layout = CloudworksRegistries.slots(machine.type);
            if (layout != null) for (MachineSlotLayout.Slot slot : layout.slots())
                ports.add(new Port(machine.id, slot.id(), slot.codecId().toString(),
                        slot.role() == MachineSlotLayout.PortRole.OUTPUT, false));
            result.add(new Card(machine.id, machine.x, machine.y, machine.type.getDisplayName(),
                    machine.type, machine.count, machine.active, ports));
            result.get(result.size() - 1).waiting = machine.waiting != null;
            result.get(result.size() - 1).waitingGroup = machine.waiting;
            result.get(result.size() - 1).missing = machine.missing;
        }
        for (CloudworksLineDraft.Channel channel : draft.channels) {
            List<String> codecs = channelCodecs;
            for (CloudworksLineDraft.Resource resource : channel.resources) {
                String codec = resource.descriptor.getCodecId().toString();
                if (!channelCodecs.contains(codec)) {
                    codecs = new ArrayList<>(channelCodecs);
                    break;
                }
            }
            if (codecs != channelCodecs) {
                LinkedHashSet<String> unique = new LinkedHashSet<>(codecs);
                for (CloudworksLineDraft.Resource resource : channel.resources)
                    unique.add(resource.descriptor.getCodecId().toString());
                codecs = new ArrayList<>(unique);
            }
            List<Port> ports;
            if (rememberOrder) {
                CachedChannelPorts cached = cachedChannelPorts.get(channel.id);
                if (cached == null || cached.mode != channel.mode || !cached.codecs.equals(codecs)) {
                    cached = new CachedChannelPorts(channel.mode, codecs, channel.id);
                    cachedChannelPorts.put(channel.id, cached);
                }
                ports = cached.ports;
            } else {
                ports = createChannelPorts(channel.id, channel.mode, codecs);
            }
            result.add(new Card(channel.id, channel.x, channel.y, GuiLineWindow.tr("channel_" + channel.mode.name().toLowerCase(Locale.ROOT)),
                    CloudworksChannelIcons.stack(channel.mode), 1, channel.active, ports));
            result.get(result.size() - 1).waiting = channel.waiting != null;
            result.get(result.size() - 1).waitingGroup = channel.waiting;
        }
        if (!rememberOrder) return result;
        Set<UUID> present = new HashSet<>();
        for (Card card : result) present.add(card.id);
        cardOrder.retainAll(present);
        cachedChannelPorts.keySet().retainAll(present);
        Set<UUID> ordered = new HashSet<>(cardOrder);
        for (Card card : result) if (ordered.add(card.id)) cardOrder.add(card.id);
        Map<UUID, Integer> order = new HashMap<>();
        for (int i = 0; i < cardOrder.size(); i++) order.put(cardOrder.get(i), i);
        result.sort(Comparator.comparingInt(card -> order.get(card.id)));
        return result;
    }

    private static List<Port> createChannelPorts(UUID id, CloudworksChannelNode.Mode mode, List<String> codecs) {
        List<Port> ports = new ArrayList<>(codecs.size());
        for (String codec : codecs) ports.add(new Port(id, codec, codec, mode.input(), true, mode));
        return ports;
    }

    private static final class CachedChannelPorts {
        final CloudworksChannelNode.Mode mode;
        final List<String> codecs;
        final List<Port> ports;
        CachedChannelPorts(CloudworksChannelNode.Mode mode, List<String> codecs, UUID node) {
            this.mode = mode;
            this.codecs = new ArrayList<>(codecs);
            ports = createChannelPorts(node, mode, this.codecs);
        }
    }

    public void fit() {
        List<Card> cards = cards();
        if (cards.isEmpty()) {
            viewport.fit(0, 0, CARD_WIDTH, 100, width, height);
            return;
        }
        double minX = Double.MAX_VALUE, minY = minX, maxX = -minX, maxY = -minX;
        for (Card card : cards) {
            minX = Math.min(minX, card.x); minY = Math.min(minY, card.y);
            maxX = Math.max(maxX, card.x + CARD_WIDTH); maxY = Math.max(maxY, card.y + card.height());
        }
        viewport.fit(minX, minY, maxX, maxY, width, height);
    }

    public boolean focusNode(UUID id) {
        for (Card card : cards()) {
            if (!card.id.equals(id)) continue;
            model.selectedNode = id;
            model.selectedWire = null;
            linking = null;
            dragging = null;
            panning = false;
            lastSelected = null;
            cardOrder.remove(id);
            cardOrder.add(id);
            viewport.center(card.x + CARD_WIDTH / 2D, card.y + card.height() / 2D, width, height);
            return true;
        }
        return false;
    }

    @Override
    public void drawBackground(int mouseX, int mouseY, float partialTicks) {
        super.drawBackground(mouseX, mouseY, partialTicks);
        GlStateManager.disableDepth();
        fill(relativeX, relativeY, relativeX + width, relativeY + height, 0xFF24282B);
        List<Card> cards = cards();
        Map<UUID, CloudworksGuiData.Node> states = new HashMap<>();
        for (CloudworksGuiData.Node node : runtime.get().nodes) states.put(node.id, node);
        long sampledAt = sampleTick.getAsLong();
        long worldTick = minecraft.world == null ? -1 : minecraft.world.getTotalWorldTime();
        CloudworksLineDraft draft = configuration.get();
        if (draft == null) {
            MekanismRenderer.resetGuiRenderState();
            return;
        }
        if (cachedDisplayWidth != minecraft.displayWidth || cachedDisplayHeight != minecraft.displayHeight
                || cachedGuiScale != minecraft.gameSettings.guiScale) {
            cachedDisplayWidth = minecraft.displayWidth;
            cachedDisplayHeight = minecraft.displayHeight;
            cachedGuiScale = minecraft.gameSettings.guiScale;
            cachedScaleFactor = new ScaledResolution(minecraft).getScaleFactor();
        }
        int factor = cachedScaleFactor;
        GL11.glPushAttrib(GL11.GL_SCISSOR_BIT);
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(getX() * factor, minecraft.displayHeight - (getY() + height) * factor, width * factor, height * factor);
        GlStateManager.pushMatrix();
        try {
            GlStateManager.translate(relativeX + viewport.x(), relativeY + viewport.y(), 0);
            GlStateManager.scale(viewport.scale(), viewport.scale(), 1);
            int minX = (int) viewport.worldX(0), maxX = (int) viewport.worldX(width);
            int minY = (int) viewport.worldY(0), maxY = (int) viewport.worldY(height);
            for (int x = Math.floorDiv(minX, 24) * 24; x < maxX; x += 24) fill(x, minY, x + 1, maxY, 0xFF303639);
            for (int y = Math.floorDiv(minY, 24) * 24; y < maxY; y += 24) fill(minX, y, maxX, y + 1, 0xFF303639);
            for (CloudworksLineDraft.Wire wire : draft.links) {
                Port from = port(cards, wire.source, wire.sourcePort, true);
                Port to = port(cards, wire.target, wire.targetPort, false);
                if (from != null && to != null) curve(from.x, from.y, to.x, to.y,
                        wire.id.equals(model.selectedWire) ? 0xFFFFCF61 : color(from.codec));
            }
            GlStateManager.depthMask(true);
            GlStateManager.clear(GL11.GL_DEPTH_BUFFER_BIT);
            int iconLayer = 0;
            for (Card card : cards) {
                if (card.x > maxX || card.y > maxY || card.x + CARD_WIDTH < minX || card.y + card.height() < minY) continue;
                int x = card.x, y = card.y, h = card.height();
                // Flat node layers follow painter order. Native item rendering owns depth only within its node.
                GlStateManager.disableDepth();
                GlStateManager.depthMask(false);
                CloudworksGuiData.Node state = states.get(card.id);
                CloudworksDiagnosticCategory category = CloudworksDiagnosticCategory.of(
                        card.waiting ? "WAITING_BLUEPRINT" : state == null ? card.active ? "" : "OFF" : state.state);
                fill(x, y, x + CARD_WIDTH, y + h, collision && model.selection.contains(card.id) ? 0xFFE46B6B
                        : model.selection.contains(card.id) || card.id.equals(model.selectedNode) ? 0xFFFFCF61
                        : diagnostics ? category.color : 0xFF6D8581);
                fill(x + 1, y + 1, x + CARD_WIDTH - 1, y + h - 1, 0xFF353D3C);
                fill(x + 1, y + 1, x + CARD_WIDTH - 1, y + 22, 0xFF465451);
                if (!card.icon.isEmpty()) {
                    GlStateManager.depthMask(true);
                    GlStateManager.pushMatrix();
                    GlStateManager.translate(0, 0, iconLayer++ * 0.01F);
                    gui().renderItem(card.icon, x + 3, y + 3);
                    GlStateManager.popMatrix();
                    GlStateManager.disableDepth();
                    GlStateManager.depthMask(false);
                }
                fill(x + CARD_WIDTH - 15, y + 6, x + CARD_WIDTH - 4, y + 17, 0xFF202726);
                fill(x + CARD_WIDTH - 14, y + 7, x + CARD_WIDTH - 5, y + 16,
                        card.active ? 0xFF76CFA0 : 0xFFE46B6B);
                text(card.name, x + 23, y + 7, CARD_WIDTH - 42, 0xFFFFFF);
                String status = state == null || state.state.isEmpty() ? ""
                        : mekanism.common.util.LangUtils.localize("cloudworks.state." + state.state);
            if (card.waiting) status = card.missing > 0 ? mekanism.common.util.LangUtils.localizeWithFormat(
                        "cloudworks.editor.blueprint_node_missing", card.missing) : GuiLineWindow.tr("blueprint_waiting");
                else if (!card.active) status = mekanism.common.util.LangUtils.localize("cloudworks.state.OFF");
                text((card.waiting ? "" : "x" + card.count + " ") + status, x + 6, y + 28, CARD_WIDTH - 12, 0xD6E1DA);
                for (Port port : card.ports) {
                    boolean compatible = linking == null || compatible(linking, port);
                    fill((int) port.x - 3, (int) port.y - 3, (int) port.x + 4, (int) port.y + 4,
                            compatible ? color(port.codec) : 0xFF666666);
                    String key = "cloudworks.port." + port.id;
                    String label = !port.channel && mekanism.common.util.LangUtils.canLocalize(key)
                            ? mekanism.common.util.LangUtils.localize(key) : resourceLabel(port.codec);
                    text((port.output ? "> " : "< ") + label, x + 9, (int) port.y - 4, CARD_WIDTH - 18, 0xDAE4E0);
                }
                if (!preview) fill(x + 6, y + h - 8, x + CARD_WIDTH - 6, y + h - 4, 0xFF202726);
                if (state != null && !preview) fill(x + 6, y + h - 8,
                        x + 6 + (int) ((CARD_WIDTH - 12) * CloudworksGuiProgress.fraction(
                                state, sampledAt, worldTick, partialTicks)),
                        y + h - 4, 0xFF76CFA0);
                if (diagnostics && diagnosticFilter != null && category != diagnosticFilter)
                    fill(x, y, x + CARD_WIDTH, y + h, 0xB024282B);
            }
            CloudworksBlueprintCanvasGeometry.Bounds waitingBounds = null;
            for (Card card : cards) if (card.waiting)
                waitingBounds = CloudworksBlueprintCanvasGeometry.include(waitingBounds,
                        card.x, card.y, CARD_WIDTH, card.height());
            if (waitingBounds != null)
                hazardVisible(waitingBounds, .35F);
            if (linking != null) curve(linking.x, linking.y, worldX(mouseX), worldY(mouseY), color(linking.codec));
            if (boxSelecting) {
                int x1 = (int) Math.min(boxX, boxEndX), y1 = (int) Math.min(boxY, boxEndY);
                int x2 = (int) Math.max(boxX, boxEndX), y2 = (int) Math.max(boxY, boxEndY);
                fill(x1, y1, x2, y2, 0x4476CFA0);
                fill(x1, y1, x2, y1 + 1, 0xFF76CFA0); fill(x1, y2 - 1, x2, y2, 0xFF76CFA0);
                fill(x1, y1, x1 + 1, y2, 0xFF76CFA0); fill(x2 - 1, y1, x2, y2, 0xFF76CFA0);
            }
            if (ghost != null) drawGhost(mouseX, mouseY);
        } finally {
            GlStateManager.depthMask(true);
            GlStateManager.clear(GL11.GL_DEPTH_BUFFER_BIT);
            GlStateManager.popMatrix();
            GL11.glPopAttrib();
            MekanismRenderer.resetGuiRenderState();
        }
        if (diagnostics) drawDiagnostics(states);
    }

    private void hazardVisible(CloudworksBlueprintCanvasGeometry.Bounds bounds, float alpha) {
        int viewLeft = (int) Math.floor(viewport.worldX(0));
        int viewTop = (int) Math.floor(viewport.worldY(0));
        int viewRight = (int) Math.ceil(viewport.worldX(width));
        int viewBottom = (int) Math.ceil(viewport.worldY(height));
        int left = Math.max(bounds.left, viewLeft), top = Math.max(bounds.top, viewTop);
        int right = Math.min(bounds.right, viewRight), bottom = Math.min(bounds.bottom, viewBottom);
        if (right <= left || bottom <= top) return;
        hazard(left, top, right - left, bottom - top, alpha, left - bounds.left);
    }

    private void hazard(int x, int y, int w, int h, float alpha) {
        hazard(x, y, w, h, alpha, 0);
    }

    private void hazard(int x, int y, int w, int h, float alpha, int sourceOffset) {
        minecraft.getTextureManager().bindTexture(HAZARD_TEXTURE);
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA,
                GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA, GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ZERO);
        GlStateManager.color(1, 1, 1, alpha);
        int phase = (int) ((net.minecraft.client.Minecraft.getSystemTime() % 1500) * 16 / 1500);
        for (int yy = 0; yy < h; yy += 16) for (int xx = 0; xx < w; ) {
            int u = Math.floorMod(xx + sourceOffset - phase, 16);
            int tileW = Math.min(16 - u, w - xx), tileH = Math.min(16, h - yy);
            net.minecraft.client.gui.Gui.drawScaledCustomSizeModalRect(x + xx, y + yy, u, 0,
                    tileW, tileH, tileW, tileH, 16, 16);
            xx += tileW;
        }
        GlStateManager.color(1, 1, 1, 1);
    }

    private int[] ghostAnchor(int mouseX, int mouseY) {
        CloudworksLineDraft group = ghost.draft();
        int minX = CloudworksBlueprint.minimumX(group), minY = CloudworksBlueprint.minimumY(group);
        int maxX = minX, maxY = minY;
        for (CloudworksLineDraft.Machine node : group.machines) {
            maxX = Math.max(maxX, node.x + CARD_WIDTH); maxY = Math.max(maxY, node.y + CloudworksDraftGeometry.height(node));
        }
        for (CloudworksLineDraft.Channel node : group.channels) {
            maxX = Math.max(maxX, node.x + CARD_WIDTH); maxY = Math.max(maxY, node.y + CloudworksDraftGeometry.channelHeight(node));
        }
        return new int[]{CloudworksDraftGeometry.snap(worldX(mouseX) - (maxX - minX) / 2D),
                CloudworksDraftGeometry.snap(worldY(mouseY) - (maxY - minY) / 2D)};
    }

    private void drawGhost(int mouseX, int mouseY) {
        int[] anchor = ghostAnchor(mouseX, mouseY);
        CloudworksLineDraft group = ghost.draft();
        int dx = anchor[0] - CloudworksBlueprint.minimumX(group), dy = anchor[1] - CloudworksBlueprint.minimumY(group);
        group.machines.forEach(node -> { node.x += dx; node.y += dy; });
        group.channels.forEach(node -> { node.x += dx; node.y += dy; });
        List<Card> ghostCards = cards(group, false);
        for (CloudworksLineDraft.Wire wire : group.links) {
            Port a = port(ghostCards, wire.source, wire.sourcePort, true), b = port(ghostCards, wire.target, wire.targetPort, false);
            if (a != null && b != null) curve(a.x, a.y, b.x, b.y, 0xFF8F9D93);
        }
        int minX = (int) viewport.worldX(0), maxX = (int) viewport.worldX(width);
        int minY = (int) viewport.worldY(0), maxY = (int) viewport.worldY(height);
        CloudworksBlueprintCanvasGeometry.Bounds ghostBounds = null;
        GlStateManager.depthMask(true);
        GlStateManager.clear(GL11.GL_DEPTH_BUFFER_BIT);
        int iconLayer = 0;
        for (Card card : ghostCards) {
            int x = card.x, y = card.y, h = card.height();
            ghostBounds = CloudworksBlueprintCanvasGeometry.include(ghostBounds, x, y, CARD_WIDTH, h);
            if (x > maxX || y > maxY || x + CARD_WIDTH < minX || y + h < minY) continue;
            fill(x, y, x + CARD_WIDTH, y + h, 0x4D6D8581);
            if (!card.icon.isEmpty()) {
                GlStateManager.depthMask(true);
                GlStateManager.pushMatrix();
                GlStateManager.translate(0, 0, iconLayer++ * 0.01F);
                gui().renderItem(card.icon, x + 3, y + 3);
                GlStateManager.popMatrix();
                GlStateManager.disableDepth(); GlStateManager.depthMask(false);
                fill(x + 3, y + 3, x + 19, y + 19, 0xB024282B);
            }
            text(card.name, x + 23, y + 7, CARD_WIDTH - 30, 0x4DFFFFFF);
            text("x" + card.count, x + 6, y + 28, CARD_WIDTH - 12, 0x4DFFFFFF);
            for (Port port : card.ports) {
                fill((int) port.x - 3, (int) port.y - 3, (int) port.x + 4, (int) port.y + 4,
                        color(port.codec) & 0xFFFFFF | 0x4D000000);
                String key = "cloudworks.port." + port.id;
                String label = !port.channel && mekanism.common.util.LangUtils.canLocalize(key)
                        ? mekanism.common.util.LangUtils.localize(key) : resourceLabel(port.codec);
                text((port.output ? "> " : "< ") + label, x + 9, (int) port.y - 4, CARD_WIDTH - 18, 0x4DFFFFFF);
            }
        }
        if (ghostBounds != null)
            hazardVisible(ghostBounds, .5F);
    }

    private void drawDiagnostics(Map<UUID, CloudworksGuiData.Node> states) {
        fill(relativeX, relativeY, relativeX + width, relativeY + 19, 0xEE24282B);
        int summaryWidth = Math.max(1, width - 96);
        int segment = Math.max(1, summaryWidth / (CloudworksDiagnosticCategory.values().length + 1));
        text(GuiLineWindow.tr("diagnostic_all"), relativeX + 3, relativeY + 5, segment - 4, 0xFFFFFF);
        for (CloudworksDiagnosticCategory category : CloudworksDiagnosticCategory.values()) {
            long count = states.values().stream().filter(node -> CloudworksDiagnosticCategory.of(node.state) == category).count();
            text(GuiLineWindow.tr("diagnostic_" + category.name().toLowerCase(Locale.ROOT)) + " " + count,
                    relativeX + (category.ordinal() + 1) * segment + 2, relativeY + 5, segment - 4, category.color);
        }
    }

    private void text(String value, int x, int y, int available, int color) {
        drawScrollingString(new TextComponentString(value), x - relativeX, y - relativeY,
                TextAlignment.LEFT, color, available, 0, false);
    }

    public void click(int x, int y, int button) {
        if (button == 0 && ghost != null && editable.getAsBoolean()) {
            int[] anchor = ghostAnchor(x, y);
            java.util.function.BiConsumer<Integer, Integer> handler = placeGhost;
            cancelGhost(); handler.accept(anchor[0], anchor[1]); return;
        }
        if (diagnostics && button == 0 && y < getY() + 19) {
            int index = (x - getX()) / Math.max(1, (width - 96) / (CloudworksDiagnosticCategory.values().length + 1)) - 1;
            diagnosticFilter = index < 0 || index >= CloudworksDiagnosticCategory.values().length
                    ? null : CloudworksDiagnosticCategory.values()[index];
            return;
        }
        if (button == 2 || button == 0 && Keyboard.isKeyDown(Keyboard.KEY_SPACE)) {
            panning = true; lastX = x; lastY = y; return;
        }
        if (button == 1) { linking = null; return; }
        if (button != 0) return;
        List<Card> cards = cards();
        double wx = worldX(x), wy = worldY(y);
        if (editable.getAsBoolean()) {
            Port port = portAt(cards, wx, wy);
            if (port != null) { linking = port; return; }
        }
        for (int i = cards.size() - 1; i >= 0; i--) {
            Card card = cards.get(i);
            if (wx < card.x || wx > card.x + CARD_WIDTH || wy < card.y || wy > card.y + card.height()) continue;
            if (!CloudworksBlueprintCanvasGeometry.interactive(card.waiting)) {
                long now = System.currentTimeMillis();
                boolean doubleClick = card.id.equals(lastSelected) && now - lastClick < 350;
                model.selectedNode = card.id;
                model.selectedWire = null;
                model.selection.clear();
                Map<UUID, UUID> waitingGroups = new HashMap<>();
                for (Card pending : cards) waitingGroups.put(pending.id, pending.waitingGroup);
                model.selection.addAll(CloudworksBlueprintCanvasGeometry.group(card.waitingGroup, waitingGroups));
                dragging = null;
                panning = false;
                linking = null;
                lastSelected = doubleClick ? null : card.id;
                lastClick = now;
                if (doubleClick) openProperties.run();
                else if (editable.getAsBoolean()) beginDrag(card, wx, wy);
                return;
            }
            if (editable.getAsBoolean() && net.minecraft.client.gui.GuiScreen.isCtrlKeyDown()) {
                if (!model.selection.remove(card.id)) model.selection.add(card.id);
                model.selectedNode = model.selection.contains(card.id) ? card.id : null;
                model.selectedWire = null;
                return;
            }
            if (!model.selection.contains(card.id)) { model.selection.clear(); model.selection.add(card.id); }
            model.selectedNode = card.id;
            model.selectedWire = null;
            cardOrder.remove(card.id);
            cardOrder.add(card.id);
            long now = System.currentTimeMillis();
            if (card.id.equals(lastSelected) && now - lastClick < 350) {
                openProperties.run();
                lastSelected = null;
                return;
            }
            lastSelected = card.id; lastClick = now;
            if (editable.getAsBoolean()) {
                beginDrag(card, wx, wy);
            }
            return;
        }
        model.selectedNode = model.selectedWire = null;
        model.selection.clear();
        lastSelected = null;
        if (editable.getAsBoolean() && net.minecraft.client.gui.GuiScreen.isShiftKeyDown()) {
            boxSelecting = true; boxX = boxEndX = wx; boxY = boxEndY = wy; return;
        }
        if (button == 0) { panning = true; lastX = x; lastY = y; }
        CloudworksLineDraft draft = configuration.get();
        if (draft == null) return;
        for (CloudworksLineDraft.Wire wire : draft.links) {
            Port a = port(cards, wire.source, wire.sourcePort, true), b = port(cards, wire.target, wire.targetPort, false);
            if (a == null || b == null) continue;
            for (int i = 0; i <= 64; i++) {
                double[] point = point(a.x, a.y, b.x, b.y, i / 64D);
                if (Math.hypot(wx - point[0], wy - point[1]) < 6 / viewport.scale()) {
                    model.selectedWire = wire.id;
                    panning = false;
                    if (editable.getAsBoolean()) openProperties.run();
                    return;
                }
            }
        }
    }

    private void beginDrag(Card card, double wx, double wy) {
        dragging = card.id; grabX = wx - card.x; grabY = wy - card.y;
        dragStartX = wx; dragStartY = wy; collision = false; moveX = moveY = 0;
        dragOrigins.clear();
        for (CloudworksLineDraft.Machine node : model.draft.machines)
            if (model.selection.contains(node.id)) dragOrigins.put(node.id, new int[]{node.x, node.y});
        for (CloudworksLineDraft.Channel channel : model.draft.channels)
            if (model.selection.contains(channel.id)) dragOrigins.put(channel.id, new int[]{channel.x, channel.y});
    }

    public void drag(int x, int y) {
        if (boxSelecting) {
            boxEndX = worldX(x); boxEndY = worldY(y);
        } else if (panning) {
            viewport.pan(x - lastX, y - lastY);
            lastX = x; lastY = y;
        } else if (dragging != null && editable.getAsBoolean()) {
            restoreDragOrigins();
            moveX = (int) Math.round(worldX(x) - dragStartX);
            moveY = (int) Math.round(worldY(y) - dragStartY);
            collision = !CloudworksDraftGeometry.canMove(model.draft, model.selection, moveX, moveY);
            for (CloudworksLineDraft.Machine machine : model.draft.machines) if (dragOrigins.containsKey(machine.id)) {
                machine.x += moveX; machine.y += moveY;
            }
            for (CloudworksLineDraft.Channel channel : model.draft.channels) if (dragOrigins.containsKey(channel.id)) {
                channel.x += moveX; channel.y += moveY;
            }
        }
    }

    private void restoreDragOrigins() {
        // Observer-mode double clicks open a read-only window and release the canvas
        // without an edit draft or captured drag origins.
        if (model.draft == null || dragOrigins.isEmpty()) return;
        for (CloudworksLineDraft.Machine machine : model.draft.machines) if (dragOrigins.containsKey(machine.id)) {
            int[] origin = dragOrigins.get(machine.id); machine.x = origin[0]; machine.y = origin[1];
        }
        for (CloudworksLineDraft.Channel channel : model.draft.channels) if (dragOrigins.containsKey(channel.id)) {
            int[] origin = dragOrigins.get(channel.id); channel.x = origin[0]; channel.y = origin[1];
        }
    }

    public void release(int x, int y) {
        if (x < 0 || y < 0) {
            restoreDragOrigins();
            dragging = null; linking = null; panning = boxSelecting = collision = false;
            dragOrigins.clear(); return;
        }
        if (boxSelecting) {
            double minX = Math.min(boxX, boxEndX), maxX = Math.max(boxX, boxEndX);
            double minY = Math.min(boxY, boxEndY), maxY = Math.max(boxY, boxEndY);
            for (Card card : cards()) if (CloudworksBlueprintCanvasGeometry.interactive(card.waiting)
                    && card.x >= minX && card.y >= minY
                    && card.x + CARD_WIDTH <= maxX && card.y + card.height() <= maxY) model.selection.add(card.id);
            model.selectedNode = model.selection.stream().findFirst().orElse(null);
            boxSelecting = false;
        }
        if (dragging != null) {
            restoreDragOrigins();
            boolean moved = !collision && (moveX != 0 || moveY != 0);
            int dx = CloudworksDraftGeometry.snap(moveX), dy = CloudworksDraftGeometry.snap(moveY);
            dragging = null; dragOrigins.clear(); collision = false;
            if (moved && (dx != 0 || dy != 0) && moveSelection != null) moveSelection.accept(dx, dy);
        }
        if (linking != null && editable.getAsBoolean() && isMouseOverCheckWindows(x, y)) {
            Port other = portAt(cards(), worldX(x), worldY(y));
            if (other != null && compatible(linking, other)) {
                Port from, to;
                if (linking.channel && other.channel) {
                    from = linking.channelMode == CloudworksChannelNode.Mode.RECEIVE_INPUT
                            || linking.channelMode == CloudworksChannelNode.Mode.TRANSFER_OUTPUT
                            ? linking : other;
                    to = from == linking ? other : linking;
                } else {
                    from = linking.output ? linking : other;
                    to = linking.output ? other : linking;
                }
                boolean duplicate = model.draft.links.stream().anyMatch(w -> w.source.equals(from.node) && w.target.equals(to.node)
                        && w.sourcePort.equals(from.id) && w.targetPort.equals(to.id));
                if (!duplicate) model.addLink(new CloudworksLineDraft.Wire(UUID.randomUUID(), from.node, from.id, to.node, to.id));
            }
        }
        linking = null; dragging = null; panning = false;
    }

    @Override public boolean mouseClicked(double x, double y, int button) {
        if (!visible || !active || !isMouseOverCheckWindows(x, y)) return false;
        click((int) x, (int) y, button); return true;
    }
    @Override public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        if (!panning && !interacting()) return false;
        drag((int) x, (int) y); return true;
    }
    @Override public boolean mouseReleased(double x, double y, int button) {
        if (!panning && !interacting()) return false;
        release((int) x, (int) y); return true;
    }
    @Override public boolean mouseScrolled(double x, double y, double delta) {
        if (!visible || !active || !isMouseOverCheckWindows(x, y)) return false;
        zoom(delta > 0 ? 1 : -1, (int) x, (int) y); return true;
    }

    public void zoom(int wheel, int x, int y) {
        viewport.zoom(wheel > 0 ? 1.15 : 1 / 1.15, x - getX(), y - getY());
    }

    private static boolean compatible(Port a, Port b) {
        if (a.channel && b.channel)
            return a.output == b.output && a.codec.equals(b.codec) && !a.node.equals(b.node);
        return a.output != b.output && a.codec.equals(b.codec) && !a.node.equals(b.node);
    }

    private Port portAt(List<Card> cards, double x, double y) {
        for (int i = cards.size() - 1; i >= 0; i--) {
            Card card = cards.get(i);
            if (!CloudworksBlueprintCanvasGeometry.interactive(card.waiting)) continue;
            for (Port port : card.ports)
                if (Math.abs(x - port.x) < 7 / viewport.scale() && Math.abs(y - port.y) < 7 / viewport.scale()) return port;
            if (x >= card.x && x <= card.x + CARD_WIDTH && y >= card.y && y <= card.y + card.height()) return null;
        }
        return null;
    }

    private static Port port(List<Card> cards, UUID node, String id, boolean output) {
        for (Card card : cards) if (card.id.equals(node)) for (Port port : card.ports)
            if (port.id.equals(id) && (port.output == output || port.channel)) return port;
        return null;
    }

    private static int color(String codec) {
        return codec.equals("cloudworks:heat") ? 0xFFE0664A : codec.contains("item") ? 0xFF76CFA0 : codec.contains("fluid") ? 0xFF6FADDF
                : codec.contains("gas") ? 0xFFD9B476 : 0xFFD38FB5;
    }

    private static String resourceLabel(String id) {
        if (id.equals("cloudworks:heat")) return mekanism.common.util.LangUtils.localize("cloudworks.resource.heat");
        String key = "cloudworks.resource." + id.replace(':', '.');
        if (mekanism.common.util.LangUtils.canLocalize(key)) return mekanism.common.util.LangUtils.localize(key);
        mekanism.api.qio.resource.QIOResourceCodec<?> codec = QIOResourceCodecRegistry.INSTANCE.get(id);
        if (codec != null) {
            key = "cloudworks.resource." + codec.getFamily();
            return mekanism.common.util.LangUtils.canLocalize(key)
                    ? mekanism.common.util.LangUtils.localize(key) : codec.getFamily().replace('_', ' ');
        }
        return id.substring(id.indexOf(':') + 1).replace('_', ' ');
    }

    private static double[] point(double ax, double ay, double bx, double by, double t) {
        double bend = Math.max(30, Math.abs(bx - ax) * .5), u = 1 - t;
        return new double[]{u*u*u*ax + 3*u*u*t*(ax+bend) + 3*u*t*t*(bx-bend) + t*t*t*bx,
                u*u*u*ay + 3*u*u*t*ay + 3*u*t*t*by + t*t*t*by};
    }

    private static void curve(double ax, double ay, double bx, double by, int color) {
        GlStateManager.disableTexture2D(); GlStateManager.disableDepth();
        GlStateManager.color((color >> 16 & 255) / 255F, (color >> 8 & 255) / 255F, (color & 255) / 255F, 1);
        GL11.glLineWidth(2);
        GL11.glBegin(GL11.GL_LINE_STRIP);
        for (int i = 0; i <= 32; i++) {
            double[] point = point(ax, ay, bx, by, i / 32D);
            GL11.glVertex2d(point[0], point[1]);
        }
        GL11.glEnd(); GL11.glLineWidth(1);
        GlStateManager.enableTexture2D(); GlStateManager.color(1, 1, 1, 1);
    }

    private static final class Card {
        final UUID id;
        final int x, y;
        final String name;
        final ItemStack icon;
        final long count;
        final boolean active;
        boolean waiting;
        UUID waitingGroup;
        long missing;
        final List<Port> ports;

        Card(UUID id, int x, int y, String name, ItemStack icon, long count, boolean active, List<Port> ports) {
            this.id = id; this.x = x; this.y = y; this.name = name; this.icon = icon; this.count = count; this.ports = ports;
            this.active = active;
            for (int i = 0; i < ports.size(); i++) {
                Port port = ports.get(i);
                port.x = x + (port.output ? CARD_WIDTH : 0);
                port.y = y + 46 + i * 12;
            }
        }

        int height() { return 57 + Math.max(1, ports.size()) * 12; }
    }

    private static final class Port {
        final UUID node;
        final String id, codec;
        final boolean output, channel;
        final CloudworksChannelNode.Mode channelMode;
        double x, y;

        Port(UUID node, String id, String codec, boolean output, boolean channel) {
            this(node, id, codec, output, channel, null);
        }
        Port(UUID node, String id, String codec, boolean output, boolean channel,
                CloudworksChannelNode.Mode channelMode) {
            this.node = node; this.id = id; this.codec = codec; this.output = output;
            this.channel = channel; this.channelMode = channelMode;
        }
    }
}
