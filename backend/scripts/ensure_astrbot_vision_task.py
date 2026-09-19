"""确保 AstrBot 具备「摘要/分析专用视觉配置」：persona vision_task_quiet + profile vision-task。

为什么需要它
------------
AI 摘要 / AI 分析走 AstrBot 的 Agent 管线，其中有两件事会互相叠加把图片识别搞坏：

1. **模型来自配置档案（config profile），不是请求体里的 model。**
   默认档案的主模型是纯文本模型时，图片消息段会被丢掉，模型只能答「内容未知」。
   → 所以要有一个「主模型支持图片」的档案（本机叫 `vision`，模型 GLM-4.5V）。

2. **AstrBot 会按人格（persona）注入工具。** `persona.tools = null` 表示"用全部工具"，
   于是模型会去调 `send_message_to_user` / `future_task`，把「请稍等片刻」当成摘要正文；
   而且会话会记住 persona，复用旧会话会一直沿用旧人格。
   → 所以还要有一个 `tools = []` 的人格，以及绑定它的档案 `vision-task`。

本脚本幂等地补齐这两样东西（只新增，不改动既有档案与人格）：

    python backend/scripts/ensure_astrbot_vision_task.py

之后重启 AstrBot（后端：`POST /api/system/stop-astrbot` → `POST /api/system/start-astrbot`），
后端配置项 `astrbot.vision-config-name`（AI 对话用）与 `astrbot.vision-task-config-name`
（摘要/分析用）保持默认即可。
"""

from __future__ import annotations

import argparse
import datetime
import json
import os
import sqlite3
import sys
import urllib.error
import urllib.request

DEFAULT_ROOT = r"D:\ai\Documents\qq-web\Astrbot"
DEFAULT_BASE_URL = "http://127.0.0.1:6185"
DEFAULT_PERSONA_ID = "vision_task_quiet"
DEFAULT_PERSONA_PROMPT = "你是内容分析器。严格按用户给出的格式要求输出结果，不寒暄、不解释、不调用任何工具。"
DEFAULT_SOURCE_PROFILE = "vision"
DEFAULT_TARGET_PROFILE = "vision-task"


def read_token(repo_root: str) -> str:
    """从 backend/.env 读取 ASTRBOT_TOKEN。"""
    env_path = os.path.join(repo_root, "backend", ".env")
    with open(env_path, encoding="utf-8") as fh:
        for line in fh:
            if line.startswith("ASTRBOT_TOKEN="):
                return line.split("=", 1)[1].strip()
    raise SystemExit(f"未在 {env_path} 找到 ASTRBOT_TOKEN")


def api(base_url: str, token: str, method: str, path: str, payload=None):
    data = json.dumps(payload).encode("utf-8") if payload is not None else None
    req = urllib.request.Request(
        base_url + path,
        data=data,
        method=method,
        headers={"X-API-Key": token, "Content-Type": "application/json"},
    )
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        raise SystemExit(f"{method} {path} 失败: HTTP {exc.code} {exc.read().decode('utf-8', 'replace')}")


def ensure_persona(db_path: str, persona_id: str, system_prompt: str) -> None:
    conn = sqlite3.connect(db_path)
    cur = conn.cursor()
    row = cur.execute("select id, tools from personas where persona_id=?", (persona_id,)).fetchone()
    if row:
        print(f"[=] persona 已存在: {persona_id} (id={row[0]}, tools={row[1]})")
        conn.close()
        return

    next_id = (cur.execute("select max(id) from personas").fetchone()[0] or 0) + 1
    next_sort = (cur.execute("select max(sort_order) from personas").fetchone()[0] or 0) + 1
    now = datetime.datetime.now().isoformat(sep=" ", timespec="microseconds")
    cur.execute(
        "insert into personas (created_at, updated_at, id, persona_id, system_prompt, begin_dialogs,"
        " tools, skills, custom_error_message, folder_id, sort_order, is_default)"
        " values (?,?,?,?,?,?,?,?,?,?,?,?)",
        (now, now, next_id, persona_id, system_prompt, "[]", "[]", None, None, None, next_sort, 0),
    )
    conn.commit()
    conn.close()
    print(f"[+] 已创建 persona: {persona_id} (id={next_id}, tools=[])")


def ensure_profile(base_url: str, token: str, source: str, target: str, persona_id: str) -> None:
    profiles = api(base_url, token, "GET", "/api/v1/config-profiles")["data"]["info_list"]
    by_name = {p["name"]: p for p in profiles}
    if target in by_name:
        print(f"[=] 配置档案已存在: {target} (id={by_name[target]['id']})")
        return
    if source not in by_name:
        raise SystemExit(f"找不到源档案 {source}，请先在 AstrBot 里创建「主模型支持图片」的档案")

    raw = api(base_url, token, "GET", f"/api/v1/config-profiles/{by_name[source]['id']}")["data"]
    config = raw.get("config") if isinstance(raw, dict) and isinstance(raw.get("config"), dict) else raw
    old_persona = config["agent_runner"]["config"]["persona"]["persona_id"]
    config["agent_runner"]["config"]["persona"]["persona_id"] = persona_id

    created = api(base_url, token, "POST", "/api/v1/config-profiles", {"name": target, "config": config})
    print(
        f"[+] 已创建配置档案: {target} (id={created['data']['conf_id']})，"
        f"人格 {old_persona} → {persona_id}，模型 {config['agent_runner']['config']['model']['provider_id']}"
    )


def main() -> int:
    parser = argparse.ArgumentParser(description="补齐 AstrBot 视觉抽取任务所需的 persona 与配置档案")
    parser.add_argument("--astrbot-root", default=os.environ.get("ASTRBOT_ROOT", DEFAULT_ROOT))
    parser.add_argument("--base-url", default=os.environ.get("ASTRBOT_API_URL", DEFAULT_BASE_URL))
    parser.add_argument("--token", default=None, help="默认读 backend/.env 的 ASTRBOT_TOKEN")
    parser.add_argument("--persona-id", default=DEFAULT_PERSONA_ID)
    parser.add_argument("--persona-prompt", default=DEFAULT_PERSONA_PROMPT)
    parser.add_argument("--source-profile", default=DEFAULT_SOURCE_PROFILE, help="主模型支持图片的档案名")
    parser.add_argument("--target-profile", default=DEFAULT_TARGET_PROFILE)
    args = parser.parse_args()

    repo_root = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
    token = args.token or read_token(repo_root)
    db_path = os.path.join(args.astrbot_root, "data", "data_v4.db")
    if not os.path.exists(db_path):
        raise SystemExit(f"找不到 AstrBot 数据库: {db_path}（用 --astrbot-root 指定数据目录）")

    ensure_persona(db_path, args.persona_id, args.persona_prompt)
    ensure_profile(args.base_url, token, args.source_profile, args.target_profile, args.persona_id)
    print("\n下一步：重启 AstrBot 让新人格/档案生效")
    print("  POST /api/system/stop-astrbot  →  POST /api/system/start-astrbot")
    return 0


if __name__ == "__main__":
    sys.exit(main())
