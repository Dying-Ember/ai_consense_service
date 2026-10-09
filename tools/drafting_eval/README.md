# Drafting 变量识别：第五轮虚构资料与只读评分工具

这套工具供队员用 MiniMax-M3 在实际前端重跑变量识别，并用相同评分规则检查结果。要求 Python 3.10 或更新版本，仅使用标准库。所有命令从本后端仓库根目录执行；前端、后端和 MiniMax 配置请先按 [中继与开发移交说明](../../docs/minimax-relay-handoff.md) 启动。

## 资料边界

- **只上传 `fixtures/round5/01_upload_documents/` 中的 15 份 DOCX。** 文件正文明确标注 Fictional test correspondence，人物、地址、决议和工程信息均为软件测试虚构内容。
- `02_reference_do_not_upload/` 全部是评分或复核资料，**不要上传，也不要放进模型提示词、RAG、OCR来源包或代码代理的本轮识别上下文**。原文 TXT 也留在离线复核侧。
- NTT、SCT、SCC 赛方模板不在此包中，请从获得授权的赛方 `2a_Standard documents` 自行取得，上传到前端模板区。模板和项目往来资料是两个独立区域。
- 新建测试项目，上传原始 15 文件并等待全部成功解析，选择“MiniMax 国内 Token Plan”，通过前端发起一次变量识别。等待完成；采集前不要采用、确认、编辑变量或修改资料，也不要生成文稿。不要在历史 Bonsai 项目上覆盖重跑。

评分器 `validate_drafting_fixture.py` 是 aggregate 仓库原 `scripts/validate-drafting-fixture.py` 的**字节完全相同副本**，没有修改正确率、来源检查、列表完整性或缺失证据规则。原离线回归测试仅调整导入文件名，19 项原断言保持原样。

`grade()` 真正读取的参考文件是 `expected_values.json` 和 `incremental_expected_states.json`，同时直接读取 15 份 DOCX 的 OOXML 原文。其余提供的 `input_catalogue_snapshot.json`、`withheld_control.json`、15 份 TXT、`author_validation.json` 和 `fixture-freeze.json` 用于独立复核与原始出处记录。未携带其他题目的 change ledger 或 scenario config。原作者 seal 中的 `Ref/...` 是原包相对路径，可能列出未发布的辅助记录；当前子集的可用相对路径和字节哈希以 `fixtures/round5/transfer-manifest.json` 为准。封存源文件本身没有改写。

## 前端完成后采集

以下 PowerShell 命令只执行 GET，并把结果保存到已忽略的 `.local/`。先把 `$projectId` 改为刚才前端新建项目的实际 ID，后端端口按实际部署调整。该 API ID 与项目显示名称可能不同，可在前端请求地址或项目 API 中确认。当前批准 harness 版本已明确固定；如果队员有意升级 harness，请记录新版本及改动后再指定新的预期版本，不能为绕过失败任意改填。

```powershell
$backend = 'http://127.0.0.1:8080'
$projectId = 'round5-minimax-acceptance'
$projectPath = [Uri]::EscapeDataString($projectId)
$last = Invoke-RestMethod -Method Get -Uri "$backend/api/drafting/$projectPath/variables/extract-trace"
if ($last.code -ne 0 -or $last.data.status -ne 'completed') { throw '前端识别尚未完成或请求失败' }
$runId = $last.data.runId
$harness = 'draft-extraction-20261009.27-media-scope-and-floor-normalization+joint-evidence-20261007.2'
$runDir = ".local/drafting-eval/$runId"

python tools/drafting_eval/capture_drafting_run.py `
  --base-url $backend --project-id $projectId --run-id $runId `
  --expected-harness-version $harness --profile minimax-cn `
  --out-dir "$runDir/snapshot"
if ($LASTEXITCODE -ne 0) { throw '采集被拒绝，请检查版本、run、资料、采用状态或采集期间变动' }

python tools/drafting_eval/validate_drafting_fixture.py `
  --actual "$runDir/snapshot/variables.json" `
  --fixture tools/drafting_eval/fixtures/round5 `
  --out-dir "$runDir/score"
$scoreExit = $LASTEXITCODE
Write-Output "评分退出码: $scoreExit"
```

