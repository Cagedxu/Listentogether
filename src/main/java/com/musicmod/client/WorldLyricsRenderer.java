package com.musicmod.client;

import com.musicmod.api.RomajiSupport;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.math.Axis;
import com.musicmod.MusicMod;
import com.musicmod.client.gui.LyricsParser;
import com.musicmod.config.MusicModConfig;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 世界�?3D 歌词�?
 *
 * <p>歌词在世界里有一个真实坐标，始终朝向镜头；渲染时把它投影到屏幕，
 * �?HUD 阶段绘制。这样既保留距离、缩放和视差，又不会被光影包�?
 * 延迟渲染/后处理吞掉，兼容性比直接在世界阶段画文字好得多�?/p>
 *
 * <p>效果参�?DangoMusic（GPL-3.0）的 LyricsModule。本项目�?GPL-3.0
 * 分发，详�?LICENSE �?README�?/p>
 */
@Mod.EventBusSubscriber(modid = MusicMod.MOD_ID, value = Dist.CLIENT)
public final class WorldLyricsRenderer {

    /** 歌词距离（格�?*/
    private static final double DISTANCE = 10.0D;
    /** 左右随机散布角度 */
    private static final float SPREAD = 30.0F;
    /** 文字大小基准 */
    private static final float BASE_SCALE = 0.045F;
    /** 歌词行高（字体像素） */
    private static final float LINE_HEIGHT = 11.0F;
    /** 原文换行宽度（字体像素） */
    private static final int WRAP_WIDTH = 200;
    /** 翻译换行宽度 */
    private static final int TRANSLATION_WRAP = 270;
    /** 翻译缩放 */
    private static final float TRANSLATION_SCALE = 0.72F;
    /** 旧句淡出时长（秒�?*/
    private static final float FADE_SECONDS = 0.18F;
    /** 手绘抖动的帧�?*/
    private static final int WIGGLE_FPS = 8;
    /** 屏幕边缘留白（GUI 像素�?*/
    private static final double EDGE_MARGIN_X = 10.0D;
    private static final double EDGE_MARGIN_TOP = 8.0D;
    private static final double EDGE_MARGIN_BOTTOM = 34.0D;
    /** 原文颜色：DangoMusic 默认暖白 0xF7E1D7 */
    private static final int TEXT_RGB = 0xFFF7E1D7;
    /** 翻译颜色：低饱和的暖�?*/
    private static final int TRANSLATION_RGB = 0xFFE5B4C2;
    /** 光晕颜色：与原文同色扩散，不额外染橙 */
    private static final int GLOW_RGB = 0xFFF7E1D7;

    private static final Random RANDOM = new Random();

    private static final class Floating {
        final List<String> lines;
        List<String> translation;
        final String sourceText;
        final String sourceTranslation;
        final long startMs;
        final long revealMs;
        final long[] charTimes;
        double x, y, z;
        float yaw, pitch;
        long fadeStartNs;
        boolean fading;
        long resumedAtNs;
        double screenX, screenY;
        boolean hasScreen;

        Floating(List<String> lines, List<String> translation, String sourceText, String sourceTranslation,
                 long startMs, long revealMs, long[] charTimes, double x, double y, double z,
                 float yaw, float pitch) {
            this.lines = lines;
            this.translation = translation;
            this.sourceText = sourceText;
            this.sourceTranslation = sourceTranslation;
            this.startMs = startMs;
            this.revealMs = revealMs;
            this.charTimes = charTimes;
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
            this.pitch = pitch;
        }
    }

    /** 投影结果；behind 表示歌词在镜头背面�?*/
    private static final class Projection {
        double x, y;
        double cx, cz;
        boolean behind;
    }

    private static String currentSong = "";
    private static String currentLyrics = null;
    private static List<LyricsParser.Line> parsed = List.of();
    private static Floating active;
    private static final List<Floating> fading = new ArrayList<>();
    private static int lastIndex = -1;
    private static long lastPositionMs = -1L;
    private static boolean wasPaused;
    private static MusicModConfig.LyricsDisplay lastDisplayMode = null;

