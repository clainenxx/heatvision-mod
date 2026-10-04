package com.example.heatvision;

import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;

public final class ModSounds {
    /** Suara laser yang diulang terus selama heat vision aktif. */
    public static final SoundEvent LASER_LOOP = register("laser_loop");
    /** Suara "pew" sekali saat heat vision mulai menyala. */
    public static final SoundEvent LASER_START = register("laser_start");

    private ModSounds() {}

    private static SoundEvent register(String name) {
        Identifier id = Identifier.of(HeatVisionMod.MOD_ID, name);
        return Registry.register(Registries.SOUND_EVENT, id, SoundEvent.of(id));
    }

    /** Dipanggil dari onInitialize supaya class ini pasti ter-load. */
    public static void init() {}
}
