package jp.kcraft.piano;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import java.io.File;
import java.net.URI;
import java.net.http.*;
import java.nio.*;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicBoolean;

/** Folia runtime for JW Piano's models, key layout and 88-note sample mapping.
 * The upstream FluentAPI is no longer published. No legacy scheduler is used here. */
public final class PianoPlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    private final Map<String, Piano> pianos = new ConcurrentHashMap<>();
    private final Map<UUID, Player> players = new ConcurrentHashMap<>();
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, Long> clicks = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private NamespacedKey tag;
    private volatile boolean running;
    private volatile String pendingSave;
    private final Object saveLock = new Object();
    private String web, secret, pack;
    private double range;
    private boolean mute;
    private RealtimeAudio realtime;

    @Override public void onEnable() {
        saveDefaultConfig(); running = true; tag = new NamespacedKey(this, "model");
        web = getConfig().getString("web-url", "").replaceAll("/+$", "");
        secret = getConfig().getString("bridge-secret", ""); pack = getConfig().getString("resource-pack-url", "");
        range = Math.max(1, Math.min(128, getConfig().getDouble("hearing-distance", 32)));
        mute = getConfig().getBoolean("mute-performer-in-game", true);
        if (Bukkit.getPluginManager().isPluginEnabled("packetevents")) { realtime = new RealtimeAudio(range,mute); getLogger().info("PacketEvents: tick待ちなしのサウンド送信を有効化"); }
        getServer().getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("piano")).setExecutor(this);
        getCommand("piano").setTabCompleter(this);
        for (Player p : Bukkit.getOnlinePlayers()) { players.put(p.getUniqueId(), p); onPlayer(p,()-> {if(realtime!=null)realtime.publish(p,p.getLocation());}); }
        var saved = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "pianos.yml"));
        for (String name : saved.getKeys(false)) {
            try {
                var section = saved.getConfigurationSection(name);
                World world = Bukkit.getWorld(section.getString("world", "")); if (world == null) continue;
                var loc = new Location(world, section.getDouble("x"), section.getDouble("y"), section.getDouble("z"));
                var piano = new Piano(name, UUID.fromString(section.getString("owner")), loc);
                piano.slot = section.getInt("slot",piano.slot);
                if(realtime!=null)piano.voice=new RealtimeAudio.Voice(piano.origin,piano.slot);
                piano.skin = section.getInt("skin", 167085); piano.volume = section.getInt("volume", 70);
                pianos.put(name, piano); at(piano, piano::spawn);
            } catch (Exception e) { getLogger().warning("ピアノの読込失敗: " + name + ": " + e.getMessage()); }
        }
        getLogger().info("日本語Foliaランタイム起動。ピアノ数=" + pianos.size());
    }
    @Override public void onDisable() {
        running = false;
        for (Session s : sessions.values()) s.close(); sessions.clear();
        // Entities are non-persistent, so no foreign-region world access during disable.
        save(); synchronized (saveLock) { flushSave(); }
        http.close();
    }
    private void at(Piano piano, Runnable action) {
        if (!running || piano.removed) return;
        if (Bukkit.isOwnedByCurrentRegion(piano.origin)) action.run();
        else Bukkit.getRegionScheduler().execute(this, piano.origin, () -> { if (running && !piano.removed) action.run(); });
    }
    private void onPlayer(Player player, Runnable action) {
        if (!running) return;
        if (Bukkit.isOwnedByCurrentRegion(player)) action.run();
        else player.getScheduler().execute(this, action, null, 1);
    }
    @EventHandler public void join(PlayerJoinEvent e) { players.put(e.getPlayer().getUniqueId(), e.getPlayer());if(realtime!=null)realtime.publish(e.getPlayer(),e.getPlayer().getLocation()); }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void move(PlayerMoveEvent e) { if(realtime!=null && e.getTo()!=null && e.hasChangedPosition())realtime.publish(e.getPlayer(),e.getTo()); }
    @EventHandler(priority=EventPriority.MONITOR) public void changedWorld(PlayerChangedWorldEvent e) { if(realtime!=null)realtime.publish(e.getPlayer(),e.getPlayer().getLocation()); }
    @EventHandler public void quit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId(); players.remove(id); clicks.remove(id);
        if(realtime!=null)realtime.remove(id);
        Session s = sessions.remove(id); if (s != null) s.close();
    }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) { sender.sendMessage("ゲーム内から実行してください。"); return true; }
        if (!player.hasPermission("kcraft.piano.play")) { player.sendMessage("演奏する権限がありません。"); return true; }
        if (args.length == 0) { menu(player); return true; }
        if (args[0].equalsIgnoreCase("pack")) {
            if (pack.isBlank()) player.sendMessage("リソースパックが未設定です。");
            else player.setResourcePack(pack, getConfig().getString("resource-pack-sha1", ""));
            return true;
        }
        if (args[0].equalsIgnoreCase("list")) { player.sendMessage("ピアノ: " + String.join("、", new TreeSet<>(pianos.keySet()))); return true; }
        if (args.length < 2) { player.sendMessage("/piano で一覧を開けます。"); return true; }
        String name = args[1];
        if (args[0].equalsIgnoreCase("create")) {
            if (!player.hasPermission("kcraft.piano.create")) { player.sendMessage("設置は運営に依頼してください。"); return true; }
            if (!name.matches("[A-Za-z0-9_-]{1,24}")) { player.sendMessage("名前は半角英数字・_・-の1〜24文字です。"); return true; }
            if (pianos.size() >= Math.min(10,getConfig().getInt("max-pianos", 10))) { player.sendMessage("設置数の上限です。"); return true; }
            var loc = player.getLocation().clone();
            // Keep the entire model within one chunk, including at a Folia region boundary.
            loc.setX((loc.getBlockX() >> 4) * 16 + 8); loc.setZ((loc.getBlockZ() >> 4) * 16 + 8); loc.setYaw(0); loc.setPitch(0);
            if (pianos.values().stream().anyMatch(p -> p.origin.getWorld().equals(loc.getWorld()) && p.origin.distanceSquared(loc) < 16)) {
                player.sendMessage("この場所にはすでにピアノがあります。別のチャンクに設置してください。"); return true;
            }
            var piano = new Piano(name, player.getUniqueId(), loc);
            if (pianos.putIfAbsent(name, piano) != null) { player.sendMessage("その名前は使用中です。"); return true; }
            at(piano, piano::spawn); save(); player.sendMessage("ピアノ「" + name + "」をこのチャンクの中央に設置しました。/piano pack でモデルを表示できます。"); return true;
        }
        Piano piano = pianos.get(name);
        if (piano == null) { player.sendMessage("そのピアノはありません。/piano list"); return true; }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "connect" -> {
                if (!piano.origin.getWorld().equals(player.getWorld()) || player.getLocation().distanceSquared(piano.origin) > 1024) { player.sendMessage("ピアノの32ブロック以内で接続してください。"); break; }
                if (secret.isBlank()) { player.sendMessage("Web接続の設定がまだ完了していません。"); break; }
                var activeSession=piano.active.get();
                if (activeSession!=null && !activeSession.player.equals(player.getUniqueId())) { player.sendMessage("このピアノは演奏中です。"); break; }
                Session previous = sessions.remove(player.getUniqueId()); if (previous != null) previous.close();
                var session = new Session(player.getUniqueId(), piano);
                if(!piano.active.compareAndSet(null,session)){player.sendMessage("このピアノは演奏中です。");break;}
                sessions.put(player.getUniqueId(), session); session.connect();
                player.sendMessage("演奏画面に接続しています…");
            }
            case "remove", "skin", "volume" -> {
                if (!piano.owner.equals(player.getUniqueId()) && !player.hasPermission("kcraft.piano.admin")) { player.sendMessage("設置者か運営だけが変更できます。"); break; }
                if (args[0].equalsIgnoreCase("remove")) {
                    pianos.remove(name, piano);
                    sessions.values().removeIf(s -> { if (s.piano != piano) return false; s.close(); return true; });
                    at(piano, () -> { piano.removed = true; piano.destroy(); }); save(); player.sendMessage("ピアノを削除しました。");
                } else if (args.length < 3) player.sendMessage("/piano skin 名前 grand|upright|electric|closed または /piano volume 名前 0-100");
                else if (args[0].equalsIgnoreCase("skin")) {
                    Integer model = Map.of("grand",167085,"upright",167087,"electric",167084,"closed",167086).get(args[2]);
                    if (model == null) player.sendMessage("種類: grand / upright / electric / closed");
                    else { piano.skin = model; at(piano, () -> piano.body.getEquipment().setHelmet(item(model, Color.WHITE))); save(); player.sendMessage("ピアノの種類を変更しました。"); }
                } else try { piano.volume = Math.max(0, Math.min(100, Integer.parseInt(args[2]))); save(); player.sendMessage("音量: " + piano.volume + "%"); }
                catch (NumberFormatException e) { player.sendMessage("音量は0〜100です。"); }
            }
            default -> player.sendMessage("/piano で一覧を開けます。");
        }
        return true;
    }
    @Override public List<String> onTabComplete(CommandSender s, Command c, String a, String[] args) {
        List<String> options = args.length == 1 ? List.of("connect","pack","list","create","remove","skin","volume") : args.length == 2 ? new ArrayList<>(pianos.keySet()) : args.length == 3 && args[0].equals("skin") ? List.of("grand","upright","electric","closed") : List.of();
        return options.stream().filter(x -> x.startsWith(args[args.length-1])).sorted().toList();
    }
    private static final class Menu implements InventoryHolder { Inventory inventory; List<String> names; public Inventory getInventory() { return inventory; } }
    private void menu(Player player) {
        Menu holder = new Menu(); holder.names = pianos.keySet().stream().sorted().limit(45).toList();
        holder.inventory = Bukkit.createInventory(holder, 54, Component.text("ピアノを選ぶ"));
        for (int i=0;i<holder.names.size();i++) { var icon = new ItemStack(Material.NOTE_BLOCK); var meta = icon.getItemMeta(); meta.displayName(Component.text(holder.names.get(i))); meta.lore(List.of(Component.text("クリックして演奏画面へ"))); icon.setItemMeta(meta); holder.inventory.setItem(i,icon); }
        var packItem = new ItemStack(Material.BOOK); var meta = packItem.getItemMeta(); meta.displayName(Component.text("リソースパックを適用")); packItem.setItemMeta(meta); holder.inventory.setItem(49,packItem);
        player.openInventory(holder.inventory);
    }
    @EventHandler public void inventory(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof Menu menu)) return;
        e.setCancelled(true); if (!(e.getWhoClicked() instanceof Player p)) return;
        int slot = e.getRawSlot();
        if (slot == 49) { p.closeInventory(); onCommand(p,getCommand("piano"),"piano",new String[]{"pack"}); }
        else if (slot >= 0 && slot < menu.names.size()) { p.closeInventory(); onCommand(p,getCommand("piano"),"piano",new String[]{"connect",menu.names.get(slot)}); }
    }
    @EventHandler public void drag(InventoryDragEvent e) { if (e.getView().getTopInventory().getHolder() instanceof Menu) e.setCancelled(true); }
    @EventHandler public void click(PlayerInteractEvent e) {
        if (!e.getAction().isLeftClick() && !e.getAction().isRightClick()) return;
        Player player = e.getPlayer(); if (!player.hasPermission("kcraft.piano.play")) return;
        long now=System.nanoTime(); Long last=clicks.get(player.getUniqueId()); if (last != null && now-last < 40_000_000) return;
        var eye = player.getEyeLocation(); var direction = eye.getDirection();
        for (var piano : pianos.values()) {
            if (!piano.origin.getWorld().equals(player.getWorld()) || piano.origin.distanceSquared(eye)>100) continue;
            var hit = eye.toVector().clone();
            for (double d=0; d<6; d+=0.025) {
                hit.copy(eye.toVector()).add(direction.clone().multiply(d));
                double x=hit.getX()-piano.origin.getX(), y=hit.getY()-piano.origin.getY(), z=hit.getZ()-piano.origin.getZ();
                if (x < -1.5 || x > 1.1 || y < 1.55 || y > 1.8 || z<0.12 || z>0.55) continue;
                int note = piano.hit(x,z); if (note<0) continue;
                e.setCancelled(true); clicks.put(player.getUniqueId(), now);
                at(piano, () -> { piano.note(note,100,null); Bukkit.getRegionScheduler().runDelayed(this,piano.origin,t -> piano.note(note,0,null),4); }); return;
            }
        }
    }
    private ItemStack item(int model, Color color) {
        ItemStack item = new ItemStack(Material.LEATHER_HORSE_ARMOR); var meta=(LeatherArmorMeta)item.getItemMeta(); meta.setCustomModelData(model); meta.setColor(color); item.setItemMeta(meta); return item;
    }
    private final class Piano {
        final String name; final UUID owner; final Location origin;
        final ArmorStand[] keys = new ArmorStand[88]; final double[] keyX = new double[88];
        final boolean[] black = new boolean[88], pressed = new boolean[88];
        final AtomicReference<Session> active=new AtomicReference<>();
        final AtomicIntegerArray nextAnimation=new AtomicIntegerArray(88);
        final AtomicBoolean animationScheduled=new AtomicBoolean();
        ArmorStand body; final List<ArmorStand> models = new ArrayList<>();
        volatile int skin=167085, volume=70; volatile boolean removed; volatile boolean sustain; int slot; RealtimeAudio.Voice voice;
        Piano(String name, UUID owner, Location origin) { this.name=name; this.owner=owner; this.origin=origin.clone();var slots=pianos.values().stream().map(p->p.slot).toList();while(slots.contains(slot))slot++;if(realtime!=null)voice=new RealtimeAudio.Voice(this.origin,slot);for(int i=0;i<88;i++)nextAnimation.set(i,-1); }
        ArmorStand model(Location loc, int id, boolean small, Color color) {
            ArmorStand stand=loc.getWorld().spawn(loc, ArmorStand.class, a -> { a.setVisible(false); a.setGravity(false); a.setMarker(true); a.setSmall(small); a.setPersistent(false); a.setInvulnerable(true); a.setSilent(true); a.getPersistentDataContainer().set(tag,PersistentDataType.STRING,name); a.getEquipment().setHelmet(item(id,color)); });
            models.add(stand); return stand;
        }
        void spawn() {
            if (body != null) return;
            origin.getChunk().addPluginChunkTicket(PianoPlugin.this);
            body=model(origin,skin,false,Color.WHITE);
            double x=-1.5;
            for (int n=21;n<=108;n++) {
                int i=n-21, pc=n%12; black[i]=pc==1||pc==3||pc==6||pc==8||pc==10;
                keyX[i]=x+(black[i]?0.025:0.05);
                keys[i]=model(origin.clone().add(keyX[i],black[i]?1.12:1.1,black[i]?0.25:0.3),black[i]?167075:167077,true,black[i]?Color.fromRGB(22,22,22):Color.WHITE);
                if (!black[i]) x+=0.05;
            }
            model(origin.clone().add(-0.2,-0.65,1.1),167072,true,Color.WHITE);
        }
        int hit(double x, double z) {
            for (int pass=0;pass<2;pass++) for (int i=0;i<88;i++) if (black[i]==(pass==0) && Math.abs(x-keyX[i])<(black[i]?0.018:0.028) && (!black[i]||z<0.34)) return i+21;
            return -1;
        }
        void note(int note,int velocity,UUID performer) {
            if (removed || body==null || note<21||note>108) return;
            animate(note,velocity);
            if(realtime!=null){realtime.handle(voice,0,note,velocity,volume,performer);return;}
            soundFallback(note,velocity,performer);
        }
        void animate(int note,int velocity){
            if(removed||body==null)return;
            int i=note-21; pressed[i]=velocity>0;
            keys[i].getEquipment().setHelmet(item(black[i]?(velocity>0?167076:167075):(velocity>0?167078:167077),velocity>0?Color.fromRGB(76,151,220):black[i]?Color.fromRGB(22,22,22):Color.WHITE));
        }
        void queueAnimation(int note,int velocity){
            nextAnimation.set(note-21,velocity);
            if(animationScheduled.compareAndSet(false,true))at(this,()->{
                animationScheduled.set(false);
                for(int i=0;i<88;i++){int v=nextAnimation.getAndSet(i,-1);if(v>=0)animate(i+21,v);}
            });
        }
        void soundFallback(int note,int velocity,UUID performer){
            // Send each recipient's sound on that recipient's entity owner thread.
            // No global-region task and no unbounded note queue.
            for (Player listener : players.values()) {
                if (mute && listener.getUniqueId().equals(performer)) continue;
                boolean pedal=sustain; int level=volume;
                onPlayer(listener, () -> {
                    if (!listener.isOnline() || !listener.getWorld().equals(origin.getWorld()) || listener.getLocation().distanceSquared(origin)>range*range) return;
                    String sound="minecraft:"+(pedal?"1c.":"1.")+note;
                    if (velocity>0) listener.playSound(origin,sound,SoundCategory.RECORDS,level/100f*velocity/100f,1f);
                    else if (!pedal) listener.stopSound("minecraft:1."+note,SoundCategory.RECORDS);
                });
            }
        }
        void panic() {
            if(realtime!=null){realtime.handle(voice,2,0,0,volume,null);resetKeys();return;}
            sustain=false; for (int i=0;i<88;i++) if (pressed[i]) note(i+21,0,null);
            for (Player listener:players.values()) onPlayer(listener,()-> { if (listener.getWorld().equals(origin.getWorld()) && listener.getLocation().distanceSquared(origin)<=range*range) for(int n=21;n<=108;n++){listener.stopSound("minecraft:1."+n,SoundCategory.RECORDS);listener.stopSound("minecraft:1c."+n,SoundCategory.RECORDS);} });
        }
        void resetKeys(){for(int i=0;i<88;i++){nextAnimation.set(i,-1);if(pressed[i])animate(i+21,0);}}
        void pedal(boolean down) { if(realtime!=null){realtime.handle(voice,1,0,down?127:0,volume,null);return;}boolean previous=sustain; sustain=down; if(previous&&!down){boolean[] held=pressed.clone();for(Player listener:players.values()) onPlayer(listener,()->{if(listener.getWorld().equals(origin.getWorld())&&listener.getLocation().distanceSquared(origin)<=range*range)for(int n=21;n<=108;n++)if(!held[n-21])listener.stopSound("minecraft:1c."+n,SoundCategory.RECORDS);});} }
        void destroy() { panic(); for (ArmorStand model:models) model.remove(); models.clear(); origin.getChunk().removePluginChunkTicket(PianoPlugin.this); }
    }
    private final class Session implements WebSocket.Listener {
        final UUID player; final Piano piano; final String token;
        final long expires=System.nanoTime()+TimeUnit.MINUTES.toNanos(Math.max(1,Math.min(60,getConfig().getInt("session-minutes",60))));
        final AtomicInteger pending=new AtomicInteger();
        final java.io.ByteArrayOutputStream fragments = new java.io.ByteArrayOutputStream(16);
        volatile WebSocket socket; volatile boolean closed;
        CompletableFuture<WebSocket> outgoing=CompletableFuture.completedFuture(null);
        final AtomicInteger outgoingCount=new AtomicInteger();
        Session(UUID player,Piano piano) { this.player=player; this.piano=piano; byte[] bytes=new byte[32];random.nextBytes(bytes);token=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
        void connect() {
            http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10)).header("Authorization","Bearer "+secret)
                .buildAsync(URI.create(web.replaceFirst("^http","ws")+"/bridge/"+token),this).whenComplete((ws,error)-> {
                    if(error!=null) { close(); Player p=players.get(player);if(p!=null)onPlayer(p,()->p.sendMessage("Web接続に失敗しました。/piano connect "+piano.name+" で再接続してください。")); }
                });
            Bukkit.getAsyncScheduler().runDelayed(PianoPlugin.this,t-> { sessions.remove(player,this); close(); },Math.max(1,Math.min(60,getConfig().getInt("session-minutes",60))),TimeUnit.MINUTES);
        }
        public void onOpen(WebSocket ws) {
            socket=ws;if(closed){ws.abort();return;} ws.request(1);
            Player p=players.get(player);if(p!=null)onPlayer(p,()->p.sendMessage(Component.text("▶ 「"+piano.name+"」の演奏画面を開く（接続URLは共有しないでください）").clickEvent(ClickEvent.openUrl(web+"/#session="+token))));
        }
        public CompletionStage<?> onText(WebSocket ws,CharSequence data,boolean last) { ws.request(1);return null; }
        public CompletionStage<?> onBinary(WebSocket ws,ByteBuffer data,boolean last) {
            int size=data.remaining();
            if(fragments.size()+size>16){close();return null;}
            byte[] bytes=new byte[size];data.get(bytes);fragments.writeBytes(bytes);
            if(last){ byte[] packet=fragments.toByteArray();fragments.reset(); receive(packet); }
            ws.request(1);return null;
        }
        void receive(byte[] bytes) {
            if(closed||System.nanoTime()>expires||bytes.length!=16)return;
            ByteBuffer b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            int type=Byte.toUnsignedInt(b.get()),note=Byte.toUnsignedInt(b.get()),vel=Byte.toUnsignedInt(b.get()),channel=Byte.toUnsignedInt(b.get());
            if(type>3||vel>127||channel>15||(type==0&&(note<21||note>108))||!Double.isFinite(b.getDouble(8)))return;
            long received=System.nanoTime();
            if(realtime!=null){
                realtime.handle(piano.voice,type,note,vel,piano.volume,player);
                acknowledge(bytes,received);
                if(type==0)piano.queueAnimation(note,vel);
                else if(type==2)at(piano,piano::resetKeys);
                return;
            }
            if(pending.incrementAndGet()>512){pending.decrementAndGet();close();return;}
            at(piano,()-> {
                pending.decrementAndGet(); if(closed)return;
                if(type==0)piano.note(note,vel,player);else if(type==1)piano.pedal(vel>0);else if(type==2)piano.panic();
                acknowledge(bytes,received);
            });
        }
        synchronized void acknowledge(byte[] bytes,long received){
            WebSocket ws=socket;if(ws==null||ws.isOutputClosed()||closed)return;
            if(outgoingCount.incrementAndGet()>512){outgoingCount.decrementAndGet();close();return;}
            ByteBuffer ack=ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);ack.put(bytes);ack.putDouble((System.nanoTime()-received)/1_000_000d);ack.flip();
            outgoing=outgoing.thenCompose(ignored->closed?CompletableFuture.completedFuture(ws):ws.sendBinary(ack,true));
            outgoing.whenComplete((ignored,error)->{outgoingCount.decrementAndGet();if(error!=null)close();});
        }
        public CompletionStage<?> onClose(WebSocket ws,int code,String reason) { close();return null; }
        public void onError(WebSocket ws,Throwable error) { close(); }
        void close() { if(closed)return;closed=true;sessions.remove(player,this);piano.active.compareAndSet(this,null);WebSocket ws=socket;if(ws!=null)ws.abort();if(realtime!=null){realtime.handle(piano.voice,2,0,0,piano.volume,null);at(piano,()->{if(piano.active.get()==null)piano.resetKeys();});}else at(piano,()->{if(piano.active.get()==null)piano.panic();}); }
    }
    private void save() {
        var yaml=new YamlConfiguration();
        for(var p:pianos.values()){String n=p.name+".";yaml.set(n+"owner",p.owner.toString());yaml.set(n+"world",p.origin.getWorld().getName());yaml.set(n+"x",p.origin.getX());yaml.set(n+"y",p.origin.getY());yaml.set(n+"z",p.origin.getZ());yaml.set(n+"skin",p.skin);yaml.set(n+"volume",p.volume);yaml.set(n+"slot",p.slot);}
        pendingSave=yaml.saveToString();
        if(running)Bukkit.getAsyncScheduler().runNow(this,t->{synchronized(saveLock){flushSave();}});
    }
    private void flushSave() {
        String text=pendingSave;if(text==null)return;pendingSave=null;
        try {var tmp=new File(getDataFolder(),"pianos.yml.tmp").toPath();java.nio.file.Files.writeString(tmp,text);java.nio.file.Files.move(tmp,new File(getDataFolder(),"pianos.yml").toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING,java.nio.file.StandardCopyOption.ATOMIC_MOVE);}
        catch(Exception e){getLogger().severe("ピアノの保存に失敗: "+e.getMessage());}
    }
}
