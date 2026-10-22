package com.musicmod.network.packet;

import com.musicmod.PlayMode;
import com.musicmod.client.ClientMusicManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 服务器 -> 客户端：发送可用歌曲列表及当前播放状态。
 * 包含播放模式，供播放器 GUI 显示。
 */
public class S2CSongListPacket {
    private final List<String> songs;
    private final String currentSong;
    private final boolean isPlaying;
    private final boolean isPaused;
    private final PlayMode playMode;

    public S2CSongListPacket(List<String> songs, String currentSong, boolean isPlaying,
                             boolean isPaused, PlayMode playMode) {
        this.songs = songs;
        this.currentSong = currentSong;
        this.isPlaying = isPlaying;
        this.isPaused = isPaused;
        this.playMode = playMode;
    }

    public S2CSongListPacket(FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        this.songs = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            this.songs.add(buf.readUtf(256));
        }
        this.currentSong = buf.readUtf(256);
        this.isPlaying = buf.readBoolean();
        this.isPaused = buf.readBoolean();
        this.playMode = PlayMode.fromName(buf.readUtf(32));
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(songs.size());
        for (String song : songs) {
            buf.writeUtf(song, 256);
        }
        buf.writeUtf(currentSong, 256);
        buf.writeBoolean(isPlaying);
        buf.writeBoolean(isPaused);
        buf.writeUtf(playMode.name(), 32);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                    ClientMusicManager.getInstance().onSongListReceived(
                            songs, currentSong, isPlaying, isPaused, playMode));
        });
        ctx.get().setPacketHandled(true);
    }

    public List<String> getSongs() { return songs; }
    public String getCurrentSong() { return currentSong; }
    public boolean isPlaying() { return isPlaying; }
    public boolean isPaused() { return isPaused; }
    public PlayMode getPlayMode() { return playMode; }
}