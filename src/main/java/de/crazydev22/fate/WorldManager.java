package de.crazydev22.fate;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

public class WorldManager {
    private static final Map<String, WorldType> WORLD_TYPES;
    private static final List<Material> ITEMS;
    private static final List<Material> BLOCKS;

    private final SharedFate plugin;
    private final Random random = new Random();
    private final DeletionQueue queue;

    private long counter;
    private Map<World.Environment, World> worlds;

    private ResetSettings reset;

    WorldManager(SharedFate plugin) {
        this.plugin = plugin;

        plugin.saveDefaultConfig();
        reload();

        counter = plugin.getConfig().getLong("counter", 0);
        worlds = createWorlds(counter);

        this.queue = new DeletionQueue(plugin);
    }

    public boolean contains(@Nullable World world) {
        if (world == null) return false;
        World existing = worlds.get(world.getEnvironment());
        if (existing == null) return false;
        return existing.getName().equals(world.getName());
    }

    @NotNull
    public World overworld() {
        return worlds.get(World.Environment.NORMAL);
    }

    @NotNull
    public World nether() {
        return worlds.get(World.Environment.NETHER);
    }

    @NotNull
    public World end() {
        return worlds.get(World.Environment.THE_END);
    }

    public void clear(@NotNull Player player) {
        if (reset.enderchest()) player.getEnderChest().clear();
        if (reset.statistics()) {
            Registry<EntityType> types = RegistryAccess.registryAccess().getRegistry(RegistryKey.ENTITY_TYPE);

            for (Statistic statistic : Statistic.values()) {
                switch (statistic.getType()) {
                    case UNTYPED -> {
                        if (player.getStatistic(statistic) != 0)
                            player.setStatistic(statistic, 0);
                    }
                    case ITEM -> {
                        for (Material material : ITEMS) {
                            if (player.getStatistic(statistic, material) != 0)
                                player.setStatistic(statistic, material, 0);
                        }
                    }
                    case BLOCK -> {
                        for (Material material : BLOCKS) {
                            if (player.getStatistic(statistic, material) != 0)
                                player.setStatistic(statistic, material, 0);
                        }
                    }
                    case ENTITY -> {
                        for (EntityType type : types) {
                            if (player.getStatistic(statistic, type) != 0)
                                player.setStatistic(statistic, type, 0);
                        }
                    }
                }
            }
        }
    }

    public long getCounter() {
        return counter;
    }

    @NotNull
    public ResetSettings getReset() {
        return reset;
    }

    public void advance() {
        reload();
        plugin.getConfig().set("counter", ++counter);
        plugin.saveConfig();

        Map<World.Environment, World> oldMap = worlds;
        worlds = createWorlds(counter);

        Registry<GameRule<?>> registry = RegistryAccess.registryAccess().getRegistry(RegistryKey.GAME_RULE);
        for (World oldWorld : oldMap.values()) {
            World newWorld = worlds.get(oldWorld.getEnvironment());
            if (newWorld == null)
                continue;

            for (GameRule<?> rule : registry) {
                copy(rule, oldWorld, newWorld);
            }

            WorldBorder oldWorldBorder = oldWorld.getWorldBorder();
            WorldBorder newWorldBorder = newWorld.getWorldBorder();
            newWorldBorder.setCenter(oldWorldBorder.getCenter());
            newWorldBorder.setSize(oldWorldBorder.getSize());
            newWorldBorder.setDamageAmount(oldWorldBorder.getDamageAmount());
            newWorldBorder.setDamageBuffer(oldWorldBorder.getDamageBuffer());
            newWorldBorder.setWarningTimeTicks(oldWorldBorder.getWarningTimeTicks());
            newWorldBorder.setWarningDistance(oldWorldBorder.getWarningDistance());
        }
        queue.add(oldMap.values());
    }

    public void reload() {
        plugin.reloadConfig();

        FileConfiguration config = plugin.getConfig();
        reset = new ResetSettings(
                config.getBoolean("reset.inventory", true),
                config.getBoolean("reset.experience", true),
                config.getBoolean("reset.enderchest", false),
                config.getBoolean("reset.statistics", false)
        );
    }

    private <T> void copy(GameRule<T> rule, World source, World target) {
        if (!source.getFeatureFlags().containsAll(rule.requiredFeatures()) || !target.getFeatureFlags().containsAll(rule.requiredFeatures()))
            return;
        T value = source.getGameRuleValue(rule);
        if (value == null) return;
        target.setGameRule(rule, value);
    }

    public void shutdown() {
        plugin.reloadConfig();
        plugin.getConfig().set("counter", counter);
        plugin.saveConfig();

        queue.shutdown();
    }

    @NotNull
    private Map<World.Environment, World> createWorlds(long counter) {
        World overworld = create(World.Environment.NORMAL, counter);
        if (overworld == null) throw new IllegalStateException("Failed to create overworld");

        World nether = create(World.Environment.NETHER, counter);
        if (nether == null) throw new IllegalStateException("Failed to create nether");

        World end = create(World.Environment.THE_END, counter);
        if (end == null) throw new IllegalStateException("Failed to create end");

        return Map.of(
                World.Environment.NORMAL, overworld,
                World.Environment.NETHER, nether,
                World.Environment.THE_END, end
        );
    }

    @Nullable
    private World create(@NotNull World.Environment environment, long counter) {
        String name = switch (environment) {
            case NORMAL -> "overworld";
            case NETHER -> "nether";
            case THE_END -> "the_end";
            default -> throw new IllegalArgumentException();
        };

        ConfigurationSection section = plugin.getConfig()
                .getConfigurationSection("world-settings." + name);
        if (section == null) {
            return WorldCreator.name(counter + "/" + name)
                    .environment(environment)
                    .createWorld();
        }

        long seed = section.getLong("seed", 0);
        boolean generateStructures = section.getBoolean("generate-structures", false);
        boolean bonusChest = section.getBoolean("bonus-chest", false);
        WorldType type = WORLD_TYPES.getOrDefault(section.getString("type", "normal")
                        .toUpperCase(Locale.ENGLISH),
                WorldType.NORMAL);
        String settings = section.getString("generatorSettings", "");

        String generator = section.getString("generator");
        String biomeProvider = section.getString("biome-provider");

        if (seed == 0) seed = random.nextLong();

        if (generator != null && generator.isBlank())
            generator = null;
        if (biomeProvider != null && biomeProvider.isBlank())
            biomeProvider = null;


        return WorldCreator.name(counter + "/" + name)
                .environment(environment)
                .seed(seed)
                .generateStructures(generateStructures)
                .bonusChest(bonusChest)
                .type(type)
                .generatorSettings(settings)
                .generator(generator)
                .biomeProvider(biomeProvider)
                .createWorld();
    }

    static {
        WORLD_TYPES = new HashMap<>(WorldType.values().length);
        for (WorldType type : WorldType.values()) {
            WORLD_TYPES.put(type.name(), type);
        }

        List<Material> items = new ArrayList<>();
        List<Material> blocks = new ArrayList<>();

        for (Material material : Material.values()) {
            if (material.isLegacy())
                continue;

            if (material.isItem())
                items.add(material);
            if (material.isBlock())
                blocks.add(material);
        }

        ITEMS = Collections.unmodifiableList(items);
        BLOCKS = Collections.unmodifiableList(blocks);
    }
}
