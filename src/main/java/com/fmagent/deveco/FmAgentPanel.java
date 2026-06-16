package com.fmagent.deveco;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.editor.Editor;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class FmAgentPanel extends JPanel {
    private static final String DEFAULT_FM_AGENT_HOME =
            "/Users/lianganran/codes/2_SJTU_code/FM-agent/FM-Agent-Internal";
    private static final String FM_AGENT_HOME_KEY = "com.fmagent.deveco.fmAgentHome";

    private final Project project;
    private final JTextField fmAgentHome;
    private final JCheckBox resume;
    private final JCheckBox isolate;
    private final JTextArea output;
    private final JButton installButton;
    private final JButton verifyProjectButton;
    private final JButton verifySelectionButton;
    private final JButton refreshButton;
    private final JButton stopButton;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private volatile Process currentProcess;
    private volatile Path lastTargetPath;

    FmAgentPanel(Project project) {
        super(new BorderLayout(8, 8));
        this.project = project;

        setBorder(new EmptyBorder(12, 12, 12, 12));

        PropertiesComponent properties = PropertiesComponent.getInstance(project);
        fmAgentHome = new JTextField(properties.getValue(FM_AGENT_HOME_KEY, DEFAULT_FM_AGENT_HOME), 45);
        resume = new JCheckBox("Resume", true);
        isolate = new JCheckBox("Isolate", false);
        output = new JTextArea(ProjectSummary.from(project).asText());
        output.setEditable(false);
        output.setLineWrap(false);
        output.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

        installButton = new JButton("Install FM-Agent");
        verifyProjectButton = new JButton("Verify Project");
        verifySelectionButton = new JButton("Verify Selection");
        refreshButton = new JButton("Refresh Results");
        stopButton = new JButton("Stop");
        stopButton.setEnabled(false);

        add(buildTopPanel(properties), BorderLayout.NORTH);
        add(new JScrollPane(output), BorderLayout.CENTER);

        Disposer.register(project, () -> {
            stopCurrentProcess();
            executor.shutdownNow();
        });

        installButton.addActionListener(event -> installFmAgent());
        verifyProjectButton.addActionListener(event -> verifyProject());
        verifySelectionButton.addActionListener(event -> verifySelection());
        refreshButton.addActionListener(event -> refreshResults());
        stopButton.addActionListener(event -> stopCurrentProcess());
    }

    private JPanel buildTopPanel(PropertiesComponent properties) {
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.insets = new Insets(0, 0, 8, 8);
        constraints.anchor = GridBagConstraints.WEST;

        constraints.gridx = 0;
        constraints.gridy = 0;
        panel.add(new JLabel("FM-Agent path"), constraints);

        constraints.gridx = 1;
        constraints.weightx = 1;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        panel.add(fmAgentHome, constraints);

        JButton browse = new JButton("Browse");
        browse.addActionListener(event -> chooseFmAgentHome(properties));
        constraints.gridx = 2;
        constraints.weightx = 0;
        constraints.fill = GridBagConstraints.NONE;
        panel.add(browse, constraints);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        buttons.add(installButton);
        buttons.add(verifyProjectButton);
        buttons.add(verifySelectionButton);
        buttons.add(refreshButton);
        buttons.add(stopButton);
        buttons.add(resume);
        buttons.add(isolate);

        constraints.gridx = 0;
        constraints.gridy = 1;
        constraints.gridwidth = 3;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        panel.add(buttons, constraints);

        return panel;
    }

    private void chooseFmAgentHome(PropertiesComponent properties) {
        JFileChooser chooser = new JFileChooser(fmAgentHome.getText().trim());
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setDialogTitle("Select FM-Agent-Internal directory");
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            String selected = chooser.getSelectedFile().getAbsolutePath();
            fmAgentHome.setText(selected);
            properties.setValue(FM_AGENT_HOME_KEY, selected);
        }
    }

    private void installFmAgent() {
        Path home = requireFmAgentHome();
        if (home == null) {
            return;
        }
        runProcess(home, home, "Installing FM-Agent", FmAgentCommands.installCommand());
    }

    private void verifyProject() {
        Path home = requireFmAgentHome();
        Path projectPath = projectPath();
        if (home == null || projectPath == null) {
            return;
        }
        lastTargetPath = projectPath;
        runProcess(home, projectPath, "Verifying project", FmAgentCommands.verifyCommand(projectPath, resume.isSelected(), isolate.isSelected()));
    }

    private void verifySelection() {
        Path home = requireFmAgentHome();
        if (home == null) {
            return;
        }

        Editor editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
        if (editor == null || !editor.getSelectionModel().hasSelection()) {
            Messages.showWarningDialog(project, "Select code in the editor before running selection verification.", "FM Agent");
            return;
        }

        String selectedText = editor.getSelectionModel().getSelectedText();
        if (selectedText == null || selectedText.trim().isEmpty()) {
            Messages.showWarningDialog(project, "The current selection is empty.", "FM Agent");
            return;
        }

        VirtualFile file = FileEditorManager.getInstance(project).getSelectedFiles().length == 0
                ? null
                : FileEditorManager.getInstance(project).getSelectedFiles()[0];

        try {
            Path selectionRepo = FmAgentSelectionProject.create(selectedText, file);
            lastTargetPath = selectionRepo;
            runProcess(home, selectionRepo, "Verifying selection", FmAgentCommands.verifyCommand(selectionRepo, false, false));
        } catch (IOException exception) {
            appendLine("Failed to prepare selection project: " + exception.getMessage());
        }
    }

    private void refreshResults() {
        Path target = lastTargetPath != null ? lastTargetPath : projectPath();
        if (target == null) {
            return;
        }
        appendLine("");
        appendLine("=== FM-Agent results: " + target + " ===");
        append(FmAgentResults.render(target));
    }

    private Path requireFmAgentHome() {
        String path = fmAgentHome.getText().trim();
        PropertiesComponent.getInstance(project).setValue(FM_AGENT_HOME_KEY, path);
        if (path.isEmpty()) {
            Messages.showWarningDialog(project, "Set the FM-Agent path first.", "FM Agent");
            return null;
        }

        Path home = Path.of(path);
        if (!Files.isDirectory(home)) {
            Messages.showWarningDialog(project, "FM-Agent path does not exist: " + home, "FM Agent");
            return null;
        }
        if (!Files.isRegularFile(home.resolve("main.py"))) {
            Messages.showWarningDialog(project, "FM-Agent path must contain main.py: " + home, "FM Agent");
            return null;
        }
        return home;
    }

    private Path projectPath() {
        String basePath = project.getBasePath();
        if (basePath == null || basePath.isBlank()) {
            Messages.showWarningDialog(project, "Open a project before running FM-Agent.", "FM Agent");
            return null;
        }
        return Path.of(basePath);
    }

    private void runProcess(Path fmAgentHomePath, Path targetPath, String title, String command) {
        setRunning(true);
        appendLine("");
        appendLine("=== " + title + " ===");
        appendLine("FM-Agent: " + fmAgentHomePath);
        appendLine("Target: " + targetPath);
        appendLine("Command: " + command);

        executor.submit(() -> {
            int exitCode = -1;
            try {
                ProcessBuilder builder = new ProcessBuilder("/bin/bash", "-lc", command);
                builder.directory(fmAgentHomePath.toFile());
                builder.redirectErrorStream(true);
                currentProcess = builder.start();

                try (var reader = currentProcess.inputReader(StandardCharsets.UTF_8)) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        appendLine(line);
                    }
                }

                exitCode = currentProcess.waitFor();
                appendLine("Process exited with code " + exitCode);
            } catch (IOException exception) {
                appendLine("Process failed: " + exception.getMessage());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                appendLine("Process interrupted.");
            } finally {
                currentProcess = null;
                setRunning(false);
                if (exitCode == 0) {
                    append(FmAgentResults.render(targetPath));
                }
            }
        });
    }

    private void stopCurrentProcess() {
        Process process = currentProcess;
        if (process != null) {
            process.destroy();
            appendLine("Stop requested.");
        }
    }

    private void setRunning(boolean running) {
        SwingUtilities.invokeLater(() -> {
            installButton.setEnabled(!running);
            verifyProjectButton.setEnabled(!running);
            verifySelectionButton.setEnabled(!running);
            refreshButton.setEnabled(!running);
            stopButton.setEnabled(running);
        });
    }

    private void append(String text) {
        SwingUtilities.invokeLater(() -> {
            output.append(text);
            output.setCaretPosition(output.getDocument().getLength());
        });
    }

    private void appendLine(String line) {
        append(line + System.lineSeparator());
    }
}
