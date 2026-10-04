package com.example.heatvision.network;

import com.example.heatvision.HeatVisionMod;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Client -> Server: pemain mulai / berhenti menembakkan laser. */
public record LaserStatePayload(boolean active) implements CustomPayload {
    public static final Id<LaserStatePayload> ID =
            new Id<>(Identifier.of(HeatVisionMod.MOD_ID, "laser_state"));
    public static final PacketCodec<RegistryByteBuf, LaserStatePayload> CODEC =
            PacketCodec.tuple(PacketCodecs.BOOLEAN, LaserStatePayload::active, LaserStatePayload::new);

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
