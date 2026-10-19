> 路径约定：
> - <模型根> = GPT-SoVITS v2Pro 安装目录（含 GPT_SoVITS/、runtime/）
> - <工作目录> = 放数据集与脚本的目录
> - <游戏目录> = 解包后的 galgame 目录

# 从 galgame 解包到 GPT-SoVITS 音色上线 —— 复用手册

> 首次实践：2026-10-19，《秽翼的尤斯蒂娅》→ 缇娅（Eustia）音色。
> 目标读者：之后要「炼制新音色」的自己。照着 §1→§8 走，参数与坑都在这。

---

## 0. 总览：一条流水线

```
游戏数据 → 定位语音 → 抽音频 → 句级对齐(voice↔说话人↔台词) → ASR 补日文
        → list.txt → 三步预处理(文本/HuBERT/语义+SV) → SoVITS 训练 → GPT 训练
        → 权重落盘 → application.yml characters → /api/system/tts 验证
```

所有中间产物统一放 `<工作目录>/`，脚本都在那里。

---

## 1. 定位语音资源（BGI 引擎为例）

《秽翼的尤斯蒂娅》是 **BGI（Buriko）** 引擎，汉化版：**文本 GBK 中文、语音日文原声**。

用 GARbro 列出每个 arc 的条目前缀，判断内容：

| arc | 内容 |
|---|---|
| data01000 | 剧本（205 个 .cst） |
| data02001~02101 | 立绘（face_ / tati_） |
| **data04001~04099** | **语音**（约 3.5 万条，命名 `aiy<场景><序号>`） |
| data02500 | CG（hcg_ / eve_） |
| data02700 | 背景（bg_） |
| data02800 | 场景图 |
| data02840 | 动画（.mpg） |
| data05000 / 05010 | BGM / SE |

命令（**必须 cd 到游戏目录用相对路径**，中文绝对路径直接传参容易失败）：

```bash
GAR='<GARbro>'
cd '<游戏目录>'
"$GAR" data04001.arc               # 列内容
"$GAR" -x data04001.arc aiy010000010   # 按条目名抽取
```

⚠️ 不要把输出管道给 `sed`/`Select-Object -First N`——会提前断流杀掉进程，只解出前几个。
重定向到文件再统计。

---

## 2. 语音格式：剥 64 字节头

BGI 语音文件 = **64 字节 Buriko 头 + 标准 Ogg Vorbis**：

- 第 0~3 字节（小端 uint32）= 音频数据偏移，实测 **0x40 = 64**
- 偏移 64 起是 `OggS`，可直接当 .ogg 用
- 采样率 **44100Hz、单声道**——正好是 TTS 训练要求的格式，**无需重采样**

```js
const buf = fs.readFileSync(file);
const off = buf.readUInt32LE(0);
if (buf.subarray(off, off + 4).toString('latin1') !== 'OggS') throw new Error('not ogg');
fs.writeFileSync(out, buf.subarray(off));
```

---

## 3. 句级对齐：从 .cst 字节码抽 voice ↔ 说话人 ↔ 台词

剧本是编译字节码，文本以**NUL 分隔的字符串表**存放，voice 文件名（`aiy` + 9 位数字）
后面紧邻就是「说话人」「台词」。编码是混合的：

- 中文台词 = **GBK**
- 日文原文 = **Shift-JIS**
- 图像/指令名 = ASCII

抽取算法：
1. 按 `0x00` 切分字符串表；
2. 每块同时用 GBK 与 Shift-JIS 解码，打分：**假名 +5、汉字 +1、半角片假名 −6、U+FFFD −10**；
3. 允许 **trim 0~3 个前导字节**（块首常混入操作码字节），取得分最高的解码；
4. 命中 `^aiy\d{9}` 后，取后续 blocks：短且无「」。！？的名字 = 说话人，其余 = 台词。

产出 `voices.tsv`（本项目 **36,038 条**，说话人分布：凯伊姆 4625、缇娅 3344、西斯狄娜 2086…）。

⚠️ 日文原文会被 NUL 切碎（只能拿到句尾），**不可靠**；训练文本一律走 ASR。

---

## 4. ASR 补文本（关键）

用 **faster-whisper**（本项目用 `small`，461MB，GPU fp16）：

```bash
# 模型放 GPT-SoVITS/tools/asr/models/faster-whisper-small
python asr_all.py <model_dir> <raw_wav_dir> <list.txt> <speaker> ja
```

- 过滤：时长 **<1s 或 >15s** 的丢（太短噪音、太长难对齐）
- 输出格式：`wav路径|说话人|语言|文本`，**UTF-8 写文件**（Python stdout 是 cp936，重定向会成乱码）
- 断点续跑：已有行按 basename 跳过
- 实测 3338 条 → 2895 条可用（转写质量与中文台词一致）

---

## 5. 三步预处理（GPT-SoVITS v2Pro）

环境变量驱动（照 `webui.py` 的 1abc 步骤）：

