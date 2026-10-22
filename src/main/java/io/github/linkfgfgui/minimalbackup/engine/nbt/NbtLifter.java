package io.github.linkfgfgui.minimalbackup.engine.nbt;

import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;

/**
 * 语义提升：只做调色板容器（{@code PalettedContainerTag}）。
 *
 * <p>套娃 {@code TAG_Byte_Array} 提升（§三.1）按 PLAN 1 D1 不做：{@code byte[]} 在本系统里是
 * 不透明数组，拆开重压缩必然改变存储字节。
 *
 * <p>提升判据取严版（D2）：解包 → 按原版格式重新打包必须与原 {@code long[]} <b>逐字节相同</b>，
 * 否则退回普通 Compound。这让规范化对调色板是字节 no-op。
 */
public final class NbtLifter {
    private static final int BLOCK_COUNT = 4096;
    private static final int BIOME_COUNT = 64;

    private NbtLifter() {
    }

    /** 自底向上提升，返回可能被替换的根。 */
    public static NbtTag lift(NbtTag tag) {
        if (tag instanceof NbtTag.CompoundTag compound) {
            for (Map.Entry<String, NbtTag> entry : compound.children().entrySet()) {
                entry.setValue(lift(entry.getValue()));
            }
            NbtTag.PalettedContainerTag palette = tryPalette(compound);
            return palette != null ? palette : compound;
        }
        if (tag instanceof NbtTag.ListTag list) {
            for (int i = 0; i < list.size(); i++) {
                list.elements().set(i, lift(list.get(i)));
            }
        }
        return tag;
    }

    private static NbtTag.PalettedContainerTag tryPalette(NbtTag.CompoundTag compound) {
        NbtTag paletteTag = compound.get("palette");
        NbtTag dataTag = compound.get("data");
        if (!(paletteTag instanceof NbtTag.ListTag palette)
                || palette.elemTypeId() != NbtTag.TAG_COMPOUND
                || !(dataTag instanceof NbtTag.LongArrayTag data)
                || palette.size() == 0) {
            return null;
        }
        int states = palette.size();
        int blockBits = NbtWriter.bitsFor(states, 4);
        NbtTag.PalettedContainerTag lifted = tryLayout(compound, palette, data, BLOCK_COUNT, blockBits, 4);
        if (lifted != null) {
            return lifted;
        }
        int biomeBits = NbtWriter.bitsFor(states, 1);
        return tryLayout(compound, palette, data, BIOME_COUNT, biomeBits, 1);
    }

    private static NbtTag.PalettedContainerTag tryLayout(NbtTag.CompoundTag compound,
                                                         NbtTag.ListTag palette,
                                                         NbtTag.LongArrayTag data,
                                                         int count, int bits, int minBits) {
        boolean nonSpanning;
        if (data.data().length == NbtWriter.packedLength(count, bits, true)) {
            nonSpanning = true;
        } else if (data.data().length == NbtWriter.packedLength(count, bits, false)) {
            nonSpanning = false;
        } else {
            return null;
        }
        int[] indices;
        try {
            indices = NbtWriter.unpackIndices(data.data(), count, bits, nonSpanning);
        } catch (ArrayIndexOutOfBoundsException e) {
            return null;
        }
        for (int index : indices) {
            if (index < 0 || index >= palette.size()) {
                return null;
            }
        }
        long[] repacked = NbtWriter.packIndices(indices, count, bits, nonSpanning);
        if (!Arrays.equals(repacked, data.data())) {
            return null;
        }
        TreeMap<String, NbtTag> extra = new TreeMap<>(compound.children());
        extra.remove("palette");
        extra.remove("data");
        return new NbtTag.PalettedContainerTag(palette, indices, nonSpanning, count, minBits, extra);
    }
}
