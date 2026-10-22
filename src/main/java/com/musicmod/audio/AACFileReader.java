package com.musicmod.audio;

import net.sourceforge.jaad.SampleBuffer;
import net.sourceforge.jaad.aac.Decoder;
import net.sourceforge.jaad.adts.ADTSDemultiplexer;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.UnsupportedAudioFileException;
import javax.sound.sampled.spi.AudioFileReader;
import java.io.*;
import java.net.URL;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

import static java.nio.file.StandardOpenOption.READ;
import static javax.sound.sampled.AudioSystem.NOT_SPECIFIED;

/**
 * 自定义 AAC/M4A AudioFileReader SPI，替代 jaad 的 AACAudioFileReader。
 * 魔数分派：只接受真 MP4（ftyp 魔数）和 ADTS（0xFFF 同步字），拒绝 MP3/FLAC/OGG/WAV 等。
 * MP4 路径：使用自写的 MP4AACDemuxer 支持经典 MP4 和 fMP4，复用 jaad Decoder 逐帧解码。
 * ADTS 路径：复用 jaad 的 ADTSDemultiplexer。
 */
public class AACFileReader extends AudioFileReader {

    private static final int ADTS_SYNC = 0xFFF0;
    private static final int ADTS_MASK = 0xFFF6;

    @Override
    public AudioFileFormat getAudioFileFormat(InputStream in) throws UnsupportedAudioFileException, IOException {
        if (!in.markSupported()) in = new BufferedInputStream(in);
        in.mark(4096);
        try {
            FormatType ft = detectFormat(in);
            in.reset();
            if (ft == FormatType.MP4) {
                return decodeMP4FileFormat(in);
            } else if (ft == FormatType.ADTS) {
                return decodeADTSFileFormat(in);
            } else {
                throw new UnsupportedAudioFileException("not AAC/MP4/ADTS");
            }
        } catch (UnsupportedAudioFileException e) {
            in.reset();
            throw e;
        } catch (IOException e) {
            in.reset();
            throw e;
        }
    }

    @Override
    public AudioFileFormat getAudioFileFormat(URL url) throws UnsupportedAudioFileException, IOException {
        try (InputStream in = url.openStream()) {
            return getAudioFileFormat(in);
        }
    }

    @Override
    public AudioFileFormat getAudioFileFormat(File file) throws UnsupportedAudioFileException, IOException {
        try (InputStream in = Files.newInputStream(file.toPath(), READ)) {
            FormatType ft = detectFormat(in);
            if (ft == FormatType.MP4) {
                return decodeMP4FileFormat(file);
            } else if (ft == FormatType.ADTS) {
                try (InputStream in2 = Files.newInputStream(file.toPath(), READ)) {
                    return decodeADTSFileFormat(in2);
                }
            } else {
                throw new UnsupportedAudioFileException("not AAC/MP4/ADTS: " + file.getName());
            }
        }
    }

    @Override
    public AudioInputStream getAudioInputStream(InputStream in) throws UnsupportedAudioFileException, IOException {
        if (!in.markSupported()) throw new IllegalArgumentException("in.markSupported() == false");
        in.mark(4096);
        try {
            FormatType ft = detectFormat(in);
            in.reset();
            if (ft == FormatType.MP4) {
                return decodeMP4Stream(in);
            } else if (ft == FormatType.ADTS) {
                return decodeADTSStream(in);
            } else {
                throw new UnsupportedAudioFileException("not AAC/MP4/ADTS");
            }
        } catch (UnsupportedAudioFileException e) {
            in.reset();
            throw e;
        } catch (IOException e) {
            in.reset();
            throw e;
        }
    }

    @Override
    public AudioInputStream getAudioInputStream(URL url) throws UnsupportedAudioFileException, IOException {
        InputStream in = url.openStream();
        try {
            return getAudioInputStream(in.markSupported() ? in : new BufferedInputStream(in));
        } catch (UnsupportedAudioFileException | IOException e) {
            in.close();
            throw e;
        }
    }

    @Override
    public AudioInputStream getAudioInputStream(File file) throws UnsupportedAudioFileException, IOException {
        FormatType ft;
        try (InputStream in = Files.newInputStream(file.toPath(), READ)) {
            ft = detectFormat(in);
        }
        if (ft == FormatType.MP4) {
            return decodeMP4Stream(file);
        } else if (ft == FormatType.ADTS) {
            try (InputStream in = Files.newInputStream(file.toPath(), READ)) {
                return decodeADTSStream(in);
            }
        }
        throw new UnsupportedAudioFileException("not AAC/MP4/ADTS: " + file.getName());
    }

    // ========== 格式检测 ==========

    private enum FormatType { MP4, ADTS, UNKNOWN }

