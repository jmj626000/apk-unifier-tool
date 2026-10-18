package com.apkunifier.ui;

import javax.swing.TransferHandler;
import java.awt.datatransfer.DataFlavor;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** 支持把文件/文件夹拖入窗口的 TransferHandler。 */
public class FileDropHandler extends TransferHandler {

    private final Consumer<List<Path>> onFiles;

    public FileDropHandler(Consumer<List<Path>> onFiles) {
        this.onFiles = onFiles;
    }

    @Override
    public boolean canImport(TransferSupport support) {
        return support.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
    }

    @Override
    public boolean importData(TransferSupport support) {
        if (!canImport(support)) {
            return false;
        }
        try {
            @SuppressWarnings("unchecked")
            List<File> files = (List<File>) support.getTransferable()
                    .getTransferData(DataFlavor.javaFileListFlavor);
            List<Path> paths = new ArrayList<>();
            for (File file : files) {
                paths.add(file.toPath());
            }
            onFiles.accept(paths);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
