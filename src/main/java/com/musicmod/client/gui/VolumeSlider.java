package com.musicmod.client.gui;

import com.musicmod.client.ClientMusicManager;
import com.musicmod.config.MusicModConfig;
import com.musicmod.i18n.Lang;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * 个人音量滑块（客户端配置 volume 0.0~1.0）。
 * 拖动时实时写入 Forge 配置并应用到音频引擎，界面关闭时统一落盘。
 */
@OnlyIn(Dist.CLIENT)
public class VolumeSlider extends AbstractSliderButton {

    public VolumeSlider(int x, int y, int w, int h, float initialVolume) {
        super(x, y, w, h, Component.empty(),
                Math.max(0.0, Math.min(1.0, initialVolume)));
        updateMessage();
    }

    @Override
    protected void updateMessage() {
        int pct = (int) Math.round(this.value * 100.0);
        // 播放栏宽度有限，使用短文案避免百分比被裁剪
        setMessage(Component.literal(Theme.tr("音量 ") + pct + "%"));
    }

    @Override
    public void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        if (MusicModConfig.getUiStyle() == MusicModConfig.UiStyle.CLASSIC) {
            super.renderWidget(g, mouseX, mouseY, partialTick);
            return;
        }
        boolean light = Theme.isLight();
        boolean hover = isHovered();
        int x = getX(), y = getY(), w = getWidth(), h = getHeight();
        int fill = light ? (hover ? 0x66FFF9EE : 0x4DFFFBF3)
                : (hover ? 0x664A4A68 : 0x3D202034);
        int border = light ? 0xFFC9B79C : 0xFF5A5A78;
        g.fill(x, y, x + w, y + h, fill);
        g.fill(x, y, x + w, y + 1, border);
        g.fill(x, y + h - 1, x + w, y + h, border);
        g.fill(x, y, x + 1, y + h, border);
        g.fill(x + w - 1, y, x + w, y + h, border);

        net.minecraft.client.gui.Font font = ModFonts.get();
        // 一根贯穿整个高度、可左右滑动的竖向音量条（无横向底条）
        int barW = 3;
        int travel = Math.max(1, w - 8 - barW);
        int barX = x + 4 + (int) (travel * this.value);
        int barY = y + 1, barH = h - 2;
        int accent = light ? 0xFFB4551E : 0xFFB9A7D8;
        g.fill(barX, barY, barX + barW, barY + barH, accent);
        g.fill(barX, barY, barX + 1, barY + barH, light ? 0x80FFFFFF : 0x30FFFFFF);
        // 文字绘制在竖条之上，保持可读
        UiText.centered(g, font, getMessage(), x + w / 2,
                y + (h - font.lineHeight) / 2 + 1,
                light ? 0xFF2B2722 : 0xFFF0F0F0);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (mouseX >= getX() && mouseX < getX() + getWidth()
                && mouseY >= getY() && mouseY < getY() + getHeight()) {
            this.value = Math.max(0.0, Math.min(1.0, this.value + Math.signum(delta) * 0.05));
            applyValue();
            updateMessage();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    protected void applyValue() {
        float vol = (float) this.value;
        MusicModConfig.setClientVolume(vol);
        // 实时生效，拖动时即可听到音量变化
        ClientMusicManager.getInstance().getAudioEngine().setVolume(vol);
    }
}