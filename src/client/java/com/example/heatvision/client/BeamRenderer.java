package com.example.heatvision.client;

import com.example.heatvision.HeatVisionMod;
import com.example.heatvision.LaserRay;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityPose;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Menggambar laser sungguhan (bukan particle): dua beam dari mata kiri & kanan,
 * masing-masing berupa dua bidang bersilang (cross) dengan 3 lapisan: glow merah, oranye, inti putih.
 */
public final class BeamRenderer {
    private static final long REMOTE_TIMEOUT_TICKS = 30;

    // Posisi mata pada model kepala, relatif terhadap titik kamera (eye position) pemain.
    /** Jarak ke depan dari titik kamera sampai permukaan wajah. */
    private static final double EYE_FORWARD = 0.25;
    /** Jarak mata kiri / kanan dari tengah wajah. */
    private static final double EYE_SIDE = 0.12;
    /** Titik putar kepala (leher) berada segini di bawah titik kamera. Kepala berputar mengelilingi titik ini. */
    private static final double NECK_BELOW_EYE = 0.22;
    /** Tinggi mata dari titik putar leher, searah "atas" kepala. Naikkan kalau laser terasa terlalu rendah. */
    private static final double HEAD_EYE_UP = 0.215;

    private BeamRenderer() {}

    public static void render(WorldRenderContext ctx) {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientWorld world = mc.world;
        if (world == null || mc.player == null) return;

        VertexConsumerProvider consumers = ctx.consumers();
        MatrixStack ms = ctx.matrixStack();
        if (consumers == null || ms == null) return;

        List<Entity> shooters = new ArrayList<>();
        if (HeatVisionClient.localActive) shooters.add(mc.player);

        long now = world.getTime();
        Iterator<Map.Entry<Integer, Long>> it = HeatVisionClient.REMOTE.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, Long> e = it.next();
            Entity entity = world.getEntityById(e.getKey());
            if (entity == null || !entity.isAlive() || now - e.getValue() > REMOTE_TIMEOUT_TICKS) {
                it.remove();
                continue;
            }
            shooters.add(entity);
        }
        if (shooters.isEmpty()) return;

        float td = mc.getRenderTickCounter().getTickProgress(false);
        Vec3d cam = ctx.camera().getPos();
        float time = (world.getTime() + td);

        VertexConsumer vc = consumers.getBuffer(RenderLayer.getLightning());
        ms.push();
        ms.translate(-cam.x, -cam.y, -cam.z);
        Matrix4f mat = ms.peek().getPositionMatrix();

        for (Entity shooter : shooters) {
            Vec3d eye = shooter.getCameraPosVec(td);
            Vec3d look = shooter.getRotationVec(td);
            LaserRay.Hit hit = LaserRay.trace(world, eye, look, shooter, HeatVisionMod.RANGE);

            // basis kepala: right/up diturunkan dari arah pandang (ikut pitch, jadi tetap pas di mata
            // saat menunduk / mendongak / berenang / glide)
            Vec3d right = look.crossProduct(new Vec3d(0, 1, 0));
            if (right.lengthSquared() < 1.0e-4) {
                double yaw = Math.toRadians(shooter.getYaw(td));
                right = new Vec3d(-Math.cos(yaw), 0, -Math.sin(yaw));
            }
            right = right.normalize();
            Vec3d up = right.crossProduct(look).normalize();

            // Kepala berputar (pitch) mengelilingi leher, bukan mengelilingi titik kamera. Jadi hitung dulu
            // posisi leher, lalu mata = leher + "atas kepala" + "depan kepala". Dengan begini posisi mata
            // tetap sesuai model saat menunduk / mendongak ekstrem.
            // Pose tegak/jongkok: badan lurus ke atas. Pose lain (berenang, glide): badan mengikuti arah pandang.
            EntityPose pose = shooter.getPose();
            boolean upright = pose == EntityPose.STANDING || pose == EntityPose.CROUCHING;
            Vec3d neck = upright
                    ? eye.subtract(0, NECK_BELOW_EYE, 0)
                    : eye.subtract(up.multiply(NECK_BELOW_EYE));
            Vec3d base = neck.add(up.multiply(HEAD_EYE_UP)).add(look.multiply(EYE_FORWARD));
            Vec3d left = base.subtract(right.multiply(EYE_SIDE));
            Vec3d rightEye = base.add(right.multiply(EYE_SIDE));

            float pulse = 1.0f + 0.18f * MathHelper.sin(time * 1.7f + shooter.getId());
            drawBeam(vc, mat, left, hit.end(), cam, pulse);
            drawBeam(vc, mat, rightEye, hit.end(), cam, pulse);

            // mata menyala (tidak digambar di first person karena menutupi layar)
            boolean firstPersonSelf = shooter == mc.player && mc.options.getPerspective().isFirstPerson();
            if (!firstPersonSelf) {
                Vec3d fwd = look.multiply(0.012); // sedikit di depan wajah supaya tidak z-fighting
                drawEyeGlow(vc, mat, left.add(fwd), cam, pulse);
                drawEyeGlow(vc, mat, rightEye.add(fwd), cam, pulse);
            }
        }

