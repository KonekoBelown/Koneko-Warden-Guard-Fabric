package com.koneko.wardenguard.mixin;

import com.koneko.wardenguard.WardenGuard;
import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.WardenEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(WardenEntity.class)
public abstract class WardenEntityMixin {
    @Inject(method = "isValidTarget", at = @At("HEAD"), cancellable = true)
    private void koneko_warden_guard$filterTargets(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        WardenEntity self = (WardenEntity) (Object) this;
        if (WardenGuard.shouldRejectWardenTarget(self, entity)) {
            cir.setReturnValue(false);
        }
    }
}
