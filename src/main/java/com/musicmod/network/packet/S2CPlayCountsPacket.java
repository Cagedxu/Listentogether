package com.musicmod.network.packet;

import com.musicmod.client.ClientTagStore;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/** 服务器 -> 客户端：当前存档的歌曲播放次数。 */
public class S2CPlayCountsPacket {
    private final Map<String, Integer> counts;

    public S2CPlayCountsPacket(Map<String, Integer> counts) {
        this.counts = new HashMap<>(counts);
    }

    public S2CPlayCountsPacket(FriendlyByteBuf buf) {
        int size = buf.readVarInt();
        this.counts = new HashMap<>(size);
        for (int i = 0; i < size; i++) {
            counts.put(buf.readUtf(256), buf.readVarInt());
        }
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(counts.size());
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            buf.writeUtf(entry.getKey(), 256);
            buf.writeVarInt(Math.max(0, entry.getValue()));
        }
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                ClientTagStore.getInstance().onPlayCountsReceived(counts)));
        ctx.get().setPacketHandled(true);
    }
}
