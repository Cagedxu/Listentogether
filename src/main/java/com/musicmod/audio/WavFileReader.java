package com.musicmod.audio;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;

/**
 * 轻量 WAV(PCM) 读取器。
 * Fabric 运行时 mp3spi 的 MpegAudioFileReader 会把 WAV 文件误判为
 * 8 声道 MPEG，导致播放瞬间结束；这里直接解析 RIFF/WAVE 头，
 * 绕开 Java Sound 的 SPI 分派。非 PCM WAV 回退给 AudioSystem。
 */
public final class WavFileReader {

    private WavFileReader() {}

    /** 从网络输入流解析 WAV 头，返回可边下边播的 PCM 流。 */
    public static AudioInputStream open(InputStream input) throws Exception {
        BufferedInputStream in = input instanceof BufferedInputStream
                ? (BufferedInputStream) input : new BufferedInputStream(input, 1 << 20);

        if (readIntLE(in) != 0x46464952) throw new IOException("not RIFF");
        readIntLE(in); // file size (可能为 0/未知)
        if (readIntLE(in) != 0x45564157) throw new IOException("not WAVE");

        int channels = -1, sampleRate = -1, bits = -1, blockAlign = -1, audioFormat = -1;
        long dataSize = -1;
        boolean foundData = false;

        while (true) {
            int chunkId = readIntLE(in);
            long chunkSize = readIntLE(in) & 0xFFFFFFFFL;
            if (chunkId == 0x20746D66) { // "fmt "
                if (chunkSize < 16) throw new IOException("invalid fmt chunk");
                audioFormat = readShortLE(in);
                channels = readShortLE(in);
                sampleRate = readIntLE(in);
                readIntLE(in); // byte rate
                blockAlign = readShortLE(in);
                bits = readShortLE(in);
                long remaining = chunkSize - 16;
                if (audioFormat == 0xFFFE && chunkSize >= 40) {
                    readShortLE(in); // cbSize
                    readShortLE(in); // valid bits
                    readIntLE(in);   // channel mask
                    audioFormat = readShortLE(in); // subformat first 2 bytes
                    remaining -= 10;
                }
                skipFully(in, remaining + (chunkSize & 1));
            } else if (chunkId == 0x61746164) { // "data"
                dataSize = chunkSize == 0 || chunkSize == 0xFFFFFFFFL ? Long.MAX_VALUE : chunkSize;
                foundData = true;
                break;
            } else {
                skipFully(in, chunkSize + (chunkSize & 1));
            }
        }

        if (!foundData || channels <= 0 || sampleRate <= 0 || bits <= 0 || blockAlign <= 0) {
            throw new IOException("invalid WAV header");
        }
        if (audioFormat != 1 && audioFormat != 3) {
            throw new IOException("unsupported WAV format: " + audioFormat);
        }

        AudioFormat.Encoding encoding = audioFormat == 3
                ? AudioFormat.Encoding.PCM_FLOAT
                : (bits == 8 ? AudioFormat.Encoding.PCM_UNSIGNED : AudioFormat.Encoding.PCM_SIGNED);
        AudioFormat format = new AudioFormat(encoding, sampleRate, bits, channels, blockAlign, sampleRate, false);
        long frameLength = dataSize == Long.MAX_VALUE ? AudioSystem.NOT_SPECIFIED
                : dataSize / format.getFrameSize();
        return new AudioInputStream(new LimitedInputStream(in, dataSize), format, frameLength);
    }

