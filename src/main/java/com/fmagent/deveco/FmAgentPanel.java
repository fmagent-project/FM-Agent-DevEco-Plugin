package com.fmagent.deveco;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JEditorPane;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.event.HyperlinkEvent;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

final class FmAgentPanel extends JPanel {
    private static final String DEFAULT_FM_AGENT_HOME = "~/FM-agent";
    private static final String FM_AGENT_HOME_KEY = "com.fmagent.deveco.fmAgentHome";

    private final Project project;
    private final JTextField fmAgentHome;
    private final JTextField opencodeTimeoutSeconds;
    private final JCheckBox resume;
    private final JCheckBox isolate;
    private final JTextArea monitorOutput;
    private final JEditorPane resultOutput;
    private final JPanel monitorPanel;
    private final JPanel resultPanel;
    private final JButton installButton;
    private final JButton checkEnvironmentButton;
    private final JButton verifyProjectButton;
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
        resultOutput = createResultPane();
        monitorPanel = buildMonitorPanel();
        resultPanel = buildVerifyResultPanel();

        installButton = new JButton("Install FM-Agent");
        checkEnvironmentButton = new JButton("Check Environment");
        verifyProjectButton = new JButton("Verify Project");
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

    private JEditorPane createResultPane() {
        JEditorPane pane = new JEditorPane("text/html", FmAgentResults.emptyHtml());
        pane.setEditable(false);
        pane.setOpaque(false);
        pane.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
        pane.addHyperlinkListener(event -> {
            if (event.getEventType() == HyperlinkEvent.EventType.ACTIVATED) {
                openResultLink(event);
            }
        });
        return pane;
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
        JFileChooser chooser = new JFileChooser(expandUserHome(fmAgentHome.getText().trim()).toFile());
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setDialogTitle("Select FM-Agent directory");
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            String selected = chooser.getSelectedFile().getAbsolutePath();
            fmAgentHome.setText(selected);
            properties.setValue(FM_AGENT_HOME_KEY, selected);
        }
    }

    private void installFmAgent() {
        Path home = fmAgentHomePath();
        Path projectPath = projectPath();
        if (home == null || projectPath == null) {
            return;
        }

        boolean cloneRepository = !Files.exists(home);
        Path workingDirectory = home;
        if (cloneRepository) {
            Path parent = home.getParent();
            if (parent == null) {
                Messages.showWarningDialog(project, "FM-Agent path must have a parent directory: " + home, "FM Agent");
                return;
            }
            try {
                Files.createDirectories(parent);
            } catch (IOException exception) {
                Messages.showWarningDialog(project,
                        "Could not create FM-Agent parent directory: " + parent + System.lineSeparator() + exception.getMessage(),
                        "FM Agent");
                return;
            }
            workingDirectory = parent;
        } else if (!validateExistingFmAgentHome(home)) {
            return;
        }

        runProcess(home, projectPath, workingDirectory, "Installing FM-Agent",
                FmAgentCommands.installCommand(home, projectPath, cloneRepository), false, false);
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
        if (!ensureGitRepository(projectPath)) {
            return;
        }
        lastTargetPath = projectPath;
        runProcess(home, projectPath, "Verifying project",
                FmAgentCommands.verifyCommand(projectPath, resume.isSelected(), isolate.isSelected(), timeoutSeconds()));
    }

    private void refreshResults() {
        refreshResults(true);
    }

    void refreshResultsSilently() {
        refreshResults(false);
    }

    private void refreshResults(boolean showResult) {
        Path target = resultTarget(showResult);
        if (target == null) {
            return;
        }
        updateResultViews(target);
        if (showResult) {
            showVerifyResult();
        }
    }

    private Path requireFmAgentHome() {
        Path home = fmAgentHomePath();
        if (home == null) {
            return null;
        }
        if (!validateExistingFmAgentHome(home)) {
            return null;
        }
        return home;
    }

    private Path fmAgentHomePath() {
        String path = fmAgentHome.getText().trim();
        PropertiesComponent.getInstance(project).setValue(FM_AGENT_HOME_KEY, path);
        if (path.isEmpty()) {
            Messages.showWarningDialog(project, "Set the FM-Agent path first.", "FM Agent");
            return null;
        }
        return expandUserHome(path).toAbsolutePath().normalize();
    }

    private Path expandUserHome(String path) {
        if (path.equals("~")) {
            return Path.of(System.getProperty("user.home"));
        }
        if (path.startsWith("~/")) {
            return Path.of(System.getProperty("user.home")).resolve(path.substring(2));
        }
        return Path.of(path);
    }

    private boolean validateExistingFmAgentHome(Path home) {
        if (!Files.isDirectory(home)) {
            Messages.showWarningDialog(project, "FM-Agent path does not exist: " + home, "FM Agent");
            return false;
        }
        if (!Files.isRegularFile(home.resolve("main.py"))) {
            Messages.showWarningDialog(project, "FM-Agent path must contain main.py: " + home, "FM Agent");
            return false;
        }
        return true;
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

    private Path resultTarget(boolean showWarning) {
        if (lastTargetPath != null) {
            return lastTargetPath;
        }
        String basePath = project.getBasePath();
        if (basePath == null || basePath.isBlank()) {
            if (showWarning) {
                Messages.showWarningDialog(project, "Open a project before getting FM-Agent results.", "FM Agent");
            }
            return null;
        }
        return Path.of(basePath);
    }

    private boolean ensureGitRepository(Path targetPath) {
        GitCommandResult head = runGitCommand(targetPath, "rev-parse", "--verify", "HEAD");
        if (head.success()) {
            appendLine("[ok] Target git repository has HEAD: " + head.output().strip());
            return true;
        }

        appendLine("[..] Target has no git HEAD; preparing git repository in " + targetPath);
        if (!Files.exists(targetPath.resolve(".git"))) {
            GitCommandResult init = runGitCommand(targetPath, "init");
            appendGitOutput(init);
            if (!init.success()) {
                Messages.showWarningDialog(project, "Could not initialize git repository in " + targetPath, "FM Agent");
                return false;
            }
        }

        GitCommandResult add = runGitCommand(targetPath, "add", "-A");
        appendGitOutput(add);
        if (!add.success()) {
            Messages.showWarningDialog(project, "Could not stage project files in " + targetPath, "FM Agent");
            return false;
        }

        GitCommandResult commit = runGitCommand(targetPath,
                "-c", "user.name=FM-Agent",
                "-c", "user.email=fm-agent@local",
                "commit", "--allow-empty", "-m", "Initialize repository for FM-Agent");
        appendGitOutput(commit);
        if (!commit.success()) {
            Messages.showWarningDialog(project, "Could not create initial git commit in " + targetPath, "FM Agent");
            return false;
        }

        GitCommandResult verifiedHead = runGitCommand(targetPath, "rev-parse", "--verify", "HEAD");
        appendGitOutput(verifiedHead);
        if (!verifiedHead.success()) {
            Messages.showWarningDialog(project, "Git repository was initialized, but HEAD is still unavailable in " + targetPath, "FM Agent");
            return false;
        }
        appendLine("[ok] Target git repository ready: " + verifiedHead.output().strip());
        return true;
    }

    private void appendGitOutput(GitCommandResult result) {
        if (!result.output().isBlank()) {
            appendLine(result.output().strip());
        }
    }

    private GitCommandResult runGitCommand(Path targetPath, String... args) {
        try {
            String[] command = new String[args.length + 3];
            command[0] = "git";
            command[1] = "-C";
            command[2] = targetPath.toString();
            System.arraycopy(args, 0, command, 3, args.length);
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectErrorStream(true);
            Process process = builder.start();
            String output;
            try (var reader = process.inputReader(StandardCharsets.UTF_8)) {
                output = reader.lines().collect(java.util.stream.Collectors.joining(System.lineSeparator()));
            }
            int exitCode = process.waitFor();
            return new GitCommandResult(exitCode, output);
        } catch (IOException exception) {
            return new GitCommandResult(-1, exception.getMessage());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new GitCommandResult(-1, "Interrupted while running git.");
        }
    }

    private void runProcess(Path fmAgentHomePath, Path targetPath, String title, String command) {
        runProcess(fmAgentHomePath, targetPath, title, command, true, true);
    }

    private void runProcess(Path fmAgentHomePath, Path targetPath, String title, String command, boolean monitorFmAgentTrace) {
        runProcess(fmAgentHomePath, targetPath, title, command, monitorFmAgentTrace, false);
    }

    private void runProcess(Path fmAgentHomePath, Path targetPath, String title, String command,
                            boolean monitorFmAgentTrace, boolean updateResultsOnSuccess) {
        runProcess(fmAgentHomePath, targetPath, fmAgentHomePath, title, command, monitorFmAgentTrace, updateResultsOnSuccess);
    }

    private void runProcess(Path fmAgentHomePath, Path targetPath, Path workingDirectory, String title, String command,
                            boolean monitorFmAgentTrace, boolean updateResultsOnSuccess) {
        showMonitor();
        setRunning(true);
        if (updateResultsOnSuccess) {
            updateResultViews(targetPath);
        }
        appendLine("");
        appendLine("=== " + title + " ===");
        appendLine("FM-Agent: " + fmAgentHomePath);
        appendLine("Target: " + targetPath);
        appendLine("Working directory: " + workingDirectory);
        appendLine("Command: " + command);
        append(FmAgentEnvironment.describe(fmAgentHomePath, timeoutSeconds()));

        executor.submit(() -> {
            int exitCode = -1;
            FmAgentLogMonitor monitor = new FmAgentLogMonitor(targetPath);
            try {
                ProcessBuilder builder = new ProcessBuilder("/bin/bash", "-lc", command);
                builder.directory(workingDirectory.toFile());
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
                    if (updateResultsOnSuccess) {
                        updateResultViews(targetPath);
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
                if (updateResultsOnSuccess) {
                    updateResultViews(targetPath);
                    appendLine("Verify Result refreshed for " + targetPath);
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

    private void setResultHtml(String html) {
        SwingUtilities.invokeLater(() -> {
            resultOutput.setText(html);
            resultOutput.setCaretPosition(0);
        });
    }

    private void updateResultViews(Path targetPath) {
        setResultHtml(FmAgentResults.renderHtml(targetPath));
    }

    private void openResultLink(HyperlinkEvent event) {
        Path path = pathFromLink(event);
        if (path == null) {
            return;
        }
        VirtualFile file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path);
        if (file == null) {
            Messages.showWarningDialog(project, "Result file not found: " + path, "FM Agent");
            return;
        }
        FileEditorManager.getInstance(project).openFile(file, true);
    }

    private Path pathFromLink(HyperlinkEvent event) {
        try {
            URI uri = event.getURL() != null ? event.getURL().toURI() : new URI(event.getDescription());
            if (!"file".equalsIgnoreCase(uri.getScheme())) {
                return null;
            }
            return Path.of(uri).toAbsolutePath().normalize();
        } catch (IllegalArgumentException | URISyntaxException exception) {
            Messages.showWarningDialog(project, "Could not open result link: " + event.getDescription(), "FM Agent");
            return null;
        }
    }

    private void showMonitor() {
        SwingUtilities.invokeLater(showMonitorAction);
    }

    private void showVerifyResult() {
        SwingUtilities.invokeLater(showVerifyResultAction);
    }

    private record GitCommandResult(int exitCode, String output) {
        private boolean success() {
            return exitCode == 0;
        }
    }
}
