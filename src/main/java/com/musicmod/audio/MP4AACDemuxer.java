package com.musicmod.audio;

import java.io.*;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * 自写 MP4/fMP4 解复用器，提取 AAC 帧供 jaad Decoder 解码。
 * 支持经典 MP4（moov+mdat）和分片 MP4（fMP4：moov+mvex+moof/mdat 对）。
 * 只处理单音轨 AAC（mp4a）。
 */
public class MP4AACDemuxer {

    private final InputStream in;
    private byte[] audioSpecificConfig;
    private int timescale = 1;
    private int sampleRate;
    private int channels;
    private long duration;

    // fMP4 / 经典 MP4 帧列表
    private final List<FrameInfo> frames = new ArrayList<>();
    private int frameIndex = 0;

    // 用于从文件随机读取（经典 MP4 需要 seek 到 mdat）
    private final File file;
    private RandomAccessFile raf;

    private static class FrameInfo {
        final long offset; // 相对文件起始的绝对偏移
        final int size;
        FrameInfo(long offset, int size) {
            this.offset = offset;
            this.size = size;
        }
    }

    public MP4AACDemuxer(InputStream in) throws IOException {
        this.in = in;
        this.file = null;
        parse();
    }

    public MP4AACDemuxer(File file) throws IOException {
        this.file = file;
        this.in = null;
        this.raf = new RandomAccessFile(file, "r");
        parse();
    }

    public byte[] getAudioSpecificConfig() {
        return audioSpecificConfig;
    }

    public int getSampleRate() {
        return sampleRate;
    }

    public int getChannels() {
        return channels;
    }

    public long getDurationMicros() {
        return duration;
    }

    public boolean hasMoreFrames() {
        return frameIndex < frames.size();
    }

    public byte[] readNextFrame() throws IOException {
        if (frameIndex >= frames.size()) {
            return null;
        }
        FrameInfo fi = frames.get(frameIndex++);
        byte[] data = new byte[fi.size];
        if (raf != null) {
            raf.seek(fi.offset);
            raf.readFully(data);
        } else if (in != null) {
            // 流模式：要求底层支持 mark/reset，且所有数据已在内存或顺序可读
            // 对于 fMP4 流，moof/mdat 是顺序的，但 offset 是相对于 moof 起始
            // 简化：流模式下不支持随机 seek，只能顺序读（适用于 ADTS 或已缓冲的流）
            // 实际上 MP4AACDemuxer 主要给文件用，流走 ADTS 路径
            throw new IOException("Stream mode random access not supported");
        }
        return data;
    }

    // ========== 解析 ==========

    private void parse() throws IOException {
        byte[] buf = new byte[8];
        long pos = 0;
        boolean foundMoov = false;
        boolean isFragmented = false;
        long mdatStart = -1;
        long mdatSize = -1;

        // 第一遍扫描：读取顶层 box
        while (true) {
            if (raf != null) {
                if (raf.getFilePointer() >= raf.length()) break;
                raf.readFully(buf);
            } else {
                int rd = readNBytes(in, buf, 0, 8);
                if (rd < 8) break;
            }
            long size = unsignedInt(buf, 0);
            String type = new String(buf, 4, 4, java.nio.charset.StandardCharsets.US_ASCII);
            long boxStart = pos;
            long boxLen = size;
            if (size == 0) {
                // 到文件末尾
                break;
            } else if (size == 1) {
                // extended size (64-bit)
                byte[] ext = new byte[8];
                if (raf != null) raf.readFully(ext); else readNBytes(in, ext, 0, 8);
                boxLen = unsignedLong(ext, 0);
            }

            if ("moov".equals(type)) {
                foundMoov = true;
                byte[] moovData = new byte[(int) boxLen - 8];
                if (raf != null) raf.readFully(moovData); else readNBytes(in, moovData, 0, moovData.length);
                parseMoov(moovData);
                isFragmented = hasMvex(moovData);
                pos = boxStart + boxLen;
                continue;
            } else if ("mdat".equals(type)) {
                mdatStart = (raf != null) ? raf.getFilePointer() : -1;
                mdatSize = boxLen - 8;
                if (!isFragmented && foundMoov) {
                    // 经典 MP4：mdat 紧跟 moov，用 stbl 中的 stsz/stco 定位帧
                    // 已经通过 parseMoov 拿到了帧列表（如果有 stsz/stco）
                }
                // 跳过 mdat 数据
                if (raf != null) {
                    raf.seek(boxStart + boxLen);
                } else {
                    skipNBytes(in, boxLen - 8);
                }
                pos = boxStart + boxLen;
                continue;
            } else if ("moof".equals(type)) {
                // fMP4：解析 moof
                byte[] moofData = new byte[(int) boxLen - 8];
                if (raf != null) raf.readFully(moofData); else readNBytes(in, moofData, 0, moofData.length);
                long moofStart = boxStart;
                parseMoof(moofData, moofStart);
                pos = boxStart + boxLen;
                continue;
            } else {
                // 跳过未知 box
                long skip = boxLen - 8;
                if (raf != null) {
                    raf.seek(boxStart + boxLen);
                } else {
                    skipNBytes(in, skip);
                }
                pos = boxStart + boxLen;
            }
        }

        if (!foundMoov) {
            throw new IOException("moov box not found");
        }

        // 经典 MP4（非 fMP4）：如果 stbl 有有效条目，已经通过 parseMoov 填充了 frames
        // 如果 stbl 为空（fMP4），frames 由 parseMoof 填充
    }

