package com.qqai.common;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * 分页参数归一化：客户端传入的 size 必须被夹在 [1, MAX_SIZE] 之内。
 *
 * 为什么需要：多个列表接口直接把 {@code @RequestParam size} 交给 {@code PageRequest.of(page, size)}，
 * 一个 {@code size=100000} 的请求就会全表回表并一次性序列化，足以拖垮数据库与内存。
 * 这里集中一处上限，避免每个接口各写一遍 {@code Math.min} 而漏掉某处。
 */
public final class PageLimits {

    /** 单页最大条数：列表页 20/50/100 足够，导出类需求走专门的导出接口 */
    public static final int MAX_SIZE = 200;

    private PageLimits() {
    }

    /** 归一化 size：下界 1、上界 {@link #MAX_SIZE} */
    public static int clampSize(int size) {
        if (size < 1) {
            return 1;
        }
        return Math.min(size, MAX_SIZE);
    }

    /** 归一化 page：负数按 0 处理 */
    public static int clampPage(int page) {
        return Math.max(0, page);
    }

    /** 等价于 {@code PageRequest.of(clampPage(page), clampSize(size))} */
    public static Pageable of(int page, int size) {
        return PageRequest.of(clampPage(page), clampSize(size));
    }

    /** 带排序的分页，size 同样受上限保护 */
    public static Pageable of(int page, int size, Sort sort) {
        return PageRequest.of(clampPage(page), clampSize(size), sort);
    }
}
