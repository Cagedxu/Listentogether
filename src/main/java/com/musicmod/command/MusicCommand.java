package com.musicmod.command;

import com.musicmod.i18n.Lang;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.musicmod.MusicMod;
import com.musicmod.audio.MusicLibrary;
import com.musicmod.network.NetworkHandler;
import com.musicmod.network.packet.S2CSetVolumePacket;
import com.musicmod.playlist.Playlist;
import com.musicmod.playlist.PlaylistLibrary;
import com.musicmod.server.MusicServerManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * /music 命令系统。
 *
 * 所有人可用：
 *   /music list [页码]        - 查看歌曲列表
 *   /music nowplaying (np)    - 查看当前播放状态
 *   /music volume <0-100>     - 设置个人音量
 *   /music playlist           - 查看服务器歌单
 *   /music playlist show <名> - 查看歌单内容
 *
 * OP（等级2）可用：
 *   /music play <歌名>        - 播放指定歌曲
 *   /music stop               - 停止播放
 *   /music pause              - 暂停/恢复
 *   /music next               - 下一首
 *   /music prev               - 上一首
 *   /music shuffle            - 切换随机播放
 *   /music autoplay           - 切换自动连播
 *   /music queue              - 查看播放队列
 *   /music playlist play/create/rename/delete/add/remove - 歌单管理
 *   /music rescan             - 重新扫描音乐文件夹
 */
public class MusicCommand {

    /**
     * 歌名 Tab 补全
     */
    private static final SuggestionProvider<CommandSourceStack> SONG_SUGGESTIONS =
            (context, builder) -> {
                MusicLibrary library = MusicServerManager.getInstance().getMusicLibrary();
                if (library != null) {
                    library.scan();
                    // 补全显示标题（标签标题优先，无标签时为文件名去扩展名）
                    List<String> titles = new java.util.ArrayList<>();
                    for (String name : library.getSongNames()) {
                        titles.add(library.getTags(name).getDisplayTitle());
                    }
                    return SharedSuggestionProvider.suggest(titles, builder);
                }
                return SharedSuggestionProvider.suggest(List.of(), builder);
            };

    /**
     * 歌单名 Tab 补全（用于 greedyString 参数，名称可含空格）
     */
    private static final SuggestionProvider<CommandSourceStack> PLAYLIST_SUGGESTIONS =
            (context, builder) -> {
                PlaylistLibrary library = MusicServerManager.getInstance().getPlaylistLibrary();
                if (library == null) return SharedSuggestionProvider.suggest(List.of(), builder);
                List<String> names = new ArrayList<>();
                for (Playlist playlist : library.getAll()) {
                    names.add(playlist.getName());
                }
                return SharedSuggestionProvider.suggest(names, builder);
            };

    /** 歌单名 Tab 补全（用于 string 参数，需加引号） */
    private static final SuggestionProvider<CommandSourceStack> PLAYLIST_QUOTED_SUGGESTIONS =
            (context, builder) -> {
                PlaylistLibrary library = MusicServerManager.getInstance().getPlaylistLibrary();
                if (library == null) return SharedSuggestionProvider.suggest(List.of(), builder);
                List<String> names = new ArrayList<>();
                for (Playlist playlist : library.getAll()) {
                    names.add(quoteSuggestion(playlist.getName()));
                }
                return SharedSuggestionProvider.suggest(names, builder);
            };

