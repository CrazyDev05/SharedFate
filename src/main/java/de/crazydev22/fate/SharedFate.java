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
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class SharedFate extends JavaPlugin implements Listener {
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
        if (processing)
            return;

        processing = true;
        Location spawn = Bukkit.getWorld(NamespacedKey.minecraft("overworld"))
                .getSpawnLocation();

        Player player = event.getPlayer();
        DamageSource source = createSource(event.getDamageSource());
        for (Player target : getServer().getOnlinePlayers()) {
            if (target.isDead())
                continue;
            target.damage(Double.MAX_VALUE, source);

            target.setRespawnLocation(null);
            target.teleport(spawn);
        }
        player.setRespawnLocation(null);
        player.teleport(spawn);

        manager.advance();
        processing = false;
    }

    @NotNull
    private DamageSource createSource(@NotNull DamageSource original) {
        Entity cause = original.getCausingEntity();
        Entity direct = original.getCausingEntity();
        Location location = original.getDamageLocation();

        DamageSource.Builder builder = DamageSource.builder(DamageType.INDIRECT_MAGIC);
        if (cause != null) builder.withCausingEntity(cause);
        if (direct != null) builder.withDirectEntity(direct);
        if (location != null) builder.withDamageLocation(location);
        return builder.build();
    }
}