    // ========== moov 解析 ==========

    private void parseMoov(byte[] data) throws IOException {
        ByteBuffer bb = ByteBuffer.wrap(data);
        while (bb.remaining() >= 8) {
            int boxStart = bb.position();
            int size = bb.getInt();
            if (size < 8 || size > bb.remaining() + 8) break;
            String type = readType(bb);
            if ("mvhd".equals(type)) {
                parseMvhd(bb, size - 8);
            } else if ("trak".equals(type)) {
                parseTrak(bb, size - 8);
            }
            // 无论子解析器消费了多少字节，统一重置到 box 边界
            bb.position(boxStart + size);
        }
    }

    private boolean hasMvex(byte[] data) {
        ByteBuffer bb = ByteBuffer.wrap(data);
        while (bb.remaining() >= 8) {
            int size = bb.getInt();
            if (size < 8) break;
            String type = readType(bb);
            if ("mvex".equals(type)) return true;
            if (bb.remaining() < size - 8) break;
            bb.position(bb.position() + size - 8);
        }
        return false;
    }

    private void parseMvhd(ByteBuffer bb, int len) {
        if (len < 12) { bb.position(bb.position() + len); return; }
        int version = bb.get() & 0xFF;
        bb.get(); // flags
        if (version == 0) {
            if (len < 20) { bb.position(bb.position() + len - 4); return; }
            bb.getInt(); // creation_time
            bb.getInt(); // modification_time
            this.timescale = bb.getInt();
            this.duration = (bb.getInt() & 0xFFFFFFFFL) * 1_000_000L / Math.max(1, this.timescale);
        } else {
            if (len < 32) { bb.position(bb.position() + len - 4); return; }
            bb.getLong(); bb.getLong();
            this.timescale = bb.getInt();
            this.duration = bb.getLong() * 1_000_000L / Math.max(1, this.timescale);
        }
    }

    private void parseTrak(ByteBuffer bb, int len) throws IOException {
        int end = bb.position() + len;
        while (bb.position() < end && bb.remaining() >= 8) {
            int boxStart = bb.position();
            int size = bb.getInt();
            if (size < 8 || size > bb.remaining() + 8) break;
            String type = readType(bb);
            if ("mdia".equals(type)) {
                parseMdia(bb, size - 8);
            }
            bb.position(boxStart + size);
        }
    }

    private void parseMdia(ByteBuffer bb, int len) throws IOException {
        int end = bb.position() + len;
        while (bb.position() < end && bb.remaining() >= 8) {
            int boxStart = bb.position();
            int size = bb.getInt();
            if (size < 8 || size > bb.remaining() + 8) break;
            String type = readType(bb);
            if ("minf".equals(type)) {
                parseMinf(bb, size - 8);
            } else if ("mdhd".equals(type)) {
                parseMdhd(bb, size - 8);
            }
            bb.position(boxStart + size);
        }
    }

