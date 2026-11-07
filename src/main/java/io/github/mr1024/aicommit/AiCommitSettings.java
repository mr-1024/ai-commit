package io.github.mr1024.aicommit;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.options.Configurable;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;

/**
 * 设置页：可自定义生成命令，默认使用 claude。
 * 需要覆盖时填完整命令，如 "/usr/local/bin/claude -p" 或任何能接收 stdin diff 的本地 CLI。
 */
public class AiCommitSettings implements Configurable {

    static final String COMMAND_KEY = "aiCommit.command";
    private static final String DEFAULT_COMMAND = "claude";

    private JTextField myCommandField;

    @Override
    public @Nls(capitalization = Nls.Capitalization.Title) String getDisplayName() {
        return "AI Commit Message";
    }

    @Override
    public @Nullable JComponent createComponent() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        JPanel row = new JPanel();
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.add(new JLabel("生成命令:"));
        row.add(Box.createHorizontalStrut(8));
        myCommandField = new JTextField(getCurrentCommand(), 40);
        row.add(myCommandField);
        panel.add(row);
        panel.add(Box.createVerticalStrut(4));
        panel.add(new JLabel("默认使用 claude；需要覆盖时填完整命令，如 /usr/local/bin/claude -p 或其他本地 CLI"));
        return panel;
    }

    static String getCurrentCommand() {
        return PropertiesComponent.getInstance().getValue(COMMAND_KEY, DEFAULT_COMMAND);
    }

    @Override
    public boolean isModified() {
        return !getCurrentCommand().equals(myCommandField.getText().trim());
    }

    @Override
    public void apply() {
        PropertiesComponent.getInstance().setValue(COMMAND_KEY, myCommandField.getText().trim(), DEFAULT_COMMAND);
    }

    @Override
    public void reset() {
        myCommandField.setText(getCurrentCommand());
    }

    @Override
    public void disposeUIResources() {
        myCommandField = null;
    }
}
