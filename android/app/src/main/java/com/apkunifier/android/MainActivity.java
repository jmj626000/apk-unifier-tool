package com.apkunifier.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.apkunifier.core.ConversionOptions;
import com.apkunifier.core.ConversionResult;
import com.apkunifier.core.ProgressListener;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 单 Activity：选择安装包 → 逐个转换 → 安装 / 分享。 */
public class MainActivity extends Activity {

    private static final int REQUEST_SELECT = 1001;

    private final List<Job> jobs = new ArrayList<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final StringBuilder logBuffer = new StringBuilder();

    private ArrayAdapter<Job> adapter;
    private Button btnSelect;
    private Button btnConvert;
    private ProgressBar progressBar;
    private TextView logText;
    private ScrollView logScroll;
    private boolean converting;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        btnSelect = findViewById(R.id.btnSelect);
        btnConvert = findViewById(R.id.btnConvert);
        progressBar = findViewById(R.id.progressBar);
        logText = findViewById(R.id.logText);
        logScroll = findViewById(R.id.logScroll);
        ListView listView = findViewById(R.id.fileList);

        adapter = new ArrayAdapter<Job>(this, android.R.layout.simple_list_item_2,
                android.R.id.text1, jobs) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                Job job = jobs.get(position);
                TextView line1 = view.findViewById(android.R.id.text1);
                TextView line2 = view.findViewById(android.R.id.text2);
                line1.setText(job.displayName);
                line2.setText(job.status);
                return view;
            }
        };
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((parent, view, position, id) -> onJobClicked(jobs.get(position)));

        btnSelect.setOnClickListener(v -> selectFiles());
        btnConvert.setOnClickListener(v -> startConversion());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }

    private void selectFiles() {
        if (converting) {
            return;
        }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(intent, REQUEST_SELECT);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_SELECT || resultCode != RESULT_OK || data == null) {
            return;
        }
        List<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) {
            int count = data.getClipData().getItemCount();
            for (int i = 0; i < count; i++) {
                uris.add(data.getClipData().getItemAt(i).getUri());
            }
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        for (Uri uri : uris) {
            addFile(uri);
        }
        btnConvert.setEnabled(!jobs.isEmpty() && !converting);
    }

    private void addFile(Uri uri) {
        String name = queryDisplayName(uri);
        Job job = new Job(uri, name);
        try {
            job.localFile = copyToCache(uri, name);
            job.status = "就绪";
        } catch (IOException e) {
            job.status = "读取失败：" + e.getMessage();
        }
        jobs.add(job);
        adapter.notifyDataSetChanged();
        log("已选择：" + name);
    }

    private String queryDisplayName(Uri uri) {
        ContentResolver resolver = getContentResolver();
        try (Cursor cursor = resolver.query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    String value = cursor.getString(index);
                    if (value != null && !value.isEmpty()) {
                        return value;
                    }
                }
            }
        } catch (Exception ignored) {
            // 查询失败则回退到 Uri 末段
        }
        String last = uri.getLastPathSegment();
        return last == null ? "unnamed.apk" : last;
    }

    private File copyToCache(Uri uri, String displayName) throws IOException {
        String safe = displayName.replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
        File dir = new File(getCacheDir(), "input");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("无法创建缓存目录");
        }
        File dest = new File(dir, System.currentTimeMillis() + "_" + safe);
        try (InputStream in = getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(dest)) {
            if (in == null) {
                throw new IOException("无法打开输入流");
            }
            byte[] buffer = new byte[1 << 16];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                out.write(buffer, 0, read);
            }
        }
        return dest;
    }

    private void startConversion() {
        if (converting || jobs.isEmpty()) {
            return;
        }
        converting = true;
        btnSelect.setEnabled(false);
        btnConvert.setEnabled(false);
        progressBar.setProgress(0);

        final Path outputDir = getExternalFilesDir("out") != null
                ? getExternalFilesDir("out").toPath()
                : getFilesDir().toPath();

        final List<Job> pending = new ArrayList<>();
        for (Job job : jobs) {
            if (job.localFile != null) {
                job.status = "排队中";
                job.result = null;
                pending.add(job);
            }
        }
        adapter.notifyDataSetChanged();

        final int total = pending.size();
        if (total == 0) {
            converting = false;
            btnSelect.setEnabled(true);
            btnConvert.setEnabled(true);
            toast("没有可转换的文件");
            return;
        }
        progressBar.setMax(total * 100);

        executor.execute(() -> {
            int completed = 0;
            for (Job job : pending) {
                final Job current = job;
                ui.post(() -> {
                    current.status = "转换中…";
                    adapter.notifyDataSetChanged();
                });
                final int base = completed * 100;
                ProgressListener listener = new ProgressListener() {
                    @Override
                    public void onLog(String line) {
                        MainActivity.this.log(line);
                    }

                    @Override
                    public void onProgress(int percent) {
                        ui.post(() -> progressBar.setProgress(base + Math.max(0, Math.min(100, percent))));
                    }

                    @Override
                    public void onIndeterminate(String label) {
                        MainActivity.this.log(label);
                    }
                };
                try {
                    ConversionOptions options = new ConversionOptions();
                    options.outputDir = outputDir;
                    options.sign = true;
                    options.extractObb = true;
                    options.overwrite = true;
                    AndroidConversionService service = new AndroidConversionService();
                    ConversionResult result = service.convert(current.localFile.toPath(), options, listener);
                    current.result = result;
                    ui.post(() -> {
                        current.status = "完成：" + result.outputApk.getFileName()
                                + (result.merged ? "（已合并+签名）" : "（已签名）");
                        adapter.notifyDataSetChanged();
                    });
                } catch (Throwable t) {
                    ui.post(() -> {
                        current.status = "失败：" + t.getMessage();
                        adapter.notifyDataSetChanged();
                    });
                    log("转换失败 " + current.displayName + "：" + t.getMessage());
                }
                completed++;
                final int done = completed;
                ui.post(() -> progressBar.setProgress(done * 100));
            }
            ui.post(() -> {
                converting = false;
                btnSelect.setEnabled(true);
                btnConvert.setEnabled(true);
                toast("全部处理完成");
            });
        });
    }

    private void onJobClicked(Job job) {
        if (job.result == null || job.result.outputApk == null) {
            toast(job.status);
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(job.result.outputApk.getFileName().toString())
                .setItems(new String[]{"安装", "分享"}, (dialog, which) -> {
                    if (which == 0) {
                        installApk(job.result);
                    } else {
                        shareApk(job.result);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private Uri uriFor(Path path) {
        File file = path.toFile();
        return FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
    }

    private void installApk(ConversionResult result) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uriFor(result.outputApk),
                    "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            toast("未找到可安装 APK 的应用");
        }
    }

    private void shareApk(ConversionResult result) {
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("application/vnd.android.package-archive");
        intent.putExtra(Intent.EXTRA_STREAM, uriFor(result.outputApk));
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(intent, "分享 APK"));
    }

    private void log(String line) {
        ui.post(() -> {
            logBuffer.append(line).append('\n');
            logText.setText(logBuffer.toString());
            logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
        });
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    /** 单个待转换任务。 */
    private static class Job {
        final Uri uri;
        final String displayName;
        File localFile;
        String status = "待处理";
        ConversionResult result;

        Job(Uri uri, String displayName) {
            this.uri = uri;
            this.displayName = displayName;
        }
    }
}
