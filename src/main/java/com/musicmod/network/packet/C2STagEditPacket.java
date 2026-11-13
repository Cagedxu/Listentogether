package com.musicmod.network.packet;

import com.musicmod.client.tags.AudioTagWriter;
import com.musicmod.client.tags.TagEditData;
import com.musicmod.server.MusicServerManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.function.Supplier;

/** 客户端 -> 服务器：OP 修改歌曲标签并写回服务器源文件。 */
public class C2STagEditPacket {
    private final String song;
    private final String title;
    private final String artist;
    private final String album;
    private final String lyrics;
    private final byte[] cover;
    private final String coverMime;

    public C2STagEditPacket(String song, TagEditData data) {
        this.song = song == null ? "" : song;
        this.title = data.title == null ? "" : data.title;
        this.artist = data.artist == null ? "" : data.artist;
        this.album = data.album == null ? "" : data.album;
        this.lyrics = capUtf8(data.lyrics == null ? "" : data.lyrics, 18000);
        this.cover = fitCover(data.cover);
        this.coverMime = this.cover == null ? "" : "image/jpeg";
    }

    private static String capUtf8(String s, int maxBytes) {
        if (s == null) return "";
        if (s.getBytes(StandardCharsets.UTF_8).length <= maxBytes) return s;
        int bytes = 0;
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            int n = new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8).length;
            if (bytes + n > maxBytes) break;
            out.appendCodePoint(cp);
            bytes += n;
            i += Character.charCount(cp);
        }
        return out.toString();
    }

    /** 原版自定义包上限 32767 字节；封面压到 160px JPEG，避免连接被踢。 */
    private static byte[] fitCover(byte[] src) {
        if (src == null || src.length == 0) return null;
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(src));
            if (img == null) return null;
            int max = 160;
            int w = img.getWidth(), h = img.getHeight();
            double scale = Math.min(1.0, Math.min((double) max / w, (double) max / h));
            int nw = Math.max(1, (int) Math.round(w * scale));
            int nh = Math.max(1, (int) Math.round(h * scale));
            BufferedImage out = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = out.createGraphics();
            g.drawImage(img, 0, 0, nw, nh, null);
            g.dispose();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            Iterator<ImageWriter> it = ImageIO.getImageWritersByFormatName("jpeg");
            if (!it.hasNext()) return null;
            ImageWriter writer = it.next();
            try (ImageOutputStream ios = ImageIO.createImageOutputStream(bos)) {
                writer.setOutput(ios);
                ImageWriteParam param = writer.getDefaultWriteParam();
                if (param.canWriteCompressed()) {
                    param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                    param.setCompressionQuality(0.78f);
                }
                writer.write(null, new IIOImage(out, null, null), param);
            } finally {
                writer.dispose();
            }
            byte[] result = bos.toByteArray();
            return result.length <= 22000 ? result : null;
        } catch (Throwable t) {
            return null;
        }
    }

    public C2STagEditPacket(FriendlyByteBuf buf) {
        this.song = buf.readUtf(256);
        this.title = buf.readUtf(512);
        this.artist = buf.readUtf(512);
        this.album = buf.readUtf(512);
        this.lyrics = buf.readUtf(65536);
        int len = Math.min(buf.readVarInt(), 200 * 1024);
        this.cover = len > 0 ? buf.readByteArray(len) : null;
        this.coverMime = buf.readUtf(64);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(song, 256);
        buf.writeUtf(title, 512);
        buf.writeUtf(artist, 512);
        buf.writeUtf(album, 512);
        buf.writeUtf(lyrics, 65536);
        if (cover == null) {
            buf.writeVarInt(0);
        } else {
            buf.writeVarInt(cover.length);
            buf.writeByteArray(cover);
        }
        buf.writeUtf(coverMime, 64);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null || (!player.hasPermissions(2) && !player.server.isSingleplayer())) return;
            MusicServerManager manager = MusicServerManager.getInstance();
            File file = manager.getMusicLibrary().getSongFile(song);
            if (file == null || !file.isFile()) return;
            TagEditData data = new TagEditData(title, artist, album, lyrics, cover, coverMime, "server");
            AudioTagWriter.write(file, data);
            manager.getMusicLibrary().scan();
            for (ServerPlayer p : player.server.getPlayerList().getPlayers()) {
                manager.sendSongInfoToPlayer(p, song);
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
