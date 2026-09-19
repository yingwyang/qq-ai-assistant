# 人格库（Persona Library）

这里存放本系统使用的人格，**文件即事实来源**：改文件 → 跑脚本 → 重启 AstrBot 生效。
写法必须遵守 [`doc/PERSONA_AUTHORING_GUIDE.md`](../PERSONA_AUTHORING_GUIDE.md)。

| 文件 | persona_id | 定位 |
|---|---|---|
| `ray.md` | `瑞吉儿·加德纳 (Ray)` | 冷淡克制的角色人格（短句、省略号、口癖） |
| `elysia.md` | `爱莉希雅` | 活泼明亮的朋友型人格（语气词、句尾「♪」） |
| `qq-summary-bot.md` | `qq_总结bot` | 分析者人格（忠实、对黑话敏感、分点习惯） |
| `gray-hazel.md` | `灰泽满` | QQ 群默认人格（虚拟主播，粉丝向语气） |

每个文件的结构（YAML front-matter + 五段式正文）：

```markdown
---
persona_id: 灰泽满        # AstrBot 里的人格名，改名等于新建
tools: []                 # 本系统约定：不挂工具
is_default: false         # 是否设为 AstrBot 默认人格（同一时间只应有一个 true）
---

# 你是谁
# 性格与态度
# 说话方式
# 边界（优先于上面的任何设定）
# 语气示例
```

## 应用

```bash
python backend/scripts/apply_astrbot_personas.py            # 预览差异（不动数据库）
python backend/scripts/apply_astrbot_personas.py --apply    # 写库
# 然后重启 AstrBot：前端「人格与状态」面板的重启按钮，或
# POST /api/system/stop-astrbot → POST /api/system/start-astrbot
```

脚本是幂等的：只在内容有差异时更新，`tools` 一律写成 `[]`。

## 和「副本」的关系

前端选人格时，系统会为该人格生成一份**无工具副本**（`qqai_p_<slug>`）并追加《运行契约》，
原始人格不动。所以：

- 这里改的是**原始人格**（用户在前端看到、AstrBot 后台里看到的那份）；
- 副本会在下次选用该人格时自动重新生成（前端显示「需同步（会重启）」）；
- 如果你希望副本立刻跟上，去前端点一次该人格即可。
