package io.github.linkfgfgui.minimalbackup.engine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 一个 {@code .zpack} 的追加、扫描、截断与压实；引擎里唯一直接做块文件 IO 的类。
 *
 * <p>文件布局：8 字节文件头 + 连续块区 + 可选索引缓存区。追加新块时新块写在旧缓存区
 * 的起始偏移上，新缓存区紧跟新块之后，因此旧缓存被完全覆盖、文件保持紧致。
 *
 * <p>缓存只是加速：缓存只保存每个 block header 的 offset（varint delta）；
 * 任何校验失败都退回顺序扫描。
 */
public final class BlockFile {
    public static final int FILE_HEADER_SIZE = 8;
    public static final int FILE_MAGIC = 0x4D425A50;
    public static final int FILE_VERSION = 1;
    private static final int CACHE_MAGIC = 0x4D424931;
    private static final int CACHE_HEADER_SIZE = 20;
    /**
     * Reject absurd cache lengths before allocating. A legitimate cache is one varint per
     * block; 64 MiB covers over 16 million blocks, far beyond any real {@code .zpack}.
     */
    private static final int MAX_CACHE_BYTES = 64 * 1024 * 1024;
    private static final byte[] EMPTY = new byte[0];

    private BlockFile() {
    }

    /** 顺序扫描结果。 */
    public static final class Scan {
        private final List<Block> blocks;
        private final long blockAreaEnd;
        private final long fileLength;

        private Scan(List<Block> blocks, long blockAreaEnd, long fileLength) {
            this.blocks = Collections.unmodifiableList(blocks);
            this.blockAreaEnd = blockAreaEnd;
            this.fileLength = fileLength;
        }

        public List<Block> blocks() {
            return blocks;
        }

        public long blockAreaEnd() {
            return blockAreaEnd;
        }

        public long fileLength() {
            return fileLength;
        }
    }

    /** 待追加的新块：块头 + 载荷。 */
    public static final class NewBlock {
        private Block block;
        private final byte[] payload;

        public NewBlock(Block block, byte[] payload) {
            if (block == null || payload == null) {
                throw new IllegalArgumentException("null block/payload");
            }
            if (block.payloadLength() != payload.length) {
                throw new IllegalArgumentException("payload length mismatch");
            }
            this.block = block;
            this.payload = payload;
        }

        public Block block() {
            return block;
        }

        public byte[] payload() {
            return payload;
        }
    }

    /** 8 字节文件头。 */
    public static byte[] fileHeader() {
        ByteBuffer buf = ByteBuffer.allocate(FILE_HEADER_SIZE).order(ByteOrder.BIG_ENDIAN);
        buf.putInt(FILE_MAGIC);
        buf.putShort((short) FILE_VERSION);
        buf.putShort((short) 0);
        return buf.array();
    }

