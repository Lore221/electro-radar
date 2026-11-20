package com.electroradar.radar.network;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.saveddata.SavedData;

/** Тип сети принадлежит ID мира и сохраняется, даже когда все её источники выгружены. */
public final class RadarNetworkKinds extends SavedData {
    public static final Factory<RadarNetworkKinds> FACTORY =
            new Factory<>(RadarNetworkKinds::new,RadarNetworkKinds::load,null);
    private final Map<NetworkId,NetworkKind> kinds=new HashMap<>();

    public Optional<NetworkKind> kind(NetworkId id) { return Optional.ofNullable(kinds.get(id)); }

    public void claim(NetworkId id,NetworkKind kind) {
        if(kind==NetworkKind.INTERCEPTION) throw new IllegalArgumentException("Not a radar network");
        NetworkKind existing=kinds.get(id);
        if(existing!=null && existing!=kind) throw new IllegalArgumentException("Incompatible radar network kind");
        if(existing==null) { kinds.put(id,kind); setDirty(); }
    }

    public static RadarNetworkKinds load(CompoundTag tag,HolderLookup.Provider registries) {
        var data=new RadarNetworkKinds();
        var entries=tag.getList("Networks",Tag.TAG_COMPOUND);
        for(int i=0;i<entries.size();i++) {
            CompoundTag entry=entries.getCompound(i);
            if(!entry.hasUUID("Id")) continue;
            NetworkKind kind=switch(entry.getString("Kind")) {
                case "RADAR" -> NetworkKind.RADAR;
                case "SIMPLE_RADAR" -> NetworkKind.SIMPLE_RADAR;
                default -> null;
            };
            if(kind!=null) data.kinds.put(new NetworkId(entry.getUUID("Id")),kind);
        }
        return data;
    }

    @Override public CompoundTag save(CompoundTag tag,HolderLookup.Provider registries) {
        var entries=new ListTag();
        kinds.forEach((id,kind)->{
            var entry=new CompoundTag();
            entry.putUUID("Id",id.value()); entry.putString("Kind",kind.name()); entries.add(entry);
        });
        tag.put("Networks",entries);
        return tag;
    }
}
