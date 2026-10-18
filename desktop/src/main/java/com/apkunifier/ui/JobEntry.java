package com.apkunifier.ui;

import com.apkunifier.core.ContainerFormat;

import java.nio.file.Path;

/** 界面任务表中的一行。 */
public class JobEntry {

    public final Path file;
    public ContainerFormat format = ContainerFormat.UNKNOWN;
    public String packageName = "";
    public String version = "";
    public String sizeText = "";
    public String status = "等待";
    public int progress;
    public boolean indeterminate;
    public String message = "";

    public JobEntry(Path file) {
        this.file = file;
    }
}
