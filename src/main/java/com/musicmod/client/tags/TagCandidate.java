package com.musicmod.client.tags;

/** 在线搜索结果候选。 */
public final class TagCandidate {
    public final String source;
    public final String id;
    public String title = "";
    public String artist = "";
    public String album = "";
    public String lyrics = "";
    public String coverUrl = "";
    public byte[] cover = null;
    public String coverMime = "";
    public int durationSec;

    public TagCandidate(String source, String id) {
        this.source = source;
        this.id = id;
    }

    public TagEditData toData() {
        return new TagEditData(title, artist, album, lyrics, cover, coverMime, source);
    }

    public String summary() {
        StringBuilder sb = new StringBuilder(title == null ? "" : title);
        if (artist != null && !artist.isEmpty()) sb.append(" - ").append(artist);
        if (album != null && !album.isEmpty()) sb.append("  [").append(album).append(']');
        sb.append("  (").append(source).append(')');
        return sb.toString();
    }
}
