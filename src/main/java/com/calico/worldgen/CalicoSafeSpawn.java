package com.calico.worldgen;

import javax.annotation.Nullable;

import com.calico.Calico;
import com.calico.config.TerrainStyle;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.storage.ServerLevelData;

/**
 * Deterministic safe-spawn finder for dangerous Calico terrain styles.
 * <p>
 * Prefer a natural solid top surface with air above near world spawn; place a
 * small platform only as last resort. No-ops for normal overworld noise.
 */
public final class CalicoSafeSpawn {
    /** Mix into world seed so spawn search is stable but distinct from biome noise. */
    private static final long SEARCH_SEED_MIX = 0x5AFE_C011_C05A_FE11L;
    /** Spiral radius in chunks (vanilla uses ~5; islands need wider reach). */
    private static final int MAX_SPIRAL_CHUNKS = 48;
    /** Platform half-extent (3×3 → radius 1; 5×5 → radius 2). */
    private static final int PLATFORM_RADIUS = 2;
    private static final String PLAYER_TAG = "calico_safe_spawn_v1";

    private CalicoSafeSpawn() {
    }

    /**
     * True when overworld uses Calico weighted biomes with non-normal terrain
     * (sky_islands, wedding_cake, ant_hill, islands, big_islands, cave, mountainous,
     * or any other custom noise ≠ OVERWORLD).
     */
    public static boolean needsSafeSpawn(ServerLevel level) {
        if (!level.dimension().equals(Level.OVERWORLD)) {
            return false;
        }
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        if (!(generator instanceof NoiseBasedChunkGenerator noiseGen)) {
            return false;
        }
        if (!(noiseGen.getBiomeSource() instanceof WeightedBiomeSource)) {
            return false;
        }
        // Normal Calico overworld — leave vanilla spawn alone.
        if (noiseGen.stable(NoiseGeneratorSettings.OVERWORLD)) {
            return false;
        }
        return true;
    }

    /** Best-effort style label for logs (from noise settings key). */
    public static String describeStyle(ServerLevel level) {
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        if (!(generator instanceof NoiseBasedChunkGenerator noiseGen)) {
            return "unknown";
        }
        return noiseGen.generatorSettings().unwrapKey()
                .map(k -> k.location().getPath())
                .orElseGet(() -> {
                    NoiseGeneratorSettings s = noiseGen.generatorSettings().value();
                    if (s.seaLevel() < 0 && !s.isAquifersEnabled() && s.noiseSettings().minY() >= 0) {
                        return TerrainStyle.SKY_ISLANDS.serializedName();
                    }
                    if (s.seaLevel() < 0 && !s.isAquifersEnabled()) {
                        return TerrainStyle.WEDDING_CAKE.serializedName();
                    }
                    return "custom";
                });
    }

    /**
     * Searches near the noise sampler spawn for a safe standing position.
     * Deterministic from {@code level.getSeed()}. Returns null if nothing found
     * within the spiral (caller may place a platform).
     */
    @Nullable
    public static BlockPos findSafeSpawn(ServerLevel level) {
        RandomSource random = RandomSource.create(level.getSeed() ^ SEARCH_SEED_MIX);
        ChunkPos center = new ChunkPos(level.getChunkSource().randomState().sampler().findSpawnPosition());
        // Slight deterministic jitter so two adjacent seeds don't always land on the same column.
        int jitterX = random.nextInt(3) - 1;
        int jitterZ = random.nextInt(3) - 1;
        center = new ChunkPos(center.x + jitterX, center.z + jitterZ);

        int dx = 0;
        int dz = 0;
        int stepX = 0;
        int stepZ = -1;
        int max = Mth.square(MAX_SPIRAL_CHUNKS * 2 + 1);
        for (int i = 0; i < max; i++) {
            ChunkPos chunkPos = new ChunkPos(center.x + dx, center.z + dz);
            // Force chunk load so heightmaps / block states are available.
            level.getChunk(chunkPos.x, chunkPos.z);
            BlockPos found = findInChunk(level, chunkPos, random);
            if (found != null) {
                return found;
            }
            if (dx == dz || (dx < 0 && dx == -dz) || (dx > 0 && dx == 1 - dz)) {
                int tmp = stepX;
                stepX = -stepZ;
                stepZ = tmp;
            }
            dx += stepX;
            dz += stepZ;
        }
        return null;
    }

