package com.example.heatvision;

import com.example.heatvision.network.LaserBeamPayload;
import com.example.heatvision.network.LaserStatePayload;
import com.example.heatvision.network.StaminaPayload;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.AbstractFireBlock;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Heat Vision - sisi server: raycast laser tiap tick, damage entity, bakar & hancurkan blok.
 */
public class HeatVisionMod implements ModInitializer {
    public static final String MOD_ID = "heatvision";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    // ======================= PENGATURAN =======================
    /** Jarak maksimum laser (blok). */
    public static final double RANGE = 48.0;
    /** Damage per hit ke entity (2.0 = 1 hati). */
    public static final float DAMAGE = 2.0f;
    /** Damage diberikan tiap N tick selama laser mengenai entity (5 tick = 4x per detik). */
    public static final int HIT_INTERVAL_TICKS = 5;
    /** Lama entity terbakar (detik), di-refresh terus selama kena laser. */
    public static final float BURN_SECONDS = 6.0f;
    /** Waktu dasar sebelum blok hancur (tick) + tambahan per hardness blok. */
    public static final int BREAK_BASE_TICKS = 6;
    public static final float BREAK_TICKS_PER_HARDNESS = 5.0f;
    public static final int BREAK_MAX_TICKS = 100;
    /** Apakah laser boleh menghancurkan blok. */
    public static final boolean BREAK_BLOCKS = true;
    /** Stamina maksimum = lama heat vision bisa dipakai terus-menerus (tick). 200 tick = 10 detik. */
    public static final int MAX_STAMINA_TICKS = 200;
    /** Cooldown kalau stamina habis (tick). 400 tick = 20 detik. Stamina penuh lagi setelah cooldown selesai. */
    public static final int COOLDOWN_TICKS = 400;
    // Isi ulang stamina: 1 tick stamina per tick (selama tidak dipakai), jadi dipakai 5 detik = isi ulang 5 detik.
    // ==========================================================

    private static final SoundEvent EXPLODE_SOUND = SoundEvent.of(Identifier.ofVanilla("entity.generic.explode"));
    private static final SoundEvent FIRE_SOUND = SoundEvent.of(Identifier.ofVanilla("block.fire.ambient"));

    private static final class State {
        BlockPos target;
        int progress;
        int ticks;
    }

    /** Stamina & cooldown heat vision per pemain. */
    private static final class Stamina {
        int value = MAX_STAMINA_TICKS;
        int cooldown = 0;
        boolean dirty = true;
        int sinceSync = 0;
    }

    private static final Map<UUID, State> ACTIVE = new HashMap<>();
    private static final Map<UUID, Stamina> STAMINA = new HashMap<>();

