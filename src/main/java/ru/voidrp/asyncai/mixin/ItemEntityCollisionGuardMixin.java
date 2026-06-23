package ru.voidrp.asyncai.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.List;

/**
 * Prevents main-thread freeze caused by ItemEntity.tick() → Entity.move() → Entity.collide()
 * → Level.getEntityCollisions() iterating EntitySectionStorage with a massive number of entities.
 *
 * Root cause (Watchdog dump 2026-06-23 15:26):
 *   ItemEntity.tick → Entity.move → Entity.collide
 *   → Level.getEntityCollisions → EntitySectionStorage.forEachAccessibleNonEmptySection
 *   → EntitySection.getEntities → EntitySelector.lambda [RUNNABLE, CPU spin, 10+ s]
 *   EntitySectionStorage iterates all entity sections in AABB and applies a predicate to each
 *   entity — when thousands of items are stacked in one spot, this stalls the tick loop.
 *
 * Bytecode evidence: Entity.collide() contains
 *   INVOKEVIRTUAL net/minecraft/world/level/Level.getEntityCollisions
 *   (Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;)Ljava/util/List;
 * confirmed via javap on rename_*.jar (constant pool entry #1473).
 *
 * Fix: redirect Level.getEntityCollisions() inside Entity.collide() to return List.of()
 * when the calling entity is an ItemEntity. Items do not interact with other entities via
 * bounding-box collision shapes during movement — pickup is handled by AABB proximity in
 * playerTouch(), and item merging uses isMergable() + AABB proximity, neither of which
 * uses VoxelShape entity collision. Skipping entity collision shapes for items is safe.
 *
 * Note: the previous version of this mixin targeted Level.noCollision() in ItemEntity.tick()
 * (the noPhysics flag path) and did NOT cover the Entity.move() → collide() path.
 */
@Mixin(Entity.class)
public abstract class ItemEntityCollisionGuardMixin {

    @Redirect(
        method = "collide",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;getEntityCollisions(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;)Ljava/util/List;"
        ),
        require = 0
    )
    private List<VoxelShape> voidrp_skipEntityCollisionsForItems(
            Level level, Entity entity, AABB aabb) {
        if ((Object) this instanceof ItemEntity) {
            return List.of();
        }
        return level.getEntityCollisions(entity, aabb);
    }
}
