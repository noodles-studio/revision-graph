package io.github.noodles_studio.revisiongraph.ui;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import io.github.noodles_studio.revisiongraph.RevisionGraphBundle;
import org.jetbrains.annotations.NotNull;

public final class RevisionGraphToolWindowFactory implements ToolWindowFactory, DumbAware {
    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        String title = RevisionGraphBundle.INSTANCE.message("toolwindow.title");
        toolWindow.setStripeTitleProvider(() -> title);
        toolWindow.setTitle(title);

        RevisionGraphView view = new RevisionGraphView(project);
        Content content = toolWindow.getContentManager().getFactory().createContent(view.getComponent(), "", false);
        content.setDisposer(view);
        toolWindow.getContentManager().addContent(content);
    }
}
