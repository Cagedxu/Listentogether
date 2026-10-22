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
import java.util.Map;

/**
 * 英文文本适配。
 * <p>
 * 资源文件 en_us.json 以中文原文作为 key，值为英文；这样代码里保留中文原文，
 * 英文客户端通过 tr() 取英文，中文客户端保持原文。
 * 服务端没有客户端语言信息，默认按英文输出（本版本只要求英语适配）。
 */
public final class Lang {

    private static final Map<String, String> EN = new HashMap<>();

    static {
        try (InputStream in = Lang.class.getResourceAsStream("/assets/musicmod/lang/en_us.json")) {
            if (in != null) {
                JsonObject obj = JsonParser.parseReader(
                        new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
                for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
                    if (e.getValue().isJsonPrimitive() && e.getValue().getAsJsonPrimitive().isString()) {
                        EN.put(e.getKey(), e.getValue().getAsString());
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    private Lang() {}

    public static String tr(String text) {
        if (text == null || text.isEmpty() || !isEnglish()) return text;
        String translated = EN.get(text);
        return translated != null ? translated : text;
    }

    private static boolean isEnglish() {
        // 每次读取当前客户端语言，切换语言后无需重启；专用服务器返回 null，按英文。
        Boolean clientResult = DistExecutor.unsafeCallWhenOn(Dist.CLIENT, () -> ClientLang::isEnglish);
        return clientResult == null || clientResult;
    }
}
