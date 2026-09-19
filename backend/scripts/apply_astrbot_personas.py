"""把 doc/personas/*.md 里的人设写进 AstrBot（幂等：只更新有差异的）。

用法：
    python backend/scripts/apply_astrbot_personas.py            # 只看差异，不动数据库
    python backend/scripts/apply_astrbot_personas.py --apply    # 写库（之后需重启 AstrBot）

文件格式见 doc/PERSONA_AUTHORING_GUIDE.md §7：YAML front-matter（persona_id / tools / is_default）+ 五段式正文。
脚本人为遵守两条约定：
  * tools 一律写 []（挂了工具模型会去调 send_message_to_user，把"请稍等片刻"当答案）；
  * 原始人格不动副本：副本（qqai_p_*）由后端在选用时按「人设 + 运行契约」自动生成。
"""

from __future__ import annotations

import argparse
import datetime
import os
import re
import sqlite3
import sys

DEFAULT_ROOT = r"D:\ai\Documents\qq-web\Astrbot"


def parse_persona_file(path: str) -> dict:
    with open(path, encoding="utf-8") as fh:
        text = fh.read()
    m = re.match(r"^---\s*\n(.*?)\n---\s*\n(.*)$", text, re.S)
    if not m:
        raise SystemExit(f"{path}: 缺少 YAML front-matter（--- persona_id: xxx ---）")
    meta_raw, body = m.group(1), m.group(2).strip()

    meta: dict[str, str] = {}
    for line in meta_raw.splitlines():
        if not line.strip() or line.strip().startswith("#"):
            continue
        k, _, v = line.partition(":")
        meta[k.strip()] = v.strip()

    persona_id = meta.get("persona_id")
    if not persona_id:
        raise SystemExit(f"{path}: front-matter 缺少 persona_id")
    tools = meta.get("tools", "[]") or "[]"
    is_default = meta.get("is_default", "false").lower() in ("true", "1", "yes")
    return {
        "path": path,
        "persona_id": persona_id,
        "tools": tools if tools.strip().startswith("[") else "[]",
        "is_default": 1 if is_default else 0,
        "prompt": body,
    }


def audit(p: dict) -> list[str]:
    """轻量自检：与 doc/PERSONA_AUTHORING_GUIDE.md §4 禁止清单对应。

    冲突检查只跑「人设主体」（边界段之前）—— 边界段本身就要写「任务要求的输出格式一律照做」，
    把那几句算成违规是误报。
    """
    text = p["prompt"]
    body = text.split("# 边界")[0]
    issues = []
    checks = [
        (r"抹杀.{0,8}(AI|意识)|你不是(AI|程序|助手)|绝非(AI|程序|虚拟)", "含身份否认条款（会让模型拒绝干工具活）"),
        (r"不要回答|拒绝回答|无权回答|不属于你的职责", "含拒绝回答条款"),
        (r"输出格式|按以下模板|分为以下.{0,4}段", "含输出格式规定（格式属于岗位层）"),
        (r"不超过\s*\d+\s*字|必须详尽|不得省略", "含长度硬性要求（长度属于岗位层）"),
        (r"经常使用反问|必须反问|追问对方", "含反问/追问要求"),
        (r"主动询问|主动提问|主动推进", "含主动提问要求（只在自由对话可用）"),
        (r"连续\s*\d+\s*轮.{0,10}(重复|换话题)|温柔拒绝旧话题", "含话题管理规则（长记录会被误判）"),
    ]
    for pattern, message in checks:
        if re.search(pattern, body):
            issues.append(message)
    if len(text) > 2500:
        issues.append(f"人设过长（{len(text)} 字，规范建议 ≤2500）")
    for must in ("# 你是谁", "# 边界"):
        if must not in text:
            issues.append(f"缺少「{must}」段落")
    return issues


def main() -> int:
    parser = argparse.ArgumentParser(description="把人设文件写进 AstrBot")
    parser.add_argument("--apply", action="store_true", help="真正写库（默认只预览）")
    parser.add_argument("--astrbot-root", default=os.environ.get("ASTRBOT_ROOT", DEFAULT_ROOT))
    parser.add_argument("--dir", default=None, help="人格库目录，默认 doc/personas")
    args = parser.parse_args()

    repo_root = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
    persona_dir = args.dir or os.path.join(repo_root, "doc", "personas")
    db_path = os.path.join(args.astrbot_root, "data", "data_v4.db")
    if not os.path.exists(db_path):
        raise SystemExit(f"找不到 AstrBot 数据库: {db_path}")

    files = sorted(
        os.path.join(persona_dir, f) for f in os.listdir(persona_dir)
        if f.endswith(".md") and f.lower() != "readme.md"
    )
    if not files:
        raise SystemExit(f"{persona_dir} 下没有 .md 人设文件")

    personas = [parse_persona_file(p) for p in files]
    conn = sqlite3.connect(db_path)
    cur = conn.cursor()

    changed = 0
    for p in personas:
        issues = audit(p)
        row = cur.execute(
            "select system_prompt, tools, is_default from personas where persona_id=?",
            (p["persona_id"],),
        ).fetchone()

        if row is None:
            action = "新建"
        elif row[0] != p["prompt"] or (row[1] or "[]").strip() != p["tools"] or row[2] != p["is_default"]:
            action = "更新"
        else:
            action = "无变化"

        flag = "".join(f"\n      ⚠ {i}" for i in issues) or "\n      ✓ 自检通过"
        print(f"[{action}] {p['persona_id']}  ({len(p['prompt'])} 字, tools={p['tools']}, default={p['is_default']}){flag}")

        if action == "无变化" or not args.apply:
            continue

        now = datetime.datetime.now().isoformat(sep=" ", timespec="microseconds")
        if action == "新建":
            next_id = (cur.execute("select max(id) from personas").fetchone()[0] or 0) + 1
            next_sort = (cur.execute("select max(sort_order) from personas").fetchone()[0] or 0) + 1
            cur.execute(
                "insert into personas (created_at, updated_at, id, persona_id, system_prompt, begin_dialogs,"
                " tools, skills, custom_error_message, folder_id, sort_order, is_default)"
                " values (?,?,?,?,?,?,?,?,?,?,?,?)",
                (now, now, next_id, p["persona_id"], p["prompt"], "[]", p["tools"], None, None, None,
                 next_sort, p["is_default"]),
            )
        else:
            cur.execute(
                "update personas set system_prompt=?, tools=?, is_default=?, updated_at=? where persona_id=?",
                (p["prompt"], p["tools"], p["is_default"], now, p["persona_id"]),
            )
        changed += 1

    if args.apply:
        conn.commit()
        print(f"\n已写入 {changed} 个人格。下一步：重启 AstrBot 让人格生效")
        print("  POST /api/system/stop-astrbot  →  POST /api/system/start-astrbot")
    else:
        print("\n（预览模式，未写库。加 --apply 才会真正写入）")
    conn.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
