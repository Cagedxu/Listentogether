package com.musicmod.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.musicmod.MusicMod;
import com.musicmod.client.gui.LyricsParser;
import com.musicmod.config.MusicModConfig;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.LightTexture;
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
 * 世界内 3D 歌词。
 *
 * <p>歌词在世界里有一个真实坐标，始终朝向镜头；渲染时把它投影到屏幕，
 * 在 HUD 阶段绘制。这样既保留距离、缩放和视差，又不会被光影包的
 * 延迟渲染/后处理吞掉，兼容性比直接在世界阶段画文字好得多。</p>
 *
 * <p>效果参考 DangoMusic（GPL-3.0）的 LyricsModule。本项目以 GPL-3.0
 * 分发，详见 LICENSE 与 README。</p>
 */
@Mod.EventBusSubscriber(modid = MusicMod.MOD_ID, value = Dist.CLIENT)
public final class WorldLyricsRenderer {

    /** 歌词距离（格） */
    private static final double DISTANCE = 10.0D;
    /** 左右随机散布角度 */
    private static final float SPREAD = 30.0F;
    /** 文字大小基准 */
    private static final float BASE_SCALE = 0.032F;
    /** 歌词行高（字体像素） */
    private static final float LINE_HEIGHT = 11.0F;
    /** 原文换行宽度（字体像素） */
    private static final int WRAP_WIDTH = 200;
    /** 翻译换行宽度 */
    private static final int TRANSLATION_WRAP = 270;
    /** 翻译缩放 */
    private static final float TRANSLATION_SCALE = 0.72F;
    /** 旧句淡出时长（秒） */
    private static final float FADE_SECONDS = 0.4F;
    /** 手绘抖动的帧率 */
    private static final int WIGGLE_FPS = 8;
    /** 屏幕边缘留白（GUI 像素） */
    private static final double EDGE_MARGIN_X = 10.0D;
    private static final double EDGE_MARGIN_TOP = 8.0D;
    private static final double EDGE_MARGIN_BOTTOM = 34.0D;
    /** 原文颜色：DangoMusic 默认暖白 0xF7E1D7 */
    private static final int TEXT_RGB = 0xFFF7E1D7;
    /** 翻译颜色：低饱和的暖粉 */
    private static final int TRANSLATION_RGB = 0xFFE5B4C2;
    /** 光晕颜色：与原文同色扩散，不额外染橙 */
    private static final int GLOW_RGB = 0xFFF7E1D7;

    private static final Random RANDOM = new Random();

    private static final class Floating {
        final List<String> lines;
        final List<String> translation;
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

