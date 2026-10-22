package com.musicmod.network.packet;

import com.musicmod.client.ClientPlaylists;
import com.musicmod.playlist.Playlist;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 服务器 -> 客户端：同步完整歌单列表。
 * <p>
 * 歌单数据量很小（仅名称与歌曲名），每次变更后全量广播，
 * 避免增量同步带来的乱序/丢失问题。客户端由 {@link ClientPlaylists} 缓存。
 */
public class S2CPlaylistsPacket {

    private final List<Playlist> playlists;

    public S2CPlaylistsPacket(List<Playlist> playlists) {
        this.playlists = new ArrayList<>(playlists == null ? 0 : playlists.size());
        if (playlists != null) {
            for (Playlist playlist : playlists) {
                // 拷贝一份，避免网络线程编码时服务端歌单被修改
                this.playlists.add(new Playlist(playlist.getId(), playlist.getName(),
                        playlist.getCreatedAt(), playlist.getSongs()));
            }
        }
    }

    public S2CPlaylistsPacket(FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        this.playlists = new ArrayList<>(Math.min(count, 128));
        for (int i = 0; i < count; i++) {
            String id = buf.readUtf(64);
            String name = buf.readUtf(128);
            long createdAt = buf.readLong();
            int songCount = buf.readVarInt();
            List<String> songs = new ArrayList<>(Math.min(songCount, 512));
            for (int j = 0; j < songCount; j++) {
                songs.add(buf.readUtf(256));
            }
            this.playlists.add(new Playlist(id, name, createdAt, songs));
        }
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(playlists.size());
        for (Playlist playlist : playlists) {
            buf.writeUtf(playlist.getId(), 64);
            buf.writeUtf(playlist.getName(), 128);
            buf.writeLong(playlist.getCreatedAt());
            List<String> songs = playlist.getSongs();
            buf.writeVarInt(songs.size());
            for (String song : songs) {
                buf.writeUtf(song, 256);
            }
        }
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                    ClientPlaylists.getInstance().onPlaylistsReceived(playlists));
        });
        ctx.get().setPacketHandled(true);
    }

    public List<Playlist> getPlaylists() {
        return playlists;
    }
}
