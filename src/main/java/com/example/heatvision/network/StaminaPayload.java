package com.example.heatvision.network;

import com.example.heatvision.HeatVisionMod;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Server -> Client: sisa stamina (tick) dan sisa cooldown (tick) heat vision milik pemain. */
public record StaminaPayload(int stamina, int cooldown) implements CustomPayload {
    public static final Id<StaminaPayload> ID =
            new Id<>(Identifier.of(HeatVisionMod.MOD_ID, "stamina"));
    public static final PacketCodec<RegistryByteBuf, StaminaPayload> CODEC =
            PacketCodec.tuple(
                    PacketCodecs.VAR_INT, StaminaPayload::stamina,
                    PacketCodecs.VAR_INT, StaminaPayload::cooldown,
                    StaminaPayload::new);

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