    private WorldLyricsRenderer() {}

    /** 断开连接 / 切换世界时清空，避免旧歌词残留在新世界�?*/
    public static void reset() {
        currentSong = "";
        currentLyrics = null;
        parsed = List.of();
        active = null;
        fading.clear();
        lastIndex = -1;
        lastPositionMs = -1L;
        wasPaused = false;
    }

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        if (!MusicModConfig.isWorldLyricsEnabled()) {
            active = null;
            fading.clear();
            lastIndex = -1;
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.options.hideGui) {
            return;
        }

        ClientMusicManager manager = ClientMusicManager.getInstance();
        String song = manager.getCurrentSong();
        if (song == null || song.isEmpty() || !manager.isPlaying()) {
            fadeActive();
            return;
        }

        long now = System.nanoTime();
        ClientTagStore tags = ClientTagStore.getInstance();
        tags.requestSongInfo(song);
        ClientTagStore.SongInfo info = tags.getSongInfo(song);
        if (info == null || info.lyrics == null || info.lyrics.isEmpty()) {
            fadeActive();
            return;
        }

        if (!song.equals(currentSong) || !info.lyrics.equals(currentLyrics)) {
            currentSong = song;
            currentLyrics = info.lyrics;
            parsed = LyricsParser.parse(info.lyrics);
            active = null;
            fading.clear();
            lastIndex = -1;
            lastPositionMs = -1L;
        }
        MusicModConfig.LyricsDisplay displayMode = MusicModConfig.getLyricsDisplay();
        if (displayMode != lastDisplayMode) {
            lastDisplayMode = displayMode;
            refreshSecondary(active);
        }

        if (parsed.isEmpty() || !LyricsParser.isTimed(parsed)) {
            fadeActive();
            return;
        }

        long pos = manager.getAudioEngine().getCurrentPositionMs();
        // 拖动进度条会瞬间跳很远：重新找当前句，不要补播中间所有行
        if (lastPositionMs >= 0 && Math.abs(pos - lastPositionMs) > 1500L) {
            lastIndex = -1;
            fadeActive();
        }
        lastPositionMs = pos;

        int idx = LyricsParser.findActiveLine(parsed, pos);
        boolean paused = manager.isPaused();
        if (paused) {
            fadeActive();
            lastIndex = idx;
            wasPaused = true;
            return;
        }

        if (idx != lastIndex || wasPaused) {
            boolean resumed = wasPaused && idx == lastIndex;
            wasPaused = false;
            lastIndex = idx;
            fadeActive();
            if (idx >= 0) {
                LyricsParser.Line line = parsed.get(idx);
                if (!line.text.isBlank()) {
                    long next = idx + 1 < parsed.size()
                            ? parsed.get(idx + 1).timeMs : line.timeMs + 5000L;
                    active = spawn(line, Math.max(next, line.timeMs + 400L), event.getPartialTick());
                    if (resumed && active != null) active.resumedAtNs = now;
                }
            }
        }

        if (active == null && fading.isEmpty()) return;

        GuiGraphics gg = event.getGuiGraphics();
        // 光影�?其他 HUD 可能�?Post 阶段留下非白色的 shader color�?
        // 那会把整段文字（包括发光和描边）一起乘成黑色�?
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        int guiW = gg.guiWidth();
        int guiH = gg.guiHeight();
        float partial = event.getPartialTick();
        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 cam = camera.getPosition();

        double fov = Math.toRadians(mc.options.fov().get());
        double tanV = Math.tan(fov * 0.5D);
        double aspect = (double) mc.getWindow().getWidth() / Math.max(1, mc.getWindow().getHeight());
        double tanH = tanV * aspect;

        gg.pose().pushPose();
        long songNow = pos;
        pruneFading(now);
        if (active == null && fading.isEmpty()) {
            gg.pose().popPose();
            return;
        }

