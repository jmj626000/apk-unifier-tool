package com.apkunifier.ui;

import com.apkunifier.core.ContainerFormat;
import com.apkunifier.core.ContainerInfo;
import com.apkunifier.core.ContainerInspector;
import com.apkunifier.core.ConversionException;
import com.apkunifier.core.ConversionOptions;
import com.apkunifier.core.ConversionResult;
import com.apkunifier.core.ConversionService;
import com.apkunifier.core.ProgressListener;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JToolBar;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.TableColumn;
import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** 主窗口。 */
public class MainWindow extends JFrame {

    private static final List<String> SUPPORTED_EXTENSIONS =
            Arrays.asList(".apk", ".xapk", ".apkx", ".apkz", ".apkm", ".apks", ".zip");

    private final JobTableModel model = new JobTableModel();
    private final JTable table = new JTable(model);
    private final JTextArea logArea = new JTextArea();
    private final JProgressBar overallProgress = new JProgressBar(0, 100);
    private final JLabel statusLabel = new JLabel("就绪");
    private final JTextField outputDirField = new JTextField(28);
    private final JTextField toolPathField = new JTextField(28);
    private final JLabel toolStatusLabel = new JLabel();
    private final JCheckBox obbCheck = new JCheckBox("还原容器内的 OBB 数据", true);
    private final JCheckBox overwriteCheck = new JCheckBox("覆盖同名输出文件", true);
    private final JCheckBox keepTempCheck = new JCheckBox("保留临时解包目录", false);
    private final JCheckBox signCheck = new JCheckBox("合并后自动签名（产物可直接安装）", true);
    private final JTextField keystoreField = new JTextField(20);
    private final JPasswordField keystorePassField = new JPasswordField("android", 10);
    private final JTextField aliasField = new JTextField("androiddebugkey", 14);

    private JButton addFilesButton;
    private JButton addFolderButton;
    private JButton removeButton;
    private JButton clearButton;
    private JButton startButton;
    private JButton stopButton;
    private JButton downloadToolButton;

    private final ConversionService service = new ConversionService();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private ExecutorService executor;
    private Path lastOutputDir;

