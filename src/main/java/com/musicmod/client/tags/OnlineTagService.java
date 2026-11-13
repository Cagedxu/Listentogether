package com.musicmod.client.tags;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.musicmod.MusicMod;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** 双源在线标签搜索：网易云优先，MusicBrainz + LRCLIB 回退。 */
public final class OnlineTagService {
    private static final String UA = "Listentogether/1.2.3 (Minecraft Forge mod)";
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(6))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private OnlineTagService() {}

    public static CompletableFuture<List<TagCandidate>> search(String query) {
        CompletableFuture<List<TagCandidate>> ne = CompletableFuture.supplyAsync(() -> safe(() -> searchNetEase(query)));
        CompletableFuture<List<TagCandidate>> qq = CompletableFuture.supplyAsync(() -> safe(() -> searchQQ(query)));
        CompletableFuture<List<TagCandidate>> kg = CompletableFuture.supplyAsync(() -> safe(() -> searchKugou(query)));
        CompletableFuture<List<TagCandidate>> mb = CompletableFuture.supplyAsync(() -> safe(() -> searchMusicBrainz(query)));
        return CompletableFuture.allOf(ne, qq, kg, mb).thenApply(v -> {
            List<TagCandidate> all = new ArrayList<>();
            all.addAll(ne.join());
            all.addAll(qq.join());
            all.addAll(kg.join());
            all.addAll(mb.join());
            return all;
        });
    }

    private static List<TagCandidate> safe(ThrowingSupplier<List<TagCandidate>> supplier) {
        try {
            return supplier.get();
        } catch (Throwable t) {
            MusicMod.LOGGER.debug("Online tag source failed", t);
            return List.of();
        }
    }

    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    /** 补全候选的封面与歌词。 */
    public static CompletableFuture<TagCandidate> enrich(TagCandidate c) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (c.source.equals("网易云")) {
                    if (c.coverUrl.isEmpty() || c.lyrics.isEmpty() || c.album.isEmpty()) {
                        fillNetEaseDetail(c);
                    }
                    if (c.lyrics.isEmpty()) c.lyrics = netEaseLyrics(c.id);
                    if (c.cover == null && !c.coverUrl.isEmpty()) c.cover = download(c.coverUrl, "https://music.163.com/");
                } else if (c.source.equals("QQ音乐")) {
                    if (c.lyrics.isEmpty()) c.lyrics = qqLyrics(c.id);
                    if (c.cover == null && !c.coverUrl.isEmpty()) c.cover = download(c.coverUrl, "https://y.qq.com/");
                } else if (c.source.equals("酷狗")) {
                    if (c.lyrics.isEmpty()) c.lyrics = kugouLyrics(c.title, c.artist, c.id, c.durationSec);
                    if (c.cover == null && !c.coverUrl.isEmpty()) c.cover = download(c.coverUrl, "https://www.kugou.com/");
                } else if (c.source.equals("MusicBrainz")) {
                    if (c.lyrics.isEmpty()) c.lyrics = lrclib(c.title, c.artist);
                    if (c.cover == null && !c.coverUrl.isEmpty()) c.cover = download(c.coverUrl, "https://musicbrainz.org/");
                }
                if (c.cover == null || c.cover.length == 0) {
                    String fallback = coverFromNetEase(c.title, c.artist);
                    if (!fallback.isEmpty()) c.cover = download(fallback, "https://music.163.com/");
                }
                if (c.cover != null && c.cover.length > 0 && c.coverMime.isEmpty()) {
                    c.coverMime = c.cover.length > 2
                            && (c.cover[0] & 0xFF) == 0x89 && c.cover[1] == 'P'
                            ? "image/png" : "image/jpeg";
                }
            } catch (Throwable t) {
                MusicMod.LOGGER.debug("Failed to enrich tag candidate", t);
            }
            return c;
        });
    }

    private static List<TagCandidate> searchNetEase(String query) throws Exception {
        String url = "https://music.163.com/api/search/get/web?csrf_token=&s="
                + enc(query) + "&type=1&offset=0&total=true&limit=8";
        JsonObject root = getJson(url);
        List<TagCandidate> out = new ArrayList<>();
        JsonObject result = obj(root, "result");
        JsonArray songs = arr(result, "songs");
        for (JsonElement e : songs) {
            if (!e.isJsonObject()) continue;
            JsonObject s = e.getAsJsonObject();
            TagCandidate c = new TagCandidate("网易云", str(s, "id"));
            c.title = str(s, "name");
            JsonArray artists = arr(s, "artists");
            StringBuilder a = new StringBuilder();
            for (JsonElement ae : artists) {
                if (!a.isEmpty()) a.append(" / ");
                a.append(str(ae.getAsJsonObject(), "name"));
            }
            c.artist = a.toString();
            JsonObject album = obj(s, "album");
            c.album = str(album, "name");
            c.coverUrl = str(album, "picUrl");
            out.add(c);
        }
        return out;
    }

    /** 其他源缺封面时，用歌名+作者回查网易云封面。 */
    private static String coverFromNetEase(String title, String artist) {
        try {
            String q = (title + " " + artist).trim();
            if (q.isEmpty()) return "";
            String url = "https://music.163.com/api/search/get/web?csrf_token=&s="
                    + enc(q) + "&type=1&offset=0&total=true&limit=1";
            JsonArray songs = arr(obj(getJson(url), "result"), "songs");
            if (songs.size() == 0) return "";
            return str(obj(songs.get(0).getAsJsonObject(), "album"), "picUrl");
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static void fillNetEaseDetail(TagCandidate c) {
        try {
            String url = "https://music.163.com/api/song/detail/?id=" + enc(c.id)
                    + "&ids=%5B" + enc(c.id) + "%5D";
            JsonArray songs = arr(getJson(url), "songs");
            if (songs.size() == 0) return;
            JsonObject s = songs.get(0).getAsJsonObject();
            if (c.title.isEmpty()) c.title = str(s, "name");
            JsonObject album = obj(s, "album");
            if (c.album.isEmpty()) c.album = str(album, "name");
            String pic = str(album, "picUrl");
            if (!pic.isEmpty()) c.coverUrl = pic;
            JsonArray artists = arr(s, "artists");
            if (c.artist.isEmpty() && artists.size() > 0) {
                StringBuilder a = new StringBuilder();
                for (JsonElement ae : artists) {
                    if (!a.isEmpty()) a.append(" / ");
                    a.append(str(ae.getAsJsonObject(), "name"));
                }
                c.artist = a.toString();
            }
        } catch (Throwable ignored) {
        }
    }

    private static String netEaseLyrics(String id) {
        try {
            JsonObject root = getJson("https://music.163.com/api/song/lyric?id=" + enc(id) + "&lv=1&kv=1&tv=-1");
            JsonObject lrc = obj(root, "lrc");
            String text = str(lrc, "lyric");
            if (!text.isBlank()) {
                JsonObject tlyric = obj(root, "tlyric");
                return mergeTranslation(text, str(tlyric, "lyric"));
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    /** 把逐行翻译按时间戳合并成「原文\u2009译文」，供 LyricsParser 识别。 */
    private static String mergeTranslation(String original, String translation) {
        if (translation == null || translation.isBlank()) return original;
        java.util.Map<Long, String> byTime = new java.util.HashMap<>();
        for (String line : translation.split("\r?\n")) {
            java.util.regex.Matcher m = LINE_TIME.matcher(line);
            if (!m.find()) continue;
            long ms = timeMs(m);
            String text = line.substring(m.end()).trim();
            if (!text.isEmpty()) byTime.put(ms, text);
        }
        if (byTime.isEmpty()) return original;
        StringBuilder out = new StringBuilder(original.length() + translation.length());
        for (String line : original.split("\r?\n", -1)) {
            java.util.regex.Matcher m = LINE_TIME.matcher(line);
            if (!m.find()) {
                out.append(line).append('\n');
                continue;
            }
            String text = line.substring(m.end());
            String tr = byTime.get(timeMs(m));
            if (tr != null && !text.contains("\u2009")) {
                out.append(line, 0, m.end()).append(text).append('\u2009').append(tr);
            } else {
                out.append(line);
            }
            out.append('\n');
        }
        return out.toString();
    }

    private static final java.util.regex.Pattern LINE_TIME =
            java.util.regex.Pattern.compile("\\[(\\d{1,3}):(\\d{2})(?:\\.(\\d{1,3}))?\\]");

    private static long timeMs(java.util.regex.Matcher m) {
        long min = Long.parseLong(m.group(1));
        long sec = Long.parseLong(m.group(2));
        String frac = m.group(3) == null ? "0" : m.group(3);
        long ms = frac.length() == 1 ? Long.parseLong(frac) * 100L
                : frac.length() == 2 ? Long.parseLong(frac) * 10L : Long.parseLong(frac);
        return (min * 60 + sec) * 1000L + ms;
    }

    private static List<TagCandidate> searchQQ(String query) throws Exception {
        String url = "https://c.y.qq.com/soso/fcgi-bin/client_search_cp?p=1&n=6&w="
                + enc(query) + "&format=json";
        JsonObject root = getJson(url, "https://y.qq.com/");
        JsonObject data = obj(root, "data");
        JsonObject song = obj(data, "song");
        List<TagCandidate> out = new ArrayList<>();
        for (JsonElement e : arr(song, "list")) {
            if (!e.isJsonObject()) continue;
            JsonObject s = e.getAsJsonObject();
            TagCandidate c = new TagCandidate("QQ音乐", str(s, "songmid"));
            c.title = str(s, "songname");
            JsonArray singers = arr(s, "singer");
            StringBuilder a = new StringBuilder();
            for (JsonElement se : singers) {
                if (!a.isEmpty()) a.append(" / ");
                a.append(str(se.getAsJsonObject(), "name"));
            }
            c.artist = a.toString();
            c.album = str(s, "albumname");
            String mid = str(s, "albummid");
            if (!mid.isEmpty()) c.coverUrl = "https://y.qq.com/music/photo_new/T002R300x300M000" + mid + ".jpg";
            c.durationSec = intVal(s, "interval");
            out.add(c);
        }
        return out;
    }

    private static List<TagCandidate> searchKugou(String query) throws Exception {
        String url = "https://songsearch.kugou.com/song_search_v2?keyword=" + enc(query)
                + "&page=1&pagesize=6&platform=WebFilter&userid=0&clientver=2000"
                + "&iscorrection=1&privilege_filter=0&filter=10";
        JsonObject root = getJson(url);
        JsonObject data = obj(root, "data");
        List<TagCandidate> out = new ArrayList<>();
        for (JsonElement e : arr(data, "lists")) {
            if (!e.isJsonObject()) continue;
            JsonObject s = e.getAsJsonObject();
            TagCandidate c = new TagCandidate("酷狗", str(s, "FileHash"));
            c.title = str(s, "SongName");
            c.artist = str(s, "SingerName");
            c.album = str(s, "AlbumName");
            String image = str(s, "Image");
            if (image.isEmpty()) image = str(s, "trans_param");
            if (!image.isEmpty()) {
                c.coverUrl = image.replace("{size}", "480").replace("http://", "https://");
            }
            c.durationSec = intVal(s, "Duration");
            out.add(c);
        }
        return out;
    }

    private static String qqLyrics(String songmid) {
        try {
            String url = "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?songmid="
                    + enc(songmid) + "&format=json&nobase64=1";
            JsonObject root = getJson(url, "https://y.qq.com/");
            String lyric = str(root, "lyric");
            return lyric.replace("\n", "\n");
        } catch (Throwable t) {
            return "";
        }
    }

    private static String kugouLyrics(String title, String artist, String hash, int durationSec) {
        try {
            String search = "https://krcs.kugou.com/search?ver=1&man=yes&client=mobi&keyword="
                    + enc((artist + " - " + title).trim()) + "&duration=" + Math.max(0, durationSec)
                    + "&hash=" + enc(hash);
            JsonObject root = getJson(search);
            JsonArray cands = arr(root, "candidates");
            if (cands.size() == 0) return "";
            JsonObject c = cands.get(0).getAsJsonObject();
            String id = str(c, "id");
            String key = str(c, "accesskey");
            if (id.isEmpty() || key.isEmpty()) return "";
            String dl = "https://lyrics.kugou.com/download?ver=1&client=pc&id=" + enc(id)
                    + "&accesskey=" + enc(key) + "&fmt=lrc&charset=utf8";
            JsonObject lr = getJson(dl);
            String content = str(lr, "content");
            if (content.isEmpty()) return "";
            return new String(java.util.Base64.getDecoder().decode(content), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return "";
        }
    }

    private static List<TagCandidate> searchMusicBrainz(String query) throws Exception {
        String url = "https://musicbrainz.org/ws/2/recording/?query=" + enc(query)
                + "&fmt=json&limit=6";
        JsonObject root = getJson(url);
        List<TagCandidate> out = new ArrayList<>();
        for (JsonElement e : arr(root, "recordings")) {
            if (!e.isJsonObject()) continue;
            JsonObject r = e.getAsJsonObject();
            TagCandidate c = new TagCandidate("MusicBrainz", str(r, "id"));
            c.title = str(r, "title");
            JsonArray credits = arr(r, "artist-credit");
            StringBuilder a = new StringBuilder();
            for (JsonElement ce : credits) {
                JsonObject co = ce.getAsJsonObject();
                if (!a.isEmpty()) a.append(" / ");
                a.append(str(co, "name"));
            }
            c.artist = a.toString();
            JsonArray releases = arr(r, "releases");
            if (releases.size() > 0) {
                JsonObject rel = releases.get(0).getAsJsonObject();
                c.album = str(rel, "title");
                String rid = str(rel, "id");
                if (!rid.isEmpty()) c.coverUrl = "https://coverartarchive.org/release/" + rid + "/front-500";
            }
            out.add(c);
        }
        return out;
    }

    private static String lrclib(String title, String artist) {
        try {
            String url = "https://lrclib.net/api/search?track_name=" + enc(title)
                    + "&artist_name=" + enc(artist);
            JsonArray arr = JsonParser.parseString(get(url)).getAsJsonArray();
            for (JsonElement e : arr) {
                JsonObject o = e.getAsJsonObject();
                String synced = str(o, "syncedLyrics");
                if (!synced.isBlank()) return synced;
                String plain = str(o, "plainLyrics");
                if (!plain.isBlank()) return plain;
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    private static JsonObject getJson(String url) throws Exception {
        return JsonParser.parseString(get(url)).getAsJsonObject();
    }

    private static JsonObject getJson(String url, String referer) throws Exception {
        return JsonParser.parseString(get(url, referer)).getAsJsonObject();
    }

    private static String get(String url, String referer) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(9))
                .header("User-Agent", UA)
                .header("Referer", referer)
                .GET().build();
        HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (res.statusCode() / 100 != 2) throw new IllegalStateException("HTTP " + res.statusCode());
        return res.body();
    }

    private static String get(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(9))
                .header("User-Agent", UA)
                .header("Referer", "https://music.163.com/")
                .GET().build();
        HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (res.statusCode() / 100 != 2) throw new IllegalStateException("HTTP " + res.statusCode());
        return res.body();
    }

    private static byte[] download(String url, String referer) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(12))
                    .header("User-Agent", UA)
                    .header("Referer", referer == null ? "" : referer)
                    .GET().build();
            HttpResponse<byte[]> res = HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (res.statusCode() / 100 == 2) return res.body();
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static byte[] download(String url) {
        return download(url, "https://music.163.com/");
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }

    private static int intVal(JsonObject o, String key) {
        try {
            return o.has(key) ? o.get(key).getAsInt() : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static String str(JsonObject o, String key) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) return "";
        try {
            return o.get(key).getAsString();
        } catch (Throwable t) {
            return "";
        }
    }

    private static JsonObject obj(JsonObject o, String key) {
        if (o == null || !o.has(key) || !o.get(key).isJsonObject()) return new JsonObject();
        return o.getAsJsonObject(key);
    }

    private static JsonArray arr(JsonObject o, String key) {
        if (o == null || !o.has(key) || !o.get(key).isJsonArray()) return new JsonArray();
        return o.getAsJsonArray(key);
    }
}
