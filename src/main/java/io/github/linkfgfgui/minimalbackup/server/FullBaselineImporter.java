package io.github.linkfgfgui.minimalbackup.server;

import io.github.linkfgfgui.minimalbackup.engine.Log;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Optional full baseline: reads every region chunk through the live {@link ChunkMap}, which makes
 * the existing read-capture hook write block 1 for targets that do not have a block yet. No new
 * storage path and no Anvil parsing: the importer only forces reads.
 *
 * <p>Entity and POI region files are not imported here (their storages are not reachable from a
 * {@code ChunkMap}); those targets are still captured on their normal read/write hooks. The
 * command is meant for an initial, quiesced import, not for a running busy server.
 */
public final class FullBaselineImporter {
    private static final Log.Logger LOG = Log.LOGGER;

    /** Result counters. */
    public static final class Report {
        public long regions;
        public long chunksRead;
        public long chunksPresent;
        public long filesRead;
        public long filesCaptured;
        public long failures;
        public final List<String> errors = new ArrayList<>();
    }

    private FullBaselineImporter() {
    }

    public static Report run(BackupRuntime runtime, MinecraftServer server) {
        Report report = new Report();
        Path world = server.getWorldPath(LevelResource.ROOT);
        for (ServerLevel level : server.getAllLevels()) {
            Path dimensionRoot = dimensionRoot(world, level.dimension());
            Path regionDir = dimensionRoot.resolve("region");
            if (!Files.isDirectory(regionDir)) {
                continue;
            }
            ChunkMap map = level.getChunkSource().chunkMap;
            List<Path> files;
            try (Stream<Path> stream = Files.list(regionDir)) {
                files = stream.filter(path -> path.getFileName().toString().endsWith(".mca"))
                        .toList();
            } catch (IOException e) {
                report.failures++;
                report.errors.add(regionDir + ": " + e.getMessage());
                continue;
            }
            for (Path file : files) {
                int[] region = parseRegion(file.getFileName().toString());
                if (region == null) {
                    continue;
                }
                report.regions++;
                List<CompletableFuture<Optional<CompoundTag>>> futures = new ArrayList<>(1024);
                for (int dx = 0; dx < 32; dx++) {
                    for (int dz = 0; dz < 32; dz++) {
                        futures.add(map.read(new ChunkPos(region[0] * 32 + dx,
                                region[1] * 32 + dz)));
                    }
                }
                for (CompletableFuture<Optional<CompoundTag>> future : futures) {
                    report.chunksRead++;
                    try {
                        if (future.join().isPresent()) {
                            report.chunksPresent++;
                        }
                    } catch (RuntimeException e) {
                        report.failures++;
                        if (report.errors.size() < 20) {
                            report.errors.add(file.getFileName() + ": " + e.getMessage());
                        }
                    }
                }
            }
        }

        for (Path file : List.of(world.resolve("level.dat"))) {
            readFile(runtime, file, report);
        }
        for (String dir : List.of("data", "playerdata")) {
            Path folder = world.resolve(dir);
            if (!Files.isDirectory(folder)) {
                continue;
            }
            try (Stream<Path> stream = Files.list(folder)) {
                for (Path file : stream.filter(path -> path.getFileName().toString().endsWith(".dat"))
                        .toList()) {
                    readFile(runtime, file, report);
                }
            } catch (IOException e) {
                report.failures++;
                report.errors.add(folder + ": " + e.getMessage());
            }
        }
        LOG.info("full baseline: regions={} chunksRead={} chunksPresent={} filesRead={} "
                        + "filesCaptured={} failures={}", report.regions, report.chunksRead,
                report.chunksPresent, report.filesRead, report.filesCaptured, report.failures);
        return report;
    }

    private static void readFile(BackupRuntime runtime, Path file, Report report) {
        if (!Files.isRegularFile(file)) {
            return;
        }
        report.filesRead++;
        try {
            CompoundTag tag = PlatformNbt.read(file);
            if (runtime.captureFullBaselineFile(file, tag)) {
                report.filesCaptured++;
            }
        } catch (IOException | RuntimeException e) {
            report.failures++;
            if (report.errors.size() < 20) {
                report.errors.add(file.getFileName() + ": " + e.getMessage());
            }
        }
    }

    static Path dimensionRoot(Path world, ResourceKey<Level> dimension) {
        ResourceLocation id = dimension.location();
        if (id.equals(Level.OVERWORLD.location())) {
            return world;
        }
        if (id.equals(Level.NETHER.location())) {
            return world.resolve("DIM-1");
        }
        if (id.equals(Level.END.location())) {
            return world.resolve("DIM1");
        }
        return world.resolve("dimensions").resolve(id.getNamespace()).resolve(id.getPath());
    }

    /** Parses {@code r.X.Z.mca}; returns null for anything else. */
    static int[] parseRegion(String name) {
        if (!name.startsWith("r.") || !name.endsWith(".mca")) {
            return null;
        }
        String[] parts = name.substring(2, name.length() - 4).split("\\.");
        if (parts.length != 2) {
            return null;
        }
        try {
            return new int[]{Integer.parseInt(parts[0]), Integer.parseInt(parts[1])};
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
