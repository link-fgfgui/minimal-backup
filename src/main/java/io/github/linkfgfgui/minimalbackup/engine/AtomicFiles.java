package io.github.linkfgfgui.minimalbackup.engine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 落盘原子替换：先写/拷临时文件，再 move 到位；{@code ATOMIC_MOVE} 不被支持时退回普通 move。
 *
 * <p>快照目录里所有「写一半比没写更糟」的文件（槽位镜像、清单、索引、重建产物）都走这里。
 * 失败时清掉临时文件，目标文件绝不留半个。
 *
 * <p>纯 JDK、Java 8 兼容。
 */
public final class AtomicFiles {
    /** 临时文件后缀；{@link VaultPaths#isExcluded(String)} 会把它排除掉。 */
    public static final String TEMP_SUFFIX = ".mb-tmp";

    private AtomicFiles() {
    }

    /** 写字节到临时文件再原子替换目标。 */
    public static void write(Path target, byte[] bytes) throws IOException {
        Path temp = tempFor(target);
        try {
            Files.write(temp, bytes);
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(temp);
            throw e;
        }
        commit(temp, target);
    }

    /** 拷贝文件到临时文件再原子替换目标。 */
    public static void copy(Path source, Path target) throws IOException {
        Path temp = tempFor(target);
        try {
            Files.copy(source, temp, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(temp);
            throw e;
        }
        commit(temp, target);
    }

    /** 把已写好的临时文件移入目标；move 失败时清掉临时文件。 */
    public static void commit(Path temp, Path target) throws IOException {
        try {
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(temp);
            throw e;
        }
    }

    /** 目标同目录下的临时文件路径。 */
    public static Path tempFor(Path target) {
        return target.resolveSibling(target.getFileName().toString() + TEMP_SUFFIX);
    }
}
