package com.musicmod.network.packet;

import com.musicmod.server.MusicServerManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** 客户端 -> 服务器：直接删除服务器歌曲文件（仅 OP）。 */
public class C2SDeleteSongPacket {
    private final String song;

    public C2SDeleteSongPacket(String song) {
        this.song = song == null ? "" : song;
    }

    public C2SDeleteSongPacket(FriendlyByteBuf buf) {
        this.song = buf.readUtf(256);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(song, 256);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null || (!player.hasPermissions(2) && !player.server.isSingleplayer())) return;
            MusicServerManager.getInstance().deleteSong(song);
        });
        ctx.get().setPacketHandled(true);
    }
}
