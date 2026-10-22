package com.musicmod.client;

import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.Locale;

/** 客户端语言判断：直接读取游戏当前语言，切换语言后立即生效。 */
@OnlyIn(Dist.CLIENT)
public final class ClientLang {

    private static String cachedCode = "";
    private static boolean cachedEnglish = true;

    private ClientLang() {}

    public static boolean isEnglish() {
        try {
            String code = Minecraft.getInstance().getLanguageManager().getSelected();
            if (code == null) code = "";
            if (!code.equals(cachedCode)) {
                cachedCode = code;
                cachedEnglish = code.toLowerCase(Locale.ROOT).startsWith("en");
            }
            return cachedEnglish;
        } catch (Throwable ignored) {
            return true;
        }
    }
}
