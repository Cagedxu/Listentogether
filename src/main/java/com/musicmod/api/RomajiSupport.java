package com.musicmod.api;

import java.util.concurrent.CopyOnWriteArrayList;

/** 罗马音拓展注册表；主模组不内置日语词典。 */
public final class RomajiSupport {
    private static final CopyOnWriteArrayList<RomajiProvider> PROVIDERS = new CopyOnWriteArrayList<>();

    private RomajiSupport() {}

    public static void register(RomajiProvider provider) {
        if (provider != null && !PROVIDERS.contains(provider)) PROVIDERS.add(provider);
    }

    public static void unregister(RomajiProvider provider) {
        PROVIDERS.remove(provider);
    }

    public static boolean available() {
        return !PROVIDERS.isEmpty();
    }

    public static boolean canHandle(String text) {
        for (RomajiProvider p : PROVIDERS) {
            try {
                if (p.canHandle(text)) return true;
            } catch (Throwable ignored) {}
        }
        return false;
    }

    public static String romanize(String text) {
        for (RomajiProvider p : PROVIDERS) {
            try {
                if (p.canHandle(text)) {
                    String out = p.romanize(text);
                    if (out != null && !out.isBlank()) return out;
                }
            } catch (Throwable ignored) {}
        }
        return text;
    }
}
