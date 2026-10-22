package io.github.linkfgfgui.minimalbackup.engine;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 快照库路径映射、键规范化、固定排除规则与快照名校验。
 *
 * <p>库布局：{@code <gameDir>/mbackups/<worldKey>/minimalbackup_snapshots/}。
 * {@code worldKey} 是世界存档文件夹名，只能取文件夹名（不允许分隔符），
 * 键一律相对世界存档目录、以 {@code /} 分隔。
 *
 * <p>纯 JDK，无 MC/loader 依赖。
 */
public final class VaultPaths {
    /** 备份基目录名，位于 gameDir 下、与世界存档目录分离。 */
    public static final String VAULT_DIR_NAME = "mbackups";
    /** 单个世界的快照根目录名。 */
    public static final String SNAPSHOT_DIR_NAME = "minimalbackup_snapshots";
    /** 快照树索引文件名（唯一提交点）。 */
    public static final String INDEX_FILE_NAME = "index.properties";
    /** 回滚事务标记文件名。 */
    public static final String RESTORE_MARKER_NAME = "restore.marker";
    /**
     * 单机自动重进的提示文件：arm 时写入，下一次世界加载成功后删除。
     * 与事务 marker 分开，因为 marker 在正常停服时仍需保留给崩溃恢复。
     */
    public static final String REJOIN_MARKER_NAME = "restore.rejoin";
    /**
     * 上次停服时 level.dat 的指纹：用来发现「模组没运行时世界被保存过」。
     * 原版没有保存次数计数，level.dat 每次保存都会重写（LastPlayed 也跟着变），
     * 所以内容哈希就是最可靠的见证。
     */
    public static final String WORLD_STATE_NAME = "world.state";
    /** 块文件名后缀。 */
    public static final String ZPACK_SUFFIX = ".zpack";
    /** 压实临时文件后缀；自愈会删除所有以它结尾的残留。 */
    public static final String COMPACT_TEMP_SUFFIX = ".tmp";

    private VaultPaths() {
    }

    /** 库根：{@code <gameDir>/mbackups/<worldKey>}。 */
    public static Path vaultRoot(Path gameDir, String worldKey) {
        if (gameDir == null) {
            throw new IllegalArgumentException("null game dir");
        }
        return gameDir.resolve(VAULT_DIR_NAME).resolve(requireWorldKey(worldKey));
    }

    /** 快照根：{@code <vaultRoot>/minimalbackup_snapshots}。 */
    public static Path snapshotRoot(Path vaultRoot) {
        if (vaultRoot == null) {
            throw new IllegalArgumentException("null vault root");
        }
        return vaultRoot.resolve(SNAPSHOT_DIR_NAME);
    }

    /** 索引文件：{@code <vaultRoot>/minimalbackup_snapshots/index.properties}。 */
    public static Path indexFile(Path vaultRoot) {
        return snapshotRoot(vaultRoot).resolve(INDEX_FILE_NAME);
    }

    /** 逻辑键对应的块文件路径：{@code <snapshotRoot>/<key>.zpack}。 */
    public static Path zpackFor(Path snapshotRoot, String relPath) {
        return snapshotRoot.resolve(normalizeKey(relPath) + ZPACK_SUFFIX);
    }

    /** 块文件路径反推逻辑键。 */
    public static String keyForZpack(Path snapshotRoot, Path zpack) {
        String rel = snapshotRoot.relativize(zpack).toString().replace('\\', '/');
        if (!rel.endsWith(ZPACK_SUFFIX)) {
            throw new IllegalArgumentException("not a zpack path: " + zpack);
        }
        return rel.substring(0, rel.length() - ZPACK_SUFFIX.length());
    }

