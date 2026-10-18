package com.apkunifier.core;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** 容器解包结果。 */
public class ExtractionResult {

    public final Path workDir;
    /** 所有 APK 被平铺展开的目录，可直接交给合并工具。 */
    public final Path apkDir;
    public final List<Path> apkFiles = new ArrayList<>();
    public final List<Path> obbFiles = new ArrayList<>();
    /** OBB 在容器中的相对路径，用于还原到输出目录。 */
    public final List<String> obbRelativePaths = new ArrayList<>();

    public ExtractionResult(Path workDir, Path apkDir) {
        this.workDir = workDir;
        this.apkDir = apkDir;
    }
}
