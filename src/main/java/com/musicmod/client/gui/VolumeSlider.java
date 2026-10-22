package com.musicmod.client.gui;

import com.musicmod.client.ClientMusicManager;
import com.musicmod.config.MusicModConfig;
import com.musicmod.i18n.Lang;
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
        setMessage(Component.literal(Lang.tr("音量 ") + pct + "%"));
    }

    @Override
    protected void applyValue() {
        float vol = (float) this.value;
        MusicModConfig.setClientVolume(vol);
        // 实时生效，拖动时即可听到音量变化
        ClientMusicManager.getInstance().getAudioEngine().setVolume(vol);
    }
}