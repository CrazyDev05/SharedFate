package de.crazydev22.fate;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.logging.Level;
import java.util.logging.Logger;

public class DeletionQueue {
    private static final Set<DeletingTask> paths = ConcurrentHashMap.newKeySet();
    private final LinkedList<World> worlds = new LinkedList<>();

    private final ExecutorService service;
    private final Executor delayed;
    private final Logger logger;
    private volatile boolean shutdown;

    DeletionQueue(SharedFate plugin) {
        plugin.getServer().getGlobalRegionScheduler().runAtFixedRate(plugin, this::tick, 1L, 1L);
        service = Executors.newFixedThreadPool(3);
        delayed = CompletableFuture.delayedExecutor(2, TimeUnit.MINUTES, service);
        logger = plugin.getLogger();
    }

    public void add(@NotNull Collection<@NotNull World> worlds) {
        this.worlds.addAll(worlds);
    }

    public void shutdown() {
        service.shutdown();
        shutdown = true;
    }

    private void tick(@NotNull ScheduledTask task) {
        if (shutdown) {
            task.cancel();
            return;
        }

        if (worlds.isEmpty() || Bukkit.isTickingWorlds())
            return;

        List<World> failed = new LinkedList<>();
        long ms = System.currentTimeMillis() + 20;
        while (ms > System.currentTimeMillis()) {
            World world = worlds.poll();
            if (world == null)
                break;

            if (!Bukkit.unloadWorld(world, false)) {
                failed.add(world);
                continue;
            }
            delayed.execute(new DeletingTask(world.getWorldPath()));
        }
        worlds.addAll(failed);
    }

    private class DeletingTask extends SimpleFileVisitor<Path> implements Runnable {
        private final Path path;
        private int attempts = 0;

        private DeletingTask(Path path) {
            this.path = path;
            paths.add(this);
        }

        @Override
        public void run() {
            try {
                logger.log(Level.INFO, "Deleting world " + path);
                delete();
                paths.remove(this);
            } catch (IOException e) {
                synchronized (this) {
                    if (++attempts > 4) {
                        logger.log(Level.SEVERE, "Failed to delete world " + path, e);
                        return;
                    }
                }
                logger.log(Level.FINE, "Failed to delete world " + path + ", retrying in 5 seconds", e);
                delayed.execute(this);
            }
        }

        @Override
        public @NonNull FileVisitResult visitFile(@NonNull Path file, @NonNull BasicFileAttributes attrs) throws IOException {
            Files.delete(file);
            return FileVisitResult.CONTINUE;
        }

        @Override
        public @NonNull FileVisitResult postVisitDirectory(@NonNull Path dir, @Nullable IOException exc) throws IOException {
            Files.delete(dir);
            return FileVisitResult.CONTINUE;
        }

        private void delete() throws IOException {
            Files.walkFileTree(path, this);

            try {
                Path parent = path.getParent();
                if (parent != null) Files.deleteIfExists(parent);
            } catch (Throwable ignored) {}
        }
    }

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            for (DeletingTask task : paths) {
                try {
                    task.delete();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }));
    }
}
