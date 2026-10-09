# 同事通过本机中继使用 MiniMax-M3

开发同事可以在自己的机器上运行本仓库后端，用独立的中继 token 调用 MiniMax-M3。官方 API key 只保留在中继主机；后端接入、变量识别测试和代码建议都不需要官方 key。本文的代码建议接口是非流式文本聊天，生成补丁后仍需开发者审阅、应用和运行测试。

## 合并拉取后，使用已收到的 relay-client.env

将负责人私下提供的 `relay-client.env` 重命名为 `.env.relay`，放到 **后端仓库根目录，与 `pom.xml` 同级**。新代码会忽略 `.env.relay`；保留文件原有三项 `OPENAI_BASE_URL`、`OPENAI_API_KEY`、`OPENAI_MODEL` 即可。前端仓库无需此文件。

Spring Boot 不会自动读取这个文件。在后端根目录的 PowerShell 中执行以下命令，从文件读取字面配置，转换为后端需要的变量，并启动独立 H2 测试实例。脚本不显示 token，也不修改系统环境变量。

```powershell
$relay = @{}
foreach ($line in Get-Content -LiteralPath '.env.relay') {
  if ($line.Trim().StartsWith('#') -or -not $line.Contains('=')) { continue }
  $pair = $line.Split('=', 2)
  $name = $pair[0].Trim()
  if ($name -in @('OPENAI_BASE_URL', 'OPENAI_API_KEY', 'OPENAI_MODEL')) {
    $relay[$name] = $pair[1].Trim()
  }
}
if (-not $relay['OPENAI_BASE_URL'] -or -not $relay['OPENAI_API_KEY'] -or $relay['OPENAI_MODEL'] -ne 'MiniMax-M3') {
  throw '中继配置缺少地址、token，或模型名称不是 MiniMax-M3'
}
$env:SPRING_PROFILES_ACTIVE = 'h2,minimax-relay'
$env:CONSENSE_MINIMAX_ENABLED = 'true'
$env:CONSENSE_MINIMAX_BASE_URL = $relay['OPENAI_BASE_URL'].TrimEnd('/') -replace '/v1$', ''
$env:CONSENSE_MINIMAX_RELAY_API_KEY = $relay['OPENAI_API_KEY']
$env:CONSENSE_H2_JDBC_URL = 'jdbc:h2:file:./data/teammate-consense;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE'
$env:CONSENSE_ALLOWED_ORIGINS = 'http://localhost:5173,http://127.0.0.1:5173'
mvn -DskipTests package
if ($LASTEXITCODE -ne 0) { throw '后端构建失败' }
java -jar target/consense-service-1.0.0.jar
```

`OPENAI_BASE_URL` 的末尾 `/v1` 会在映射到 Java 配置时移除；文件里的独立 `OPENAI_API_KEY` 会映射到 `CONSENSE_MINIMAX_RELAY_API_KEY`。SDK 开发工具则可直接使用同一个文件：`python tools/minimax_relay/client.py --env-file .env.relay --prompt-file review-prompt.txt --source src/main/java/com/consense/ai/MiniMaxChatClient.java`，先按后文安装依赖并创建问题文件。

启动前端时在前端仓库的另一个 PowerShell 窗口设置 `CONSENSE_API_TARGET=http://127.0.0.1:8080`，按前端 README 启动，选择“MiniMax 国内 Token Plan”。下面的 `.env.example` / `.env.local` 配置方式供需要自行填写配置或使用现有 MySQL 部署的同事参考。

## 地址和凭据

截至 2026-10-09，中继地址为 `http://10.149.131.175:8092`。同一局域网的设备需要能访问这个 IP；处在同一个已授权 Tailscale 网络中的设备也可使用 `100.81.17.113`。IP 或 token 更换后更新本机配置。

| 使用位置 | 配置值 |
| --- | --- |
| 本仓库 Java 后端 `CONSENSE_MINIMAX_BASE_URL` | `http://10.149.131.175:8092`，末尾不带 `/v1` |
| OpenAI SDK `OPENAI_BASE_URL` | `http://10.149.131.175:8092/v1` |
| 聊天完整 URL | `http://10.149.131.175:8092/v1/chat/completions` |
| 模型名称 | `MiniMax-M3` |
| 应用模型来源 ID | `minimax-cn`，页面名称为“MiniMax 国内 Token Plan” |