    /** 快照根下全部块文件，按路径排序；根不存在时返回空列表。 */
    public static List<Path> listZpacks(Path snapshotRoot) throws IOException {
        List<Path> files = new ArrayList<>();
        if (snapshotRoot == null || !Files.isDirectory(snapshotRoot)) {
            return files;
        }
        try (Stream<Path> walk = Files.walk(snapshotRoot)) {
            walk.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(ZPACK_SUFFIX))
                    .sorted()
                    .forEach(files::add);
        }
        return files;
    }

    /** 世界存档文件夹名合法性：非空、不含分隔符/盘符、不是 {@code .}/{@code ..}。 */
    public static String requireWorldKey(String worldKey) {
        if (worldKey == null || worldKey.isEmpty()) {
            throw new IllegalArgumentException("empty world key");
        }
        if (worldKey.equals(".") || worldKey.equals("..")) {
            throw new IllegalArgumentException("invalid world key: " + worldKey);
        }
        if (worldKey.endsWith(".") || worldKey.endsWith(" ")) {
            throw new IllegalArgumentException("world key may not end with dot/space: " + worldKey);
        }
        for (int i = 0; i < worldKey.length(); i++) {
            char c = worldKey.charAt(i);
            if (c == '/' || c == '\\' || c == ':' || c == '\0') {
                throw new IllegalArgumentException("invalid world key: " + worldKey);
            }
        }
        return worldKey;
    }

    /**
     * 键规范化：反斜杠转正斜杠、折叠多重斜杠、去掉 {@code .} 段；
     * 拒绝绝对路径、盘符、{@code ..}、空键、NUL 与 {@code :}。
     */
    public static String normalizeKey(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("null key");
        }
        String slashed = raw.replace('\\', '/');
        if (slashed.isEmpty()) {
            throw new IllegalArgumentException("empty key");
        }
        if (slashed.charAt(0) == '/') {
            throw new IllegalArgumentException("absolute key: " + raw);
        }
        if (slashed.length() >= 2 && slashed.charAt(1) == ':') {
            throw new IllegalArgumentException("drive-qualified key: " + raw);
        }
        List<String> parts = new ArrayList<>();
        for (String part : slashed.split("/")) {
            if (part.isEmpty() || part.equals(".")) {
                continue;
            }
            if (part.equals("..")) {
                throw new IllegalArgumentException("parent segment in key: " + raw);
            }
            if (part.indexOf('\0') >= 0 || part.indexOf(':') >= 0) {
                throw new IllegalArgumentException("invalid character in key: " + raw);
            }
            parts.add(part);
        }
        if (parts.isEmpty()) {
            throw new IllegalArgumentException("empty key: " + raw);
        }
        return String.join("/", parts);
    }

    /**
     * 固定排除：session.lock、level.dat_old、restore.marker、临时文件、
     * 以及任何包含库目录名/快照根目录名的路径（纵深防御）。
     */
    public static boolean isExcluded(String relPath) {
        String key = normalizeKey(relPath);
        if (key.equals(RESTORE_MARKER_NAME) || key.equals(REJOIN_MARKER_NAME)) {
            return true;
        }
        String name = key.substring(key.lastIndexOf('/') + 1);
        if (name.equals("session.lock") || name.equals("level.dat_old")) {
            return true;
        }
        if (name.endsWith(AtomicFiles.TEMP_SUFFIX) || name.endsWith(COMPACT_TEMP_SUFFIX)) {
            return true;
        }
        for (String segment : key.split("/")) {
            if (segment.equals(VAULT_DIR_NAME) || segment.equals(SNAPSHOT_DIR_NAME)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 快照名（slot）合法性：1..64 字符、首字符字母数字、其余字母数字/{@code _}/{@code -}；
     * 因为 {@code .} 是 index.properties 的字段分隔符，所以点一律拒绝。
     */
    public static boolean isValidSnapshotName(String name) {
        if (name == null || name.isEmpty() || name.length() > 64) {
            return false;
        }
        char first = name.charAt(0);
        if (!isAsciiAlnum(first)) {
            return false;
        }
        for (int i = 1; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!isAsciiAlnum(c) && c != '_' && c != '-') {
                return false;
            }
        }
        return true;
    }

    private static boolean isAsciiAlnum(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
    }

    /** 自底向上删除 root 下的空目录（保留 root 本身）。 */
    public static void deleteEmptyDirs(Path root) throws IOException {
        if (root == null || !Files.isDirectory(root)) {
            return;
        }
        List<Path> dirs = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isDirectory).forEach(dirs::add);
        }
        dirs.sort(Comparator.reverseOrder());
        for (Path dir : dirs) {
            if (dir.equals(root)) {
                continue;
            }
            try (DirectoryStream<Path> children = Files.newDirectoryStream(dir)) {
                if (!children.iterator().hasNext()) {
                    Files.delete(dir);
                }
            }
        }
    }
}
