package com.musicmod.client;

import com.musicmod.MusicMod;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 本地收藏歌单（客户端存储，不参与服务器同步）。
 * 持久化为 musicmod_cache/favorites.json（简单行格式，每行一个歌曲名）。
 */
public class ClientFavorites {
    private static final Set<String> favorites = new LinkedHashSet<>();
    private static boolean loaded = false;

    private static Path getFile() {
        Path dir = Minecraft.getInstance().gameDirectory.toPath().resolve("musicmod_cache");
        return dir.resolve("favorites.json");
    }

    private static void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        try {
            Path file = getFile();
            if (Files.exists(file)) {
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (String line : lines) {
                    String s = line.trim();
                    if (!s.isEmpty()) favorites.add(s);
                }
            }
        } catch (Exception e) {
            MusicMod.LOGGER.warn("Failed to load favorites: " + e);
        }
    }

    public static boolean isFavorite(String songName) {
        ensureLoaded();
        return favorites.contains(songName);
    }

    public static void toggle(String songName) {
        ensureLoaded();
        if (songName == null || songName.isEmpty()) return;
        if (!favorites.remove(songName)) {
            favorites.add(songName);
        }
        save();
    }

    public static void add(String songName) {
        ensureLoaded();
        if (songName == null || songName.isEmpty()) return;
        favorites.add(songName);
        save();
    }

    /** 批量添加（收藏歌单勾选后点「确定」时一次落盘） */
    public static void addAll(Iterable<String> songNames) {
        ensureLoaded();
        if (songNames == null) return;
        boolean changed = false;
        for (String songName : songNames) {
            if (songName != null && !songName.isEmpty() && favorites.add(songName)) {
                changed = true;
            }
        }
        if (changed) save();
    }

    public static void remove(String songName) {
        ensureLoaded();
        favorites.remove(songName);
        save();
    }

    public static List<String> getFavorites() {
        ensureLoaded();
        return new ArrayList<>(favorites);
    }

    public static void save() {
        try {
            Path file = getFile();
            Files.createDirectories(file.getParent());
            StringBuilder sb = new StringBuilder();
            for (String s : favorites) {
                sb.append(s.replace('\n', ' ')).append('\n');
            }
            Files.write(file, sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            MusicMod.LOGGER.warn("Failed to save favorites: " + e);
        }
    }
}