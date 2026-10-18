package com.apkunifier.core;

import com.apkunifier.util.HttpDownloader;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 负责定位 / 获取合并工具 APKEditor.jar。
 *
 * <p>查找顺序：用户指定路径 → 本工具管理目录 → 程序所在目录。</p>
 */
public class ToolManager {

    /** 兜底版本号，当无法访问 GitHub 时使用。 */
    public static final String FALLBACK_TAG = "V1.4.9";
    private static final String RELEASE_PAGE = "https://github.com/REAndroid/APKEditor/releases/latest";
    private static final String DOWNLOAD_BASE = "https://github.com/REAndroid/APKEditor/releases/download/";

    private final Path managedDir;

    public ToolManager() {
        this.managedDir = Path.of(System.getProperty("user.home"), ".apk-unifier");
    }

    public Path managedDir() {
        return managedDir;
    }

    public Path managedJar() {
        return managedDir.resolve("APKEditor.jar");
    }

    /** 按优先级返回可用的 APKEditor.jar，找不到返回 null。 */
    public Path locate(Path configured) {
        if (configured != null && Files.isRegularFile(configured)) {
            return configured;
        }
        if (Files.isRegularFile(managedJar())) {
            return managedJar();
        }
        Path appDir = applicationDir();
        if (appDir != null) {
            Path direct = appDir.resolve("APKEditor.jar");
            if (Files.isRegularFile(direct)) {
                return direct;
            }
            Path nested = appDir.resolve("apkeditor").resolve("APKEditor.jar");
            if (Files.isRegularFile(nested)) {
                return nested;
            }
        }
        return null;
    }

    /** 从 GitHub 下载最新版 APKEditor.jar 到管理目录。 */
    public Path downloadLatest(ProgressListener listener) throws IOException {
        String tag = resolveLatestTag(listener);
        String version = tag.replaceFirst("^[Vv]", "");
        String url = DOWNLOAD_BASE + tag + "/APKEditor-" + version + ".jar";
        Path target = managedJar();
        listener.onLog("正在下载：" + url);
        HttpDownloader.download(url, target, (done, total) -> {
            if (total > 0) {
                int percent = 5 + (int) (done * 90 / total);
                listener.onProgress(Math.min(95, percent));
            }
        });
        listener.onLog("下载完成：" + target);
        return target;
    }

    private String resolveLatestTag(ProgressListener listener) {
        try {
            String location = HttpDownloader.resolveRedirect(RELEASE_PAGE);
            if (location != null) {
                String tag = location.substring(location.lastIndexOf('/') + 1);
                if (!tag.isBlank()) {
                    return tag;
                }
            }
        } catch (IOException e) {
            listener.onLog("查询最新版本失败，改用内置版本 " + FALLBACK_TAG + "：" + e.getMessage());
        }
        return FALLBACK_TAG;
    }

    /** 推断程序自身所在目录（用于放置随程序分发的 APKEditor.jar）。 */
    private Path applicationDir() {
        try {
            URI uri = ToolManager.class.getProtectionDomain().getCodeSource().getLocation().toURI();
            Path path = Path.of(uri);
            return Files.isDirectory(path) ? path : path.getParent();
        } catch (Exception e) {
            return null;
        }
    }
}