    /** Brigadier 字符串参数建议需要带引号，内部引号与反斜杠转义 */
    private static String quoteSuggestion(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("music")
            // ---- 所有人可用 ----
            .then(Commands.literal("list")
                .executes(context -> listSongs(context, 1))
                .then(Commands.argument("page", IntegerArgumentType.integer(1))
                    .executes(context -> listSongs(context, IntegerArgumentType.getInteger(context, "page")))))
            .then(Commands.literal("nowplaying")
                .executes(MusicCommand::showNowPlaying))
            .then(Commands.literal("np")
                .executes(MusicCommand::showNowPlaying))
            .then(Commands.literal("volume")
                .then(Commands.argument("level", IntegerArgumentType.integer(0, 100))
                    .executes(MusicCommand::setVolume)))
            .then(Commands.literal("help")
                .executes(MusicCommand::showHelp))
            // ---- OP 可用 ----
            .then(Commands.literal("play")
                .requires(source -> source.hasPermission(2))
                .then(Commands.argument("song", StringArgumentType.greedyString())
                    .suggests(SONG_SUGGESTIONS)
                    .executes(MusicCommand::playSong)))
            .then(Commands.literal("stop")
                .requires(source -> source.hasPermission(2))
                .executes(MusicCommand::stopMusic))
            .then(Commands.literal("pause")
                .requires(source -> source.hasPermission(2))
                .executes(MusicCommand::togglePause))
            .then(Commands.literal("next")
                .requires(source -> source.hasPermission(2))
                .executes(MusicCommand::playNext))
            .then(Commands.literal("prev")
                .requires(source -> source.hasPermission(2))
                .executes(MusicCommand::playPrev))
            .then(Commands.literal("mode")
                .requires(source -> source.hasPermission(2))
                .executes(MusicCommand::cyclePlayMode))
            .then(Commands.literal("queue")
                .requires(source -> source.hasPermission(2))
                .executes(MusicCommand::showQueue))
            // ---- 歌单 ----
            .then(Commands.literal("playlist")
                .executes(MusicCommand::listPlaylists)
                .then(Commands.literal("list")
                    .executes(MusicCommand::listPlaylists))
                .then(Commands.literal("show")
                    .then(Commands.argument("name", StringArgumentType.greedyString())
                        .suggests(PLAYLIST_SUGGESTIONS)
                        .executes(MusicCommand::showPlaylist)))
                .then(Commands.literal("play")
                    .requires(source -> source.hasPermission(2))
                    .then(Commands.argument("name", StringArgumentType.greedyString())
                        .suggests(PLAYLIST_SUGGESTIONS)
                        .executes(MusicCommand::playPlaylist)))
                .then(Commands.literal("create")
                    .requires(source -> source.hasPermission(2))
                    .then(Commands.argument("name", StringArgumentType.greedyString())
                        .executes(MusicCommand::createPlaylist)))
                .then(Commands.literal("delete")
                    .requires(source -> source.hasPermission(2))
                    .then(Commands.argument("name", StringArgumentType.greedyString())
                        .suggests(PLAYLIST_SUGGESTIONS)
                        .executes(MusicCommand::deletePlaylist)))
                .then(Commands.literal("rename")
                    .requires(source -> source.hasPermission(2))
                    .then(Commands.argument("name", StringArgumentType.string())
                        .suggests(PLAYLIST_QUOTED_SUGGESTIONS)
                        .then(Commands.argument("newName", StringArgumentType.greedyString())
                            .executes(MusicCommand::renamePlaylist))))
                .then(Commands.literal("add")
                    .requires(source -> source.hasPermission(2))
                    .then(Commands.argument("name", StringArgumentType.string())
                        .suggests(PLAYLIST_QUOTED_SUGGESTIONS)
                        .then(Commands.argument("song", StringArgumentType.greedyString())
                            .suggests(SONG_SUGGESTIONS)
                            .executes(MusicCommand::addSongToPlaylist))))
                .then(Commands.literal("remove")
                    .requires(source -> source.hasPermission(2))
                    .then(Commands.argument("name", StringArgumentType.string())
                        .suggests(PLAYLIST_QUOTED_SUGGESTIONS)
                        .then(Commands.argument("song", StringArgumentType.greedyString())
                            .suggests(SONG_SUGGESTIONS)
                            .executes(MusicCommand::removeSongFromPlaylist)))))
            .then(Commands.literal("rescan")
                .requires(source -> source.hasPermission(2))
                .executes(MusicCommand::rescan))
            // 默认执行：显示帮助
            .executes(MusicCommand::showHelp)
        );
    }

    // ==================== 命令实现 ====================

    /**
     * 列出歌曲（分页，每页10首）
     */
    private static int listSongs(CommandContext<CommandSourceStack> context, int page) {
        CommandSourceStack source = context.getSource();
        MusicLibrary library = MusicServerManager.getInstance().getMusicLibrary();
        if (library == null) {
            source.sendFailure(Component.literal(Lang.tr("§c音乐库未初始化")));
            return 0;
        }
        library.scan();
        List<String> songs = library.getSongNames();
        if (songs.isEmpty()) {
            source.sendSuccess(() -> Component.literal(
                    Lang.tr("§e音乐文件夹中没有歌曲。请将音乐文件放入: §f") +
                    library.getSongsDir().getAbsolutePath()), false);
            return 0;
        }

        int perPage = 10;
        int totalPages = (songs.size() + perPage - 1) / perPage;
        if (page > totalPages) page = totalPages;

        int start = (page - 1) * perPage;
        int end = Math.min(start + perPage, songs.size());

        final int finalPage = page;
        final int finalTotalPages = totalPages;
        source.sendSuccess(() -> Component.literal(
                Lang.tr("§6♪ §e歌曲列表 §7(") + songs.size() + Lang.tr("首) §f- 第") + finalPage + "/" + finalTotalPages + Lang.tr("页")), false);
        for (int i = start; i < end; i++) {
            String song = songs.get(i);
            String displayTitle = library.getTags(song).getDisplayTitle();
            String prefix = "§7" + String.format("%2d. ", i + 1);
            String current = MusicServerManager.getInstance().getCurrentSong();
            if (song.equals(current)) {
                source.sendSuccess(() -> Component.literal(prefix + "§a§l" + displayTitle + Lang.tr(" §r§a♪ 正在播放")), false);
            } else {
                final String title = displayTitle;
                source.sendSuccess(() -> Component.literal(prefix + "§f" + title), false);
            }
        }
        if (finalPage < finalTotalPages) {
            source.sendSuccess(() -> Component.literal(
                    Lang.tr("§7输入 /music list ") + (finalPage + 1) + Lang.tr(" 查看下一页")), false);
        }
        return songs.size();
    }

    /**
     * 播放歌曲
     */
    private static int playSong(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        String songQuery = StringArgumentType.getString(context, "song").trim();
        boolean success = MusicServerManager.getInstance().playMusic(songQuery);
        if (!success) {
            source.sendFailure(Component.literal(Lang.tr("§c未找到歌曲: §f") + songQuery +
                    Lang.tr("§c。使用 /music list 查看可用歌曲。")));
            return 0;
        }
        return 1;
    }

    /**
     * 停止播放
     */
    private static int stopMusic(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        if (!MusicServerManager.getInstance().isPlaying()) {
            source.sendFailure(Component.literal(Lang.tr("§c当前没有正在播放的歌曲")));
            return 0;
        }
        MusicServerManager.getInstance().stopMusic();
        return 1;
    }

    /**
     * 暂停/恢复
     */
    private static int togglePause(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        if (!MusicServerManager.getInstance().togglePause()) {
            source.sendFailure(Component.literal(Lang.tr("§c当前没有正在播放的歌曲")));
            return 0;
        }
        return 1;
    }

    /**
     * 下一首
     */
    private static int playNext(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        if (!MusicServerManager.getInstance().playNext()) {
            source.sendFailure(Component.literal(Lang.tr("§c播放队列为空，无法切换下一首")));
            return 0;
        }
        return 1;
    }

    /**
     * 上一首
     */
    private static int playPrev(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        if (!MusicServerManager.getInstance().playPrevious()) {
            source.sendFailure(Component.literal(Lang.tr("§c播放队列为空，无法切换上一首")));
            return 0;
        }
        return 1;
    }

    /**
     * 切换播放模式（顺序 -> 单曲循环 -> 随机）
     */
    private static int cyclePlayMode(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        MusicServerManager mgr = MusicServerManager.getInstance();
        com.musicmod.PlayMode mode = mgr.cyclePlayMode();
        source.sendSuccess(() -> Component.literal(
                Lang.tr("§6♪ 播放模式: ") + mode.getSymbol() + " §a" + mode.getDisplayName()), true);
        return 1;
    }

    /**
     * 显示播放队列
     */
    private static int showQueue(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        MusicServerManager mgr = MusicServerManager.getInstance();
        List<String> queue = mgr.getQueue();
        if (queue.isEmpty()) {
            source.sendSuccess(() -> Component.literal(Lang.tr("§7播放队列为空。播放任意歌曲会自动加入队列。")), false);
            return 0;
        }
        com.musicmod.PlayMode pm = mgr.getPlayMode();
        source.sendSuccess(() -> Component.literal(
                Lang.tr("§6♪ §e播放队列 §7(") + queue.size() + Lang.tr("首) §a[") + pm.getSymbol() + " " + pm.getDisplayName() + "]"), false);
        MusicLibrary lib = mgr.getMusicLibrary();
        for (int i = 0; i < queue.size() && i < 20; i++) {
            String song = queue.get(i);
            String displayTitle = lib != null ? lib.getTags(song).getDisplayTitle() : song;
            String prefix = "§7" + String.format("%2d. ", i + 1);
            if (song.equals(mgr.getCurrentSong())) {
                source.sendSuccess(() -> Component.literal(prefix + "§a§l" + displayTitle + " §r§a♪"), false);
            } else {
                final String title = displayTitle;
                source.sendSuccess(() -> Component.literal(prefix + "§f" + title), false);
            }
        }
        if (queue.size() > 20) {
            source.sendSuccess(() -> Component.literal(Lang.tr("§7... 还有 ") + (queue.size() - 20) + Lang.tr(" 首")), false);
        }
        return queue.size();
    }

    // ==================== 歌单命令实现 ====================

    /** 按名称查找歌单（忽略大小写） */
    private static Playlist findPlaylist(String name) {
        PlaylistLibrary library = MusicServerManager.getInstance().getPlaylistLibrary();
        return library == null ? null : library.findByName(name);
    }

    /** 列出所有歌单（所有人可用） */
    private static int listPlaylists(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        PlaylistLibrary library = MusicServerManager.getInstance().getPlaylistLibrary();
        if (library == null) {
            source.sendFailure(Component.literal(Lang.tr("§c歌单系统未初始化")));
            return 0;
        }
        List<Playlist> playlists = library.getAll();
        if (playlists.isEmpty()) {
            source.sendSuccess(() -> Component.literal(
                    Lang.tr("§7还没有任何歌单。") + (source.hasPermission(2)
                            ? Lang.tr("使用 §f/music playlist create <歌单名> §7创建。") : "")), false);
            return 0;
        }
        source.sendSuccess(() -> Component.literal(
                Lang.tr("§6♪ §e服务器歌单 §7(") + playlists.size() + Lang.tr(" 个)")), false);
        for (int i = 0; i < playlists.size(); i++) {
            Playlist playlist = playlists.get(i);
            String prefix = "§7" + String.format("%2d. ", i + 1);
            source.sendSuccess(() -> Component.literal(
                    prefix + "§f" + playlist.getName()
                            + " §8- §7" + playlist.size() + Lang.tr(" 首")), false);
        }
        source.sendSuccess(() -> Component.literal(
                Lang.tr("§7使用 §f/music playlist show <歌单名> §7查看内容；")
                        + Lang.tr("§f/music playlist play <歌单名> §7播放（OP）")), false);
        return playlists.size();
    }

    /** 显示歌单内容（所有人可用） */
    private static int showPlaylist(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        String name = StringArgumentType.getString(context, "name").trim();
        Playlist playlist = findPlaylist(name);
        if (playlist == null) {
            source.sendFailure(Component.literal(Lang.tr("§c歌单不存在: §f") + name
                    + Lang.tr("§c。使用 /music playlist list 查看所有歌单。")));
            return 0;
        }
        MusicLibrary library = MusicServerManager.getInstance().getMusicLibrary();
        source.sendSuccess(() -> Component.literal(
                Lang.tr("§6♪ §e歌单: §f") + playlist.getName()
                        + " §7(" + playlist.size() + Lang.tr(" 首)")), false);
        if (playlist.isEmpty()) {
            source.sendSuccess(() -> Component.literal(
                    Lang.tr("§7歌单还是空的。") + (source.hasPermission(2)
                            ? Lang.tr("使用 §f/music playlist add \"") + playlist.getName() + Lang.tr("\" <歌曲> §7添加。")
                            : "")), false);
            return 0;
        }
        int limit = Math.min(playlist.size(), 30);
        for (int i = 0; i < limit; i++) {
            String song = playlist.getSongs().get(i);
            boolean exists = library != null && library.getSongMap().containsKey(song);
            String title = exists ? library.getTags(song).getDisplayTitle() : song;
            String suffix = exists ? "" : Lang.tr(" §c(文件缺失)");
            String prefix = "§7" + String.format("%2d. ", i + 1);
            source.sendSuccess(() -> Component.literal(
                    prefix + "§f" + title + suffix), false);
        }
        if (playlist.size() > limit) {
            source.sendSuccess(() -> Component.literal(
                    Lang.tr("§7... 还有 ") + (playlist.size() - limit) + Lang.tr(" 首")), false);
        }
        return playlist.size();
    }

    /** 新建歌单（OP） */
    private static int createPlaylist(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        String name = StringArgumentType.getString(context, "name");
        String error = MusicServerManager.getInstance().createPlaylist(name);
        if (error != null) {
            source.sendFailure(Component.literal("§c" + error));
            return 0;
        }
        String clean = PlaylistLibrary.sanitizeName(name);
        source.sendSuccess(() -> Component.literal(
                Lang.tr("§6♪ §a歌单已创建: §f") + clean), true);
        return 1;
    }

    /** 重命名歌单（OP） */
    private static int renamePlaylist(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        String name = StringArgumentType.getString(context, "name").trim();
        String newName = StringArgumentType.getString(context, "newName");
        Playlist playlist = findPlaylist(name);
        if (playlist == null) {
            source.sendFailure(Component.literal(Lang.tr("§c歌单不存在: §f") + name));
            return 0;
        }
        String error = MusicServerManager.getInstance().renamePlaylist(playlist.getId(), newName);
        if (error != null) {
            source.sendFailure(Component.literal("§c" + error));
            return 0;
        }
        String clean = PlaylistLibrary.sanitizeName(newName);
        source.sendSuccess(() -> Component.literal(
                Lang.tr("§6♪ §a歌单已重命名为: §f") + clean), true);
        return 1;
    }

    /** 删除歌单（OP） */
    private static int deletePlaylist(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        String name = StringArgumentType.getString(context, "name").trim();
        Playlist playlist = findPlaylist(name);
        if (playlist == null) {
            source.sendFailure(Component.literal(Lang.tr("§c歌单不存在: §f") + name));
            return 0;
        }
        String error = MusicServerManager.getInstance().deletePlaylist(playlist.getId());
        if (error != null) {
            source.sendFailure(Component.literal("§c" + error));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(
                Lang.tr("§6♪ §a歌单已删除: §f") + playlist.getName()), true);
        return 1;
    }

    /** 添加歌曲到歌单（OP，支持模糊匹配歌曲） */
    private static int addSongToPlaylist(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        String name = StringArgumentType.getString(context, "name").trim();
        String song = StringArgumentType.getString(context, "song").trim();
        Playlist playlist = findPlaylist(name);
        if (playlist == null) {
            source.sendFailure(Component.literal(Lang.tr("§c歌单不存在: §f") + name));
            return 0;
        }
        String error = MusicServerManager.getInstance()
                .addSongToPlaylist(playlist.getId(), song);
        if (error != null) {
            source.sendFailure(Component.literal("§c" + error));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(
                Lang.tr("§6♪ §a已添加到歌单「§f") + playlist.getName() + "§a」: §f" + song), true);
        return 1;
    }

    /** 从歌单移除歌曲（OP） */
    private static int removeSongFromPlaylist(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        String name = StringArgumentType.getString(context, "name").trim();
        String song = StringArgumentType.getString(context, "song").trim();
        Playlist playlist = findPlaylist(name);
        if (playlist == null) {
            source.sendFailure(Component.literal(Lang.tr("§c歌单不存在: §f") + name));
            return 0;
        }
        String error = MusicServerManager.getInstance()
                .removeSongFromPlaylist(playlist.getId(), song);
        if (error != null) {
            source.sendFailure(Component.literal("§c" + error));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(
                Lang.tr("§6♪ §a已从歌单「§f") + playlist.getName() + Lang.tr("§a」移除: §f") + song), true);
        return 1;
    }

    /** 播放歌单（OP） */
    private static int playPlaylist(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        String name = StringArgumentType.getString(context, "name").trim();
        Playlist playlist = findPlaylist(name);
        if (playlist == null) {
            source.sendFailure(Component.literal(Lang.tr("§c歌单不存在: §f") + name));
            return 0;
        }
        String error = MusicServerManager.getInstance().playPlaylist(playlist.getId(), "");
        if (error != null) {
            source.sendFailure(Component.literal("§c" + error));
            return 0;
        }
        return 1;
    }

    // ==================== 其它命令 ====================

    /**
     * 重新扫描音乐文件夹
     */
    private static int rescan(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        MusicServerManager.getInstance().rescanLibrary();
        MusicLibrary library = MusicServerManager.getInstance().getMusicLibrary();
        int count = library != null ? library.getSongCount() : 0;
        source.sendSuccess(() -> Component.literal(
                Lang.tr("§6♪ §a重新扫描完成，共找到 §f") + count + Lang.tr(" §a首歌曲")), true);
        return count;
    }

    /**
     * 当前播放状态
     */
    private static int showNowPlaying(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        MusicServerManager mgr = MusicServerManager.getInstance();
        if (!mgr.isPlaying()) {
            source.sendSuccess(() -> Component.literal(Lang.tr("§7当前没有正在播放的歌曲")), false);
            return 0;
        }
        String song = mgr.getCurrentSong();
        long elapsed = mgr.getElapsedMs();
        String status = mgr.isPaused() ? Lang.tr("§e[已暂停]") : Lang.tr("§a[播放中]");
        String posStr = formatTime(elapsed);
        MusicLibrary lib = mgr.getMusicLibrary();
        String displayTitle = lib != null ? lib.getTags(song).getDisplayTitle() : song;
        source.sendSuccess(() -> Component.literal(
                "§6♪ " + status + " §f" + displayTitle + " §7(" + posStr + ")"), false);
        return 1;
    }

    /**
     * 设置个人音量（发送音量包到执行者的客户端，由客户端写入配置并应用）
     */
    private static int setVolume(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        int level = IntegerArgumentType.getInteger(context, "level");
        ServerPlayer player = source.getPlayer();
        if (player != null) {
            NetworkHandler.sendToPlayer(new S2CSetVolumePacket(level), player);
        } else {
            source.sendSuccess(() -> Component.literal(
                    Lang.tr("§6♪ §e个人音量已设置为: §f") + level + "%"), false);
        }
        return 1;
    }

    /**
     * 显示帮助
     */
    private static int showHelp(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        source.sendSuccess(() -> Component.literal(Lang.tr("§6===== §e一起听 §6=====")), false);
        source.sendSuccess(() -> Component.literal(Lang.tr("§e按 F8 键打开播放器界面 §7(可在控制设置中改键)")), false);
        source.sendSuccess(() -> Component.literal(Lang.tr("§f/music list [页] §7- 查看歌曲列表")), false);
        source.sendSuccess(() -> Component.literal(Lang.tr("§f/music np §7- 查看当前播放")), false);
        source.sendSuccess(() -> Component.literal(Lang.tr("§f/music volume <0-100> §7- 设置个人音量")), false);
        source.sendSuccess(() -> Component.literal(Lang.tr("§f/music playlist §7- 查看歌单 / §f/music playlist show <歌单>")), false);
        source.sendSuccess(() -> Component.literal(Lang.tr("§f/music play <歌名> §7- 播放歌曲 §8(OP)")), false);
        source.sendSuccess(() -> Component.literal(Lang.tr("§f/music stop §7- 停止播放 §8(OP)")), false);
        source.sendSuccess(() -> Component.literal(Lang.tr("§f/music pause §7- 暂停/恢复 §8(OP)")), false);
        source.sendSuccess(() -> Component.literal(Lang.tr("§f/music next §7- 下一首 §8(OP)")), false);
        source.sendSuccess(() -> Component.literal(Lang.tr("§f/music prev §7- 上一首 §8(OP)")), false);
        source.sendSuccess(() -> Component.literal(Lang.tr("§f/music mode §7- 切换播放模式 §8(OP)")), false);
        source.sendSuccess(() -> Component.literal(Lang.tr("§f/music queue §7- 查看队列 §8(OP)")), false);
        source.sendSuccess(() -> Component.literal(Lang.tr("§f/music playlist play <歌单> §7- 播放歌单 §8(OP)")), false);
        source.sendSuccess(() -> Component.literal(Lang.tr("§f/music playlist create/delete <歌单> §7- 新建/删除歌单 §8(OP)")), false);
        source.sendSuccess(() -> Component.literal(Lang.tr("§f/music playlist add/remove <歌单> <歌曲> §7- 增删歌曲 §8(OP)")), false);
        source.sendSuccess(() -> Component.literal(Lang.tr("§f/music rescan §7- 重新扫描 §8(OP)")), false);
        return 1;
    }

    /**
     * 格式化时间（毫秒 -> mm:ss）
     */
    private static String formatTime(long ms) {
        long totalSeconds = ms / 1000;
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        return String.format("%d:%02d", minutes, seconds);
    }
}