    public MainWindow() {
        super("APK Unifier — 安装包统一转换工具");
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(900, 600));
        setSize(1080, 740);
        setLocationRelativeTo(null);
        buildUi();
        wireEvents();
        refreshToolStatus();
        updateSigningEnabled();
        updateButtons();
    }

    private void buildUi() {
        setLayout(new BorderLayout());
        add(buildToolBar(), BorderLayout.NORTH);

        table.setRowHeight(26);
        table.setSelectionMode(javax.swing.ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.setFillsViewportHeight(true);
        table.setDragEnabled(true);
        table.setTransferHandler(new FileDropHandler(this::addFiles));
        int[] widths = {300, 70, 220, 130, 90, 90, 80};
        for (int i = 0; i < widths.length && i < table.getColumnCount(); i++) {
            TableColumn column = table.getColumnModel().getColumn(i);
            column.setPreferredWidth(widths[i]);
        }
        JScrollPane tableScroll = new JScrollPane(table);
        tableScroll.setBorder(BorderFactory.createTitledBorder("待转换文件（可直接拖拽文件到此处）"));

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, tableScroll, buildBottomPanel());
        split.setResizeWeight(0.55);
        split.setOneTouchExpandable(true);
        add(split, BorderLayout.CENTER);
    }

    private JToolBar buildToolBar() {
        JToolBar bar = new JToolBar();
        bar.setFloatable(false);
        addFilesButton = new JButton("添加文件");
        addFolderButton = new JButton("添加文件夹");
        removeButton = new JButton("移除选中");
        clearButton = new JButton("清空列表");
        JButton helpButton = new JButton("使用说明");
        bar.add(addFilesButton);
        bar.add(addFolderButton);
        bar.add(removeButton);
        bar.add(clearButton);
        bar.addSeparator();
        bar.add(helpButton);
        bar.add(Box.createHorizontalGlue());
        helpButton.addActionListener(e -> showHelp());
        return bar;
    }

    private JPanel buildBottomPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 6));
        panel.setBorder(BorderFactory.createEmptyBorder(4, 8, 8, 8));
        JPanel options = new JPanel();
        options.setLayout(new BoxLayout(options, BoxLayout.Y_AXIS));
        options.add(buildOptionsPanel());
        options.add(buildSigningPanel());
        panel.add(options, BorderLayout.NORTH);

        logArea.setEditable(false);
        logArea.setFont(new java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 12));
        JScrollPane logScroll = new JScrollPane(logArea);
        logScroll.setBorder(BorderFactory.createTitledBorder("日志"));
        panel.add(logScroll, BorderLayout.CENTER);

        JPanel south = new JPanel(new BorderLayout(8, 0));
        overallProgress.setStringPainted(true);
        south.add(overallProgress, BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        startButton = new JButton("开始转换");
        stopButton = new JButton("停止");
        JButton openOutputButton = new JButton("打开输出目录");
        buttons.add(statusLabel);
        buttons.add(openOutputButton);
        buttons.add(startButton);
        buttons.add(stopButton);
        south.add(buttons, BorderLayout.EAST);
        openOutputButton.addActionListener(e -> openOutputDir());
        panel.add(south, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel buildOptionsPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createTitledBorder("选项"));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 3, 3, 3);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;

        c.gridx = 0;
        c.gridy = 0;
        c.weightx = 0;
        panel.add(new JLabel("输出目录："), c);
        c.gridx = 1;
        c.weightx = 1;
        outputDirField.setToolTipText("留空则输出到源文件所在目录");
        panel.add(outputDirField, c);
        c.gridx = 2;
        c.weightx = 0;
        JButton browseOutput = new JButton("浏览…");
        browseOutput.addActionListener(e -> chooseOutputDir());
        panel.add(browseOutput, c);

        c.gridx = 0;
        c.gridy = 1;
        c.weightx = 0;
        panel.add(new JLabel("合并工具："), c);
        c.gridx = 1;
        c.weightx = 1;
        toolPathField.setToolTipText("APKEditor.jar 路径；留空则自动查找");
        panel.add(toolPathField, c);
        c.gridx = 2;
        c.weightx = 0;
        JPanel toolButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        JButton browseTool = new JButton("选择…");
        browseTool.addActionListener(e -> chooseToolJar());
        downloadToolButton = new JButton("自动下载");
        downloadToolButton.addActionListener(e -> downloadTool(null));
        toolButtons.add(browseTool);
        toolButtons.add(downloadToolButton);
        panel.add(toolButtons, c);

        c.gridx = 1;
        c.gridy = 2;
        c.gridwidth = 2;
        toolStatusLabel.setForeground(new java.awt.Color(0x66, 0x66, 0x66));
        panel.add(toolStatusLabel, c);

        c.gridx = 1;
        c.gridy = 3;
        c.gridwidth = 2;
        JPanel checks = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
        checks.add(obbCheck);
        checks.add(overwriteCheck);
        checks.add(keepTempCheck);
        checks.add(signCheck);
        panel.add(checks, c);
        return panel;
    }

    private JPanel buildSigningPanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        panel.setBorder(BorderFactory.createTitledBorder("签名设置（留空则使用内置调试密钥）"));
        keystoreField.setToolTipText("自定义 keystore（.jks/.keystore/.p12）；留空使用内置调试密钥");
        JButton choose = new JButton("选择…");
        choose.addActionListener(e -> chooseKeystore());
        panel.add(new JLabel("密钥库："));
        panel.add(keystoreField);
        panel.add(choose);
        panel.add(new JLabel("口令："));
        panel.add(keystorePassField);
        panel.add(new JLabel("别名："));
        panel.add(aliasField);
        return panel;
    }

    private void wireEvents() {
        addFilesButton.addActionListener(e -> chooseFiles());
        addFolderButton.addActionListener(e -> chooseFolder());
        removeButton.addActionListener(e -> removeSelected());
        clearButton.addActionListener(e -> {
            if (!running.get()) {
                model.clear();
                updateButtons();
            }
        });
        startButton.addActionListener(e -> startConversion());
        stopButton.addActionListener(e -> stopConversion());
        signCheck.addActionListener(e -> updateSigningEnabled());
        toolPathField.addActionListener(e -> refreshToolStatus());
        toolPathField.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override
            public void focusLost(java.awt.event.FocusEvent e) {
                refreshToolStatus();
            }
        });
        setTransferHandler(new FileDropHandler(this::addFiles));
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                if (running.get()) {
                    int choice = JOptionPane.showConfirmDialog(MainWindow.this,
                            "转换仍在进行，确定要退出吗？", "确认退出", JOptionPane.YES_NO_OPTION);
                    if (choice != JOptionPane.YES_OPTION) {
                        return;
                    }
                    stopConversion();
                }
                dispose();
                System.exit(0);
            }
        });
    }

    // ------------------------------------------------------------------ 输入

    private void chooseFiles() {
        JFileChooser chooser = new JFileChooser();
        chooser.setMultiSelectionEnabled(true);
        chooser.setFileFilter(new FileNameExtensionFilter(
                "安装包 (*.xapk *.apkx *.apkz *.apkm *.apks *.apk *.zip)",
                "xapk", "apkx", "apkz", "apkm", "apks", "apk", "zip"));
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            List<Path> paths = new ArrayList<>();
            for (File file : chooser.getSelectedFiles()) {
                paths.add(file.toPath());
            }
            if (paths.isEmpty() && chooser.getSelectedFile() != null) {
                paths.add(chooser.getSelectedFile().toPath());
            }
            addFiles(paths);
        }
    }

    private void chooseFolder() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            addFiles(List.of(chooser.getSelectedFile().toPath()));
        }
    }

    /** 添加文件或目录（目录会自动展开为受支持的文件）。 */
    private void addFiles(List<Path> paths) {
        if (running.get()) {
            info("转换进行中，暂不能添加文件。");
            return;
        }
        List<Path> expanded = new ArrayList<>();
        for (Path path : paths) {
            if (Files.isDirectory(path)) {
                try (var stream = Files.list(path)) {
                    stream.filter(Files::isRegularFile)
                            .filter(p -> hasSupportedExtension(p.getFileName().toString()))
                            .sorted()
                            .forEach(expanded::add);
                } catch (Exception e) {
                    log("读取目录失败：" + path + " — " + e.getMessage());
                }
            } else {
                expanded.add(path);
            }
        }
        for (Path path : expanded) {
            JobEntry job = new JobEntry(path);
            job.sizeText = formatSize(sizeOf(path));
            model.add(job);
            inspectAsync(job);
        }
        updateButtons();
    }

    private boolean hasSupportedExtension(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (String ext : SUPPORTED_EXTENSIONS) {
            if (lower.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }

    /** 后台读取容器元信息并回填到表格。 */
    private void inspectAsync(JobEntry job) {
        Thread thread = new Thread(() -> {
            try {
                ContainerInfo info = ContainerInspector.inspect(job.file);
                SwingUtilities.invokeLater(() -> {
                    job.format = info.format;
                    job.packageName = info.packageName == null ? "" : info.packageName;
                    job.version = info.displayVersion();
                    if (info.unsupported) {
                        job.status = "不支持";
                        job.message = info.note;
                    }
                    model.rowChanged(rowOf(job));
                    updateButtons();
                });
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    job.status = "无法识别";
                    job.message = e.getMessage();
                    model.rowChanged(rowOf(job));
                });
            }
        }, "inspector");
        thread.setDaemon(true);
        thread.start();
    }

    private void removeSelected() {
        if (running.get()) {
            return;
        }
        int[] rows = table.getSelectedRows();
        Arrays.sort(rows);
        for (int i = rows.length - 1; i >= 0; i--) {
            if (rows[i] >= 0 && rows[i] < model.getRowCount()) {
                model.remove(rows[i]);
            }
        }
        updateButtons();
    }

    // ------------------------------------------------------------------ 转换

    private void startConversion() {
        if (running.get()) {
            return;
        }
        List<JobEntry> jobs = new ArrayList<>(model.jobs());
        if (jobs.isEmpty()) {
            info("请先添加需要转换的文件。");
            return;
        }

        ConversionOptions options = new ConversionOptions();
        options.outputDir = parsePath(outputDirField.getText());
        options.apkeditorJar = parsePath(toolPathField.getText());
        options.extractObb = obbCheck.isSelected();
        options.overwrite = overwriteCheck.isSelected();
        options.keepWorkDir = keepTempCheck.isSelected();

        Path keystore = parsePath(keystoreField.getText());
        if (signCheck.isSelected() && !keystoreField.getText().isBlank() && keystore == null) {
            info("指定的密钥库文件不存在：" + keystoreField.getText().trim());
            return;
        }
        options.sign = signCheck.isSelected();
        options.keystore = keystore;
        options.keystorePassword = new String(keystorePassField.getPassword());
        options.keyAlias = aliasField.getText().isBlank() ? "androiddebugkey" : aliasField.getText().trim();

        boolean needsTool = jobs.stream().anyMatch(j -> j.format != ContainerFormat.APK);
        if (needsTool && service.resolveTool(options) == null) {
            int choice = JOptionPane.showConfirmDialog(this,
                    "合并 split APK 需要 APKEditor.jar，当前未找到。\n是否立即自动下载？",
                    "缺少合并工具", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (choice == JOptionPane.YES_OPTION) {
                downloadTool(this::startConversion);
            }
            return;
        }

        running.set(true);
        cancelled.set(false);
        logArea.setText("");
        overallProgress.setValue(0);
        statusLabel.setText("转换中…");
        updateButtons();
        log("开始转换，共 " + jobs.size() + " 个文件。");

        int threads = Math.min(3, Math.max(1, Runtime.getRuntime().availableProcessors() - 1));
        executor = Executors.newFixedThreadPool(threads);
        AtomicInteger remaining = new AtomicInteger(jobs.size());
        for (JobEntry job : jobs) {
            executor.submit(() -> {
                try {
                    runJob(job, options);
                } finally {
                    if (remaining.decrementAndGet() == 0) {
                        SwingUtilities.invokeLater(this::onAllFinished);
                    }
                }
            });
        }
        executor.shutdown();
    }

    private void runJob(JobEntry job, ConversionOptions options) {
        if (cancelled.get()) {
            SwingUtilities.invokeLater(() -> {
                job.status = "已取消";
                model.rowChanged(rowOf(job));
            });
            return;
        }
        SwingUtilities.invokeLater(() -> {
            job.status = "处理中";
            job.progress = 0;
            job.indeterminate = false;
            model.rowChanged(rowOf(job));
        });

        final int[] lastPercent = {-1};
        ProgressListener listener = new ProgressListener() {
            @Override
            public void onLog(String line) {
                appendLog("[" + job.file.getFileName() + "] " + line);
            }

            @Override
            public void onProgress(int percent) {
                if (percent == lastPercent[0]) {
                    return;
                }
                lastPercent[0] = percent;
                SwingUtilities.invokeLater(() -> {
                    job.progress = percent;
                    job.indeterminate = false;
                    model.rowChanged(rowOf(job));
                    updateOverall();
                });
            }

            @Override
            public void onIndeterminate(String label) {
                SwingUtilities.invokeLater(() -> {
                    job.status = label;
                    job.indeterminate = true;
                    model.rowChanged(rowOf(job));
                });
            }
        };

        try {
            ConversionResult result = service.convert(job.file, options, listener);
            lastOutputDir = result.outputApk == null ? lastOutputDir : result.outputApk.getParent();
            SwingUtilities.invokeLater(() -> {
                job.status = "完成";
                job.progress = 100;
                job.indeterminate = false;
                if (result.packageName != null) {
                    job.packageName = result.packageName;
                }
                if (result.versionName != null) {
                    job.version = result.versionName;
                }
                job.message = result.summary();
                model.rowChanged(rowOf(job));
                updateOverall();
            });
        } catch (ConversionException e) {
            appendLog("[" + job.file.getFileName() + "] 失败：" + e.getMessage());
            SwingUtilities.invokeLater(() -> {
                job.status = "失败";
                job.indeterminate = false;
                job.message = e.getMessage();
                model.rowChanged(rowOf(job));
                updateOverall();
            });
        }
    }

    private void stopConversion() {
        if (!running.get()) {
            return;
        }
        cancelled.set(true);
        if (executor != null) {
            executor.shutdownNow();
        }
        statusLabel.setText("已停止");
        log("已请求停止，正在结束未完成的任务…");
        running.set(false);
        updateButtons();
    }

    private void onAllFinished() {
        running.set(false);
        cancelled.set(false);
        updateButtons();
        long failed = model.jobs().stream().filter(j -> "失败".equals(j.status)).count();
        long done = model.jobs().stream().filter(j -> "完成".equals(j.status)).count();
        statusLabel.setText("完成 " + done + " 个，失败 " + failed + " 个");
        overallProgress.setValue(100);
    }

    // ------------------------------------------------------------------ 工具

    private void chooseOutputDir() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            outputDirField.setText(chooser.getSelectedFile().getAbsolutePath());
        }
    }

    private void chooseToolJar() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new FileNameExtensionFilter("APKEditor jar (*.jar)", "jar"));
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            toolPathField.setText(chooser.getSelectedFile().getAbsolutePath());
            refreshToolStatus();
        }
    }

    private void chooseKeystore() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new FileNameExtensionFilter(
                "密钥库 (*.jks *.keystore *.p12 *.pfx)", "jks", "keystore", "p12", "pfx"));
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            keystoreField.setText(chooser.getSelectedFile().getAbsolutePath());
        }
    }

    private void updateSigningEnabled() {
        boolean enabled = signCheck.isSelected();
        keystoreField.setEnabled(enabled);
        keystorePassField.setEnabled(enabled);
        aliasField.setEnabled(enabled);
    }

    private void downloadTool(Runnable onDone) {
        downloadToolButton.setEnabled(false);
        toolStatusLabel.setText("正在下载 APKEditor.jar…");
        statusLabel.setText("下载合并工具…");
        Thread thread = new Thread(() -> {
            try {
                Path jar = service.toolManager().downloadLatest(new ProgressListener() {
                    @Override
                    public void onLog(String line) {
                        appendLog(line);
                    }

                    @Override
                    public void onProgress(int percent) {
                        SwingUtilities.invokeLater(() -> overallProgress.setValue(percent));
                    }

                    @Override
                    public void onIndeterminate(String label) {
                        // 忽略
                    }
                });
                SwingUtilities.invokeLater(() -> {
                    toolPathField.setText(jar.toString());
                    refreshToolStatus();
                    overallProgress.setValue(0);
                    statusLabel.setText("合并工具已就绪");
                    log("合并工具已就绪：" + jar);
                    if (onDone != null) {
                        onDone.run();
                    }
                });
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    refreshToolStatus();
                    overallProgress.setValue(0);
                    statusLabel.setText("下载失败");
                    JOptionPane.showMessageDialog(this,
                            "自动下载失败：" + e.getMessage()
                                    + "\n\n可手动下载 APKEditor.jar（GitHub: REAndroid/APKEditor）后，选择其路径。",
                            "下载失败", JOptionPane.ERROR_MESSAGE);
                });
            } finally {
                SwingUtilities.invokeLater(() -> downloadToolButton.setEnabled(true));
            }
        }, "tool-download");
        thread.setDaemon(true);
        thread.start();
    }

    private void refreshToolStatus() {
        Path tool = service.toolManager().locate(parsePath(toolPathField.getText()));
        if (tool != null) {
            toolStatusLabel.setText("合并工具已就绪：" + tool);
        } else {
            toolStatusLabel.setText("未找到 APKEditor.jar（合并 split 必需，可点击「自动下载」）");
        }
    }

    private void openOutputDir() {
        Path dir = parsePath(outputDirField.getText());
        if (dir == null) {
            dir = lastOutputDir;
        }
        if (dir == null) {
            info("还没有输出目录，请先设置或完成一次转换。");
            return;
        }
        try {
            Desktop.getDesktop().open(dir.toFile());
        } catch (Exception e) {
            info("无法打开目录：" + dir + "\n" + e.getMessage());
        }
    }

    private void showHelp() {
        JOptionPane.showMessageDialog(this,
                """
                        APK Unifier 可把 XAPK / APKX / APKZ / APKM / APKS 等分发包
                        统一转换为单个可直接安装的 APK。

                        使用步骤：
                        1. 添加文件（支持拖拽），或添加整个文件夹；
                        2. 设置输出目录（留空则输出到源文件所在目录）；
                        3. 首次使用点击「自动下载」获取合并工具 APKEditor.jar；
                        4. 点击「开始转换」，完成后在输出目录查看结果。

                        说明：
                        · 合并 split APK 依赖 APKEditor.jar（开源，需本机已安装 Java）；
                        · 合并后签名会失效，程序会自动用内置调试密钥重新签名，
                          使产物可直接安装；也可在「签名设置」中指定自己的密钥库；
                        · 若容器包含 OBB 数据，会一并还原到输出目录；
                        · 单个普通 APK 会直接复制，无需合并工具。
                        """,
                "使用说明", JOptionPane.INFORMATION_MESSAGE);
    }

    // ------------------------------------------------------------------ 辅助

    private int rowOf(JobEntry job) {
        return model.jobs().indexOf(job);
    }

    private void updateButtons() {
        boolean isRunning = running.get();
        boolean empty = model.isEmpty();
        if (addFilesButton != null) {
            addFilesButton.setEnabled(!isRunning);
            addFolderButton.setEnabled(!isRunning);
            removeButton.setEnabled(!isRunning && !empty);
            clearButton.setEnabled(!isRunning && !empty);
            startButton.setEnabled(!isRunning && !empty);
            stopButton.setEnabled(isRunning);
        }
    }

    private void updateOverall() {
        List<JobEntry> jobs = model.jobs();
        if (jobs.isEmpty()) {
            overallProgress.setValue(0);
            return;
        }
        int total = 0;
        for (JobEntry job : jobs) {
            total += "完成".equals(job.status) ? 100 : Math.max(0, job.progress);
        }
        overallProgress.setValue(total / jobs.size());
    }

    private void log(String line) {
        appendLog(line);
    }

    private void appendLog(String line) {
        SwingUtilities.invokeLater(() -> {
            logArea.append(line + System.lineSeparator());
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }

    private void info(String message) {
        JOptionPane.showMessageDialog(this, message, "提示", JOptionPane.INFORMATION_MESSAGE);
    }

    private static Path parsePath(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Path path = Path.of(text.trim());
        return Files.exists(path) ? path : null;
    }

    private static long sizeOf(Path path) {
        try {
            return Files.size(path);
        } catch (Exception e) {
            return 0L;
        }
    }

    private static String formatSize(long bytes) {
        if (bytes <= 0) {
            return "";
        }
        String[] units = {"B", "KB", "MB", "GB"};
        double value = bytes;
        int unit = 0;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return String.format(Locale.ROOT, "%.1f %s", value, units[unit]);
    }
}
