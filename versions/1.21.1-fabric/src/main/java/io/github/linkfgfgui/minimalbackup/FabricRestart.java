package io.github.linkfgfgui.minimalbackup;

import com.mojang.authlib.yggdrasil.YggdrasilAuthenticationService;
import com.mojang.serialization.Dynamic;
import io.github.linkfgfgui.minimalbackup.engine.Log;
import io.github.linkfgfgui.minimalbackup.server.BackupRuntime;
import io.github.linkfgfgui.minimalbackup.server.RestoreHold;
import io.github.linkfgfgui.minimalbackup.server.RestoreSwap;
import java.net.Proxy;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.Util;
import net.minecraft.commands.Commands;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.Services;
import net.minecraft.server.WorldLoader;
import net.minecraft.server.WorldStem;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.dedicated.DedicatedServerSettings;
import net.minecraft.server.level.progress.LoggerChunkProgressListener;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.storage.LevelDataAndDimensions;
import net.minecraft.world.level.storage.LevelStorageSource;

/**
 * Replaces a 1.21.1 Fabric dedicated server instance in the same JVM after a restore.
 *
 * <p>Identical to the NeoForge supervisor: the old instance stops normally (releasing the level,
 * its storage lock and the port), this thread waits for its thread to die and boots a new
 * {@link DedicatedServer} from the same world directory. {@code BackupRuntime.init} picks the
 * restore marker up while the level loads, so the new instance starts from the snapshot.
 * {@code UtilMixin} suppresses {@code Util.shutdownExecutors()} while {@link RestoreSwap} is set,
 * and {@code RestoreHold} keeps the player connections open across the gap.
 */
public final class FabricRestart {
    private static final long OLD_THREAD_TIMEOUT_MILLIS = 180_000L;

    private FabricRestart() {
    }

    /** Called once the replacement instance reached SERVER_STARTED. */
    public static void started() {
        RestoreSwap.swapping = false;
    }

    /** Server thread: schedules the replacement and returns true when it will run. */
    public static boolean schedule(MinecraftServer server) {
        if (!(server instanceof DedicatedServer)) {
            return false;
        }
        BackupRuntime runtime = BackupRuntime.get();
        if (runtime == null) {
            return false;
        }
        String worldKey = runtime.worldKey();
        Thread oldThread = server.getRunningThread();
        RestoreSwap.swapping = true;
        Thread supervisor = new Thread(() -> run(worldKey, oldThread),
                "minimalbackup restore supervisor");
        supervisor.start();
        return true;
    }

    private static void run(String worldKey, Thread oldThread) {
        try {
            oldThread.join(OLD_THREAD_TIMEOUT_MILLIS);
            if (oldThread.isAlive()) {
                Log.LOGGER.error("restore restart: the old server did not stop; "
                        + "not starting a second instance");
                RestoreSwap.swapping = false;
                RestoreHold.close();
                return;
            }
            start(worldKey);
        } catch (Throwable t) {
            Log.LOGGER.error("restore restart failed: {}", t.toString());
            RestoreSwap.swapping = false;
            RestoreHold.close();
        }
    }

    private static void start(String worldKey) throws Exception {
        Path gameDir = FabricLoader.getInstance().getGameDir();
        DedicatedServerSettings settings =
                new DedicatedServerSettings(gameDir.resolve("server.properties"));
        settings.forceSave();
        Services services = Services.create(new YggdrasilAuthenticationService(Proxy.NO_PROXY),
                gameDir.toFile());
        LevelStorageSource storage = LevelStorageSource.createDefault(gameDir);
        LevelStorageSource.LevelStorageAccess access = storage.validateAndCreateAccess(worldKey);
        Dynamic<?> levelData = access.getDataTag();
        PackRepository packs = ServerPacksSource.createPackRepository(access);
        WorldLoader.InitConfig config = new WorldLoader.InitConfig(
                new WorldLoader.PackConfig(packs, LevelStorageSource.readDataConfig(levelData),
                        false, false),
                Commands.CommandSelection.DEDICATED,
                settings.getProperties().functionPermissionLevel);
        WorldStem stem = Util.blockUntilDone(executor -> WorldLoader.load(config,
                data -> {
                    Registry<LevelStem> dimensions =
                            data.datapackDimensions().registryOrThrow(Registries.LEVEL_STEM);
                    LevelDataAndDimensions loaded = LevelStorageSource.getLevelDataAndDimensions(
                            levelData, data.dataConfiguration(), dimensions,
                            data.datapackWorldgen());
                    return new WorldLoader.DataLoadOutput<>(loaded.worldData(),
                            loaded.dimensions().dimensionsRegistryAccess());
                },
                WorldStem::new, Util.backgroundExecutor(), executor)).get();
        access.saveDataTag(stem.registries().compositeAccess(), stem.worldData());
        MinecraftServer server = MinecraftServer.spin(thread -> new DedicatedServer(thread, access,
                packs, stem, settings, DataFixers.getDataFixer(), services,
                LoggerChunkProgressListener::createFromGameruleRadius));
        Log.LOGGER.info("restore restart: new server instance starting on port {}",
                settings.getProperties().serverPort);
    }
}
