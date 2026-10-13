package nl.kmc.crown;

import nl.kmc.core.domain.GameRegistration;
import nl.kmc.core.domain.PointAward;
import nl.kmc.game.api.arcade.ArcadeGameManager;
import nl.kmc.game.api.arcade.ArcadeLogic.Cooldowns;
import nl.kmc.game.api.arcade.ArcadeLogic.Region;
import nl.kmc.game.api.arcade.ArcadeLogic.TeamEnergy;
import nl.kmc.game.api.arcade.ArenaConfig;
import nl.kmc.stats.service.StatisticsService;
import org.bukkit.*;
import org.bukkit.boss.BarColor;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.*;

/**
 * Crown Control: several crowns lie in the arena. Whoever picks one up wears it and earns points for as long
 * as he keeps it; an enemy takes it by hitting the carrier a few times or by knocking him out. The team that
 * wears crowns earns energy, and energy buys short team abilities that only a crown-wearing team can use.
 * Spending is atomic ({@link TeamEnergy#trySpend}): a refused activation never costs anything.
 */
public final class CrownControlManager extends ArcadeGameManager {

    /** One crown: lying somewhere, or worn by a player. */
    private static final class Crown {
        ItemDisplay display;
        Location at;
        UUID carrier;
        int hits;
        long freeFrom;
        long lastStealTick = -100000;
    }

    private final CrownControlPlugin plugin;
    private Region crownZone;
    private long durationTicks;
    private final List<Crown> crowns = new ArrayList<>();
    private final Map<UUID, Crown> carrying = new HashMap<>();

    private final Map<String, TeamEnergy> energy = new HashMap<>();
    private final Cooldowns cooldowns = new Cooldowns();
    private final Map<String, Long> doubleUntil = new HashMap<>();
    private final Map<String, Long> overdriveUntil = new HashMap<>();
    private final Map<String, Long> fortifyUntil = new HashMap<>();
    private String lockdownBy;
    private long lockdownUntil;
    private String bountyBy, bountyTarget;
    private long bountyUntil;
    private final Map<UUID, Integer> contribution = new HashMap<>();

    public CrownControlManager(CrownControlPlugin plugin, GameRegistration reg, StatisticsService stats, ArenaConfig arena) {
        super(plugin, reg, stats, arena);
        this.plugin = plugin;
    }

    // ── Hooks ─────────────────────────────────────────────────────────────────

    @Override protected String titleColored() { return "§6§lCROWN CONTROL"; }
    @Override protected String introSubKey() { return "@crown.intro-sub"; }
    @Override protected List<String> tutorialKeys() {
        return List.of("@crown.tut.1", "@crown.tut.2", "@crown.tut.3", "@crown.tut.4");
    }
    @Override protected Combat combat() { return Combat.TEAM_PVP; }

    @Override
    protected List<String> arenaProblems() {
        List<String> p = new ArrayList<>();
        if (arena.world() == null) p.add("geen arena-wereld (/crown world)");
        if (arena.region("zone") == null) p.add("geen kroon-zone (/crown pos1 + pos2 + zone)");
        if (arena.locs("spawns").isEmpty()) p.add("geen spawns (/crown spawn add)");
        return p;
    }

    @Override protected List<Location> spawnPoints() { return arena.locs("spawns"); }

    @Override
    protected Location flyoverCenter() {
        Region r = arena.region("zone");
        World w = arena.world();
        return r == null || w == null ? null : new Location(w, r.centerX(), r.centerY() + 3, r.centerZ());
    }

    @Override
    protected void prepareGame() {
        var cfg = gamePlugin.getConfig();
        crownZone = arena.region("zone");
        durationTicks = cfg.getInt("game.duration-seconds", 180) * 20L;
        energy.clear();
        cooldowns.clear();
        doubleUntil.clear();
        overdriveUntil.clear();
        fortifyUntil.clear();
        contribution.clear();
        carrying.clear();
        lockdownBy = null;
        lockdownUntil = 0;
        bountyBy = bountyTarget = null;
        bountyUntil = 0;
        for (String t : activeTeams()) energy.put(t, new TeamEnergy(cfg.getDouble("energy.capacity", 100)));
        placeCrowns();
    }

