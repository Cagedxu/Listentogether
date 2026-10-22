package com.musicmod.network.packet;

import com.musicmod.client.ClientMusicManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 服务器 -> 客户端：通知暂停或恢复播放。
 */
public class S2CPausePacket {
    private final boolean paused;       // true=暂停, false=恢复
    private final long offsetMs;        // 恢复时的播放位置（毫秒）

    public S2CPausePacket(boolean paused, long offsetMs) {
        this.paused = paused;
        this.offsetMs = offsetMs;
    }

    public S2CPausePacket(FriendlyByteBuf buf) {
        this.paused = buf.readBoolean();
        this.offsetMs = buf.readLong();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(paused);
        buf.writeLong(offsetMs);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                    ClientMusicManager.getInstance().onPauseToggle(paused, offsetMs));
        });
        ctx.get().setPacketHandled(true);
    }

    public boolean isPaused() { return paused; }
    public long getOffsetMs() { return offsetMs; }
}