# 远程处理 / Remote processing

> 状态：**规范草案（v1）**。手机端的接口已经留好（[`RemoteApi.kt`](../app/src/main/java/com/dearded/spektralab/RemoteApi.kt)：数据结构、`RemoteBackend` 接口，默认实现 `RemoteBackends.DISABLED` 一律返回“正式版提供”），但没有任何网络代码、不申请网络权限；参考服务端尚未实现，排在正式版。本文是实现时的依据，改动接口必须同时改这里和 `RemoteApi.kt`。

把照片交给**你自己的**电脑、服务器或云电脑做全分辨率冲印、修复放大或大模型编辑，处理完传回手机。不经过任何第三方服务，没有中转、没有统计。

## 1. 和“徕卡一瞬”的区别

小米 17 Ultra 徕卡版的“徕卡一瞬”是拍摄时在手机上完成的固定色调（复刻 M9 与 M3 + Monopan 50），不是云端处理。这一类“确定性的胶片 / 色调”SpektraLab 的引擎已经在手机上完成。

远程模块对应的是手机厂商放到云端的那一层：三星 Galaxy AI 的生成式编辑（Google Cloud Imagen）、谷歌 Magic Editor、Adobe 桌面端的 AI 降噪。不同之处是它跑在用户自己的 GPU 上，因此：

- 照片不离开你自己的设备；
- 没有手机 GPU 单缓冲上限，按原始分辨率处理；
- 可以批量处理，可以用手机跑不动的大模型。

## 2. 架构

```
手机 ──HTTPS(固定证书指纹)──► spektralab-gateway ──stdio 帧协议──► spektralab-host（上游引擎）
                                  │
                                  ├─► comfyui（可选：修复、放大、指令编辑）
                                  └─► ollama（可选：视觉大模型调色建议）
```

- **gateway**：本文第 4 节的 HTTP 接口、扫码配对、任务队列、后处理钩子执行器。
- **host**：上游桌面版自带的 `spektralab-host`。它的 `export_frame` 已经接受一份完整的参数快照、自己开会话、自己写文件、发进度事件、支持中途取消，因此引擎不需要任何改动。
- **AI 后端**：ComfyUI 的“API 格式”工作流 JSON 直接作为钩子定义；Ollama 的 `format` 字段接受 JSON Schema，用来让视觉模型输出本应用的参数。

## 3. 服务器配置

| 档位 | 能做什么 | 硬件 | 磁盘 |
|---|---|---|---|
| **A 只跑胶片** | 全分辨率冲印、批量套风格、16 位 TIFF | 任意 4 核以上 x86_64 / ARM64（无 GPU 时用 Mesa lavapipe 软件 Vulkan），或任意支持 Vulkan 的显卡；内存 4–8 GB | 约 2 GB |
| **B 加修复与建议** | 降噪（NAFNet / Restormer）、放大（Real-ESRGAN）、视觉模型调色建议（Qwen3-VL 4B/8B） | 8 GB 显存（RTX 3060 / 4060 级）、16 GB 内存 | 20–40 GB |
| **C 加指令编辑** | “把天空换成黄昏”一类的大模型编辑（Qwen-Image-Edit / Step1X-Edit） | 24 GB 显存（3090 / 4090）、32–64 GB 内存；16 GB 显存可用量化加卸载，较慢 | 80–150 GB，建议 SSD |

**系统**

- Linux：首选。Docker + NVIDIA Container Toolkit；Vulkan 需要 `NVIDIA_DRIVER_CAPABILITIES=compute,utility,graphics`，镜像里自带 `nvidia_icd.json`（容器里经常缺这个文件）。
- Windows：CUDA 可以走 WSL2，但 WSL2 里的 Vulkan 不可靠，胶片引擎用 lavapipe 或原生 Windows 版上游。
- macOS：ComfyUI、Ollama 可用 Metal；Docker 在 Mac 上用不到 GPU，引擎需原生运行。
- 云电脑 / 租用 GPU（AutoDL、RunPod、各家云电脑等）：通常没有入站端口，用下面的隧道主动连出。

**网络**

- 局域网：直接用。
- 外网：推荐 Tailscale / WireGuard，不要直接把端口映射到公网。中国大陆访问 Tailscale 官方中继很慢，需要自建 DERP；也可以用 frp 经一台 VPS 转发。
- 上行带宽决定速度：RAW / DNG 10–60 MB，50 MP 的 16 位 TIFF 约 300 MB。外网时建议传 RAW 上去、拿 JPEG / HEIF 回来。上传分块、可断点续传。

**打包**（参考 Immich 的做法）：一个 `docker-compose.yml`，用 profile 选择组件；引擎镜像分 `:cpu`（lavapipe）和 `:nvidia` 两种，启动时自检 `vulkaninfo`，看不到 GPU 就退回 lavapipe；模型不打进镜像，钩子第一次启用时下载到挂载的 `models/` 目录，可配置 ModelScope 或 Hugging Face 镜像。

