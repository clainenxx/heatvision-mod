package com.example.heatvision.client;

import net.minecraft.client.sound.MovingSoundInstance;
import net.minecraft.entity.Entity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;

/** Suara laser yang mengikuti pemain selama heat vision-nya aktif. */
public class LaserSoundInstance extends MovingSoundInstance {
    private final Entity entity;

    public LaserSoundInstance(Entity entity, SoundEvent event, boolean loop, float volume) {
        super(event, SoundCategory.PLAYERS, entity.getWorld().getRandom());
        this.entity = entity;
        this.repeat = loop;
        this.repeatDelay = 0;
        this.volume = volume;
        this.pitch = 1.0f;
        follow();
    }

    private void follow() {
        this.x = entity.getX();
        this.y = entity.getEyeY();
        this.z = entity.getZ();
    }

    @Override
    public void tick() {
        if (entity.isRemoved() || !HeatVisionClient.isShooting(entity.getId())) {
            setDone();
            return;
        }
        follow();
    }
}
