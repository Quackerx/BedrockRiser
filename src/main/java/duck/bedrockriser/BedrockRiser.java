package duck.bedrockriser;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.plugin.java.JavaPlugin;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.*;

public class BedrockRiser extends JavaPlugin implements Listener, TabExecutor {

    private static final int NONE = Integer.MIN_VALUE;
    private long intervalTicks;
    private int maxHeight;
    private int perRise;
    private int scatter;
    private int chunksPerTick;
    private String startRaw;
    private boolean pauseEmpty;
    private boolean alarmOn;
    private String alarmMsg;
    private final Set<String> worlds = new HashSet<>();
    private final Map<String, WorldState> states = new HashMap<>();
    private final Map<String, ChunkData> cache = new HashMap<>();
    private final LinkedHashSet<ChunkRef> pending = new LinkedHashSet<>();
    private final ArrayDeque<ChunkRef> forced = new ArrayDeque<>();
    private YamlConfiguration data;
    private File dataFile;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();

        dataFile = new File(getDataFolder(), "data.yml");
        data = YamlConfiguration.loadConfiguration(dataFile);

        Bukkit.getPluginManager().registerEvents(this, this);
        getCommand("bedrockriser").setExecutor(this);
        getCommand("bedrockriser").setTabCompleter(this);

        Bukkit.getScheduler().runTaskTimer(this, this::tick, 1L, 1L);

        Bukkit.getScheduler().runTaskTimer(this, this::flush, 200L, 200L);

