package com.musicmod.audio;

import com.musicmod.MusicMod;
import javax.sound.sampled.*;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;

/**
 * 客户端音频播放引擎。
 * 利用 Java Sound SPI（mp3spi/vorbisspi）支持 MP3、OGG、WAV、AIFF、AU 等格式。
 * 所有音频统一解码为 PCM 16bit 44.1kHz 立体声后通过 SourceDataLine 播放。
 */
public class AudioEngine {
    public static final AudioFormat TARGET_FORMAT = new AudioFormat(
            AudioFormat.Encoding.PCM_SIGNED,
            44100.0f, 16, 2, 4, 44100.0f, false);

    /** 动态目标格式：基于源采样率，保持 16bit 立体声小端 */
    private AudioFormat createTargetFormat(float sampleRate) {
        int frameSize = 2 * (16 / 8); // 2ch * 2bytes = 4
        return new AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                sampleRate, 16, 2, frameSize, sampleRate, false);
    }

    private SourceDataLine line;
    private AudioInputStream decodedStream;
    private AudioInputStream rawStream;
    private Thread playThread;

    private volatile boolean playing = false;
    private volatile boolean paused = false;
    private volatile boolean stopRequested = false;

    private volatile float volume = 1.0f; // 0.0 ~ 1.0
    private long bytesPerMs = (long) (TARGET_FORMAT.getFrameRate() * TARGET_FORMAT.getFrameSize() / 1000.0);
    private long startOffsetBytes = 0;
    private long totalBytesWritten = 0;
    private String currentSongName = "";
    private long songDurationMs = -1;
    // SPI 转换后的流 frameLength 常为 -1（MP3/OGG/FLAC），用标签时长作为回退，
    // 否则进度条无法渲染、拖动 seek 无法使用（WAV 原始 PCM 不受影响）
    private long fallbackDurationMs = -1;

    private PlaybackListener listener;

    public interface PlaybackListener {
        void onPlaybackFinished(String songName);
        void onPlaybackError(String songName, String error);
    }

    public void setListener(PlaybackListener listener) {
        this.listener = listener;
    }

    /**
     * 开始播放指定音频文件。
     * @param file 音频文件
     * @param songName 歌曲显示名
     * @param offsetMs 起始偏移（毫秒），用于多端同步
     * @param volume 音量 0.0~1.0
     */
    public synchronized void play(File file, String songName, long offsetMs, float volume) {
        stopInternal();
        ensureContextClassLoader();
        try {
            AudioInputStream ais = openAudioInputStream(file);
            beginPlayback(ais, songName, offsetMs, volume);
        } catch (UnsupportedAudioFileException e) {
            playing = false;
            MusicMod.LOGGER.error("Unsupported audio format: {} (file={})", songName, file.getName(), e);
            logAudioProviders();
            notifyError(songName, "不支持的音频格式: " + file.getName());
        } catch (Exception e) {
            playing = false;
            MusicMod.LOGGER.error("Failed to play audio: {} (file={})", songName, file.getName(), e);
            notifyError(songName, "播放失败: " + e.getMessage());
        }
    }

    /**
     * 根据文件扩展名选择正确的 AudioFileReader，绕过 AudioSystem SPI 自动分派。
     * 避免 MpegAudioFileReader 误判 m4a/mp4 为 MP3。
     */
    private AudioInputStream openAudioInputStream(File file) throws Exception {
        String name = file.getName().toLowerCase();
        // 按扩展名选择 reader，避免 Java Sound SPI 把 WAV/MP3 误判成其它格式。
        if (name.endsWith(".m4a") || name.endsWith(".mp4") || name.endsWith(".aac")) {
            return new AACFileReader().getAudioInputStream(file);
        }
        if (name.endsWith(".mp3")) {
            return new javazoom.spi.mpeg.sampled.file.MpegAudioFileReader().getAudioInputStream(file);
        }
        if (name.endsWith(".ogg")) {
            return new javazoom.spi.vorbis.sampled.file.VorbisAudioFileReader().getAudioInputStream(file);
        }
        if (name.endsWith(".flac")) {
            return new org.jflac.sound.spi.FlacAudioFileReader().getAudioInputStream(file);
        }
        if (name.endsWith(".wav")) {
            try {
                return WavFileReader.open(file);
            } catch (Exception e) {
                MusicMod.LOGGER.debug("WavFileReader failed for {}, falling back to AudioSystem: {}",
                        file.getName(), e.getMessage());
            }
        }
        return AudioSystem.getAudioInputStream(file);
    }

    /**
     * 从输入流播放（支持边下边播：流数据不足时读取会阻塞等待）。
     * @param input 音频数据流（如 {@link SongBufferInputStream}）
     * @param songName 歌曲显示名
     * @param offsetMs 起始偏移（毫秒），用于多端同步
     * @param volume 音量 0.0~1.0
     */
    public synchronized void play(InputStream input, String songName, long offsetMs, float volume) {
        play(input, songName, "", offsetMs, volume);
    }

    /**
     * 从网络流播放；按文件名扩展名选择 reader，避免 SPI 误判 WAV。
     */
    public synchronized void play(InputStream input, String songName, String fileName,
                                  long offsetMs, float volume) {
        stopInternal();
        ensureContextClassLoader();
        try {
            BufferedInputStream buffered = new BufferedInputStream(input, 1 << 20);
            beginPlayback(openStreamAudioInputStream(buffered, fileName), songName, offsetMs, volume);
        } catch (UnsupportedAudioFileException e) {
            playing = false;
            MusicMod.LOGGER.error("Unsupported audio format: {}", songName, e);
            logAudioProviders();
            notifyError(songName, "不支持的音频格式: " + songName);
        } catch (Exception e) {
            playing = false;
            MusicMod.LOGGER.error("Failed to play audio: {}", songName, e);
            notifyError(songName, "播放失败: " + e.getMessage());
        }
    }

    private AudioInputStream openStreamAudioInputStream(InputStream input, String fileName) throws Exception {
        String name = fileName == null ? "" : fileName.toLowerCase();
        if (name.endsWith(".wav")) {
            try {
                return WavFileReader.open(input);
            } catch (Exception e) {
                MusicMod.LOGGER.debug("Stream WavFileReader failed, falling back to AudioSystem: {}", e.getMessage());
                return AudioSystem.getAudioInputStream(input);
            }
        }
        if (name.endsWith(".mp3")) {
            return new javazoom.spi.mpeg.sampled.file.MpegAudioFileReader().getAudioInputStream(input);
        }
        if (name.endsWith(".ogg")) {
            return new javazoom.spi.vorbis.sampled.file.VorbisAudioFileReader().getAudioInputStream(input);
        }
        if (name.endsWith(".flac")) {
            return new org.jflac.sound.spi.FlacAudioFileReader().getAudioInputStream(input);
        }
        return AudioSystem.getAudioInputStream(input);
    }

    /**
     * 确保 Java Sound SPI ServiceLoader 使用模组类加载器。
     * Forge 环境中播放线程的上下文类加载器可能无法看到模组 jar 内的
     * SPI 服务文件（META-INF/services），导致 FLAC/M4A 解码器不被发现。
     */
    private void ensureContextClassLoader() {
        Thread.currentThread().setContextClassLoader(AudioEngine.class.getClassLoader());
    }

    /** 诊断：日志输出当前发现的 Java Sound SPI 提供者 */
    private void logAudioProviders() {
        try {
            StringBuilder sb = new StringBuilder("Java Sound SPI providers: AudioFileReaders=");
            for (javax.sound.sampled.spi.AudioFileReader r :
                    javax.sound.sampled.spi.AudioFileReader.class.getClassLoader() == null
                            ? java.util.Collections.<javax.sound.sampled.spi.AudioFileReader>emptyList()
                            : java.util.ServiceLoader.load(javax.sound.sampled.spi.AudioFileReader.class,
                                    AudioEngine.class.getClassLoader())) {
                sb.append(r.getClass().getName()).append(",");
            }
            sb.append(" FormatConversionProviders=");
            for (javax.sound.sampled.spi.FormatConversionProvider p :
                    java.util.ServiceLoader.load(javax.sound.sampled.spi.FormatConversionProvider.class,
                            AudioEngine.class.getClassLoader())) {
                sb.append(p.getClass().getName()).append(",");
            }
            MusicMod.LOGGER.warn(sb.toString());
        } catch (Exception ignored) {}
    }

    private void beginPlayback(AudioInputStream rawIn, String songName, long offsetMs, float volume) throws Exception {
        stopRequested = false;
        paused = false;
        this.volume = Math.max(0.0f, Math.min(1.0f, volume));
        this.currentSongName = songName;
        this.totalBytesWritten = 0;

        rawStream = rawIn;
        AudioFormat baseFormat = rawStream.getFormat();
        MusicMod.LOGGER.info("Audio format for {}: encoding={} rate={} bits={} ch={} frameSize={} bigEndian={}",
                songName, baseFormat.getEncoding(), (int) baseFormat.getSampleRate(),
                baseFormat.getSampleSizeInBits(), baseFormat.getChannels(),
                baseFormat.getFrameSize(), baseFormat.isBigEndian());

        // 转换策略（按优先级依次尝试）：
        // 1. 转换为动态目标格式（源采样率 + 16bit 立体声小端）——MP3/OGG/FLAC 的 SPI 转换器负责重采样+端序转换
        // 2. 转换为 PCM_SIGNED 原生格式——24bit/单声道 FLAC 等，jflac 转换器要求目标声道/位深与源一致，
        //    直接请求 PCM_SIGNED 编码可获取源原生格式的 PCM 流；非 16bit 时降位深到 16bit
        // 3. 源已是 PCM 16bit 直接播放——M4A/AAC：jaad 返回 PCM 但无 PCM-to-PCM 转换器
        AudioFormat playFormat = null;
        AudioInputStream stream = null;
        if (isCompatible(baseFormat)) {
            // 源格式与目标完全一致，直接使用
            stream = rawStream;
            playFormat = TARGET_FORMAT;
        } else {
            // 策略1：转换为动态目标格式（源采样率，16bit 立体声小端）
            AudioFormat targetFmt = createTargetFormat(baseFormat.getSampleRate());
            try {
                stream = AudioSystem.getAudioInputStream(targetFmt, rawStream);
                playFormat = targetFmt;
            } catch (IllegalArgumentException e1) {
                MusicMod.LOGGER.debug("Strategy 1 (direct target) failed for {}, trying native PCM: {}",
                        songName, e1.getMessage());
                // 策略2：转换为 PCM_SIGNED 原生格式（转换失败不消耗流数据，可安全重试）
                try {
                    AudioInputStream nativePcm = AudioSystem.getAudioInputStream(
                            AudioFormat.Encoding.PCM_SIGNED, rawStream);
                    AudioFormat nf = nativePcm.getFormat();
                    // jflac FlacFormatConversionProvider 返回的格式 frameSize=1（已知 bug），
                    // 实际 PCM 数据每帧 = channels * (sampleSizeInBits / 8) 字节。
                    // 不修正则 SourceDataLine 无法打开（16bit 立体声 frameSize 应为 4 而非 1）
                    int correctFrameSize = nf.getChannels() * (nf.getSampleSizeInBits() / 8);
                    if (correctFrameSize > 0 && nf.getFrameSize() != correctFrameSize) {
                        MusicMod.LOGGER.info("Corrected frameSize {} -> {} for {}",
                                nf.getFrameSize(), correctFrameSize, songName);
                        nf = new AudioFormat(nf.getEncoding(), nf.getSampleRate(),
                                nf.getSampleSizeInBits(), nf.getChannels(), correctFrameSize,
                                nf.getFrameRate(), nf.isBigEndian());
                        nativePcm = new AudioInputStream(nativePcm, nf, nativePcm.getFrameLength());
                    }
                    if (nf.getEncoding() == AudioFormat.Encoding.PCM_SIGNED
                            && nf.getSampleSizeInBits() > 0
                            && nf.getChannels() > 0
                            && nf.getFrameSize() > 0) {
                        if (nf.isBigEndian() && nf.getSampleSizeInBits() == 16) {
                            // 大端 16bit PCM：端序转换后播放
                            playFormat = new AudioFormat(nf.getEncoding(), nf.getSampleRate(),
                                    16, nf.getChannels(), nf.getFrameSize(),
                                    nf.getFrameRate(), false);
                            stream = new AudioInputStream(new EndianSwapInputStream(nativePcm),
                                    playFormat, nativePcm.getFrameLength());
                        } else if (nf.getSampleSizeInBits() != 16) {
                            // 非 16bit（如 24bit）：降位深到 16bit
                            playFormat = new AudioFormat(nf.getEncoding(), nf.getSampleRate(),
                                    16, nf.getChannels(), nf.getChannels() * 2,
                                    nf.getFrameRate(), false);
                            stream = new AudioInputStream(
                                    new BitDepthConverterInputStream(nativePcm, nf.getSampleSizeInBits(), 16, nf.isBigEndian()),
                                    playFormat, nativePcm.getFrameLength());
                        } else {
                            // 小端 16bit：直接播放
                            stream = nativePcm;
                            playFormat = nf;
                        }
                    }
                } catch (IllegalArgumentException ignored) {}
            }
            // 策略3：源已是 PCM 16bit 直接播放（M4A/AAC 回退路径）
            if (stream == null) {
                if (baseFormat.getEncoding() == AudioFormat.Encoding.PCM_SIGNED
                        && baseFormat.getSampleSizeInBits() == 16
                        && baseFormat.getChannels() > 0
                        && baseFormat.getFrameSize() > 0) {
                    if (baseFormat.isBigEndian()) {
                        // 大端 16bit PCM：需端序转换才能在 LE 的 SourceDataLine 上正常播放
                        playFormat = new AudioFormat(baseFormat.getEncoding(),
                                baseFormat.getSampleRate(), 16, baseFormat.getChannels(),
                                baseFormat.getFrameSize(), baseFormat.getFrameRate(), false);
                        stream = new AudioInputStream(
                                new EndianSwapInputStream(rawStream), playFormat, rawStream.getFrameLength());
                    } else {
                        stream = rawStream;
                        playFormat = baseFormat;
                    }
                } else {
                    throw new UnsupportedAudioFileException(
                            "不支持的音频格式: " + baseFormat);
                }
            }
        }
        decodedStream = stream;
        MusicMod.LOGGER.info("Playing {} with format: encoding={} rate={} bits={} ch={}",
                songName, playFormat.getEncoding(), (int) playFormat.getSampleRate(),
                playFormat.getSampleSizeInBits(), playFormat.getChannels());

        // 按实际播放格式计算 bytesPerMs（进度条/seek 依赖）
        bytesPerMs = (long) (playFormat.getFrameRate() * playFormat.getFrameSize() / 1000.0);
        this.startOffsetBytes = offsetMs * bytesPerMs;

        // 计算歌曲总时长（来自流的长度可能未知，返回-1）
        long frameLength = decodedStream.getFrameLength();
        if (frameLength > 0) {
            songDurationMs = (long) (frameLength * 1000.0f / playFormat.getFrameRate());
        } else {
            songDurationMs = -1;
        }

        DataLine.Info info = new DataLine.Info(SourceDataLine.class, playFormat);
        if (!AudioSystem.isLineSupported(info)) {
            throw new LineUnavailableException("SourceDataLine not supported for format: " + playFormat);
        }
        line = (SourceDataLine) AudioSystem.getLine(info);
        line.open(playFormat, 8192 * 4);
        applyVolume();
        line.start();

        // 跳到指定偏移位置（同步用）
        if (startOffsetBytes > 0) {
            skipBytes(decodedStream, startOffsetBytes);
        }

        playing = true;
        playThread = new Thread(this::playbackLoop, "MusicMod-AudioPlayer");
        playThread.setDaemon(true);
        playThread.start();
    }

    private void playbackLoop() {
        byte[] buffer = new byte[8192];
        try {
            int bytesRead;
            while (!stopRequested && playing) {
                // 暂停处理
                if (paused) {
                    Thread.sleep(50);
                    continue;
                }
                bytesRead = decodedStream.read(buffer, 0, buffer.length);
                if (bytesRead == -1) {
                    break; // 播放完毕
                }
                line.write(buffer, 0, bytesRead);
                totalBytesWritten += bytesRead;
            }
            if (!stopRequested && playing) {
                // 自然播放结束
                line.drain();
                playing = false;
                if (listener != null) {
                    listener.onPlaybackFinished(currentSongName);
                }
                // 0.26.0：自然结束后释放音频线与流资源，并标记 stopRequested。
                // 旧代码不清理导致 line/流残留占用，后续 stopInternal 关闭时
                // 可能与已退出的线程状态冲突引发卡死。
                try { line.stop(); line.close(); } catch (Exception ignored) {}
                line = null;
                closeStreams();
                stopRequested = true;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            if (!stopRequested) {
                MusicMod.LOGGER.error("Error during playback", e);
                notifyError(currentSongName, "播放出错: " + e.getMessage());
            }
        }
    }

    public synchronized void stop() {
        stopInternal();
    }

    private void stopInternal() {
        stopRequested = true;
        paused = false;
        playing = false;
        // 0.26.0 关键修复：先关闭流和音频线，再等待线程退出。
        // 旧顺序（先 join 再 close）在播放线程卡于 line.write() 时，
        // join 超时后 line.close() 可能因线程仍在写入而永久阻塞，
        // 导致 synchronized 锁永不释放，后续 play()/stop() 全部卡死，
        // Render 线程调用 stop() 时整局游戏冻结。
        // 先关流 → 解除 decodedStream.read() 阻塞；
        // 先关 line → 解除 line.write() 阻塞，线程随即退出循环。
        closeStreams();
        if (line != null) {
            try {
                line.flush();
                line.stop();
            } catch (Exception ignored) {}
            try {
                line.close();
            } catch (Exception ignored) {}
            line = null;
        }
        if (playThread != null) {
            playThread.interrupt();
            try {
                playThread.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            playThread = null;
        }
        currentSongName = "";
        startOffsetBytes = 0;
        totalBytesWritten = 0;
        songDurationMs = -1;
    }

    public void setPaused(boolean p) {
        if (line != null && playing) {
            if (p && !paused) {
                paused = true;
                line.stop();
            } else if (!p && paused) {
                paused = false;
                line.start();
            }
        }
    }

    public void setVolume(float v) {
        this.volume = Math.max(0.0f, Math.min(1.0f, v));
        applyVolume();
    }

    private void applyVolume() {
        if (line != null) {
            try {
                if (line.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
                    FloatControl gain = (FloatControl) line.getControl(FloatControl.Type.MASTER_GAIN);
                    float min = gain.getMinimum();
                    float max = gain.getMaximum();
                    if (volume <= 0.001f) {
                        gain.setValue(min);
                    } else {
                        // 对数音量曲线，更符合人耳感知
                        float dB = (float) (Math.log10(volume) * 20.0);
                        gain.setValue(Math.max(min, Math.min(max, dB)));
                    }
                }
            } catch (Exception e) {
                MusicMod.LOGGER.warn("Failed to apply volume control", e);
            }
        }
    }

    /**
     * 获取当前播放位置（毫秒）
     */
    public long getCurrentPositionMs() {
        return (startOffsetBytes + totalBytesWritten) / bytesPerMs;
    }

    /** 设置回退时长（来自标签解析，播放前由 ClientMusicManager 设置） */
    public void setFallbackDurationMs(long ms) {
        this.fallbackDurationMs = ms;
    }

    public long getSongDurationMs() {
        return songDurationMs > 0 ? songDurationMs : fallbackDurationMs;
    }

    public boolean isPlaying() {
        return playing && !stopRequested;
    }

    public boolean isPaused() {
        return paused;
    }

    public String getCurrentSongName() {
        return currentSongName;
    }

    public float getVolume() {
        return volume;
    }

    private boolean isCompatible(AudioFormat format) {
        return format.getEncoding() == AudioFormat.Encoding.PCM_SIGNED
                && format.getSampleRate() == TARGET_FORMAT.getSampleRate()
                && format.getSampleSizeInBits() == TARGET_FORMAT.getSampleSizeInBits()
                && format.getChannels() == TARGET_FORMAT.getChannels()
                && !format.isBigEndian(); // TARGET_FORMAT 为小端，大端 PCM 必须走转换/端序交换
    }

    /**
     * 位深转换流：将 n-bit PCM 降采样到 16bit PCM（小端）。
     * 取每个样本的最高 16 位（MSB），丢弃最低位——这是有损但保真度最高的降位深方式。
     * 使用 carry-over 缓冲确保跨 read() 调用不丢失部分样本字节。
     */
    private static class BitDepthConverterInputStream extends InputStream {
        private final InputStream source;
        private final int srcBits;
        private final int srcBytes;
        private final boolean srcBigEndian;
        private final byte[] srcBuf = new byte[16384];
        private int srcPos = 0;
        private int srcEnd = 0;

        BitDepthConverterInputStream(InputStream source, int srcBits, int dstBits, boolean srcBigEndian) {
            if (dstBits != 16) throw new IllegalArgumentException("Only 16bit output supported");
            this.source = source;
            this.srcBits = srcBits;
            this.srcBytes = srcBits / 8;
            this.srcBigEndian = srcBigEndian;
        }

        @Override
        public int read() throws IOException {
            byte[] b = new byte[2];
            int n = read(b, 0, 2);
            return n < 2 ? -1 : ((b[1] & 0xFF) << 8 | (b[0] & 0xFF));
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int maxSamples = len / 2;
            if (maxSamples == 0) return 0;
            int produced = 0;
            while (produced < maxSamples) {
                int avail = srcEnd - srcPos;
                if (avail < srcBytes) {
                    // carry-over：将剩余字节移到缓冲区开头再读取新数据
                    if (avail > 0) System.arraycopy(srcBuf, srcPos, srcBuf, 0, avail);
                    srcPos = 0;
                    srcEnd = avail;
                    int space = srcBuf.length - srcEnd;
                    if (space <= 0) break;
                    int rd = source.read(srcBuf, srcEnd, space);
                    if (rd == -1) break;
                    srcEnd += rd;
                    continue;
                }
                int convertible = Math.min((srcEnd - srcPos) / srcBytes, maxSamples - produced);
                for (int i = 0; i < convertible; i++) {
                    int srcOff = srcPos + i * srcBytes;
                    int hi, lo;
                    if (srcBigEndian) {
                        // 大端 [HIGH, MID, LOW...]: 取最高两字节
                        hi = srcBuf[srcOff] & 0xFF;
                        lo = srcBuf[srcOff + 1] & 0xFF;
                    } else {
                        // 小端 [...LOW, MID, HIGH]: 最高两字节排在末尾
                        hi = srcBuf[srcOff + srcBytes - 1] & 0xFF;
                        lo = srcBuf[srcOff + srcBytes - 2] & 0xFF;
                    }
                    int dstOff = off + (produced + i) * 2;
                    b[dstOff] = (byte) lo;
                    b[dstOff + 1] = (byte) hi;
                }
                srcPos += convertible * srcBytes;
                produced += convertible;
            }
            return produced == 0 ? -1 : produced * 2;
        }

        @Override
        public void close() throws IOException {
            source.close();
        }
    }

    /**
     * 大端 16bit PCM → 小端 转换流。
     * 逐对交换字节，供 SourceDataLine（小端）正确播放大端 PCM 数据。
     * 使用 carry-over 缓冲确保跨 read() 调用不丢失奇数尾字节。
     */
    private static class EndianSwapInputStream extends InputStream {
        private final InputStream source;
        private final byte[] swapBuf = new byte[8192];
        private int bufPos = 0;
        private int bufEnd = 0;

        EndianSwapInputStream(InputStream source) {
            this.source = source;
        }

        @Override
        public int read() throws IOException {
            byte[] b = new byte[2];
            int n = read(b, 0, 2);
            if (n < 2) return -1;
            return (b[1] & 0xFF) | ((b[0] & 0xFF) << 8);
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int evenLen = (len / 2) * 2;
            if (evenLen == 0) return 0;
            int produced = 0;
            while (produced < evenLen) {
                int avail = bufEnd - bufPos;
                if (avail < 2) {
                    // carry-over：剩余字节移到开头再读取新数据
                    if (avail > 0) System.arraycopy(swapBuf, bufPos, swapBuf, 0, avail);
                    bufPos = 0;
                    bufEnd = avail;
                    int space = swapBuf.length - bufEnd;
                    if (space <= 0) break;
                    int rd = source.read(swapBuf, bufEnd, space);
                    if (rd == -1) break;
                    bufEnd += rd;
                    continue;
                }
                int convertible = Math.min((bufEnd - bufPos) / 2 * 2, evenLen - produced);
                for (int i = 0; i < convertible; i += 2) {
                    b[off + produced + i] = swapBuf[bufPos + i + 1];
                    b[off + produced + i + 1] = swapBuf[bufPos + i];
                }
                bufPos += convertible;
                produced += convertible;
            }
            return produced == 0 ? -1 : produced;
        }

        @Override
        public int available() throws IOException {
            return ((source.available() + (bufEnd - bufPos)) / 2) * 2;
        }

        @Override
        public void close() throws IOException {
            source.close();
        }
    }

    private void skipBytes(AudioInputStream stream, long bytes) throws IOException {
        long remaining = bytes;
        byte[] skipBuf = new byte[8192];
        while (remaining > 0) {
            int toRead = (int) Math.min(remaining, skipBuf.length);
            int read = stream.read(skipBuf, 0, toRead);
            if (read == -1) break;
            remaining -= read;
        }
    }

    private void closeStreams() {
        if (decodedStream != null) {
            try { decodedStream.close(); } catch (IOException ignored) {}
            decodedStream = null;
        }
        if (rawStream != null) {
            try { rawStream.close(); } catch (IOException ignored) {}
            rawStream = null;
        }
    }

    private void notifyError(String song, String error) {
        playing = false;
        if (listener != null) {
            listener.onPlaybackError(song, error);
        }
    }
}