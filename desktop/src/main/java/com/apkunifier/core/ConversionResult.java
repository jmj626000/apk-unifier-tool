package com.apkunifier.core;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** 单次转换结果。 */
public class ConversionResult {

    public Path outputApk;
    public String packageName;
    public String versionName;
    public long versionCode = -1L;
    public int splitCount;
    public boolean merged;
    public boolean signed;
    public final List<Path> obbOutputs = new ArrayList<>();

    public String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append(outputApk == null ? "未生成输出" : outputApk.getFileName().toString());
        if (packageName != null) {
            sb.append(" | ").append(packageName);
            if (versionName != null) {
                sb.append(" v").append(versionName);
            }
        }
        if (merged) {
            sb.append(" | 合并 ").append(splitCount).append(" 个 split");
            sb.append(signed ? " | 已签名" : " | 未签名");
        }
        if (!obbOutputs.isEmpty()) {
            sb.append(" | 附带 ").append(obbOutputs.size()).append(" 个 OBB");
        }
        return sb.toString();
    }
}
