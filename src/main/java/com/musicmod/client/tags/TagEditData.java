package com.musicmod.client.tags;

/** 可编辑的歌曲标签数据，同时用于在线搜索结果与最终写入。 */
public final class TagEditData {
    public String title = "";
    public String artist = "";
    public String album = "";
    public String lyrics = "";
    public byte[] cover = null;
    public String coverMime = "";
    public String source = "";

    public TagEditData() {}

    public TagEditData(String title, String artist, String album, String lyrics,
                       byte[] cover, String coverMime, String source) {
        this.title = title == null ? "" : title;
        this.artist = artist == null ? "" : artist;
        this.album = album == null ? "" : album;
        this.lyrics = lyrics == null ? "" : lyrics;
        this.cover = cover;
        this.coverMime = coverMime == null ? "" : coverMime;
        this.source = source == null ? "" : source;
    }

    public boolean hasCover() {
        return cover != null && cover.length > 0;
    }

    public String summary() {
        String a = artist.isEmpty() ? "未知作者" : artist;
        String al = album.isEmpty() ? "未知专辑" : album;
        return title + " — " + a + " / " + al + (source.isEmpty() ? "" : "  [" + source + "]");
    }
}
