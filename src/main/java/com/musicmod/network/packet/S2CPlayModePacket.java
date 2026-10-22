package com.musicmod.network.packet;

import com.musicmod.PlayMode;
import com.musicmod.client.ClientMusicManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 服务器 -> 客户端：广播播放模式变更。
 * 任何玩家切换播放模式后，全服同步显示。
 */
public class S2CPlayModePacket {
    private final PlayMode playMode;

    public S2CPlayModePacket(PlayMode playMode) {
        this.playMode = playMode;
    }

    public S2CPlayModePacket(FriendlyByteBuf buf) {
        this.playMode = PlayMode.fromName(buf.readUtf(32));
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(playMode.name(), 32);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                    ClientMusicManager.getInstance().onPlayModeReceived(playMode));
        });
        ctx.get().setPacketHandled(true);
    }
}