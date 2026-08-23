package dev.revere.alley.feature.arena.internal;

import dev.revere.alley.AlleyPlugin;
import dev.revere.alley.bootstrap.AlleyContext;
import dev.revere.alley.bootstrap.annotation.Service;
import dev.revere.alley.common.FileUtil;
import dev.revere.alley.common.VoidChunkGenerator;
import dev.revere.alley.common.logger.Logger;
import dev.revere.alley.common.serializer.Serializer;
import dev.revere.alley.core.config.ConfigService;
import dev.revere.alley.feature.arena.Arena;
import dev.revere.alley.feature.arena.ArenaService;
import dev.revere.alley.feature.arena.ArenaType;
import dev.revere.alley.feature.arena.ArenaValidator;
import dev.revere.alley.feature.arena.internal.types.FreeForAllArena;
import dev.revere.alley.feature.arena.internal.types.SharedArena;
import dev.revere.alley.feature.arena.internal.types.StandAloneArena;
import dev.revere.alley.feature.arena.schematic.ArenaSchematicService;
import dev.revere.alley.feature.kit.Kit;
import dev.revere.alley.feature.kit.KitService;
import lombok.Getter;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.io.File;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * @author Emmy
 * @project Alley
 * @date 20/05/2024 - 16:54
 */
@Getter
@Service(provides = ArenaService.class, priority = 110)
public class ArenaServiceImpl implements ArenaService {
    private final AlleyPlugin plugin;
    private final ConfigService configService;
    private final KitService kitService;
    private final ArenaSchematicService arenaSchematicService;
    private final ExecutorService executorService;

    private final List<Arena> arenas = new ArrayList<>();
    private final List<StandAloneArena> temporaryArenas = new ArrayList<>();
    private final Deque<Location> reusableCopyLocations = new ConcurrentLinkedDeque<>();
    private final AtomicInteger copyIdCounter = new AtomicInteger(0);

    private final Map<String, List<Arena>> arenasByKit = new ConcurrentHashMap<>();
    private final Map<String, Arena> arenasByName = new ConcurrentHashMap<>();

    private final ThreadLocalRandom random = ThreadLocalRandom.current();

    private World temporaryWorld;
    private Location nextCopyLocation;
    private final int arenaSpacing = 1500;

    private final ArenaValidator arenaValidator = new ArenaValidator();

    public ArenaServiceImpl(AlleyPlugin plugin, ConfigService configService, KitService kitService, ArenaSchematicService arenaSchematicService) {
        this.plugin = plugin;
        this.configService = configService;
        this.kitService = kitService;
        this.arenaSchematicService = arenaSchematicService;
        this.executorService = Executors.newFixedThreadPool(4);
    }

    @Override
    public void initialize(AlleyContext context) {
        this.loadArenas();
        this.initializeTemporaryWorld();
        buildCaches();

        this.arenaSchematicService.generateMissingSchematics(this.arenas);
    }

