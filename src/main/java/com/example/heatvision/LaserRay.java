package com.example.heatvision;

import net.minecraft.entity.Entity;
import net.minecraft.entity.projectile.ProjectileUtil;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;

/** Raycast laser, dipakai bersama oleh server (damage/blok) dan client (visual beam). */
public final class LaserRay {
    private LaserRay() {}

    /** @param block hasil raycast blok (null jika tidak kena blok), @param entity entity yang kena (null jika tidak ada). */
    public record Hit(Vec3d start, Vec3d end, BlockHitResult block, Entity entity) {}

    public static Hit trace(World world, Vec3d start, Vec3d look, Entity shooter, double range) {
        Vec3d farEnd = start.add(look.multiply(range));

        BlockHitResult blockHit = world.raycast(new RaycastContext(
                start, farEnd,
                RaycastContext.ShapeType.OUTLINE,
                RaycastContext.FluidHandling.NONE,
                shooter));
        boolean hitBlock = blockHit.getType() == HitResult.Type.BLOCK;

        Vec3d blockEnd = hitBlock ? blockHit.getPos() : farEnd;
        double maxSq = start.squaredDistanceTo(blockEnd);

        Box box = new Box(start, blockEnd).expand(1.0);
        EntityHitResult entityHit = ProjectileUtil.raycast(
                shooter, start, blockEnd, box,
                e -> !e.isSpectator() && e.canHit(),
                maxSq);

        if (entityHit != null) {
            return new Hit(start, entityHit.getPos(), hitBlock ? blockHit : null, entityHit.getEntity());
        }
        return new Hit(start, blockEnd, hitBlock ? blockHit : null, null);
    }
}
