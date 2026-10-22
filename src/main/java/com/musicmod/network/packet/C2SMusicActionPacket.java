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
 * 客户端 -> 服务器：播放器 GUI 发出的控制指令。
 * OP 权限校验在服务端进行，防止非 OP 玩家通过伪造包控制播放。
 */
public class C2SMusicActionPacket {
    public static final byte ACTION_PLAY = 0;
    public static final byte ACTION_STOP = 1;
    public static final byte ACTION_PAUSE = 2;
    public static final byte ACTION_NEXT = 3;
    public static final byte ACTION_PREV = 4;
    public static final byte ACTION_RESCAN = 7;
    public static final byte ACTION_SEEK = 8; // 1.4.0：进度条拖动跳转
    public static final byte ACTION_PLAY_MODE = 9; // 1.7.0：切换播放模式
    public static final byte ACTION_PLAY_WITH_QUEUE = 10; // 0.14.0：带播放队列播放（歌单/歌手/专辑上下文）
    public static final byte ACTION_SET_EMPTY_BEHAVIOR = 11; // 0.25.0：OP 设置服务器无人时行为

    private final byte action;
    private final String songName; // ACTION_PLAY / ACTION_PLAY_WITH_QUEUE 使用
    private final long seekMs;     // 仅 ACTION_SEEK 使用
    private final List<String> queue; // 仅 ACTION_PLAY_WITH_QUEUE 使用：播放队列歌曲名列表

    public C2SMusicActionPacket(byte action, String songName) {
        this(action, songName, 0);
    }

    public C2SMusicActionPacket(byte action, String songName, long seekMs) {
        this.action = action;
        this.songName = songName == null ? "" : songName;
        this.seekMs = seekMs;
        this.queue = null;
    }

    /** 带播放队列的播放指令（从歌单/歌手/专辑等上下文播放时使用） */
    public C2SMusicActionPacket(byte action, String songName, List<String> queue) {
        this.action = action;
        this.songName = songName == null ? "" : songName;
        this.seekMs = 0;
        this.queue = queue;
    }

    public C2SMusicActionPacket(FriendlyByteBuf buf) {
        this.action = buf.readByte();
        this.songName = buf.readUtf(256);
        this.seekMs = buf.readLong();
        if (buf.isReadable()) {
            int size = buf.readVarInt();
            this.queue = new ArrayList<>(Math.min(size, 512));
            for (int i = 0; i < size; i++) {
                this.queue.add(buf.readUtf(256));
            }
        } else {
            this.queue = null;
        }
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeByte(action);
        buf.writeUtf(songName, 256);
        buf.writeLong(seekMs);
        if (queue != null) {
            buf.writeVarInt(Math.min(queue.size(), 512));
            for (int i = 0; i < Math.min(queue.size(), 512); i++) {
                buf.writeUtf(queue.get(i), 256);
            }
        } else {
            buf.writeVarInt(0);
        }
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;
            MusicServerManager mgr = MusicServerManager.getInstance();
            // 控制类操作需要 OP（等级2）
            boolean isOp = player.hasPermissions(2);
            switch (action) {
                case ACTION_PLAY:
                    if (isOp && !songName.isEmpty()) mgr.playMusic(songName);
                    break;
                case ACTION_PLAY_WITH_QUEUE:
                    if (isOp && !songName.isEmpty()) mgr.playMusicWithQueue(songName, queue);
                    break;
                case ACTION_STOP:
                    if (isOp) mgr.stopMusic();
                    break;
                case ACTION_PAUSE:
                    if (isOp) mgr.togglePause();
                    break;
                case ACTION_NEXT:
                    if (isOp) mgr.playNext();
                    break;
                case ACTION_PREV:
                    if (isOp) mgr.playPrevious();
                    break;
                case ACTION_PLAY_MODE:
                    if (isOp) mgr.cyclePlayMode();
                    break;
                case ACTION_RESCAN:
                    if (isOp) mgr.rescanLibrary();
                    break;
                case ACTION_SEEK:
                    if (isOp && seekMs >= 0) mgr.seekTo(seekMs);
                    break;
                case ACTION_SET_EMPTY_BEHAVIOR:
                    if (isOp && !songName.isEmpty()) {
                        com.musicmod.EmptyServerBehavior behavior =
                                com.musicmod.EmptyServerBehavior.fromName(songName);
                        if (behavior != null) mgr.setEmptyBehavior(behavior);
                    }
                    break;
                default:
                    MusicMod.LOGGER.warn("Unknown music action {} from {}",
                            action, player.getName().getString());
            }
        });
        ctx.get().setPacketHandled(true);
    }
}