Linux/macOS 可用相同 Python 参数；先以只读 GET 取得已完成的 run ID，然后明确填写 `--project-id`、`--run-id`、`--expected-harness-version` 和新的 `.local/.../snapshot` 输出目录。不要将根 URL `/v1` 的模型中继地址当成本工具的 `--base-url`：这里请求的是 ConSense 后端 `/api/drafting/...`。

采集器不上传、不重跑模型、不采用、不编辑、不生成。它保存 `variables.json`、`plan.json`、`inputs.json`、`catalog.json`、`templates.json`、固定 run 的 `trace.json`、`manifest.json` 和 `capture-seal.json`。两次完整读取必须一致；固定 run 必须仍为 latest、completed、非 stale，且来源 revision、plan snapshot、模型 profile、catalogue version、15 份资料身份及完整原生文本投影必须吻合。输出目录必须是本仓库 `.local/` 下的新目录，已有目录拒绝覆盖。

manifest 的 15 个 DOCX 字节哈希取自本地冻结文件。现有后端没有项目输入原始二进制 GET 接口，因此不能宣称通过 API 反读证明上传字节完全相同。工具另行重算 trace 全文的 parsed-source hash，并把它与真实 plan 来源身份、完整 DOCX 原生投影对应；上传时仍需使用这里列出的原始文件。两次读取可检测持久化数据漂移，不能提供数据库原子快照，也不能观察尚未保存的并发识别。因此请在前端显示完成后保持项目静止再采集。

## 评分与解释

详细报告在 `.local/drafting-eval/<runId>/score/drafting-score.md` 和 `drafting-score.json`。

| 退出码 | 含义 |
|---|---|
| 0 | PASS，当前自动检查全部通过 |
| 1 | FAIL，有明确错误或验收边界失败 |
| 2 | INPUT_ERROR，资料或快照缺失、无效 |
| 3 | REVIEW，需逐项独立语义复核；不能当作执行失败，也不能直接当作全对 |

工具核对 79 个目录字段、全部 15 文件、79 项原生证据锚点、来源 identity 与 revision、列表每项和重复次数、21 项应留空边界、4 项刻意缺失，以及没有自动采用等条件。值一致和字面引用存在仍不自动证明完整合同语义；报告保留 REVIEW，复核者需要检查引用实际支持哪个值、表格列的角色、上下文限制及后续撤销/冲突。

## 已有结果与局限

现有 MiniMax-M3 运行 `c4bb7f20-cde1-4bae-8205-1da79f2a2375`，项目 `drafting-novel-round5-20261009`，从北京时间 2026-10-09 10:55:47 到 11:00:17，耗时约 269.933 秒。该次运行有值字段 **58/58 正确**，应留空字段 **21/21 正确留空**，四个刻意缺证字段 **4/4 正确留空**。自动评分原报告保留 **42 PASS / 37 REVIEW / 0 FAIL**；独立逐项复核 37 个 REVIEW 和 38 个候选引文后全部有原文语义支持。复核台账见 [独立语义报告](../../docs/testing/m3-round5-20261009/semantic-audit.md) 和 [逐候选 JSON](../../docs/testing/m3-round5-20261009/semantic-audit.json)。

这不是新的盲测：这套固定资料已用于 harness 开发并且参考答案现已随仓库交付。不能推广为所有新题、所有模型、H800/120B MoE 或最终合同生成的保证。本次完成的是变量识别；条款采用、填充、文稿与排版另行验收。

Bonsai 第五轮原始 first-run 和其失败记录必须保留。原 first-run 严格有值分数是 51/58；固定原回答在 `.27` 离线 replay 后为 56/58，Bills 与 Sections 两个模型输出/引用问题仍缺失。仓库 captured workflow 回归故意断言这些缺失；不要把参考答案写入 replay 或修改断言来伪装通过。M3 的上述全对成绩属于另一次真实运行，不能覆盖旧成绩。

## 无网络离线测试

```text
python -m unittest discover -s tools/drafting_eval/tests -v
```

19 项保留的评分器回归 + 9 项新增采集边界回归通过，共 28 项，零失败。测试不连接后端、不调用真实模型；采集测试使用冻结虚构资料和模拟 GET 返回，检查只读传输、完整来源与哈希、run/revision/catalogue 漂移、采用状态、输出目录和防覆盖等边界。原 M3 保存快照也已离线通过新采集器的来源检查，并用副本评分器复现相同 58/58、21/21、37 REVIEW 和零失败；没有重跑 M3。