    @Override
    protected void beginPlay() {
        for (UUID id : players) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;
            giveKit(p);
            giveMenuItem(p);
        }
    }

    @Override protected void onRespawn(Player p) { giveMenuItem(p); }

    private void giveMenuItem(Player p) {
        ItemStack star = new ItemStack(Material.NETHER_STAR);
        ItemMeta m = star.getItemMeta();
        m.setDisplayName(api.tr(p.getUniqueId(), "crown.menu-item"));
        star.setItemMeta(m);
        p.getInventory().setItem(8, star);
    }

    // ── Crowns ────────────────────────────────────────────────────────────────

    private void placeCrowns() {
        removeCrownDisplays();
        crowns.clear();
        World w = arena.world();
        if (w == null || crownZone == null) return;
        int n = Math.max(1, gamePlugin.getConfig().getInt("crowns.count", 3));
        double rad = Math.max(0, Math.min(crownZone.sizeX(), crownZone.sizeZ()) / 2.0 - 2.0);
        for (int i = 0; i < n; i++) {
            double a = 2 * Math.PI * i / n;
            double r = n == 1 ? 0 : rad;
            Crown c = new Crown();
            c.at = new Location(w, crownZone.centerX() + Math.cos(a) * r, crownZone.minY(), crownZone.centerZ() + Math.sin(a) * r);
            crowns.add(c);
            spawnDisplay(c);
        }
    }

    private void spawnDisplay(Crown c) {
        if (c.display != null && c.display.isValid()) c.display.remove();
        World w = c.at.getWorld();
        if (w == null) return;
        c.display = w.spawn(c.at.clone().add(0, 1.4, 0), ItemDisplay.class, d -> {
            d.setItemStack(new ItemStack(Material.GOLDEN_HELMET));
            d.setBillboard(Display.Billboard.CENTER);
            d.setGlowing(true);
            d.setPersistent(false);
            d.setTransformation(new org.bukkit.util.Transformation(new org.joml.Vector3f(),
                    new org.joml.Quaternionf(), new org.joml.Vector3f(1.8f, 1.8f, 1.8f), new org.joml.Quaternionf()));
        });
    }

    private void removeCrownDisplays() {
        for (Crown c : crowns) if (c.display != null && c.display.isValid()) c.display.remove();
    }

    private ItemStack crownItem(UUID id) {
        ItemStack it = new ItemStack(Material.GOLDEN_HELMET);
        ItemMeta m = it.getItemMeta();
        m.setDisplayName(api.tr(id, "crown.item"));
        m.setUnbreakable(true);
        it.setItemMeta(m);
        return it;
    }

    private Location clampToZone(Location l) {
        double x = Math.max(crownZone.minX() + 0.5, Math.min(crownZone.maxX() - 0.5, l.getX()));
        double z = Math.max(crownZone.minZ() + 0.5, Math.min(crownZone.maxZ() - 0.5, l.getZ()));
        double y = Math.max(crownZone.minY(), l.getY());
        return new Location(arena.world(), x, y, z);
    }

    private void giveTo(Crown c, Player p) {
        UUID id = p.getUniqueId();
        c.carrier = id;
        c.hits = 0;
        carrying.put(id, c);
        if (c.display != null && c.display.isValid()) c.display.remove();
        c.display = null;
        p.getInventory().setHelmet(crownItem(id));
        p.setGlowing(true);
        p.playSound(p.getLocation(), Sound.ITEM_ARMOR_EQUIP_GOLD, 1f, 1.2f);
        contribution.merge(id, 1, Integer::sum);
    }

    /** Takes the crown off its carrier and lays it down at {@code where}. */
    private void drop(Crown c, Location where) {
        UUID old = c.carrier;
        c.carrier = null;
        c.hits = 0;
        if (old != null) {
            carrying.remove(old);
            Player p = Bukkit.getPlayer(old);
            if (p != null) {
                p.setGlowing(false);
                giveTeamHelmet(p);
            }
        }
        c.at = clampToZone(where != null ? where : new Location(arena.world(), crownZone.centerX(), crownZone.minY(), crownZone.centerZ()));
        c.freeFrom = ticks + gamePlugin.getConfig().getInt("crowns.drop-delay-ticks", 30);
        spawnDisplay(c);
    }

    private boolean enemies(UUID a, UUID b) { return !teamId(a).equals(teamId(b)); }

    /** An enemy takes the crown from its carrier (hits or knock-out). */
    private void steal(Crown c, Player victim, Player thief) {
        var cfg = gamePlugin.getConfig();
        Location at = victim.getLocation();
        drop(c, at);
        if (thief != null && !carrying.containsKey(thief.getUniqueId()) && isActive(thief.getUniqueId())) {
            giveTo(c, thief);
            c.freeFrom = ticks;
            if (ticks - c.lastStealTick >= 100) {       // no points for passing a crown back and forth
                award("steal:" + c.hashCode() + ":" + ticks + ":" + thief.getUniqueId(), thief.getUniqueId(),
                        scaled(teamId(thief.getUniqueId()), cfg.getInt("points.steal", 15)),
                        PointAward.Reason.BONUS, "steal");
                gainEnergy(teamId(thief.getUniqueId()), cfg.getDouble("energy.per-steal", 10));
            }
            c.lastStealTick = ticks;
            for (Player v : Bukkit.getOnlinePlayers()) {
                if (isParticipant(v.getUniqueId()))
                    v.sendMessage(api.tr(v.getUniqueId(), "crown.stolen", thief.getName(), victim.getName()));
            }
        } else {
            for (Player v : Bukkit.getOnlinePlayers()) {
                if (isParticipant(v.getUniqueId()))
                    v.sendMessage(api.tr(v.getUniqueId(), "crown.dropped", victim.getName()));
            }
        }
    }

    @Override
    protected boolean onPlayerHit(Player attacker, Player victim, EntityDamageByEntityEvent e) {
        Crown c = carrying.get(victim.getUniqueId());
        if (c == null || !enemies(attacker.getUniqueId(), victim.getUniqueId())) return false;
        if (isProtected(victim.getUniqueId()) || !isActive(attacker.getUniqueId())) return false;
        int need = Math.max(1, gamePlugin.getConfig().getInt("crowns.hits-to-steal", 3));
        c.hits++;
        if (c.hits >= need) {
            steal(c, victim, attacker);
        } else {
            actionBar(attacker, api.tr(attacker.getUniqueId(), "crown.hit", c.hits, need));
            actionBar(victim, api.tr(victim.getUniqueId(), "crown.hit-victim", c.hits, need));
        }
        return false;       // the hit itself still does its normal damage
    }

    @Override
    protected void onPlayerDown(Player victim, UUID killer) {
        Crown c = carrying.get(victim.getUniqueId());
        if (c != null) {
            Player k = killer != null ? Bukkit.getPlayer(killer) : null;
            if (k != null && enemies(killer, victim.getUniqueId())) steal(c, victim, k);
            else steal(c, victim, null);
        }
        if (bountyBy == null || ticks >= bountyUntil || killer == null) return;
        if (teamId(victim.getUniqueId()).equals(bountyTarget) && teamId(killer).equals(bountyBy)) {
            int bonus = gamePlugin.getConfig().getInt("abilities.bounty.bonus", 15);
            if (award("bounty:" + victim.getUniqueId() + ":" + ticks, killer, bonus, PointAward.Reason.BONUS, "bounty")) {
                contribution.merge(killer, 2, Integer::sum);
                Player kp = Bukkit.getPlayer(killer);
                if (kp != null) actionBar(kp, api.tr(killer, "crown.bounty-paid", bonus));
            }
        }
    }

    @Override
    protected void onPlayerLeft(UUID id) {
        Crown c = carrying.get(id);
        if (c == null) return;
        Player p = Bukkit.getPlayer(id);
        drop(c, p != null ? p.getLocation() : null);
    }

    @EventHandler(ignoreCancelled = true)
    public void onArmorClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p) || !getState().isRunning()) return;
        if (!isParticipant(p.getUniqueId())) return;
        if (e.getSlotType() == InventoryType.SlotType.ARMOR) e.setCancelled(true);
    }

    /** The team that wears the most crowns right now, or null. */
    private String holderTeam() {
        Map<String, Integer> n = new HashMap<>();
        for (Crown c : crowns) if (c.carrier != null) n.merge(teamId(c.carrier), 1, Integer::sum);
        String best = null;
        int top = 0;
        boolean tie = false;
        for (var en : n.entrySet()) {
            if (en.getValue() > top) { best = en.getKey(); top = en.getValue(); tie = false; }
            else if (en.getValue() == top) tie = true;
        }
        return tie ? null : best;
    }

    private int crownsOf(String team) {
        int n = 0;
        for (Crown c : crowns) if (c.carrier != null && teamId(c.carrier).equals(team)) n++;
        return n;
    }

    private int freeCrowns() {
        int n = 0;
        for (Crown c : crowns) if (c.carrier == null) n++;
        return n;
    }

    // ── Loop ──────────────────────────────────────────────────────────────────

    @Override
    protected void playTick() {
        var cfg = gamePlugin.getConfig();

        // pick-up of crowns that lie on the floor
        double r = cfg.getDouble("crowns.pickup-radius", 1.8);
        for (Crown c : crowns) {
            if (c.carrier != null || ticks < c.freeFrom) continue;
            if (c.display == null || !c.display.isValid()) spawnDisplay(c);
            for (UUID id : players) {
                if (!isActive(id) || carrying.containsKey(id)) continue;
                Location l = Bukkit.getPlayer(id).getLocation();
                if (l.getWorld() != c.at.getWorld()) continue;
                double dx = l.getX() - c.at.getX(), dz = l.getZ() - c.at.getZ();
                if (dx * dx + dz * dz > r * r || Math.abs(l.getY() - c.at.getY()) > 3) continue;
                giveTo(c, Bukkit.getPlayer(id));
                for (Player v : Bukkit.getOnlinePlayers()) {
                    if (isParticipant(v.getUniqueId()))
                        v.sendMessage(api.tr(v.getUniqueId(), "crown.picked", nameOf(id)));
                }
                break;
            }
        }

        // hold points + energy: every carrier is paid for himself
        long interval = cfg.getInt("game.score-interval-seconds", 10) * 20L;
        if (interval > 0 && ticks > 0 && ticks % interval == 0) {
            for (Crown c : crowns) {
                if (c.carrier == null || !isActive(c.carrier)) continue;
                String team = teamId(c.carrier);
                if (award("hold:" + ticks + ":" + c.carrier, c.carrier, scaled(team, cfg.getInt("points.hold", 10)),
                        PointAward.Reason.OBJECTIVE, "hold")) {
                    gainEnergy(team, cfg.getDouble("energy.per-hold", 10));
                    contribution.merge(c.carrier, 1, Integer::sum);
                }
            }
        }

        // lockdown: keep the other teams out of the closed areas
        if (lockdownBy != null && ticks < lockdownUntil && ticks % 10 == 0) enforceLockdown();
        else if (lockdownBy != null && ticks >= lockdownUntil) { lockdownBy = null; tellKey("crown.lockdown-over"); }
        if (bountyBy != null && ticks >= bountyUntil) { bountyBy = bountyTarget = null; }

        if (ticks % 10 == 0) drawCrowns();
        long left = Math.max(0, durationTicks - ticks) / 20;
        String holder = holderTeam() != null ? teamName(holderTeam()) : "§7-";
        bar("§6§lCROWN CONTROL §8| §e" + mmss(left) + " §8| §7kronen: §6" + (crowns.size() - freeCrowns()) + "§7/" + crowns.size()
                        + " §8| " + holder,
                1.0 - (double) ticks / Math.max(1, durationTicks), left <= 20 ? BarColor.RED : BarColor.YELLOW);
        if (ticks >= durationTicks) end();
    }

    /** Double Down multiplies the holder's objective rewards (never the activation itself). */
    private int scaled(String team, int base) {
        if (doubleUntil.getOrDefault(team, 0L) > ticks)
            return (int) Math.round(base * gamePlugin.getConfig().getDouble("abilities.double-down.multiplier", 2.0));
        return base;
    }

    private void gainEnergy(String team, double amount) {
        TeamEnergy e = energy.get(team);
        if (e != null) e.add(amount);
    }

    // ── Abilities ─────────────────────────────────────────────────────────────

    public static final String OK = "ok";

    /** Tries to activate an ability for the player's team; returns {@link #OK} or the lang key of the refusal. */
    public String activate(Player p, Ability a) {
        if (!live || !getState().isRunning()) return "crown.err.not-running";
        UUID id = p.getUniqueId();
        if (!isActive(id)) return "crown.err.not-active";
        String team = teamId(id);
        var cfg = gamePlugin.getConfig();
        String base = "abilities." + a.key + ".";
        if (crownsOf(team) == 0) return "crown.err.no-crown";
        if (!cooldowns.ready(team + ":" + a.key, ticks)) return "crown.err.cooldown";
        TeamEnergy e = energy.get(team);
        double cost = cfg.getDouble(base + "cost", 20);
        if (e == null || e.value() + 1e-9 < cost) return "crown.err.energy";

        // checks that must pass BEFORE anything is paid
        if (a == Ability.LOCKDOWN && arena.regions("lockdown").isEmpty()) return "crown.err.no-lockdown-zone";
        if (a == Ability.LOCKDOWN && lockdownBy != null && ticks < lockdownUntil) return "crown.err.already-active";
        if (a == Ability.DOUBLE_DOWN && doubleUntil.getOrDefault(team, 0L) > ticks) return "crown.err.already-active";
        String bountyTargetTeam = null;
        if (a == Ability.BOUNTY) {
            if (bountyBy != null && ticks < bountyUntil) return "crown.err.already-active";
            bountyTargetTeam = pickBountyTarget(team);
            if (bountyTargetTeam == null) return "crown.err.no-target";
        }

        if (!e.trySpend(cost)) return "crown.err.energy";          // atomic: nothing is taken on failure
        long dur = cfg.getInt(base + "seconds", 10) * 20L;
        cooldowns.start(team + ":" + a.key, ticks, cfg.getInt(base + "cooldown", 20) * 20L);

        switch (a) {
            case DOUBLE_DOWN -> doubleUntil.put(team, ticks + dur);
            case LOCKDOWN -> { lockdownBy = team; lockdownUntil = ticks + dur; }
            case OVERDRIVE -> {
                overdriveUntil.put(team, ticks + dur);
                int lvl = Math.max(1, cfg.getInt(base + "speed-level", 2)) - 1;
                effect(team, PotionEffectType.SPEED, dur, lvl);
            }
            case BOUNTY -> { bountyBy = team; bountyTarget = bountyTargetTeam; bountyUntil = ticks + dur; }
            case FORTIFY -> {
                fortifyUntil.put(team, ticks + dur);
                effect(team, PotionEffectType.RESISTANCE, dur, 0);
            }
        }
        for (Player v : Bukkit.getOnlinePlayers()) {
            if (!isParticipant(v.getUniqueId())) continue;
            v.sendMessage(api.tr(v.getUniqueId(), "crown.activated", teamName(team),
                    api.tr(v.getUniqueId(), "crown.ability." + a.key + ".name"),
                    a == Ability.BOUNTY ? teamName(bountyTargetTeam) : ""));
        }
        for (UUID m : membersOf(team)) {
            Player mp = Bukkit.getPlayer(m);
            if (mp != null) mp.playSound(mp.getLocation(), Sound.BLOCK_BEACON_POWER_SELECT, 1f, 1.2f);
        }
        return OK;
    }

    private String pickBountyTarget(String own) {
        String best = null;
        int bestPts = -1;
        for (String t : activeTeams()) {
            if (t.equals(own)) continue;
            boolean anyone = false;
            for (UUID m : membersOf(t)) if (isActive(m)) { anyone = true; break; }
            if (!anyone) continue;
            int pts = book.team(t);
            if (pts > bestPts) { bestPts = pts; best = t; }
        }
        return best;
    }

    private void effect(String team, PotionEffectType type, long ticks, int amplifier) {
        for (UUID m : membersOf(team)) {
            Player p = Bukkit.getPlayer(m);
            if (p != null && isActive(m)) p.addPotionEffect(new PotionEffect(type, (int) ticks, amplifier, false, true, true));
        }
    }

    private void enforceLockdown() {
        var zones = arena.regions("lockdown").values();
        for (UUID id : players) {
            if (!isActive(id) || teamId(id).equals(lockdownBy)) continue;
            Player p = Bukkit.getPlayer(id);
            Location l = p.getLocation();
            for (Region r : zones) {
                if (r.contains(l.getX(), l.getY(), l.getZ())) {
                    p.teleport(respawnLocation(p));
                    actionBar(p, api.tr(id, "crown.locked-out"));
                    p.playSound(l, Sound.BLOCK_ANVIL_LAND, 0.6f, 1.2f);
                    break;
                }
            }
        }
    }

    // ── Menu ──────────────────────────────────────────────────────────────────

    @EventHandler
    public void onMenu(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || !live || !getState().isRunning()) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Player p = e.getPlayer();
        if (!isParticipant(p.getUniqueId())) return;
        ItemStack it = e.getItem();
        if (it == null || it.getType() != Material.NETHER_STAR) return;
        e.setCancelled(true);
        openMenu(p);
    }

    public void openMenu(Player p) {
        new AbilityGui(this, p).open(p);
    }

    /** State shown in the menu for one ability: {@code OK} when it can be used right now. */
    String abilityState(Player p, Ability a) {
        String team = teamId(p.getUniqueId());
        var cfg = gamePlugin.getConfig();
        if (crownsOf(team) == 0) return "crown.err.no-crown";
        if (!cooldowns.ready(team + ":" + a.key, ticks)) return "crown.err.cooldown";
        TeamEnergy e = energy.get(team);
        if (e == null || e.value() + 1e-9 < cfg.getDouble("abilities." + a.key + ".cost", 20)) return "crown.err.energy";
        if (a == Ability.LOCKDOWN && arena.regions("lockdown").isEmpty()) return "crown.err.no-lockdown-zone";
        return OK;
    }

    double cost(Ability a) { return gamePlugin.getConfig().getDouble("abilities." + a.key + ".cost", 20); }
    long cooldownSeconds(Player p, Ability a) {
        return cooldowns.remaining(teamId(p.getUniqueId()) + ":" + a.key, ticks) / 20;
    }
    double energyOf(Player p) {
        TeamEnergy e = energy.get(teamId(p.getUniqueId()));
        return e != null ? e.value() : 0;
    }
    double capacity() { return gamePlugin.getConfig().getDouble("energy.capacity", 100); }

    // ── Visuals / HUD ─────────────────────────────────────────────────────────

    private void drawCrowns() {
        World w = arena.world();
        if (w == null) return;
        for (Crown c : crowns) {
            if (c.carrier == null) {
                Particle.DustOptions dust = new Particle.DustOptions(Color.YELLOW, 1.6f);
                for (int i = 0; i < 12; i++) {
                    double a = 2 * Math.PI * i / 12;
                    w.spawnParticle(Particle.DUST, c.at.getX() + Math.cos(a) * 1.2, c.at.getY() + 0.3,
                            c.at.getZ() + Math.sin(a) * 1.2, 1, 0, 0, 0, 0, dust);
                }
                w.spawnParticle(Particle.END_ROD, c.at.getX(), c.at.getY() + 2.2, c.at.getZ(), 2, 0.1, 0.4, 0.1, 0.01);
            } else {
                Player p = Bukkit.getPlayer(c.carrier);
                if (p == null) continue;
                Color col = colorOf(teamColor(teamId(c.carrier)));
                w.spawnParticle(Particle.DUST, p.getLocation().add(0, 2.5, 0), 4, 0.25, 0.1, 0.25, 0,
                        new Particle.DustOptions(col, 1.4f));
            }
        }
    }

    @Override
    protected List<String> sidebar(Player viewer) {
        UUID id = viewer.getUniqueId();
        String team = teamId(id);
        List<String> l = new ArrayList<>();
        l.add(api.tr(id, "crown.sb.time", mmss(Math.max(0, durationTicks - ticks) / 20)));
        l.add(api.tr(id, "crown.sb.crowns", crownsOf(team), crowns.size() - freeCrowns(), crowns.size()));
        Crown mine = carrying.get(id);
        if (mine != null) {
            l.add(api.tr(id, "crown.sb.wearing", mine.hits, Math.max(1, gamePlugin.getConfig().getInt("crowns.hits-to-steal", 3))));
        }
        l.add("");
        l.add(api.tr(id, "crown.sb.team", shownTeamPoints(team)));
        l.add(api.tr(id, "crown.sb.energy", (int) energyOf(viewer), (int) capacity()));
        if (doubleUntil.getOrDefault(team, 0L) > ticks) l.add(api.tr(id, "crown.sb.double", (doubleUntil.get(team) - ticks) / 20));
        if (bountyBy != null && ticks < bountyUntil) l.add(api.tr(id, "crown.sb.bounty", teamName(bountyTarget)));
        l.add(api.tr(id, "crown.sb.you", contribution.getOrDefault(id, 0)));
        return l;
    }

    // ── Result ────────────────────────────────────────────────────────────────

    @Override
    protected Result finishGame() {
        String holder = holderTeam();
        if (holder != null) {
            List<UUID> wearers = new ArrayList<>();
            for (Crown c : crowns) if (c.carrier != null && teamId(c.carrier).equals(holder)) wearers.add(c.carrier);
            awardShared("end:" + holder, holder, wearers, gamePlugin.getConfig().getInt("points.end-bonus", 50),
                    PointAward.Reason.BONUS, "end-bonus");
        }
        List<String> ranked = book.rankTeams(activeTeams());
        awardTeamPlacements(ranked);
        List<UUID> order = new ArrayList<>();
        for (String t : ranked) {
            List<UUID> m = membersOf(t);
            m.sort((a, b) -> Integer.compare(contribution.getOrDefault(b, 0), contribution.getOrDefault(a, 0)));
            order.addAll(m);
        }
        for (Player v : Bukkit.getOnlinePlayers()) {
            UUID vid = v.getUniqueId();
            v.sendMessage(api.tr(vid, "crown.results"));
            int i = 1;
            for (String t : ranked)
                v.sendMessage("  §7#" + (i++) + " " + teamName(t) + " §8— §e" + shownTeamPoints(t) + " ptn"
                        + (t.equals(holder) ? " §6♛" : ""));
        }
        UUID mvp = null;
        int best = -1;
        for (UUID id : players) {
            int c = contribution.getOrDefault(id, 0);
            if (c > best) { best = c; mvp = id; }
        }
        String winner = ranked.isEmpty() ? "-" : teamName(ranked.get(0));
        return new Result(winner, mvp, mvp != null ? nameOf(mvp) : null, order);
    }

    @Override
    protected void cleanupGame() {
        removeCrownDisplays();
        for (UUID id : new ArrayList<>(carrying.keySet())) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) p.setGlowing(false);
        }
        carrying.clear();
        crowns.clear();
        for (UUID id : players) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;
            p.removePotionEffect(PotionEffectType.SPEED);
            p.removePotionEffect(PotionEffectType.RESISTANCE);
        }
        energy.clear();
        lockdownBy = null;
        bountyBy = bountyTarget = null;
    }
}