## 4. 接口 v1

- 基础地址 `https://<host>:8765/v1`。除 `/info`、`/pair` 外都要 `Authorization: Bearer <token>`。
- 每个响应带 `SpektraLab-API: 1`。只做向后兼容的新增；不兼容的改动换 `/v2`。
- 错误用 RFC 9457 `application/problem+json`：`{type, title, status, code, detail}`。`code` 沿用上游 host 的错误名（`bad_request`、`not_found`、`decode_failed`、`engine_error`、`unsupported`、`cancelled`、`io_error`、`internal`），另加 `unauthorized`、`too_large`、`busy`、`version_mismatch`、`hook_failed`。

| 方法与路径 | 作用 |
|---|---|
| `GET /info` | `{name, api:[1], server_version, fingerprint}`，不需鉴权，用于发现 |
| `POST /pair {code, device_name}` | 用一次性配对码换 `{token, device_id}` |
| `GET /capabilities` | 引擎版本与后端、支持的格式与色彩空间、上传上限、`features`、可用钩子列表（含参数 schema、许可、显存需求）、队列长度 |
| `HEAD /sources/{sha256}` | 返回已收到的字节数 `Upload-Offset`；等于总长表示文件已在服务器上 |
| `PUT /sources/{sha256}` | 分块上传，`Content-Range: bytes a-b/total`。按内容哈希存放，同一张 RAW 可被多个任务复用 |
| `POST /jobs` | 提交任务（见下），`Idempotency-Key` 为手机端任务 id；返回 `202 {job_id, position}` |
| `GET /jobs/{id}` | `{state, progress, phase, eta_s, error?, result?}`，state 为 `queued / running / done / failed / cancelled` |
| `GET /jobs/{id}/events` | SSE：`progress`、`state`、`done` |
| `DELETE /jobs/{id}` | 取消（映射到 host 的 `cancel`） |
| `GET /jobs/{id}/result` | 下载结果，支持 `Range`，`ETag` 为 sha256；保留 N 天后返回 `410` |
| `GET /jobs?since=` | 手机重启后找回自己的任务 |

**任务体**（与手机端 `ProcessingJob` 对齐）

```json
{
  "source": {"sha256": "…", "name": "DSC0001.ARW", "raw": true},
  "edit": { "…": "引擎线上参数，与手机端 Edit.toJson() 相同" },
  "look": null,
  "frame": null,
  "white_balance": {"temperature_k": 5200, "tint": 4},
  "highlight_stops": 0.0,
  "output": {"format": "tiff16", "long_edge": 0, "color_space": "display-p3", "quality": 92},
  "hooks": [{"name": "restore.denoise-nafnet", "args": {}}],
  "client": {"job_id": 1730500000123, "app_version": "0.1.x-alpha"}
}
```

- 手机不发送文件路径：服务器在 `jobs/<id>/` 下自己生成所有路径，绝不接受客户端给的路径。
- `workingEdge`、`maxPixels` 是手机 GPU 的限制，不发送；去掉这些限制正是远程处理的意义。
- 冲印后调整（`look`）、相纸边框（`frame`）、手机高光恢复目前只有手机端实现。服务器在 `features` 里如实声明是否支持；不支持时手机要提示或拒绝，**不能静默丢掉**。
- 服务器不认识的字段返回 `bad_request`，除非它在 `features` 里声明过。

## 5. 后处理钩子

钩子**只能在服务器的配置文件里登记**，手机只能按名字选用、传入 schema 允许的参数。手机永远不能下发脚本或命令，否则令牌被盗就等于远程执行代码。

```toml
[[hook]]
name = "restore.denoise-nafnet"
stage = "before_film"        # before_film | after_film
kind = "comfyui"             # comfyui | ollama | command
workflow = "hooks/nafnet.json"
license = "MIT"
min_vram_gb = 4
timeout_s = 600
```

- `command` 类型按 argv 执行，不经过 shell：`{input}` 是输入文件、`{outdir}` 是任务目录，环境变量 `SPK_JOB_ID`、`SPK_JOB_JSON`；以服务器的非特权用户运行，有超时。非零退出返回 `hook_failed` 和 stderr 最后 4 KB。
- **顺序**：修复 / 放大 → 指令编辑 → 胶片冲印。颗粒和光晕必须最后加，否则会被放大或扩散模型抹掉。
- **调色建议**（`advisor.*`）：视觉模型按 JSON Schema 输出本应用的参数，服务器校验、限幅、失败重试；手机把结果作为可编辑的建议显示，**不自动套用**。
- **生成式钩子**跑过之后，结果里注明，手机在照片信息里标记为 AI 编辑。

**默认可选的模型**

| 用途 | 模型 | 许可 | 档位 |
|---|---|---|---|
| 放大 | Real-ESRGAN | BSD-3 | B |
| 降噪 / 去模糊 | NAFNet、Restormer | MIT | B |
| 调色建议 | Qwen3-VL | Apache-2.0 | B |
| 指令编辑 | Qwen-Image-Edit、Step1X-Edit | Apache-2.0 | C |
| 生成式修复 | SUPIR | 非商用 | 默认关闭 |
| 指令编辑 | FLUX.1 Kontext [dev] | 非商用权重，须启用内容过滤 | 默认关闭 |

