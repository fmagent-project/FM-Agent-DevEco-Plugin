package com.fmagent.deveco;

import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

public final class FmAgentToolWindowFactory implements ToolWindowFactory, DumbAware {
    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        FmAgentPanel panel = new FmAgentPanel(project);
        ContentFactory contentFactory = ContentFactory.getInstance();

        Content mainContent = contentFactory.createContent(panel, "Main", false);
        Content monitorContent = contentFactory.createContent(panel.monitorComponent(), "Monitor", false);
        Content verifyResultContent = contentFactory.createContent(panel.verifyResultComponent(), "Verify Result", false);

        panel.setNavigationActions(
                () -> toolWindow.getContentManager().setSelectedContent(monitorContent),
                () -> toolWindow.getContentManager().setSelectedContent(verifyResultContent));

        toolWindow.getContentManager().addContent(mainContent);
        toolWindow.getContentManager().addContent(monitorContent);
        toolWindow.getContentManager().addContent(verifyResultContent);
        toolWindow.getContentManager().setSelectedContent(mainContent);
    }
}
