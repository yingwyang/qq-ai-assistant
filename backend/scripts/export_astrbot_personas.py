"""导出 AstrBot 人格原文，便于按规范重写（备份 + 审阅）。"""

import json
import os
import sqlite3

DB = r'D:\ai\Documents\qq-web\Astrbot\data\data_v4.db'
OUT_DIR = r'D:\ai\Documents\qq-web\Astrbot\data\persona-backup'

os.makedirs(OUT_DIR, exist_ok=True)
c = sqlite3.connect(DB)
cols = [d[1] for d in c.execute("PRAGMA table_info(personas)")]
rows = [dict(zip(cols, r)) for r in c.execute("select * from personas")]

stamp = '2026-09-19'
with open(os.path.join(OUT_DIR, f'personas-backup-{stamp}.json'), 'w', encoding='utf-8') as fh:
    json.dump(rows, fh, ensure_ascii=False, indent=2)

for r in rows:
    name = r['persona_id']
    safe = ''.join(ch if ch.isalnum() or ch in '-_' else '_' for ch in name)
    path = os.path.join(OUT_DIR, f'{safe}.txt')
    with open(path, 'w', encoding='utf-8') as fh:
        fh.write(r['system_prompt'] or '')
    print(f"{name}: prompt={len(r['system_prompt'] or '')} 字, tools={r['tools']}, default={r['is_default']} -> {path}")
