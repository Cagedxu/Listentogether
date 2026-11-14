package com.musicmod.server;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;

/** 每个存档独立保存的歌曲播放次数。 */
public class PlayCountData extends SavedData {
    private static final String DATA_NAME = "musicmod_play_counts";

    private final Map<String, Integer> counts = new HashMap<>();

    public static PlayCountData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                PlayCountData::load, PlayCountData::new, DATA_NAME);
    }

    private static PlayCountData load(CompoundTag tag) {
        PlayCountData data = new PlayCountData();
        for (String key : tag.getAllKeys()) {
            data.counts.put(key, Math.max(0, tag.getInt(key)));
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            tag.putInt(entry.getKey(), entry.getValue());
        }
        return tag;
    }

    public int get(String songName) {
        return counts.getOrDefault(songName, 0);
    }

    public int increment(String songName) {
        int next = get(songName) + 1;
        counts.put(songName, next);
        setDirty();
        return next;
    }

    public Map<String, Integer> snapshot() {
        return new HashMap<>(counts);
    }
}