    private static FormatType detectFormat(InputStream in) throws IOException {
        byte[] head = new byte[12];
        int rd = readNBytes(in, head, 0, 12);
        if (rd < 4) return FormatType.UNKNOWN;

        // MP4: ftyp at offset 4
        if (rd >= 8 && head[4] == 'f' && head[5] == 't' && head[6] == 'y' && head[7] == 'p') {
            return FormatType.MP4;
        }

        // ADTS: sync word 0xFFF (12 bits), ID=1 → 0xFFFx where (x&0xF6)==0xF0
        int sync = ((head[0] & 0xFF) << 8) | (head[1] & 0xFF);
        if ((sync & ADTS_MASK) == ADTS_SYNC) {
            return FormatType.ADTS;
        }

        // 明确拒绝其他已知格式（避免 jaad 误判 MP3）
        if (isKnownNonAAC(head, rd)) {
            return FormatType.UNKNOWN;
        }

        return FormatType.UNKNOWN;
    }

    private static boolean isMP4File(File file) throws IOException {
        try (InputStream in = Files.newInputStream(file.toPath(), READ)) {
            return detectFormat(in) == FormatType.MP4;
        }
    }

    private static boolean isKnownNonAAC(byte[] h, int len) {
        // MP3: ID3 or MPEG sync
        if (h[0] == 'I' && h[1] == 'D' && h[2] == '3') return true;
        if (len >= 2 && (h[0] & 0xFF) == 0xFF && ((h[1] & 0xE0) == 0xE0)) return true; // MPEG sync (not ADTS)
        // FLAC
        if (h[0] == 'f' && h[1] == 'L' && h[2] == 'a' && h[3] == 'C') return true;
        // OGG
        if (h[0] == 'O' && h[1] == 'g' && h[2] == 'g' && h[3] == 'S') return true;
        // RIFF/WAV
        if (h[0] == 'R' && h[1] == 'I' && h[2] == 'F' && h[3] == 'F') return true;
        // AIFF
        if (h[0] == 'F' && h[1] == 'O' && h[2] == 'R' && h[3] == 'M') return true;
        // AU
        if (h[0] == '.' && h[1] == 's' && h[2] == 'n' && h[3] == 'd') return true;
        return false;
    }

    // ========== MP4 解码 ==========

    private AudioFileFormat decodeMP4FileFormat(InputStream in) throws UnsupportedAudioFileException, IOException {
        // 流模式下无法随机 seek，不支持获取 file format（需要解析 moov）
        throw new UnsupportedAudioFileException("MP4 stream file format not supported");
    }

    private AudioFileFormat decodeMP4FileFormat(File file) throws UnsupportedAudioFileException, IOException {
        try {
            MP4AACDemuxer demuxer = new MP4AACDemuxer(file);
            if (demuxer.getAudioSpecificConfig() == null) {
                throw new UnsupportedAudioFileException("MP4 AAC track not found");
            }
            Decoder decoder = Decoder.create(demuxer.getAudioSpecificConfig());
            SampleBuffer sampleBuffer = new SampleBuffer(decoder.getAudioFormat());
            sampleBuffer.setBigEndian(false);
            AudioFormat af = toAudioFormat(sampleBuffer);
            Map<String, Object> props = new HashMap<>();
            props.put("duration", demuxer.getDurationMicros());
            return new AudioFileFormat(AudioFileFormat.Type.WAVE, af, NOT_SPECIFIED, props);
        } catch (IOException e) {
            throw new UnsupportedAudioFileException(e.getMessage());
        }
    }

    private AudioInputStream decodeMP4Stream(InputStream in) throws UnsupportedAudioFileException, IOException {
        // 流模式不支持 MP4（需要随机 seek），走 Unsupported
        throw new UnsupportedAudioFileException("MP4 stream not supported; use File");
    }

    private AudioInputStream decodeMP4Stream(File file) throws UnsupportedAudioFileException, IOException {
        try {
            MP4AACDemuxer demuxer = new MP4AACDemuxer(file);
            if (demuxer.getAudioSpecificConfig() == null) {
                throw new UnsupportedAudioFileException("MP4 AAC track not found");
            }
            Decoder decoder = Decoder.create(demuxer.getAudioSpecificConfig());
            SampleBuffer sampleBuffer = new SampleBuffer(decoder.getAudioFormat());
            sampleBuffer.setBigEndian(false);
            AudioFormat af = toAudioFormat(sampleBuffer);
            return new AACAudioInputStream(demuxer, decoder, sampleBuffer, af);
        } catch (IOException e) {
            throw new UnsupportedAudioFileException(e.getMessage());
        }
    }

    // ========== ADTS 解码 ==========

