package com.qqai.config;

import com.qqai.service.FilePurgeService;
import com.qqai.service.MediaRetentionSettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 媒体保留策略定时清理：把「修改时间早于 现在 - N 天」的图片/视频/语音删掉。
 *
 * <p><b>默认不生效</b>：{@code file.retention.enabled} 默认 false，必须由管理员在
 * 「后台 → 数据维护 → 媒体管理」显式打开；开关是运行时状态（{@link MediaRetentionSettingsService}），
 * 改完立即生效、无需重启。</p>
 *
 * <p><b>cron 为何只走配置</b>：cron 只在应用启动时注册一次，做成运行时可改需要
 * {@code SchedulingConfigurer} 重注册（{@code GroupDigestScheduler} 就是这么做的）。
 * 本功能刻意只暴露「开关 + 天数」两个运行时项，cron 留在 yml，避免管理端多一个
 * 能改错调度时刻的输入框。</p>
 *
 * <p><b>为什么是 4:00</b>：项目已有三个定时任务——消息归档 2:00（{@code archive.cron}）、
 * 订阅过期 2:00、归档清理 3:00。错开到 4:00，避免同一时刻多个任务同时扫磁盘。</p>
 */
@Component
public class MediaRetentionScheduler {

    private static final Logger log = LoggerFactory.getLogger(MediaRetentionScheduler.class);

    @Autowired
    private MediaRetentionSettingsService retentionSettings;

    @Autowired
    private FilePurgeService filePurgeService;

    /** 每天 4:00 执行；开关关闭时连目录扫描都不做 */
    @Scheduled(cron = "${file.retention.cron:0 0 4 * * ?}")
    public void purgeExpiredMedia() {
        if (!retentionSettings.isEnabled()) {
            log.debug("媒体保留策略未启用，跳过本次自动清理");
            return;
        }

        int days = retentionSettings.getDays();
        log.info("媒体保留策略开始执行: 清理 {} 天前的媒体文件, types={}", days, retentionSettings.typeList());
        try {
            FilePurgeService.PurgeResult result = filePurgeService.purgeMediaOlderThan(
                    days, retentionSettings.typeList(), "system:media-retention");
            log.info("媒体保留策略执行完成: 删除 {} 个文件, 释放 {}, note={}",
                    result.getTotalDeleted(), result.getFreedMB(),
                    result.getNote() == null ? "-" : result.getNote());
        } catch (Exception e) {
            // 定时任务里绝不能让异常逃出去：逃出去会被调度器记一次失败，但也不该影响后续调度
            log.error("媒体保留策略执行失败（本次不删除任何文件，下次调度继续重试）", e);
        }
    }
}
