package nl.kmc.game.api;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;

/**
 * Spawns "bot" players that are real {@link Player}s as far as every plugin is concerned (they show
 * up in {@code Bukkit.getOnlinePlayers()}, fire join events, take damage, can be teleported) but have
 * no client behind them: they just stand where they are put. Meant for testing games alone.
 *
 * <p>There is no Bukkit API for this, so it builds a server-side player with a dummy in-memory
 * network channel via reflection on the server internals (Mojang-mapped names, Paper 1.20.5+).
 * Reflection — not compile-time references — keeps the normal build independent of server internals;
 * the price is that a future Minecraft update could rename something, in which case {@link #spawn}
 * throws a clear exception rather than breaking anything else.
 */
public final class FakePlayerSpawner {

    private static final class Bot {
        final Object handle;    // net.minecraft.server.level.ServerPlayer
        final Object channel;   // io.netty.channel.embedded.EmbeddedChannel
        final Player player;
        Bot(Object handle, Object channel, Player player) { this.handle = handle; this.channel = channel; this.player = player; }
    }

    private static final Map<UUID, Bot> BOTS = new LinkedHashMap<>();
    private static BukkitTask tickTask;
    private static long tickCount;

    private FakePlayerSpawner() {}

    // ── Public API ────────────────────────────────────────────────────────────

    /** True if {@code player} is one of our bots. */
    public static boolean isBot(Player player) { return player != null && BOTS.containsKey(player.getUniqueId()); }

    public static Collection<Player> bots() {
        List<Player> out = new ArrayList<>();
        for (Bot b : BOTS.values()) out.add(b.player);
        return out;
    }

    /**
     * Creates a bot called {@code name} (max 16 chars) at {@code at}.
     *
     * @throws IllegalStateException if the name is taken or the server internals aren't what we expect
     */
    public static Player spawn(JavaPlugin plugin, String name, Location at) {
        if (name.length() > 16) throw new IllegalStateException("Naam te lang (max 16 tekens): " + name);
        if (Bukkit.getPlayerExact(name) != null) throw new IllegalStateException("Er is al een speler met de naam " + name);
        try {
            return doSpawn(plugin, name, at);
        } catch (ReflectiveOperationException | LinkageError e) {
            throw new IllegalStateException("Fake player maken mislukt (server-internals anders dan verwacht?): " + rootMessage(e), e);
        }
    }

    /** Removes one bot. Returns false if it isn't a bot. */
    public static boolean remove(Player player) {
        Bot bot = player == null ? null : BOTS.remove(player.getUniqueId());
        if (bot == null) return false;
        try {
            Object playerList = call(call(Bukkit.getServer(), "getServer"), "getPlayerList");
            Method remove = findMethod(playerList.getClass(), "remove", cls("net.minecraft.server.level.ServerPlayer"));
            remove.invoke(playerList, bot.handle);
        } catch (ReflectiveOperationException e) {
            // Fall back to the API: a kick tears the connection down the normal way.
            player.kick();
        }
        stopTickIfIdle();
        return true;
    }

    public static int removeAll() {
        int n = 0;
        for (Player p : new ArrayList<>(bots())) if (remove(p)) n++;
        return n;
    }

    // ── Spawning ──────────────────────────────────────────────────────────────

