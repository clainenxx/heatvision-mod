package com.example.heatvision.client;

import com.example.heatvision.HeatVisionMod;
import com.example.heatvision.ModSounds;
import com.example.heatvision.network.LaserBeamPayload;
import com.example.heatvision.network.LaserStatePayload;
import com.example.heatvision.network.StaminaPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.Entity;
import org.lwjgl.glfw.GLFW;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class HeatVisionClient implements ClientModInitializer {
    /** true = laser aktif selama tombol ditahan. false = tekan sekali nyala, tekan lagi mati. */
    public static final boolean HOLD_MODE = true;
    /** Kekuatan filter merah di first person (0..255). */
    public static final int FILTER_ALPHA = 0x78;
    /** Volume suara laser. */
    public static final float LOOP_VOLUME = 0.45f;
    public static final float START_VOLUME = 0.8f;
    /** true = bar tetap tampil saat stamina sedang terisi ulang (setelah laser dilepas) sampai penuh.
     *  false = bar hanya tampil saat laser menyala, atau saat cooldown sampai penuh. */
    public static final boolean SHOW_BAR_WHILE_REGEN = true;

    public static KeyBinding FIRE_KEY;

    /** Status laser pemain lokal. */
    public static boolean localActive = false;
    /** Pemain lain yang sedang menembak: entityId -> world time update terakhir. */
    public static final Map<Integer, Long> REMOTE = new HashMap<>();

    /** Stamina (tick) & cooldown (tick) pemain lokal. Server otoritatif, client memprediksi supaya bar mulus. */
    public static int stamina = HeatVisionMod.MAX_STAMINA_TICKS;
    public static int cooldown = 0;

    private static final Map<Integer, LaserSoundInstance> SOUNDS = new HashMap<>();
    private static final long REMOTE_TIMEOUT_TICKS = 30;

    private static boolean toggled = false;
    /** Setelah stamina habis, tombol harus dilepas dulu sebelum bisa dipakai lagi. */
    private static boolean needRelease = false;
    private static float filter = 0f;

    public static boolean isShooting(int entityId) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null && entityId == mc.player.getId()) return localActive;
        return REMOTE.containsKey(entityId);
    }

    @Override
    public void onInitializeClient() {
        FIRE_KEY = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.heatvision.fire",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_I,
                "category.heatvision"));

        ClientPlayNetworking.registerGlobalReceiver(LaserBeamPayload.ID, (payload, context) -> {
            MinecraftClient mc = context.client();
            if (mc.player == null || mc.world == null || payload.entityId() == mc.player.getId()) return;
            if (payload.active()) {
                REMOTE.put(payload.entityId(), mc.world.getTime());
            } else {
                REMOTE.remove(payload.entityId());
            }
        });

        ClientPlayNetworking.registerGlobalReceiver(StaminaPayload.ID, (payload, context) -> {
            stamina = payload.stamina();
            cooldown = payload.cooldown();
            if (cooldown > 0) needRelease = true;
        });

        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        HudRenderCallback.EVENT.register(this::renderHud);
        WorldRenderEvents.AFTER_ENTITIES.register(BeamRenderer::render);

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            REMOTE.clear();
            SOUNDS.clear();
            localActive = false;
            toggled = false;
            needRelease = false;
            stamina = HeatVisionMod.MAX_STAMINA_TICKS;
            cooldown = 0;
            filter = 0f;
        });
    }

    private void tick(MinecraftClient mc) {
        if (mc.isPaused()) return;

        // ---------- prediksi stamina (sama persis dengan aturan server) ----------
        if (cooldown > 0) {
            cooldown--;
            if (cooldown == 0) stamina = HeatVisionMod.MAX_STAMINA_TICKS;
        } else if (localActive) {
            stamina--;
            if (stamina <= 0) {
                stamina = 0;
                cooldown = HeatVisionMod.COOLDOWN_TICKS;
                needRelease = true;
                toggled = false;
            }
        } else if (stamina < HeatVisionMod.MAX_STAMINA_TICKS) {
            stamina++;
        }

        // ---------- input ----------
        boolean canFire = mc.player != null && mc.player.isAlive() && !mc.player.isSpectator()
                && mc.currentScreen == null;
        boolean hasStamina = cooldown == 0 && stamina > 0;

        boolean want;
        if (!canFire) {
            toggled = false;
            while (FIRE_KEY.wasPressed()) { /* buang input */ }
            want = false;
        } else if (HOLD_MODE) {
            while (FIRE_KEY.wasPressed()) { /* buang input */ }
            want = FIRE_KEY.isPressed() && hasStamina && !needRelease;
        } else {
            while (FIRE_KEY.wasPressed()) toggled = !toggled;
            want = toggled && hasStamina;
            if (toggled && !hasStamina) toggled = false;
        }
        if (!FIRE_KEY.isPressed()) needRelease = false;

        if (want != localActive) {
            localActive = want;
            if (mc.player != null) {
                ClientPlayNetworking.send(new LaserStatePayload(want));
            }
        }

        updateSounds(mc);

        // fade filter merah masuk/keluar
        boolean firstPerson = mc.options.getPerspective().isFirstPerson();
        float target = (localActive && firstPerson) ? 1f : 0f;
        filter += (target - filter) * 0.35f;
        if (Math.abs(target - filter) < 0.01f) filter = target;
    }

    /** Mulai / hentikan suara laser untuk semua pemain yang sedang menembak. */
    private void updateSounds(MinecraftClient mc) {
        if (mc.world == null || mc.player == null) {
            SOUNDS.clear();
            return;
        }
        long now = mc.world.getTime();
        REMOTE.entrySet().removeIf(e -> now - e.getValue() > REMOTE_TIMEOUT_TICKS);

        Set<Integer> shooting = new HashSet<>(REMOTE.keySet());
        if (localActive) shooting.add(mc.player.getId());

        for (int id : shooting) {
            Entity entity = mc.world.getEntityById(id);
            if (entity == null) continue;
            LaserSoundInstance current = SOUNDS.get(id);
            if (current == null || current.isDone()) {
                LaserSoundInstance loop = new LaserSoundInstance(entity, ModSounds.LASER_LOOP, true, LOOP_VOLUME);
                SOUNDS.put(id, loop);
                mc.getSoundManager().play(new LaserSoundInstance(entity, ModSounds.LASER_START, false, START_VOLUME));
                mc.getSoundManager().play(loop);
            }
        }
        SOUNDS.keySet().removeIf(id -> !shooting.contains(id));
    }

    private void renderHud(DrawContext ctx, RenderTickCounter tickCounter) {
        MinecraftClient mc = MinecraftClient.getInstance();
        int w = ctx.getScaledWindowWidth();
        int h = ctx.getScaledWindowHeight();

        if (filter > 0.01f) {
            // lapisan merah menyeluruh (menutup tangan juga) -> semua warna jadi kemerahan
            int a = (int) (FILTER_ALPHA * filter);
            ctx.fill(0, 0, w, h, (a << 24) | 0xFF1000);

            // vignette merah gelap di atas & bawah
            int edge = (int) (0x90 * filter);
            int band = h / 4;
            ctx.fillGradient(0, 0, w, band, (edge << 24) | 0x500000, 0x00500000);
            ctx.fillGradient(0, h - band, w, h, 0x00500000, (edge << 24) | 0x500000);
        }

        renderStaminaBar(ctx, mc, w, h);
    }

    /** Progress bar stamina heat vision, di atas hotbar (di atas baris hati / armor). */
    private void renderStaminaBar(DrawContext ctx, MinecraftClient mc, int w, int h) {
        if (mc.player == null || mc.player.isSpectator() || mc.options.hudHidden) return;

        boolean cooling = cooldown > 0;
        boolean regen = !cooling && !localActive && stamina < HeatVisionMod.MAX_STAMINA_TICKS;
        boolean visible = localActive || cooling || (SHOW_BAR_WHILE_REGEN && regen);
        if (!visible) return;

        float frac = cooling
                ? 1f - (cooldown / (float) HeatVisionMod.COOLDOWN_TICKS)
                : stamina / (float) HeatVisionMod.MAX_STAMINA_TICKS;
        frac = Math.max(0f, Math.min(1f, frac));

        int barW = 100, barH = 5;
        int x = (w - barW) / 2;
        int y = h - 64;

        // bingkai + latar
        ctx.fill(x - 1, y - 1, x + barW + 1, y + barH + 1, 0xFF000000);
        ctx.fill(x, y, x + barW, y + barH, 0xFF2A1410);

        int fw = Math.round(barW * frac);
        if (fw > 0) {
            if (cooling) {
                ctx.fillGradient(x, y, x + fw, y + barH, 0xFF8A8A8A, 0xFF4A4A4A); // abu-abu: sedang cooldown
            } else if (frac < 0.25f) {
                ctx.fillGradient(x, y, x + fw, y + barH, 0xFFFF3030, 0xFF901010); // merah: hampir habis
            } else {
                ctx.fillGradient(x, y, x + fw, y + barH, 0xFFFFC040, 0xFFFF5A10); // oranye panas
            }
        }

        // teks kecil di atas bar
        String label;
        int color;
        if (cooling) {
            label = String.format("Cooldown %.1fs", cooldown / 20f);
            color = 0xFFAAAAAA;
        } else {
            label = String.format("Heat Vision %.1fs", stamina / 20f);
            color = 0xFFFFB060;
        }
        int tw = mc.textRenderer.getWidth(label);
        ctx.drawText(mc.textRenderer, label, (w - tw) / 2, y - 10, color, true);
    }
}