        ms.pop();
    }

    /** Cahaya menyala di mata: dua kotak yang selalu menghadap kamera (halo oranye + inti putih). */
    private static void drawEyeGlow(VertexConsumer vc, Matrix4f mat, Vec3d c, Vec3d cam, float pulse) {
        Vec3d toCam = cam.subtract(c);
        if (toCam.lengthSquared() < 1.0e-6) return;
        toCam = toCam.normalize();
        Vec3d r = toCam.crossProduct(new Vec3d(0, 1, 0));
        if (r.lengthSquared() < 1.0e-6) r = new Vec3d(1, 0, 0);
        r = r.normalize();
        Vec3d u = r.crossProduct(toCam).normalize();

        float[][] glow = {
                {0.085f, 1.00f, 0.20f, 0.05f, 0.35f}, // halo merah
                {0.050f, 1.00f, 0.55f, 0.15f, 0.70f}, // oranye
                {0.026f, 1.00f, 0.97f, 0.85f, 1.00f}, // inti putih
        };
        for (int i = 0; i < glow.length; i++) {
            float sz = glow[i][0] * pulse;
            Vec3d o = c.add(toCam.multiply(0.004 * (i + 1)));
            Vec3d rs = r.multiply(sz), us = u.multiply(sz);
            Vec3d p1 = o.subtract(rs).subtract(us), p2 = o.add(rs).subtract(us);
            Vec3d p3 = o.add(rs).add(us), p4 = o.subtract(rs).add(us);
            v(vc, mat, p1, glow[i]); v(vc, mat, p2, glow[i]); v(vc, mat, p3, glow[i]); v(vc, mat, p4, glow[i]);
            v(vc, mat, p4, glow[i]); v(vc, mat, p3, glow[i]); v(vc, mat, p2, glow[i]); v(vc, mat, p1, glow[i]);
        }
    }

    private static void drawBeam(VertexConsumer vc, Matrix4f mat, Vec3d s, Vec3d e, Vec3d cam, float pulse) {
        Vec3d dir = e.subtract(s);
        if (dir.lengthSquared() < 1.0e-4) return;
        dir = dir.normalize();

        Vec3d ref = Math.abs(dir.y) > 0.99 ? new Vec3d(1, 0, 0) : new Vec3d(0, 1, 0);
        Vec3d p1 = dir.crossProduct(ref).normalize();
        Vec3d p2 = dir.crossProduct(p1).normalize();

        Vec3d mid = s.add(e).multiply(0.5);
        Vec3d toCam = cam.subtract(mid);
        Vec3d towardCam = toCam.lengthSquared() > 1.0e-6 ? toCam.normalize() : Vec3d.ZERO;

        // {setengah lebar, r, g, b, a}
        float[][] layers = {
                {0.060f, 1.00f, 0.10f, 0.05f, 0.28f}, // glow merah
                {0.034f, 1.00f, 0.45f, 0.10f, 0.55f}, // oranye
                {0.014f, 1.00f, 0.95f, 0.80f, 1.00f}, // inti putih panas
        };
        for (int i = 0; i < layers.length; i++) {
            float w = layers[i][0] * pulse;
            Vec3d off = towardCam.multiply(0.003 * i);
            Vec3d a = s.add(off), b = e.add(off);
            quad(vc, mat, a, b, p1.multiply(w), layers[i]);
            quad(vc, mat, a, b, p2.multiply(w), layers[i]);
        }
    }

    /** Satu bidang (ditulis dua arah supaya terlihat dari kedua sisi). */
    private static void quad(VertexConsumer vc, Matrix4f mat, Vec3d a, Vec3d b, Vec3d side, float[] c) {
        Vec3d a1 = a.add(side), a2 = a.subtract(side);
        Vec3d b1 = b.add(side), b2 = b.subtract(side);
        v(vc, mat, a1, c); v(vc, mat, a2, c); v(vc, mat, b2, c); v(vc, mat, b1, c);
        v(vc, mat, b1, c); v(vc, mat, b2, c); v(vc, mat, a2, c); v(vc, mat, a1, c);
    }

    private static void v(VertexConsumer vc, Matrix4f mat, Vec3d p, float[] c) {
        vc.vertex(mat, (float) p.x, (float) p.y, (float) p.z).color(c[1], c[2], c[3], c[4]);
    }
}
