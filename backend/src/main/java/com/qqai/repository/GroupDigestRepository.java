package com.qqai.repository;

import com.qqai.entity.GroupDigest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 群日报仓库（AI 摘要阶段 3）。
 *
 * <p>表中 {@code UNIQUE(group_id, digest_date)} 保证同群同天只有一条，
 * 因此「按群 + 日期」查询返回 {@link Optional} 而不是 List。</p>
 */
@Repository
public interface GroupDigestRepository extends JpaRepository<GroupDigest, Long> {

    /** 查询某群某天的日报（幂等判断用：已存在则直接返回，不再调用大模型） */
    Optional<GroupDigest> findByGroupIdAndDigestDate(String groupId, LocalDate digestDate);

    /** 查询某群最新一期日报 */
    Optional<GroupDigest> findFirstByGroupIdOrderByDigestDateDesc(String groupId);

    /** 查询某群历史日报（按日期倒序，配合 {@link Pageable} 限制条数） */
    List<GroupDigest> findByGroupIdOrderByDigestDateDesc(String groupId, Pageable pageable);

    /** 查询某天所有已生成的日报（定时任务用来一次性得到「当天已有日报的群」，避免 N+1 查询） */
    List<GroupDigest> findByDigestDate(LocalDate digestDate);
}
