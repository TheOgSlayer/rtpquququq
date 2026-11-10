package me.server.duels;

import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredListener;
import org.bukkit.plugin.RegisteredListener;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

public class DuelsPlugin extends JavaPlugin implements Listener, CommandExecutor {

    enum State { COUNTDOWN, FIGHT, COLLECT, ENDED }

    static class Duel {
        final UUID a, b;
        final Location center;
        Location deathLoc;
        int collectLeft;
        State state = State.COUNTDOWN;

        Duel(UUID a, UUID b, Location center) { this.a = a; this.b = b; this.center = center; this.deathLoc = center; }
        UUID other(UUID id) { return id.equals(a) ? b : a; }
    }

    /** Where to search: world + centre + radius. */
    static class Target {
        World world; int cx, cz, radius;
    }

    private static final Set<Material> BAD_GROUND = EnumSet.of(Material.LAVA, Material.WATER, Material.CACTUS,
            Material.MAGMA_BLOCK, Material.FIRE, Material.CAMPFIRE, Material.SWEET_BERRY_BUSH, Material.POWDER_SNOW);

    // ---- runtime state ----
    private final LinkedHashSet<UUID> queue = new LinkedHashSet<>();
    private final Set<UUID> matching = new HashSet<>();      // opponent found, arena being located
    private final Set<UUID> limbo = new HashSet<>();         // loser in spectator phase
    private final Set<UUID> pendingSpawn = new HashSet<>();  // left mid-fight, send to spawn on join
    private final Map<UUID, Duel> active = new HashMap<>();
    private final Map<UUID, Location> respawnAt = new HashMap<>();
    private final Set<UUID> forceTp = new HashSet<>();       // duel teleports other plugins may not cancel
    private final Map<UUID, String> cancelSeen = new HashMap<>();   // diagnostics: where a teleport got cancelled
    private Stats stats;

