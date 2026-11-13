package com.musicmod.client.tags;

import com.musicmod.client.gui.CardButton;
import com.musicmod.client.gui.Theme;
import com.musicmod.i18n.Lang;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** 封面来源选择：本地图片 / 联网搜索。 */
public class TagCoverChoiceScreen extends Screen {
    private static final int W = 260;
    private static final int H = 112;
    private final TagEditorScreen parent;

    public TagCoverChoiceScreen(TagEditorScreen parent) {
        super(Component.literal(Lang.tr("更改封面")));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int x = (this.width - W) / 2;
        int y = (this.height - H) / 2;
        addRenderableWidget(new CardButton(x + 16, y + 46, 108, 24,
                Component.literal(Lang.tr("本地图片")), b -> {
                    net.minecraft.client.Minecraft.getInstance().setScreen(parent);
                    parent.chooseLocalCover();
                }));
        addRenderableWidget(new CardButton(x + 136, y + 46, 108, 24,
                Component.literal(Lang.tr("搜索封面")), b -> {
                    net.minecraft.client.Minecraft.getInstance().setScreen(parent);
                    parent.openCoverSearch();
                }));
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        renderBackground(g);
        int x = (this.width - W) / 2;
        int y = (this.height - H) / 2;
        g.fill(x - 1, y - 1, x + W + 1, y + H + 1, Theme.c(0xFF4A4A60));
        g.fill(x, y, x + W, y + H, Theme.listBg());
        g.drawCenteredString(font, this.title, this.width / 2, y + 14, Theme.c(0xFFFFFF));
        g.drawCenteredString(font, Lang.tr("选择封面来源"), this.width / 2, y + 32, Theme.c(0xAAAAAA));
        super.render(g, mouseX, mouseY, partial);
    }

    @Override
    public void onClose() {
        net.minecraft.client.Minecraft.getInstance().setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
