package com.apkunifier;

import com.apkunifier.cli.CliRunner;
import com.apkunifier.ui.MainWindow;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.GraphicsEnvironment;
import java.util.Arrays;

/** 程序入口：默认启动图形界面，带 --cli 参数时进入命令行模式。 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        if (args.length > 0 && ("--cli".equals(args[0]) || "-c".equals(args[0]))) {
            System.exit(CliRunner.run(Arrays.copyOfRange(args, 1, args.length)));
        }
        if (args.length > 0 && ("--help".equals(args[0]) || "-h".equals(args[0]))) {
            CliRunner.printUsage(System.out);
            return;
        }

        if (GraphicsEnvironment.isHeadless()) {
            System.err.println("当前环境没有图形界面，请使用命令行模式：");
            CliRunner.printUsage(System.err);
            System.exit(2);
        }

        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // 使用默认外观
        }
        SwingUtilities.invokeLater(() -> new MainWindow().setVisible(true));
    }
}