    @Override
    public void shutdown(AlleyContext context) {
        // The temporary world is deleted as a whole below. Do not enqueue
        // per-arena FAWE resets during shutdown because the schematic service
        // is intentionally shut down earlier in the reverse service order.
        this.temporaryArenas.clear();

        if (temporaryWorld != null) {
            String worldName = temporaryWorld.getName();

            temporaryWorld.getPlayers().forEach(player ->
                    player.teleport(Bukkit.getServer().getWorlds().get(0).getSpawnLocation())
            );

            if (Bukkit.unloadWorld(temporaryWorld, false)) {
                Logger.info("Successfully unloaded temporary world: " + worldName);
                File worldFolder = new File(Bukkit.getWorldContainer(), worldName);
                if (worldFolder.exists()) {
                    FileUtil.deleteWorldFolder(worldFolder);
                    Logger.info("Deleted temporary world folder: " + worldName);
                }
            } else {
                Logger.error("Failed to unload temporary world: " + worldName);
            }
            temporaryWorld = null;
        }

        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdown();
            try {
                if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                    executorService.shutdownNow();
                }
            } catch (InterruptedException e) {
                executorService.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    private void buildCaches() {
        arenasByKit.clear();
        arenasByName.clear();

        for (Kit kit : kitService.getKits()) {
            List<Arena> kitArenas = arenas.stream()
                    .filter(arena -> arena.getKits().contains(kit.getName()))
                    .filter(Arena::isEnabled)
                    .collect(Collectors.toList());
            arenasByKit.put(kit.getName(), kitArenas);
        }

        for (Arena arena : arenas) {
            arenasByName.put(arena.getName().toLowerCase(), arena);
        }
    }

    private void initializeTemporaryWorld() {
        String worldName = "temporary_arena_world";
        cleanupExistingWorld(worldName);

        WorldCreator creator = new WorldCreator(worldName);
        creator.generateStructures(false).generator(new VoidChunkGenerator());

        this.temporaryWorld = creator.createWorld();
        this.nextCopyLocation = new Location(temporaryWorld, 0, 100, 0);
    }

    /**
     * Method to load all arenas from the arenas.yml file.
     * 从 arenas.yml 文件中加载所有竞技场的方法。
     */
    @Override
    public void loadArenas() {
        this.arenas.clear();
        FileConfiguration config = configService.getArenasConfig();
        ConfigurationSection arenasConfig = config.getConfigurationSection("arenas");

        if (arenasConfig == null) {
            return;
        }

        Set<String> arenaNames = arenasConfig.getKeys(false);

        if (arenaNames.size() <= 5) {
            for (String arenaName : arenaNames) {
                Arena arena = loadSingleArena(config, arenaName);
                if (arena != null) {
                    this.arenas.add(arena);
                }
            }
            return;
        }

        List<CompletableFuture<Arena>> futures = new ArrayList<>();

        for (String arenaName : arenaNames) {
            CompletableFuture<Arena> future = CompletableFuture.supplyAsync(() -> loadSingleArena(config, arenaName), executorService);
            futures.add(future);
        }

        for (CompletableFuture<Arena> future : futures) {
            try {
                Arena arena = future.get(5, TimeUnit.SECONDS);
                if (arena != null) {
                    this.arenas.add(arena);
                }
            } catch (TimeoutException e) {
                Logger.error("Arena loading timed out after 5 seconds");
                future.cancel(true);
            } catch (Exception e) {
                Logger.error("Failed to load arena: " + e.getMessage());
            }
        }
    }

    private Arena loadSingleArena(FileConfiguration config, String arenaName) {
        try {
            String name = "arenas." + arenaName;

            ArenaType arenaType = ArenaType.valueOf(config.getString(name + ".type"));
            Location minimum = Serializer.deserializeLocation(config.getString(name + ".minimum"));
            Location maximum = Serializer.deserializeLocation(config.getString(name + ".maximum"));

            Arena arena = createArenaByType(arenaType, arenaName, minimum, maximum, config, name);
            configureArena(arena, config, name);

            return arena;
        } catch (Exception e) {
            Logger.error("Error loading arena " + arenaName + ": " + e.getMessage());
            return null;
        }
    }

    private Arena createArenaByType(ArenaType arenaType, String arenaName,
                                    Location minimum, Location maximum,
                                    FileConfiguration config, String name) {
        switch (arenaType) {
            case SHARED:
                return new SharedArena(arenaName, minimum, maximum);

            case STANDALONE:
                int heightLimit = config.getInt(name + ".height-limit", 7);
                int voidLevel = config.getInt(name + ".void-level", 70);
                return new StandAloneArena(
                        arenaName, minimum, maximum,
                        Serializer.deserializeLocation(config.getString(name + ".team-one-portal")),
                        Serializer.deserializeLocation(config.getString(name + ".team-two-portal")),
                        heightLimit, voidLevel
                );

            case FFA:
                return new FreeForAllArena(
                        arenaName,
                        Serializer.deserializeLocation(config.getString(name + ".safe-zone.pos1")),
                        Serializer.deserializeLocation(config.getString(name + ".safe-zone.pos2"))
                );

            default:
                throw new IllegalStateException("Unexpected arena type: " + arenaType);
        }
    }

    private void configureArena(Arena arena, FileConfiguration config, String name) {
        if (config.contains(name + ".kits")) {
            Set<String> validKits = new HashSet<>();
            for (String kitName : config.getStringList(name + ".kits")) {
                if (kitService.getKit(kitName) != null) {
                    validKits.add(kitName);
                }
            }
            arena.getKits().addAll(validKits);
        }

        if (config.contains(name + ".pos1")) {
            arena.setPos1(Serializer.deserializeLocation(config.getString(name + ".pos1")));
        }
        if (config.contains(name + ".pos2")) {
            arena.setPos2(Serializer.deserializeLocation(config.getString(name + ".pos2")));
        }
        if (config.contains(name + ".center")) {
            arena.setCenter(Serializer.deserializeLocation(config.getString(name + ".center")));
        }
        if (config.contains(name + ".display-name")) {
            arena.setDisplayName(config.getString(name + ".display-name"));
        }
        if (config.contains(name + ".enabled")) {
            arena.setEnabled(config.getBoolean(name + ".enabled"));
        }
        if (arena instanceof StandAloneArena standAloneArena) {
            standAloneArena.setSkyWarsArena(config.getBoolean(name + ".skywars.enabled", false));
            List<Location> skyWarsSpawns = new ArrayList<>();
            for (String serializedLocation : config.getStringList(name + ".skywars.spawns")) {
                Location location = Serializer.deserializeLocation(serializedLocation);
                if (location != null) skyWarsSpawns.add(location);
            }
            standAloneArena.setSkyWarsSpawns(skyWarsSpawns);
        }
    }

    @Override
    public StandAloneArena createTemporaryArenaCopy(StandAloneArena originalArena) {
        if (originalArena.isTemporaryCopy()) {
            throw new IllegalArgumentException("Cannot create a temporary copy of a temporary arena.");
        }

        int copyId = copyIdCounter.incrementAndGet();
        Location copyLocation = getNextCopyLocationForArena(originalArena);

        Location originalPos1 = originalArena.getPos1();
        Location originalMin = originalArena.getMinimum();
        Location originalMax = originalArena.getMaximum();

        if (originalPos1 != null && originalMin != null && originalMax != null) {
            int actualMinY = Math.min(originalMin.getBlockY(), originalMax.getBlockY());
            int pos1OffsetFromActualMin = originalPos1.getBlockY() - actualMinY;
            int targetMinY = 100 - pos1OffsetFromActualMin;
            copyLocation.setY(targetMinY);
        }

        StandAloneArena copiedArena = originalArena.createCopy(temporaryWorld, copyLocation, copyId);
        copiedArena.setHeightLimit(copiedArena.getPos1().getBlockY() + copiedArena.getHeightLimit());

        File schematicFile = this.arenaSchematicService.getSchematicFile(originalArena.getName());
        Location slotOrigin = new Location(temporaryWorld, copyLocation.getBlockX(), 100, copyLocation.getBlockZ());
        copiedArena.setCopyOrigin(slotOrigin);

        Location priorityMinimum = getPriorityMinimum(copiedArena);
        Location priorityMaximum = getPriorityMaximum(copiedArena);
        CompletableFuture<Void> spawnPaste = this.arenaSchematicService
                .pasteRegionAsync(copyLocation, schematicFile, priorityMinimum, priorityMaximum);
        CompletableFuture<Void> spawnReady = spawnPaste.thenCompose(ignored ->
                preloadSpawnChunksAsync(copiedArena));
        CompletableFuture<Void> fullPaste = spawnPaste.thenCompose(ignored ->
                this.arenaSchematicService.pasteAsync(copyLocation, schematicFile));
        fullPaste.exceptionally(throwable -> {
            Logger.logException("Failed to finish standalone arena paste " + copiedArena.getName(),
                    throwable instanceof Exception exception ? exception : new Exception(throwable));
            return null;
        });
        copiedArena.setPreparationFutures(spawnReady, fullPaste);
        this.temporaryArenas.add(copiedArena);
        return copiedArena;
    }

    public Location getNextCopyLocationForArena(StandAloneArena originalArena) {
        Location reusable = this.reusableCopyLocations.pollFirst();
        if (reusable != null) {
            return reusable.clone();
        }

        Location location = this.nextCopyLocation.clone();

        Location originalPos1 = originalArena.getPos1();
        Location originalMin = originalArena.getMinimum();

        if (originalPos1 != null && originalMin != null) {
            int pos1OffsetFromMin = originalPos1.getBlockY() - originalMin.getBlockY();
            location.setY(100 - pos1OffsetFromMin);
        }

        nextCopyLocation.add(arenaSpacing, 0, 0);
        if (nextCopyLocation.getX() > arenaSpacing * 10) {
            nextCopyLocation.setX(0);
            nextCopyLocation.add(0, 0, arenaSpacing);
        }

        return location;
    }

    private Location getPriorityMinimum(StandAloneArena arena) {
        return getPriorityBounds(arena)[0];
    }

    private Location getPriorityMaximum(StandAloneArena arena) {
        return getPriorityBounds(arena)[1];
    }

    /**
     * Returns a small union box around both spawns and the spectator center.
     * The box is intentionally capped by the arena bounds so a huge map does
     * not turn the fast path into another full-map paste.
     */
    private Location[] getPriorityBounds(StandAloneArena arena) {
        Location min = arena.getMinimum();
        Location max = arena.getMaximum();
        int arenaMinX = Math.min(min.getBlockX(), max.getBlockX());
        int arenaMinY = Math.min(min.getBlockY(), max.getBlockY());
        int arenaMinZ = Math.min(min.getBlockZ(), max.getBlockZ());
        int arenaMaxX = Math.max(min.getBlockX(), max.getBlockX());
        int arenaMaxY = Math.max(min.getBlockY(), max.getBlockY());
        int arenaMaxZ = Math.max(min.getBlockZ(), max.getBlockZ());
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;

        List<Location> anchors = new ArrayList<>();
        if (arena.getPos1() != null) anchors.add(arena.getPos1());
        if (arena.getPos2() != null) anchors.add(arena.getPos2());
        if (arena.getCenter() != null) anchors.add(arena.getCenter());
        int radius = 2 * 16 + 8;
        for (Location anchor : anchors) {
            minX = Math.min(minX, anchor.getBlockX() - radius);
            minY = Math.min(minY, anchor.getBlockY() - radius);
            minZ = Math.min(minZ, anchor.getBlockZ() - radius);
            maxX = Math.max(maxX, anchor.getBlockX() + radius);
            maxY = Math.max(maxY, anchor.getBlockY() + radius);
            maxZ = Math.max(maxZ, anchor.getBlockZ() + radius);
        }
        if (anchors.isEmpty()) {
            minX = arenaMinX;
            minY = arenaMinY;
            minZ = arenaMinZ;
            maxX = arenaMaxX;
            maxY = arenaMaxY;
            maxZ = arenaMaxZ;
        } else {
            minX = Math.max(arenaMinX, minX);
            minY = Math.max(arenaMinY, minY);
            minZ = Math.max(arenaMinZ, minZ);
            maxX = Math.min(arenaMaxX, maxX);
            maxY = Math.min(arenaMaxY, maxY);
            maxZ = Math.min(arenaMaxZ, maxZ);
        }
        return new Location[]{
                new Location(min.getWorld(), minX, minY, minZ),
                new Location(min.getWorld(), maxX, maxY, maxZ)
        };
    }

    private CompletableFuture<Void> preloadSpawnChunksAsync(StandAloneArena arena) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(this.plugin, () -> {
            try {
                World world = arena.getMinimum().getWorld();
                if (world == null) {
                    result.completeExceptionally(new IllegalStateException("Arena world is unavailable"));
                    return;
                }

                Set<Long> chunkKeys = new HashSet<>();
                for (Location anchor : Arrays.asList(arena.getPos1(), arena.getPos2(), arena.getCenter())) {
                    if (anchor == null) continue;
                    int chunkX = anchor.getBlockX() >> 4;
                    int chunkZ = anchor.getBlockZ() >> 4;
                    for (int x = chunkX - 2; x <= chunkX + 2; x++) {
                        for (int z = chunkZ - 2; z <= chunkZ + 2; z++) {
                            chunkKeys.add((((long) x) << 32) ^ (z & 0xffffffffL));
                        }
                    }
                }

                CompletableFuture<?>[] loads = chunkKeys.stream()
                        .map(key -> world.getChunkAtAsync((int) (key >> 32), (int) (long) key, true))
                        .toArray(CompletableFuture[]::new);
                CompletableFuture.allOf(loads).whenComplete((ignored, throwable) -> {
                    if (throwable == null) result.complete(null);
                    else result.completeExceptionally(throwable);
                });
            } catch (Throwable throwable) {
                result.completeExceptionally(throwable);
            }
        });
        return result;
    }

