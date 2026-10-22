package io.github.linkfgfgui.minimalbackup.engine.nbt;

import java.io.IOException;

/**
 * 规范化的两个纯函数（PLAN 1 §2）。
 *
 * <ul>
 *   <li>{@link #canonicalize(byte[])}：嗅探 → parse → lift → write；非 NBT 原样返回。</li>
 *   <li>{@link #alignCanonical(byte[], Result)}：解析旧基底、对齐新树、写出对齐后的新字节。</li>
 * </ul>
 *
 * <p>数据流是"字节 → 树 → 字节"。新值这一侧的树由 {@link #canonicalize} 产出并一路带到
 * {@link #alignCanonical}，所以 delta 目标每侧只解析一次（旧一侧一次、新一侧一次）。
 *
 * <p><b>契约</b>：delta 路径上 {@link #alignCanonical} 必须无条件调用
 * {@link TreeAligner#align(NbtTag, NbtTag)}，不允许"看起来没有可对齐的容器"就短路返回。
 * 附属 codec 用 {@code @Redirect} 挂在这个调用上取走两棵树；任何快速返回都会让附属静默退回
 * 自己解析，或拿到错代的树。
 */
public final class NbtCanon {
    private NbtCanon() {
    }

    /** 规范化结果：存储字节 + 产生它的树（非 NBT 时树为 null）。 */
    public static final class Result {
        private final byte[] bytes;
        private final NbtTag tree;
        private final boolean nbt;

        private Result(byte[] bytes, NbtTag tree, boolean nbt) {
            this.bytes = bytes;
            this.tree = tree;
            this.nbt = nbt;
        }

        public byte[] bytes() {
            return bytes;
        }

        public NbtTag tree() {
            return tree;
        }

        public boolean isNbt() {
            return nbt;
        }
    }

    /**
     * 入口嗅探：{@code 0x0A 0x00 0x00} = TAG_Compound + 空根名（{@code NbtIo.write} 的产物）。
     * CLI 存进去的整块 {@code .mca}、gzip 的 {@code .dat}、ASCII 假夹具都过不了这一关。
     */
    public static boolean looksLikeNbt(byte[] raw) {
        return raw != null && raw.length >= 3
                && raw[0] == NbtTag.TAG_COMPOUND && raw[1] == 0 && raw[2] == 0;
    }

    /** 规范化任意载荷；非裸 NBT 或解析失败时逐字节原样返回。 */
    public static Result canonicalize(byte[] raw) {
        if (!looksLikeNbt(raw)) {
            return new Result(raw, null, false);
        }
        try {
            NbtTag tree = NbtReader.read(raw);
            NbtLifter.lift(tree);
            return new Result(NbtWriter.write(tree), tree, true);
        } catch (IOException | RuntimeException e) {
            return new Result(raw, null, false);
        }
    }

    /** 便利入口：只要字节。 */
    public static byte[] canonicalizeBytes(byte[] raw) {
        return canonicalize(raw).bytes();
    }

    /**
     * 把已经规范化的新值对齐到旧基底上并写出。
     *
     * @param canonicalBase 上一个已存块的存储字节（规范化+对齐后的字节）
     * @param newCanon      {@link #canonicalize(byte[])} 的结果（复用其中的树）
     */
    public static byte[] alignCanonical(byte[] canonicalBase, Result newCanon) throws IOException {
        if (newCanon.tree() == null) {
            // No tree on the new side: there is nothing an addon could capture.
            return newCanon.bytes();
        }
        NbtTag oldTree = null;
        if (looksLikeNbt(canonicalBase)) {
            try {
                oldTree = NbtReader.read(canonicalBase);
                NbtLifter.lift(oldTree);
            } catch (IOException | RuntimeException e) {
                oldTree = null;
            }
        }
        // Unconditional by contract (PLAN 1 §2.1): an addon redirects this call and must see it
        // even when the base is not NBT (oldTree == null). A fast return here would silently
        // remove the addon's capture seam.
        NbtTag aligned = TreeAligner.align(oldTree, newCanon.tree());
        return NbtWriter.write(aligned);
    }

    /** 便利入口：两侧都是字节（测试与非 Checkpoint 调用方使用，会多解析一次新值）。 */
    public static byte[] alignCanonical(byte[] canonicalBase, byte[] canonicalNew)
            throws IOException {
        return alignCanonical(canonicalBase, canonicalize(canonicalNew));
    }
}
