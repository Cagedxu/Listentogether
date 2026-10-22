package com.musicmod.audio;

import java.io.IOException;
import java.io.InputStream;

/**
 * 边下边播的阻塞式内存缓冲流。
 * <p>
 * 网络接收线程通过 {@link #writeChunk(byte[])} 写入数据块，
 * 音频解码线程通过 read 读取；缓冲为空时 read 阻塞等待，
 * 直到有新数据或写入完成（{@link #setWriteComplete()}）。
 * <p>
 * 全部数据保留在内存中，{@link #mark(int)} / {@link #reset()}
 * 永远有效（满足 Java Sound SPI 解析音频头部的需求）。
 */
public class SongBufferInputStream extends InputStream {

    private byte[] data;
    private int writePos = 0;
    private int readPos = 0;
    private boolean writeComplete = false;
    private int markPos = 0;
    private IOException writeError = null;

    public SongBufferInputStream() {
        this(64 * 1024);
    }

    public SongBufferInputStream(int initialCapacity) {
        data = new byte[Math.max(8192, initialCapacity)];
    }

    /**
     * 写入一个数据块（网络线程调用）
     */
    public synchronized void writeChunk(byte[] chunk) {
        if (writeComplete) return; // 流已关闭（如切歌），静默丢弃
        ensureCapacity(writePos + chunk.length);
        System.arraycopy(chunk, 0, data, writePos, chunk.length);
        writePos += chunk.length;
        notifyAll();
    }

    /**
     * 标记写入完成，read 将在消费完缓冲后返回 -1
     */
    public synchronized void setWriteComplete() {
        writeComplete = true;
        notifyAll();
    }

    /**
     * 写入出错（如传输中断），唤醒并通知读端
     */
    public synchronized void setWriteError(IOException e) {
        writeError = e;
        writeComplete = true;
        notifyAll();
    }

    /**
     * 当前缓冲区中可读字节数
     */
    public synchronized int getBufferedBytes() {
        return writePos - readPos;
    }

    private void ensureCapacity(int minCapacity) {
        if (minCapacity <= data.length) return;
        int newCap = data.length;
        while (newCap < minCapacity) newCap <<= 1;
        byte[] newData = new byte[newCap];
        System.arraycopy(data, 0, newData, 0, writePos);
        data = newData;
    }

    private void awaitData() throws IOException {
        while (readPos >= writePos) {
            if (writeError != null) throw writeError;
            if (writeComplete) return;
            try {
                wait();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting for song data", e);
            }
        }
    }

    @Override
    public synchronized int read() throws IOException {
        awaitData();
        if (readPos >= writePos) return -1;
        return data[readPos++] & 0xFF;
    }

    @Override
    public synchronized int read(byte[] b, int off, int len) throws IOException {
        if (len == 0) return 0;
        awaitData();
        if (readPos >= writePos) return -1;
        int toRead = Math.min(len, writePos - readPos);
        System.arraycopy(data, readPos, b, off, toRead);
        readPos += toRead;
        return toRead;
    }

    @Override
    public synchronized int available() throws IOException {
        return writePos - readPos;
    }

    @Override
    public boolean markSupported() {
        return true;
    }

    @Override
    public synchronized void mark(int readlimit) {
        markPos = readPos;
    }

    @Override
    public synchronized void reset() throws IOException {
        readPos = markPos;
    }

    @Override
    public synchronized void close() {
        writeComplete = true;
        notifyAll();
    }
}