## 6. 安全

- **配对**：服务器首次启动生成自签证书并打印二维码 `spektralab://pair?h=<host:port>&fp=sha256:<指纹>&code=<一次性码>`。配对码 5 分钟或用一次后失效。手机把令牌存在 Keystore 加密的存储里；服务器可列出和吊销设备。
- **TLS**：手机只接受二维码里那个证书指纹（自定义 TrustManager；单靠 OkHttp 的 CertificatePinner 会先拒掉自签证书）。走 Tailscale 时可用 `*.ts.net` 正式证书。
- **发现**：mDNS `_spektralab._tcp`，TXT `api=1`、`fp=<指纹前 16 位>`。部分路由器会丢组播，扫码和手动输入始终可用。
- **网络权限**：当前安卓版不申请网络权限，承诺“没有任何东西离开手机”。远程处理需要 `INTERNET` 权限，该权限用户无法单独关闭，因此远程功能将放在单独的构建变体里，或在隐私说明中明确改写这条承诺，二选一，实施前定。

## 7. 手机端

- 上传：Android 14+ 用“用户发起的数据传输”任务（UIDT），以下版本用 WorkManager 前台任务；8–16 MB 分块，断线后从服务器报告的偏移续传。
- 任务提交后由服务器保管。手机被系统杀掉后，重新打开时轮询 `GET /jobs/{id}` 并下载结果。`ProcessingQueue` 持久化 `remoteJobId` 与上传偏移。
- 结果按本地导出同样的流程写入相册：先写临时文件、校验 sha256，再发布。

## 8. 云服务器、云电脑、租用显卡

接口本身不区分机器在哪里，难点只在“手机怎么连上它”。按机器有没有可用的入站端口分三种：

| 机器 | 连接方式 | 证书校验 |
|---|---|---|
| 有公网 IP 的云服务器 | 安全组放行 8765，手机直连 | 固定证书指纹（同局域网） |
| 云电脑、没有入站端口的机器 | gateway 主动连出：Tailscale（大陆需自建 DERP）或 frp 经一台 VPS 转发 | Tailscale 用 `*.ts.net` 正式证书；frp 用固定指纹 |
| 租用 GPU 平台自带的 HTTP 端口映射 | 用平台给的 HTTPS 地址 | 平台的正式证书，走系统 CA 校验，令牌照样必需 |

因此配对二维码里的校验方式有两种：`fp=sha256:…`（固定指纹）或 `tls=system`（系统 CA）。两种都必须带令牌。

**按小时计费的租用 GPU 要额外处理：**

- 实例随时会被关掉：模型、配对设备、未完成任务都放在持久卷上，实例重开后不用重新配对，任务可以续跑。
- 镜像启动要快：模型不打进镜像，首次启用时下载到持久卷。
- 队列空闲一段时间后可选自动关机，避免空跑计费。
- 手机端把“服务器离线”当常态：任务停在“等待服务器”，服务器回来后继续，不算失败。

（各平台端口映射的具体做法和价格尚未逐一核实，写配置指南时再按平台补齐。）

## 参考

- [ComfyUI 服务端路由](https://docs.comfy.org/development/comfyui-server/comms_routes)、[工作流 API 格式](https://docs.comfy.org/development/api-development/workflow-api-format)
- [Ollama 结构化输出](https://docs.ollama.com/capabilities/structured-outputs)
- [Immich 远程机器学习](https://immich.app/docs/guides/remote-machine-learning)
- [tus 断点续传协议](https://tus.io/protocols/resumable-upload)、[RFC 9457](https://www.rfc-editor.org/rfc/rfc9457)
- [Android UIDT 任务](https://developer.android.com/develop/background-work/background-tasks/uidt)
- [NVIDIA Container Toolkit](https://docs.nvidia.com/datacenter/cloud-native/container-toolkit/1.12.0/user-guide.html)
- [Derp-China（自建 Tailscale 中继）](https://github.com/S4kur4/Derp-China)
- 模型许可：[Real-ESRGAN](https://github.com/xinntao/Real-ESRGAN/blob/master/LICENSE)、[NAFNet](https://github.com/megvii-research/NAFNet/blob/main/LICENSE)、[Qwen3-VL](https://github.com/QwenLM/Qwen3-VL)、[Step1X-Edit](https://github.com/stepfun-ai/Step1X-Edit)、[SUPIR](https://github.com/Fanghua-Yu/SUPIR/blob/master/LICENSE)、[FLUX.1 Kontext dev](https://huggingface.co/black-forest-labs/FLUX.1-Kontext-dev)
- 徕卡一瞬：[GSMArena 评测](https://www.gsmarena.com/xiaomi_17_ultra_leica_edition-review-2922.php)
