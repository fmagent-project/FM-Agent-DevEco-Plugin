package com.fmagent.deveco;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.editor.Editor;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

final class FmAgentPanel extends JPanel {
    private static final String DEFAULT_FM_AGENT_HOME =
            "/Users/lianganran/codes/2_SJTU_code/FM-agent/FM-Agent-Internal";
    private static final String FM_AGENT_HOME_KEY = "com.fmagent.deveco.fmAgentHome";

    private final Project project;
    private final JTextField fmAgentHome;
    private final JTextField opencodeTimeoutSeconds;
    private final JCheckBox resume;
    private final JCheckBox isolate;
    private final JTextArea monitorOutput;
    private final JTextArea resultOutput;
    private final JPanel monitorPanel;
    private final JPanel resultPanel;
    private final JButton installButton;
    private final JButton checkEnvironmentButton;
    private final JButton verifyProjectButton;
    private final JButton verifySelectionButton;
    private final JButton getResultsButton;
    private final JButton stopButton;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private volatile Process currentProcess;
    private volatile Path lastTargetPath;
    private Runnable showMonitorAction = () -> {
    };
    private Runnable showVerifyResultAction = () -> {
    };

    FmAgentPanel(Project project) {
        super(new BorderLayout(8, 8));
        this.project = project;

        setBorder(new EmptyBorder(12, 12, 12, 12));

        PropertiesComponent properties = PropertiesComponent.getInstance(project);
        fmAgentHome = new JTextField(properties.getValue(FM_AGENT_HOME_KEY, DEFAULT_FM_AGENT_HOME), 45);
        opencodeTimeoutSeconds = new JTextField(properties.getValue("com.fmagent.deveco.opencodeTimeoutSeconds", "300"), 8);
        resume = new JCheckBox("Resume", true);
        isolate = new JCheckBox("Isolate", false);
        monitorOutput = createOutputArea(ProjectSummary.from(project).asText());
        resultOutput = createOutputArea("No verification result loaded." + System.lineSeparator());
        monitorPanel = buildMonitorPanel();
        resultPanel = buildVerifyResultPanel();

        installButton = new JButton("Install FM-Agent");
        checkEnvironmentButton = new JButton("Check Environment");
        verifyProjectButton = new JButton("Verify Project");
        verifySelectionButton = new JButton("Verify Selection");
        getResultsButton = new JButton("Get Results");
        stopButton = new JButton("Stop");
        stopButton.setEnabled(false);

        add(buildMainPanel(properties), BorderLayout.CENTER);

        Disposer.register(project, () -> {
            stopCurrentProcess();
            executor.shutdownNow();
        });

        installButton.addActionListener(event -> installFmAgent());
        checkEnvironmentButton.addActionListener(event -> checkEnvironment());
        verifyProjectButton.addActionListener(event -> verifyProject());
        verifySelectionButton.addActionListener(event -> verifySelection());
        getResultsButton.addActionListener(event -> refreshResults());
        stopButton.addActionListener(event -> stopCurrentProcess());
    }

    JComponent monitorComponent() {
        return monitorPanel;
    }

    JComponent verifyResultComponent() {
        return resultPanel;
    }

    void setNavigationActions(Runnable showMonitorAction, Runnable showVerifyResultAction) {
        this.showMonitorAction = showMonitorAction;
        this.showVerifyResultAction = showVerifyResultAction;
    }

    private JTextArea createOutputArea(String initialText) {
        JTextArea area = new JTextArea(initialText);
        area.setEditable(false);
        area.setLineWrap(false);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        return area;
    }

    private JPanel buildMainPanel(PropertiesComponent properties) {
        JPanel panel = new JPanel(new BorderLayout(0, 12));
        panel.add(buildSettingsPanel(properties), BorderLayout.NORTH);
        panel.add(buildActionPanel(), BorderLayout.CENTER);
        return panel;
    }

