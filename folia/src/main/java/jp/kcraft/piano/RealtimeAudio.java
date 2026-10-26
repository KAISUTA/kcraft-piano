package jp.kcraft.piano;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;

/** Outgoing packets only. No channel injection or PacketEvents dependency.
 * Connections and positions are captured on the player's entity thread. */
final class RealtimeAudio {
    record Listener(UUID id, UUID world, double x, double y, double z, Object connection) {}
    final class Voice {
        final UUID world; final double x,y,z;
        final Object[] sounds=new Object[88], identifiers=new Object[88];
        final boolean[] held=new boolean[88], sounding=new boolean[88]; boolean pedal;
        Voice(Location origin,int slot) {
            world=origin.getWorld().getUID();x=origin.getX();y=origin.getY();z=origin.getZ();
            try {for(int n=21;n<=108;n++){Object id=parse.invoke(null,"kcraft:p"+slot+"."+n);identifiers[n-21]=id;sounds[n-21]=direct.invoke(null,createSound.invoke(null,id));}}
            catch(ReflectiveOperationException e){throw new IllegalStateException("音源の初期化に失敗",e);}
        }
    }
    private final ConcurrentMap<UUID,Listener> listeners=new ConcurrentHashMap<>();
    private final double rangeSquared; private final boolean mute;
    private final Method getHandle,send,parse,direct,createSound;
    private final Field connection;
    private final Constructor<?> soundPacket,stopPacket;
    private final Object records;
    @SuppressWarnings({"rawtypes","unchecked"}) RealtimeAudio(double range,boolean mute)throws ReflectiveOperationException {
        this.rangeSquared=range*range;this.mute=mute;
        ClassLoader loader=Bukkit.getServer().getClass().getClassLoader();
        Class<?> id;try{id=Class.forName("net.minecraft.resources.Identifier",true,loader);}catch(ClassNotFoundException e){id=Class.forName("net.minecraft.resources.ResourceLocation",true,loader);}
        Class<?> holder=Class.forName("net.minecraft.core.Holder",true,loader),sound=Class.forName("net.minecraft.sounds.SoundEvent",true,loader),source=Class.forName("net.minecraft.sounds.SoundSource",true,loader);
        parse=id.getMethod("parse",String.class);direct=holder.getMethod("direct",Object.class);createSound=sound.getMethod("createVariableRangeEvent",id);
        records=Enum.valueOf((Class<? extends Enum>)source,"RECORDS");
        soundPacket=Class.forName("net.minecraft.network.protocol.game.ClientboundSoundPacket",true,loader).getConstructor(holder,source,double.class,double.class,double.class,float.class,float.class,long.class);
        stopPacket=Class.forName("net.minecraft.network.protocol.game.ClientboundStopSoundPacket",true,loader).getConstructor(id,source);
        Class<?> craft=Class.forName(Bukkit.getServer().getClass().getPackageName()+".entity.CraftPlayer",true,loader);
        getHandle=craft.getMethod("getHandle");connection=getHandle.getReturnType().getField("connection");
        send=connection.getType().getMethod("send",Class.forName("net.minecraft.network.protocol.Packet",true,loader));
    }
    Voice voice(Location location,int slot){return new Voice(location,slot);}
    void publish(Player player,Location location){
        try{Object conn=connection.get(getHandle.invoke(player));if(conn!=null)listeners.put(player.getUniqueId(),new Listener(player.getUniqueId(),location.getWorld().getUID(),location.getX(),location.getY(),location.getZ(),conn));}
        catch(ReflectiveOperationException e){throw new IllegalStateException("接続情報を取得できません",e);}
    }
    void remove(UUID id){listeners.remove(id);}
    void handle(Voice voice,int type,int note,int velocity,int volume,UUID performer){
        synchronized(voice){
            if(type==0){int i=note-21;voice.held[i]=velocity>0;if(velocity>0){if(voice.sounding[i])send(voice,i,0,volume,performer);voice.sounding[i]=true;send(voice,i,velocity,volume,performer);}else if(!voice.pedal){voice.sounding[i]=false;send(voice,i,0,volume,performer);}}
            else if(type==1){voice.pedal=velocity>0;if(!voice.pedal)for(int i=0;i<88;i++)if(!voice.held[i]&&voice.sounding[i]){voice.sounding[i]=false;send(voice,i,0,volume,performer);}}
            else if(type==2){voice.pedal=false;Arrays.fill(voice.held,false);for(int i=0;i<88;i++)if(voice.sounding[i]){voice.sounding[i]=false;send(voice,i,0,volume,null);}}
        }
    }
    private void send(Voice voice,int i,int velocity,int volume,UUID performer){
        try{
            Object packet=velocity>0?soundPacket.newInstance(voice.sounds[i],records,voice.x,voice.y,voice.z,volume/100f*velocity/100f,1f,ThreadLocalRandom.current().nextLong()):stopPacket.newInstance(voice.identifiers[i],records);
            for(Listener listener:listeners.values()){
                if(mute&&listener.id.equals(performer)||!voice.world.equals(listener.world))continue;
                double dx=listener.x-voice.x,dy=listener.y-voice.y,dz=listener.z-voice.z;
                if(dx*dx+dy*dy+dz*dz<=rangeSquared)send.invoke(listener.connection,packet);
            }
        }catch(ReflectiveOperationException e){throw new IllegalStateException("サウンドの送信に失敗",e);}
    }
}
