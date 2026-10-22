package com.musicmod.client;

import com.musicmod.MusicMod;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import java.io.ByteArrayInputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 封面纹理管理器。
 * 将服务器下发的封面 JPEG 解码为 NativeImage 并注册为动态纹理，
 * 供 GUI 通过 ResourceLocation 绘制。
 * 所有 GL 操作通过 Minecraft.execute 调度到客户端主线程执行。
 */
public class CoverTextureManager {

    /** 纹理 ID 与尺寸缓存 */
    private static final Map<String, ResourceLocation> textures = new ConcurrentHashMap<>();
    private static final Map<String, int[]> sizes = new ConcurrentHashMap<>();

    /**
     * 注册封面纹理。
     * @param songName 歌曲名（作为缓存 key）
     * @param jpegBytes JPEG/PNG 图片字节
     * @param onReady 纹理就绪回调（在主线程执行，可为 null）
     */
    public static void setCover(String songName, byte[] jpegBytes, Runnable onReady) {
        Minecraft.getInstance().execute(() -> {
            try {
                // 必须使用 read(InputStream)：MC 内部会用 MemoryUtil.memAlloc 分配 direct ByteBuffer。
                // 绝不能用 ByteBuffer.wrap()（堆缓冲区）——LWJGL 的 stbi_load_from_memory
                // 要求 direct buffer，堆 buffer 传给 native 层会触发 EXCEPTION_ACCESS_VIOLATION 崩溃。
                // stb_image 本身支持 JPEG/PNG，无需 BufferedImage 中转。
                NativeImage nativeImage = NativeImage.read(new ByteArrayInputStream(jpegBytes));

                ResourceLocation texId = new ResourceLocation(MusicMod.MOD_ID,
                        "cover_" + sanitize(songName));
                DynamicTexture texture = new DynamicTexture(nativeImage);
                texture.upload();
                Minecraft.getInstance().getTextureManager().register(texId, texture);

                textures.put(songName, texId);
                sizes.put(songName, new int[]{nativeImage.getWidth(), nativeImage.getHeight()});
                if (onReady != null) onReady.run();
            } catch (Exception e) {
                MusicMod.LOGGER.warn("Failed to register cover texture for " + songName + ": " + e);
                if (onReady != null) onReady.run();
            }
        });
    }

    /** 获取封面纹理 ID，未就绪返回 null */
    public static ResourceLocation get(String songName) {
        return textures.get(songName);
    }

    /** 封面是否已就绪 */
    public static boolean isReady(String songName) {
        return textures.containsKey(songName);
    }

    /** 获取封面原始尺寸 [w, h]，未就绪返回 null */
    public static int[] getSize(String songName) {
        return sizes.get(songName);
    }

    /** 清空所有封面纹理（断开连接时调用） */
    public static void clearAll() {
        Minecraft.getInstance().execute(() -> {
            for (ResourceLocation id : textures.values()) {
                try {
                    Minecraft.getInstance().getTextureManager().release(id);
                } catch (Exception ignored) {}
            }
            textures.clear();
            sizes.clear();
        });
    }

    /** 将歌曲名转为合法资源路径字符 */
    private static String sanitize(String name) {
        StringBuilder sb = new StringBuilder();
        for (char c : name.toCharArray()) {
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '_' || c == '-') {
                sb.append(Character.toLowerCase(c));
            } else {
                sb.append('_');
            }
        }
        // 加 hash 后缀避免 sanitize 后重名
        return sb.toString() + "_" + Integer.toHexString(name.hashCode());
    }
}