从项目负责人私下获得独立中继 token。负责人当前保存的客户端交付文件名为 `relay-client.env`，其中 `OPENAI_API_KEY` 是中继 token。把这个值写到同事自己的 `CONSENSE_MINIMAX_RELAY_API_KEY`。不要将官方 key、真实中继 token、填好后的 `.env.local`、含认证头的日志或截图提交到 Git/PR。`GET /health` 可检查服务和网络；`GET /v1/models` 带 token 可检查认证。这些检查本身不触发官方生成请求，也不能证明云端当时有空闲容量。

## 在同事机器上启动后端

需要 JDK 17、Maven 和本仓库正常所需的运行环境。若已经有 MySQL 环境，将下文 `h2,minimax-relay` 替换成 `mysql,minimax-relay`，继续使用原来的数据库配置。建议新建测试项目和数据库，不复制负责人的运行数据。

1. 将仓库根目录的 `.env.example` 复制为 `.env.local`，填入中继主机地址和独立 token。`CONSENSE_MINIMAX_BASE_URL` 使用根地址，不带 `/v1`。后端需要的最小内容如下（示例不包含真实凭据）：

   ```dotenv
   SPRING_PROFILES_ACTIVE=h2,minimax-relay
   CONSENSE_MINIMAX_BASE_URL=http://10.149.131.175:8092
   CONSENSE_MINIMAX_RELAY_API_KEY=< privately supplied relay token >
   CONSENSE_H2_JDBC_URL=jdbc:h2:file:./data/teammate-consense;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE
   CONSENSE_ALLOWED_ORIGINS=http://localhost:5173,http://127.0.0.1:5173
   CONSENSE_LLM_ENABLED=false
   CONSENSE_OCR_ENABLED=false
   ```

2. Spring Boot 不会自动读取 `.env.local`。在仓库根目录用 PowerShell 把这些字面值导入当前进程，然后构建启动：

   ```powershell
   $allowed = @('SPRING_PROFILES_ACTIVE', 'CONSENSE_MINIMAX_BASE_URL',
     'CONSENSE_MINIMAX_RELAY_API_KEY', 'CONSENSE_H2_JDBC_URL',
     'CONSENSE_ALLOWED_ORIGINS', 'CONSENSE_LLM_ENABLED', 'CONSENSE_OCR_ENABLED')
   foreach ($line in Get-Content -LiteralPath '.env.local') {
     if ($line.Trim().StartsWith('#') -or -not $line.Contains('=')) { continue }
     $pair = $line.Split('=', 2)
     $name = $pair[0].Trim()
     if ($allowed -contains $name) {
       [Environment]::SetEnvironmentVariable($name, $pair[1].Trim(), 'Process')
     }
   }
   mvn -DskipTests package
   java -jar target/consense-service-1.0.0.jar
   ```

   Linux/macOS 也可通过 IDE、容器或进程环境传入以上键。这个 dotenv 示例作为字面数据读取；含分号的 JDBC URL 不能直接当作未加引号的 shell 命令执行。

3. 前端配置 `CONSENSE_API_TARGET=http://127.0.0.1:8080`，按前端仓库 README 启动。访问自己的前端后在“模型来源”选择“MiniMax 国内 Token Plan”。普通用户和前端不需要 token。可以用自己的后端 `GET /api/system/llm-profiles` 确认 `minimax-cn` 显示 `configured: true`；它仅说明配置齐全。

`minimax-relay` profile 只从 `CONSENSE_MINIMAX_RELAY_API_KEY` 读取凭据，即使环境中保留了 `CONSENSE_MINIMAX_API_KEY` 或旧的 `MiniMaxCN_Token_Plan_API_Key`，也不会把官方 key 作为中继认证发送。不要通过额外 Spring 启动参数覆盖该 profile 的单次发送策略。OCR、embedding、rerank、PDF 渲染仍需各自部署配置；MiniMax 的聊天接口不能代替它们。使用 DOCX 的原生文本变量识别可先验证聊天路径。

## 用 MiniMax 参与变量识别测试

在新的测试项目上传对应的 NTT/SCT/SCC 模板及待识别资料，选择上述模型来源，再启动变量识别。检查每个候选值、原文引用、完整列表、来源冲突及应留空字段；保留本轮 run ID、harness 版本和结果。修改代码后用相同资料回归，同时增加不同变量值的新资料，防止把某份考题答案写成规则。

仓库中的 `src/test/resources/drafting/harness` 保留了可公开的脱敏重放资料；相应离线 Java 测试不调用云端。运行以下测试检查接入和 MiniMax 返回门控：

```text
mvn -Dtest=MiniMaxRelayConfigurationTest,MiniMaxOverloadHttpTest,MiniMaxProfileAdapterTest,OpenAiStructuredVettingDecodingTest test
```

