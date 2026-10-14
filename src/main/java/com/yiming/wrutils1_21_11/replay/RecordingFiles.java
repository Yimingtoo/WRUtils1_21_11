package com.yiming.wrutils1_21_11.replay;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/** 按录制名在 {@code <root>/<label>/} 下定位/清理产出的 {@code .mcpr}。 */
public final class RecordingFiles {
    private RecordingFiles() {
    }

    public static List<Path> list(Path root, String label) {
        Path dir = root.resolve(label);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(path -> path.getFileName().toString().endsWith(".mcpr")).toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    public static Optional<Path> newest(Path root, String label) {
        return list(root, label).stream()
                .max(Comparator.comparingLong(path -> path.toFile().lastModified()));
    }

    /** 删除该录制名下的所有产出（含删空的录制目录）。返回是否真的删掉了东西，供"过几 tick 再试"判断。 */
    public static boolean deleteAll(Path root, String label) {
        boolean deleted = false;
        for (Path file : list(root, label)) {
            try {
                if (Files.deleteIfExists(file)) {
                    deleted = true;
                }
            } catch (IOException e) {
                // 正在写、被占用等：留待下次重试
            }
        }
        if (deleted) {
            try {
                // 只在该目录已空时成功；非空/被占用则忽略
                Files.deleteIfExists(root.resolve(label));
            } catch (IOException ignored) {
                // 目录里还有东西：留个空壳无伤大雅
            }
        }
        return deleted;
    }
}
