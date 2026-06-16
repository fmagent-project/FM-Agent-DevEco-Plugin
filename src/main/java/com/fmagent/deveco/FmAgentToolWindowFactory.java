package com.fmagent.deveco;

import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.Font;

public final class FmAgentToolWindowFactory implements ToolWindowFactory, DumbAware {
    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(new EmptyBorder(12, 12, 12, 12));

        JLabel title = new JLabel("FM Agent");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 16f));

        JTextArea output = new JTextArea(ProjectSummary.from(project).asText());
        output.setEditable(false);
        output.setLineWrap(false);
        output.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

        JButton refresh = new JButton("Refresh");
        refresh.addActionListener(event -> output.setText(ProjectSummary.from(project).asText()));

        panel.add(title, BorderLayout.NORTH);
        panel.add(new JScrollPane(output), BorderLayout.CENTER);
        panel.add(refresh, BorderLayout.SOUTH);

        Content content = ContentFactory.getInstance().createContent(panel, "Project", false);
        toolWindow.getContentManager().addContent(content);
    }
}
