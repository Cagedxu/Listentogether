package com.musicmod.network.packet;

import com.musicmod.EmptyServerBehavior;
import com.musicmod.client.ClientMusicManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 服务器 -> 客户端：广播无人时行为变更。
 * OP 修改服务器配置后，全服同步显示。
 */
public class S2CEmptyBehaviorPacket {
    private final EmptyServerBehavior behavior;

    public S2CEmptyBehaviorPacket(EmptyServerBehavior behavior) {
        this.behavior = behavior;
    }

    public S2CEmptyBehaviorPacket(FriendlyByteBuf buf) {
        this.behavior = EmptyServerBehavior.fromName(buf.readUtf(32));
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(behavior.name(), 32);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                    ClientMusicManager.getInstance().onEmptyBehaviorReceived(behavior));
        });
        ctx.get().setPacketHandled(true);
    }
}