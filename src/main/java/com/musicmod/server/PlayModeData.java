package com.musicmod.server;

import com.musicmod.PlayMode;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/** Per-save persistence for the active playback mode. */
public final class PlayModeData extends SavedData {
    private static final String DATA_NAME = "musicmod_play_mode";
    private static final String MODE_TAG = "mode";

    private PlayMode mode;

    public PlayModeData() {
    }

    public PlayModeData(PlayMode mode) {
        this.mode = mode;
    }

    public static PlayModeData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                PlayModeData::load, PlayModeData::new, DATA_NAME);
    }

    public static PlayModeData load(CompoundTag tag) {
        PlayMode mode = tag.contains(MODE_TAG)
                ? PlayMode.fromName(tag.getString(MODE_TAG))
                : null;
        return new PlayModeData(mode);
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        if (mode != null) {
            tag.putString(MODE_TAG, mode.name());
        }
        return tag;
    }

    public PlayMode getMode(PlayMode fallback) {
        return mode == null ? fallback : mode;
    }

    public void setMode(PlayMode mode) {
        if (this.mode != mode) {
            this.mode = mode;
            setDirty();
        }
    }
}
