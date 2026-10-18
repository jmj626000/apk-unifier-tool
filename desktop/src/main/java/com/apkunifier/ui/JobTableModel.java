package com.apkunifier.ui;

import javax.swing.table.AbstractTableModel;
import java.util.ArrayList;
import java.util.List;

/** 任务表数据模型。 */
public class JobTableModel extends AbstractTableModel {

    private static final String[] COLUMNS = {"文件", "格式", "包名", "版本", "大小", "状态", "进度"};

    private final List<JobEntry> jobs = new ArrayList<>();

    public void add(JobEntry job) {
        jobs.add(job);
        fireTableRowsInserted(jobs.size() - 1, jobs.size() - 1);
    }

    public void remove(int index) {
        jobs.remove(index);
        fireTableRowsDeleted(index, index);
    }

    public void clear() {
        int size = jobs.size();
        jobs.clear();
        if (size > 0) {
            fireTableRowsDeleted(0, size - 1);
        }
    }

    public List<JobEntry> jobs() {
        return jobs;
    }

    public boolean isEmpty() {
        return jobs.isEmpty();
    }

    public void rowChanged(int index) {
        if (index >= 0 && index < jobs.size()) {
            fireTableRowsUpdated(index, index);
        }
    }

    @Override
    public int getRowCount() {
        return jobs.size();
    }

    @Override
    public int getColumnCount() {
        return COLUMNS.length;
    }

    @Override
    public String getColumnName(int column) {
        return COLUMNS[column];
    }

    @Override
    public Object getValueAt(int rowIndex, int columnIndex) {
        JobEntry job = jobs.get(rowIndex);
        return switch (columnIndex) {
            case 0 -> job.file.getFileName().toString();
            case 1 -> job.format.displayName();
            case 2 -> job.packageName;
            case 3 -> job.version;
            case 4 -> job.sizeText;
            case 5 -> job.status;
            case 6 -> job.indeterminate ? "处理中…" : (job.progress > 0 ? job.progress + "%" : "");
            default -> "";
        };
    }

    @Override
    public boolean isCellEditable(int rowIndex, int columnIndex) {
        return false;
    }
}
