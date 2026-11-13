package com.musicmod.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import java.nio.ByteBuffer;
import com.musicmod.MusicMod;

/**
 * Off-screen lyric mask + downsample glow.
 *
 * <p>This deliberately never touches {@link GuiGraphics#bufferSource()} or
 * {@link GuiGraphics#flush()}. The vanilla HUD buffer may already contain the
 * whole HUD at this point; flushing it while our mask is bound would copy the
 * HUD into the mask and remove it from the real target.
 */
public final class LyricsGlow {

    private static final int LEVELS = 7;
    private static final RenderTarget[] TARGETS = new RenderTarget[LEVELS];
    private static RenderTarget mask;
    private static int width = -1;
    private static int height = -1;

    private static int savedFbo;
    private static final int[] savedViewport = new int[4];
    private static boolean debugLogged;

    private LyricsGlow() {}

    public static void captureTarget() {
        savedFbo = GL30.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, savedViewport);
    }

    public static void prepare(int guiW, int guiH) {
        if (guiW == width && guiH == height) return;
        width = guiW;
        height = guiH;
        if (mask != null) {
            mask.destroyBuffers();
            mask = null;
        }
        for (int i = 0; i < LEVELS; i++) {
            if (TARGETS[i] != null) {
                TARGETS[i].destroyBuffers();
                TARGETS[i] = null;
            }
        }
        mask = new TextureTarget(guiW, guiH, true, false);
        mask.setClearColor(0.0F, 0.0F, 0.0F, 0.0F);
        mask.setFilterMode(GL11.GL_LINEAR);
        for (int i = 0; i < LEVELS; i++) {
            int div = 2 << i;
            int w = Math.max(1, guiW / div);
            int h = Math.max(1, guiH / div);
            RenderTarget target = new TextureTarget(w, h, false, false);
            target.setClearColor(0.0F, 0.0F, 0.0F, 0.0F);
            target.setFilterMode(GL11.GL_LINEAR);
            TARGETS[i] = target;
        }
    }

    /** Bind and clear the mask without calling RenderTarget.clear (it unbinds). */
    public static void beginMask() {
        if (mask == null) return;
        mask.bindWrite(false);
        RenderSystem.viewport(0, 0, mask.width, mask.height);
        RenderSystem.clearColor(0.0F, 0.0F, 0.0F, 0.0F);
        RenderSystem.clear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT, false);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
    }

    /** Flush only the mask's own text batches, still while the mask is bound. */
    public static void endMask() {
        if (mask != null) {
            mask.bindWrite(false);
            RenderSystem.viewport(0, 0, mask.width, mask.height);
        }
    }

    public static void blurAndComposite(GuiGraphics gg) {
        if (!debugLogged && mask != null) {
            debugLogged = true;
            mask.bindWrite(false);
            ByteBuffer buf = ByteBuffer.allocateDirect(mask.width * mask.height * 4);
            GL11.glReadPixels(0, 0, mask.width, mask.height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buf);
            int max = 0;
            for (int i = 3; i < buf.capacity(); i += 4) max = Math.max(max, buf.get(i) & 255);
            MusicMod.LOGGER.info("World lyrics mask {}x{} max alpha {}", mask.width, mask.height, max);
        }
        for (int level = 0; level < LEVELS; level++) {
            downsample(level);
        }

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, savedFbo);
        GL11.glViewport(savedViewport[0], savedViewport[1], savedViewport[2], savedViewport[3]);

        RenderSystem.enableBlend();
        RenderSystem.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        RenderSystem.colorMask(true, true, true, true);
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);

        for (int i = LEVELS - 1; i >= 0; i--) {
            RenderTarget target = TARGETS[i];
            if (target == null) continue;
            float alpha = 0.50F / (float) (1 << i);
            RenderSystem.setShaderTexture(0, target.getColorTextureId());
            drawQuad(gg.pose().last().pose(), width, height, alpha);
        }

        RenderSystem.defaultBlendFunc();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
    }

    private static void downsample(int level) {
        RenderTarget src = level == 0 ? mask : TARGETS[level - 1];
        RenderTarget dst = TARGETS[level];
        if (src == null || dst == null) return;

        dst.bindWrite(false);
        RenderSystem.viewport(0, 0, dst.width, dst.height);
        RenderSystem.clearColor(0.0F, 0.0F, 0.0F, 0.0F);
        RenderSystem.clear(GL11.GL_COLOR_BUFFER_BIT, false);
        RenderSystem.disableBlend();
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderTexture(0, src.getColorTextureId());
        drawQuad(new Matrix4f(), dst.width, dst.height, 1.0F);
        RenderSystem.enableBlend();
    }

    private static void drawQuad(Matrix4f matrix, int guiW, int guiH, float alpha) {
        Tesselator tess = Tesselator.getInstance();
        BufferBuilder buffer = tess.getBuilder();
        buffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        int a = (int) (alpha * 255.0F);
        buffer.vertex(matrix, 0.0F, 0.0F, 0.0F).uv(0.0F, 1.0F).color(255, 255, 255, a).endVertex();
        buffer.vertex(matrix, guiW, 0.0F, 0.0F).uv(1.0F, 1.0F).color(255, 255, 255, a).endVertex();
        buffer.vertex(matrix, guiW, guiH, 0.0F).uv(1.0F, 0.0F).color(255, 255, 255, a).endVertex();
        buffer.vertex(matrix, 0.0F, guiH, 0.0F).uv(0.0F, 0.0F).color(255, 255, 255, a).endVertex();
        BufferUploader.drawWithShader(buffer.end());
    }
}