    public void cleanupTemporaryArenas() {
        for (StandAloneArena arena : new ArrayList<>(temporaryArenas)) {
            deleteTemporaryArena(arena);
        }
    }

    /**
     * Cleans up an existing world by unloading it and deleting its corresponding folder.
     * This includes teleporting any players in the world back to the spawn location of the
     * first loaded world.
     * 通过卸载现有世界并删除其对应的文件夹来清理。
     * 这包括将世界中的任何玩家传送回第一个加载的世界的出生点位置。
     *
     * @param worldName the name of the world to be cleaned up
     *                  要清理的世界的名称
     */
    private void cleanupExistingWorld(String worldName) {
        World existingWorld = this.plugin.getServer().getWorld(worldName);
        if (existingWorld != null) {
            existingWorld.getPlayers().forEach(player ->
                    player.teleport(Bukkit.getServer().getWorlds().get(0).getSpawnLocation())
            );

            boolean unloaded = this.plugin.getServer().unloadWorld(existingWorld, false);
            if (!unloaded) {
                Logger.error("Failed to unload world: " + worldName);
            }
        }

        File worldFolder = new File(Bukkit.getWorldContainer(), worldName);
        if (worldFolder.exists()) {
            FileUtil.deleteWorldFolder(worldFolder);
        }
    }

