package com.musicmod.client.tags;

import com.musicmod.MusicMod;
import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;
import org.jaudiotagger.tag.images.StandardArtwork;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;

/**
 * 直接写源文件标签，不再生成 .bak 备份；对 WAV/AIFF 等封面/歌词
 * 无法可靠内嵌的格式，额外生成同名 .lrc 与 .cover.jpg 兜底。
 */
public final class AudioTagWriter {
    private AudioTagWriter() {}

    public static final class Result {
        public final boolean ok;
        public final String message;
        public final boolean sidecar;
        public Result(boolean ok, String message, boolean sidecar) {
            this.ok = ok;
            this.message = message;
            this.sidecar = sidecar;
        }
    }

    public static Result write(File file, TagEditData data) {
        if (file == null || !file.isFile()) {
            return new Result(false, "文件不存在", false);
        }
        String ext = extension(file.getName());
        boolean sidecarFormat = ext.equals("wav") || ext.equals("aiff") || ext.equals("aif");
        try {
            AudioFile audio = AudioFileIO.read(file);
            Tag tag = audio.getTagOrCreateAndSetDefault();
            boolean clearAll = (data.title == null || data.title.isBlank())
                    && (data.artist == null || data.artist.isBlank())
                    && (data.album == null || data.album.isBlank())
                    && (data.lyrics == null || data.lyrics.isBlank())
                    && !data.hasCover();
            if (clearAll) {
                for (FieldKey key : new FieldKey[]{FieldKey.TITLE, FieldKey.ARTIST, FieldKey.ALBUM, FieldKey.LYRICS}) {
                    try { tag.deleteField(key); } catch (Throwable ignored) {}
                }
                try { tag.deleteArtworkField(); } catch (Throwable ignored) {}
            } else {
                set(tag, FieldKey.TITLE, data.title);
                set(tag, FieldKey.ARTIST, data.artist);
                set(tag, FieldKey.ALBUM, data.album);
                set(tag, FieldKey.LYRICS, data.lyrics);
            }
            boolean coverWritten = false;
            if (data.hasCover()) {
                try {
                    StandardArtwork art = new StandardArtwork();
                    art.setBinaryData(data.cover);
                    art.setMimeType(data.coverMime == null || data.coverMime.isEmpty()
                            ? "image/jpeg" : data.coverMime);
                    art.setDescription("Cover");
                    art.setPictureType(3);
                    tag.setField(art);
                    coverWritten = true;
                } catch (Throwable ignored) {
                    coverWritten = false;
                }
            }
            audio.commit();
            boolean sidecar = false;
            if (sidecarFormat && !coverWritten && data.hasCover()) {
                writeCoverSidecar(file, data.cover);
                sidecar = true;
            }
            if (sidecarFormat && data.lyrics != null && !data.lyrics.isBlank()) {
                writeLrcSidecar(file, data.lyrics);
                sidecar = true;
            }
            if (clearAll) deleteSidecars(file);
            String msg = "标签已写入: " + file.getName();
            if (sidecar) msg += "（封面/歌词已写入同名配套文件）";
            return new Result(true, msg, sidecar);
        } catch (Throwable t) {
            MusicMod.LOGGER.warn("Failed to write tags for {}", file, t);
            String msg = "标签写入失败: " + (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
            if (sidecarFormat) {
                try {
                    if (data.hasCover()) writeCoverSidecar(file, data.cover);
                    if (data.lyrics != null && !data.lyrics.isBlank()) writeLrcSidecar(file, data.lyrics);
                    return new Result(true, msg + "（已改用配套文件）", true);
                } catch (Throwable ignored) {
                }
            }
            return new Result(false, msg, false);
        }
    }

    /** 读取源文件已有标签与封面，供编辑器回显。 */
    public static TagEditData read(File file) {
        TagEditData data = new TagEditData();
        if (file == null || !file.isFile()) return data;
        try {
            AudioFile audio = AudioFileIO.read(file);
            Tag tag = audio.getTag();
            if (tag != null) {
                data.title = value(tag, FieldKey.TITLE);
                data.artist = value(tag, FieldKey.ARTIST);
                data.album = value(tag, FieldKey.ALBUM);
                data.lyrics = value(tag, FieldKey.LYRICS);
                try {
                    var art = tag.getFirstArtwork();
                    if (art != null && art.getBinaryData() != null) {
                        data.cover = art.getBinaryData();
                        data.coverMime = art.getMimeType() == null ? "" : art.getMimeType();
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            MusicMod.LOGGER.debug("Failed to read tags from {}", file, t);
        }
        if (data.lyrics.isBlank()) {
            File lrc = new File(stripExt(file) + ".lrc");
            if (lrc.isFile()) {
                try { data.lyrics = Files.readString(lrc.toPath(), StandardCharsets.UTF_8); } catch (IOException ignored) {}
            }
        }
        if (data.cover == null) {
            File cover = new File(stripExt(file) + ".cover.jpg");
            if (cover.isFile()) {
                try {
                    data.cover = Files.readAllBytes(cover.toPath());
                    data.coverMime = "image/jpeg";
                } catch (IOException ignored) {}
            }
        }
        return data;
    }

    private static String value(Tag tag, FieldKey key) {
        try {
            String v = tag.getFirst(key);
            return v == null ? "" : v;
        } catch (Throwable t) {
            return "";
        }
    }

    private static void set(Tag tag, FieldKey key, String value) {
        try {
            tag.setField(key, value == null ? "" : value);
        } catch (Throwable ignored) {
        }
    }

    private static void writeLrcSidecar(File audio, String lyrics) throws IOException {
        File lrc = new File(stripExt(audio) + ".lrc");
        Files.writeString(lrc.toPath(), lyrics, StandardCharsets.UTF_8);
    }

    private static void writeCoverSidecar(File audio, byte[] bytes) throws IOException {
        File img = new File(stripExt(audio) + ".cover.jpg");
        Files.write(img.toPath(), bytes);
    }

    private static void deleteSidecars(File audio) {
        try { Files.deleteIfExists(new File(stripExt(audio) + ".lrc").toPath()); } catch (IOException ignored) {}
        try { Files.deleteIfExists(new File(stripExt(audio) + ".cover.jpg").toPath()); } catch (IOException ignored) {}
    }

    private static String stripExt(File f) {
        int i = f.getName().lastIndexOf('.');
        return i > 0 ? new File(f.getParentFile(), f.getName().substring(0, i)).getAbsolutePath()
                : f.getAbsolutePath();
    }

    private static String extension(String name) {
        int i = name.lastIndexOf('.');
        return i >= 0 ? name.substring(i + 1).toLowerCase(Locale.ROOT) : "";
    }
}