        // 光晕改为在字形层用少量放大副本绘制，避免离屏 FBO 破坏 HUD/光影状态。
        drawAll(gg, camera, cam, now, songNow, guiW, guiH, tanH, tanV);
        gg.pose().popPose();
        gg.flush();
    }

    /** 只在光晕画布上画字的轮廓，不画阴影和任何多层副本。*/
    private static boolean maskPass;
    private static boolean fadingPass;
    private static MultiBufferSource.BufferSource maskBuffer;

    private static MultiBufferSource.BufferSource maskBuffer() {
        if (maskBuffer == null) {
            // Must not use Tesselator.getInstance(): GuiGraphics owns that builder,
            // and flushing it while the mask is bound would flush the whole HUD into the mask.
            maskBuffer = MultiBufferSource.immediate(new BufferBuilder(1536));
        }
        return maskBuffer;
    }

    private static void pruneFading(long now) {
        for (int i = fading.size() - 1; i >= 0; i--) {
            Floating f = fading.get(i);
            if ((now - f.fadeStartNs) / 1_000_000_000.0F >= FADE_SECONDS) {
                fading.remove(i);
            }
        }
    }

    private static void drawAll(GuiGraphics gg, Camera camera, Vec3 cam, long now, long songNow,
                                int guiW, int guiH, double tanH, double tanV) {
        for (int i = fading.size() - 1; i >= 0; i--) {
            Floating f = fading.get(i);
            float t = (now - f.fadeStartNs) / 1_000_000_000.0F;
            if (t >= FADE_SECONDS) continue;
            fadingPass = true;
            drawFloating(gg, f, camera, cam, 1.0F - t / FADE_SECONDS, 0.0F, songNow,
                    guiW, guiH, tanH, tanV, true);
            fadingPass = false;
        }
        if (active != null) {
            float appear = active.resumedAtNs == 0L ? 1.0F
                    : Math.min(1.0F, (now - active.resumedAtNs) / 300_000_000.0F);
            drawFloating(gg, active, camera, cam, appear, 0.0F, songNow,
                    guiW, guiH, tanH, tanV, false);
        }
    }

    private static void fadeActive() {
        if (active != null) {
            active.fading = true;
            active.fadeStartNs = System.nanoTime();
            fading.add(active);
            active = null;
        }
    }

    private static void refreshSecondary(Floating f) {
        if (f == null) return;
        String secondary = LyricsDisplayHelper.secondary(f.sourceText, f.sourceTranslation);
        f.translation = secondary == null ? List.of() : wrap(secondary, TRANSLATION_WRAP);
    }

    private static Floating spawn(LyricsParser.Line line, long nextMs, float partial) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return null;

        List<String> lines = wrap(line.text, WRAP_WIDTH);
        if (lines.isEmpty()) return null;

        List<String> translation = List.of();
        String secondary = LyricsDisplayHelper.secondary(line.text, line.translation);
        if (secondary != null) {
            translation = wrap(secondary, TRANSLATION_WRAP);
        }

        int chars = 0;
        for (String s : lines) chars += s.length();
        long lineDur = Math.max(400L, nextMs - line.timeMs);
        long reveal = Math.min(Math.max(250L, chars * 55L), (long) (lineDur * 0.6D));
        long[] charTimes = buildCharTimes(line.text, lineDur);

        Camera camera = mc.gameRenderer.getMainCamera();
        float yaw = camera.getYRot() + (RANDOM.nextFloat() * 2.0F - 1.0F) * SPREAD;
        float pitch = camera.getXRot() - 3.0F - RANDOM.nextFloat() * 10.0F;
        pitch = Math.max(-60.0F, Math.min(30.0F, pitch));

        double yr = Math.toRadians(yaw);
        double pr = Math.toRadians(pitch);
        double dx = -Math.sin(yr) * Math.cos(pr);
        double dy = -Math.sin(pr);
        double dz = Math.cos(yr) * Math.cos(pr);

        Vec3 eye = mc.player.getEyePosition(partial);
        return new Floating(lines, translation, line.text, line.translation,
                line.timeMs, reveal, charTimes,
                eye.x + dx * DISTANCE, eye.y + dy * DISTANCE, eye.z + dz * DISTANCE,
                yaw, pitch);
    }

    private static Projection project(Floating f, Camera camera, Vec3 cam,
                                      int guiW, int guiH, double tanH, double tanV) {
        double rx = f.x - cam.x;
        double ry = f.y - cam.y;
        double rz = f.z - cam.z;

        double yaw = Math.toRadians(camera.getYRot());
        double pitch = Math.toRadians(camera.getXRot());

        // 相机基向量：forward 为视线方向，right 为屏幕右方，up 为屏幕上方向
        double fx = -Math.sin(yaw) * Math.cos(pitch);
        double fy = -Math.sin(pitch);
        double fz = Math.cos(yaw) * Math.cos(pitch);
        double rxAxis = -Math.cos(yaw);
        double rzAxis = -Math.sin(yaw);
        // up = right x forward，跟着俯仰角一起转，抬�?低头时投影才不会�?
        double ux = -Math.sin(yaw) * Math.sin(pitch);
        double uy = Math.cos(pitch);
        double uz = Math.cos(yaw) * Math.sin(pitch);

        Projection p = new Projection();
        p.cx = rx * rxAxis + rz * rzAxis;
        double cy = rx * ux + ry * uy + rz * uz;
        p.cz = rx * fx + ry * fy + rz * fz;
        if (p.cz <= 0.05D) {
            p.behind = true;
            return p;
        }

        p.x = guiW * 0.5D + (p.cx / p.cz) / tanH * (guiW * 0.5D);
        p.y = guiH * 0.5D - (cy / p.cz) / tanV * (guiH * 0.5D);
        return p;
    }

    private static double guiScale(double cz, double tanV, int guiH) {
        double worldTextHeight = 9.0D * BASE_SCALE * (DISTANCE / 6.5D);
        double scale = worldTextHeight / (2.0D * Math.max(1.0D, cz) * tanV) * guiH / 9.0D;
        return Math.max(0.75D, Math.min(5.0D, scale));
    }

    private static double halfWidth(Floating f) {
        Font font = Minecraft.getInstance().font;
        double max = 0.0D;
        for (String line : f.lines) max = Math.max(max, font.width(line));
        for (String line : f.translation) max = Math.max(max, font.width(line) * TRANSLATION_SCALE);
        return max * 0.5D + 7.0D;
    }

    private static double topExtent(Floating f) {
        return f.lines.size() * LINE_HEIGHT * 0.5D + 8.0D;
    }

    private static double bottomExtent(Floating f) {
        double bottom = f.lines.size() * LINE_HEIGHT * 0.5D;
        if (!f.translation.isEmpty()) {
            bottom += 2.5D + f.translation.size() * LINE_HEIGHT * TRANSLATION_SCALE;
        }
        return bottom + 8.0D;
    }

    private static void drawFloating(GuiGraphics gg, Floating f, Camera camera, Vec3 cam,
                                     float alphaMul, float lift, long songNow,
                                     int guiW, int guiH, double tanH, double tanV,
                                     boolean fadingOut) {
        Projection p = project(f, camera, cam, guiW, guiH, tanH, tanV);
        boolean follow = f == active && MusicModConfig.isWorldLyricsFollowEnabled();
        double scale = guiScale(p.behind ? DISTANCE : p.cz, tanV, guiH);
        // 跟随开启时固定�?10 格距离取屏幕字号；走近不再额外放大，
        // 否则字会大得离谱，光晕也会被一起撑糊�?
        if (follow) {
            scale = guiScale(DISTANCE, tanV, guiH);
        }
        double hw = halfWidth(f) * scale + 2.0D;
        double topH = topExtent(f) * scale + 2.0D;
        double bottomH = bottomExtent(f) * scale + 2.0D;

        double targetX;
        double targetY;
        if (follow) {
            // 跟随只做“别跑出屏幕”：还在屏幕里就用真实投影位置；
            // 快出屏时停在当前屏幕位置，不往�?右边缘中心拽，也不强制缩放�?
            double minX = EDGE_MARGIN_X + hw;
            double maxX = guiW - EDGE_MARGIN_X - hw;
            double minY = EDGE_MARGIN_TOP + topH;
            double maxY = guiH - EDGE_MARGIN_BOTTOM - bottomH;
            boolean outside = p.behind
                    || p.x < minX || p.x > maxX
                    || p.y < minY || p.y > maxY;
            if (outside) {
                if (!f.hasScreen) return;
                targetX = f.screenX;
                targetY = f.screenY;
            } else {
                targetX = p.x;
                targetY = p.y;
            }
        } else {
            if (p.behind) return;
            targetX = p.x;
            targetY = p.y;
        }

        if (!f.hasScreen) {
            f.screenX = targetX;
            f.screenY = targetY;
            f.hasScreen = true;
        } else {
            double speed = Math.abs(targetX - f.screenX) > guiW * 0.45D
                    || Math.abs(targetY - f.screenY) > guiH * 0.45D ? 0.55D : 0.25D;
            f.screenX += (targetX - f.screenX) * speed;
            f.screenY += (targetY - f.screenY) * speed;
        }

        gg.pose().pushPose();
        // 对齐到整数屏幕像素，避免 5 倍放大后字边被线性过滤糊�?
        gg.pose().translate(Math.round(f.screenX), Math.round(f.screenY - lift * 8.0D), 0.0D);
        gg.pose().scale((float) scale, (float) scale, 1.0F);

        long elapsed = songNow - f.startMs;
        // 消失中的旧句只做干净的整句淡出：不再抖动，也不再画多层发光副本�?
        // 抖动 + 28 层偏移光晕在 alpha 降低时会散成一团，看起来像“炸开”�?
        boolean decorate = true;
        drawAnimated(gg, f.lines, elapsed, f.revealMs, alphaMul, f.charTimes, 0.0F, decorate, true, true, true);
        if (!f.translation.isEmpty()) {
            drawTranslation(gg, f.translation, elapsed, f.revealMs, alphaMul,
                    f.lines.size() * LINE_HEIGHT / 2.0F + 2.5F, decorate);
        }
        gg.pose().popPose();
    }

    private static void drawAnimated(GuiGraphics gg, List<String> lines, long elapsed, long revealMs,
                                     float alphaMul, long[] charTimes, float yOffset,
                                     boolean decorate, boolean perCharReveal,
                                     boolean allowPop, boolean drawShadow) {
        Font font = Minecraft.getInstance().font;
        boolean wordPop = charTimes != null && charTimes.length > 0;
        int total = Math.max(1, lines.stream().mapToInt(String::length).sum());
        int charIndex = 0;
        int nonSpace = 0;
        float y = -(lines.size() * LINE_HEIGHT) / 2.0F + yOffset;
        long frame = decorate ? System.currentTimeMillis() * WIGGLE_FPS / 1000L : 0L;

        for (String line : lines) {
            float lineW = font.width(line);
            float x = -lineW / 2.0F;
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                float cw = font.width(String.valueOf(c));
                if (c != ' ') {
                    float alpha;
                    float lift = 0.0F;
                    float pop = 1.0F;
                    if (wordPop) {
                        int ci = Math.min(nonSpace, charTimes.length - 1);
                        long at = charTimes[ci];
                        float t = (elapsed - at) / 160.0F;
                        if (perCharReveal && t < 0.0F) {
                            alpha = 0.0F;
                        } else {
                            alpha = perCharReveal ? Math.min(1.0F, t * 3.0F) * alphaMul : alphaMul;
                            if (allowPop) {
                                float back = Math.max(0.0F, 1.0F - t);
                                pop = 1.0F + 0.22F * back * back;
                            }
                        }
                    } else {
                        float appearAt = (float) charIndex / total * revealMs;
                        float a = Math.max(0.0F, Math.min(1.0F, (elapsed - appearAt) / 180.0F));
                        a = a * a * (3.0F - 2.0F * a);
                        alpha = a * alphaMul;
                        lift = (1.0F - a) * 5.0F;
                    }
                    if (alpha > 0.02F) {
                        drawGlyph(gg, font, String.valueOf(c), x, y + lift,
                                TEXT_RGB, alpha, nonSpace, frame, pop, decorate, drawShadow);
                    }
                    nonSpace++;
                }
                x += cw;
                charIndex++;
            }
            y += LINE_HEIGHT;
        }
    }

    private static void drawTranslation(GuiGraphics gg, List<String> lines, long elapsed,
                                        long revealMs, float alphaMul, float top,
                                        boolean decorate) {
        float a = Math.max(0.0F, Math.min(1.0F, (elapsed - 120L) / 260.0F));
        a = a * a * (3.0F - 2.0F * a);
        float alpha = a * alphaMul;
        if (alpha <= 0.02F) return;

        // 翻译不用逐字揭示，但保留和原文一样的词块弹出、轻微抖动、发光与阴影。
        // revealMs=0 + charTimes=null 会让 drawAnimated 一次性显示整行。
        gg.pose().pushPose();
        gg.pose().translate(0.0F, top, 0.0F);
        gg.pose().scale(TRANSLATION_SCALE, TRANSLATION_SCALE, 1.0F);
        drawAnimated(gg, lines, elapsed, revealMs, alpha, null, 0.0F, decorate, true, false, true);
        gg.pose().popPose();
    }

    private static void drawGlyph(GuiGraphics gg, Font font, String glyph, float x, float y,
                                  int rgb, float alpha, int index, long frame, float pop,
                                  boolean decorate, boolean drawShadow) {
        // 手绘抖动：恢复原版的逐字旋转/位移/缩放
        float rot = 0.0F;
        float dx = 0.0F;
        float dy = 0.0F;
        float sc = pop;
        boolean popping = pop > 1.02F;
        if (alpha > 0.02F && frame != 0L && !popping) {
            rot = hashRand(index, frame, 1, 0) * 9.0F;
            dx = hashRand(index, frame, 2, 0) * 0.8F;
            dy = hashRand(index, frame, 3, 0) * 1.6F;
            sc *= 1.0F + hashRand(index, frame, 4, 0) * 0.12F;
        }

        float cx = x + font.width(glyph) / 2.0F;
        float cy = y + 3.5F;
        gg.pose().pushPose();
        gg.pose().translate(cx + dx, cy + dy, 0.0F);
        gg.pose().mulPose(Axis.ZP.rotationDegrees(rot));
        gg.pose().scale(sc, sc, 1.0F);
        gg.pose().translate(-cx, -cy, 0.0F);

        Matrix4f matrix = gg.pose().last().pose();
        int coreA = (int) (alpha * 255.0F);
        if (coreA < 5) {
            gg.pose().popPose();
            return;
        }

        int color = (coreA << 24) | (rgb & 0xFFFFFF);
        if (maskPass) {
            // 光晕画布只画字的轮廓，不画阴影；使用独立的 BufferSource，避免刷走 HUD。
            font.drawInBatch(glyph, x, y, color, false, matrix, maskBuffer(),
                    Font.DisplayMode.NORMAL, LightTexture.FULL_BRIGHT, 0);
            gg.pose().popPose();
            return;
        }

        if (drawShadow) {
            int shadowColor = ((color & 0xFCFCFC) >> 2) | (color & 0xFF000000);
            float shadowOff = glyph.codePointAt(0) < 0x2E80 ? 1.0F : 0.5F;
            gg.drawString(font, glyph, (int) (x + shadowOff), (int) (y + shadowOff), shadowColor, false);
            gg.flush();
        }

        // 回退到两版本前的柔光：两层放大副本；消散时不再画光晕，只做原地溶解。
        if (decorate && !fadingPass) {
            for (int i = 0; i < 2; i++) {
                float gs = i == 0 ? 1.16F : 1.34F;
                int ga = (int) (alpha * (i == 0 ? 32.0F : 18.0F));
                if (ga < 3) continue;
                gg.pose().pushPose();
                gg.pose().translate(cx, cy, 0.0F);
                gg.pose().scale(gs, gs, 1.0F);
                gg.pose().translate(-cx, -cy, 0.0F);
                int glowColor = (ga << 24) | (rgb & 0xFFFFFF);
                font.drawInBatch(glyph, x, y, glowColor, false, gg.pose().last().pose(),
                        gg.bufferSource(), Font.DisplayMode.NORMAL, LightTexture.FULL_BRIGHT, 0);
                gg.pose().popPose();
            }
            gg.flush();
        }

        gg.drawString(font, glyph, (int) x, (int) y, color, false);
        gg.pose().popPose();
    }

    /** 每个非空白字符的出场时间：按词分组，词内字符共用时间，近似逐字歌词�?*/
    private static long[] buildCharTimes(String text, long lineDur) {
        List<int[]> wordRanges = new ArrayList<>();
        List<String> words = new ArrayList<>();
        int start = -1;
        int cjk = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                if (start >= 0) {
                    wordRanges.add(new int[]{start, i});
                    words.add(text.substring(start, i));
                    start = -1;
                    cjk = 0;
                }
                continue;
            }
            boolean isCjk = c >= 0x2E80;
            if (start < 0) {
                start = i;
                cjk = 0;
            } else if (isCjk != (cjk > 0) || (isCjk && cjk >= 2)) {
                wordRanges.add(new int[]{start, i});
                words.add(text.substring(start, i));
                start = i;
                cjk = 0;
            }
            if (isCjk) cjk++;
        }
        if (start >= 0) {
            wordRanges.add(new int[]{start, text.length()});
            words.add(text.substring(start));
        }
        if (words.isEmpty()) return new long[0];

        long span = Math.min((long) (lineDur * 0.75D), words.size() * 330L);
        List<Long> out = new ArrayList<>();
        for (int wi = 0; wi < words.size(); wi++) {
            long at = span * wi / Math.max(1, words.size());
            for (int k = 0; k < words.get(wi).length(); k++) {
                if (!Character.isWhitespace(words.get(wi).charAt(k))) out.add(at);
            }
        }
        long[] result = new long[out.size()];
        for (int i = 0; i < result.length; i++) result[i] = out.get(i);
        return result;
    }

    private static float hashRand(int k, long frame, int salt, int seed) {
        int h = k * 73856093 ^ (int) (frame * 19349663L) ^ salt * 83492791 ^ seed;
        h = h ^ (h >>> 13);
        h *= 0x5bd1e995;
        h = h ^ (h >>> 15);
        return (h & 0xFFFF) / 32767.5F - 1.0F;
    }

    /** 按像素宽度换行：英文优先在空格处断，中日韩可以任意字之间断�?*/
    private static List<String> wrap(String text, int maxWidth) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty()) return out;
        Font font = Minecraft.getInstance().font;
        StringBuilder sb = new StringBuilder();
        int lastSpace = -1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            sb.append(c);
            if (c == ' ') lastSpace = sb.length() - 1;
            if (font.width(sb.toString()) > maxWidth && sb.length() > 1) {
                if (lastSpace > 0) {
                    out.add(sb.substring(0, lastSpace).trim());
                    String rest = sb.substring(lastSpace + 1);
                    sb.setLength(0);
                    sb.append(rest);
                } else {
                    out.add(sb.substring(0, sb.length() - 1).trim());
                    char last = sb.charAt(sb.length() - 1);
                    sb.setLength(0);
                    sb.append(last);
                }
                lastSpace = -1;
            }
        }
        if (!sb.toString().isBlank()) out.add(sb.toString().trim());
        out.removeIf(String::isEmpty);
        return out;
    }
}
