package ru.voidrp.asyncai.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Prevents main-thread freeze caused by ItemEntity.tick()'s "stuck in block" check
 * calling Level.noCollision(Entity, AABB) → getEntityCollisions → EntitySectionStorage
 * iterating a massive number of entities (huge item piles) in one section.
 *
 * Root cause (Watchdog dump 2026-06-24 10:57, HUNG_TICK):
 *   ItemEntity.tick(ItemEntity.java:177)
 *   → CollisionGetter.noCollision(CollisionGetter.java:57)
 *   → EntityGetter.getEntityCollisions(EntityGetter.java:62)
 *   → Level.getEntities → EntitySectionStorage.forEachAccessibleNonEmptySection
 *   → EntitySection.getEntities [RUNNABLE, CPU spin, 30+ s]
 *
 * This is a DIFFERENT call site than {@link ItemEntityCollisionGuardMixin}, which only
 * redirects getEntityCollisions inside Entity.collide() (the Entity.move() path). The
 * tick() noCollision call (offset 169, javap confirmed:
 *   INVOKEVIRTUAL net/minecraft/world/level/Level.noCollision
 *   (Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;)Z)
 * was left uncovered and is what hung the server here.
 *
 * Vanilla logic:
 *   this.noPhysics = !level.noCollision(this, bb.deflate(1e-7));
 *   if (this.noPhysics) this.moveTowardsClosestSpace(...);
 * Returning true (noCollision == true) sets noPhysics = false and skips the
 * moveTowardsClosestSpace "unstuck from block" logic — a purely cosmetic behaviour for
 * items spawned inside blocks. Skipping it for items is safe and avoids the entity scan.
 */
@Mixin(ItemEntity.class)
public abstract class ItemEntityTickCollisionGuardMixin {

    @Redirect(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;noCollision(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;)Z"
        ),
        require = 0
    )
    private boolean voidrp_skipTickCollisionScanForItems(Level level, Entity entity, AABB aabb) {
        return true;
    }
}