```bash
export inp_text=<list.txt>            # 1-get-text
export inp_wav_dir=<raw>
export exp_name=eustia
export i_part=0  all_parts=1
export opt_dir=<模型根>/logs/<exp>    # ⚠️ 必须是 logs/<exp>
export bert_pretrained_dir=<模型根>/GPT_SoVITS/pretrained_models/chinese-roberta-wwm-ext-large
export cnhubert_base_dir=<模型根>/GPT_SoVITS/pretrained_models/chinese-hubert-base
export pretrained_s2G=<模型根>/GPT_SoVITS/pretrained_models/v2Pro/s2Gv2Pro.pth
export s2config_path=<模型根>/GPT_SoVITS/configs/s2v2Pro.json
export sv_path=<模型根>/GPT_SoVITS/pretrained_models/sv/pretrained_eres2netv2w24s4ep4.ckpt
export is_half=True  version=v2Pro

python GPT_SoVITS/prepare_datasets/1-get-text.py        # → 2-name2text-0.txt（+ 3-bert，仅 zh）
python GPT_SoVITS/prepare_datasets/2-get-hubert-wav32k.py  # → 4-cnhubert/ 5-wav32k/
python GPT_SoVITS/prepare_datasets/2-get-sv.py          # → 7-sv_cn/（v2Pro 必需！）
python GPT_SoVITS/prepare_datasets/3-get-semantic.py    # → 6-name2semantic-0.tsv
```

**合并分片**（训练读的是无后缀文件）：

```bash
cp $opt_dir/2-name2text-0.txt  $opt_dir/2-name2text.txt
cp $opt_dir/6-name2semantic-0.tsv $opt_dir/6-name2semantic.tsv
```

⚠️ 坑：
- `3-bert` 对纯日语**为空是正常的**（BERT 特征只对 `lan=="zh"` 生成）。
- `opt_dir` 写错 → 训练报 `AssertionError`（找不到 2-name2text/4-cnhubert/5-wav32k/7-sv_cn）。
- v2Pro 一定要跑 **2-get-sv.py**，否则 `data_utils.py` 的 `assert os.path.exists(self.path7)` 失败。

---

## 6. 训练（照 webui.py 复刻）

SoVITS（`s2v2Pro.json` 改字段）：

| 字段 | 值 |
|---|---|
| version | v2Pro |
| pretrained_s2G / s2D | …/pretrained_models/v2Pro/s2Gv2Pro.pth / s2Dv2Pro.pth |
| batch_size | **2**（8GB 显存实测；4 会 CUDA OOM） |
| epochs | **4**（每 2 轮存一次，先拿到可用权重） |
| save_every_epoch | 2 |
| save_weight_dir | SoVITS_weights_v2Pro |
| data.exp_dir / s2_ckpt_dir | …/logs/eustia |
| name / version | eustia / v2Pro |

```bash
# ⚠️ 必须先建检查点目录，否则第 2 轮保存时 move 失败（白跑整轮）
mkdir -p <模型根>/logs/<exp>/logs_s2_v2Pro
python -s GPT_SoVITS/s2_train.py --config <logs/eustia/tmp_s2.json>
```

GPT（`s1longer-v2.yaml` 改字段）：

| 字段 | 值 |
|---|---|
| pretrained_s1 | …/pretrained_models/s1v3.ckpt |
| batch_size | 4 |
| epochs | 15 |
| save_every_n_epoch | 5 |
| half_weights_save_dir | GPT_weights_v2Pro |
| train_semantic_path | <exp>/6-name2semantic.tsv |
| train_phoneme_path | <exp>/2-name2text.txt |

```bash
python -s GPT_SoVITS/s1_train.py --config_file <logs/eustia/tmp_s1.yaml>
```

⚠️ **内存坑（本机 15.3GB）**：`s2_train.py` 默认 `num_workers=5`，每个 worker 完整加载
约 1.5GB 特征（cnhubert 744MB + wav32k 619MB + sv 117MB）→ 5 份 + 主进程直接
`fatal: Memory allocation failure`。解法：

```python
src = open('GPT_SoVITS/s2_train.py', encoding='utf-8').read().replace('num_workers=5', 'num_workers=0')
open('GPT_SoVITS/_s2_train_nw0.py','w',encoding='utf-8').write(src)
```
再用 `_s2_train_nw0.py` 训练，并且**不要和 GPT 训练同时跑**。

⚠️ **worker 数不是越低越好**：`num_workers=0` 时数据加载在主进程，实测只有 1.3 it/s；
`2` 个 worker 也没有明显提升（瓶颈在 GPU 计算本身）。内存够时应保留 2。

⚠️ **单卡还要绕开 DDP**：`s2_train.py` 用 `DDP(net_g, device_ids=[rank])` 包装模型，
单卡 8GB 会在 DDP 初始化（`_sync_module_states` → broadcast）处就 CUDA OOM。补丁：

```python
src = src.replace('net_g = DDP(net_g, device_ids=[rank], find_unused_parameters=True)', 'net_g = net_g.cuda()')
src = src.replace('net_d = DDP(net_d, device_ids=[rank], find_unused_parameters=True)', 'net_d = net_d.cuda()')
src = src.replace('net_g.module', 'net_g').replace('net_d.module', 'net_d').replace('generator.module', 'generator')
```

