package com.example.heatvision.network;

import com.example.heatvision.HeatVisionMod;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Server -> Client: pemain dengan entityId ini sedang menembakkan laser (agar pemain lain bisa melihatnya). */
public record LaserBeamPayload(int entityId, boolean active) implements CustomPayload {
    public static final Id<LaserBeamPayload> ID =
            new Id<>(Identifier.of(HeatVisionMod.MOD_ID, "laser_beam"));
    public static final PacketCodec<RegistryByteBuf, LaserBeamPayload> CODEC =
            PacketCodec.tuple(
                    PacketCodecs.VAR_INT, LaserBeamPayload::entityId,
                    PacketCodecs.BOOLEAN, LaserBeamPayload::active,
                    LaserBeamPayload::new);

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
