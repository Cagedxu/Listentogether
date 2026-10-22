package com.musicmod.network;

import com.musicmod.MusicMod;
import com.musicmod.network.packet.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

/**
 * 网络通道注册与发包工具类。
 */
public class NetworkHandler {
    // 协议版本：新增包或修改包结构时必须提升，避免新旧版本客户端/服务端混连导致解码错误。
    // 4：新增歌单同步包与歌单操作包；5：歌单操作包支持批量添加歌曲。
    private static final String PROTOCOL_VERSION = "5";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(MusicMod.MOD_ID, "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals
    );

    private static int packetId = 0;

    public static void register() {
        // 服务器 -> 客户端
        CHANNEL.messageBuilder(S2CPlayPacket.class, packetId++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(S2CPlayPacket::encode)
                .decoder(S2CPlayPacket::new)
                .consumerMainThread(S2CPlayPacket::handle)
                .add();

        CHANNEL.messageBuilder(S2CStopPacket.class, packetId++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(S2CStopPacket::encode)
                .decoder(S2CStopPacket::new)
                .consumerMainThread(S2CStopPacket::handle)
                .add();

        CHANNEL.messageBuilder(S2CPausePacket.class, packetId++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(S2CPausePacket::encode)
                .decoder(S2CPausePacket::new)
                .consumerMainThread(S2CPausePacket::handle)
                .add();

        CHANNEL.messageBuilder(S2CSongListPacket.class, packetId++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(S2CSongListPacket::encode)
                .decoder(S2CSongListPacket::new)
                .consumerMainThread(S2CSongListPacket::handle)
                .add();

        CHANNEL.messageBuilder(S2CSongDataPacket.class, packetId++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(S2CSongDataPacket::encode)
                .decoder(S2CSongDataPacket::new)
                .consumerMainThread(S2CSongDataPacket::handle)
                .add();

        CHANNEL.messageBuilder(S2CSetVolumePacket.class, packetId++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(S2CSetVolumePacket::encode)
                .decoder(S2CSetVolumePacket::new)
                .consumerMainThread(S2CSetVolumePacket::handle)
                .add();

        CHANNEL.messageBuilder(S2CSongTagsPacket.class, packetId++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(S2CSongTagsPacket::encode)
                .decoder(S2CSongTagsPacket::new)
                .consumerMainThread(S2CSongTagsPacket::handle)
                .add();

        CHANNEL.messageBuilder(S2CSongInfoPacket.class, packetId++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(S2CSongInfoPacket::encode)
                .decoder(S2CSongInfoPacket::new)
                .consumerMainThread(S2CSongInfoPacket::handle)
                .add();

        CHANNEL.messageBuilder(S2CPlayModePacket.class, packetId++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(S2CPlayModePacket::encode)
                .decoder(S2CPlayModePacket::new)
                .consumerMainThread(S2CPlayModePacket::handle)
                .add();

        CHANNEL.messageBuilder(S2CEmptyBehaviorPacket.class, packetId++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(S2CEmptyBehaviorPacket::encode)
                .decoder(S2CEmptyBehaviorPacket::new)
                .consumerMainThread(S2CEmptyBehaviorPacket::handle)
                .add();

        // 客户端 -> 服务器
        CHANNEL.messageBuilder(C2SRequestSongDataPacket.class, packetId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(C2SRequestSongDataPacket::encode)
                .decoder(C2SRequestSongDataPacket::new)
                .consumerMainThread(C2SRequestSongDataPacket::handle)
                .add();

        CHANNEL.messageBuilder(C2SRequestListPacket.class, packetId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(C2SRequestListPacket::encode)
                .decoder(C2SRequestListPacket::new)
                .consumerMainThread(C2SRequestListPacket::handle)
                .add();

        CHANNEL.messageBuilder(C2SRequestSongInfoPacket.class, packetId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(C2SRequestSongInfoPacket::encode)
                .decoder(C2SRequestSongInfoPacket::new)
                .consumerMainThread(C2SRequestSongInfoPacket::handle)
                .add();

        CHANNEL.messageBuilder(C2SMusicActionPacket.class, packetId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(C2SMusicActionPacket::encode)
                .decoder(C2SMusicActionPacket::new)
                .consumerMainThread(C2SMusicActionPacket::handle)
                .add();

        CHANNEL.messageBuilder(C2SUploadSongPacket.class, packetId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(C2SUploadSongPacket::encode)
                .decoder(C2SUploadSongPacket::new)
                .consumerMainThread(C2SUploadSongPacket::handle)
                .add();

        CHANNEL.messageBuilder(S2CUploadResultPacket.class, packetId++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(S2CUploadResultPacket::encode)
                .decoder(S2CUploadResultPacket::new)
                .consumerMainThread(S2CUploadResultPacket::handle)
                .add();

        // 歌单同步（服务器 -> 客户端）
        CHANNEL.messageBuilder(S2CPlaylistsPacket.class, packetId++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(S2CPlaylistsPacket::encode)
                .decoder(S2CPlaylistsPacket::new)
                .consumerMainThread(S2CPlaylistsPacket::handle)
                .add();

        // 歌单操作（客户端 -> 服务器）
        CHANNEL.messageBuilder(C2SPlaylistActionPacket.class, packetId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(C2SPlaylistActionPacket::encode)
                .decoder(C2SPlaylistActionPacket::new)
                .consumerMainThread(C2SPlaylistActionPacket::handle)
                .add();
    }

    /**
     * 向所有在线玩家广播包
     */
    public static <MSG> void sendToAllPlayers(MSG message) {
        CHANNEL.send(PacketDistributor.ALL.noArg(), message);
    }

    /**
     * 向指定玩家发送包
     */
    public static <MSG> void sendToPlayer(MSG message, net.minecraft.server.level.ServerPlayer player) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), message);
    }

    /**
     * 客户端向服务器发送包
     */
    public static <MSG> void sendToServer(MSG message) {
        CHANNEL.sendToServer(message);
    }
}