    @Override
    public List<Arena> getArenas() {
        return Collections.unmodifiableList(arenas);
    }

    @Override
    public List<StandAloneArena> getTemporaryArenas() {
        return Collections.unmodifiableList(temporaryArenas);
    }

    @Override
    public void saveArena(Arena arena) {
        if (arena == null) {
            return;
        }

        arena.saveArena();
        buildCaches();
    }

    @Override
    public void deleteArena(Arena arena) {
        if (arena == null) {
            return;
        }

        arena.deleteArena();
        arenas.remove(arena);
        buildCaches();
    }

    @Override
    public void deleteTemporaryArena(StandAloneArena arena) {
        if (arena == null || !temporaryArenas.contains(arena)) {
            return;
        }
        this.temporaryArenas.remove(arena);

        // Keep the slot reserved until the serialized FAWE delete has finished.
        // Reusing it earlier would let a new match race the old cleanup.
        arena.deleteCopiedArenaAsync().whenComplete((ignored, throwable) -> {
            if (throwable != null) {
                Logger.logException("Failed to reset temporary arena " + arena.getName(),
                        throwable instanceof Exception exception ? exception : new Exception(throwable));
            }
            Location origin = arena.getCopyOrigin();
            if (origin == null) return;
            this.reusableCopyLocations.offerLast(origin.clone());
        });
    }

