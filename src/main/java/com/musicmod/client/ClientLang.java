package com.musicmod.client;

import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.Locale;

/** 客户端语言判断：直接读取游戏当前语言，切换语言后立即生效。 */
@OnlyIn(Dist.CLIENT)
public final class ClientLang {

    private static String cachedCode = "";
    private static String currentCode = "en_us";
    private static boolean cachedEnglish = true;

    private ClientLang() {}

    public static String getCode() {
        try {
            String code = Minecraft.getInstance().getLanguageManager().getSelected();
            if (code == null || code.isBlank()) code = "en_us";
            if (!code.equals(cachedCode)) {
                cachedCode = code;
                currentCode = code.toLowerCase(Locale.ROOT).replace('-', '_');
                cachedEnglish = currentCode.startsWith("en");
            }
            return currentCode;
        } catch (Throwable ignored) {
            return "en_us";
        }
    }

    public static boolean isEnglish() {
        return getCode().startsWith("en");
    }
}