    // ---- config cache ----
    private String spawnCommand, fallbackWorld, accent;
    private Set<String> allowedBiomes, blockedBiomes, blocked;
    private int spawnDistance, countdown, spectateSeconds, collectSeconds, flatRadius, maxHeightDiff, maxAttempts;
    private double leaveDistance;
    private String swordIcon, skullIcon, trophyIcon;
    private boolean debug;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        var c = getConfig();
        spawnCommand = c.getString("spawn-command", "warp spawn");
        fallbackWorld = c.getString("fallback-world", "world");
        accent = c.getString("accent-color", "#00a2ff");
        debug = c.getBoolean("debug", false);
        allowedBiomes = lowerSet(c.getStringList("allowed-biomes"), true);
        blockedBiomes = lowerSet(c.getStringList("blocked-biomes"), false);
        blocked = c.getStringList("blocked-commands").stream()
                .map(s -> s.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
        spawnDistance = c.getInt("spawn-distance", 8);
        countdown = c.getInt("countdown-seconds", 3);
        spectateSeconds = c.getInt("loser-spectator-seconds", 5);
        collectSeconds = c.getInt("collect-seconds", 90);
        leaveDistance = c.getDouble("collect-leave-distance", 100);
        flatRadius = c.getInt("flat-check-radius", 16);
        maxHeightDiff = c.getInt("max-height-diff", 5);
        maxAttempts = c.getInt("max-search-attempts", 1500);
        swordIcon = c.getString("icons.sword", "⚔");
        skullIcon = c.getString("icons.skull", "☠");
        trophyIcon = c.getString("icons.trophy", "🏆");

        getLogger().info("RTPDuels v" + getDescription().getVersion() + " enabled.");
        stats = new Stats(new File(getDataFolder(), "stats.yml"));
        Bukkit.getPluginManager().registerEvents(this, this);
        getCommand("rtpqueue").setExecutor(this);

        // Watch our own duel teleports at every priority: record where a cancel first appears,
        // and at MONITOR (last) undo the cancel.
        for (EventPriority pr : EventPriority.values()) {
            Bukkit.getPluginManager().registerEvent(PlayerTeleportEvent.class, this, pr, (l, ev) -> {
                if (!(ev instanceof PlayerTeleportEvent e)) return;
                UUID id = e.getPlayer().getUniqueId();
                if (!forceTp.contains(id)) return;
                if (e.isCancelled()) cancelSeen.putIfAbsent(id, pr.name());
                if (pr == EventPriority.MONITOR && e.isCancelled()) e.setCancelled(false);
            }, this, false);
        }

        // "Finding an opponent" action bar
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            Set<UUID> all = new HashSet<>(queue);
            all.addAll(matching);
            for (UUID id : all) {
                Player p = Bukkit.getPlayer(id);
                if (p != null) bar(p, "Finding an " + blue("opponent") + " for duel...");
            }
        }, 0L, 20L);
    }

    @Override
    public void onDisable() { if (stats != null) stats.save(); }

    // ------------------------------------------------------------ helpers
    private Set<String> lowerSet(List<String> in, boolean stripNamespace) {
        Set<String> out = new HashSet<>();
        for (String s : in) {
            String v = s.toLowerCase(Locale.ROOT);
            if (stripNamespace) v = v.replace("minecraft:", "");
            out.add(v);
        }
        return out;
    }

    private void log(String s) { if (debug) getLogger().info("[debug] " + s); }

    private String blue(String s) { return ChatColor.of(accent) + s + ChatColor.WHITE; }

    private void bar(Player p, String legacy) {
        p.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(ChatColor.WHITE + legacy));
    }

    private void title(Player p, String t, int in, int stay, int out) { p.sendTitle(t, "", in, stay, out); }

    private String big(String hex, String text) { return ChatColor.of(hex) + "" + ChatColor.BOLD + text; }

    private String fmt(int s) { return s >= 60 ? (s / 60) + "m " + (s % 60) + "s" : s + "s"; }

    /** True while fighting / searching / loser spectating. NOT true while the winner is collecting. */
    /** Public so RTPMenu can refuse /rtp while a player is fighting. */
    public boolean isBusy(UUID id) { return inDuel(id); }

    private boolean inDuel(UUID id) {
        Duel d = active.get(id);
        return (d != null && d.state != State.COLLECT) || matching.contains(id) || limbo.contains(id);
    }

    private void ping(Player p) { p.playSound(p.getLocation(), "block.note_block.pling", 1f, 1.2f); }

    private void sendToSpawn(Player p) {
        if (!p.isOnline()) return;
        boolean ok = false;
        try { ok = p.performCommand(spawnCommand); } catch (Exception ignored) { }
        if (!ok) {
            World w = Bukkit.getWorld(fallbackWorld);
            if (w != null) p.teleport(w.getSpawnLocation());
        }
    }

    /** Reads the overworld target from JalebiRTP's config (if enabled) or from our own config. */
    private Target resolveTarget() {
        var c = getConfig();
        String wn = c.getString("world-name", "economy");
        int radius = c.getInt("search-radius", 3000), cx = 0, cz = 0;
        if (c.getBoolean("use-rtp-plugin-config", true)) {
            ConfigurationSection s = null;
            Plugin m = Bukkit.getPluginManager().getPlugin("RTPMenu");
            if (m != null && m.isEnabled())
                s = m.getConfig().getConfigurationSection("destinations." + c.getString("rtp-destination", "overworld"));
            if (s == null) {   // legacy JalebiRTP
                Plugin j = Bukkit.getPluginManager().getPlugin("JalebiRTP");
                if (j != null && j.isEnabled()) s = j.getConfig().getConfigurationSection("dimensions.overworld");
            }
            if (s != null) {
                wn = s.getString("world", wn);
                radius = s.getInt("radius", radius);
                cx = s.getInt("center-x", 0);
                cz = s.getInt("center-z", 0);
            }
        }
        World w = Bukkit.getWorld(wn);
        if (w == null) return null;
        Target t = new Target();
        t.world = w; t.cx = cx; t.cz = cz; t.radius = Math.max(1, radius);
        return t;
    }

    // ------------------------------------------------------------ /rtpq
    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] args) {
        if (!(s instanceof Player p)) { s.sendMessage("Players only."); return true; }
        UUID id = p.getUniqueId();

        if (args.length > 0 && args[0].equalsIgnoreCase("leave")) {
            if (queue.remove(id)) p.sendMessage(ChatColor.WHITE + "You left the duel queue.");
            else p.sendMessage(ChatColor.RED + "You are not in the queue.");
            return true;
        }
        if (inDuel(id)) { p.sendMessage(ChatColor.RED + "You are already in a duel."); return true; }

        // winner still collecting: queueing ends the collection (no spawn teleport)
        Duel d = active.get(id);
        if (d != null && d.state == State.COLLECT) endCollectEarly(d, p);

        if (queue.contains(id)) {
            p.sendMessage(ChatColor.RED + "You are already in the queue. Use /rtpq leave to leave.");
            return true;
        }
        if (resolveTarget() == null) {
            p.sendMessage(ChatColor.RED + "The duel world was not found. Check the config.");
            return true;
        }
        queue.add(id);
        tryMatch();
        return true;
    }

    // ------------------------------------------------------------ matchmaking
    private void tryMatch() {
        while (queue.size() >= 2) {
            Iterator<UUID> it = queue.iterator();
            UUID a = it.next(), b = it.next();
            queue.remove(a);
            queue.remove(b);
            Player pa = Bukkit.getPlayer(a), pb = Bukkit.getPlayer(b);
            if (pa == null || pb == null) {
                if (pa != null) queue.add(a);
                if (pb != null) queue.add(b);
                continue;
            }
            startSearch(pa, pb);
        }
    }

    private void startSearch(Player pa, Player pb) {
        UUID a = pa.getUniqueId(), b = pb.getUniqueId();
        matching.add(a);
        matching.add(b);
        Target target = resolveTarget();
        log("searching arena in " + (target == null ? "null" : target.world.getName()));

        new BukkitRunnable() {
            int tries = 0;

            private void giveUp(String msg) {
                cancel();
                matching.remove(a);
                matching.remove(b);
                if (pa.isOnline()) { queue.add(a); if (msg != null) pa.sendMessage(ChatColor.RED + msg); }
                if (pb.isOnline()) { queue.add(b); if (msg != null) pb.sendMessage(ChatColor.RED + msg); }
                tryMatch();
            }

            @Override
            public void run() {
                if (!pa.isOnline() || !pb.isOnline() || target == null) { giveUp(null); return; }
                for (int i = 0; i < 2; i++) {
                    tries++;
                    Location[] spots;
                    try { spots = tryFind(target); }
                    catch (Exception ex) { getLogger().warning("Arena search error: " + ex); spots = null; }
                    if (spots != null) {
                        cancel();
                        log("arena found after " + tries + " attempts at " + spots[2]);
                        beginDuel(pa, pb, spots);
                        return;
                    }
                }
                if (tries >= maxAttempts) giveUp("Couldn't find a good duel arena, you were put back in the queue.");
            }
        }.runTaskTimer(this, 1L, 1L);
    }

    private boolean biomeOk(Biome biome) {
        String n = biome.getKey().getKey().toLowerCase(Locale.ROOT);
        for (String bad : blockedBiomes) if (n.contains(bad)) return false;
        return allowedBiomes.isEmpty() || allowedBiomes.contains(n);
    }

    private int top(World w, int x, int z) { return w.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES); }

    /** One random attempt. Returns {spawnA, spawnB, centre} or null. */
    private Location[] tryFind(Target t) {
        World w = t.world;
        ThreadLocalRandom r = ThreadLocalRandom.current();
        int x = t.cx + r.nextInt(-t.radius, t.radius + 1);
        int z = t.cz + r.nextInt(-t.radius, t.radius + 1);
        if (!w.getWorldBorder().isInside(new Location(w, x, 64, z))) return null;

        int R = Math.max(flatRadius, spawnDistance + 4);

        // cheap biome pre-check (no chunk loading)
        int[][] pts = {{0, 0}, {R, 0}, {-R, 0}, {0, R}, {0, -R}, {R, R}, {-R, -R}, {R, -R}, {-R, R}};
        for (int[] o : pts) if (!biomeOk(w.getBiome(x + o[0], 64, z + o[1]))) return null;

        // flatness / liquids / hazards
        int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
        for (int dx = -R; dx <= R; dx += R) {
            for (int dz = -R; dz <= R; dz += R) {
                int y = top(w, x + dx, z + dz);
                Block b = w.getBlockAt(x + dx, y, z + dz);
                if (b.isLiquid() || BAD_GROUND.contains(b.getType())) return null;
                min = Math.min(min, y);
                max = Math.max(max, y);
            }
        }
        if (max - min > maxHeightDiff) return null;

        int cy = top(w, x, z);
        if (!biomeOk(w.getBiome(x, cy, z))) return null;

        Location a = spot(w, x - spawnDistance, z);
        Location b = spot(w, x + spawnDistance, z);
        if (a == null || b == null) return null;
        a.setYaw(-90f);
        b.setYaw(90f);
        return new Location[]{a, b, new Location(w, x + 0.5, cy, z + 0.5)};
    }

    private Location spot(World w, int x, int z) {
        int y = top(w, x, z);
        Block t = w.getBlockAt(x, y, z);
        if (t.isLiquid() || BAD_GROUND.contains(t.getType()) || y <= w.getMinHeight()) return null;
        return new Location(w, x + 0.5, y + 1, z + 0.5);
    }

    // ------------------------------------------------------------ duel start
    private void prepare(Player p) {
        p.closeInventory();   // stops anyone clicking /rtp menu buttons during the duel
        p.leaveVehicle();
        p.eject();
        p.setGameMode(GameMode.SURVIVAL);
        p.setHealth(p.getMaxHealth());
        p.setFoodLevel(20);
        p.setSaturation(20f);
        p.setFireTicks(0);
        p.setFallDistance(0f);
    }

    /** Teleports (loading the chunk first) and verifies it worked, retrying a few times. */
    private void doTeleport(Player p, Location loc, int attempt) {
        if (!p.isOnline()) return;
        loc.getWorld().getChunkAt(loc);
        PlayerTeleportEvent.TeleportCause cause = attempt == 1 ? PlayerTeleportEvent.TeleportCause.PLUGIN
                : attempt == 2 ? PlayerTeleportEvent.TeleportCause.COMMAND : PlayerTeleportEvent.TeleportCause.UNKNOWN;
        UUID id = p.getUniqueId();
        cancelSeen.remove(id);
        boolean ok;
        forceTp.add(id);
        try { ok = p.teleport(loc, cause); }
        finally { forceTp.remove(id); }
        log("teleport " + p.getName() + " attempt " + attempt + " (" + cause + ") -> " + (ok ? "ok" : "FAILED") + " " + loc);
        if (!ok) {
            getLogger().warning("Teleport of " + p.getName() + " to the duel arena failed (attempt " + attempt
                    + ", cause " + cause + ").");
            if (attempt == 1) {
                getLogger().warning("  cancel first visible at event priority: "
                        + cancelSeen.getOrDefault(id, "none (not a plain event cancel)"));
                getLogger().warning("  player: dead=" + p.isDead() + " vehicle=" + p.isInsideVehicle()
                        + " passengers=" + p.getPassengers().size() + " gamemode=" + p.getGameMode());
                getLogger().warning("  target world: " + loc.getWorld().getName() + ", loaded="
                        + (Bukkit.getWorld(loc.getWorld().getName()) != null));
                getLogger().warning("  other plugins listening to teleports: " + teleportListeners());
            }
        }
            if (attempt == 1) diagnose(p, loc);
        }

        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!p.isOnline()) return;
            Duel d = active.get(p.getUniqueId());
            if (d == null || d.state != State.COUNTDOWN) return;
            boolean there = p.getWorld().equals(loc.getWorld()) && p.getLocation().distanceSquared(loc) < 100;
            if (there) return;
            if (attempt < 3) { doTeleport(p, loc, attempt + 1); return; }
            getLogger().warning("Could not teleport " + p.getName() + " to the duel arena after 3 attempts. "
                    + "Cancelling the duel.");
            abortDuel(d);
        }, 3L);
    }

    /** Finds out which plugin cancels a teleport to the arena by replaying a test event through each listener. */
    private void diagnose(Player p, Location loc) {
        try {
            PlayerTeleportEvent ev = new PlayerTeleportEvent(p, p.getLocation(), loc,
                    PlayerTeleportEvent.TeleportCause.PLUGIN);
            StringBuilder all = new StringBuilder();
            boolean found = false;
            for (RegisteredListener rl : PlayerTeleportEvent.getHandlerList().getRegisteredListeners()) {
                all.append(rl.getPlugin().getName()).append(" (").append(rl.getPriority()).append(")  ");
                if (found) continue;
                try { rl.callEvent(ev); } catch (Exception ignored) { }
                if (ev.isCancelled()) {
                    found = true;
                    getLogger().warning("DIAGNOSIS: the teleport is cancelled by the plugin '"
                            + rl.getPlugin().getName() + "' (priority " + rl.getPriority() + ").");
                }
            }
            if (!found) getLogger().warning("DIAGNOSIS: no plugin cancels a test teleport, so it is refused for "
                    + "another reason (player riding/carrying something, dead, ...).");
            getLogger().warning("DIAGNOSIS: plugins listening to teleports: " + all);
        } catch (Exception e) {
            getLogger().warning("DIAGNOSIS failed: " + e);
        }
    }

    private String teleportListeners() {
        StringBuilder sb = new StringBuilder();
        for (RegisteredListener rl : PlayerTeleportEvent.getHandlerList().getRegisteredListeners()) {
            if (rl.getPlugin() == this) continue;
            sb.append(rl.getPlugin().getName()).append('@').append(rl.getPriority()).append(", ");
        }
        return sb.length() == 0 ? "none" : sb.toString();
    }

    private void abortDuel(Duel d) {
        d.state = State.ENDED;
        active.remove(d.a);
        active.remove(d.b);
        for (UUID id : new UUID[]{d.a, d.b}) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) p.sendMessage(ChatColor.RED + "The duel was cancelled: couldn't teleport to the arena.");
        }
    }

    private void beginDuel(Player pa, Player pb, Location[] spots) {
        UUID a = pa.getUniqueId(), b = pb.getUniqueId();
        matching.remove(a);
        matching.remove(b);
        Duel d = new Duel(a, b, spots[2]);
        active.put(a, d);
        active.put(b, d);
        prepare(pa);
        prepare(pb);
        doTeleport(pa, spots[0], 1);
        doTeleport(pb, spots[1], 1);

        // opponent winrate on the hotbar (repeated so it stays visible)
        new BukkitRunnable() {
            int i = 0;

            @Override
            public void run() {
                if ((d.state != State.COUNTDOWN && d.state != State.FIGHT) || i++ >= 8) { cancel(); return; }
                winrateBar(pa, b);
                winrateBar(pb, a);
            }
        }.runTaskTimer(this, 0L, 20L);

        // 3..2..1..FIGHT!
        new BukkitRunnable() {
            int n = countdown;

            @Override
            public void run() {
                if (d.state != State.COUNTDOWN) { cancel(); return; }
                Player x = Bukkit.getPlayer(a), y = Bukkit.getPlayer(b);
                if (n > 0) {
                    for (Player p : new Player[]{x, y}) {
                        if (p == null) continue;
                        title(p, big(accent, String.valueOf(n)), 0, 25, 0);
                        ping(p);
                    }
                    n--;
                    return;
                }
                d.state = State.FIGHT;
                for (Player p : new Player[]{x, y}) {
                    if (p == null) continue;
                    title(p, big("#ffd21f", swordIcon + " FIGHT! " + swordIcon), 0, 30, 10);
                    p.playSound(p.getLocation(), "entity.ender_dragon.growl", 0.6f, 1f);
                }
                cancel();
            }
        }.runTaskTimer(this, 0L, 20L);
    }

    private void winrateBar(Player viewer, UUID opponent) {
        int total = stats.total(opponent);
        if (total == 0) {
            bar(viewer, "This is the first match of your " + blue("opponent"));
            return;
        }
        double wr = stats.wins(opponent) * 100.0 / total;
        String s = String.format(Locale.US, "%.1f", wr);
        if (s.endsWith(".0")) s = s.substring(0, s.length() - 2);
        bar(viewer, "Your opponent has a winrate of " + blue(s + "%") + ".");
    }

    // ------------------------------------------------------------ death / results
    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent e) {
        Player loser = e.getEntity();
        UUID lid = loser.getUniqueId();
        Duel d = active.get(lid);
        if (d == null || d.state == State.ENDED) return;

        // winner died while collecting -> end it
        if (d.state == State.COLLECT) {
            d.state = State.ENDED;
            active.remove(d.a);
            active.remove(d.b);
            clearItems(d);
            return;
        }

        UUID wid = d.other(lid);
        Player winner = Bukkit.getPlayer(wid);
        d.state = State.COLLECT;
        d.deathLoc = loser.getLocation();
        e.setKeepInventory(false);   // loser drops everything
        e.setKeepLevel(false);
        e.setDeathMessage(null);
        stats.record(wid, lid);

        active.remove(lid);
        boolean quit = pendingSpawn.contains(lid);

        if (!quit) {
            limbo.add(lid);
            respawnAt.put(lid, d.deathLoc);
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (loser.isOnline() && loser.isDead()) loser.spigot().respawn();
            }, 2L);
            Bukkit.getScheduler().runTaskLater(this, () -> loserPhase(loser), 6L);
        }

        if (winner != null && winner.isOnline()) startCollect(d, winner);
        else {
            d.state = State.ENDED;
            active.remove(wid);
            Bukkit.getScheduler().runTaskLater(this, () -> clearItems(d), 40L);
        }
    }

    private void loserPhase(Player loser) {
        UUID id = loser.getUniqueId();
        if (!loser.isOnline()) { limbo.remove(id); pendingSpawn.add(id); return; }
        loser.setGameMode(GameMode.SPECTATOR);
        title(loser, big("#ff3b4e", skullIcon + " DEFEAT " + skullIcon), 0, spectateSeconds * 20, 10);
        Bukkit.getScheduler().runTaskLater(this, () -> {
            limbo.remove(id);
            if (loser.isOnline()) {
                loser.setGameMode(GameMode.SURVIVAL);
                sendToSpawn(loser);
            } else pendingSpawn.add(id);
        }, spectateSeconds * 20L);
    }

    private boolean hasLeft(Player w, Duel d) {
        Location l = w.getLocation();
        if (l.getWorld() != d.deathLoc.getWorld()) return true;
        return l.distanceSquared(d.deathLoc) > leaveDistance * leaveDistance;
    }

    private void startCollect(Duel d, Player winner) {
        UUID wid = winner.getUniqueId();
        d.collectLeft = collectSeconds;
        title(winner, big("#ffc400", trophyIcon + " VICTORY " + trophyIcon), 0, 60, 20);
        winner.playSound(winner.getLocation(), "ui.toast.challenge_complete", 1f, 1f);

        new BukkitRunnable() {
            int left = collectSeconds;

            @Override
            public void run() {
                if (d.state != State.COLLECT) { cancel(); return; }
                Player w = Bukkit.getPlayer(wid);
                if (w == null || !w.isOnline()) { cancel(); endCollectEarly(d, null); return; }
                if (hasLeft(w, d)) { cancel(); endCollectEarly(d, w); return; }   // rtp'd / left the arena
                if (left <= 0) { cancel(); finishCollect(d, w); return; }
                bar(w, "You have " + blue(fmt(left)) + " to collect the items.");
                d.collectLeft = left;
                left--;
            }
        }.runTaskTimer(this, 0L, 20L);
    }

    /** Timer ran out while the winner was still in the arena: send them to spawn. */
    private void finishCollect(Duel d, Player winner) {
        d.state = State.ENDED;
        active.remove(d.a);
        active.remove(d.b);
        clearItems(d);
        if (winner != null) sendToSpawn(winner);
    }

    /** Winner left the arena (rtp, command, quit...): remove the timer, do NOT teleport them. */
    private void endCollectEarly(Duel d, Player winner) {
        if (d.state != State.COLLECT) return;
        d.state = State.ENDED;
        active.remove(d.a);
        active.remove(d.b);
        if (winner != null && winner.isOnline()) bar(winner, "");
        // leftover items disappear when the original timer would have ended
        Bukkit.getScheduler().runTaskLater(this, () -> clearItems(d), Math.max(1, d.collectLeft) * 20L);
    }

    private void clearItems(Duel d) {
        clearAround(d.deathLoc, leaveDistance);
        clearAround(d.center, 60);
    }

    private void clearAround(Location loc, double r) {
        World w = loc.getWorld();
        if (w == null) return;
        for (Entity en : w.getNearbyEntities(loc, r, 400, r)) {
            if (en instanceof Item) en.remove();
        }
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Location l = respawnAt.remove(e.getPlayer().getUniqueId());
        if (l != null) e.setRespawnLocation(l);
    }

    // ------------------------------------------------------------ misc events
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        if (!inDuel(e.getPlayer().getUniqueId())) return;
        String msg = e.getMessage().substring(1).trim().toLowerCase(Locale.ROOT);
        String label = msg.split("\\s+")[0];
        int colon = label.indexOf(':');
        if (colon >= 0) label = label.substring(colon + 1);
        if (blocked.contains(label)) {
            e.setCancelled(true);
            e.getPlayer().sendMessage(ChatColor.RED + "You can't use that command during a duel.");
        }
    }

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        Duel d = active.get(e.getPlayer().getUniqueId());
        if (d == null || d.state != State.COUNTDOWN) return;
        Location from = e.getFrom(), to = e.getTo();
        if (to == null || from.getWorld() != to.getWorld()) return;
        if (from.getX() != to.getX() || from.getZ() != to.getZ()) {
            Location f = from.clone();
            f.setYaw(to.getYaw());
            f.setPitch(to.getPitch());
            e.setTo(f);
        }
    }

    @EventHandler
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        Duel d = active.get(p.getUniqueId());
        if (d != null && d.state == State.COUNTDOWN) e.setCancelled(true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();
        queue.remove(id);
        Duel d = active.get(id);
        if (d != null) {
            if (d.state == State.FIGHT || d.state == State.COUNTDOWN) {
                pendingSpawn.add(id);   // leaving mid-fight = loss
                p.setHealth(0.0);
            } else if (d.state == State.COLLECT) {
                endCollectEarly(d, null);   // winner left: no spawn teleport
            }
        } else if (limbo.contains(id)) {
            pendingSpawn.add(id);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (!pendingSpawn.remove(p.getUniqueId())) return;
        limbo.remove(p.getUniqueId());
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!p.isOnline()) return;
            if (p.isDead()) p.spigot().respawn();
            p.setGameMode(GameMode.SURVIVAL);
            Bukkit.getScheduler().runTaskLater(this, () -> sendToSpawn(p), 5L);
        }, 10L);
    }
}
