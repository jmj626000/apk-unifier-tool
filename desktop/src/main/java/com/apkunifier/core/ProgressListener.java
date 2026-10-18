package com.apkunifier.core;

/**
 * 转换过程中的进度与日志回调。所有回调都可能在后台线程被触发。
 */
public interface ProgressListener {

    /** 输出一行日志。 */
    void onLog(String line);

    /** 更新进度百分比（0-100）。 */
    void onProgress(int percent);

    /** 指示当前处于无法估算进度的阶段（如调用外部合并工具）。 */
    void onIndeterminate(String label);

    /** 空实现，便于非 GUI 场景使用。 */
    ProgressListener NOOP = new ProgressListener() {
        @Override
        public void onLog(String line) {
        }

        @Override
        public void onProgress(int percent) {
        }

        @Override
        public void onIndeterminate(String label) {
        }
    };
}
