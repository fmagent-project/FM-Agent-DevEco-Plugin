package com.fmagent.deveco;

import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import com.intellij.ui.content.ContentManagerEvent;
import com.intellij.ui.content.ContentManagerListener;
import org.jetbrains.annotations.NotNull;

public final class FmAgentToolWindowFactory implements ToolWindowFactory, DumbAware {
    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        FmAgentPanel panel = new FmAgentPanel(project);
        ContentFactory contentFactory = ContentFactory.getInstance();

        Content mainContent = contentFactory.createContent(panel, "Main", false);
        Content monitorContent = contentFactory.createContent(panel.monitorComponent(), "Monitor", false);
        Content reasoningResultContent = contentFactory.createContent(panel.reasoningResultComponent(), "Reasoning Result", false);

        panel.setNavigationActions(
                () -> toolWindow.getContentManager().setSelectedContent(monitorContent),
                () -> toolWindow.getContentManager().setSelectedContent(reasoningResultContent));

        toolWindow.getContentManager().addContentManagerListener(new ContentManagerListener() {
            @Override
            public void selectionChanged(@NotNull ContentManagerEvent event) {
                if (event.getContent() == reasoningResultContent) {
                    panel.refreshResultsSilently();
                }
            }
        });

        toolWindow.getContentManager().addContent(mainContent);
        toolWindow.getContentManager().addContent(monitorContent);
        toolWindow.getContentManager().addContent(reasoningResultContent);
        toolWindow.getContentManager().setSelectedContent(mainContent);
    }
}
