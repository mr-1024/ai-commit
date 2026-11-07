package io.github.mr1024.aicommit;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.IconLoader;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.VcsDataKeys;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.ChangesUtil;
import com.intellij.openapi.vcs.changes.ChangesViewWorkflowManager;
import com.intellij.openapi.vcs.changes.ui.ChangesListView;
import com.intellij.vcs.commit.ChangesViewCommitWorkflowHandler;
import com.intellij.vcs.commit.CommitWorkflowUi;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 提交信息输入框上方的「AI 生成提交信息」按钮。
 * 流程：取提交面板勾选的文件 → 收集这些文件相对 HEAD 的 diff → 调 claude CLI 生成 → 回填提交信息输入框。
 */
public class GenerateCommitMessageAction extends AnAction {

    private static final Logger LOG = Logger.getInstance(GenerateCommitMessageAction.class);
    /** diff 截断上限（字符数） */
    private static final int MAX_DIFF_LENGTH = 1_000_000;
    /** claude CLI 超时时间（秒） */
    private static final int CLAUDE_TIMEOUT_SECONDS = 180;

    public GenerateCommitMessageAction() {
        super("AI 生成提交信息", "基于提交面板勾选文件的 diff 用 claude 生成 Conventional Commits 提交信息",
                IconLoader.getIcon("/icons/aiCommit.svg", GenerateCommitMessageAction.class));
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        // 仅在提交信息输入框上下文中可用
        e.getPresentation().setEnabledAndVisible(
                e.getProject() != null && e.getData(VcsDataKeys.COMMIT_MESSAGE_DOCUMENT) != null);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        Document document = e.getData(VcsDataKeys.COMMIT_MESSAGE_DOCUMENT);
        if (project == null || document == null) {
            return;
        }
        String baseDir = project.getBasePath();
        if (baseDir == null) {
            return;
        }
        // 勾选的变更：已跟踪文件来自 VcsDataKeys.CHANGES，新增未跟踪文件来自 ChangesListView 的 key
        List<File> files = new ArrayList<>();
        Change[] changes = e.getData(VcsDataKeys.CHANGES);
        if (changes != null) {
            for (Change change : changes) {
                FilePath path = ChangesUtil.getFilePath(change);
                if (path != null && path.getIOFile() != null) {
                    files.add(path.getIOFile());
                }
            }
        }
        Iterable<FilePath> unversioned = e.getData(ChangesListView.UNVERSIONED_FILE_PATHS_DATA_KEY);
        List<File> unversionedFiles = new ArrayList<>();
        if (unversioned != null) {
            for (FilePath path : unversioned) {
                if (path.getIOFile() != null) {
                    unversionedFiles.add(path.getIOFile());
                }
            }
        }
        // 提交信息输入框的动作上下文拿不到勾选数据时（非模态提交面板场景），
        // 从提交工作流 UI 直接取勾选（included）的变更
        if (files.isEmpty() && unversionedFiles.isEmpty()) {
            ChangesViewCommitWorkflowHandler handler =
                    ChangesViewWorkflowManager.getInstance(project).getCommitWorkflowHandler();
            if (handler != null) {
                CommitWorkflowUi ui = handler.getUi();
                if (ui != null) {
                    List<Change> included = ui.getIncludedChanges();
                    if (included != null) {
                        for (Change change : included) {
                            FilePath path = ChangesUtil.getFilePath(change);
                            if (path != null && path.getIOFile() != null) {
                                files.add(path.getIOFile());
                            }
                        }
                    }
                    List<FilePath> includedUnversioned = ui.getIncludedUnversionedFiles();
                    if (includedUnversioned != null) {
                        for (FilePath path : includedUnversioned) {
                            if (path.getIOFile() != null) {
                                unversionedFiles.add(path.getIOFile());
                            }
                        }
                    }
                }
            }
        }
        if (files.isEmpty() && unversionedFiles.isEmpty()) {
            notify(project, "AI Commit", "请先在提交面板勾选要提交的文件", NotificationType.WARNING);
            return;
        }
        new Task.Backgroundable(project, "AI 生成提交信息", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setText("收集变更 diff...");
                doGenerate(project, document, baseDir, files, unversionedFiles, indicator);
            }
        }.queue();
    }

    private void doGenerate(@NotNull Project project, @NotNull Document document, @NotNull String baseDir,
                            @NotNull List<File> files, @NotNull List<File> unversionedFiles,
                            @NotNull ProgressIndicator indicator) {
        try {
            String repoRoot = runGit(baseDir, Arrays.asList("rev-parse", "--show-toplevel"));
            if (repoRoot == null || repoRoot.trim().isEmpty()) {
                notify(project, "AI Commit", "当前目录不是 git 仓库，无法生成提交信息", NotificationType.ERROR);
                return;
            }
            repoRoot = repoRoot.trim();

            // 勾选的已跟踪文件：相对 HEAD 的完整 diff（含已暂存与未暂存部分）
            StringBuilder diff = new StringBuilder();
            if (!files.isEmpty()) {
                List<String> args = new ArrayList<>(Arrays.asList("diff", "HEAD", "--no-color", "--no-ext-diff", "--"));
                for (File file : files) {
                    args.add(file.getAbsolutePath());
                }
                String trackedDiff = runGit(repoRoot, args);
                if (trackedDiff != null) {
                    diff.append(trackedDiff);
                }
            }
            // 勾选的未跟踪新文件：git diff HEAD 不含它们，用 --no-index 生成全文件 diff（有差异时退出码 1 属正常）
            for (File file : unversionedFiles) {
                String newFileDiff = runGit(repoRoot, Arrays.asList(
                        "diff", "--no-index", "--no-color", "--no-ext-diff", "--", "/dev/null", file.getAbsolutePath()), true);
                if (newFileDiff != null && !newFileDiff.trim().isEmpty()) {
                    diff.append(newFileDiff).append('\n');
                }
            }
            if (diff.toString().trim().isEmpty()) {
                notify(project, "AI Commit", "勾选的文件没有内容变更，无法生成提交信息", NotificationType.WARNING);
                return;
            }
            String diffText = diff.toString();
            if (diffText.length() > MAX_DIFF_LENGTH) {
                diffText = diffText.substring(0, MAX_DIFF_LENGTH);
            }

            indicator.setText("调用 claude 生成提交信息...");
            String prompt = buildPrompt(diffText);
            String result = runClaude(prompt);
            String message = cleanMessage(result);
            if (message.isEmpty()) {
                notify(project, "AI Commit", "claude 返回内容为空，生成失败", NotificationType.ERROR);
                return;
            }

            final String finalMessage = message;
            ApplicationManager.getApplication().invokeLater(() ->
                    WriteCommandAction.runWriteCommandAction(project, () -> {
                        document.setText(finalMessage);
                        notify(project, "AI Commit", "提交信息已生成", NotificationType.INFORMATION);
                    }));
        } catch (Exception ex) {
            LOG.warn("AI 生成提交信息失败", ex);
            notify(project, "AI Commit", "生成失败：" + ex.getMessage(), NotificationType.ERROR);
        }
    }

    /**
     * 组装生成命令。用户在设置页填了自定义命令则直接使用；
     * 否则使用自动探测到的 claude 二进制。
     */
    @NotNull
    private List<String> buildClaudeCommand() {
        String configured = PropertiesComponent.getInstance().getValue(AiCommitSettings.COMMAND_KEY);
        List<String> cmd = new ArrayList<>();
        if (configured != null && !configured.trim().isEmpty() && !"claude".equals(configured.trim())) {
            cmd.addAll(Arrays.asList(configured.trim().split("\\s+")));
        } else {
            cmd.add(detectClaudeBinary());
        }
        cmd.add("-p");
        return cmd;
    }

    /** 按优先级探测 claude 可执行文件：nvm（取最新 node 版本）> bun > local > /usr/local/bin > PATH */
    @NotNull
    private String detectClaudeBinary() {
        String home = System.getProperty("user.home");
        File best = null;
        String bestVersion = "";
        File[] nodes = new File(home, ".nvm/versions/node").listFiles();
        if (nodes != null) {
            for (File node : nodes) {
                File claude = new File(node, "bin/claude");
                if (claude.canExecute() && compareVersions(node.getName(), bestVersion) > 0) {
                    best = claude;
                    bestVersion = node.getName();
                }
            }
        }
        if (best != null) {
            return best.getAbsolutePath();
        }
        for (String p : new String[]{".bun/bin/claude", ".local/bin/claude"}) {
            File f = new File(home, p);
            if (f.canExecute()) {
                return f.getAbsolutePath();
            }
        }
        File global = new File("/usr/local/bin/claude");
        if (global.canExecute()) {
            return global.getAbsolutePath();
        }
        return "claude";
    }

    /** 比较形如 v22.19.0 的版本号，返回正数表示 a 更新 */
    private int compareVersions(@NotNull String a, @NotNull String b) {
        if (a.isEmpty()) {
            return b.isEmpty() ? 0 : -1;
        }
        String[] pa = a.replaceFirst("^v", "").split("\\.");
        String[] pb = b.replaceFirst("^v", "").split("\\.");
        for (int i = 0; i < Math.max(pa.length, pb.length); i++) {
            int va = i < pa.length ? parseIntSafe(pa[i]) : 0;
            int vb = i < pb.length ? parseIntSafe(pb[i]) : 0;
            if (va != vb) {
                return Integer.compare(va, vb);
            }
        }
        return 0;
    }

    private int parseIntSafe(@NotNull String s) {
        try {
            return Integer.parseInt(s.replaceAll("\\D.*", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    @NotNull
    private String buildPrompt(@NotNull String diff) {
        return "你是 git 提交信息生成器。根据下面的 diff 生成一条符合 Conventional Commits 规范的中文提交信息。\n"
                + "要求：\n"
                + "1. 格式为「type: 简短中文描述」，type 从 feat/fix/chore/docs/refactor/test/style 中选择\n"
                + "2. 描述控制在 50 字以内，概括本次变更的核心目的\n"
                + "3. 变更复杂时可在空行后附加 1-3 句中文正文说明关键改动\n"
                + "4. 只输出提交信息本身，不要任何解释、前后缀或代码块标记\n\n"
                + "diff 如下：\n" + diff;
    }

    @NotNull
    private String runClaude(@NotNull String prompt) throws Exception {
        List<String> cmd = buildClaudeCommand();

        Path promptFile = null;
        try {
            // prompt 可能达到 1MB，超出 ARG_MAX，改走临时文件重定向 stdin
            promptFile = Files.createTempFile("ai-commit-prompt", ".txt");
            Files.write(promptFile, prompt.getBytes(StandardCharsets.UTF_8));

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectInput(promptFile.toFile());
            pb.redirectErrorStream(false);
            // IDE 进程 PATH 通常只有 /usr/bin:/bin:/usr/sbin:/sbin，
            // 这里注入探测到的 claude 目录，保证子进程能找到可执行文件
            String pathEnv = pb.environment().getOrDefault("PATH", "/usr/bin:/bin:/usr/sbin:/sbin");
            String claudeDir = new File(detectClaudeBinary()).getParent();
            pb.environment().put("PATH", claudeDir + ":/usr/local/bin:" + pathEnv);
            Process process = pb.start();

            String stdout = readStream(process.getInputStream());
            String stderr = readStream(process.getErrorStream());
            if (!process.waitFor(CLAUDE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("claude 超时（" + CLAUDE_TIMEOUT_SECONDS + "秒），stderr: " + tail(stderr));
            }
            if (process.exitValue() != 0) {
                LOG.warn("claude 退出码 " + process.exitValue() + ", stdout: " + tail(stdout) + ", stderr: " + tail(stderr));
                throw new IllegalStateException("claude 退出码 " + process.exitValue() + "，输出: "
                        + tail(stdout.isEmpty() ? stderr : stdout));
            }
            if (stdout.trim().isEmpty()) {
                LOG.warn("生成命令空输出, stderr: " + tail(stderr));
                throw new IllegalStateException("生成命令无输出，请确认命令已配置且可用，stderr: " + tail(stderr));
            }
            return stdout;
        } finally {
            if (promptFile != null) {
                Files.deleteIfExists(promptFile);
            }
        }
    }

    @NotNull
    private String tail(@NotNull String s) {
        String t = s.trim();
        return t.length() > 300 ? "..." + t.substring(t.length() - 300) : t;
    }

    @NotNull
    private String cleanMessage(@NotNull String raw) {
        String message = raw.trim();
        // 去掉可能的代码块包裹
        if (message.startsWith("```")) {
            int firstLineEnd = message.indexOf('\n');
            if (firstLineEnd > 0) {
                message = message.substring(firstLineEnd + 1);
            }
            int fenceIndex = message.lastIndexOf("```");
            if (fenceIndex >= 0) {
                message = message.substring(0, fenceIndex);
            }
        }
        return message.trim();
    }

    @Nullable
    private String runGit(@NotNull String workDir, @NotNull List<String> args) {
        return runGit(workDir, args, false);
    }

    /** @param rawExit true 时不按退出码判失败（用于 --no-index：有差异退出码 1 属正常），仅超时/异常返回 null */
    @Nullable
    private String runGit(@NotNull String workDir, @NotNull List<String> args, boolean rawExit) {
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add("git");
            cmd.addAll(args);
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(new File(workDir));
            pb.redirectErrorStream(false);
            Process process = pb.start();
            String stdout = readStream(process.getInputStream());
            String stderr = readStream(process.getErrorStream());
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            if (!rawExit && process.exitValue() != 0) {
                LOG.warn("git 执行失败: " + String.join(" ", args) + ", stderr: " + stderr);
                return null;
            }
            return stdout;
        } catch (Exception ex) {
            LOG.warn("git 执行异常", ex);
            return null;
        }
    }

    @NotNull
    private String readStream(@NotNull InputStream in) throws Exception {
        byte[] buffer = new byte[8192];
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int n;
        while ((n = in.read(buffer)) != -1) {
            out.write(buffer, 0, n);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private void notify(@NotNull Project project, @NotNull String title, @NotNull String content,
                        @NotNull NotificationType type) {
        com.intellij.notification.NotificationGroupManager.getInstance()
                .getNotificationGroup("AI Commit")
                .createNotification(title, content, type)
                .notify(project);
    }
}