    @Override
    public Arena getRandomArena(Kit kit) {
        List<Arena> availableArenas = arenasByKit.get(kit.getName());
        if (availableArenas == null || availableArenas.isEmpty()) {
            return null;
        }

        List<Arena> nonSkyWarsArenas = availableArenas.stream()
                .filter(arena -> !(arena instanceof StandAloneArena standAloneArena
                        && standAloneArena.isSkyWarsArena()))
                .collect(Collectors.toList());
        if (nonSkyWarsArenas.isEmpty()) {
            return null;
        }

        Arena selectedArena = nonSkyWarsArenas.get(random.nextInt(nonSkyWarsArenas.size()));
        if (selectedArena instanceof StandAloneArena) {
            return createTemporaryArenaCopy((StandAloneArena) selectedArena);
        }
        return selectedArena;
    }

    @Override
    public boolean hasSkyWarsArena(Kit kit) {
        if (kit == null) return false;
        return this.arenas.stream()
                .filter(StandAloneArena.class::isInstance)
                .map(StandAloneArena.class::cast)
                .anyMatch(arena -> arena.isEnabled()
                        && arena.isSkyWarsArena()
                        && arena.getKits().contains(kit.getName())
                        && arena.getSkyWarsSpawns().size() >= 4);
    }

    @Override
    public Arena getRandomSkyWarsArena(Kit kit) {
        if (kit == null) return null;
        List<StandAloneArena> availableArenas = this.arenas.stream()
                .filter(StandAloneArena.class::isInstance)
                .map(StandAloneArena.class::cast)
                .filter(Arena::isEnabled)
                .filter(StandAloneArena::isSkyWarsArena)
                .filter(arena -> arena.getKits().contains(kit.getName()))
                .filter(arena -> arena.getSkyWarsSpawns().size() >= 4)
                .collect(Collectors.toList());
        if (availableArenas.isEmpty()) return null;

        return this.createTemporaryArenaCopy(availableArenas.get(this.random.nextInt(availableArenas.size())));
    }

    @Override
    public Arena getArenaByName(String name) {
        return arenasByName.get(name.toLowerCase());
    }

    @Override
    public Arena selectArenaWithPotentialTemporaryCopy(Arena arena) {
        // getRandomArena() may already return a temporary copy (e.g. the party split/FFA
        // and random-duel paths). Copying a copy throws and leaks the first copy, so a
        // copy that is already temporary is reused as-is instead of copied again.
        if (arena instanceof StandAloneArena && !((StandAloneArena) arena).isTemporaryCopy()) {
            return createTemporaryArenaCopy((StandAloneArena) arena);
        }
        return arena;
    }

    @Override
    public void registerNewArena(Arena arena) {
        if (arena != null && !arenasByName.containsKey(arena.getName().toLowerCase())) {
            this.arenas.add(arena);
            this.buildCaches();
        }
    }

    /**
     * Refresh caches when kits or arenas are modified
     * 当套件或竞技场被修改时刷新缓存
     */
    @Override
    public void refreshCaches() {
        CompletableFuture.runAsync(this::buildCaches, executorService);
    }
}