    public static AudioInputStream open(File file) throws Exception {
        long dataOffset;
        long dataSize;
        AudioFormat format;

        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            if (readIntLE(raf) != 0x46464952) { // "RIFF"
                throw new IOException("not RIFF");
            }
            raf.readInt(); // file size
            if (readIntLE(raf) != 0x45564157) { // "WAVE"
                throw new IOException("not WAVE");
            }

            int channels = -1;
            int sampleRate = -1;
            int bits = -1;
            int blockAlign = -1;
            int audioFormat = -1;
            dataOffset = -1;
            dataSize = -1;

            while (raf.getFilePointer() + 8 <= raf.length()) {
                int chunkId = readIntLE(raf);
                long chunkSize = readIntLE(raf) & 0xFFFFFFFFL;
                long chunkStart = raf.getFilePointer();

                if (chunkId == 0x20746D66) { // "fmt "
                    audioFormat = readShortLE(raf);
                    channels = readShortLE(raf);
                    sampleRate = readIntLE(raf);
                    raf.readInt(); // byte rate
                    blockAlign = readShortLE(raf);
                    bits = readShortLE(raf);
                    if (audioFormat == 0xFFFE && chunkSize >= 40) {
                        // WAVE_FORMAT_EXTENSIBLE: 子格式 GUID 前两字节为格式码
                        raf.skipBytes(2 + 2 + 4 + 4); // cbSize, validBits, channelMask
                        audioFormat = readShortLE(raf);
                    }
                } else if (chunkId == 0x61746164) { // "data"
                    dataOffset = chunkStart;
                    long remaining = file.length() - chunkStart;
                    dataSize = chunkSize == 0 || chunkSize == 0xFFFFFFFFL
                            ? remaining : Math.min(chunkSize, remaining);
                    break;
                }

                long next = chunkStart + chunkSize + (chunkSize & 1);
                if (next <= chunkStart || next > raf.length()) break;
                raf.seek(next);
            }

            if (dataOffset < 0 || dataSize <= 0 || channels <= 0 || sampleRate <= 0
                    || bits <= 0 || blockAlign <= 0) {
                throw new IOException("invalid WAV header");
            }
            if (audioFormat != 1 && audioFormat != 3) {
                throw new IOException("unsupported WAV format: " + audioFormat);
            }

            AudioFormat.Encoding encoding = audioFormat == 3
                    ? AudioFormat.Encoding.PCM_FLOAT
                    : (bits == 8 ? AudioFormat.Encoding.PCM_UNSIGNED : AudioFormat.Encoding.PCM_SIGNED);
            format = new AudioFormat(encoding, sampleRate, bits, channels, blockAlign, sampleRate, false);
        }

        FileInputStream fis = new FileInputStream(file);
        try {
            fis.skipNBytes(dataOffset);
        } catch (IOException e) {
            fis.close();
            throw e;
        }
        BufferedInputStream data = new BufferedInputStream(fis, 64 * 1024);
        return new AudioInputStream(data, format, dataSize / format.getFrameSize());
    }

    private static int readShortLE(RandomAccessFile raf) throws IOException {
        int b0 = raf.read();
        int b1 = raf.read();
        if (b1 < 0) throw new IOException("unexpected EOF");
        return (b0 & 0xFF) | ((b1 & 0xFF) << 8);
    }

    private static int readIntLE(RandomAccessFile raf) throws IOException {
        int b0 = raf.read();
        int b1 = raf.read();
        int b2 = raf.read();
        int b3 = raf.read();
        if (b3 < 0) throw new IOException("unexpected EOF");
        return (b0 & 0xFF) | ((b1 & 0xFF) << 8) | ((b2 & 0xFF) << 16) | ((b3 & 0xFF) << 24);
    }

    private static void skipFully(InputStream in, long bytes) throws IOException {
        long remaining = bytes;
        byte[] buf = new byte[4096];
        while (remaining > 0) {
            int read = in.read(buf, 0, (int) Math.min(buf.length, remaining));
            if (read < 0) throw new IOException("unexpected EOF");
            remaining -= read;
        }
    }

    private static int readShortLE(InputStream in) throws IOException {
        int b0 = in.read();
        int b1 = in.read();
        if (b1 < 0) throw new IOException("unexpected EOF");
        return (b0 & 0xFF) | ((b1 & 0xFF) << 8);
    }

    private static int readIntLE(InputStream in) throws IOException {
        int b0 = in.read();
        int b1 = in.read();
        int b2 = in.read();
        int b3 = in.read();
        if (b3 < 0) throw new IOException("unexpected EOF");
        return (b0 & 0xFF) | ((b1 & 0xFF) << 8) | ((b2 & 0xFF) << 16) | ((b3 & 0xFF) << 24);
    }

    private static final class LimitedInputStream extends InputStream {
        private final InputStream source;
        private long remaining;
        LimitedInputStream(InputStream source, long limit) {
            this.source = source;
            this.remaining = limit;
        }
        @Override public int read() throws IOException {
            if (remaining <= 0) return -1;
            int b = source.read();
            if (b >= 0) remaining--;
            return b;
        }
        @Override public int read(byte[] b, int off, int len) throws IOException {
            if (remaining <= 0) return -1;
            int n = source.read(b, off, (int) Math.min(len, remaining));
            if (n > 0) remaining -= n;
            return n;
        }
        @Override public int available() throws IOException {
            return (int) Math.min(source.available(), remaining);
        }
        @Override public void close() throws IOException { source.close(); }
    }
}
