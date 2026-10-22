package com.musicmod.network.packet;

import com.musicmod.MusicMod;
import com.musicmod.server.MusicServerManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 客户端 -> 服务器：歌单操作（GUI 与命令共用的底层接口）。
 * <p>
 * 所有修改与播放操作均要求 OP（等级 2），在服务端统一校验，
 * 防止非 OP 玩家伪造包修改服务器歌单或控制全服播放。
 */
public class C2SPlaylistActionPacket {

    public static final byte ACTION_CREATE = 0;
    public static final byte ACTION_RENAME = 1;
    public static final byte ACTION_DELETE = 2;
    public static final byte ACTION_ADD_SONG = 3;
    public static final byte ACTION_REMOVE_SONG = 4;
    public static final byte ACTION_PLAY = 5;
    public static final byte ACTION_PLAY_SONG = 6;
    public static final byte ACTION_ADD_SONGS = 7; // 批量添加（勾选歌曲后点「确定」）

    private final byte action;
    /** 目标歌单 ID（CREATE 时为空） */
    private final String playlistId;
    /** 名称（CREATE/RENAME）或歌曲名（ADD/REMOVE/PLAY_SONG） */
    private final String value;
    /** 批量添加的歌曲列表（仅 ACTION_ADD_SONGS 使用） */
    private final List<String> songs;

    public C2SPlaylistActionPacket(byte action, String playlistId, String value) {
        this.action = action;
        this.playlistId = playlistId == null ? "" : playlistId;
        this.value = value == null ? "" : value;
        this.songs = null;
    }

    /** 批量添加歌曲（歌单内勾选多首后点「确定」） */
    public C2SPlaylistActionPacket(String playlistId, List<String> songs) {
        this.action = ACTION_ADD_SONGS;
        this.playlistId = playlistId == null ? "" : playlistId;
        this.value = "";
        this.songs = songs == null ? List.of() : new ArrayList<>(songs);
    }

    public C2SPlaylistActionPacket(FriendlyByteBuf buf) {
        this.action = buf.readByte();
        this.playlistId = buf.readUtf(64);
        this.value = buf.readUtf(256);
        if (buf.isReadable()) {
            int count = Math.min(buf.readVarInt(), 512);
            List<String> list = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                list.add(buf.readUtf(256));
            }
            this.songs = list;
        } else {
            this.songs = null;
        }
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeByte(action);
        buf.writeUtf(playlistId, 64);
        buf.writeUtf(value, 256);
        if (songs != null) {
            int count = Math.min(songs.size(), 512);
            buf.writeVarInt(count);
            for (int i = 0; i < count; i++) {
                buf.writeUtf(songs.get(i), 256);
            }
        } else {
            buf.writeVarInt(0);
        }
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;
            MusicServerManager manager = MusicServerManager.getInstance();

            if (!player.hasPermissions(2)) {
                manager.sendMessageTo(player, "§c[一起听] 需要管理员权限才能管理歌单");
                return;
            }

            String error;
            String success = null;
            try {
                switch (action) {
                    case ACTION_CREATE:
                        error = manager.createPlaylist(value);
                        success = "§6♪ §a歌单已创建";
                        break;
                    case ACTION_RENAME:
                        error = manager.renamePlaylist(playlistId, value);
                        success = "§6♪ §a歌单已重命名";
                        break;
                    case ACTION_DELETE:
                        error = manager.deletePlaylist(playlistId);
                        success = "§6♪ §a歌单已删除";
                        break;
                    case ACTION_ADD_SONG:
                        error = manager.addSongToPlaylist(playlistId, value);
                        success = "§6♪ §a已添加到歌单";
                        break;
                    case ACTION_ADD_SONGS:
                        error = manager.addSongsToPlaylist(playlistId, songs);
                        success = "§6♪ §a已添加所选歌曲";
                        break;
                    case ACTION_REMOVE_SONG:
                        error = manager.removeSongFromPlaylist(playlistId, value);
                        success = "§6♪ §a已从歌单移除";
                        break;
                    case ACTION_PLAY:
                    case ACTION_PLAY_SONG:
                        error = manager.playPlaylist(playlistId, value);
                        break;
                    default:
                        MusicMod.LOGGER.warn("Unknown playlist action {} from {}",
                                action, player.getName().getString());
                        return;
                }
            } catch (Exception e) {
                MusicMod.LOGGER.error("Failed to handle playlist action {}", action, e);
                error = "操作失败: " + e.getMessage();
            }

            if (error != null) {
                manager.sendMessageTo(player, "§c[一起听] " + error);
            } else if (success != null) {
                manager.sendMessageTo(player, success);
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