    private AudioFileFormat decodeADTSFileFormat(InputStream in) throws UnsupportedAudioFileException, IOException {
        try {
            ADTSDemultiplexer adts = new ADTSDemultiplexer(in);
            Decoder decoder = Decoder.create(adts.getDecoderInfo());
            SampleBuffer sampleBuffer = new SampleBuffer(decoder.getAudioFormat());
            sampleBuffer.setBigEndian(false);
            AudioFormat af = toAudioFormat(sampleBuffer);
            // 估算时长：统计帧数
            int frames = 1;
            try {
                while (true) {
                    adts.skipNextFrame();
                    frames++;
                }
            } catch (EOFException ignored) {}
            double lengthSec = frames * (double) decoder.getConfig().getSampleLength() / (double) decoder.getConfig().getSampleFrequency().getFrequency();
            Map<String, Object> props = new HashMap<>();
            props.put("duration", (long) (lengthSec * 1_000_000L));
            return new AudioFileFormat(AudioFileFormat.Type.WAVE, af, NOT_SPECIFIED, props);
        } catch (IOException e) {
            throw new UnsupportedAudioFileException(e.getMessage());
        }
    }

    private AudioInputStream decodeADTSStream(InputStream in) throws UnsupportedAudioFileException, IOException {
        try {
            ADTSDemultiplexer adts = new ADTSDemultiplexer(in);
            Decoder decoder = Decoder.create(adts.getDecoderInfo());
            SampleBuffer sampleBuffer = new SampleBuffer(decoder.getAudioFormat());
            sampleBuffer.setBigEndian(false);
            AudioFormat af = toAudioFormat(sampleBuffer);
            return new AACAudioInputStream(adts, decoder, sampleBuffer, af);
        } catch (IOException e) {
            throw new UnsupportedAudioFileException(e.getMessage());
        }
    }

    // ========== 工具 ==========

    private static AudioFormat toAudioFormat(SampleBuffer sb) {
        int frameSize = sb.getChannels() * (sb.getBitsPerSample() / 8);
        return new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, sb.getSampleRate(),
                sb.getBitsPerSample(), sb.getChannels(), frameSize, sb.getSampleRate(), sb.isBigEndian());
    }

    private static int readNBytes(InputStream in, byte[] b, int off, int len) throws IOException {
        int total = 0;
        while (total < len) {
            int r = in.read(b, off + total, len - total);
            if (r < 0) break;
            total += r;
        }
        return total;
    }

    // ========== AACAudioInputStream ==========

    /**
     * 包装 MP4AACDemuxer 或 ADTSDemultiplexer，逐帧解码为 PCM。
     */
    public static class AACAudioInputStream extends AudioInputStream {
        private final Object demuxer; // MP4AACDemuxer or ADTSDemultiplexer
        private final Decoder decoder;
        private final SampleBuffer sampleBuffer;
        private byte[] pcmBuffer = new byte[0];
        private int pcmPos = 0;
        private boolean eof = false;

        public AACAudioInputStream(MP4AACDemuxer demuxer, Decoder decoder, SampleBuffer sampleBuffer, AudioFormat format) {
            super(new ByteArrayInputStream(new byte[0]), format, NOT_SPECIFIED);
            this.demuxer = demuxer;
            this.decoder = decoder;
            this.sampleBuffer = sampleBuffer;
        }

        public AACAudioInputStream(ADTSDemultiplexer demuxer, Decoder decoder, SampleBuffer sampleBuffer, AudioFormat format) {
            super(new ByteArrayInputStream(new byte[0]), format, NOT_SPECIFIED);
            this.demuxer = demuxer;
            this.decoder = decoder;
            this.sampleBuffer = sampleBuffer;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (eof) return -1;
            int total = 0;
            while (total < len) {
                if (pcmPos >= pcmBuffer.length) {
                    if (!decodeNextFrame()) {
                        eof = true;
                        return total > 0 ? total : -1;
                    }
                }
                int toCopy = Math.min(len - total, pcmBuffer.length - pcmPos);
                System.arraycopy(pcmBuffer, pcmPos, b, off + total, toCopy);
                pcmPos += toCopy;
                total += toCopy;
            }
            return total;
        }

        @Override
        public int read() throws IOException {
            byte[] b = new byte[1];
            int r = read(b, 0, 1);
            return r < 0 ? -1 : (b[0] & 0xFF);
        }

        private boolean decodeNextFrame() throws IOException {
            byte[] frame;
            if (demuxer instanceof MP4AACDemuxer) {
                frame = ((MP4AACDemuxer) demuxer).readNextFrame();
            } else if (demuxer instanceof ADTSDemultiplexer) {
                try {
                    frame = ((ADTSDemultiplexer) demuxer).readNextFrame();
                } catch (EOFException e) {
                    frame = null;
                }
            } else {
                frame = null;
            }
            if (frame == null || frame.length == 0) return false;
            decoder.decodeFrame(frame, sampleBuffer);
            pcmBuffer = sampleBuffer.getData();
            pcmPos = 0;
            return true;
        }

        @Override
        public void close() throws IOException {
            eof = true;
        }
    }
}