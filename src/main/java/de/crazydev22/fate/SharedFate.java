package de.crazydev22.fate;

import io.papermc.paper.event.entity.EntityPortalReadyEvent;
import io.papermc.paper.event.player.AsyncPlayerSpawnLocationEvent;
import org.bukkit.*;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class SharedFate extends JavaPlugin implements Listener {
    private final NamespacedKey KEY = new NamespacedKey(this, "counter");
    private WorldManager manager;

    private boolean processing = false;

    @Override
    public void onEnable() {
        manager = new WorldManager(this);
        getServer().getPluginManager().registerEvents(this, this);
    }

    @Override
    public void onDisable() {
        manager.shutdown();
    }

    @EventHandler
    public void onSpawn(AsyncPlayerSpawnLocationEvent event) {
        if (manager.contains(event.getSpawnLocation().getWorld()))
            return;

        event.setSpawnLocation(manager.overworld().getSpawnLocation());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Long counter = player.getPersistentDataContainer().get(KEY, PersistentDataType.LONG);
        if (counter == null || counter == manager.getCounter())
            return;

        kill(player, DamageSource.builder(DamageType.MAGIC).build());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        event.getPlayer().getPersistentDataContainer().set(KEY, PersistentDataType.LONG, manager.getCounter());
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        if (manager.contains(event.getRespawnLocation().getWorld()))
            return;
        event.setRespawnLocation(manager.overworld().getSpawnLocation());
    }

    @EventHandler(ignoreCancelled = true)
    public void onPortalEventReady(EntityPortalReadyEvent event) {
        World target = getTarget(event.getPortalType(), event.getEntity().getWorld().getEnvironment());
        if (target != null) event.setTargetWorld(target);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPortalEvent(EntityPortalEvent event) {
        if (event.getTo() == null)
            return;

        World target = getTarget(event.getPortalType(), event.getFrom().getWorld().getEnvironment());
        if (target == null) event.setCancelled(true);
        else event.getTo().setWorld(target);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerPortal(PlayerPortalEvent event) {
        PortalType type = switch (event.getCause()) {
            case END_PORTAL -> PortalType.ENDER;
            case NETHER_PORTAL -> PortalType.NETHER;
            case END_GATEWAY -> PortalType.END_GATEWAY;
            default -> null;
        };
        if (type == null) return;
        World target = getTarget(type, event.getFrom().getWorld().getEnvironment());
        if (target == null) event.setCancelled(true);
        else event.getTo().setWorld(target);
    }

    @Nullable
    private World getTarget(PortalType type, World.Environment source) {
        return switch (type) {
            case NETHER -> switch (source) {
                case NETHER -> manager.overworld();
                case THE_END -> null;
                default -> manager.nether();
            };
            case ENDER -> source == World.Environment.THE_END ? manager.overworld() : manager.end();
            default -> null;
        };
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerDeath(PlayerDeathEvent event) {
        if (!processing) {
            processing = true;
            manager.advance();

            DamageSource source = createSource(event.getDamageSource());
            for (Player target : getServer().getOnlinePlayers()) {
                if (target.isDead())
                    continue;

                kill(target, source);
            }

            processing = false;
        }

        manager.clear(event.getPlayer());

        ResetSettings settings = manager.getReset();
        if (!settings.inventory()) {
            event.getDrops().clear();
            event.setKeepInventory(true);
        }

        if (!settings.experience()) {
            event.setDroppedExp(0);
            event.setKeepLevel(true);
        }

        event.getPlayer().getPersistentDataContainer().set(KEY, PersistentDataType.LONG, manager.getCounter());
    }

    private void kill(@NotNull Player player, DamageSource source) {
        player.setHealth(1);
        player.setAbsorptionAmount(0);
        player.damage(20, source);
    }

    @NotNull
    private DamageSource createSource(@NotNull DamageSource original) {
        Entity cause = original.getCausingEntity();
        Entity direct = original.getDirectEntity();
        Location location = original.getDamageLocation();

        DamageSource.Builder builder = DamageSource.builder(DamageType.MAGIC);
        if (cause != null) builder.withCausingEntity(cause);
        if (direct != null) builder.withDirectEntity(direct);
        if (location != null) builder.withDamageLocation(location);
        return builder.build();
    }
}
