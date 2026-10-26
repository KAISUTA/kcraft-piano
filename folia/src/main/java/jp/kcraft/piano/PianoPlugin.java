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
import org.bukkit.util.Transformation;
import org.joml.Vector3f;
import org.joml.Quaternionf;
import java.io.File;
import java.net.URI;
import java.net.http.*;
import java.nio.*;
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
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, Long> clicks = new ConcurrentHashMap<>();
    private final Set<UUID> pendingPacks=ConcurrentHashMap.newKeySet();
    private static final UUID PACK_ID=UUID.nameUUIDFromBytes("kcraft-piano-pack".getBytes(java.nio.charset.StandardCharsets.UTF_8));
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
        mute = getConfig().getBoolean("mute-performer-in-game", false);
        try { realtime=new RealtimeAudio(range,mute);getLogger().info("音声: 接続注入なし・tick待ちなしの直接送信"); }
        catch(ReflectiveOperationException e){getLogger().warning("直接音声APIが使えないため安全な標準APIへ切替: "+e);}
        getServer().getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("piano")).setExecutor(this);
        getCommand("piano").setTabCompleter(this);
        Objects.requireNonNull(getCommand("pianoadmin")).setExecutor(this);
        for (Player p : Bukkit.getOnlinePlayers()) { players.put(p.getUniqueId(), p); onPlayer(p,()-> {if(realtime!=null)realtime.publish(p,p.getLocation());schedulePack(p);}); }
        var saved = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "pianos.yml"));
        for (String name : saved.getKeys(false)) {
            try {
                var section = saved.getConfigurationSection(name);
                World world = Bukkit.getWorld(section.getString("world", "")); if (world == null) continue;
                var loc = new Location(world, section.getDouble("x"), section.getDouble("y"), section.getDouble("z"));
                var piano = new Piano(name, UUID.fromString(section.getString("owner")), loc);
                piano.slot = section.getInt("slot",piano.slot);
                if(realtime!=null)piano.voice=realtime.voice(piano.origin,piano.slot);
                piano.origin.setYaw((float)section.getDouble("yaw",0));
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
    @EventHandler(priority=EventPriority.MONITOR) public void join(PlayerJoinEvent e) { players.put(e.getPlayer().getUniqueId(), e.getPlayer());if(realtime!=null)realtime.publish(e.getPlayer(),e.getPlayer().getLocation());schedulePack(e.getPlayer()); }
    @EventHandler public void packStatus(PlayerResourcePackStatusEvent e){if(PACK_ID.equals(e.getID()))getLogger().info("PIANO PACK status="+e.getStatus()+" player="+e.getPlayer().getName());}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void move(PlayerMoveEvent e) { if(realtime!=null && e.getTo()!=null && e.hasChangedPosition())realtime.publish(e.getPlayer(),e.getTo()); }
    @EventHandler(priority=EventPriority.MONITOR) public void changedWorld(PlayerChangedWorldEvent e) { if(realtime!=null)realtime.publish(e.getPlayer(),e.getPlayer().getLocation()); }
    @EventHandler public void quit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId(); players.remove(id); clicks.remove(id);
        if(realtime!=null)realtime.remove(id);
        pendingPacks.remove(id);
    }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if(command.getName().equalsIgnoreCase("pianoadmin")){
            if(sender instanceof Player&&!sender.hasPermission("kcraft.piano.admin"))return true;
            if(args.length==2&&args[0].equals("pack")){Player target=players.values().stream().filter(p->p.getName().equalsIgnoreCase(args[1])).findFirst().orElse(null);if(target!=null)onPlayer(target,()->schedulePack(target));sender.sendMessage(target==null?"Player offline":"Pack refresh scheduled");}
            else sender.sendMessage("READY version="+getPluginMeta().getVersion()+" audio="+(realtime==null?"fallback":"direct")+" pianos="+pianos.size()+" onlineSnapshots="+players.size()+" fixedBridges="+sessions.size()+" packsWaiting="+pendingPacks.size());
            return true;
        }
        if (!(sender instanceof Player player)) { sender.sendMessage("ゲーム内から実行してください。"); return true; }
        if(!Bukkit.isOwnedByCurrentRegion(player)){String[] copy=args.clone();onPlayer(player,()->onCommand(player,command,label,copy));return true;}
        if (!player.hasPermission("kcraft.piano.play")) { player.sendMessage("演奏する権限がありません。"); return true; }
        if (args.length == 0) { menu(player); return true; }
        if (args[0].equalsIgnoreCase("pack")) {
            if (pack.isBlank()) player.sendMessage("リソースパックが未設定です。");
            else {schedulePack(player);player.sendMessage("パックの適用を予約しました。チュートリアル中は終了後に適用します。");}
            return true;
        }
        if (args[0].equalsIgnoreCase("list")) { player.sendMessage("ピアノ: " + String.join("、", new TreeSet<>(pianos.keySet()))); return true; }
        if(args.length==1&&args[0].equalsIgnoreCase("create")){String generated="piano1";for(int i=1;pianos.containsKey(generated);i++)generated="piano"+(i+1);return onCommand(player,command,label,new String[]{"create",generated});}
        if (args.length < 2) { player.sendMessage("/piano で一覧を開けます。"); return true; }
        String name = args[1];
        if (args[0].equalsIgnoreCase("create")) {
            if (!player.hasPermission("kcraft.piano.create")) { player.sendMessage("設置は運営に依頼してください。"); return true; }
            if (!name.matches("[A-Za-z0-9_-]{1,24}")) { player.sendMessage("名前は半角英数字・_・-の1〜24文字です。"); return true; }
            if (pianos.size() >= Math.min(10,getConfig().getInt("max-pianos", 10))) { player.sendMessage("設置数の上限です。"); return true; }
            var loc = placement(player);
            if (pianos.values().stream().anyMatch(p -> p.origin.getWorld().equals(loc.getWorld()) && p.origin.distanceSquared(loc) < 16)) {
                player.sendMessage("ピアノが重なるため、少し離れた場所を指定してください。"); return true;
            }
            var piano = new Piano(name, player.getUniqueId(), loc);
            if (pianos.putIfAbsent(name, piano) != null) { player.sendMessage("その名前は使用中です。"); return true; }
            piano.spawn(); save(); player.sendMessage("ピアノ「" + name + "」を指定位置に設置しました。/piano pack でモデルを表示できます。"); return true;
        }
        Piano piano = pianos.get(name);
        if (piano == null) { player.sendMessage("そのピアノはありません。/piano list"); return true; }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "connect" -> {
                if (!piano.origin.getWorld().equals(player.getWorld()) || player.getLocation().distanceSquared(piano.origin) > 1024) { player.sendMessage("ピアノの32ブロック以内で接続してください。"); break; }
                if (secret.isBlank()) { player.sendMessage("Web接続の設定がまだ完了していません。"); break; }
                startBridge(piano);
                player.sendMessage(Component.text("▶ 「"+piano.name+"」の固定演奏ページ（パスワード入力）").clickEvent(ClickEvent.openUrl(web+"/#piano="+java.net.URLEncoder.encode(piano.name,java.nio.charset.StandardCharsets.UTF_8))));
            }
            case "remove", "remo", "delete", "move", "skin", "volume" -> {
                if (!piano.owner.equals(player.getUniqueId()) && !player.hasPermission("kcraft.piano.admin")) { player.sendMessage("設置者か運営だけが変更できます。"); break; }
                if (Set.of("remove","remo","delete").contains(args[0].toLowerCase(Locale.ROOT))) {
                    pianos.remove(name, piano);
                    sessions.values().removeIf(s -> { if (s.piano != piano) return false; s.close(); return true; });
                    piano.destroy(); save(); player.sendMessage("ピアノを削除しました。");
                } else if(args[0].equalsIgnoreCase("move")){
                    Session active=piano.active.get();if(active!=null)active.close();
                    Piano moved=new Piano(piano.name,piano.owner,placement(player));moved.slot=piano.slot;moved.skin=piano.skin;moved.volume=piano.volume;if(realtime!=null)moved.voice=realtime.voice(moved.origin,moved.slot);
                    pianos.put(name,moved);piano.destroy();moved.spawn();save();player.sendMessage("指定位置へ移動しました。");
                } else if (args.length < 3) player.sendMessage("/piano skin 名前 grand|upright|electric|closed または /piano volume 名前 0-100");
                else if (args[0].equalsIgnoreCase("skin")) {
                    Integer model = Map.of("grand",167085,"upright",167087,"electric",167084,"closed",167086).get(args[2]);
                    if (model == null) player.sendMessage("種類: grand / upright / electric / closed");
                    else { piano.skin = model; piano.entity(piano.body,()->piano.body.setItemStack(item(model,Color.WHITE))); save(); player.sendMessage("ピアノの種類を変更しました。"); }
                } else try { piano.volume = Math.max(0, Math.min(100, Integer.parseInt(args[2]))); save(); player.sendMessage("音量: " + piano.volume + "%"); }
                catch (NumberFormatException e) { player.sendMessage("音量は0〜100です。"); }
            }
            default -> player.sendMessage("/piano で一覧を開けます。");
        }
        return true;
    }
    @Override public List<String> onTabComplete(CommandSender s, Command c, String a, String[] args) {
        List<String> options = args.length == 1 ? List.of("connect","pack","list","create","remove","move","skin","volume") : args.length == 2 ? new ArrayList<>(pianos.keySet()) : args.length == 3 && args[0].equals("skin") ? List.of("grand","upright","electric","closed") : List.of();
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
        if(e.getHand()!=null&&e.getHand()!=EquipmentSlot.HAND)return;
        if (!e.getAction().isLeftClick() && !e.getAction().isRightClick()) return;
        Player player = e.getPlayer(); if (!player.hasPermission("kcraft.piano.play")) return;
        long now=System.nanoTime(); Long last=clicks.get(player.getUniqueId()); if (last != null && now-last < 40_000_000) return;
        var eye = player.getEyeLocation(); var direction = eye.getDirection();
        Piano selected=null;KeyboardGeometry.Hit nearest=null;
        for (var piano : pianos.values()) {
            if (!piano.origin.getWorld().equals(player.getWorld()) || piano.origin.distanceSquared(eye)>100) continue;
            if(!piano.ready)continue;
            var hit=piano.hit(eye,direction);
            if(hit!=null&&(nearest==null||hit.distance()<nearest.distance())){nearest=hit;selected=piano;}
        }
        if(selected==null)return;
        if(e.getClickedBlock()!=null&&eye.distanceSquared(e.getClickedBlock().getLocation().add(.5,.5,.5))<Math.pow(Math.max(0,nearest.distance()-.9),2))return;
        e.setCancelled(true);clicks.put(player.getUniqueId(),now);Piano target=selected;int note=nearest.note();
        if(realtime!=null)realtime.handle(target.voice,0,note,100,target.volume,null);
        target.queueAnimation(note,100);
        if(realtime==null)at(target,()->target.soundFallback(note,100,null));
        Bukkit.getAsyncScheduler().runDelayed(this,t->{if(target.removed)return;if(realtime!=null)realtime.handle(target.voice,0,note,0,target.volume,null);else at(target,()->target.soundFallback(note,0,null));target.queueAnimation(note,0);},160,TimeUnit.MILLISECONDS);
    }
    private Location placement(Player player){
        Location result=player.getLocation().clone();
        org.bukkit.block.Block block=player.getTargetBlockExact(5,FluidCollisionMode.NEVER);
        if(block!=null)result=block.getLocation().add(.5,1,.5);else{var forward=result.getDirection().setY(0);if(forward.lengthSquared()>0)result.add(forward.normalize().multiply(2));}
        result.setYaw(player.getLocation().getYaw()+180);result.setPitch(0);return result;
    }
    private ItemStack item(int model, Color color) {
        ItemStack item = new ItemStack(Material.LEATHER_HORSE_ARMOR); var meta=(LeatherArmorMeta)item.getItemMeta(); meta.setCustomModelData(model); meta.setColor(color);
        String path=Map.of(167072,"bench",167075,"key/piano_black_key",167076,"key/piano_black_key_down",167077,"key/piano_key",167078,"key/piano_key_down",167084,"piano/electric_piano",167085,"piano/grand_piano",167086,"piano/grand_piano_close",167087,"piano/up_right_piano_close").get(model);
        if(path!=null)meta.setItemModel(new NamespacedKey("kcraft",path));item.setItemMeta(meta);return item;
    }
    private final class Piano {
        final String name; final UUID owner; final Location origin;
        final KeyboardGeometry geometry=new KeyboardGeometry();
        final ItemDisplay[] keys=new ItemDisplay[88];
        final boolean[] pressed=new boolean[88];
        final AtomicReference<Session> active=new AtomicReference<>();
        final AtomicIntegerArray nextAnimation=new AtomicIntegerArray(88);
        final AtomicBoolean animationScheduled=new AtomicBoolean();
        volatile ItemDisplay body; final List<ItemDisplay> models=new CopyOnWriteArrayList<>();
        final Set<Long> chunks=ConcurrentHashMap.newKeySet();
        volatile int skin=167085,volume=70;volatile boolean removed,ready;volatile boolean sustain;int slot;RealtimeAudio.Voice voice;
        Piano(String name,UUID owner,Location origin){this.name=name;this.owner=owner;this.origin=origin.clone();var slots=pianos.values().stream().map(p->p.slot).toList();while(slots.contains(slot))slot++;if(realtime!=null)voice=realtime.voice(this.origin,slot);for(int i=0;i<88;i++)nextAnimation.set(i,-1);}
        Location position(double x,double y,double z){double a=Math.toRadians(origin.getYaw()),c=Math.cos(a),v=Math.sin(a);Location location=origin.clone().add(c*x-v*z,y,v*x+c*z);location.setPitch(0);return location;}
        void entity(ItemDisplay display,Runnable action){if(display==null)return;if(Bukkit.isOwnedByCurrentRegion(display))action.run();else display.getScheduler().execute(PianoPlugin.this,action,null,1);}
        // Cancel ItemDisplay built-in Y=180 before scaling; keep translation unchanged.
        CompletableFuture<ItemDisplay> model(Location loc,int id,Color color,Vector3f offset,Vector3f scale){
            var result=new CompletableFuture<ItemDisplay>();
            Runnable create=()->{
                if(removed){result.complete(null);return;}
                try{ItemDisplay display=loc.getWorld().spawn(loc,ItemDisplay.class,e->{e.setItemStack(item(id,color));e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);e.setPersistent(false);e.setInvulnerable(true);e.setSilent(true);e.setDisplayWidth(8);e.setDisplayHeight(6);e.setInterpolationDuration(0);e.setTeleportDuration(0);e.setTransformation(new Transformation(offset,new Quaternionf(),scale,new Quaternionf().rotationY((float)Math.PI)));e.getPersistentDataContainer().set(tag,PersistentDataType.STRING,name);});models.add(display);result.complete(display);}
                catch(Throwable error){result.completeExceptionally(error);}
            };
            if(Bukkit.isOwnedByCurrentRegion(loc))create.run();else Bukkit.getRegionScheduler().execute(PianoPlugin.this,loc,create);
            return result;
        }
        void spawn(){
            if(removed||ready)return;
            List<CompletableFuture<Void>> cleanup=new ArrayList<>();
            int minX=(int)Math.floor(origin.getX()-4)>>4,maxX=(int)Math.floor(origin.getX()+4)>>4,minZ=(int)Math.floor(origin.getZ()-4)>>4,maxZ=(int)Math.floor(origin.getZ()+4)>>4;
            for(int x=minX;x<=maxX;x++)for(int z=minZ;z<=maxZ;z++){
                int cx=x,cz=z;var done=new CompletableFuture<Void>();cleanup.add(done);
                Bukkit.getRegionScheduler().execute(PianoPlugin.this,origin.getWorld(),cx,cz,()->{
                    try{if(removed){done.complete(null);return;}Chunk chunk=origin.getWorld().getChunkAt(cx,cz);chunk.addPluginChunkTicket(PianoPlugin.this);chunks.add(((long)cx<<32)^(cz&0xffffffffL));for(Entity old:chunk.getEntities())if(name.equals(old.getPersistentDataContainer().get(tag,PersistentDataType.STRING)))old.remove();done.complete(null);}
                    catch(Throwable error){done.completeExceptionally(error);}
                });
            }
            CompletableFuture.allOf(cleanup.toArray(CompletableFuture[]::new)).thenCompose(ignored->{
                List<CompletableFuture<?>> created=new ArrayList<>();
                created.add(model(origin,skin,Color.WHITE,new Vector3f(.16875f,.9f,.2f),new Vector3f(1.8f)).thenAccept(e->body=e));
                for(int i=0;i<88;i++){int index=i;created.add(model(position(geometry.x[i],geometry.floor(i),geometry.centerZ(i)),geometry.black[i]?167075:167077,geometry.black[i]?Color.fromRGB(22,22,22):Color.WHITE,new Vector3f(0,.1f,0),new Vector3f((float)(4*KeyboardGeometry.WHITE_WIDTH),.2f,(float)geometry.depthScale(i))).thenAccept(e->keys[index]=e));}
                created.add(model(position(0,0,1.05),167072,Color.WHITE,new Vector3f(0,.5f,0),new Vector3f(1)));
                return CompletableFuture.allOf(created.toArray(CompletableFuture[]::new));
            }).whenComplete((ignored,error)->{if(error!=null)getLogger().severe("Model creation failed "+name+": "+error);else{ready=!removed;if(ready)startBridge(this);getLogger().info("MODEL READY name="+name+" pieces="+models.size()+" yaw="+origin.getYaw());}});
        }
        KeyboardGeometry.Hit hit(Location eye,org.bukkit.util.Vector direction){
            double a=Math.toRadians(origin.getYaw()),c=Math.cos(a),v=Math.sin(a),x=eye.getX()-origin.getX(),z=eye.getZ()-origin.getZ();
            return geometry.ray(c*x+v*z,eye.getY()-origin.getY(),-v*x+c*z,c*direction.getX()+v*direction.getZ(),direction.getY(),-v*direction.getX()+c*direction.getZ(),6);
        }
        void note(int note,int velocity,UUID performer){if(removed||note<21||note>108)return;animate(note,velocity);if(realtime!=null)realtime.handle(voice,0,note,velocity,volume,performer);else soundFallback(note,velocity,performer);}
        void animate(int note,int velocity){
            if(removed)return;int i=note-21;pressed[i]=velocity>0;
            ItemDisplay key=keys[i];if(key==null)return;boolean black=geometry.black[i];
            ItemStack stack=item(black?(velocity>0?167076:167075):(velocity>0?167078:167077),velocity>0?Color.fromRGB(76,151,220):black?Color.fromRGB(22,22,22):Color.WHITE);
            entity(key,()->key.setItemStack(stack));
        }
        void queueAnimation(int note,int velocity){nextAnimation.set(note-21,velocity);if(animationScheduled.compareAndSet(false,true))at(this,()->{animationScheduled.set(false);for(int i=0;i<88;i++){int v=nextAnimation.getAndSet(i,-1);if(v>=0)animate(i+21,v);}});}
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
        void destroy(){
            if(removed)return;panic();removed=true;ready=false;
            for(ItemDisplay display:models)entity(display,display::remove);models.clear();
            for(long key:chunks){int x=(int)(key>>32),z=(int)key;Bukkit.getRegionScheduler().execute(PianoPlugin.this,origin.getWorld(),x,z,()->origin.getWorld().getChunkAt(x,z).removePluginChunkTicket(PianoPlugin.this));}chunks.clear();
        }
    }
    private final class Session implements WebSocket.Listener {
        final Piano piano; final String token;
        final AtomicInteger pending=new AtomicInteger();
        final java.io.ByteArrayOutputStream fragments = new java.io.ByteArrayOutputStream(16);
        volatile WebSocket socket; volatile boolean closed;
        volatile long probeSent;
        CompletableFuture<WebSocket> outgoing=CompletableFuture.completedFuture(null);
        final AtomicInteger outgoingCount=new AtomicInteger();
        Session(Piano piano) {this.piano=piano;try{token=Base64.getUrlEncoder().withoutPadding().encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(piano.name.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}

        void connect() {
            http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10)).header("Authorization","Bearer "+secret)
                .buildAsync(URI.create(web.replaceFirst("^http","ws")+"/bridge/"+token+"?name="+java.net.URLEncoder.encode(piano.name,java.nio.charset.StandardCharsets.UTF_8)),this).whenComplete((ws,error)-> {
                    if(error!=null) retry();
                });

        }
        public void onOpen(WebSocket ws) {
            socket=ws;if(closed){ws.abort();return;} ws.request(1);
            // Warm the relay before the first MIDI note; serialize with later binary sends.
            probeSent=System.nanoTime();outgoing=ws.sendText("ping",true);
        }
        public CompletionStage<?> onText(WebSocket ws,CharSequence data,boolean last) { if(getConfig().getBoolean("latency-probe",false)&&last&&data.toString().equals("pong")&&probeSent!=0)getLogger().info("BRIDGE PROBE ms="+(System.nanoTime()-probeSent)/1_000_000d);ws.request(1);return null; }
        public CompletionStage<?> onBinary(WebSocket ws,ByteBuffer data,boolean last) {
            int size=data.remaining();
            if(fragments.size()+size>16){close();return null;}
            byte[] bytes=new byte[size];data.get(bytes);fragments.writeBytes(bytes);
            if(last){ byte[] packet=fragments.toByteArray();fragments.reset(); receive(packet); }
            ws.request(1);return null;
        }
        void receive(byte[] bytes) {
            if(closed||bytes.length!=16)return;
            ByteBuffer b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            int type=Byte.toUnsignedInt(b.get()),note=Byte.toUnsignedInt(b.get()),vel=Byte.toUnsignedInt(b.get()),channel=Byte.toUnsignedInt(b.get());
            if(type>3||vel>127||channel>15||(type==0&&(note<21||note>108))||!Double.isFinite(b.getDouble(8)))return;
            long received=System.nanoTime();
            if(realtime!=null){
                realtime.handle(piano.voice,type,note,vel,piano.volume,null);
                acknowledge(bytes,received);
                if(type==0)piano.queueAnimation(note,vel);
                else if(type==2)at(piano,piano::resetKeys);
                return;
            }
            if(pending.incrementAndGet()>512){pending.decrementAndGet();close();return;}
            at(piano,()-> {
                pending.decrementAndGet(); if(closed)return;
                if(type==0)piano.note(note,vel,null);else if(type==1)piano.pedal(vel>0);else if(type==2)piano.panic();
                acknowledge(bytes,received);
            });
        }
        synchronized void acknowledge(byte[] bytes,long received){
            WebSocket ws=socket;if(ws==null||ws.isOutputClosed()||closed)return;
            if(outgoingCount.incrementAndGet()>512){outgoingCount.decrementAndGet();close();return;}
            ByteBuffer ack=ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);ack.put(bytes);ack.putDouble((System.nanoTime()-received)/1_000_000d);ack.flip();
            outgoing=outgoing.thenCompose(ignored->closed?CompletableFuture.completedFuture(ws):ws.sendBinary(ack,true));
            outgoing.whenComplete((ignored,error)->{outgoingCount.decrementAndGet();if(error!=null)retry();});
        }
        public CompletionStage<?> onClose(WebSocket ws,int code,String reason) { retry();return null; }
        public void onError(WebSocket ws,Throwable error) { retry(); }
        synchronized void retry(){if(closed)return;close();if(running&&!piano.removed&&pianos.get(piano.name)==piano)Bukkit.getAsyncScheduler().runDelayed(PianoPlugin.this,t->startBridge(piano),2,TimeUnit.SECONDS);}
        void close() { if(closed)return;closed=true;sessions.remove(piano.name,this);piano.active.compareAndSet(this,null);WebSocket ws=socket;if(ws!=null)ws.abort();if(realtime!=null){realtime.handle(piano.voice,2,0,0,piano.volume,null);at(piano,()->{if(piano.active.get()==null)piano.resetKeys();});}else at(piano,()->{if(piano.active.get()==null)piano.panic();}); }
    }
    private synchronized void startBridge(Piano piano){
        if(!running||piano.removed||!piano.ready||web.isBlank()||secret.isBlank()||pianos.get(piano.name)!=piano)return;
        Session bridge=new Session(piano);if(sessions.putIfAbsent(piano.name,bridge)!=null)return;piano.active.set(bridge);bridge.connect();
    }
    private boolean bedrock(Player player){
        if(Bukkit.getPluginManager().getPlugin("floodgate")==null)return false;
        try{Class<?> api=Class.forName("org.geysermc.floodgate.api.FloodgateApi",true,Bukkit.getPluginManager().getPlugin("floodgate").getClass().getClassLoader());Object instance=api.getMethod("getInstance").invoke(null);return (boolean)api.getMethod("isFloodgatePlayer",UUID.class).invoke(instance,player.getUniqueId());}catch(ReflectiveOperationException e){return player.getUniqueId().getMostSignificantBits()==0;}
    }
    private void schedulePack(Player player){
        if(pack.isBlank()||bedrock(player)||!getConfig().getBoolean("auto-resource-pack",true)||!pendingPacks.add(player.getUniqueId()))return;
        int[] idle={0};player.getScheduler().runAtFixedRate(this,task->{
            if(!running||!player.isOnline()){pendingPacks.remove(player.getUniqueId());task.cancel();return;}
            if(player.getMetadata("kcraft_touring").stream().anyMatch(org.bukkit.metadata.MetadataValue::asBoolean)){idle[0]=0;return;}
            if(++idle[0]<2)return;
            try{byte[] hash=java.util.HexFormat.of().parseHex(getConfig().getString("resource-pack-sha1",""));player.addResourcePack(PACK_ID,pack,hash,"K-Craftのピアノモデル・音源を読み込みます",false);getLogger().info("PIANO PACK sent player="+player.getName()+" afterTutorial=true");}
            catch(RuntimeException e){getLogger().warning("Piano pack request failed: "+e.getMessage());}
            pendingPacks.remove(player.getUniqueId());task.cancel();
        },()->pendingPacks.remove(player.getUniqueId()),40L,20L);
    }
    private void save() {
        var yaml=new YamlConfiguration();
        for(var p:pianos.values()){String n=p.name+".";yaml.set(n+"owner",p.owner.toString());yaml.set(n+"world",p.origin.getWorld().getName());yaml.set(n+"x",p.origin.getX());yaml.set(n+"y",p.origin.getY());yaml.set(n+"z",p.origin.getZ());yaml.set(n+"skin",p.skin);yaml.set(n+"volume",p.volume);yaml.set(n+"slot",p.slot);yaml.set(n+"yaw",p.origin.getYaw());}
        pendingSave=yaml.saveToString();
        if(running)Bukkit.getAsyncScheduler().runNow(this,t->{synchronized(saveLock){flushSave();}});
    }
    private void flushSave() {
        String text=pendingSave;if(text==null)return;pendingSave=null;
        try {var tmp=new File(getDataFolder(),"pianos.yml.tmp").toPath();java.nio.file.Files.writeString(tmp,text);java.nio.file.Files.move(tmp,new File(getDataFolder(),"pianos.yml").toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING,java.nio.file.StandardCopyOption.ATOMIC_MOVE);}
        catch(Exception e){getLogger().severe("ピアノの保存に失敗: "+e.getMessage());}
    }
}
