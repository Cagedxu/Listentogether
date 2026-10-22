package com.musicmod.network.packet;

import com.musicmod.client.ClientMusicManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 服务器 -> 客户端：设置个人音量。
 * 由 /music volume 命令触发，发送给执行命令的玩家。
 */
public class S2CSetVolumePacket {
    private final int volumePercent; // 0-100

    public S2CSetVolumePacket(int volumePercent) {
        this.volumePercent = Math.max(0, Math.min(100, volumePercent));
    }

    public S2CSetVolumePacket(FriendlyByteBuf buf) {
        this.volumePercent = buf.readVarInt();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(volumePercent);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                    ClientMusicManager.getInstance().setVolume(volumePercent / 100.0f));
        });
        ctx.get().setPacketHandled(true);
    }

    public int getVolumePercent() { return volumePercent; }
}