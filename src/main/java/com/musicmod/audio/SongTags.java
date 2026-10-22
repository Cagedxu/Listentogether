package com.musicmod.audio;

import com.musicmod.i18n.Lang;

/**
 * 歌曲元数据标签（ID3v1/ID3v2、Vorbis Comment、FLAC 等）。
 * 双端通用纯数据类，不引用任何客户端/服务端专有 API。
 */
public class SongTags {
    public final String fileName; // 完整文件名（含扩展名）

    public String title = "";
    public String artist = "";
    public String album = "";
    public long durationMs = -1;

    /** 封面图片原始字节（JPEG/PNG），可能为 null */
    public byte[] coverImage = null;
    /** 封面 MIME 类型，如 image/jpeg */
    public String coverMime = "";
    /** 内嵌歌词文本（可能带 LRC 时间标签），可能为空 */
    public String lyrics = "";
    /**
     * 备用歌词候选：来自非标准位置（如 TXXX 用户自定义帧、"lyrics-xxx" 标签）。
     * 仅在标准歌词帧（USLT/lyrics/©lyr）缺失时兜底使用，
     * 因为这类帧常被工具写成二次编码的乱码文本。
     */
    public String lyricsAlt = "";

    public SongTags(String fileName) {
        this.fileName = fileName;
    }

    /** 显示标题：无标签时用文件名去扩展名 */
    public String getDisplayTitle() {
        if (title != null && !title.isEmpty()) return title;
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    /** 显示艺术家：无标签时显示Lang.tr("未知艺术家")由调用方处理，这里返回原值 */
    public String getArtistOrEmpty() {
        return artist == null ? "" : artist;
    }

    public String getAlbumOrEmpty() {
        return album == null ? "" : album;
    }

    /** 标签摘要是否有效（至少识别出标题/艺术家/专辑之一） */
    public boolean hasInfo() {
        return (title != null && !title.isEmpty())
                || (artist != null && !artist.isEmpty())
                || (album != null && !album.isEmpty());
    }
}