package com.apkunifier.cli;

import com.apkunifier.core.ConversionException;
import com.apkunifier.core.ConversionOptions;
import com.apkunifier.core.ConversionResult;
import com.apkunifier.core.ConversionService;
import com.apkunifier.core.ProgressListener;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 无界面的命令行模式，便于批量处理与脚本集成。 */
public final class CliRunner {

    private static final List<String> SUPPORTED_EXTENSIONS =
            List.of(".apk", ".xapk", ".apkx", ".apkz", ".apkm", ".apks", ".zip");

    private CliRunner() {
    }

    public static void printUsage(PrintStream out) {
        out.println("""
                用法: java -jar apk-unifier.jar --cli <文件或目录...> [选项]

                选项:
                  -o, --output <目录>   输出目录（默认与源文件同目录）
                      --tool <jar>      指定 APKEditor.jar 路径
                      --no-obb          不还原容器内的 OBB 数据
                      --no-overwrite    不覆盖已存在的输出文件
                      --no-sign         合并后不签名（默认用内置调试密钥签名）
                      --ks <文件>       使用指定的密钥库（.jks/.keystore/.p12）
                      --ks-pass <口令>  密钥库口令（默认 android）
                      --ks-alias <别名> 密钥别名（默认 androiddebugkey）
                      --quiet           只输出结果，不打印详细日志
                  -h, --help            显示本帮助

                支持的输入: XAPK / APKX / APKZ / APKM / APKS 以及普通 APK
                """);
    }

    public static int run(String[] args) {
        List<Path> inputs = new ArrayList<>();
        Path outputDir = null;
        Path tool = null;
        boolean extractObb = true;
        boolean overwrite = true;
        boolean sign = true;
        boolean quiet = false;
        Path keystore = null;
        String keystorePassword = "android";
        String keyAlias = "androiddebugkey";

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "-o", "--output" -> outputDir = i + 1 < args.length ? Path.of(args[++i]) : null;
                case "--tool" -> tool = i + 1 < args.length ? Path.of(args[++i]) : null;
                case "--no-obb" -> extractObb = false;
                case "--no-overwrite" -> overwrite = false;
                case "--no-sign" -> sign = false;
                case "--ks" -> keystore = i + 1 < args.length ? Path.of(args[++i]) : null;
                case "--ks-pass" -> keystorePassword = i + 1 < args.length ? args[++i] : keystorePassword;
                case "--ks-alias" -> keyAlias = i + 1 < args.length ? args[++i] : keyAlias;
                case "--quiet" -> quiet = true;
                case "-h", "--help" -> {
                    printUsage(System.out);
                    return 0;
                }
                default -> inputs.add(Path.of(arg));
            }
        }

        List<Path> files = expand(inputs);
        if (files.isEmpty()) {
            System.err.println("未找到可转换的文件。");
            printUsage(System.err);
            return 2;
        }

        ConversionOptions options = new ConversionOptions();
        options.outputDir = outputDir;
        options.apkeditorJar = tool;
        options.extractObb = extractObb;
        options.overwrite = overwrite;
        options.sign = sign;
        options.keystore = keystore;
        options.keystorePassword = keystorePassword;
        options.keyAlias = keyAlias;

        ConversionService service = new ConversionService();
        if (service.resolveTool(options) == null) {
            System.err.println("提示：未找到 APKEditor.jar，需要合并 split 的文件将失败。");
            System.err.println("      可通过 --tool <jar> 指定，或在图形界面中点击「自动下载」。");
        }

        boolean showLogs = !quiet;
        int success = 0;
        int failure = 0;
        for (Path file : files) {
            System.out.println(">>> " + file.getFileName());
            ProgressListener listener = new ProgressListener() {
                @Override
                public void onLog(String line) {
                    if (showLogs) {
                        System.out.println("    | " + line);
                    }
                }

                @Override
                public void onProgress(int percent) {
                    // 命令行模式不展示进度条
                }

                @Override
                public void onIndeterminate(String label) {
                    if (showLogs) {
                        System.out.println("    | " + label);
                    }
                }
            };
            try {
                ConversionResult result = service.convert(file, options, listener);
                System.out.println("    成功：" + result.summary());
                success++;
            } catch (ConversionException e) {
                System.out.println("    失败：" + e.getMessage());
                failure++;
            }
        }
        System.out.printf(Locale.ROOT, "全部结束：成功 %d 个，失败 %d 个%n", success, failure);
        return failure == 0 ? 0 : 1;
    }

    private static List<Path> expand(List<Path> inputs) {
        List<Path> files = new ArrayList<>();
        for (Path path : inputs) {
            if (Files.isDirectory(path)) {
                try (var stream = Files.list(path)) {
                    stream.filter(Files::isRegularFile)
                            .filter(CliRunner::isSupported)
                            .sorted()
                            .forEach(files::add);
                } catch (Exception e) {
                    System.err.println("读取目录失败：" + path + " — " + e.getMessage());
                }
            } else if (Files.isRegularFile(path)) {
                files.add(path);
            } else {
                System.err.println("路径不存在：" + path);
            }
        }
        return files;
    }

    private static boolean isSupported(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        for (String ext : SUPPORTED_EXTENSIONS) {
            if (name.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }
}