离线测试通过、前端真实变量识别、最终文稿生成及 PDF 排版是不同验收项目。此前某一轮 79 个变量正确不能替代新资料验收。

## 用 MiniMax 参与代码分析和修改

提供了简单 SDK 客户端 `tools/minimax_relay/client.py`。它把明确指定的源文件和问题发送给 M3，返回建议或补丁，不运行 shell、不修改文件。开发者审阅后应用补丁，再运行离线测试和真实识别回归。不要将 `.env.local`、上传的私人合同、完整运行日志或其他凭据作为源文件上传。

```text
python -m pip install -r tools/minimax_relay/requirements.txt
```

新建一个普通 UTF-8 文本文件 `review-prompt.txt`，例如：

```text
请检查附带的 MiniMax adapter 和回归测试，确认经中继调用时不会叠加529重试。
指出具体问题；如需修改请给最小 unified diff 和建议运行的测试。
不要改变量业务规则，也不要声称已经执行测试。
```

在自己的测试机器执行：

```text
python tools/minimax_relay/client.py --env-file .env.local --prompt-file review-prompt.txt --source src/main/java/com/consense/ai/MiniMaxChatClient.java --source src/test/java/com/consense/ai/MiniMaxOverloadHttpTest.java --max-tokens 4096
```

客户端可以直接复用 `.env.local` 的 `CONSENSE_MINIMAX_BASE_URL` 和 `CONSENSE_MINIMAX_RELAY_API_KEY`，自动把根地址转换成 SDK 所需的 `/v1` 地址。指定 `--env-file` 时，该文件是本次调用唯一的配置来源，不继承进程中其他工具的 `OPENAI_*`；文件缺少完整配置会直接报错。未指定文件时才读取进程环境变量。也支持成对设置 `OPENAI_BASE_URL` 和 `OPENAI_API_KEY`，但不能把一组的地址与另一组的 token 混用。若这两个 SDK 别名都留空，会复用完整的 `CONSENSE_*` 配置；若任一非空，必须同时填好中继地址和中继 token。`OPENAI_MODEL` 只支持 `MiniMax-M3`。

这不是兼容所有代码代理的承诺：需要流式、Responses API 或 tools/function calling 的编辑器插件不在当前中继接口范围内。对于支持非流式 OpenAI Chat 的客户端，明确配置 `stream=false`、模型 `MiniMax-M3`、输出不超过 16,384，关闭客户端自动重试后再接入。

SDK 等效关键配置为：

```python
client = OpenAI(base_url="http://10.149.131.175:8092/v1",
                api_key=os.environ["CONSENSE_MINIMAX_RELAY_API_KEY"],
                timeout=1900, max_retries=0)
reply = client.chat.completions.create(
    model="MiniMax-M3", messages=[{"role": "user", "content": "Review this test..."}],
    max_tokens=4096, stream=False, n=1,
    extra_body={"thinking": {"type": "disabled"}, "reasoning_split": True})
```

客户端自身的离线验证为：

```text
python -m unittest discover -s tools/minimax_relay -p test_client.py -v
```

## 并发、超时和失败处理

中继最多同时处理两个上游生成，另有八个处理连接上限。第三个同时生成请求会返回 `429 relay_busy`，应等待现有调用结束；不要同时批量启动变量识别和多个代码建议请求。

中继仅对明确的 HTTP `529` 且 `error.type=overloaded_error` 进行最多三次尝试，等待 2 秒、5 秒。应用 `minimax-relay` profile 关闭自身 529 重试，仍保持一次性请求体和关闭重定向/网络重放；最终 529 返回前端。SDK `max_retries=0` 同样避免出现应用三次乘中继三次的九次调用。401、429、其他 5xx、断连和语义错误不因此自动重试。

每次上游超时为 600 秒，最坏的三次总耗时可达到 1,807 秒，所以新 profile 和 SDK 客户端设置 1,900 秒超时；常规运行通常远低于这个上限。中途取消客户端不能保证上游同步取消。中继使用 HTTP，授权同事需处于受信任局域网或 Tailscale；尚无公网 TLS 入口。接口仅提供认证的 `GET /v1/models`、非流式 `POST /v1/chat/completions` 和无认证健康检查。

当前前端 Drafting 的识别和生成等待时间覆盖上述单次调用上限；Advice 聊天仍使用默认 600 秒前端超时，极端上游等待时可能先在页面超时。遇到这种情况先检查后端是否还在处理，避免立即重复提交。上述 SDK 客户端不受该 Advice 页面等待限制。
