package com.musicmod.audio;

import com.musicmod.MusicMod;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;

/**
 * 轻量级音频标签解析器（纯 Java，无第三方依赖）。
 * 支持：MP3(ID3v1/ID3v2.3/ID3v2.4)、FLAC(StreamInfo/VorbisComment/Picture)、
 *       OGG(Vorbis Comment)、WAV(时长)、M4A/MP4(iTunes metadata)。
 * 解析失败时静默降级，仅返回文件名信息。
 */
public class TagReader {

    /** 标签区最大扫描字节数，防止异常文件占用过多内存 */
    private static final int MAX_SCAN = 1024 * 1024;

    /** ID3v2 标签最大字节数（含 APIC 封面可达数 MB，1MB 不够） */
    private static final int MAX_ID3_TAG = 16 * 1024 * 1024;

    /** 日文 Shift_JIS 超集（windows-31j），日系软件常把 Shift_JIS 写入 Latin-1 帧/ID3v1 */
    private static final Charset CS_MS932 = Charset.forName("MS932");
    /** 简体中文 GBK，部分中文软件同样不规范写入 Latin-1 帧 */
    private static final Charset CS_GBK = Charset.forName("GBK");

    /** mvhd timescale 临时存储（供 mehd 计算 fragmented MP4 时长使用，解析完即失效） */
    private static long mvhdTimescale = 0;

    public static SongTags read(File file) {
        SongTags tags = new SongTags(file.getName());
        String ext = extOf(file.getName());
        try {
            switch (ext) {
                case "mp3":
                    readId3v2(file, tags);
                    readId3v1(file, tags);
                    if (tags.durationMs < 0) tags.durationMs = estimateMp3Duration(file);
                    break;
                case "flac":
                    readFlac(file, tags);
                    break;
                case "ogg":
                case "opus":
                case "oga":
                    readOggVorbis(file, tags);
                    break;
                case "wav":
                    readWav(file, tags);
                    break;
                case "m4a":
                case "mp4":
                case "m4b":
                case "aac":
                    readMp4(file, tags);
                    break;
                default:
                    break;
            }
        } catch (Exception e) {
            MusicMod.LOGGER.debug("Tag read failed for " + file.getName() + ": " + e);
        }
        // 歌词兜底：标准歌词帧缺失时才使用 TXXX 等非标准来源
        if (tags.lyrics.trim().isEmpty() && !tags.lyricsAlt.trim().isEmpty()) {
            tags.lyrics = tags.lyricsAlt;
        }
        tags.lyricsAlt = "";
        return tags;
    }

    // ==================== ID3v2 (MP3) ====================