脚本里的 `device` 变量是 `"cpu"`（给非 CUDA 分支用），所以**必须显式 `.cuda()`**；
写 `.to(device)` 会让模型留在 CPU，训练时报
`Expected all tensors to be on the same device, but found at least two devices, cuda:0 and cpu`。

实测速度：RTX 5060 Laptop（8GB）v2Pro SoVITS 约 **1.5 it/s**，1450 步/轮 ≈ 16 分钟/轮。

---

## 7. 接入网站（qq-ai-assistant）

`application.yml` 的 `gpt-sovits.characters` 加一条：

```yaml
- name: Eustia
  label: 尤斯蒂娅（秽翼）
  gpt-path: GPT_weights_v2Pro/eustia-e15.ckpt
  sovits-path: SoVITS_weights_v2Pro/eustia_e8_sXXXX.pth
  ref-audio: reference/Eustia_ref.wav     # 相对 modelRoot
  prompt-text: 本名はユースティアと言うらしいのですが、よくわかりません。
  prompt-lang: ja
```

参考音频要求：**3~10 秒、干净、单人、无 BGM**；prompt-text 必须是它的逐字转写
（v2Pro 的 SoVITS 用 v3 系，**prompt_text 不能为空**）。

验证：

```bash
GET  /api/system/tts/characters     # 列表里应有 Eustia
POST /api/system/tts {text, character:"Eustia", ...}
```

⚠️ `/set_gpt_weights`、`/set_sovits_weights` 是 **api_v2 的全局状态**：并发切音色会互相覆盖
（声音串台）。**该竞态已修复**（提交 579d864）：`GptSovitsService.generateVoice` 用
`ReentrantLock` 把「切换权重 → 合成」整段串行化，代价是 TTS 请求排队（单次 1~5 秒）。

---

## 8. 已知坑速查

| 现象 | 原因 / 解法 |
|---|---|
| `ModuleNotFoundError: tools.portable_runtime` | v5 的 api_v2 需要脚本目录在 sys.path；**不要用 `python -I`** |
| 换目录后 import 到了旧安装 | 便携 runtime 的 `Lib/site-packages/users.pth` 硬绑旧路径，sed 改到新目录 |
| `3-bert` 空 | 正常：BERT 特征只对中文行生成 |
| 训练报 `AssertionError`（无路径） | `opt_dir` 不是 `logs/<exp>`，或没合并 `-0` 分片，或漏跑 2-get-sv |
| `fatal: Memory allocation failure` | s2 的 5 个 dataloader worker 撑爆内存 → `num_workers=0` |
| 合成 400 `prompt_text cannot be empty` | v3 系 SoVITS 必须给参考音频的逐字文本 |
| GARbro 只解出前几个 | 管道接 saw/Select 导致断流；重定向到文件 |
| 脚本里中文路径（GARbro） | 先 `cd` 到目录用相对路径；或全部用 `D:/...` 形式 |

---

## 9. 时间与资源参考（RTX 5060 Laptop 8GB / 15.3GB RAM）

| 阶段 | 耗时 |
|---|---|
| 抽 3338 条语音 | ~55 分钟 |
| ASR 2895 条（small, fp16） | ~25 分钟 |
| 三步预处理（2896 条） | ~15 分钟 |
| SoVITS 8 epoch（worker=0） | 见 §10 |
| GPT 15 epoch（batch 4） | ~70 分钟 |

---

## 10. 本次实测结果（2026-10-19，《秽翼的尤斯蒂娅》→ 缇娅）

| 项 | 结果 |
|---|---|
| 语音抽取 | 3338 条（14 个 arc，失败 0） |
| 句级对齐 | 36,038 条 voice↔说话人↔台词 |
| ASR 可用样本 | 2895 条（过滤 <1s / >15s / 空转写） |
| 预处理 | 2896 条：2-name2text / 4-cnhubert / 5-wav32k / 6-name2semantic / 7-sv_cn |
| GPT 训练 | 15 epoch，最终 **top_3_acc 0.846**，产出 eustia-e5/e10/e15.ckpt |
| SoVITS 训练 | 4 epoch（batch 2，1.5 it/s），e4 检查点 **eustia_e4_s5800.pth**（另有 e2） |
| 接入 | application.yml characters 增加 Eustia，重启后端生效 |
| 验证 | /api/system/tts/characters 返回 3 个角色；/api/system/tts 返回 audioUrl，落地 44.1k wav |

**接入后的耗时观察（重要）**：换角色后的首次合成 **4.9s**（含权重加载），同角色第二次 **1.8s**。
原因是后端每次切音色都会调 `/set_gpt_weights` + `/set_sovits_weights`，
**每次切权重都会重新加载模型**。多角色混用时每次切换都要付这个代价。

**采样率**：v2Pro 输出 32kHz（训练特征 32k）；参考音频用 44.1k 也没问题，服务端会自行重采样。
