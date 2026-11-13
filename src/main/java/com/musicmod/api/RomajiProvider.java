package com.musicmod.api;

/** 由拓展模组实现的日语罗马音提供器。 */
public interface RomajiProvider {
    /** 该文本是否包含需要处理的日文（假名或汉字）。 */
    boolean canHandle(String text);

    /** 返回罗马音；无法转换时返回 null 或原文。 */
    String romanize(String text);
}
