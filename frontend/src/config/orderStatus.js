/**
 * 订单状态「单一事实来源」
 *
 * 后端枚举：com.qqai.entity.enums.OrderStatus
 *   PENDING / PAID / PENDING_REFUND / DISPUTED / REFUNDED / CANCELLED / EXPIRED
 *
 * 企业级一致性约束：用户端「我的订单」、管理后台「订单管理」的筛选下拉、表格徽章、
 * 详情抽屉、批量确认收款提示、以及文档，都必须引用本模块的文案，
 * 不允许各处自行写中文字符串（历史上出现过同一状态既叫「待支付」又叫「待确认」）。
 */

/** 状态 -> 规范中文文案（用户端与后台共用同一套叫法） */
export const ORDER_STATUS_LABELS = {
  PENDING: '待确认收款',
  PAID: '已支付',
  PENDING_REFUND: '退款审批中',
  DISPUTED: '纠纷中',
  REFUNDED: '已退款',
  CANCELLED: '已取消',
  EXPIRED: '已过期',
};

/** 状态展示顺序（筛选下拉、图例、文档表格共用） */
export const ORDER_STATUS_ORDER = [
  'PENDING',
  'PAID',
  'PENDING_REFUND',
  'DISPUTED',
  'REFUNDED',
  'CANCELLED',
  'EXPIRED',
];

/** 筛选下拉选项：[{ value, label }] */
export const ORDER_STATUS_OPTIONS = ORDER_STATUS_ORDER.map((value) => ({
  value,
  label: ORDER_STATUS_LABELS[value],
}));

/** 状态文案；未知状态原样返回，空值返回 '-' */
export function orderStatusText(status) {
  if (!status) return '-';
  return ORDER_STATUS_LABELS[status] || String(status);
}

/** 状态语义提示（详情抽屉/提示文案用，说明这个状态对用户意味着什么） */
export const ORDER_STATUS_HINTS = {
  PENDING: '订单已提交，等待管理员确认收款；确认到账后自动发放积分与权益',
  PAID: '已确认到账，积分与权益已发放',
  PENDING_REFUND: '退款申请已提交，等待管理员审批',
  DISPUTED: '纠纷申诉处理中，等待管理员处理',
  REFUNDED: '已完成退款，对应积分已扣回',
  CANCELLED: '订单已取消，未产生任何权益',
  EXPIRED: '订单已过期，未在有效期内完成确认',
};

export function orderStatusHint(status) {
  return ORDER_STATUS_HINTS[status] || '';
}
