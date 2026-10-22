package com.musicmod.network.packet;

import com.musicmod.client.ClientTagStore;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 服务器 -> 客户端：单曲详情（封面缩略图 + 歌词 + 完整标签）。
 * 封面由服务端缩放至 128px 并重编码为 JPEG，控制单包体积。
 */
public class S2CSongInfoPacket {
    private final String songName;
    private final String title;
    private final String artist;
    private final String album;
    private final long durationMs;
    private final byte[] coverJpeg; // 可能为空数组（无封面）
    private final String coverMime;
    private final String lyrics;    // 可能为空串

    public S2CSongInfoPacket(String songName, String title, String artist, String album,
                             long durationMs, byte[] coverJpeg, String coverMime, String lyrics) {
        this.songName = songName;
        this.title = title == null ? "" : title;
        this.artist = artist == null ? "" : artist;
        this.album = album == null ? "" : album;
        this.durationMs = durationMs;
        this.coverJpeg = coverJpeg == null ? new byte[0] : coverJpeg;
        this.coverMime = coverMime == null ? "" : coverMime;
        this.lyrics = lyrics == null ? "" : lyrics;
    }

    public S2CSongInfoPacket(FriendlyByteBuf buf) {
        this.songName = buf.readUtf(256);
        this.title = buf.readUtf(256);
        this.artist = buf.readUtf(256);
        this.album = buf.readUtf(256);
        this.durationMs = buf.readLong();
        this.coverJpeg = buf.readByteArray();
        this.coverMime = buf.readUtf(64);
        this.lyrics = buf.readUtf(32767);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(songName, 256);
        buf.writeUtf(title, 256);
        buf.writeUtf(artist, 256);
        buf.writeUtf(album, 256);
        buf.writeLong(durationMs);
        buf.writeByteArray(coverJpeg);
        buf.writeUtf(coverMime, 64);
        buf.writeUtf(lyrics, 32767);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                    ClientTagStore.getInstance().onSongInfoReceived(
                            songName, title, artist, album, durationMs, coverJpeg, lyrics));
        });
        ctx.get().setPacketHandled(true);
    }

    public String getSongName() { return songName; }
    public String getTitle() { return title; }
    public String getArtist() { return artist; }
    public String getAlbum() { return album; }
    public long getDurationMs() { return durationMs; }
    public byte[] getCoverJpeg() { return coverJpeg; }
    public String getLyrics() { return lyrics; }
}