        for (String name : worlds) {
            World w = Bukkit.getWorld(name);
            if (w != null) {
                queueLoaded(w);
            }
        }
    }

    @Override
    public void onDisable() {
        flush();
    }

    private void loadSettings() {
        reloadConfig();
        intervalTicks = Math.max(1L, getConfig().getLong("rise-interval-ticks", 24000L));
        maxHeight = getConfig().getInt("max-height", 20);
        startRaw = String.valueOf(getConfig().get("start-height", "auto"));
        perRise = Math.max(1, getConfig().getInt("blocks-per-rise", 1));
        scatter = Math.max(1, getConfig().getInt("scatter-layers", 5));
        chunksPerTick = Math.max(1, getConfig().getInt("chunks-per-tick", 2));
        pauseEmpty = getConfig().getBoolean("pause-when-empty", false);
        alarmOn = getConfig().getBoolean("alarm.enabled", false);
        alarmMsg = getConfig().getString("alarm.message", "&4&lWARNING! &cThe bedrock has risen to &eY={height}&c!");
        worlds.clear();
        worlds.addAll(getConfig().getStringList("worlds"));
    }

    private static class WorldState {
        int risen;
        long countdown;
    }

    private String skey(String world) {
        return world.replace('.', '_');
    }

    private WorldState state(String name) {
        WorldState s = states.get(name);
        if (s == null) {
            s = new WorldState();
            s.risen = data.getInt("worlds." + skey(name) + ".risen", 0);
            s.countdown = data.getLong("worlds." + skey(name) + ".countdown", intervalTicks);
            states.put(name, s);
        }
        return s;
    }

    private void saveState() {
        for (Map.Entry<String, WorldState> e : states.entrySet()) {
            data.set("worlds." + skey(e.getKey()) + ".risen", e.getValue().risen);
            data.set("worlds." + skey(e.getKey()) + ".countdown", e.getValue().countdown);
        }
        try {
            data.save(dataFile);
        } catch (IOException ex) {
            getLogger().warning("Could not save data.yml: " + ex.getMessage());
        }
    }
    private int minY(World w) {
        try {
            return (Integer) World.class.getMethod("getMinHeight").invoke(w);
        } catch (Throwable t) {
            return 0;
        }
    }

    private int startY(World w) {
        try {
            if (!startRaw.equalsIgnoreCase("auto")) {
                return Integer.parseInt(startRaw.trim());
            }
        } catch (NumberFormatException ignored) {

        }
        return minY(w) + 4;
    }

    private int capY(World w) {
        return Math.min(maxHeight, w.getMaxHeight() - 1);
    }

    private int maxRisen(World w) {
        return Math.max(0, capY(w) - startY(w));
    }

    private int topY(World w, int risen) {
        if (risen <= 0) {
            return NONE;
        }
        return Math.min(startY(w) + risen, capY(w));
    }

    private boolean shouldBe(long seed, int x, int y, int z, int top) {
        if (y > top) {
            return false;
        }
        int d = top - y;
        if (d >= scatter - 1) {
            return true;
        }
        long h = seed ^ (x * 0x9E3779B97F4A7C15L) ^ (y * 0xC2B2AE3D27D4EB4FL) ^ (z * 0x165667B19E3779F9L);
        h ^= (h >>> 33);
        h *= 0xff51afd7ed558ccdL;
        h ^= (h >>> 33);
        h *= 0xc4ceb9fe1a85ec53L;
        h ^= (h >>> 33);
        double r = (h >>> 11) * (1.0 / (1L << 53));
        return r < (d + 1.0) / scatter;
    }

    private void tick() {
        for (String name : worlds) {
            World w = Bukkit.getWorld(name);
            if (w == null) {
                continue;
            }
            WorldState s = state(name);
            if (s.risen >= maxRisen(w)) {
                continue;
            }
            if (pauseEmpty && w.getPlayers().isEmpty()) {
                continue;
            }
            s.countdown--;
            if (s.countdown <= 0) {
                rise(w, perRise);
            }
        }
        processQueues();
    }

    private void rise(World w, int blocks) {
        WorldState s = state(w.getName());
        int oldTop = topY(w, s.risen);
        int oldDisplay = oldTop == NONE ? startY(w) : oldTop;
        s.risen = Math.clamp(maxRisen(w), 0, s.risen + blocks);
        s.countdown = intervalTicks;
        int newTop = topY(w, s.risen);
        saveState();
        queueLoaded(w);

        if (alarmOn && blocks > 0 && newTop != NONE) {
            String msg = alarmMsg.replace("{world}", w.getName())
                    .replace("{height}", String.valueOf(newTop))
                    .replace("{old_height}", String.valueOf(oldDisplay))
                    .replace("{blocks}", String.valueOf(blocks));

            msg = ChatColor.translateAlternateColorCodes('&', msg);
            for (Player p : w.getPlayers()) {
                p.sendMessage(msg);
            }
        }
    }

    private void reset(World w) {
        WorldState s = state(w.getName());
        s.risen = 0;
        s.countdown = intervalTicks;
        saveState();
        queueLoaded(w);

        File dir = new File(getDataFolder(), "chunks" + File.separator + w.getName());
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                String n = f.getName();
                if (!n.endsWith(".bin")) {
                    continue;
                }
                String[] parts = n.substring(0, n.length() - 4).split("_");
                if (parts.length != 2) {
                    continue;
                }
                try {
                    int cx = Integer.parseInt(parts[0]);
                    int cz = Integer.parseInt(parts[1]);
                    if (!w.isChunkLoaded(cx, cz)) {
                        forced.add(new ChunkRef(w.getName(), cx, cz));
                    }
                } catch (NumberFormatException ignored) {

                }
            }
        }
    }

    private void queueLoaded(World w) {
        for (Chunk c : w.getLoadedChunks()) {
            pending.add(new ChunkRef(w.getName(), c.getX(), c.getZ()));
        }
    }

    private void processQueues() {
        int budget = chunksPerTick;
        Iterator<ChunkRef> it = pending.iterator();
        while (budget > 0 && it.hasNext()) {
            ChunkRef r = it.next();
            it.remove();
            World w = Bukkit.getWorld(r.world);
            if (w != null && w.isChunkLoaded(r.x, r.z)) {
                sync(w.getChunkAt(r.x, r.z));
                budget--;
            }
        }
        while (budget > 0 && !forced.isEmpty()) {
            ChunkRef r = forced.poll();
            World w = Bukkit.getWorld(r.world);
            if (w == null) {
                continue;
            }
            sync(w.getChunkAt(r.x, r.z));
            budget--;
        }
    }

    private boolean isProtected(Material m) {
        String n = m.name();
        return n.equals("BEDROCK") || n.contains("CHEST") || n.contains("BARREL") || n.contains("SHULKER")
                || n.contains("FURNACE") || n.contains("SMOKER") || n.contains("HOPPER")
                || n.contains("DISPENSER") || n.contains("DROPPER") || n.contains("SPAWNER")
                || n.contains("BREWING") || n.contains("LECTERN") || n.contains("JUKEBOX")
                || n.contains("BEACON") || n.contains("COMMAND") || n.contains("STRUCTURE_BLOCK")
                || n.contains("END_PORTAL") || n.contains("NETHER_PORTAL") || n.contains("CONDUIT")
                || n.contains("SIGN") || n.contains("BANNER") || n.contains("HEAD") || n.contains("SKULL")
                || n.endsWith("_BED") || n.equals("BED") || n.contains("ENCHANTING") || n.contains("RESPAWN_ANCHOR");
    }

    private int pack(int lx, int y, int lz) {
        return ((y + 4096) << 8) | (lx << 4) | lz;
    }

    private void sync(Chunk c) {
        World w = c.getWorld();
        if (!worlds.contains(w.getName())) {
            return;
        }
        int top = topY(w, state(w.getName()).risen);
        ChunkData d = data(w.getName(), c.getX(), c.getZ(), top != NONE);
        if (d == null) {
            return;
        }
        long seed = w.getSeed();
        int minY = minY(w);
        int baseX = c.getX() << 4;
        int baseZ = c.getZ() << 4;

        if (!d.orig.isEmpty()) {
            IntShortMap kept = new IntShortMap();
            boolean removedAny = false;
            for (int k : d.orig.keyArray()) {
                int lz = k & 15;
                int lx = (k >> 4) & 15;
                int y = (k >> 8) - 4096;
                int v = d.orig.get(k);
                if (top != NONE && shouldBe(seed, baseX + lx, y, baseZ + lz, top)) {
                    kept.put(k, v);
                    continue;
                }
                Block b = c.getBlock(lx, y, lz);
                if (b.getType() == Material.BEDROCK) {
                    restore(b, gpal.get(v));
                }
                removedAny = true;
            }
            if (removedAny) {
                d.orig = kept;
                d.dirty = true;
            }
        }

        if (top != NONE) {
            int from = d.appliedTop == NONE ? minY : Math.max(minY, d.appliedTop - scatter);
            for (int y = from; y <= top; y++) {
                for (int lx = 0; lx < 16; lx++) {
                    for (int lz = 0; lz < 16; lz++) {
                        if (!shouldBe(seed, baseX + lx, y, baseZ + lz, top)) {
                            continue;
                        }
                        int key = pack(lx, y, lz);
                        if (d.orig.containsKey(key)) {
                            continue;
                        }
                        Block b = c.getBlock(lx, y, lz);
                        Material m = b.getType();
                        if (isProtected(m)) {
                            continue;
                        }
                        d.orig.put(key, idOf(b.getBlockData().getAsString()));
                        b.setType(Material.BEDROCK, false);
                    }
                }
            }
            if (d.appliedTop != top) {
                d.appliedTop = top;
            }
            d.dirty = true;
        } else if (d.appliedTop != NONE) {
            d.appliedTop = NONE;
            d.dirty = true;
        }
    }

    private void restore(Block b, String blockData) {
        try {
            b.setBlockData(Bukkit.createBlockData(blockData), false);
        } catch (Exception ex) {
            getLogger().warning("Could not restore block data '" + blockData + "', using stone instead as a fallback");
            b.setType(Material.STONE, false);
        }
    }

    private static class ChunkData {
        int appliedTop = NONE;
        IntShortMap orig = new IntShortMap();
        boolean dirty;
    }

    private static class IntShortMap {
        private static final int EMPTY = -1;
        private int[] keys = new int[16];
        private short[] vals = new short[16];
        private int size;

        IntShortMap() {
            Arrays.fill(keys, EMPTY);
        }

        private int slot(int k) {
            int h = k * 0x9E3779B1;
            h ^= (h >>> 16);
            return h & (keys.length - 1);
        }

        int get(int k) {
            int mask = keys.length - 1;
            int i = slot(k);
            while (keys[i] != EMPTY) {
                if (keys[i] == k) {
                    return vals[i];
                }
                i = (i + 1) & mask;
            }
            return -1;
        }

        boolean containsKey(int k) {
            return get(k) != -1;
        }

        void put(int k, int v) {
            if ((size + 1) * 2 > keys.length) {
                grow();
            }
            int mask = keys.length - 1;
            int i = slot(k);
            while (keys[i] != EMPTY && keys[i] != k) {
                i = (i + 1) & mask;
            }
            if (keys[i] == EMPTY) {
                keys[i] = k;
                size++;
            }
            vals[i] = (short) v;
        }

        private void grow() {
            int[] ok = keys;
            short[] ov = vals;
            keys = new int[ok.length * 2];
            vals = new short[ok.length * 2];
            Arrays.fill(keys, EMPTY);
            size = 0;
            for (int i = 0; i < ok.length; i++) {
                if (ok[i] != EMPTY) {
                    put(ok[i], ov[i]);
                }
            }
        }

        int size() {
            return size;
        }

        boolean isEmpty() {
            return size == 0;
        }

        int[] keyArray() {
            int[] r = new int[size];
            int n = 0;
            for (int k : keys) {
                if (k != EMPTY) {
                    r[n++] = k;
                }
            }
            return r;
        }
    }

    private final List<String> gpal = new ArrayList<String>();
    private final Map<String, Integer> gidx = new HashMap<String, Integer>();

    private int idOf(String s) {
        Integer i = gidx.get(s);
        if (i == null) {
            i = gpal.size();
            gpal.add(s);
            gidx.put(s, i);
        }
        return i;
    }

    private static class ChunkRef {
        final String world;
        final int x;
        final int z;

        ChunkRef(String world, int x, int z) {
            this.world = world;
            this.x = x;
            this.z = z;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof ChunkRef)) {
                return false;
            }
            ChunkRef r = (ChunkRef) o;
            return x == r.x && z == r.z && world.equals(r.world);
        }

        @Override
        public int hashCode() {
            return (world.hashCode() * 31 + x) * 31 + z;
        }
    }

    private File chunkFile(String world, int cx, int cz) {
        return new File(getDataFolder(), "chunks" + File.separator + world + File.separator + cx + "_" + cz + ".bin");
    }

    private String ckey(String world, int cx, int cz) {
        return world + ":" + cx + ":" + cz;
    }

    private ChunkData data(String world, int cx, int cz, boolean create) {
        String key = ckey(world, cx, cz);
        ChunkData d = cache.get(key);
        if (d == null) {
            d = loadFile(chunkFile(world, cx, cz));
            if (d == null && create) {
                d = new ChunkData();
            }
            if (d != null) {
                cache.put(key, d);
            }
        }
        return d;
    }

    private ChunkData loadFile(File f) {
        if (!f.exists()) {
            return null;
        }
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(f)))) {
            in.readInt();
            ChunkData d = new ChunkData();
            d.appliedTop = in.readInt();
            int pc = in.readInt();
            String[] pal = new String[pc];
            for (int i = 0; i < pc; i++) {
                pal[i] = in.readUTF();
            }
            int n = in.readInt();
            for (int i = 0; i < n; i++) {
                int k = in.readInt();
                int p = in.readInt();
                d.orig.put(k, idOf(pal[p]));
            }
            return d;
        } catch (IOException ex) {
            getLogger().warning("Could not read " + f.getName() + ": " + ex.getMessage());
            return null;
        }
    }

    private void saveFile(String world, int cx, int cz, ChunkData d) {
        File f = chunkFile(world, cx, cz);
        d.dirty = false;
        if (d.orig.isEmpty() && d.appliedTop == NONE) {
            if (f.exists()) {
                f.delete();
            }
            return;
        }
        f.getParentFile().mkdirs();
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(f)))) {
            int[] keys = d.orig.keyArray();
            List<String> pal = new ArrayList<>();
            Map<Integer, Integer> local = new HashMap<>();
            for (int k : keys) {
                int g = d.orig.get(k);
                if (!local.containsKey(g)) {
                    local.put(g, pal.size());
                    pal.add(gpal.get(g));
                }
            }
            out.writeInt(1);
            out.writeInt(d.appliedTop);
            out.writeInt(pal.size());
            for (String s : pal) {
                out.writeUTF(s);
            }
            out.writeInt(keys.length);
            for (int k : keys) {
                out.writeInt(k);
                out.writeInt(local.get(d.orig.get(k)));
            }
        } catch (IOException ex) {
            getLogger().warning("Could not save " + f.getName() + ": " + ex.getMessage());
            d.dirty = true;
        }
    }

    private void flush() {
        for (Map.Entry<String, ChunkData> e : cache.entrySet()) {
            if (e.getValue().dirty) {
                String[] p = e.getKey().split(":");
                saveFile(p[0], Integer.parseInt(p[1]), Integer.parseInt(p[2]), e.getValue());
            }
        }
        if (data != null) {
            saveState();
        }
    }

    @EventHandler
    public void onLoad(ChunkLoadEvent e) {
        String w = e.getWorld().getName();
        if (worlds.contains(w)) {
            pending.add(new ChunkRef(w, e.getChunk().getX(), e.getChunk().getZ()));
        }
    }

    @EventHandler
    public void onUnload(ChunkUnloadEvent e) {
        String w = e.getWorld().getName();
        int cx = e.getChunk().getX();
        int cz = e.getChunk().getZ();
        pending.remove(new ChunkRef(w, cx, cz));
        ChunkData d = cache.remove(ckey(w, cx, cz));
        if (d != null && d.dirty) {
            saveFile(w, cx, cz, d);
        }
    }

    private String time(long ticks) {
        long s = Math.max(0, ticks / 20);
        return (s / 60) + "m " + (s % 60) + "s";
    }

    private World pickWorld(CommandSender sender, String arg) {
        if (arg != null) {
            return Bukkit.getWorld(arg);
        }
        if (sender instanceof Player) {
            World w = ((Player) sender).getWorld();
            if (worlds.contains(w.getName())) {
                return w;
            }
        }
        for (String n : worlds) {
            World w = Bukkit.getWorld(n);
            if (w != null) {
                return w;
            }
        }
        return null;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] a) {
        if (!sender.hasPermission("bedrockriser.admin")) {
            sender.sendMessage(ChatColor.RED + "No perms.");
            return true;
        }
        String sub = a.length == 0 ? "status" : a[0].toLowerCase(Locale.ROOT);

        switch (sub) {
            case "reload" -> {
                loadSettings();
                sender.sendMessage(ChatColor.GREEN + "BedrockRiser config has been reloaded.");
                return true;
            }
            case "status" -> {
                sender.sendMessage(ChatColor.GOLD + "BedrockRiser:");
                for (String n : worlds) {
                    World w = Bukkit.getWorld(n);
                    if (w == null) {
                        sender.sendMessage(ChatColor.GRAY + n + ": world not loaded");
                        continue;
                    }
                    WorldState s = state(n);
                    int top = topY(w, s.risen);
                    String h = top == NONE ? "not started (start Y=" + startY(w) + ")" : "Y=" + top;
                    String next = s.risen >= maxRisen(w) ? "max height reached" : "next rise in " + time(s.countdown);
                    sender.sendMessage(ChatColor.YELLOW + n + ChatColor.GRAY + ": " + ChatColor.WHITE + h + ChatColor.GRAY + " (max Y=" + capY(w) + ", " + next + ")");
                }
                return true;
            }
            case "rise" -> {
                int amount = perRise;
                String worldArg = null;
                if (a.length >= 2) {
                    try {
                        amount = Integer.parseInt(a[1]);
                    } catch (NumberFormatException ex) {
                        sender.sendMessage(ChatColor.RED + "Usage: /" + label + " rise [blocks] [world]");
                        return true;
                    }
                }
                if (a.length >= 3) {
                    worldArg = a[2];
                }
                World w = pickWorld(sender, worldArg);
                if (w == null || !worlds.contains(w.getName())) {
                    sender.sendMessage(ChatColor.RED + "That world isn't enabled in the config.");
                    return true;
                }
                rise(w, amount);
                int top = topY(w, state(w.getName()).risen);
                sender.sendMessage(ChatColor.GREEN + "Bedrock in " + w.getName() + " is now at " + (top == NONE ? "start level (nothing placed)" : "Y=" + top) + ". Chunks are updating over the next few moments.");
                return true;
            }
            case "reset" -> {
                if (a.length >= 2 && a[1].equalsIgnoreCase("all")) {
                    for (String n : worlds) {
                        World w = Bukkit.getWorld(n);
                        if (w != null) {
                            reset(w);
                        }
                    }
                    sender.sendMessage(ChatColor.GREEN + "Resetting all enabled worlds. Blocks are being restored.");
                    return true;
                }
                World w = pickWorld(sender, a.length >= 2 ? a[1] : null);
                if (w == null || !worlds.contains(w.getName())) {
                    sender.sendMessage(ChatColor.RED + "That world isn't enabled in the config.");
                    return true;
                }
                reset(w);
                sender.sendMessage(ChatColor.GREEN + "Resetting " + w.getName() + ". Original blocks are being restored.");
                return true;
            }
        }

        sender.sendMessage(ChatColor.GOLD + "/" + label + " status");
        sender.sendMessage(ChatColor.GOLD + "/" + label + " rise [blocks] [world]" + ChatColor.GRAY + " (negative lowers it)");
        sender.sendMessage(ChatColor.GOLD + "/" + label + " reset [world|all]");
        sender.sendMessage(ChatColor.GOLD + "/" + label + " reload");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] a) {
        List<String> out = new ArrayList<>();
        if (!sender.hasPermission("bedrockriser.admin")) {
            return out;
        }
        if (a.length == 1) {
            for (String s : new String[]{"status", "rise", "reset", "reload"}) {
                if (s.startsWith(a[0].toLowerCase(Locale.ROOT))) {
                    out.add(s);
                }
            }
        } else if ((a.length == 3 && a[0].equalsIgnoreCase("rise")) || (a.length == 2 && a[0].equalsIgnoreCase("reset"))) {
            String prefix = a[a.length - 1].toLowerCase(Locale.ROOT);
            for (String n : worlds) {
                if (n.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                    out.add(n);
                }
            }
            if (a.length == 2 && "all".startsWith(prefix)) {
                out.add("all");
            }
        }
        return out;
    }
}
