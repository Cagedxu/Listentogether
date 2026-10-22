package com.musicmod.network.packet;

import com.musicmod.client.ClientTagStore;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 服务器 -> 客户端：歌曲标签摘要批量同步。
 * 每首歌携带：歌曲名、标题、艺术家、专辑、时长（秒）。
 * 封面与歌词数据量大，由 {@link S2CSongInfoPacket} 按需单独下发。
 */
public class S2CSongTagsPacket {

    public static class Entry {
        public final String songName;
        public final String title;
        public final String artist;
        public final String album;
        public final int durationSec; // -1 = 未知

        public Entry(String songName, String title, String artist, String album, int durationSec) {
            this.songName = songName;
            this.title = title;
            this.artist = artist;
            this.album = album;
            this.durationSec = durationSec;
        }
    }

    private final List<Entry> entries;

    public S2CSongTagsPacket(List<Entry> entries) {
        this.entries = entries;
    }

    public S2CSongTagsPacket(FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            entries.add(new Entry(
                    buf.readUtf(256),
                    buf.readUtf(256),
                    buf.readUtf(256),
                    buf.readUtf(256),
                    buf.readVarInt() - 1)); // 存储时 +1 使 -1 变 0，无符号传输
        }
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(entries.size());
        for (Entry e : entries) {
            buf.writeUtf(e.songName, 256);
            buf.writeUtf(e.title, 256);
            buf.writeUtf(e.artist, 256);
            buf.writeUtf(e.album, 256);
            buf.writeVarInt(e.durationSec + 1); // -1 -> 0
        }
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                    ClientTagStore.getInstance().onTagsReceived(entries));
        });
        ctx.get().setPacketHandled(true);
    }

    public List<Entry> getEntries() {
        return entries;
    }
}