package io.github.linkfgfgui.minimalbackup.engine.nbt;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 双树对齐：只对同路径同位置的调色板容器重排 {@code new} 的调色板，
 * 让两代共有的方块状态尽量落在 {@code old} 里的原下标上。
 *
 * <p>这是本体暴露给附属 codec 的<b>唯一</b>捕获缝（PLAN 1 §2.1）：delta 路径必须无条件调用
 * {@link #align(NbtTag, NbtTag)}，即使看上去没有可对齐的容器。附属用 {@code @Redirect} 挂在
 * 这个调用上，拿到对齐前后的两棵树，从而在编码侧一次解析都不付。
 */
public final class TreeAligner {
    private static final AtomicLong ALIGN_CALLS = new AtomicLong();

    private TreeAligner() {
    }

    /** 已执行的对齐次数（测试用：断言 delta 路径确实走到了这里）。 */
    public static long alignCount() {
        return ALIGN_CALLS.get();
    }

    public static void resetAlignCount() {
        ALIGN_CALLS.set(0L);
    }

    /** 就地重排 {@code newTree}，返回同一个实例；永远不做短路。 */
    public static NbtTag align(NbtTag oldTree, NbtTag newTree) {
        ALIGN_CALLS.incrementAndGet();
        if (oldTree != null && newTree != null) {
            alignTag(oldTree, newTree);
        }
        return newTree;
    }

    private static void alignTag(NbtTag oldTag, NbtTag newTag) {
        if (oldTag instanceof NbtTag.PalettedContainerTag oldPalette
                && newTag instanceof NbtTag.PalettedContainerTag newPalette) {
            alignPalette(oldPalette, newPalette);
            return;
        }
        if (oldTag instanceof NbtTag.CompoundTag oldCompound
                && newTag instanceof NbtTag.CompoundTag newCompound) {
            for (Map.Entry<String, NbtTag> entry : newCompound.children().entrySet()) {
                NbtTag oldValue = oldCompound.get(entry.getKey());
                if (oldValue != null) {
                    alignTag(oldValue, entry.getValue());
                }
            }
            return;
        }
        if (oldTag instanceof NbtTag.ListTag oldList && newTag instanceof NbtTag.ListTag newList
                && oldList.elemTypeId() == newList.elemTypeId()) {
            int limit = Math.min(oldList.size(), newList.size());
            for (int i = 0; i < limit; i++) {
                alignTag(oldList.get(i), newList.get(i));
            }
        }
    }

    private static void alignPalette(NbtTag.PalettedContainerTag oldTag,
                                     NbtTag.PalettedContainerTag newTag) {
        List<NbtTag> oldPalette = oldTag.palette().elements();
        List<NbtTag> newPalette = newTag.palette().elements();
        int size = newPalette.size();
        if (size == 0 || oldPalette.isEmpty()) {
            return;
        }
        Map<NbtTag, Integer> oldIndex = new HashMap<>();
        for (int i = 0; i < oldPalette.size(); i++) {
            oldIndex.putIfAbsent(oldPalette.get(i), i);
        }
        NbtTag[] aligned = new NbtTag[size];
        boolean[] taken = new boolean[size];
        int[] remap = new int[size];
        Arrays.fill(remap, -1);
        List<Integer> deferred = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            Integer target = oldIndex.get(newPalette.get(i));
            if (target != null && target < size && !taken[target]) {
                aligned[target] = newPalette.get(i);
                taken[target] = true;
                remap[i] = target;
            } else {
                deferred.add(i);
            }
        }
        int slot = 0;
        for (int i : deferred) {
            while (slot < size && taken[slot]) {
                slot++;
            }
            if (slot >= size) {
                return;
            }
            aligned[slot] = newPalette.get(i);
            taken[slot] = true;
            remap[i] = slot;
        }
        int[] indices = newTag.indices();
        for (int index : indices) {
            if (index < 0 || index >= size || remap[index] < 0) {
                return;
            }
        }
        for (int i = 0; i < indices.length; i++) {
            indices[i] = remap[indices[i]];
        }
        newTag.palette(new NbtTag.ListTag(NbtTag.TAG_COMPOUND, new ArrayList<>(Arrays.asList(aligned))));
    }
}
