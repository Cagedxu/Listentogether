package com.musicmod.server;

import com.musicmod.audio.SongTags;
import net.minecraft.advancements.Advancement;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.Locale;

/** 狼音アロ 相关歌曲的隐藏成就。 */
public final class WolfArtistAchievement {
    private static final ResourceLocation ID =
            new ResourceLocation("musicmod", "wolf_artist");
    private static final String[] ALIASES = {
            "狼音アロ", "狼音阿罗", "rouon aro", "狼音", "了口", "ろうおん あろ"
    };

    private WolfArtistAchievement() {
    }

    public static boolean matchesArtist(String artist) {
        if (artist == null || artist.isBlank()) return false;
        String value = normalize(artist);
        for (String alias : ALIASES) {
            if (value.contains(normalize(alias))) return true;
        }
        return false;
    }

    public static void check(ServerPlayer player, SongTags tags) {
        if (player == null || tags == null) return;
        if (!matchesArtist(tags.artist)) return;
        Advancement advancement = player.server.getAdvancements().getAdvancement(ID);
        if (advancement != null) {
            player.getAdvancements().award(advancement, "impossible");
        }
    }

    private static String normalize(String text) {
        return text.toLowerCase(Locale.ROOT)
                .replace("\u3000", "")
                .replace(" ", "")
                .replace("・", "")
                .replace("-", "")
                .replace("_", "");
    }
}