    private void parseMdhd(ByteBuffer bb, int len) {
        if (len < 12) { bb.position(bb.position() + len); return; }
        int version = bb.get() & 0xFF;
        bb.get();
        if (version == 0) {
            if (len < 20) { bb.position(bb.position() + len - 4); return; }
            bb.getInt(); bb.getInt();
            int mdiaTimescale = bb.getInt();
            long mdiaDuration = bb.getInt() & 0xFFFFFFFFL;
            if (mdiaTimescale > 0) {
                this.duration = mdiaDuration * 1_000_000L / mdiaTimescale;
            }
        } else {
            if (len < 32) { bb.position(bb.position() + len - 4); return; }
            bb.getLong(); bb.getLong();
            int mdiaTimescale = bb.getInt();
            long mdiaDuration = bb.getLong();
            if (mdiaTimescale > 0) {
                this.duration = mdiaDuration * 1_000_000L / mdiaTimescale;
            }
        }
    }

    private void parseMinf(ByteBuffer bb, int len) throws IOException {
        int end = bb.position() + len;
        while (bb.position() < end && bb.remaining() >= 8) {
            int boxStart = bb.position();
            int size = bb.getInt();
            if (size < 8 || size > bb.remaining() + 8) break;
            String type = readType(bb);
            if ("stbl".equals(type)) {
                parseStbl(bb, size - 8);
            }
            bb.position(boxStart + size);
        }
    }

    private void parseStbl(ByteBuffer bb, int len) throws IOException {
        int end = bb.position() + len;
        int[] sampleSizes = null;
        int[] chunkOffsets = null;
        int[] sampleToChunk = null;
        while (bb.position() < end && bb.remaining() >= 8) {
            int boxStart = bb.position();
            int size = bb.getInt();
            if (size < 8 || size > bb.remaining() + 8) break;
            String type = readType(bb);
            if ("stsd".equals(type)) {
                parseStsd(bb, size - 8);
            } else if ("stsz".equals(type)) {
                sampleSizes = parseStsz(bb, size - 8);
            } else if ("stco".equals(type)) {
                chunkOffsets = parseStco(bb, size - 8);
            } else if ("co64".equals(type)) {
                chunkOffsets = parseCo64(bb, size - 8);
            } else if ("stsc".equals(type)) {
                sampleToChunk = parseStsc(bb, size - 8);
            }
            bb.position(boxStart + size);
        }
        // 经典 MP4：根据 stsz + stco 构建帧列表
        if (sampleSizes != null && chunkOffsets != null && sampleSizes.length > 0) {
            buildClassicFrames(sampleSizes, chunkOffsets, sampleToChunk);
        }
    }

    private void parseStsd(ByteBuffer bb, int len) {
        if (len < 8) { bb.position(bb.position() + len); return; }
        int version = bb.get() & 0xFF;
        bb.get(); bb.get(); bb.get(); // flags
        int entryCount = bb.getInt();
        for (int i = 0; i < entryCount && bb.remaining() >= 8; i++) {
            int entryStart = bb.position();
            int entrySize = bb.getInt();
            if (entrySize < 8 || entrySize > bb.remaining() + 8) break;
            String entryType = readType(bb);
            if ("mp4a".equals(entryType)) {
                parseMp4a(bb, entrySize - 8);
            }
            bb.position(entryStart + entrySize);
        }
    }

    private void parseMp4a(ByteBuffer bb, int len) {
        if (len < 28) { bb.position(bb.position() + len); return; }
        // SampleEntry: 6 bytes reserved + 2 bytes data_reference_index
        bb.get(); bb.get(); bb.get(); bb.get(); bb.get(); bb.get(); // reserved (6)
        bb.getShort(); // data_reference_index
        // AudioSampleEntry: 8 bytes reserved + channelcount + samplesize + pre_defined + reserved + samplerate
        bb.get(); bb.get(); bb.get(); bb.get(); bb.get(); bb.get(); bb.get(); bb.get(); // reserved (8)
        int channelCount = bb.getShort() & 0xFFFF;
        bb.getShort(); // samplesize
        bb.getShort(); // pre_defined
        bb.getShort(); // reserved
        int samplerate = bb.getInt() >>> 16; // 16.16 fixed point, 无符号右移
        if (samplerate > 0) this.sampleRate = samplerate;
        if (channelCount > 0) this.channels = channelCount;

        // 在 mp4a 子 box 中找 esds 或 wave/esds
        int end = bb.position() + len - 28;
        while (bb.position() < end && bb.remaining() >= 8) {
            int boxStart = bb.position();
            int size = bb.getInt();
            if (size < 8 || size > bb.remaining() + 8) break;
            String type = readType(bb);
            if ("esds".equals(type)) {
                parseEsds(bb, size - 8);
                bb.position(boxStart + size);
            } else if ("wave".equals(type)) {
                parseWave(bb, size - 8);
                bb.position(boxStart + size);
            } else {
                bb.position(boxStart + size);
            }
        }
    }