    private static void readId3v2(File file, SongTags tags) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            byte[] head = new byte[10];
            if (raf.read(head) != 10) return;
            if (head[0] != 'I' || head[1] != 'D' || head[2] != '3') return;
            int size = syncsafe(head, 6);
            if (size <= 0 || size > MAX_ID3_TAG) return;
            byte[] body = new byte[size];
            raf.readFully(body);
            parseId3v2Tag(head, body, tags);
        }
    }

    /**
     * 从完整的 ID3v2 数据（10 字节头 + 帧 body）解析标签。
     * 供 MP3 文件头和 WAV 内嵌 ID3 chunk 共用。
     */
    private static void parseId3v2Tag(byte[] head, byte[] body, SongTags tags) {
        int major = head[3] & 0xFF;
        if (major < 2 || major > 4) return;
        int flags = head[5] & 0xFF;
        int pos = 0;
        // 跳过扩展头
        if ((flags & 0x40) != 0) {
            if (major == 4) {
                pos += syncsafe(body, 0);
            } else {
                pos += beInt(body, 0) + 4;
            }
        }
        while (pos + 10 <= body.length) {
            String id = new String(body, pos, 4, StandardCharsets.ISO_8859_1);
            if (id.charAt(0) == 0) break; // padding 区
            int frameSize;
            if (major == 4) frameSize = syncsafe(body, pos + 4);
            else if (major == 3) frameSize = beInt(body, pos + 4);
            else frameSize = ((body[pos + 4] & 0xFF) << 8) | (body[pos + 5] & 0xFF); // v2.2: 3字节ID+3字节size
            int frameFlags = ((body[pos + 8] & 0xFF) << 8) | (body[pos + 9] & 0xFF);
            int headerLen = (major == 2) ? 6 : 10;
            pos += headerLen;
            if (frameSize <= 0 || pos + frameSize > body.length) break;
            // 压缩/加密帧跳过
            if ((frameFlags & 0x0080) != 0 || (frameFlags & 0x0040) != 0) {
                pos += frameSize;
                continue;
            }
            byte[] data = Arrays.copyOfRange(body, pos, pos + frameSize);
            pos += frameSize;
            try {
                parseId3Frame(major, id, data, tags);
            } catch (Exception ignored) {}
        }
    }

    private static void parseId3Frame(int major, String id, byte[] data, SongTags tags) {
        if (major == 2) {
            // v2.2 使用 3 字节 ID：TT2=标题, TP1=艺术家, TAL=专辑, PIC=封面, ULT=歌词
            switch (id) {
                case "TT2": tags.title = decodeTextFrame(data); break;
                case "TP1": tags.artist = decodeTextFrame(data); break;
                case "TAL": tags.album = decodeTextFrame(data); break;
                case "PIC": parseApic(data, tags, true); break;
                case "ULT": parseUslt(data, tags); break;
                default: break;
            }
            return;
        }
        switch (id) {
            case "TIT2": tags.title = decodeTextFrame(data); break;
            case "TPE1": tags.artist = decodeTextFrame(data); break;
            case "TALB": tags.album = decodeTextFrame(data); break;
            case "TLEN":
                try { tags.durationMs = Long.parseLong(decodeTextFrame(data).trim()); } catch (Exception ignored) {}
                break;
            case "APIC": parseApic(data, tags, false); break;
            case "USLT": parseUslt(data, tags); break;
            case "TXXX": parseTxxx(data, tags); break;
            default: break;
        }
    }

    private static String decodeTextFrame(byte[] data) {
        if (data.length < 1) return "";
        int enc = data[0] & 0xFF;
        byte[] text = Arrays.copyOfRange(data, 1, data.length);
        return trimNull(decodeByEncoding(enc, text));
    }

    private static String decodeByEncoding(int enc, byte[] b) {
        switch (enc) {
            case 0: return decodeLatin1OrAsian(b);
            case 1: return decodeUtf16(b);
            case 2: return new String(b, StandardCharsets.UTF_16BE);
            case 3: return new String(b, StandardCharsets.UTF_8);
            default: return decodeLatin1OrAsian(b);
        }
    }

    /**
     * 解码声明为 Latin-1(enc=0) 的文本帧。
     * 规范要求 ISO-8859-1，但日本/中国软件常把 Shift_JIS/GBK 字节直接写入，
     * 导致日语歌手名等乱码。策略：含高位字节时依次尝试 MS932/GBK，
     * 若无替换符（\uFFFD）则采用，否则回退 Latin-1。
     */
    private static String decodeLatin1OrAsian(byte[] b) {
        boolean hasHigh = false;
        for (byte x : b) {
            if ((x & 0xFF) > 0x7F) { hasHigh = true; break; }
        }
        if (!hasHigh) return new String(b, StandardCharsets.ISO_8859_1);
        for (Charset cs : new Charset[]{CS_MS932, CS_GBK}) {
            try {
                String s = new String(b, cs);
                if (s.indexOf('\uFFFD') < 0) return s;
            } catch (Exception ignored) {}
        }
        return new String(b, StandardCharsets.ISO_8859_1);
    }

    private static String decodeUtf16(byte[] b) {
        if (b.length >= 2 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xFE)
            return new String(b, 2, b.length - 2, StandardCharsets.UTF_16LE);
        if (b.length >= 2 && (b[0] & 0xFF) == 0xFE && (b[1] & 0xFF) == 0xFF)
            return new String(b, 2, b.length - 2, StandardCharsets.UTF_16BE);
        return new String(b, StandardCharsets.UTF_16);
    }

    private static void parseApic(byte[] data, SongTags tags, boolean v22) {
        if (data.length < 4) return;
        int enc = data[0] & 0xFF;
        int p = 1;
        // v2.2: 3字节图片格式(如"JPG"/"PNG")；v2.3+: MIME 字符串
        if (v22) {
            if (p + 3 > data.length) return;
            String fmt = new String(data, p, 3, StandardCharsets.ISO_8859_1);
            tags.coverMime = fmt.equalsIgnoreCase("PNG") ? "image/png" : "image/jpeg";
            p += 3;
        } else {
            int mimeEnd = findTerminator(data, p, 0); // MIME 固定 Latin-1
            if (mimeEnd < 0) return;
            tags.coverMime = new String(data, p, mimeEnd - p, StandardCharsets.ISO_8859_1);
            p = mimeEnd + 1;
        }
        if (p >= data.length) return;
        p++; // 图片类型 1 字节
        int descEnd = findTerminator(data, p, enc);
        if (descEnd < 0) return;
        p = descEnd + termLen(enc);
        if (p >= data.length) return;
        byte[] img = Arrays.copyOfRange(data, p, data.length);
        if (img.length > 10 && tags.coverImage == null) {
            tags.coverImage = img;
        }
    }

    private static void parseUslt(byte[] data, SongTags tags) {
        if (data.length < 5) return;
        int enc = data[0] & 0xFF;
        int p = 4; // 编码1 + 语言3
        int descEnd = findTerminator(data, p, enc);
        if (descEnd < 0) return;
        p = descEnd + termLen(enc);
        if (p >= data.length) return;
        String text = trimNull(decodeByEncoding(enc, Arrays.copyOfRange(data, p, data.length)));
        if (!text.trim().isEmpty() && tags.lyrics.isEmpty()) {
            tags.lyrics = text;
        }
    }

    /**
     * 解析 TXXX 用户定义文本帧。
     * 部分标签器（ffmpeg/Lavf）将歌词写入 TXXX(description="USLT") 而非标准 USLT 帧，
     * 也有用 "LYRICS"/"lyrics-XXX" 的。此处作为歌词兜底来源。
     */
    private static void parseTxxx(byte[] data, SongTags tags) {
        if (data.length < 2) return;
        int enc = data[0] & 0xFF;
        byte[] rest = Arrays.copyOfRange(data, 1, data.length);
        int descEnd = findTerminator(rest, 0, enc);
        if (descEnd < 0) return;
        String desc = trimNull(decodeByEncoding(enc, Arrays.copyOfRange(rest, 0, descEnd)));
        int valStart = descEnd + termLen(enc);
        if (valStart >= rest.length) return;
        String val = trimNull(decodeByEncoding(enc, Arrays.copyOfRange(rest, valStart, rest.length)));
        // TXXX 只作为兜底候选：部分工具会把 UTF-8 歌词当成 GBK 再写一遍，
        // 造成"浣滆瘝"式二次编码乱码，因此优先级低于标准 USLT 帧
        if (tags.lyricsAlt.isEmpty() && !val.trim().isEmpty()) {
            String d = desc.toUpperCase(Locale.ROOT);
            if (d.equals("USLT") || d.equals("LYRICS") || d.startsWith("LYRICS")) {
                tags.lyricsAlt = val;
            }
        }
    }

    // ==================== ID3v1 (MP3) ====================

    private static void readId3v1(File file, SongTags tags) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            if (raf.length() < 128) return;
            raf.seek(raf.length() - 128);
            byte[] b = new byte[128];
            raf.readFully(b);
            if (b[0] != 'T' || b[1] != 'A' || b[2] != 'G') return;
            // ID3v1 无编码声明，日本/中国软件通常写入 Shift_JIS/GBK，
            // 用 decodeLatin1OrAsian 自动尝试恢复
            if (tags.title.isEmpty())
                tags.title = trimNull(decodeLatin1OrAsian(Arrays.copyOfRange(b, 3, 33)));
            if (tags.artist.isEmpty())
                tags.artist = trimNull(decodeLatin1OrAsian(Arrays.copyOfRange(b, 33, 63)));
            if (tags.album.isEmpty())
                tags.album = trimNull(decodeLatin1OrAsian(Arrays.copyOfRange(b, 63, 93)));
        }
    }

    // ==================== MP3 时长估算 ====================

    private static long estimateMp3Duration(File file) {
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            long len = raf.length();
            byte[] head = new byte[10];
            raf.read(head);
            long audioStart = 0;
            if (head[0] == 'I' && head[1] == 'D' && head[2] == '3') {
                audioStart = 10 + syncsafe(head, 6);
            }
            // ID3v1 尾部 128 字节不算音频
            long audioLen = len - audioStart;
            if (raf.length() >= 128) {
                raf.seek(len - 128);
                byte[] t = new byte[3];
                raf.read(t);
                if (t[0] == 'T' && t[1] == 'A' && t[2] == 'G') audioLen -= 128;
            }
            if (audioLen <= 0) return -1;

            // 找第一个 MPEG 帧头
            raf.seek(audioStart);
            byte[] buf = new byte[64];
            int n = raf.read(buf);
            for (int i = 0; i + 4 <= n; i++) {
                if ((buf[i] & 0xFF) != 0xFF || (buf[i + 1] & 0xE0) != 0xE0) continue;
                int b1 = buf[i + 1] & 0xFF;
                int b2 = buf[i + 2] & 0xFF;
                int versionIdx = (b1 >> 3) & 3; // 0=2.5, 2=2, 3=1
                int layerIdx = (b1 >> 1) & 3;   // 1=III, 2=II, 3=I
                int bitrateIdx = (b2 >> 4) & 0xF;
                int srIdx = (b2 >> 2) & 3;
                if (versionIdx == 1 || layerIdx == 0 || bitrateIdx == 0 || bitrateIdx == 15 || srIdx == 3)
                    continue;
                // Xing/Info VBR 头检测（帧头后 4 字节起，偏移取决于单声道）
                int chanMode = (buf[i + 3] >> 6) & 3;
                int sideInfo = versionIdx == 3 ? (chanMode == 3 ? 17 : 32) : (chanMode == 3 ? 9 : 17);
                if (i + 4 + sideInfo + 16 <= n) {
                    int xoff = i + 4 + sideInfo;
                    if (buf[xoff] == 'X' && buf[xoff + 1] == 'i'
                            && buf[xoff + 2] == 'n' && buf[xoff + 3] == 'g'
                            || buf[xoff] == 'I' && buf[xoff + 1] == 'n'
                            && buf[xoff + 2] == 'f' && buf[xoff + 3] == 'o') {
                        int flags = beInt(buf, xoff + 4);
                        if ((flags & 0x01) != 0) { // 帧数标志
                            int frames = beInt(buf, xoff + 8);
                            int samplesPerFrame = (layerIdx == 1) ? 1152 : 384;
                            int sampleRate = sampleRateOf(versionIdx, srIdx);
                            if (frames > 0 && sampleRate > 0)
                                return (long) frames * samplesPerFrame * 1000L / sampleRate;
                        }
                    }
                }
                // CBR 估算
                int bitrate = bitrateOf(versionIdx, layerIdx, bitrateIdx);
                int sampleRate = sampleRateOf(versionIdx, srIdx);
                if (bitrate > 0) return audioLen * 8L / bitrate; // 毫秒：audioLen(字节)*8/bitrate(kbps) = 秒*1000
                return -1;
            }
        } catch (Exception ignored) {}
        return -1;
    }

    private static final int[] BR_V1_L1 = {0,32,64,96,128,160,192,224,256,288,320,352,384,416,448,0};
    private static final int[] BR_V1_L2 = {0,32,48,56,64,80,96,112,128,160,192,224,256,320,384,0};
    private static final int[] BR_V1_L3 = {0,32,40,48,56,64,80,96,112,128,160,192,224,256,320,0};
    private static final int[] BR_V2_L1 = {0,32,48,56,64,80,96,112,128,144,160,176,192,224,256,0};
    private static final int[] BR_V2_L23 = {0,8,16,24,32,40,48,56,64,80,96,112,128,144,160,0};

    private static int bitrateOf(int ver, int layer, int idx) {
        if (ver == 3) { // MPEG1
            switch (layer) {
                case 3: return BR_V1_L1[idx];
                case 2: return BR_V1_L2[idx];
                case 1: return BR_V1_L3[idx];
            }
        } else { // MPEG2/2.5
            if (layer == 3) return BR_V2_L1[idx];
            return BR_V2_L23[idx];
        }
        return 0;
    }

    private static int sampleRateOf(int ver, int idx) {
        switch (ver) {
            case 3: return new int[]{44100, 48000, 32000, 0}[idx];
            case 2: return new int[]{22050, 24000, 16000, 0}[idx];
            case 0: return new int[]{11025, 12000, 8000, 0}[idx];
        }
        return 0;
    }

    // ==================== FLAC ====================

    private static void readFlac(File file, SongTags tags) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            byte[] magic = new byte[4];
            if (raf.read(magic) != 4) return;
            if (magic[0] != 'f' || magic[1] != 'L' || magic[2] != 'a' || magic[3] != 'C') return;
            boolean last = false;
            while (!last) {
                byte[] hdr = new byte[4];
                if (raf.read(hdr) != 4) break;
                int type = hdr[0] & 0x7F;
                last = (hdr[0] & 0x80) != 0;
                int len = ((hdr[1] & 0xFF) << 16) | ((hdr[2] & 0xFF) << 8) | (hdr[3] & 0xFF);
                if (len <= 0) continue;
                if (type == 6) {
                    // PICTURE block 常超 1MB（高分辨率封面），流式解析头部后直接读取图片数据，
                    // 不受 MAX_SCAN 限制；解析后指针移到 block 末尾保证后续 block 正常读取
                    try { parseFlacPictureStream(raf, len, tags); } catch (Exception ignored) {}
                } else if (len > MAX_SCAN) {
                    raf.skipBytes(len);
                    continue;
                } else {
                    byte[] data = new byte[len];
                    raf.readFully(data);
                    try {
                        if (type == 0) parseFlacStreamInfo(data, tags);
                        else if (type == 4) parseVorbisComment(data, tags);
                    } catch (Exception ignored) {}
                }
            }
        }
    }

    /**
     * 流式解析 FLAC PICTURE block：逐字段读取头部后直接读取图片数据。
     * 高分辨率 FLAC 封面常超 1MB，不能整体加载到内存缓冲。
     * 无论解析成功与否，最终将文件指针定位到 block 末尾。
     */
    private static void parseFlacPictureStream(RandomAccessFile raf, int blockLen, SongTags tags) throws IOException {
        long end = raf.getFilePointer() + blockLen;
        try {
            raf.skipBytes(4); // 图片类型
            int mimeLen = raf.readInt();
            if (mimeLen < 0 || mimeLen > 256 || raf.getFilePointer() + mimeLen > end) return;
            byte[] mimeBuf = new byte[mimeLen];
            raf.readFully(mimeBuf);
            String mime = new String(mimeBuf, StandardCharsets.ISO_8859_1);
            int descLen = raf.readInt();
            if (descLen < 0 || raf.getFilePointer() + descLen > end) return;
            raf.skipBytes(descLen);
            raf.skipBytes(16); // width(4) + height(4) + depth(4) + colors(4)
            int dataLen = raf.readInt();
            if (dataLen <= 0 || dataLen > 5_000_000 || raf.getFilePointer() + dataLen > end) return;
            byte[] img = new byte[dataLen];
            raf.readFully(img);
            if (tags.coverImage == null) {
                tags.coverImage = img;
                tags.coverMime = mime;
            }
        } finally {
            raf.seek(end); // 确保指针移到 block 末尾
        }
    }

    private static void parseFlacStreamInfo(byte[] d, SongTags tags) {
        if (d.length < 18) return;
        int sampleRate = ((d[10] & 0xFF) << 12) | ((d[11] & 0xFF) << 4) | ((d[12] & 0xFF) >> 4);
        long totalSamples = ((long) (d[13] & 0x0F) << 32) | ((d[14] & 0xFF) << 24)
                | ((d[15] & 0xFF) << 16) | ((d[16] & 0xFF) << 8) | (d[17] & 0xFF);
        if (sampleRate > 0 && totalSamples > 0)
            tags.durationMs = totalSamples * 1000L / sampleRate;
    }

    // ==================== OGG Vorbis ====================

    private static void readOggVorbis(File file, SongTags tags) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            int scan = (int) Math.min(raf.length(), MAX_SCAN);
            byte[] buf = new byte[scan];
            raf.readFully(buf);
            // 搜 \x03vorbis 注释头
            int idx = -1;
            for (int i = 0; i + 7 <= buf.length; i++) {
                if (buf[i] == 3 && buf[i + 1] == 'v' && buf[i + 2] == 'o'
                        && buf[i + 3] == 'r' && buf[i + 4] == 'b' && buf[i + 5] == 'i'
                        && buf[i + 6] == 's') {
                    idx = i;
                    break;
                }
            }
            if (idx < 0) return;
            byte[] comment = Arrays.copyOfRange(buf, idx + 7, buf.length);
            parseVorbisComment(comment, tags);
        }
    }

    private static void parseVorbisComment(byte[] d, SongTags tags) {
        int p = 0;
        if (p + 4 > d.length) return;
        int vlen = leInt(d, p); p += 4 + Math.max(0, vlen);
        if (p + 4 > d.length) return;
        int count = leInt(d, p); p += 4;
        for (int i = 0; i < count && p + 4 <= d.length; i++) {
            int clen = leInt(d, p); p += 4;
            if (clen < 0 || clen > d.length - p) break;
            String entry = new String(d, p, clen, StandardCharsets.UTF_8);
            p += clen;
            int eq = entry.indexOf('=');
            if (eq <= 0) continue;
            String key = entry.substring(0, eq).toUpperCase(Locale.ROOT);
            String val = entry.substring(eq + 1);
            switch (key) {
                case "TITLE": if (tags.title.isEmpty()) tags.title = val; break;
                case "ARTIST": if (tags.artist.isEmpty()) tags.artist = val; break;
                case "ALBUM": if (tags.album.isEmpty()) tags.album = val; break;
                case "LYRICS": case "UNSYNCEDLYRICS": if (tags.lyrics.isEmpty()) tags.lyrics = val; break;
                default: break;
            }
        }
    }

    // ==================== WAV 时长 + 标签 ====================

    /**
     * 读取 WAV 文件：时长（fmt/data chunk）+ 标签（LIST/INFO chunk、ID3 chunk）。
     * LIST/INFO 子块：INAM=标题, IART=艺术家, IPRD=专辑。
     * 部分编码器还会写入 "ID3 " chunk（内容为标准 ID3v2 数据）。
     */
    private static void readWav(File file, SongTags tags) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            byte[] head = new byte[12];
            if (raf.read(head) != 12) return;
            if (head[0] != 'R' || head[1] != 'I' || head[2] != 'F' || head[3] != 'F') return;
            if (head[8] != 'W' || head[9] != 'A' || head[10] != 'V' || head[11] != 'E') return;
            int byteRate = 0;
            long dataSize = 0;
            while (true) {
                byte[] ch = new byte[8];
                if (raf.read(ch) != 8) break;
                int chunkSize = leInt(ch, 4);
                String id = new String(ch, 0, 4, StandardCharsets.ISO_8859_1);
                if (id.equals("fmt ")) {
                    byte[] fmt = new byte[Math.min(chunkSize, 32)];
                    raf.readFully(fmt);
                    // nAvgBytesPerSec(byteRate) 位于 fmt chunk 偏移 8，仅需 12 字节即可读取；
                    // 标准 PCM fmt chunk 为 16 字节，此前误判 >=28 导致 WAV 时长始终无法计算
                    if (fmt.length >= 12) {
                        byteRate = leInt(fmt, 8);
                    }
                    if ((chunkSize & 1) != 0 && fmt.length < chunkSize) raf.skipBytes(chunkSize - fmt.length + 1);
                    else if ((chunkSize & 1) != 0) raf.skipBytes(1);
                    else if (fmt.length < chunkSize) raf.skipBytes(chunkSize - fmt.length);
                } else if (id.equals("data")) {
                    dataSize = chunkSize & 0xFFFFFFFFL;
                    raf.skipBytes(chunkSize + (chunkSize & 1));
                } else if (id.equals("LIST") && chunkSize > 4) {
                    int readLen = Math.min(chunkSize, MAX_SCAN);
                    byte[] listData = new byte[readLen];
                    raf.readFully(listData);
                    try { parseWavListInfo(listData, tags); } catch (Exception ignored) {}
                    if (readLen < chunkSize) raf.skipBytes(chunkSize - readLen);
                    if ((chunkSize & 1) != 0) raf.skipBytes(1);
                } else if ((id.equalsIgnoreCase("ID3 ") || id.equals("id3\0")) && chunkSize > 10) {
                    int readLen = Math.min(chunkSize, MAX_ID3_TAG);
                    byte[] id3Data = new byte[readLen];
                    raf.readFully(id3Data);
                    if (id3Data[0] == 'I' && id3Data[1] == 'D' && id3Data[2] == '3') {
                        byte[] id3Head = Arrays.copyOfRange(id3Data, 0, 10);
                        byte[] id3Body = Arrays.copyOfRange(id3Data, 10, id3Data.length);
                        try { parseId3v2Tag(id3Head, id3Body, tags); } catch (Exception ignored) {}
                    }
                    if (readLen < chunkSize) raf.skipBytes(chunkSize - readLen);
                    if ((chunkSize & 1) != 0) raf.skipBytes(1);
                } else {
                    raf.skipBytes(chunkSize + (chunkSize & 1)); // 奇数对齐填充
                }
            }
            if (byteRate > 0 && dataSize > 0) tags.durationMs = dataSize * 1000L / byteRate;
        }
    }

    /** 解析 WAV LIST/INFO chunk 中的标签子块 */
    private static void parseWavListInfo(byte[] data, SongTags tags) {
        if (data.length < 4) return;
        String type = new String(data, 0, 4, StandardCharsets.ISO_8859_1);
        if (!type.equals("INFO")) return;
        int p = 4;
        while (p + 8 <= data.length) {
            String subId = new String(data, p, 4, StandardCharsets.ISO_8859_1);
            int subSize = leInt(data, p + 4);
            p += 8;
            if (subSize <= 0 || p + subSize > data.length) break;
            byte[] textBytes = Arrays.copyOfRange(data, p, p + subSize);
            String text = trimNull(decodeLatin1OrAsian(textBytes));
            switch (subId) {
                case "INAM": if (tags.title.isEmpty()) tags.title = text; break;
                case "IART": if (tags.artist.isEmpty()) tags.artist = text; break;
                case "IPRD": if (tags.album.isEmpty()) tags.album = text; break;
                default: break;
            }
            p += subSize + (subSize & 1); // 子块奇数对齐
        }
    }

    // ==================== MP4/M4A (iTunes metadata) ====================

    /**
     * 读取 MP4/M4A 文件：时长（mvhd box）+ iTunes 标签（moov/udta/meta/ilst）。
     * ilst 标签 atom：©nam=标题, ©ART=艺术家, ©alb=专辑, ©lyr=歌词, covr=封面。
     */
    private static void readMp4(File file, SongTags tags) throws IOException {
        mvhdTimescale = 0;
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            long fileLen = raf.length();
            // 验证 ftyp box
            byte[] head = new byte[12];
            if (raf.read(head) != 12) return;
            if (head[4] != 'f' || head[5] != 't' || head[6] != 'y' || head[7] != 'p') return;
            // ftyp box 实际大小由前 4 字节决定（典型 16~32 字节，含 compatible brands），
            // 不能硬编码 12，否则后续 box 起点错位导致 moov 永远找不到
            long ftypSize = beInt(head, 0) & 0xFFFFFFFFL;
            if (ftypSize == 1) {
                byte[] ext = new byte[8];
                if (raf.read(ext) != 8) return;
                ftypSize = ((long) beInt(ext, 0) << 32) | (beInt(ext, 4) & 0xFFFFFFFFL);
            }
            if (ftypSize < 12 || ftypSize > fileLen) return;
            long pos = ftypSize;
            // 遍历顶层 box 找 moov
            while (pos < fileLen) {
                raf.seek(pos);
                byte[] boxHdr = new byte[8];
                if (raf.read(boxHdr) != 8) break;
                long size = beInt(boxHdr, 0) & 0xFFFFFFFFL;
                String type = new String(boxHdr, 4, 4, StandardCharsets.ISO_8859_1);
                if (size == 1) {
                    // 64 位 size
                    byte[] ext = new byte[8];
                    raf.read(ext);
                    size = ((long) beInt(ext, 0) << 32) | (beInt(ext, 4) & 0xFFFFFFFFL);
                } else if (size == 0) {
                    size = fileLen - pos; // 延伸到文件末尾
                }
                if (size < 8) break;
                if (type.equals("moov")) {
                    parseMoov(raf, pos + 8, pos + size, tags);
                    // 兜底：box 遍历未找到标签时，在 moov 数据中暴力搜索 "ilst" 魔数。
                    // 覆盖各种非标准嵌套结构（trak 内 udta/meta 等）
                    if (tags.title.isEmpty() && tags.artist.isEmpty()
                            && tags.album.isEmpty() && tags.coverImage == null) {
                        bruteForceSearchIlst(raf, pos + 8, pos + size, tags);
                    }
                    MusicMod.LOGGER.debug("M4A tags for {}: title='{}' artist='{}' album='{}' duration={} cover={}",
                            file.getName(), tags.title, tags.artist, tags.album,
                            tags.durationMs, tags.coverImage != null);
                    return;
                }
                pos += size;
            }
        }
    }

    /** 解析 moov box：找 mvhd(时长) 和 udta/meta/ilst(标签) */
    private static void parseMoov(RandomAccessFile raf, long start, long end, SongTags tags) throws IOException {
        long pos = start;
        while (pos + 8 <= end) {
            raf.seek(pos);
            byte[] boxHdr = new byte[8];
            if (raf.read(boxHdr) != 8) break;
            long size = beInt(boxHdr, 0) & 0xFFFFFFFFL;
            String type = new String(boxHdr, 4, 4, StandardCharsets.ISO_8859_1);
            if (size == 1) {
                byte[] ext = new byte[8];
                raf.read(ext);
                size = ((long) beInt(ext, 0) << 32) | (beInt(ext, 4) & 0xFFFFFFFFL);
            } else if (size == 0) {
                size = end - pos;
            }
            if (size < 8) break;
            if (type.equals("mvhd") && tags.durationMs < 0) {
                parseMvhd(raf, pos + 8, pos + size, tags);
            } else if (type.equals("mvex")) {
                // fragmented MP4：mvhd duration=0，实际时长在 mvex/mehd 中
                parseMvex(raf, pos + 8, pos + size, tags);
            } else if (type.equals("udta")) {
                parseUdta(raf, pos + 8, pos + size, tags);
            } else if (type.equals("meta")) {
                // 部分编码器将 meta 直接放在 moov 下（而非 udta 内）
                parseMetaBox(raf, pos, pos + size, tags);
            }
            pos += size;
        }
    }

    /** 解析 mvhd box 获取时长 */
    private static void parseMvhd(RandomAccessFile raf, long start, long end, SongTags tags) throws IOException {
        raf.seek(start);
        byte[] data = new byte[(int) Math.min(end - start, 32)];
        raf.readFully(data);
        int version = data[0] & 0xFF;
        long timescale;
        long duration;
        if (version == 1) {
            // version 1: creation(8) + modification(8) + timescale(4) + duration(8)
            timescale = beInt(data, 20) & 0xFFFFFFFFL;
            duration = ((long) beInt(data, 24) << 32) | (beInt(data, 28) & 0xFFFFFFFFL);
        } else {
            // version 0: creation(4) + modification(4) + timescale(4) + duration(4)
            timescale = beInt(data, 12) & 0xFFFFFFFFL;
            duration = beInt(data, 16) & 0xFFFFFFFFL;
        }
        if (timescale > 0 && duration > 0) {
            tags.durationMs = duration * 1000L / timescale;
        }
        if (timescale > 0) {
            mvhdTimescale = timescale; // 供 mehd 使用（fragmented MP4 mvhd duration=0）
        }
    }

    /** 解析 mvex box：找 mehd（fragmented MP4 总时长） */
    private static void parseMvex(RandomAccessFile raf, long start, long end, SongTags tags) throws IOException {
        long pos = start;
        while (pos + 8 <= end) {
            raf.seek(pos);
            byte[] boxHdr = new byte[8];
            if (raf.read(boxHdr) != 8) break;
            long size = beInt(boxHdr, 0) & 0xFFFFFFFFL;
            String type = new String(boxHdr, 4, 4, StandardCharsets.ISO_8859_1);
            if (size == 1) {
                byte[] ext = new byte[8];
                raf.read(ext);
                size = ((long) beInt(ext, 0) << 32) | (beInt(ext, 4) & 0xFFFFFFFFL);
            } else if (size == 0) {
                size = end - pos;
            }
            if (size < 8) break;
            if (type.equals("mehd") && tags.durationMs < 0) {
                parseMehd(raf, pos + 8, pos + size, tags);
            }
            pos += size;
        }
    }

    /** 解析 mehd box：fragmented MP4 的总时长（以 mvhd timescale 为单位） */
    private static void parseMehd(RandomAccessFile raf, long start, long end, SongTags tags) throws IOException {
        raf.seek(start);
        byte[] data = new byte[(int) Math.min(end - start, 12)];
        raf.readFully(data);
        int version = data[0] & 0xFF;
        long fragmentDuration;
        if (version == 1) {
            // version 1: version(1) + flags(3) + fragment_duration(8)
            fragmentDuration = ((long) beInt(data, 4) << 32) | (beInt(data, 8) & 0xFFFFFFFFL);
        } else {
            // version 0: version(1) + flags(3) + fragment_duration(4)
            fragmentDuration = beInt(data, 4) & 0xFFFFFFFFL;
        }
        if (mvhdTimescale > 0 && fragmentDuration > 0) {
            tags.durationMs = fragmentDuration * 1000L / mvhdTimescale;
        }
    }

    /** 解析 udta box：找 meta */
    private static void parseUdta(RandomAccessFile raf, long start, long end, SongTags tags) throws IOException {
        long pos = start;
        while (pos + 8 <= end) {
            raf.seek(pos);
            byte[] boxHdr = new byte[8];
            if (raf.read(boxHdr) != 8) break;
            long size = beInt(boxHdr, 0) & 0xFFFFFFFFL;
            String type = new String(boxHdr, 4, 4, StandardCharsets.ISO_8859_1);
            if (size == 1) {
                byte[] ext = new byte[8];
                raf.read(ext);
                size = ((long) beInt(ext, 0) << 32) | (beInt(ext, 4) & 0xFFFFFFFFL);
            } else if (size == 0) {
                size = end - pos;
            }
            if (size < 8) break;
            if (type.equals("meta")) {
                parseMetaBox(raf, pos, pos + size, tags);
            }
            pos += size;
        }
    }

    /**
     * 解析 meta box：找 ilst 标签。
     * meta box 有两种格式：
     * 1. ISO FullBox（标准）：8字节头 + 4字节 version/flags，子 box 从 +12 开始
     * 2. QuickTime 旧格式：8字节头，无 version/flags，子 box 从 +8 开始
     * 策略：先按 FullBox 探测第一个子 box 是否合法，不合法则回退到旧格式。
     */
    private static void parseMetaBox(RandomAccessFile raf, long boxStart, long boxEnd, SongTags tags) throws IOException {
        long contentStart = boxStart + 12; // 8 头 + 4 version/flags
        if (contentStart + 8 <= boxEnd) {
            raf.seek(contentStart);
            byte[] probe = new byte[8];
            if (raf.read(probe) == 8) {
                long firstSize = beInt(probe, 0) & 0xFFFFFFFFL;
                if (firstSize < 8 || contentStart + firstSize > boxEnd) {
                    // 第一个子 box 不合法，可能没有 version/flags
                    contentStart = boxStart + 8;
                }
            }
        } else {
            contentStart = boxStart + 8;
        }
        // 遍历 meta 子 box 找 ilst
        long pos = contentStart;
        while (pos + 8 <= boxEnd) {
            raf.seek(pos);
            byte[] boxHdr = new byte[8];
            if (raf.read(boxHdr) != 8) break;
            long size = beInt(boxHdr, 0) & 0xFFFFFFFFL;
            if (size < 8 || pos + size > boxEnd) break;
            String type = new String(boxHdr, 4, 4, StandardCharsets.ISO_8859_1);
            if (type.equals("ilst")) {
                parseIlstTags(raf, pos + 8, pos + size, tags);
            }
            pos += size;
        }
    }

    /**
     * 兜底策略：在 moov 数据中暴力搜索 "ilst" 魔数。
     * 部分 M4A 文件的 meta/ilst 嵌套在非标准位置（如 trak 内的 udta/meta），
     * box 遍历可能遗漏。直接搜索 "ilst" 字节模式可覆盖所有情况。
     */
    private static void bruteForceSearchIlst(RandomAccessFile raf, long start, long end, SongTags tags) throws IOException {
        byte[] buf = new byte[65536];
        long pos = start;
        while (pos < end) {
            int readLen = (int) Math.min(buf.length, end - pos);
            raf.seek(pos);
            int n = raf.read(buf, 0, readLen);
            if (n < 8) break;
            for (int i = 4; i <= n - 4; i++) {
                if (buf[i] == 'i' && buf[i + 1] == 'l' && buf[i + 2] == 's' && buf[i + 3] == 't') {
                    long ilstSize = ((long) (buf[i - 4] & 0xFF) << 24)
                            | ((buf[i - 3] & 0xFF) << 16)
                            | ((buf[i - 2] & 0xFF) << 8)
                            | (buf[i - 1] & 0xFF);
                    long ilstStart = pos + i - 4;
                    if (ilstSize >= 8 && ilstStart + ilstSize <= end) {
                        try {
                            parseIlstTags(raf, ilstStart + 8, ilstStart + ilstSize, tags);
                            if (!tags.title.isEmpty() || !tags.artist.isEmpty()
                                    || !tags.album.isEmpty() || tags.coverImage != null) {
                                return; // 成功解析到标签
                            }
                        } catch (Exception ignored) {}
                    }
                }
            }
            pos += n - 7; // 回退 7 字节防止 "ilst"+size 跨 chunk 边界漏检
        }
    }

    /** 解析 ilst box 的子 box（每个是一个标签，内含 data atom） */
    private static void parseIlstTags(RandomAccessFile raf, long start, long end, SongTags tags) throws IOException {
        long pos = start;
        while (pos + 8 <= end) {
            raf.seek(pos);
            byte[] boxHdr = new byte[8];
            if (raf.read(boxHdr) != 8) break;
            long size = beInt(boxHdr, 0) & 0xFFFFFFFFL;
            if (size < 8 || pos + size > end) break;
            String tagType = new String(boxHdr, 4, 4, StandardCharsets.ISO_8859_1);
            long contentLenL = size - 8;
            if (tagType.equals("covr") && contentLenL > MAX_SCAN && tags.coverImage == null) {
                // 大封面（>1MB）：data atom 超过 MAX_SCAN 截断会导致 dataSize 校验失败，
                // 直接从文件读取完整 payload，绕过内存截断
                readLargeCover(raf, pos + 8, contentLenL, tags);
            } else {
                int contentLen = (int) Math.min(contentLenL, MAX_SCAN);
                byte[] content = new byte[contentLen];
                raf.readFully(content);
                parseIlstTag(tagType, content, tags);
            }
            pos += size;
        }
    }

    /**
     * 读取超大封面（data atom > MAX_SCAN）。
     * 直接从文件读取 data atom 头部判断类型，然后读取完整图片 payload。
     */
    private static void readLargeCover(RandomAccessFile raf, long contentStart, long contentLen, SongTags tags) throws IOException {
        if (contentLen < 16) return;
        raf.seek(contentStart);
        byte[] dataHdr = new byte[16];
        if (raf.read(dataHdr) != 16) return;
        int dataSize = beInt(dataHdr, 0);
        String dataId = new String(dataHdr, 4, 4, StandardCharsets.ISO_8859_1);
        if (!dataId.equals("data") || dataSize < 16) return;
        int typeIndicator = beInt(dataHdr, 8);
        if (typeIndicator != 13 && typeIndicator != 14) return; // 不是 JPEG/PNG
        long payloadLen = (long) dataSize - 16;
        if (payloadLen <= 0 || payloadLen > 20L * 1024 * 1024) return; // 上限 20MB
        byte[] payload = new byte[(int) payloadLen];
        raf.seek(contentStart + 16);
        raf.readFully(payload);
        tags.coverImage = payload;
        tags.coverMime = typeIndicator == 13 ? "image/jpeg" : "image/png";
    }

    /** 解析单个 ilst 标签：找 data atom 并提取文本/封面 */
    private static void parseIlstTag(String tagType, byte[] content, SongTags tags) {
        // data atom: 4字节size + 4字节"data" + 4字节type indicator + 4字节locale + 数据
        int p = 0;
        while (p + 16 <= content.length) {
            int dataSize = beInt(content, p);
            String dataId = new String(content, p + 4, 4, StandardCharsets.ISO_8859_1);
            if (dataSize < 16 || p + dataSize > content.length) break;
            if (dataId.equals("data")) {
                int typeIndicator = beInt(content, p + 8);
                int dataStart = p + 16;
                int dataEnd = p + dataSize;
                byte[] payload = Arrays.copyOfRange(content, dataStart, dataEnd);
                // type 1 = UTF-8 文本, 0 = implicit UTF-8
                if (typeIndicator == 1 || typeIndicator == 0) {
                    String text = trimNull(new String(payload, StandardCharsets.UTF_8));
                    applyIlstText(tagType, text, tags);
                } else if (typeIndicator == 13 || typeIndicator == 14) {
                    // JPEG / PNG 封面
                    if (tagType.equals("covr") && tags.coverImage == null) {
                        tags.coverImage = payload;
                        tags.coverMime = typeIndicator == 13 ? "image/jpeg" : "image/png";
                    }
                }
                return; // 只处理第一个 data atom
            }
            p += dataSize;
        }
    }

    private static void applyIlstText(String tagType, String text, SongTags tags) {
        if (text.isEmpty()) return;
        // © 符号 = 0xA9，在 ISO_8859_1 中是 ©
        switch (tagType) {
            case "©nam": if (tags.title.isEmpty()) tags.title = text; break;
            case "©ART": if (tags.artist.isEmpty()) tags.artist = text; break;
            case "©alb": if (tags.album.isEmpty()) tags.album = text; break;
            case "©lyr": if (tags.lyrics.isEmpty()) tags.lyrics = text; break;
            default: break;
        }
    }

    // ==================== 工具方法 ====================

    private static String extOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    }

    /** ID3 syncsafe 整数（4字节，每字节低7位） */
    private static int syncsafe(byte[] b, int off) {
        return ((b[off] & 0x7F) << 21) | ((b[off + 1] & 0x7F) << 14)
                | ((b[off + 2] & 0x7F) << 7) | (b[off + 3] & 0x7F);
    }

    private static int beInt(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16)
                | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }

    private static int leInt(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8)
                | ((b[off + 2] & 0xFF) << 16) | ((b[off + 3] & 0xFF) << 24);
    }

    private static int findTerminator(byte[] d, int from, int enc) {
        if (enc == 1 || enc == 2) {
            for (int i = from; i + 1 < d.length; i += 2)
                if (d[i] == 0 && d[i + 1] == 0) return i;
            return -1;
        }
        for (int i = from; i < d.length; i++)
            if (d[i] == 0) return i;
        return -1;
    }

    private static int termLen(int enc) {
        return (enc == 1 || enc == 2) ? 2 : 1;
    }

    private static String trimNull(String s) {
        if (s == null) return "";
        int end = s.length();
        while (end > 0 && (s.charAt(end - 1) == '\0' || s.charAt(end - 1) == '\uFFFD')) end--;
        return s.substring(0, end).trim();
    }
}