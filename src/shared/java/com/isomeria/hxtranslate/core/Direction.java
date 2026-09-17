package com.isomeria.hxtranslate.core;

/**
 * 翻译方向：收到的消息翻成中文，发出去的消息翻成英文。
 */
public enum Direction {
    INCOMING,
    OUTGOING;

    public boolean toChinese() {
        return this == INCOMING;
    }

    public String label() {
        return this == INCOMING ? "EN→ZH" : "ZH→EN";
    }
}