    /** 文件不存在则原子创建（只含文件头）；存在则校验文件头。 */
    public static void ensureFile(Path file) throws IOException {
        if (Files.exists(file)) {
            long size = Files.size(file);
            if (size < FILE_HEADER_SIZE) {
                throw new IOException("truncated file header: " + file);
            }
            byte[] head = new byte[FILE_HEADER_SIZE];
            try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
                readFully(channel, ByteBuffer.wrap(head), 0L);
            }
            checkHeader(head, file);
            return;
        }
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        AtomicFiles.write(file, fileHeader());
    }

    /** 从文件头开始顺序扫描；遇到坏魔数/半截块/块号断裂即停。 */
    public static Scan scan(Path file) throws IOException {
        long length = Files.size(file);
        if (length < FILE_HEADER_SIZE) {
            throw new IOException("truncated file header: " + file);
        }
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            byte[] head = new byte[FILE_HEADER_SIZE];
            readFully(channel, ByteBuffer.wrap(head), 0L);
            checkHeader(head, file);
            List<Block> blocks = new ArrayList<>();
            ByteBuffer header = ByteBuffer.allocate(Block.HEADER_SIZE).order(ByteOrder.BIG_ENDIAN);
            long pos = FILE_HEADER_SIZE;
            int expected = 1;
            while (pos + Block.HEADER_SIZE <= length) {
                header.clear();
                readFully(channel, header, pos);
                header.flip();
                if (header.getInt() != Block.MAGIC) {
                    break;
                }
                int total = header.getInt(4);
                if (total < Block.HEADER_SIZE || pos + total > length) {
                    break;
                }
                int number = header.getInt(8);
                if (number != expected) {
                    break;
                }
                blocks.add(Block.decode(header.array(), 0).withOffset(pos));
                pos += total;
                expected++;
            }
            return new Scan(blocks, pos, length);
        }
    }

    /** 装载块表：优先用有效缓存区，否则退回顺序扫描。 */
    public static BlockTable loadTable(Path file, long cacheOffset) throws IOException {
        if (!Files.exists(file)) {
            throw new IOException("no such zpack: " + file);
        }
        List<Block> cached = readCache(file, cacheOffset);
        if (cached != null) {
            return BlockTable.fromBlocks(cached, cacheOffset);
        }
        Scan scan = scan(file);
        return BlockTable.fromBlocks(scan.blocks(), scan.blockAreaEnd());
    }

    /**
     * 读取并校验索引缓存区；任何不一致返回 null（调用方退回扫描）。
     * entry 只有 offset varint；块的其他字段一律以 block header 为准。
     */
    public static List<Block> readCache(Path file, long cacheOffset) throws IOException {
        if (cacheOffset < FILE_HEADER_SIZE) {
            return null;
        }
        long length = Files.size(file);
        if (cacheOffset + CACHE_HEADER_SIZE > length) {
            return null;
        }
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            ByteBuffer header = ByteBuffer.allocate(CACHE_HEADER_SIZE).order(ByteOrder.BIG_ENDIAN);
            readFully(channel, header, cacheOffset);
            header.flip();
            int magic = header.getInt();
            int count = header.getInt();
            int total = header.getInt();
            long storedHash = header.getLong();
            if (count <= 0 || total < CACHE_HEADER_SIZE) {
                return null;
            }
            if (cacheOffset + (long) total > length) {
                return null;
            }
            int dataLength = total - CACHE_HEADER_SIZE;
            if (dataLength > MAX_CACHE_BYTES || count > dataLength) {
                return null;
            }
            byte[] data = new byte[dataLength];
            if (dataLength > 0) {
                readFully(channel, ByteBuffer.wrap(data), cacheOffset + CACHE_HEADER_SIZE);
            }
            if (Xxh64.hash(data) != storedHash) {
                return null;
            }
            if (magic != CACHE_MAGIC) {
                return null;
            }
            return decodeCache(channel, data, count, cacheOffset);
        } catch (IOException e) {
            return null;
        }
    }

    /** 只存每个 header 的 offset（第一个绝对，后续为 delta）。 */
    private static List<Block> decodeCache(FileChannel channel, byte[] data, int count,
                                           long cacheOffset) throws IOException {
        VarintReader reader = new VarintReader(data);
        List<Block> blocks = new ArrayList<>(Math.min(count, 4096));
        long lastEnd = FILE_HEADER_SIZE;
        long previousOffset = 0L;
        for (int i = 0; i < count; i++) {
            long delta = reader.read();
            long offset;
            if (i == 0) {
                offset = delta;
            } else {
                if (delta <= 0L) {
                    return null;
                }
                try {
                    offset = Math.addExact(previousOffset, delta);
                } catch (ArithmeticException e) {
                    return null;
                }
            }
            if (offset < lastEnd || offset > cacheOffset - Block.HEADER_SIZE) {
                return null;
            }
            byte[] header = new byte[Block.HEADER_SIZE];
            readFully(channel, ByteBuffer.wrap(header), offset);
            Block block;
            try {
                block = Block.decode(header, 0);
            } catch (IOException bad) {
                return null;
            }
            if (block.number() != i + 1) {
                return null;
            }
            long end = offset + block.totalLength();
            if (end < offset || end > cacheOffset) {
                return null;
            }
            blocks.add(block.withOffset(offset));
            lastEnd = end;
            previousOffset = offset;
        }
        if (reader.hasRemaining()) {
            return null;
        }
        return blocks;
    }

    /** 有效缓存区总长；不是有效缓存则 -1。只校验头，不校验条目哈希。 */
    public static long cacheLength(Path file, long cacheOffset) throws IOException {
        if (cacheOffset < FILE_HEADER_SIZE) {
            return -1L;
        }
        long length = Files.size(file);
        if (cacheOffset + CACHE_HEADER_SIZE > length) {
            return -1L;
        }
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            ByteBuffer header = ByteBuffer.allocate(CACHE_HEADER_SIZE).order(ByteOrder.BIG_ENDIAN);
            readFully(channel, header, cacheOffset);
            header.flip();
            int magic = header.getInt();
            int count = header.getInt();
            int total = header.getInt();
            if (count <= 0 || total < CACHE_HEADER_SIZE) {
                return -1L;
            }
            if (magic != CACHE_MAGIC) {
                return -1L;
            }
            if (total > MAX_CACHE_BYTES || count > total - CACHE_HEADER_SIZE) {
                return -1L;
            }
            if (cacheOffset + (long) total > length) {
                return -1L;
            }
            return total;
        }
    }

    /**
     * 追加新块并重写索引缓存区。
     *
     * @param file        目标块文件（不存在则创建）
     * @param table       当前块表（会被原地更新）
     * @param newBlocks        新块，块号必须从 {@code table.nextBlockNumber()} 开始严格 +1
     * @param cacheOffset      旧缓存区偏移（-1 或失效则从块区末尾写）
     * @param verifyAfterWrite 写完块区后回读校验头部与载荷
     * @return 新缓存区偏移（等于新块区末尾）
     */
    public static long append(Path file, BlockTable table, List<NewBlock> newBlocks,
                              long cacheOffset, boolean verifyAfterWrite) throws IOException {
        ensureFile(file);
        if (newBlocks.isEmpty()) {
            return cacheOffset;
        }
        long length = Files.size(file);
        long blockAreaEnd = table.blockAreaEnd();
        if (blockAreaEnd > length) {
            throw new IOException("block area end beyond file length: " + file);
        }
        if (table.blocks().isEmpty() && length > FILE_HEADER_SIZE) {
            throw new IOException("refusing to append with an empty table to a non-empty file: " + file);
        }
        long writePos = cacheOffset == blockAreaEnd ? cacheOffset : blockAreaEnd;
        int expected = table.nextBlockNumber();
        for (NewBlock newBlock : newBlocks) {
            if (newBlock.block().number() != expected) {
                throw new IOException("block number " + newBlock.block().number() + " != expected " + expected);
            }
            expected++;
        }
        try (FileChannel channel = FileChannel.open(file,
                StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            if (length > writePos) {
                channel.truncate(writePos);
            }
            long pos = writePos;
            for (NewBlock newBlock : newBlocks) {
                Block block = newBlock.block().withOffset(pos);
                newBlock.block = block;
                writeFully(channel, ByteBuffer.wrap(block.encodeHeader()), pos);
                if (block.payloadLength() > 0) {
                    writeFully(channel, ByteBuffer.wrap(newBlock.payload()), pos + Block.HEADER_SIZE);
                }
                pos += block.totalLength();
                table.add(block);
            }
            table.setBlockAreaEnd(pos);
            writeFully(channel, ByteBuffer.wrap(encodeCache(table.blocks())), pos);
            channel.force(true);
            if (verifyAfterWrite) {
                for (NewBlock newBlock : newBlocks) {
                    Block block = newBlock.block();
                    byte[] header = new byte[Block.HEADER_SIZE];
                    readFully(channel, ByteBuffer.wrap(header), block.offset());
                    if (!Arrays.equals(header, block.encodeHeader())) {
                        throw new IOException("verify-after-write: header mismatch at block "
                                + block.number());
                    }
                    if (block.payloadLength() > 0) {
                        byte[] stored = new byte[block.payloadLength()];
                        readFully(channel, ByteBuffer.wrap(stored),
                                block.offset() + Block.HEADER_SIZE);
                        if (!Arrays.equals(stored, newBlock.payload())) {
                            throw new IOException("verify-after-write: payload mismatch at block "
                                    + block.number());
                        }
                    }
                }
            }
            return pos;
        }
    }

    /** 读块载荷。 */
    public static byte[] readPayload(Path file, Block block) throws IOException {
        int length = block.payloadLength();
        if (length == 0) {
            return EMPTY;
        }
        byte[] payload = new byte[length];
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            readFully(channel, ByteBuffer.wrap(payload), block.offset() + Block.HEADER_SIZE);
        }
        return payload;
    }

    /** 沿字典链解压到 FULL，逐块校验 XXH64 与长度。 */
    public static byte[] reconstruct(Path file, BlockTable table, Block block) throws IOException {
        List<Block> chain = new ArrayList<>();
        Block current = block;
        while (true) {
            chain.add(current);
            if (current.dictionary() == 0) {
                break;
            }
            Block previous = table.byNumber(current.dictionary());
            if (previous == null) {
                throw new IOException("missing dictionary block " + current.dictionary()
                        + " for block " + current.number());
            }
            current = previous;
            if (chain.size() > 100_000) {
                throw new IOException("dictionary cycle at block " + block.number());
            }
        }
        Collections.reverse(chain);
        byte[] data = null;
        for (Block step : chain) {
            if (step.mode() == Block.MODE_ABSENT) {
                throw new IOException("ABSENT block in dictionary chain");
            }
            byte[] payload = readPayload(file, step);
            data = step.mode() == Block.MODE_FULL
                    ? Codec.decompress(payload, null, step.originalLength(), step.subTargetType())
                    : Codec.decompress(payload, data, step.originalLength(), step.subTargetType());
            if (data.length != step.originalLength()) {
                throw new IOException("length mismatch for block " + step.number());
            }
            if (Xxh64.hash(data) != step.hash()) {
                throw new IOException("hash mismatch for block " + step.number());
            }
        }
        return data;
    }

    /**
     * 逐文件压实：按给定存活块顺序重编号、重指字典，写临时文件后原子替换。
     *
     * @return 新缓存区偏移
     */
    public static long compact(Path file, List<Block> liveBlocks) throws IOException {
        if (liveBlocks.isEmpty()) {
            throw new IllegalArgumentException("no live blocks");
        }
        Map<Integer, Integer> renumber = new HashMap<>();
        int next = 1;
        for (Block block : liveBlocks) {
            renumber.put(block.number(), next++);
        }
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temp = file.resolveSibling(file.getFileName().toString() + VaultPaths.COMPACT_TEMP_SUFFIX);
        long cacheOffset;
        try {
            try (FileChannel out = FileChannel.open(temp,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                writeFully(out, ByteBuffer.wrap(fileHeader()), 0L);
                long pos = FILE_HEADER_SIZE;
                List<Block> written = new ArrayList<>(liveBlocks.size());
                for (Block block : liveBlocks) {
                    Integer newNumber = renumber.get(block.number());
                    int newDictionary = 0;
                    if (block.dictionary() != 0) {
                        Integer mapped = renumber.get(block.dictionary());
                        if (mapped == null) {
                            throw new IOException("live block " + block.number()
                                    + " references dead dictionary " + block.dictionary());
                        }
                        newDictionary = mapped;
                    }
                    byte[] payload = block.mode() == Block.MODE_ABSENT ? EMPTY : readPayload(file, block);
                    Block rewritten = block.renumber(newNumber, newDictionary).withOffset(pos);
                    writeFully(out, ByteBuffer.wrap(rewritten.encodeHeader()), pos);
                    if (payload.length > 0) {
                        writeFully(out, ByteBuffer.wrap(payload), pos + Block.HEADER_SIZE);
                    }
                    pos += rewritten.totalLength();
                    written.add(rewritten);
                }
                writeFully(out, ByteBuffer.wrap(encodeCache(written)), pos);
                out.force(true);
                cacheOffset = pos;
            }
            AtomicFiles.commit(temp, file);
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(temp);
            throw e;
        }
        return cacheOffset;
    }

    /** 截断到指定长度（只缩短）。 */
    public static void truncate(Path file, long size) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            if (Files.size(file) > size) {
                channel.truncate(size);
            }
        }
    }

    /**
     * 每个 block 只写 offset；第一个 offset 是绝对值，后续是相对前一个的 delta。
     * 块的其他字段从 block header 读取，不再重复存储。
     */
    private static byte[] encodeCache(List<Block> blocks) {
        ByteArrayOutputStream entries = new ByteArrayOutputStream(Math.max(16, blocks.size() * 2));
        long previousOffset = 0L;
        for (int i = 0; i < blocks.size(); i++) {
            Block block = blocks.get(i);
            long offset = block.offset();
            long delta = i == 0 ? offset : offset - previousOffset;
            if (delta < 0L) {
                throw new IllegalStateException("cache offsets are not monotonic");
            }
            writeVarLong(entries, delta);
            previousOffset = offset;
        }
        byte[] data = entries.toByteArray();
        if (data.length > MAX_CACHE_BYTES) {
            throw new IllegalStateException("cache too large: " + data.length);
        }
        ByteBuffer area = ByteBuffer.allocate(CACHE_HEADER_SIZE + data.length).order(ByteOrder.BIG_ENDIAN);
        area.putInt(CACHE_MAGIC);
        area.putInt(blocks.size());
        area.putInt(CACHE_HEADER_SIZE + data.length);
        area.putLong(Xxh64.hash(data));
        area.put(data);
        return area.array();
    }

    private static void writeVarLong(ByteArrayOutputStream out, long value) {
        if (value < 0L) {
            throw new IllegalArgumentException("negative varint");
        }
        while ((value & ~0x7FL) != 0L) {
            out.write((int) ((value & 0x7FL) | 0x80L));
            value >>>= 7;
        }
        out.write((int) value);
    }

    /** Unsigned LEB128 reader with overflow checks. */
    private static final class VarintReader {
        private final byte[] data;
        private int position;

        VarintReader(byte[] data) {
            this.data = data;
        }

        boolean hasRemaining() {
            return position < data.length;
        }

        long read() throws IOException {
            long value = 0L;
            int shift = 0;
            while (true) {
                if (position >= data.length) {
                    throw new IOException("truncated varint");
                }
                int b = data[position++] & 0xFF;
                if (shift == 63 && (b & 0x7E) != 0) {
                    throw new IOException("varint overflow");
                }
                value |= (long) (b & 0x7F) << shift;
                if (value < 0L) {
                    throw new IOException("varint overflow");
                }
                if ((b & 0x80) == 0) {
                    return value;
                }
                shift += 7;
                if (shift > 63) {
                    throw new IOException("varint overflow");
                }
            }
        }
    }

    private static void checkHeader(byte[] header, Path file) throws IOException {
        ByteBuffer buf = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN);
        if (buf.getInt() != FILE_MAGIC) {
            throw new IOException("bad file magic: " + file);
        }
        int version = buf.getShort() & 0xFFFF;
        if (version != FILE_VERSION) {
            throw new IOException("unsupported file version " + version + ": " + file);
        }
        buf.getShort();
    }

    private static void readFully(FileChannel channel, ByteBuffer buffer, long position) throws IOException {
        long pos = position;
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer, pos);
            if (read < 0) {
                throw new IOException("unexpected end of file");
            }
            pos += read;
        }
    }

    private static void writeFully(FileChannel channel, ByteBuffer buffer, long position) throws IOException {
        long pos = position;
        while (buffer.hasRemaining()) {
            int written = channel.write(buffer, pos);
            if (written <= 0) {
                throw new IOException("short write");
            }
            pos += written;
        }
    }
}
