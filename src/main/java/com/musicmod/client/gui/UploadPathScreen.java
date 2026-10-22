package com.musicmod.client.gui;

import com.musicmod.i18n.Lang;

import com.musicmod.client.ClientFileUploader;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.lwjgl.glfw.GLFW;

import java.io.File;

/**
 * 无头环境下的上传回退界面。
 * 当系统无法创建 AWT 文件选择器时，改为在游戏内输入本地音乐文件的完整路径。
 */
@OnlyIn(Dist.CLIENT)
public class UploadPathScreen extends Screen {

    private final Screen parent;
    private EditBox pathBox;
    private Button uploadBtn;
    private String error = "";

    public UploadPathScreen(Screen parent) {
        super(Component.literal(Lang.tr("一起听 · 上传音乐")));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();
        int cx = this.width / 2;
        int cy = this.height / 2;

        pathBox = new EditBox(this.font, cx - 150, cy - 12, 300, 20,
                Component.literal(Lang.tr("音乐文件路径")));
        pathBox.setMaxLength(1024);
        pathBox.setHint(Component.literal(Lang.tr("§7例如 C:\\Music\\song.mp3")));
        this.addRenderableWidget(pathBox);

        uploadBtn = Button.builder(Component.literal(Lang.tr("上传")), b -> upload())
                .bounds(cx - 104, cy + 24, 100, 20).build();
        this.addRenderableWidget(uploadBtn);
        this.addRenderableWidget(Button.builder(Component.literal(Lang.tr("取消")), b -> onClose())
                .bounds(cx + 4, cy + 24, 100, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal(Lang.tr("§b📂 打开音乐文件夹")), b -> openUploadFolder())
                .bounds(cx - 100, cy + 52, 200, 20).build());

        this.setFocused(pathBox);
        pathBox.setFocused(true);
    }

    private void upload() {
        String raw = pathBox == null ? "" : pathBox.getValue().trim();
        if (raw.length() >= 2 && raw.startsWith("\"") && raw.endsWith("\"")) {
            raw = raw.substring(1, raw.length() - 1).trim();
        }
        if (raw.isEmpty()) {
            error = Lang.tr("请输入音乐文件的完整路径");
            return;
        }
        File file = new File(raw);
        if (!file.isFile()) {
            error = Lang.tr("文件不存在: ") + raw;
            return;
        }
        ClientFileUploader.uploadLocalFile(file);
        onClose();
    }

    private void openUploadFolder() {
        File dir = new File(Minecraft.getInstance().gameDirectory, "musicmod_uploads");
        if (!dir.exists()) dir.mkdirs();
        try {
            Util.getPlatform().openFile(dir);
        } catch (Throwable ignored) {
            error = Lang.tr("无法自动打开文件夹，请手动访问: ") + dir.getAbsolutePath();
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (pathBox != null) pathBox.tick();
        if (uploadBtn != null) {
            uploadBtn.active = pathBox != null && !pathBox.getValue().trim().isEmpty();
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        int cx = this.width / 2;
        int cy = this.height / 2;

        // 居中的小面板
        g.fill(cx - 172, cy - 62, cx + 172, cy + 92, 0xFF4A4A60);
        g.fill(cx - 171, cy - 61, cx + 171, cy + 91, 0xF0161626);

        g.drawCenteredString(this.font, Lang.tr("§e§l一起听 · 上传音乐"), cx, cy - 52, 0xFFFFFF);
        g.drawCenteredString(this.font, Lang.tr("§7系统文件选择器不可用，请粘贴本地音乐文件完整路径："),
                cx, cy - 36, 0xAAAAAA);
        g.drawCenteredString(this.font, Lang.tr("§8支持 MP3 / OGG / FLAC / WAV / M4A 等格式"),
                cx, cy - 24, 0x888888);
        if (!error.isEmpty()) {
            g.drawCenteredString(this.font, "§c" + error, cx, cy + 12, 0xFF5555);
        }

        super.render(g, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            onClose();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            upload();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void onClose() {
        if (parent != null) {
            Minecraft.getInstance().setScreen(parent);
        } else {
            super.onClose();
        }
    }
}
