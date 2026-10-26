package jp.kcraft.piano;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.protocol.sound.*;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSoundEffect;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import java.util.*;
import java.util.concurrent.*;

/** Network-only path. Player/world state is published by the owning entity thread;
 * the WebSocket callback only reads snapshots and writes through PacketEvents/Netty. */
final class RealtimeAudio {
    record Listener(UUID id, UUID world, double x, double y, double z, User user) {}
    static final class Voice {
        final UUID world; final Vector3d origin; final Sound[] sounds=new Sound[88];
        final boolean[] held=new boolean[88], sounding=new boolean[88]; boolean pedal;
        Voice(Location origin,int slot) {
            world=origin.getWorld().getUID();this.origin=new Vector3d(origin.getX(),origin.getY(),origin.getZ());
            for(int n=21;n<=108;n++)sounds[n-21]=Sounds.getByNameOrCreate("kcraft:p"+slot+"."+n);
        }
    }
    private final ConcurrentMap<UUID,Listener> listeners=new ConcurrentHashMap<>();
    private final double rangeSquared; private final boolean mute;
    RealtimeAudio(double range,boolean mute){this.rangeSquared=range*range;this.mute=mute;}
    void publish(Player player,Location location){
        User user=PacketEvents.getAPI().getPlayerManager().getUser(player);
        if(user!=null)listeners.put(player.getUniqueId(),new Listener(player.getUniqueId(),location.getWorld().getUID(),location.getX(),location.getY(),location.getZ(),user));
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
        for(Listener listener:listeners.values()){
            if(mute&&listener.id.equals(performer)||!voice.world.equals(listener.world))continue;
            double dx=listener.x-voice.origin.x,dy=listener.y-voice.origin.y,dz=listener.z-voice.origin.z;
            if(dx*dx+dy*dy+dz*dz>rangeSquared)continue;
            // One wrapper per recipient: PacketEvents wrappers contain mutable encoding state.
            if(velocity>0)listener.user.sendPacket(new WrapperPlayServerSoundEffect(voice.sounds[i],SoundCategory.RECORD,voice.origin,volume/100f*velocity/100f,1f));
            else listener.user.sendPacket(new StopSound(voice.sounds[i].getSoundId().toString()));
        }
    }
    /** STOP_SOUND has no stock PacketEvents wrapper. Its protocol is flags, category, name. */
    private static final class StopSound extends PacketWrapper<StopSound>{
        final String name;
        StopSound(String name){super(PacketType.Play.Server.STOP_SOUND);this.name=name;}
        @Override public void write(){writeByte(3);writeVarInt(SoundCategory.RECORD.ordinal());writeString(name);}
    }
}
