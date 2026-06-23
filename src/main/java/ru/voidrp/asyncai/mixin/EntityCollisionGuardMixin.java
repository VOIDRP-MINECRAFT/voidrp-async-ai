package ru.voidrp.asyncai.mixin;

import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import ru.voidrp.asyncai.VoidRpAsyncAI;

/**
 * Prevents server freezes caused by entities with abnormally large bounding boxes
 * (e.g. Aether AbstractWhirlwind) iterating millions of block collision shapes.
 *
 * Redirects the ImmutableList.Builder.addAll(Iterable) call inside
 * Entity.collectColliders() to a guarded version that stops iteration after
 * MAX_SHAPES shapes.  Abandoning a BlockCollisions iterator mid-way is safe —
 * it is a short-lived, disposable object and iteration stops naturally when we
 * stop calling hasNext().
 *
 * Worst-case side-effect: the entity may pass through blocks beyond the cap, but
 * that is vastly preferable to a 2-minute server freeze.
 */
@Mixin(Entity.class)
public abstract class EntityCollisionGuardMixin {

    @Unique
    private static final int MAX_SHAPES = 4096;

    @SuppressWarnings("unchecked")
    @Redirect(
        method = "collectColliders",
        at = @At(
            value  = "INVOKE",
            target = "Lcom/google/common/collect/ImmutableList$Builder;addAll(Ljava/lang/Iterable;)Lcom/google/common/collect/ImmutableCollection$Builder;"
        ),
        require = 0
    )
    private ImmutableCollection.Builder<?> voidrp_limitBlockCollisions(
            ImmutableList.Builder<Object> builder,
            Iterable<Object> shapes) {

        int count = 0;
        for (Object shape : shapes) {
            if (count >= MAX_SHAPES) {
                VoidRpAsyncAI.LOGGER.warn(
                    "[VoidRP Async AI] Entity collision shape limit ({}) reached — " +
                    "aborting BlockCollisions iteration to prevent freeze",
                    MAX_SHAPES);
                break;
            }
            builder.add(shape);
            count++;
        }
        return builder;
    }
}
