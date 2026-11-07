# AI Commit Message - IntelliJ 平台插件

在提交面板的提交信息输入框上方提供「AI 生成提交信息」按钮：收集你勾选文件的 diff，调用本机命令行工具（默认 `claude`）生成符合 Conventional Commits 规范的中文提交信息并回填输入框。

## 功能

- 提交信息输入框上方工具栏新增「AI 生成提交信息」按钮（最左侧，星星图标）
- 基于提交面板勾选的文件生成：已跟踪文件取 `git diff HEAD -- <文件>`（含已暂存 + 未暂存部分），勾选的未跟踪新文件用 `--no-index` 生成全文件 diff
- 勾选数据双通道获取：优先动作上下文 `VcsDataKeys.CHANGES`，取不到时回退 `ChangesViewWorkflowManager → getUi().getIncludedChanges()`
- diff 截断到 1MB，prompt 要求按 Conventional Commits 规范输出中文提交信息
- 生成命令可在 `Settings | Tools | AI Commit Message` 自定义，默认 `claude`，可换成任何接收 stdin diff 的本地 CLI（如 `aichat`、自写脚本等）
- 子进程 PATH 自动注入探测到的 claude 目录（nvm 最新 > `~/.bun/bin` > `~/.local/bin` > `/usr/local/bin`）
- 失败时通知中展示命令的退出码与输出尾部，便于定位

## 兼容性

build 241–253.*（PhpStorm 2024.1 至 GoLand 2025.3 等 IntelliJ 平台 IDE）。

## 安装

`Settings | Plugins | ⚙ | Install Plugin from Disk...` → 选择构建产物 zip → 重启。

## 构建

```bash
# 默认：GoLand 2025.3 基线，since 253
./build.sh
# PhpStorm 2024.1 基线、全版本兼容包（字节码 17）
IDE_APP=~/Applications/PhpStorm.app SINCE=241 UNTIL='253.*' ./build.sh
```

依赖：IntelliJ IDEA CE 自带 JBR 21 的 `javac` + `zip`，无需 Gradle/JDK。字节码统一 `--release 17`。产物为 `build/ai-commit-<version>-since<build>.zip`。

## 配置

在 `Settings | Tools | AI Commit Message` 中填入生成命令，例如：

- `claude`（默认，自动探测二进制位置）
- `/usr/local/bin/claude -p`
- 任意能接收 stdin（prompt + diff）并输出提交信息的本地命令

## 项目结构

```
src/main/java/io/github/mr1024/aicommit/
├── GenerateCommitMessageAction.java   # 按钮：收 diff → 调生成命令 → 回填输入框
└── AiCommitSettings.java              # 设置页（生成命令）
src/main/resources/
├── META-INF/plugin.xml                # 动作挂在 Vcs.MessageActionGroup
└── icons/aiCommit.svg                 # 按钮图标
build.sh                               # javac + zip 手工构建
```

## 技术要点

- 提交信息输入框通过 `VcsDataKeys.COMMIT_MESSAGE_DOCUMENT` 获取 Document 回填
- 按钮挂在 `Vcs.MessageActionGroup` 组
- prompt 走临时文件重定向 stdin，规避大 diff 超出 ARG_MAX 的问题

## 许可证

MIT
