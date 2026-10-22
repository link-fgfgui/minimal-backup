package io.github.linkfgfgui.minimalbackup.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 一个块文件的内存块表：子目标 → 按块号有序的块；块号 → 块。
 *
 * <p>由 {@link BlockFile} 从索引缓存区或顺序扫描装载；只做内存操作，不碰 IO。
 */
public final class BlockTable {
    private final List<Block> blocks = new ArrayList<>();
    private final Map<Long, List<Block>> byTarget = new HashMap<>();
    private final Map<Integer, Block> byNumber = new HashMap<>();
    private long blockAreaEnd;

    private BlockTable(long blockAreaEnd) {
        this.blockAreaEnd = blockAreaEnd;
    }

    /** 空表，块区末尾等于文件头长度。 */
    public static BlockTable empty() {
        return new BlockTable(BlockFile.FILE_HEADER_SIZE);
    }

    /** 从有序块列表建表；块号必须从 1 开始严格 +1。 */
    public static BlockTable fromBlocks(List<Block> blocks, long blockAreaEnd) {
        BlockTable table = new BlockTable(blockAreaEnd);
        for (Block block : blocks) {
            table.add(block);
        }
        return table;
    }

    /** 追加一块；块号必须等于 {@link #nextBlockNumber()}。 */
    public void add(Block block) {
        int expected = nextBlockNumber();
        if (block.number() != expected) {
            throw new IllegalArgumentException("block number " + block.number() + " != expected " + expected);
        }
        blocks.add(block);
        byNumber.put(block.number(), block);
        byTarget.computeIfAbsent(block.subTarget(), key -> new ArrayList<>()).add(block);
    }

    /** 按文件顺序返回全部块。 */
    public List<Block> blocks() {
        return Collections.unmodifiableList(blocks);
    }

    /** 某子目标的全部块（按块号升序）；没有则空列表。 */
    public List<Block> blocksFor(long subTarget) {
        List<Block> list = byTarget.get(subTarget);
        return list == null ? Collections.emptyList() : Collections.unmodifiableList(list);
    }

    /** 某子目标指定类型的全部块（按块号升序）；没有则空列表。 */
    public List<Block> blocksFor(long subTarget, int subTargetType) {
        requireType(subTargetType);
        List<Block> list = byTarget.get(subTarget);
        if (list == null) {
            return Collections.emptyList();
        }
        List<Block> filtered = new ArrayList<>();
        for (Block block : list) {
            if (block.subTargetType() == subTargetType) {
                filtered.add(block);
            }
        }
        return Collections.unmodifiableList(filtered);
    }

    /** 某子目标最后一块；没有则 null。 */
    public Block lastFor(long subTarget) {
        List<Block> list = byTarget.get(subTarget);
        return list == null || list.isEmpty() ? null : list.get(list.size() - 1);
    }

    /** 全部子目标。 */
    public Set<Long> subTargets() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(byTarget.keySet()));
    }

    /** 指定类型出现过的子目标，按块首次出现顺序。 */
    public Set<Long> subTargets(int subTargetType) {
        requireType(subTargetType);
        Set<Long> targets = new LinkedHashSet<>();
        for (Block block : blocks) {
            if (block.subTargetType() == subTargetType) {
                targets.add(block.subTarget());
            }
        }
        return Collections.unmodifiableSet(targets);
    }

    /** 表中是否有指定类型的块。 */
    public boolean hasBlockOfType(int subTargetType) {
        requireType(subTargetType);
        for (Block block : blocks) {
            if (block.subTargetType() == subTargetType) {
                return true;
            }
        }
        return false;
    }

    private static void requireType(int subTargetType) {
        if (subTargetType != Block.TYPE_FILE && subTargetType != Block.TYPE_CHUNK) {
            throw new IllegalArgumentException("bad sub-target type: " + subTargetType);
        }
    }

    public Block byNumber(int number) {
        return byNumber.get(number);
    }

    public int nextBlockNumber() {
        return blocks.isEmpty() ? 1 : blocks.get(blocks.size() - 1).number() + 1;
    }

    /** 块区末尾（不含可选索引缓存区）。 */
    public long blockAreaEnd() {
        return blockAreaEnd;
    }

    void setBlockAreaEnd(long end) {
        this.blockAreaEnd = end;
    }
}
