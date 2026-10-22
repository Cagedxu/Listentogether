package com.musicmod.network.packet;

import com.musicmod.server.MusicServerManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 客户端 -> 服务器：请求歌曲列表。
 */
public class C2SRequestListPacket {

    public C2SRequestListPacket() {}

    public C2SRequestListPacket(FriendlyByteBuf buf) {}

    public void encode(FriendlyByteBuf buf) {}

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player != null) {
                // OP 请求列表时先重新扫描音乐库（支持服务器运行期间新增/上传的音乐文件）；
                // 非 OP 只刷新客户端列表，不触发扫描
                if (player.hasPermissions(2)) {
                    MusicServerManager.getInstance().rescanLibrary();
                }
                MusicServerManager.getInstance().sendSongListToPlayer(player);
            }
        });
        ctx.get().setPacketHandled(true);
    }
}