        Floating(List<String> lines, List<String> translation, long startMs, long revealMs,
                 long[] charTimes, double x, double y, double z, float yaw, float pitch) {
            this.lines = lines;
            this.translation = translation;
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

    /** 投影结果；behind 表示歌词在镜头背面。 */
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

    private WorldLyricsRenderer() {}

    /** 断开连接 / 切换世界时清空，避免旧歌词残留在新世界。 */
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
        if (mc.player == null || mc.level == null || mc.options.hideGui || mc.screen != null) {
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
        // 光影包/其他 HUD 可能在 Post 阶段留下非白色的 shader color，
        // 那会把整段文字（包括发光和描边）一起乘成黑色。
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
        for (int i = fading.size() - 1; i >= 0; i--) {
            Floating f = fading.get(i);
            float t = (now - f.fadeStartNs) / 1_000_000_000.0F;
            if (t >= FADE_SECONDS) {
                fading.remove(i);
                continue;
            }
            drawFloating(gg, f, camera, cam, 1.0F - t / FADE_SECONDS, t * 0.4F, songNow,
                    guiW, guiH, tanH, tanV, true);
        }
        if (active != null) {
            float appear = active.resumedAtNs == 0L ? 1.0F
                    : Math.min(1.0F, (now - active.resumedAtNs) / 300_000_000.0F);
            drawFloating(gg, active, camera, cam, appear, 0.0F, songNow,
                    guiW, guiH, tanH, tanV, false);
        }
        gg.pose().popPose();
        gg.flush();
    }

    private static void fadeActive() {
        if (active != null) {
            active.fading = true;
            active.fadeStartNs = System.nanoTime();
            fading.add(active);
            active = null;
        }
    }

    private static Floating spawn(LyricsParser.Line line, long nextMs, float partial) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return null;

        List<String> lines = wrap(line.text, WRAP_WIDTH);
        if (lines.isEmpty()) return null;

        List<String> translation = List.of();
        if (MusicModConfig.getShowTranslation()
                && line.translation != null && !line.translation.isEmpty()) {
            translation = wrap(line.translation, TRANSLATION_WRAP);
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
        return new Floating(lines, translation, line.timeMs, reveal, charTimes,
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
        // up = right x forward，跟着俯仰角一起转，抬头/低头时投影才不会偏
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
        // 跟随开启时按固定 10 格距离取屏幕字号；否则走远后 cz 变大，
        // 字会越来越小，看起来像被强制缩小。
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
            // 快出屏时停在当前屏幕位置，不往左/右边缘中心拽，也不强制缩放。
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
        gg.pose().translate(f.screenX, f.screenY - lift * 8.0D, 0.0D);
        gg.pose().scale((float) scale, (float) scale, 1.0F);

        long elapsed = songNow - f.startMs;
        // 消失中的旧句只做干净的整句淡出：不再抖动，也不再画多层发光副本。
        // 抖动 + 28 层偏移光晕在 alpha 降低时会散成一团，看起来像“炸开”。
        boolean decorate = !fadingOut;
        drawAnimated(gg, f.lines, elapsed, f.revealMs, alphaMul, f.charTimes, 0.0F, decorate);
        if (!f.translation.isEmpty()) {
            drawTranslation(gg, f.translation, elapsed, f.revealMs, alphaMul,
                    f.lines.size() * LINE_HEIGHT / 2.0F + 2.5F, decorate);
        }
        gg.pose().popPose();
    }

    private static void drawAnimated(GuiGraphics gg, List<String> lines, long elapsed, long revealMs,
                                     float alphaMul, long[] charTimes, float yOffset,
                                     boolean decorate) {
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
                        if (t >= 0.0F) {
                            alpha = Math.min(1.0F, t * 3.0F) * alphaMul;
                            // 逐字轻微放大；放大期间关闭抖动和发光副本，
                            // 否则放大后的字会被多层偏移副本包成一团。
                            float back = Math.max(0.0F, 1.0F - t);
                            pop = 1.0F + 0.22F * back * back;
                        } else {
                            alpha = 0.0F;
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
                                TEXT_RGB, alpha, nonSpace, frame, pop, decorate);
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
        Font font = Minecraft.getInstance().font;
        float a = Math.max(0.0F, Math.min(1.0F, (elapsed - 120L) / 260.0F));
        a = a * a * (3.0F - 2.0F * a);
        float alpha = a * alphaMul * 0.9F;
        if (alpha <= 0.02F) return;

        gg.pose().pushPose();
        gg.pose().translate(0.0F, top, 0.0F);
        gg.pose().scale(TRANSLATION_SCALE, TRANSLATION_SCALE, 1.0F);
        float y = 0.0F;
        for (String line : lines) {
            float lineW = font.width(line);
            float x = -lineW / 2.0F;
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                float cw = font.width(String.valueOf(c));
                if (c != ' ') {
                    drawGlyph(gg, font, String.valueOf(c), x, y,
                            TRANSLATION_RGB, alpha, 2000 + i, 0L, 1.0F, false);
                }
                x += cw;
            }
            y += LINE_HEIGHT;
        }
        gg.pose().popPose();
    }

    private static void drawGlyph(GuiGraphics gg, Font font, String glyph, float x, float y,
                                  int rgb, float alpha, int index, long frame, float pop,
                                  boolean decorate) {
        // 手绘抖动：同一帧内每个字有固定的小旋转/位移/缩放
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

        // 1) 外发光：从很淡的大范围光晕，到贴着字边的暖光。
        //    淡出中的句子不再画这些偏移副本，否则低透明度下会散成一片。
        if (decorate && !popping) {
            // 1a) 宽光晕：把同一个字按比例放大后以低透明度垫在下面，
            //     比单纯偏移副本更接近原模组的柔和扩散，也不容易看出重影。
            for (int hi = 0; hi < 2; hi++) {
                float hs = hi == 0 ? 1.28F : 1.62F;
                int ha = (int) (alpha * 255.0F * (hi == 0 ? 0.13F : 0.07F));
                if (ha >= 3) {
                    gg.pose().pushPose();
                    gg.pose().translate(cx, cy, 0.0F);
                    gg.pose().scale(hs, hs, 1.0F);
                    gg.pose().translate(-cx, -cy, 0.0F);
                    int color = (ha << 24) | (GLOW_RGB & 0xFFFFFF);
                    gg.drawString(font, glyph, (int) x, (int) y, color, false);
                    gg.pose().popPose();
                }
            }
            // 1b) 贴着字边的三层暖光，补上放大量无法覆盖的近距光。
            int glowBloom = (int) (alpha * 255.0F * 0.045F);
            if (glowBloom >= 3) {
                int color = (glowBloom << 24) | (GLOW_RGB & 0xFFFFFF);
                for (int i = 0; i < 8; i++) {
                    double ang = Math.PI * 2.0D * i / 8.0D;
                    float ox = (float) Math.cos(ang) * 4.2F;
                    float oy = (float) Math.sin(ang) * 4.2F;
                    gg.drawString(font, glyph, (int) (x + ox), (int) (y + oy), color, false);
                }
            }
            int glowOuter = (int) (alpha * 255.0F * 0.11F);
            if (glowOuter >= 4) {
                int color = (glowOuter << 24) | (GLOW_RGB & 0xFFFFFF);
                for (int i = 0; i < 12; i++) {
                    double ang = Math.PI * 2.0D * i / 12.0D;
                    float ox = (float) Math.cos(ang) * 2.4F;
                    float oy = (float) Math.sin(ang) * 2.4F;
                    gg.drawString(font, glyph, (int) (x + ox), (int) (y + oy), color, false);
                }
            }
            int glowInner = (int) (alpha * 255.0F * 0.18F);
            if (glowInner >= 4) {
                int color = (glowInner << 24) | (GLOW_RGB & 0xFFFFFF);
                for (int i = 0; i < 8; i++) {
                    double ang = Math.PI * 2.0D * i / 8.0D;
                    float ox = (float) Math.cos(ang) * 1.2F;
                    float oy = (float) Math.sin(ang) * 1.2F;
                    gg.drawString(font, glyph, (int) (x + ox), (int) (y + oy), color, false);
                }
            }
            gg.flush();
        }

        // 2) 先单独画阴影并 flush，再画清晰的字。
        //    原版会把阴影和字放进同一批，半透明按深度重排后阴影可能盖到字上，
        //    整句就会发黑；DangoMusic 也是把阴影单独先画一批。
        int color = (coreA << 24) | (rgb & 0xFFFFFF);
        int shadowColor = ((color & 0xFCFCFC) >> 2) | (color & 0xFF000000);
        float shadowOff = glyph.codePointAt(0) < 0x2E80 ? 1.0F : 0.5F;
        gg.drawString(font, glyph, (int) (x + shadowOff), (int) (y + shadowOff), shadowColor, false);
        gg.flush();
        gg.drawString(font, glyph, (int) x, (int) y, color, false);
        gg.pose().popPose();
    }

    /** 每个非空白字符的出场时间：按词分组，词内字符共用时间，近似逐字歌词。 */
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

    /** 按像素宽度换行：英文优先在空格处断，中日韩可以任意字之间断。 */
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