    private static Player doSpawn(JavaPlugin plugin, String name, Location at) throws ReflectiveOperationException {
        Object server   = call(Bukkit.getServer(), "getServer");          // MinecraftServer
        Object level    = call(at.getWorld(), "getHandle");                 // ServerLevel
        UUID uuid       = UUID.nameUUIDFromBytes(("KMCBot:" + name).getBytes(java.nio.charset.StandardCharsets.UTF_8));

        Class<?> profileC = cls("com.mojang.authlib.GameProfile");
        Object profile = profileC.getConstructor(UUID.class, String.class).newInstance(uuid, name);

        Class<?> infoC = cls("net.minecraft.server.level.ClientInformation");
        Object info = infoC.getMethod("createDefault").invoke(null);

        Class<?> playerC = cls("net.minecraft.server.level.ServerPlayer");
        Object handle = newInstance(playerC, server, level, profile, info);

        // Dummy network connection: a Connection bolted onto an in-memory channel nobody reads.
        Class<?> connC = cls("net.minecraft.network.Connection");
        Class<?> flowC = cls("net.minecraft.network.protocol.PacketFlow");
        @SuppressWarnings({"unchecked", "rawtypes"})
        Object serverbound = Enum.valueOf((Class<Enum>) flowC.asSubclass(Enum.class), "SERVERBOUND");
        Object connection = connC.getConstructor(flowC).newInstance(serverbound);

        Class<?> handlerC = cls("io.netty.channel.ChannelHandler");
        Object handlers = Array.newInstance(handlerC, 1);
        Array.set(handlers, 0, connection);
        Object channel = cls("io.netty.channel.embedded.EmbeddedChannel").getConstructor(handlers.getClass()).newInstance(handlers);

        Object cookie = cls("net.minecraft.server.network.CommonListenerCookie")
                .getMethod("createInitial", profileC, boolean.class).invoke(null, profile, false);

        Object playerList = call(server, "getPlayerList");
        findMethod(playerList.getClass(), "placeNewPlayer", connC, playerC, cookie.getClass())
                .invoke(playerList, connection, handle, cookie);

        Player player = (Player) call(handle, "getBukkitEntity");
        player.teleport(at);

        BOTS.put(uuid, new Bot(handle, channel, player));
        startTick(plugin);
        return player;
    }

    // ── Ticking ───────────────────────────────────────────────────────────────

    /**
     * A real player is ticked by its network handler; ours has none, so tick it ourselves — that's what
     * makes a bot fall, take damage and have potion effects tick like anyone else. Also keeps the dummy
     * channel from piling up outgoing packets, and respawns a dead bot so it doesn't sit on a death screen.
     */
    private static void startTick(JavaPlugin plugin) {
        if (tickTask != null) return;
        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            tickCount++;
            for (Bot bot : new ArrayList<>(BOTS.values())) {
                try {
                    call(bot.handle, "doTick");
                    if (tickCount % 20 == 0) {
                        Queue<?> out = (Queue<?>) call(bot.channel, "outboundMessages");
                        out.clear();
                        if (bot.player.isDead()) bot.player.spigot().respawn();
                    }
                } catch (ReflectiveOperationException | RuntimeException e) {
                    plugin.getLogger().warning("Bot " + bot.player.getName() + " tick failed: " + rootMessage(e));
                }
            }
        }, 1L, 1L);
    }

    private static void stopTickIfIdle() {
        if (BOTS.isEmpty() && tickTask != null) { tickTask.cancel(); tickTask = null; }
    }

    // ── Reflection helpers ────────────────────────────────────────────────────

    private static Class<?> cls(String name) throws ClassNotFoundException { return Class.forName(name); }

    /** Calls a public no-arg method by name, looked up on the runtime class. */
    private static Object call(Object target, String method) throws ReflectiveOperationException {
        return findMethod(target.getClass(), method).invoke(target);
    }

    private static Method findMethod(Class<?> type, String name, Class<?>... params) throws NoSuchMethodException {
        for (Method m : type.getMethods()) {
            if (!m.getName().equals(name) || m.getParameterCount() != params.length) continue;
            Class<?>[] have = m.getParameterTypes();
            boolean ok = true;
            for (int i = 0; i < have.length && ok; i++) ok = have[i].isAssignableFrom(params[i]);
            if (ok) { m.setAccessible(true); return m; }
        }
        throw new NoSuchMethodException(type.getName() + "#" + name);
    }

    private static Object newInstance(Class<?> type, Object... args) throws ReflectiveOperationException {
        for (Constructor<?> c : type.getConstructors()) {
            if (c.getParameterCount() != args.length) continue;
            Class<?>[] have = c.getParameterTypes();
            boolean ok = true;
            for (int i = 0; i < have.length && ok; i++) ok = have[i].isInstance(args[i]);
            if (ok) return c.newInstance(args);
        }
        throw new NoSuchMethodException("No matching constructor on " + type.getName());
    }

    private static String rootMessage(Throwable t) {
        while (t.getCause() != null) t = t.getCause();
        return t.getClass().getSimpleName() + (t.getMessage() != null ? ": " + t.getMessage() : "");
    }
}