    private void parseWave(ByteBuffer bb, int len) {
        int end = bb.position() + len;
        while (bb.position() < end && bb.remaining() >= 8) {
            int boxStart = bb.position();
            int size = bb.getInt();
            if (size < 8 || size > bb.remaining() + 8) break;
            String type = readType(bb);
            if ("esds".equals(type)) {
                parseEsds(bb, size - 8);
            }
            bb.position(boxStart + size);
        }
    }

    private void parseEsds(ByteBuffer bb, int len) {
        if (len < 12) { bb.position(bb.position() + len); return; }
        int version = bb.get() & 0xFF;
        bb.get(); bb.get(); bb.get(); // flags
        // ES_Descriptor tag = 0x03
        int tag = bb.get() & 0xFF;
        int esLen = readDescriptorLength(bb);
        if (tag == 0x03 && esLen > 0) {
            bb.getShort(); // ES_ID
            bb.get(); // streamDependenceFlag etc.
            // DecoderConfigDescriptor tag = 0x04
            if (bb.remaining() < 1) return;
            tag = bb.get() & 0xFF;
            int dcLen = readDescriptorLength(bb);
            if (tag == 0x04 && dcLen > 0) {
                bb.get(); // objectTypeIndication
                bb.get(); // streamType + upstream + reserved
                // bufferSizeDB 是 3 字节（24位），不是 4 字节
                int bufferSizeDB = ((bb.get() & 0xFF) << 16) | ((bb.get() & 0xFF) << 8) | (bb.get() & 0xFF);
                bb.getInt(); // maxBitrate
                bb.getInt(); // avgBitrate
                // DecoderSpecificInfo tag = 0x05
                if (bb.remaining() < 1) return;
                tag = bb.get() & 0xFF;
                int dsLen = readDescriptorLength(bb);
                if (tag == 0x05 && dsLen > 0 && dsLen <= bb.remaining()) {
                    this.audioSpecificConfig = new byte[dsLen];
                    bb.get(this.audioSpecificConfig);
                }
            }
        }
    }

    private int readDescriptorLength(ByteBuffer bb) {
        int len = 0;
        int b;
        do {
            b = bb.get() & 0xFF;
            len = (len << 7) | (b & 0x7F);
        } while ((b & 0x80) != 0 && bb.remaining() > 0);
        return len;
    }

    private int[] parseStsz(ByteBuffer bb, int len) {
        if (len < 8) { bb.position(bb.position() + len); return null; }
        int version = bb.get() & 0xFF;
        bb.get(); bb.get(); bb.get(); // flags
        int sampleSize = bb.getInt();
        int count = bb.getInt();
        if (count < 0 || count > 10_000_000) { bb.position(bb.position() + len - 8); return null; }
        int[] sizes = new int[count];
        if (sampleSize != 0) {
            for (int i = 0; i < count; i++) sizes[i] = sampleSize;
        } else {
            for (int i = 0; i < count && bb.remaining() >= 4; i++) {
                sizes[i] = bb.getInt();
            }
        }
        return sizes;
    }

    private int[] parseStco(ByteBuffer bb, int len) {
        if (len < 8) { bb.position(bb.position() + len); return null; }
        bb.get(); bb.get(); bb.get(); bb.get(); // version+flags
        int count = bb.getInt();
        if (count < 0 || count > 10_000_000) { bb.position(bb.position() + len - 8); return null; }
        int[] offsets = new int[count];
        for (int i = 0; i < count && bb.remaining() >= 4; i++) {
            offsets[i] = bb.getInt();
        }
        return offsets;
    }