    /**
     * Places a small solid platform with air above at a deterministic location
     * near the sampler spawn, and returns the feet position on top of it.
     */
    public static BlockPos placeFallbackPlatform(ServerLevel level) {
        ChunkPos center = new ChunkPos(level.getChunkSource().randomState().sampler().findSpawnPosition());
        RandomSource random = RandomSource.create(level.getSeed() ^ SEARCH_SEED_MIX ^ 0x91A7F04DC011L);
        int footX = center.getMinBlockX() + 8 + (random.nextInt(5) - 2);
        int footZ = center.getMinBlockZ() + 8 + (random.nextInt(5) - 2);
        int platformY = choosePlatformY(level);
        BlockState floor = choosePlatformBlock(level);

        for (int x = -PLATFORM_RADIUS; x <= PLATFORM_RADIUS; x++) {
            for (int z = -PLATFORM_RADIUS; z <= PLATFORM_RADIUS; z++) {
                BlockPos floorPos = new BlockPos(footX + x, platformY, footZ + z);
                level.setBlock(floorPos, floor, Block.UPDATE_ALL);
                // Clear standing / head space
                level.setBlock(floorPos.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                level.setBlock(floorPos.above(2), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        BlockPos feet = new BlockPos(footX, platformY + 1, footZ);
        Calico.LOGGER.info(
                "Calico: safe-spawn fallback platform at {} (style={})",
                feet,
                describeStyle(level));
        return feet;
    }

    /**
     * Ensures a safe world spawn: search first, platform last resort.
     * Sets {@link net.minecraft.world.level.storage.ServerLevelData} spawn.
     *
     * @return feet position used for spawn
     */
    public static BlockPos ensureWorldSpawn(ServerLevel level, ServerLevelData levelData) {
        BlockPos found = findSafeSpawn(level);
        boolean platform = false;
        if (found == null) {
            found = placeFallbackPlatform(level);
            platform = true;
        }
        levelData.setSpawn(found, 0.0F);
        Calico.LOGGER.info(
                "Calico: safe spawn set at {} (style={}, platform={}, seed={})",
                found,
                describeStyle(level),
                platform,
                level.getSeed());
        return found;
    }

    /** Overload using ServerLevel's level data when available. */
    public static BlockPos ensureWorldSpawn(ServerLevel level) {
        return ensureWorldSpawn(level, (ServerLevelData) level.getLevelData());
    }

    public static boolean isPlayerMarked(Player player) {
        return player.getPersistentData().getBoolean(PLAYER_TAG);
    }

    public static void markPlayer(Player player) {
        player.getPersistentData().putBoolean(PLAYER_TAG, true);
    }

    /**
     * True when the player cannot safely stand at {@code feetPos}
     * (void / air / lava / no solid below / no headroom).
     */
    public static boolean isUnsafeStanding(ServerLevel level, BlockPos feetPos) {
        if (!level.getWorldBorder().isWithinBounds(feetPos)) {
            return true;
        }
        if (feetPos.getY() < level.getMinBuildHeight() || feetPos.getY() >= level.getMaxBuildHeight() - 1) {
            return true;
        }
        BlockPos below = feetPos.below();
        BlockState floor = level.getBlockState(below);
        BlockState feet = level.getBlockState(feetPos);
        BlockState head = level.getBlockState(feetPos.above());
        if (!isSafeStandable(floor, level, below)) {
            return true;
        }
        if (!isPassableForStanding(feet, level, feetPos) || !isPassableForStanding(head, level, feetPos.above())) {
            return true;
        }
        if (isDangerousFluid(feet) || isDangerousFluid(head) || isDangerousFluid(floor)) {
            return true;
        }
        return false;
    }

    @Nullable
    private static BlockPos findInChunk(ServerLevel level, ChunkPos chunkPos, RandomSource random) {
        // Deterministic column order: visit every 4th block first (fast), then fill gaps.
        int phase = random.nextInt(4);
        for (int pass = 0; pass < 2; pass++) {
            int stride = pass == 0 ? 4 : 1;
            for (int lx = 0; lx < 16; lx++) {
                for (int lz = 0; lz < 16; lz++) {
                    if (pass == 0 && ((lx + lz + phase) % stride) != 0) {
                        continue;
                    }
                    if (pass == 1 && ((lx + lz + phase) % 4) == 0) {
                        continue; // already checked
                    }
                    int x = chunkPos.getMinBlockX() + lx;
                    int z = chunkPos.getMinBlockZ() + lz;
                    BlockPos pos = findSafeColumn(level, x, z);
                    if (pos != null) {
                        return pos;
                    }
                }
            }
        }
        return null;
    }

    @Nullable
    private static BlockPos findSafeColumn(ServerLevel level, int x, int z) {
        // Prefer heightmap tops when they exist (islands / mountains / caves).
        int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        // Heightmap value is the first empty block above the top motion-blocking block (feet Y).
        if (surface > level.getMinBuildHeight()) {
            BlockPos feetPos = new BlockPos(x, surface, z);
            if (!isUnsafeStanding(level, feetPos)) {
                return feetPos;
            }
        }

        // Full column scan for floating / void / cave quirks.
        int maxY = level.getMaxBuildHeight() - 2;
        int minY = level.getMinBuildHeight() + 1;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int y = maxY; y >= minY; y--) {
            cursor.set(x, y, z);
            BlockState floor = level.getBlockState(cursor);
            if (!isSafeStandable(floor, level, cursor)) {
                continue;
            }
            BlockPos feetPos = cursor.above();
            if (!isUnsafeStanding(level, feetPos)) {
                return feetPos.immutable();
            }
        }
        return null;
    }

    private static boolean isSafeStandable(BlockState state, ServerLevel level, BlockPos pos) {
        if (state.isAir()) {
            return false;
        }
        if (isDangerousFluid(state)) {
            return false;
        }
        if (state.is(Blocks.MAGMA_BLOCK) || state.is(Blocks.CACTUS) || state.is(Blocks.FIRE)
                || state.is(Blocks.SOUL_FIRE) || state.is(Blocks.SWEET_BERRY_BUSH)
                || state.is(Blocks.POWDER_SNOW)) {
            return false;
        }
        if (state.is(BlockTags.LEAVES) || state.is(BlockTags.FENCES) || state.is(BlockTags.WALLS)
                || state.is(BlockTags.FENCE_GATES)) {
            return false;
        }
        return Block.isFaceFull(state.getCollisionShape(level, pos), Direction.UP);
    }

    private static boolean isPassableForStanding(BlockState state, ServerLevel level, BlockPos pos) {
        if (isDangerousFluid(state)) {
            return false;
        }
        // Allow non-solid (air, tall grass, etc.) but not thick collision.
        return state.getCollisionShape(level, pos).isEmpty();
    }

    private static boolean isDangerousFluid(BlockState state) {
        return state.getFluidState().is(Fluids.LAVA)
                || state.getFluidState().is(Fluids.FLOWING_LAVA)
                || state.is(Blocks.LAVA);
    }

    private static int choosePlatformY(ServerLevel level) {
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        int sea = 63;
        if (generator instanceof NoiseBasedChunkGenerator noiseGen) {
            sea = noiseGen.generatorSettings().value().seaLevel();
        }
        int preferred = sea > 0 ? sea + 1 : 64;
        return Mth.clamp(preferred, level.getMinBuildHeight() + 2, level.getMaxBuildHeight() - 4);
    }

    private static BlockState choosePlatformBlock(ServerLevel level) {
        if (level.getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator noiseGen) {
            NoiseGeneratorSettings s = noiseGen.generatorSettings().value();
            if (s.seaLevel() < 0) {
                return Blocks.STONE.defaultBlockState();
            }
        }
        return Blocks.GRASS_BLOCK.defaultBlockState();
    }
}
