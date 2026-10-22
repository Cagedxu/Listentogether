package com.musicmod.client.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LRC 歌词解析器。
 * 支持 [mm:ss.xx] 时间标签，解析为带时间戳的行列表；
 * 无时间标签的纯文本歌词解析为无时间行（全部显示，不高亮）。
 * 支持双语歌词两种格式：
 * 1. 单行格式：原文 + 窄空格(U+2009) + 翻译，在同一行内
 * 2. 双行格式：相同时间戳的连续两行自动识别为原文+翻译（兼容旧格式）
 */
public class LyricsParser {

    public static class Line {
        public final long timeMs; // -1 = 无时间标签
        public final String text;
        public String translation; // 翻译行，可为 null

        Line(long timeMs, String text) {
            this.timeMs = timeMs;
            this.text = text;
        }

        Line(long timeMs, String text, String translation) {
            this.timeMs = timeMs;
            this.text = text;
            this.translation = translation;
        }
    }

    private static final Pattern TIME_TAG = Pattern.compile(
            "\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]");

    /** 窄空格 U+2009，用于分隔原文与翻译 */
    private static final char TRANSLATION_SEPARATOR = '\u2009';

    /** 解析歌词文本 */
    public static List<Line> parse(String lyrics) {
        List<Line> lines = new ArrayList<>();
        if (lyrics == null || lyrics.isEmpty()) return lines;
        String[] rawLines = lyrics.split("\r?\n");
        for (String raw : rawLines) {
            if (raw.trim().isEmpty()) continue;
            // 跳过 LRC 元数据标签（[ar:][ti:][al:][by:] 等）
            if (raw.length() > 1 && raw.charAt(0) == '['
                    && raw.indexOf(':') > 1 && raw.indexOf(']') > 0
                    && !isTimeTag(raw)) {
                continue;
            }
            // 提取所有时间标签（一行可能有多个时间标签对应同一句词）
            Matcher m = TIME_TAG.matcher(raw);
            List<Long> times = new ArrayList<>();
            int lastEnd = 0;
            while (m.find()) {
                long min = Long.parseLong(m.group(1));
                long sec = Long.parseLong(m.group(2));
                String fracStr = m.group(3);
                long frac = 0;
                if (fracStr != null) {
                    // .xx → 百分秒；.xxx → 毫秒
                    if (fracStr.length() == 2) frac = Long.parseLong(fracStr) * 10;
                    else if (fracStr.length() == 3) frac = Long.parseLong(fracStr);
                    else frac = Long.parseLong(fracStr);
                }
                times.add(min * 60000 + sec * 1000 + frac);
                lastEnd = m.end();
            }
            String text = raw.substring(lastEnd).trim();
            // 检查窄空格分隔的翻译（原文\u2009翻译）
            String translation = null;
            int sepIdx = text.indexOf(TRANSLATION_SEPARATOR);
            if (sepIdx >= 0) {
                String main = text.substring(0, sepIdx).trim();
                String trans = text.substring(sepIdx + 1).trim();
                if (!main.isEmpty() && !trans.isEmpty()) {
                    text = main;
                    translation = trans;
                }
            }
            if (times.isEmpty()) {
                // 无时间标签：作为纯文本行
                lines.add(new Line(-1, text, translation));
            } else {
                for (long t : times) {
                    lines.add(new Line(t, text, translation));
                }
            }
        }
        // 按时间排序（有时间标签的）
        lines.sort((a, b) -> Long.compare(a.timeMs, b.timeMs));
        mergeTranslations(lines);
        return lines;
    }

    /**
     * 合并翻译行（双行格式兼容）：相同时间戳的连续行，第一行为原文，后续不同文本的行识别为翻译。
     * 仅处理尚未通过窄空格识别到翻译的行。
     * 典型双语 LRC 格式：
     * [00:01.00]Original line
     * [00:01.00]翻译行
     */
    private static void mergeTranslations(List<Line> lines) {
        for (int i = 1; i < lines.size(); ) {
            Line prev = lines.get(i - 1);
            Line cur = lines.get(i);
            // 同时间戳、都有时间标签、文本不同、原文尚无翻译 → 合并
            if (prev.timeMs >= 0 && cur.timeMs == prev.timeMs
                    && !cur.text.isEmpty() && !cur.text.equals(prev.text)
                    && prev.translation == null) {
                prev.translation = cur.text;
                lines.remove(i);
            } else {
                i++;
            }
        }
    }

    /** 歌词中是否包含翻译行 */
    public static boolean hasTranslation(List<Line> lines) {
        for (Line l : lines) {
            if (l.translation != null && !l.translation.isEmpty()) return true;
        }
        return false;
    }

    private static boolean isTimeTag(String line) {
        return TIME_TAG.matcher(line).find();
    }

    /**
     * 根据当前播放进度找到应高亮的行索引。
     * @return 高亮行索引，-1 表示无匹配（纯文本歌词或未开始）
     */
    public static int findActiveLine(List<Line> lines, long positionMs) {
        if (lines.isEmpty()) return -1;
        // 全部无时间标签 → 不高亮
        if (lines.get(0).timeMs < 0) return -1;
        int active = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).timeMs <= positionMs) active = i;
            else break;
        }
        return active;
    }

    /** 歌词是否带时间标签（可用于判断是否滚动跟随） */
    public static boolean isTimed(List<Line> lines) {
        return !lines.isEmpty() && lines.get(0).timeMs >= 0;
    }
}