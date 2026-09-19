package com.qqai.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 管理端「用户积分」列表查询。
 *
 * <p>这一页需要按 <b>积分余额 / 累计消耗</b> 排序，而这些字段在 user_credit 表里，用户表只存账号信息；
 * 用 JPA 派生方法要面对 keyword × tier × 余额区间 × 排序的组合爆炸，所以这里用
 * {@link EntityManager} 拼一条带 LEFT JOIN 的 SQL：排序字段与方向都走白名单，
 * 其余条件全部用命名参数绑定，避免注入。</p>
 */
@Service
public class AdminUserCreditQueryService {

    /** 排序列白名单（key 为前端传值，value 为真实列表达式） */
    private static final Map<String, String> SORT_COLUMNS = Map.of(
            "balance", "COALESCE(c.balance, 0)",
            "totalSpent", "COALESCE(c.total_spent, 0)",
            "totalEarned", "COALESCE(c.total_earned, 0)",
            "updatedAt", "COALESCE(c.updated_at, u.created_at)",
            "createdAt", "COALESCE(u.created_at, '1970-01-01')",
            "userId", "u.id"
    );

    private static final Set<String> SUBSCRIPTION_TIERS = Set.of(
            "FREE", "LITE", "PRO", "PROPLUS", "ULTRA", "MEGA", "SMALL_MONTH_CARD", "LARGE_MONTH_CARD", "ALL");

    @PersistenceContext
    private EntityManager entityManager;

    /** 查询结果：按排序后的用户 ID 顺序 + 总条数 */
    public record UserCreditPage(List<Long> userIds, long totalElements) {
    }

    /**
     * @param keyword 账号/昵称关键字（可为空）
     * @param tier    订阅层级（可为空；非法值忽略）
     * @param min     余额下限（可为空）
     * @param max     余额上限（可为空）
     * @param sort    balance / totalSpent / totalEarned / updatedAt / createdAt / userId
     * @param order   asc / desc
     */
    public UserCreditPage query(String keyword, String tier, Integer min, Integer max,
                               String sort, String order, int page, int size) {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1 ");
        Map<String, Object> params = new HashMap<>();

        if (keyword != null && !keyword.isBlank()) {
            where.append(" AND (LOWER(u.username) LIKE :kw OR LOWER(COALESCE(u.nickname, '')) LIKE :kw) ");
            params.put("kw", "%" + keyword.trim().toLowerCase() + "%");
        }
        String tierFilter = tier == null || tier.isBlank() ? null : tier.trim().toUpperCase();
        if (tierFilter != null && SUBSCRIPTION_TIERS.contains(tierFilter)) {
            where.append(" AND COALESCE(c.subscription_tier, 'FREE') = :tier ");
            params.put("tier", tierFilter);
        }
        if (min != null) {
            where.append(" AND COALESCE(c.balance, 0) >= :minBalance ");
            params.put("minBalance", min);
        }
        if (max != null) {
            where.append(" AND COALESCE(c.balance, 0) <= :maxBalance ");
            params.put("maxBalance", max);
        }

        String sortColumn = SORT_COLUMNS.getOrDefault(sort == null ? "" : sort, "COALESCE(c.balance, 0)");
        String direction = "asc".equalsIgnoreCase(order == null ? "" : order) ? "ASC" : "DESC";

        String from = " FROM users u LEFT JOIN user_credit c ON c.user_id = u.id ";

        Query countQuery = entityManager.createNativeQuery("SELECT COUNT(*) " + from + where);
        params.forEach(countQuery::setParameter);
        long total = ((Number) countQuery.getSingleResult()).longValue();

        Query listQuery = entityManager.createNativeQuery(
                "SELECT u.id " + from + where + " ORDER BY " + sortColumn + " " + direction + ", u.id ASC "
                        + "LIMIT :limit OFFSET :offset");
        params.forEach(listQuery::setParameter);
        listQuery.setParameter("limit", size);
        listQuery.setParameter("offset", Math.max(0, page) * size);

        @SuppressWarnings("unchecked")
        List<Number> rows = listQuery.getResultList();
        List<Long> ids = rows.stream().map(Number::longValue).toList();
        return new UserCreditPage(ids, total);
    }
}