    private JPanel buildSettingsPanel(PropertiesComponent properties) {
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

        constraints.gridx = 0;
        constraints.gridy = 1;
        panel.add(new JLabel("OpenCode timeout(s)"), constraints);

        constraints.gridx = 1;
        constraints.weightx = 0;
        constraints.fill = GridBagConstraints.NONE;
        panel.add(opencodeTimeoutSeconds, constraints);

        return panel;
    }

    private JPanel buildActionPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.add(buildInstallGroup());
        panel.add(Box.createVerticalStrut(8));
        panel.add(buildVerifyGroup());
        panel.add(Box.createVerticalStrut(8));
        panel.add(buildResultsGroup());
        panel.add(Box.createVerticalGlue());
        return panel;
    }

    private JPanel buildInstallGroup() {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        row.add(installButton);
        row.add(checkEnvironmentButton);
        return buildActionGroup("1. Install and Environment", row);
    }

    private JPanel buildVerifyGroup() {
        JPanel content = new JPanel(new GridBagLayout());
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = 0;
        constraints.gridy = 0;
        constraints.anchor = GridBagConstraints.WEST;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.weightx = 1;

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        buttons.add(verifyProjectButton);
        buttons.add(verifySelectionButton);
        buttons.add(stopButton);
        content.add(buttons, constraints);

        constraints.gridy = 1;
        JPanel options = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        options.add(new JLabel("Options"));
        options.add(resume);
        options.add(isolate);
        content.add(options, constraints);

        return buildActionGroup("2. Verify Code", content);
    }

    private JPanel buildResultsGroup() {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        row.add(getResultsButton);
        return buildActionGroup("3. Get Results", row);
    }

    private JPanel buildActionGroup(String title, JComponent content) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createTitledBorder(title));
        panel.add(content, BorderLayout.CENTER);
        panel.setAlignmentX(LEFT_ALIGNMENT);
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, panel.getPreferredSize().height));
        return panel;
    }

    private JPanel buildMonitorPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(new EmptyBorder(12, 12, 12, 12));
        panel.add(new JScrollPane(monitorOutput), BorderLayout.CENTER);
        return panel;
    }

    private JPanel buildVerifyResultPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(new EmptyBorder(12, 12, 12, 12));
        panel.add(new JScrollPane(resultOutput), BorderLayout.CENTER);
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
        runProcess(home, home, "Installing FM-Agent", FmAgentCommands.installCommand(), false, false);
    }

    private void checkEnvironment() {
        Path home = requireFmAgentHome();
        Path projectPath = projectPath();
        if (home == null || projectPath == null) {
            return;
        }
        lastTargetPath = projectPath;
        runProcess(home, projectPath, "Checking FM-Agent/OpenCode environment",
                FmAgentCommands.environmentCheckCommand(projectPath, timeoutSeconds()), false);
    }

    private void verifyProject() {
        Path home = requireFmAgentHome();
        Path projectPath = projectPath();
        if (home == null || projectPath == null) {
            return;
        }
        lastTargetPath = projectPath;
        runProcess(home, projectPath, "Verifying project",
                FmAgentCommands.verifyCommand(projectPath, resume.isSelected(), isolate.isSelected(), timeoutSeconds()));
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
            runProcess(home, selectionRepo, "Verifying selection",
                    FmAgentCommands.verifyCommand(selectionRepo, false, false, timeoutSeconds()));
        } catch (IOException exception) {
            appendLine("Failed to prepare selection project: " + exception.getMessage());
        }
    }

    private void refreshResults() {
        Path target = lastTargetPath != null ? lastTargetPath : projectPath();
        if (target == null) {
            return;
        }
        setResultText(FmAgentResults.render(target));
        showVerifyResult();
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

    private String timeoutSeconds() {
        String timeout = opencodeTimeoutSeconds.getText().trim();
        PropertiesComponent.getInstance(project).setValue("com.fmagent.deveco.opencodeTimeoutSeconds", timeout);
        return timeout;
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
        runProcess(fmAgentHomePath, targetPath, title, command, true, true);
    }

    private void runProcess(Path fmAgentHomePath, Path targetPath, String title, String command, boolean monitorFmAgentTrace) {
        runProcess(fmAgentHomePath, targetPath, title, command, monitorFmAgentTrace, false);
    }

    private void runProcess(Path fmAgentHomePath, Path targetPath, String title, String command,
                            boolean monitorFmAgentTrace, boolean updateResultsOnSuccess) {
        showMonitor();
        setRunning(true);
        appendLine("");
        appendLine("=== " + title + " ===");
        appendLine("FM-Agent: " + fmAgentHomePath);
        appendLine("Target: " + targetPath);
        appendLine("Command: " + command);
        append(FmAgentEnvironment.describe(fmAgentHomePath, timeoutSeconds()));

        executor.submit(() -> {
            int exitCode = -1;
            FmAgentLogMonitor monitor = new FmAgentLogMonitor(targetPath);
            try {
                ProcessBuilder builder = new ProcessBuilder("/bin/bash", "-lc", command);
                builder.directory(fmAgentHomePath.toFile());
                builder.redirectErrorStream(true);
                currentProcess = builder.start();
                closeProcessInput(currentProcess);

                Thread outputThread = new Thread(() -> copyProcessOutput(currentProcess), "fm-agent-output-reader");
                outputThread.setDaemon(true);
                outputThread.start();

                while (currentProcess.isAlive()) {
                    if (monitorFmAgentTrace) {
                        append(monitor.poll(targetPath, currentProcess));
                    }
                    currentProcess.waitFor(5, TimeUnit.SECONDS);
                }

                exitCode = currentProcess.waitFor();
                outputThread.join(Duration.ofSeconds(2).toMillis());
                if (monitorFmAgentTrace) {
                    append(monitor.poll(targetPath, currentProcess));
                }
                appendLine("Process exited with code " + exitCode);
            } catch (IOException exception) {
                appendLine("Process failed: " + exception.getMessage());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                appendLine("Process interrupted.");
            } finally {
                currentProcess = null;
                setRunning(false);
                if (exitCode == 0 && updateResultsOnSuccess) {
                    setResultText(FmAgentResults.render(targetPath));
                    appendLine("Verify Result updated for " + targetPath);
                }
            }
        });
    }

    private void closeProcessInput(Process process) {
        try {
            // OpenCode treats non-TTY stdin as input and waits for EOF; the plugin never writes to it.
            process.getOutputStream().close();
        } catch (IOException exception) {
            appendLine("Could not close process input: " + exception.getMessage());
        }
    }

    private void copyProcessOutput(Process process) {
        try (var reader = process.inputReader(StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                appendLine(line);
            }
        } catch (IOException exception) {
            appendLine("Could not read process output: " + exception.getMessage());
        }
    }

    private void stopCurrentProcess() {
        Process process = currentProcess;
        if (process != null) {
            process.toHandle().descendants()
                    .sorted(Comparator.comparingLong(ProcessHandle::pid).reversed())
                    .forEach(ProcessHandle::destroy);
            process.destroy();
            appendLine("Stop requested.");
        }
    }

    private void setRunning(boolean running) {
        SwingUtilities.invokeLater(() -> {
            installButton.setEnabled(!running);
            checkEnvironmentButton.setEnabled(!running);
            verifyProjectButton.setEnabled(!running);
            verifySelectionButton.setEnabled(!running);
            getResultsButton.setEnabled(true);
            stopButton.setEnabled(running);
        });
    }

    private void append(String text) {
        SwingUtilities.invokeLater(() -> {
            monitorOutput.append(text);
            monitorOutput.setCaretPosition(monitorOutput.getDocument().getLength());
        });
    }

    private void appendLine(String line) {
        append(line + System.lineSeparator());
    }

    private void setResultText(String text) {
        SwingUtilities.invokeLater(() -> {
            resultOutput.setText(text);
            resultOutput.setCaretPosition(0);
        });
    }

    private void showMonitor() {
        SwingUtilities.invokeLater(showMonitorAction);
    }

    private void showVerifyResult() {
        SwingUtilities.invokeLater(showVerifyResultAction);
    }
}