    @Override
    public void onInitialize() {
        PayloadTypeRegistry.playC2S().register(LaserStatePayload.ID, LaserStatePayload.CODEC);
        PayloadTypeRegistry.playS2C().register(LaserBeamPayload.ID, LaserBeamPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(StaminaPayload.ID, StaminaPayload.CODEC);
        ModSounds.init();

        ServerPlayNetworking.registerGlobalReceiver(LaserStatePayload.ID, (payload, context) ->
                setActive(context.player(), payload.active()));

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                ACTIVE.remove(handler.getPlayer().getUuid()));

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                staminaOf(handler.getPlayer()).dirty = true);

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            // stamina / cooldown semua pemain online
            for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                tickStamina(p, staminaOf(p));
            }
            if (ACTIVE.isEmpty()) return;
            Iterator<Map.Entry<UUID, State>> it = ACTIVE.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<UUID, State> entry = it.next();
                ServerPlayerEntity player = server.getPlayerManager().getPlayer(entry.getKey());
                if (player == null) {
                    it.remove();
                    continue;
                }
                if (!player.isAlive() || player.isSpectator()) {
                    broadcast(player, false);
                    it.remove();
                    continue;
                }
                tickPlayer(player, entry.getValue());
            }
        });

        LOGGER.info("Heat Vision loaded.");
    }

    private static Stamina staminaOf(ServerPlayerEntity player) {
        return STAMINA.computeIfAbsent(player.getUuid(), k -> new Stamina());
    }

    private static void tickStamina(ServerPlayerEntity player, Stamina s) {
        boolean firing = ACTIVE.containsKey(player.getUuid());

        if (s.cooldown > 0) {
            s.cooldown--;
            if (s.cooldown == 0) {
                s.value = MAX_STAMINA_TICKS; // cooldown selesai -> stamina penuh
                s.dirty = true;
            }
        } else if (firing) {
            s.value--;
            if (s.value <= 0) {
                s.value = 0;
                s.cooldown = COOLDOWN_TICKS;
                s.dirty = true;
                ACTIVE.remove(player.getUuid());
                broadcast(player, false);
            }
        } else if (s.value < MAX_STAMINA_TICKS) {
            s.value++; // isi ulang secepat pemakaian (1:1), dan boleh dipakai lagi kapan saja selama > 0
            if (s.value >= MAX_STAMINA_TICKS) s.dirty = true;
        }

        boolean idle = s.value >= MAX_STAMINA_TICKS && s.cooldown == 0;
        if (s.dirty || (!idle && ++s.sinceSync >= 4)) {
            s.dirty = false;
            s.sinceSync = 0;
            ServerPlayNetworking.send(player, new StaminaPayload(s.value, s.cooldown));
        }
    }

    private static void setActive(ServerPlayerEntity player, boolean active) {
        UUID id = player.getUuid();
        if (active) {
            if (!player.isAlive() || player.isSpectator()) return;
            Stamina s = staminaOf(player);
            if (s.cooldown > 0 || s.value <= 0) {
                s.dirty = true; // tolak, sinkronkan ulang ke client
                return;
            }
            if (!ACTIVE.containsKey(id)) {
                ACTIVE.put(id, new State());
                s.dirty = true;
                broadcast(player, true);
            }
        } else if (ACTIVE.remove(id) != null) {
            staminaOf(player).dirty = true;
            broadcast(player, false);
        }
    }

    private static void broadcast(ServerPlayerEntity player, boolean active) {
        LaserBeamPayload payload = new LaserBeamPayload(player.getId(), active);
        for (ServerPlayerEntity other : PlayerLookup.tracking(player)) {
            ServerPlayNetworking.send(other, payload);
        }
        ServerPlayNetworking.send(player, payload);
    }

    private static void tickPlayer(ServerPlayerEntity player, State state) {
        ServerWorld world = (ServerWorld) player.getWorld();
        state.ticks++;

        // refresh berkala supaya pemain yang baru masuk jangkauan tetap melihat laser
        if (state.ticks % 10 == 0) broadcast(player, true);

        LaserRay.Hit hit = LaserRay.trace(world, player.getEyePos(), player.getRotationVec(1.0f), player, RANGE);
        Vec3d p = hit.end();

        // ---------- kena entity / mob / player ----------
        if (hit.entity() != null) {
            state.target = null;
            state.progress = 0;
            Entity target = hit.entity();

            target.setOnFireFor(BURN_SECONDS);
            if (state.ticks % HIT_INTERVAL_TICKS == 0) {
                target.timeUntilRegen = 0; // lewati invincibility frame -> damage terus-menerus
                target.damage(world, world.getDamageSources().playerAttack(player), DAMAGE);
            }
            world.spawnParticles(ParticleTypes.FLAME, p.x, p.y, p.z, 3, 0.15, 0.15, 0.15, 0.01);
            if (state.ticks % 2 == 0) {
                world.spawnParticles(ParticleTypes.SMOKE, p.x, p.y, p.z, 2, 0.15, 0.15, 0.15, 0.01);
            }
            if (state.ticks % 8 == 0) {
                world.playSound(null, p.x, p.y, p.z, FIRE_SOUND, SoundCategory.PLAYERS, 0.6f, 1.0f);
            }
            return;
        }

        // ---------- kena blok ----------
        BlockHitResult block = hit.block();
        if (block == null) {
            state.target = null;
            state.progress = 0;
            return;
        }

        BlockPos pos = block.getBlockPos();
        BlockState bs = world.getBlockState(pos);
        if (bs.isAir() || bs.getBlock() instanceof AbstractFireBlock) {
            return;
        }

        // efek percikan api di titik tembak
        world.spawnParticles(ParticleTypes.FLAME, p.x, p.y, p.z, 4, 0.12, 0.12, 0.12, 0.02);
        if (state.ticks % 2 == 0) {
            world.spawnParticles(ParticleTypes.SMOKE, p.x, p.y, p.z, 2, 0.12, 0.12, 0.12, 0.01);
        }
        if (state.ticks % 8 == 0) {
            world.playSound(null, p.x, p.y, p.z, FIRE_SOUND, SoundCategory.BLOCKS, 0.7f, 1.0f);
        }

        // api muncul di sisi blok yang kena
        if (state.ticks % 4 == 0) {
            BlockPos firePos = pos.offset(block.getSide());
            if (world.getBlockState(firePos).isAir()) {
                BlockState fire = AbstractFireBlock.getState(world, firePos);
                if (fire.canPlaceAt(world, firePos)) {
                    world.setBlockState(firePos, fire);
                }
            }
        }

        if (!BREAK_BLOCKS) return;

        float hardness = bs.getHardness(world, pos);
        if (hardness < 0) return; // bedrock dll. tidak bisa dihancurkan
        if (!player.getAbilities().allowModifyWorld) return;

        if (!pos.equals(state.target)) {
            state.target = pos.toImmutable();
            state.progress = 0;
        }
        state.progress++;

        int needed = Math.min(BREAK_MAX_TICKS, BREAK_BASE_TICKS + Math.round(hardness * BREAK_TICKS_PER_HARDNESS));
        if (state.progress >= needed) {
            double cx = pos.getX() + 0.5, cy = pos.getY() + 0.5, cz = pos.getZ() + 0.5;
            world.breakBlock(pos, true, player);

            // efek ledakan pada blok
            world.spawnParticles(ParticleTypes.EXPLOSION, cx, cy, cz, 1, 0, 0, 0, 0);
            world.spawnParticles(ParticleTypes.FLAME, cx, cy, cz, 30, 0.35, 0.35, 0.35, 0.08);
            world.spawnParticles(ParticleTypes.LAVA, cx, cy, cz, 8, 0.3, 0.3, 0.3, 0.0);
            world.spawnParticles(ParticleTypes.LARGE_SMOKE, cx, cy, cz, 6, 0.3, 0.3, 0.3, 0.02);
            world.playSound(null, cx, cy, cz, EXPLODE_SOUND, SoundCategory.BLOCKS, 0.6f, 1.3f);

            state.target = null;
            state.progress = 0;
        }
    }
}
