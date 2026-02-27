# Java Oracle

Java Oracle is a framework for generating test oracles and verifying potential JDK bugs with LLM-assisted workflows.

一个面向 JDK 测试场景的 Oracle 生成与 Bug 验证框架。项目支持从已有测试中提取信息、生成/增强测试，并在多阶段流程中进行失败分析与 Bug 验证。
> This README is currently Chinese-first. A fuller English version is targeted for Q2 2026.

## 1. 环境配置

### 1.1 运行环境

- JDK：建议 **Java 23+**（项目中包含虚拟线程相关实现）
- Maven：3.9+
- 操作系统：Linux/macOS（Windows 需自行调整脚本和路径）

### 1.2 依赖数据准备

1. 克隆 OpenJDK 源码：

```bash
git clone https://github.com/openjdk/jdk17u-dev
```

2. 下载 JavaDoc（JDK 17 API 文档），解压到：

```text
jdk17u-dev/jdk/doc
```

3. 准备验证目录（用于执行与验证）：

```bash
mkdir verify && cp -r jdk17u-dev/test verify
```

### 1.3 关键配置

通过 `config.properties` 配置核心路径与并发参数，重点包括：

- JDK 相关路径（源码、测试、文档）
- 模型与 API Key（支持环境变量覆盖）
- 线程池与并发/QPS 控制
- `legacyEnhanceThenVerifyWorkflow`（流程模式开关）

## 2. 设计架构

核心分层如下：

1. **入口层**：`Main`
   - 解析命令并路由到对应处理器。
2. **命令层**：`CommandHandler`
   - 统一处理 `env / execute / generate / verify / getClass`。
3. **调度层**：`TestExecutionManager`
   - 管理测试套件、并发执行、统计、文件与 API 文档处理。
4. **用例层**：`TestCase`
   - 承载单个测试的增强、执行、失败分析、修复与重试循环。
5. **验证层**：`BugVerificationService` + `BugVerify`
   - 对候选 Bug 进行信息补全、验证和报告生成。
6. **信息收集层**：`InformationCollectionAgent`
   - 组合源码检索、JavaDoc 检索与外部搜索，支撑根因分析。

## 3. 工作流阶段

### 3.1 提取测试（输入筛选）

- 从目标路径加载测试集并执行。
- 将初次执行成功的测试作为后续“生成/增强”输入，过滤明显无效样本。

### 3.2 生成测试（增强与修复）

对单个测试通常按以下流水线运行：

1. `enhance`：基于 API 文档与上下文增强测试代码。
2. `execute`：执行增强后测试。
3. `verify/fix loop`：
   - 失败时先判定是否可能为真实 Bug；
   - 若更可能是测试问题，则进入修复并重跑；
   - 以轮次上限和状态收敛作为停止条件。

### 3.3 验证阶段（Bug Verification）

- 对候选 Bug 进行二次分析与信息补全。
- 汇总代码片段、文档证据与执行结果，生成报告（`BugReport/`）。

## 4. 文档匹配与注释对齐设计

框架通过 API 级别的信息绑定来降低“代码-文档”漂移：

1. 从测试代码中提取 API 调用签名。
2. 按签名映射到 JavaDoc 内容并注入到提示词上下文。
3. 在每轮修复后重新计算 API 文档映射，保证后续分析使用的是最新代码视图。

这种机制让注释/文档与当前测试代码保持同步，减少因上下文过期导致的误判。

## 5. 验证流程设计思路（细节）

验证流程遵循“先判定、再修复、再复验”的闭环：

- **判定层**：先区分“潜在真实缺陷”与“测试自身问题”。
- **修复层**：仅对测试问题做最小修复，避免污染失败信号。
- **复验层**：复跑并记录结构化结果，必要时升级到更深入的 Bug 验证流程。

同时，信息收集采用迭代策略，按需扩展证据范围（源码、JavaDoc、外部资料），以平衡准确性与开销。

## 6. 构建与运行

### 构建

```bash
mvn -B -Dmaven.test.skip=true package
```

### 运行

```bash
mvn -B -q exec:java -Dexec.mainClass=edu.tju.ista.llm4test.Main -Dexec.args="env"
mvn -B -q exec:java -Dexec.mainClass=edu.tju.ista.llm4test.Main -Dexec.args="execute java/lang"
```

## 7. Future Work

未来我们计划将当前框架进一步重构为多个可组合的 **Skills**（如检索 Skill、生成 Skill、验证 Skill、修复 Skill），以：

- 大幅精简现有耦合流程；
- 提升复用性与可替换性；
- 更清晰地支持不同任务路径下的编排与扩展。

## 8. CI

- `Build Linux Artifact`：构建并上传 Linux 产物。
- `Test Latest JDK Commits`：每日定时（UTC 02:00）拉取 `jdk17u-dev`，筛选最近 24 小时有变化的 `java/javax` 测试路径并运行验证。

### 8.1 GitHub Secrets（LLM API）

`Test Latest JDK Commits` 依赖 LLM API。请在仓库 `Settings -> Secrets and variables -> Actions` 中配置：

- 必需（至少配置一组）
  - `OPENAI_API_KEY` 或 `ARK_API_KEY` 或 `MOONSHOT_API_KEY`
- 可选（按 provider 配置）
  - `OPENAI_BASE_URL`, `OPENAI_MODEL`
  - `ARK_BASE_URL`, `ARK_MODEL`
  - `MOONSHOT_BASE_URL`
  - `BOCHA_API_KEY`（启用外部检索时）

工作流会在执行前进行 fail-fast 检查：若三组主 LLM Key 全部缺失，任务会直接失败并提示。

### 8.2 JDK 源码准备建议

当前 CI 采用“运行时 clone”的方式：

```bash
git clone https://github.com/openjdk/jdk17u-dev
```

这对持续集成是推荐默认方案（维护成本低、无需额外管理子模块）。
仅当你需要“固定到某个 commit 做严格可复现”时，再考虑 submodule 或在 workflow 中 pin 到指定 SHA。

### 8.3 配置优先级说明

当前实现是：优先读取 `config.properties`，如果为空再读取环境变量。

因此建议在 CI 中：

1. 把密钥放到 GitHub Secrets；
2. 保持 `config.properties` 中密钥项为空（或不写入），避免覆盖环境变量。
