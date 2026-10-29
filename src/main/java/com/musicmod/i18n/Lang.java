package com.musicmod.i18n;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.musicmod.client.ClientLang;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 客户端本地化。
 * 资源文件以中文原文作为 key；en_us 为英文，zh_tw / ja_jp / ko_kr / ru_ru / fr_fr
 * 为对应语言。缺失条目回退英文，再回退中文原文。
 */
public final class Lang {

    private static final Map<String, String> EN = new HashMap<>();
    private static final Map<String, Map<String, String>> LOCALES = new HashMap<>();

    static {
        load("en_us", EN);
        load("zh_tw", new HashMap<>());
        load("ja_jp", new HashMap<>());
        load("ko_kr", new HashMap<>());
        load("ru_ru", new HashMap<>());
        load("fr_fr", new HashMap<>());
    }

    private Lang() {}

    private static void load(String code, Map<String, String> target) {
        try (InputStream in = Lang.class.getResourceAsStream(
                "/assets/musicmod/lang/" + code + ".json")) {
            if (in == null) return;
            JsonObject obj = JsonParser.parseReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
                if (e.getValue().isJsonPrimitive() && e.getValue().getAsJsonPrimitive().isString()) {
                    target.put(e.getKey(), e.getValue().getAsString());
                }
            }
            if (!code.equals("en_us")) LOCALES.put(code, target);
        } catch (Exception ignored) {
        }
    }

    public static String tr(String text) {
        if (text == null || text.isEmpty()) return text;
        String code = languageCode();
        if (code.equals("zh_cn") || code.startsWith("zh_")) {
            // 简体中文直接使用原文；繁体中文使用 zh_tw 映射。
            if (code.equals("zh_tw") || code.equals("zh_hk")) {
                Map<String, String> map = LOCALES.get("zh_tw");
                String translated = map == null ? null : map.get(text);
                return translated != null ? translated : text;
            }
            return text;
        }
        Map<String, String> map = LOCALES.get(code);
        if (map != null) {
            String translated = map.get(text);
            if (translated != null) return translated;
        }
        String english = EN.get(text);
        return english != null ? english : text;
    }

    private static String languageCode() {
        String code = DistExecutor.unsafeCallWhenOn(Dist.CLIENT, () -> ClientLang::getCode);
        if (code == null || code.isBlank()) return "en_us";
        return code.toLowerCase(Locale.ROOT).replace('-', '_');
    }
}
