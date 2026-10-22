package io.github.linkfgfgui.minimalbackup.engine.anvil;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * 顺序写出一个 Anvil region 临时文件：区块数据从扇区 2 开始追加，{@link #finish()} 回填
 * 8 KiB header 并 force。
 */
public final class AnvilRegionWriter implements AutoCloseable {
    private static final byte[] ZERO = new byte[AnvilRegion.SECTOR_BYTES];

    private final FileChannel channel;
    private final int[] locations = new int[AnvilRegion.CHUNK_COUNT];
    private final int[] timestamps = new int[AnvilRegion.CHUNK_COUNT];
    private final boolean[] assigned = new boolean[AnvilRegion.CHUNK_COUNT];
    private long position = AnvilRegion.HEADER_BYTES;
    private int nextSector = AnvilRegion.HEADER_BYTES / AnvilRegion.SECTOR_BYTES;
    private boolean finished;

    public AnvilRegionWriter(Path tempFile) throws IOException {
        Path parent = tempFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        channel = FileChannel.open(tempFile,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
    }

    /** {@code chunkBytes} 是完整的 on-disk 记录：BE32(length) + type + payload，不含补齐。 */
    public void writeChunk(int index, byte[] chunkBytes, int timestamp) throws IOException {
        ensureWritable(index);
        if (chunkBytes == null || chunkBytes.length < 5) {
            throw new IOException("short chunk record");
        }
        int declared = ByteBuffer.wrap(chunkBytes, 0, 4)
                .order(ByteOrder.BIG_ENDIAN).getInt();
        if (declared != chunkBytes.length - 4) {
            throw new IOException("bad chunk length: " + declared);
        }
        int sectors = (chunkBytes.length + AnvilRegion.SECTOR_BYTES - 1)
                / AnvilRegion.SECTOR_BYTES;
        if (sectors > AnvilRegion.MAX_INTERNAL_SECTORS) {
            throw new IOException("chunk needs " + sectors + " sectors; external required");
        }
        int sectorOffset = nextSector;
        if (sectorOffset > 0xFFFFFF) {
            throw new IOException("region exceeds 24-bit sector offset");
        }
        writeFully(chunkBytes, position);
        long end = position + (long) sectors * AnvilRegion.SECTOR_BYTES;
        writeZeros(position + chunkBytes.length, end - position - chunkBytes.length);
        locations[index] = AnvilRegion.packLocation(sectorOffset, sectors);
        timestamps[index] = timestamp;
        assigned[index] = true;
        position = end;
        nextSector = sectorOffset + sectors;
    }

    /** 位置保持 0，只记录删除时间。 */
    public void clear(int index, int timestamp) {
        ensureWritable(index);
        timestamps[index] = timestamp;
        assigned[index] = true;
    }

    /** 回填 header 并 force。 */
    public void finish() throws IOException {
        if (finished) {
            return;
        }
        ByteBuffer header = ByteBuffer.allocate(AnvilRegion.HEADER_BYTES)
                .order(ByteOrder.BIG_ENDIAN);
        for (int location : locations) {
            header.putInt(location);
        }
        for (int timestamp : timestamps) {
            header.putInt(timestamp);
        }
        header.flip();
        writeFully(header, 0L);
        channel.force(true);
        finished = true;
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }

    private void ensureWritable(int index) {
        if (index < 0 || index >= AnvilRegion.CHUNK_COUNT) {
            throw new IllegalArgumentException("bad chunk index: " + index);
        }
        if (finished) {
            throw new IllegalStateException("writer already finished");
        }
        if (assigned[index]) {
            throw new IllegalArgumentException("chunk index already written: " + index);
        }
        if (!channel.isOpen()) {
            throw new IllegalStateException("writer closed");
        }
    }

    private void writeZeros(long offset, long count) throws IOException {
        long remaining = count;
        while (remaining > 0L) {
            int chunk = (int) Math.min(remaining, ZERO.length);
            writeFully(ByteBuffer.wrap(ZERO, 0, chunk), offset);
            offset += chunk;
            remaining -= chunk;
        }
    }

    private void writeFully(byte[] bytes, long offset) throws IOException {
        writeFully(ByteBuffer.wrap(bytes), offset);
    }

    private void writeFully(ByteBuffer buffer, long offset) throws IOException {
        long pos = offset;
        while (buffer.hasRemaining()) {
            int written = channel.write(buffer, pos);
            if (written <= 0) {
                throw new IOException("short write");
            }
            pos += written;
        }
    }
}