    private int[] parseCo64(ByteBuffer bb, int len) {
        if (len < 8) { bb.position(bb.position() + len); return null; }
        bb.get(); bb.get(); bb.get(); bb.get(); // version+flags
        int count = bb.getInt();
        if (count < 0 || count > 10_000_000) { bb.position(bb.position() + len - 8); return null; }
        int[] offsets = new int[count];
        for (int i = 0; i < count && bb.remaining() >= 8; i++) {
            offsets[i] = (int) bb.getLong(); // 截断到 int（文件<2GB）
        }
        return offsets;
    }

    private int[] parseStsc(ByteBuffer bb, int len) {
        if (len < 8) { bb.position(bb.position() + len); return null; }
        bb.get(); bb.get(); bb.get(); bb.get(); // version+flags
        int count = bb.getInt();
        if (count < 0 || count > 10_000_000) { bb.position(bb.position() + len - 8); return null; }
        // 简单处理：每个 chunk 的样本数
        int[] samplesPerChunk = new int[count];
        for (int i = 0; i < count && bb.remaining() >= 12; i++) {
            bb.getInt(); // first_chunk
            samplesPerChunk[i] = bb.getInt(); // samples_per_chunk
            bb.getInt(); // sample_description_index
        }
        return samplesPerChunk;
    }

    private void buildClassicFrames(int[] sampleSizes, int[] chunkOffsets, int[] sampleToChunk) {
        int sampleIdx = 0;
        for (int chunkIdx = 0; chunkIdx < chunkOffsets.length && sampleIdx < sampleSizes.length; chunkIdx++) {
            int samplesInChunk = getSamplesInChunk(chunkIdx, sampleToChunk);
            long offset = chunkOffsets[chunkIdx] & 0xFFFFFFFFL;
            for (int s = 0; s < samplesInChunk && sampleIdx < sampleSizes.length; s++) {
                int sz = sampleSizes[sampleIdx];
                frames.add(new FrameInfo(offset, sz));
                offset += sz;
                sampleIdx++;
            }
        }
    }

    private int getSamplesInChunk(int chunkIdx, int[] sampleToChunk) {
        if (sampleToChunk == null || sampleToChunk.length == 0) return 1;
        // sampleToChunk 是递增的 first_chunk，找到当前 chunk 对应的 samples_per_chunk
        // 简化：假设只有一个 entry
        return sampleToChunk[sampleToChunk.length - 1];
    }

    // ========== moof 解析（fMP4）==========

    private void parseMoof(byte[] moofData, long moofStart) throws IOException {
        this.moofBaseOffset = moofStart;
        ByteBuffer bb = ByteBuffer.wrap(moofData);
        long baseOffset = moofStart + 8; // moof 头之后
        long dataOffset = baseOffset;
        int sampleCount = 0;
        int[] sampleSizes = null;
        int[] sampleFlags = null;

        while (bb.remaining() >= 8) {
            int boxStart = bb.position();
            int size = bb.getInt();
            if (size < 8 || size > bb.remaining() + 8) break;
            String type = readType(bb);
            if ("traf".equals(type)) {
                // 解析 traf 内部
                int trafEnd = boxStart + size;
                while (bb.position() < trafEnd && bb.remaining() >= 8) {
                    int childStart = bb.position();
                    int childSize = bb.getInt();
                    if (childSize < 8 || childSize > bb.remaining() + 8) break;
                    String childType = readType(bb);
                    if ("tfhd".equals(childType)) {
                        parseTfhd(bb, childSize - 8);
                    } else if ("trun".equals(childType)) {
                        TrunInfo ti = parseTrun(bb, childSize - 8);
                        sampleCount = ti.sampleCount;
                        sampleSizes = ti.sampleSizes;
                        sampleFlags = ti.sampleFlags;
                        dataOffset = ti.dataOffset;
                    }
                    bb.position(childStart + childSize);
                }
            }
            bb.position(boxStart + size);
        }

        if (sampleCount > 0 && sampleSizes != null) {
            for (int i = 0; i < sampleCount; i++) {
                int sz = sampleSizes[i];
                // 跳过空的或填充帧（flags 指示）
                if (sampleFlags != null && i < sampleFlags.length) {
                    int flags = sampleFlags[i];
                    // sample_is_non_sync_sample = (flags >> 16) & 1; 不处理
                }
                frames.add(new FrameInfo(dataOffset, sz));
                dataOffset += sz;
            }
        }
    }

