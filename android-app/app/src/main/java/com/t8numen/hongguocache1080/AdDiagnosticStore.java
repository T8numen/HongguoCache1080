package com.t8numen.hongguocache1080;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** Used only by the recorder's single writer, including during export. */
final class AdDiagnosticStore {
    static final String DIRECTORY = "红果广告诊断";
    private static final String PREFIX = "广告事件-";
    private final File directory;
    private final String session;
    private final long shardLimit;
    private final long totalLimit;
    private final long retentionMs;
    private File active;
    private int shard;

    AdDiagnosticStore(File directory, String session, long shardLimit,
            long totalLimit, long retentionMs) {
        this.directory = directory;
        this.session = session;
        this.shardLimit = shardLimit;
        this.totalLimit = totalLimit;
        this.retentionMs = retentionMs;
    }

    void append(String json, long now) throws IOException {
        byte[] bytes = (json + "\n").getBytes(StandardCharsets.UTF_8);
        if (bytes.length > shardLimit) {
            throw new IOException("Diagnostic event exceeds shard limit");
        }
        ensureDirectory();
        if (active == null || active.length() + bytes.length > shardLimit) {
            seal();
            do {
                active = new File(directory, PREFIX + session + "-" + (++shard) + ".jsonl");
            } while (active.exists());
        }
        try (FileOutputStream output = new FileOutputStream(active, true)) {
            output.write(bytes);
        }
        prune(now);
    }

    void writeStatus(String json, long now) throws IOException {
        ensureDirectory();
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 64 * 1024) {
            throw new IOException("Diagnostic status exceeds limit");
        }
        File temporary = new File(directory, "记录状态.tmp");
        try (FileOutputStream output = new FileOutputStream(temporary)) {
            output.write(bytes);
        }
        Files.move(temporary.toPath(), new File(directory, "记录状态.json").toPath(),
                StandardCopyOption.REPLACE_EXISTING);
        prune(now);
    }

    void seal() {
        active = null;
    }

    List<File> snapshot() throws IOException {
        ensureDirectory();
        seal();
        return ownedFiles();
    }

    private void ensureDirectory() throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Cannot create diagnostic directory");
        }
    }

    private List<File> ownedFiles() {
        File[] files = directory.listFiles(file -> file.isFile()
                && (file.getName().equals("记录状态.json")
                || (file.getName().startsWith(PREFIX) && file.getName().endsWith(".jsonl"))));
        if (files == null) {
            return new ArrayList<>();
        }
        Arrays.sort(files, Comparator.comparingLong(File::lastModified).thenComparing(File::getName));
        return new ArrayList<>(Arrays.asList(files));
    }

    private void prune(long now) throws IOException {
        List<File> files = ownedFiles();
        long total = 0;
        for (File file : files) {
            if (!file.equals(active) && file.getName().endsWith(".jsonl")
                    && now - file.lastModified() > retentionMs) {
                remove(file);
            } else {
                total += file.length();
            }
        }
        if (total <= totalLimit) {
            return;
        }
        for (File file : files) {
            if (file.exists() && !file.equals(active) && file.getName().endsWith(".jsonl")) {
                long length = file.length();
                remove(file);
                total -= length;
                if (total <= totalLimit) {
                    return;
                }
            }
        }
    }

    private void remove(File file) throws IOException {
        if (!file.delete()) {
            throw new IOException("Cannot rotate diagnostic file");
        }
    }
}
