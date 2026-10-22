package com.musicmod.network.packet;

import com.musicmod.MusicMod;
import com.musicmod.client.ClientMusicManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 服务器 -> 客户端：通知播放某首歌曲。
 * 客户端收到后检查本地缓存，若缓存命中则直接播放，否则请求文件数据。
 */
public class S2CPlayPacket {
    private final String songName;    // 歌曲显示名
    private final String fileName;    // 完整文件名（含扩展名）
    private final String md5;         // 文件MD5校验值
    private final long fileSize;      // 文件大小（字节）
    private final long offsetMs;      // 播放起始偏移（毫秒，用于同步）

    public S2CPlayPacket(String songName, String fileName, String md5, long fileSize, long offsetMs) {
        this.songName = songName;
        this.fileName = fileName;
        this.md5 = md5;
        this.fileSize = fileSize;
        this.offsetMs = offsetMs;
    }

    public S2CPlayPacket(FriendlyByteBuf buf) {
        this.songName = buf.readUtf(256);
        this.fileName = buf.readUtf(256);
        this.md5 = buf.readUtf(64);
        this.fileSize = buf.readLong();
        this.offsetMs = buf.readLong();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(songName, 256);
        buf.writeUtf(fileName, 256);
        buf.writeUtf(md5, 64);
        buf.writeLong(fileSize);
        buf.writeLong(offsetMs);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                    ClientMusicManager.getInstance().onPlayRequest(songName, fileName, md5, fileSize, offsetMs));
        });
        ctx.get().setPacketHandled(true);
    }

    public String getSongName() { return songName; }
    public String getFileName() { return fileName; }
    public String getMd5() { return md5; }
    public long getFileSize() { return fileSize; }
    public long getOffsetMs() { return offsetMs; }
}