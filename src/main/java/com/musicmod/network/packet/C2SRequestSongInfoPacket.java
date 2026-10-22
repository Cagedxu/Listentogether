package com.musicmod.network.packet;

import com.musicmod.server.MusicServerManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 客户端 -> 服务器：请求单曲详情（封面、歌词、完整标签）。
 * 服务器返回 {@link S2CSongInfoPacket}。
 */
public class C2SRequestSongInfoPacket {
    private final String songName;

    public C2SRequestSongInfoPacket(String songName) {
        this.songName = songName;
    }

    public C2SRequestSongInfoPacket(FriendlyByteBuf buf) {
        this.songName = buf.readUtf(256);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(songName, 256);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;
            MusicServerManager.getInstance().sendSongInfoToPlayer(player, songName);
        });
        ctx.get().setPacketHandled(true);
    }

    public String getSongName() {
        return songName;
    }
}