    private void parseTfhd(ByteBuffer bb, int len) {
        if (len < 4) { bb.position(bb.position() + len); return; }
        int version = bb.get() & 0xFF;
        int flags = ((bb.get() & 0xFF) << 16) | ((bb.get() & 0xFF) << 8) | (bb.get() & 0xFF);
        bb.getInt(); // track_ID
        if ((flags & 0x000001) != 0) bb.getLong(); // base_data_offset
        if ((flags & 0x000002) != 0) bb.getInt(); // sample_description_index
        if ((flags & 0x000008) != 0) bb.getInt(); // default_sample_duration
        if ((flags & 0x000010) != 0) bb.getInt(); // default_sample_size
        if ((flags & 0x000020) != 0) bb.getInt(); // default_sample_flags
    }

    private static class TrunInfo {
        int sampleCount;
        int[] sampleSizes;
        int[] sampleFlags;
        long dataOffset;
    }

    private TrunInfo parseTrun(ByteBuffer bb, int len) {
        TrunInfo ti = new TrunInfo();
        if (len < 4) { bb.position(bb.position() + len); return ti; }
        int version = bb.get() & 0xFF;
        int flags = ((bb.get() & 0xFF) << 16) | ((bb.get() & 0xFF) << 8) | (bb.get() & 0xFF);
        ti.sampleCount = bb.getInt();
        if (ti.sampleCount < 0 || ti.sampleCount > 1_000_000) {
            bb.position(bb.position() + len - 8);
            ti.sampleCount = 0;
            return ti;
        }

        if ((flags & 0x000001) != 0) {
            // data_offset 相对于 moof 起始（已包含 moof 的 8 字节头）
            ti.dataOffset = (moofBaseOffset + bb.getInt()) & 0xFFFFFFFFL;
        }
        if ((flags & 0x000004) != 0) bb.getInt(); // first_sample_flags

        boolean hasDuration = (flags & 0x000100) != 0;
        boolean hasSize = (flags & 0x000200) != 0;
        boolean hasFlags = (flags & 0x000400) != 0;
        boolean hasOffset = (flags & 0x000800) != 0;

        ti.sampleSizes = new int[ti.sampleCount];
        ti.sampleFlags = new int[ti.sampleCount];
        for (int i = 0; i < ti.sampleCount && bb.remaining() >= 4; i++) {
            if (hasDuration) bb.getInt();
            if (hasSize) ti.sampleSizes[i] = bb.getInt();
            if (hasFlags) ti.sampleFlags[i] = bb.getInt();
            if (hasOffset) bb.getInt();
        }
        return ti;
    }

    private long moofBaseOffset = 0; // 当前 moof 的起始偏移

    // ========== 工具方法 ==========

    private static String readType(ByteBuffer bb) {
        byte[] b = new byte[4];
        bb.get(b);
        return new String(b, java.nio.charset.StandardCharsets.US_ASCII);
    }

    private static long unsignedInt(byte[] b, int off) {
        return ((b[off] & 0xFFL) << 24) | ((b[off + 1] & 0xFFL) << 16) | ((b[off + 2] & 0xFFL) << 8) | (b[off + 3] & 0xFFL);
    }

    private static long unsignedLong(byte[] b, int off) {
        return ((b[off] & 0xFFL) << 56) | ((b[off + 1] & 0xFFL) << 48) | ((b[off + 2] & 0xFFL) << 40) | ((b[off + 3] & 0xFFL) << 32)
                | ((b[off + 4] & 0xFFL) << 24) | ((b[off + 5] & 0xFFL) << 16) | ((b[off + 6] & 0xFFL) << 8) | (b[off + 7] & 0xFFL);
    }

    private static int readNBytes(InputStream in, byte[] b, int off, int len) throws IOException {
        int total = 0;
        while (total < len) {
            int rd = in.read(b, off + total, len - total);
            if (rd < 0) break;
            total += rd;
        }
        return total;
    }

    private static void skipNBytes(InputStream in, long n) throws IOException {
        long remaining = n;
        while (remaining > 0) {
            long skipped = in.skip(remaining);
            if (skipped <= 0) {
                int b = in.read();
                if (b < 0) break;
                remaining--;
            } else {
                remaining -= skipped;
            }
        }
    }
}