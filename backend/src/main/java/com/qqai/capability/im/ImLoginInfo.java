package com.qqai.capability.im;

/** 当前登录的 QQ 信息（qq=0 表示未登录）。 */
public record ImLoginInfo(long qq, String nickname) {
}
