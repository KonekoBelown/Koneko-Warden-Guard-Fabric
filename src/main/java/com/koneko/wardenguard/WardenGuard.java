package com.koneko.wardenguard;

import com.koneko.wardenguard.block.WardenIdolBlock;
import com.mojang.brigadier.arguments.BoolArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.Tameable;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.WardenEntity;
import net.minecraft.entity.passive.AbstractHorseEntity;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.ProjectileUtil;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registry;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class WardenGuard implements ModInitializer {
    public static final String MOD_ID = "koneko_warden_guard";

    public static final Identifier WARDEN_IDOL_ID = Identifier.of(MOD_ID, "warden_idol");
    public static final RegistryKey<Block> WARDEN_IDOL_BLOCK_KEY = RegistryKey.of(RegistryKeys.BLOCK, WARDEN_IDOL_ID);
    public static final RegistryKey<Item> WARDEN_IDOL_ITEM_KEY = RegistryKey.of(RegistryKeys.ITEM, WARDEN_IDOL_ID);
    public static final Block WARDEN_IDOL = new WardenIdolBlock(AbstractBlock.Settings.create()
            .registryKey(WARDEN_IDOL_BLOCK_KEY)
            .strength(3.5F, 9.0F)
            .sounds(BlockSoundGroup.SCULK)
            .ticksRandomly());
    public static final Item WARDEN_IDOL_ITEM = new BlockItem(WARDEN_IDOL, new Item.Settings().registryKey(WARDEN_IDOL_ITEM_KEY));

    public static final String TAG_PLAYER_TAMED = "kwg_has_warden";
    public static final String TAG_PLAYER_AUTO_SUMMON = "kwg_auto_summon";
    public static final String TAG_TRUST_EXPIRES_PREFIX = "kwg_trust_expires_";
    public static final String TAG_GUARD_WARDEN = "kwg_guard_warden";
    public static final String TAG_OWNER_PREFIX = "kwg_owner_";

    // Shared command tags for cross-mod coordination. Koneko March or other Koneko guard mods can
    // treat these as protected friendly summons and skip attack assignment.
    public static final String TAG_KONEKO_FRIENDLY_ENTITY = "koneko_friendly_entity";
    public static final String TAG_KONEKO_NO_FRIENDLY_ATTACK = "koneko_no_friendly_attack";
    public static final String TAG_KONEKO_INVULNERABLE_GUARD = "koneko_invulnerable_guard";
    public static final String TAG_KONEKO_WARDEN_GUARD_ENTITY = "koneko_warden_guard_entity";

    // Existing Koneko March command tags. This mod treats them as friendly even when the March mod
    // itself is not present as a compile-time dependency.
    public static final String TAG_MARCH_ENTITY = "konekomarch_march_entity";
    public static final String TAG_MARCH_AI = "konekomarch_march_ai";
    public static final String TAG_MARCH_MOUNT = "konekomarch_march_mount";

    // Vanilla copper oxidation is probabilistic. This fixed trust duration uses roughly 72 in-game days
    // as a stable approximation for a full copper-block oxidation window.
    public static final long TRUST_DURATION_TICKS = 72L * 24000L;

    private static final double SUMMON_DISTANCE_BEHIND = 0.85D;
    private static final double IDLE_HEIGHT = 0.35D;
    private static final double FOLLOW_TELEPORT_DISTANCE_SQ = 30.0D * 30.0D;
    private static final double TARGET_SCAN_RANGE = 24.0D;
    private static final double TARGET_FORGET_DISTANCE_SQ = 72.0D * 72.0D;
    private static final double LOCAL_GUARD_RECOVERY_RANGE = 160.0D;
    private static final double SONIC_RANGE = 32.0D;
    private static final int MELEE_COOLDOWN_TICKS = 18;
    private static final int SONIC_COOLDOWN_TICKS = 80;
    private static final int AUTO_SUMMON_COOLDOWN_TICKS = 60;
    private static final int PASSIVE_WARDEN_SCAN_INTERVAL_TICKS = 20;
    private static final int AUTO_TARGET_SCAN_INTERVAL_TICKS = 10;

    private static final Map<UUID, UUID> ACTIVE_GUARDS = new HashMap<>();
    private static final Map<UUID, UUID> OWNER_TARGETS = new HashMap<>();
    private static final Map<UUID, Integer> GUARD_MELEE_COOLDOWNS = new HashMap<>();
    private static final Map<UUID, Integer> PLAYER_SONIC_COOLDOWNS = new HashMap<>();
    private static final Map<UUID, Integer> PLAYER_AUTO_SUMMON_COOLDOWNS = new HashMap<>();
    private static final Map<UUID, Set<UUID>> WILD_WARDEN_RETALIATION_PLAYERS = new HashMap<>();

    @Override
    public void onInitialize() {
        registerContent();
        registerCommands();
        registerEvents();

        ServerTickEvents.END_WORLD_TICK.register(WardenGuard::tickWorld);
        ServerTickEvents.END_SERVER_TICK.register(WardenGuard::tickCooldowns);
    }

    private static ServerWorld serverWorld(ServerPlayerEntity player) {
        return (ServerWorld) player.getEntityWorld();
    }

    private static Vec3d pos(Entity entity) {
        return new Vec3d(entity.getX(), entity.getY(), entity.getZ());
    }


    private static void registerContent() {
        Registry.register(Registries.BLOCK, WARDEN_IDOL_ID, WARDEN_IDOL);
        Registry.register(Registries.ITEM, WARDEN_IDOL_ID, WARDEN_IDOL_ITEM);
    }

    private static void registerCommands() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
                CommandManager.literal("wardenguard")
                        .then(CommandManager.literal("toggle")
                                .requires(source -> source.getEntity() instanceof ServerPlayerEntity)
                                .executes(context -> {
                                    ServerPlayerEntity player = (ServerPlayerEntity) context.getSource().getEntity();
                                    if (player == null) {
                                        return 0;
                                    }
                                    return toggleSummon(player) ? 1 : 0;
                                }))
                        .then(CommandManager.literal("summon")
                                .requires(source -> source.getEntity() instanceof ServerPlayerEntity)
                                .executes(context -> {
                                    ServerPlayerEntity player = (ServerPlayerEntity) context.getSource().getEntity();
                                    return player != null && summonGuard(player, true) ? 1 : 0;
                                }))
                        .then(CommandManager.literal("recall")
                                .requires(source -> source.getEntity() instanceof ServerPlayerEntity)
                                .executes(context -> {
                                    ServerPlayerEntity player = (ServerPlayerEntity) context.getSource().getEntity();
                                    return player != null && recallGuard(player, true) ? 1 : 0;
                                }))
                        .then(CommandManager.literal("sonic")
                                .requires(source -> source.getEntity() instanceof ServerPlayerEntity)
                                .executes(context -> {
                                    ServerPlayerEntity player = (ServerPlayerEntity) context.getSource().getEntity();
                                    return player != null && fireCommandedSonicBoom(player) ? 1 : 0;
                                }))
                        .then(CommandManager.literal("status")
                                .requires(source -> source.getEntity() instanceof ServerPlayerEntity)
                                .executes(context -> {
                                    ServerPlayerEntity player = (ServerPlayerEntity) context.getSource().getEntity();
                                    if (player != null) {
                                        boolean trusted = hasTrustedGuard(player);
                                        boolean summoned = findOwnedGuard(player).isPresent();
                                        boolean auto = autoSummonEnabled(player);
                                        long days = Math.max(0L, trustRemainingTicks(player) / 24000L);
                                        player.sendMessage(Text.translatable("text.koneko_warden_guard.status", trusted, summoned, auto, days), false);
                                    }
                                    return 1;
                                }))
                        .then(CommandManager.literal("config")
                                .then(CommandManager.literal("auto_summon")
                                        .requires(source -> source.getEntity() instanceof ServerPlayerEntity)
                                        .then(CommandManager.argument("enabled", BoolArgumentType.bool())
                                                .executes(context -> {
                                                    ServerPlayerEntity player = (ServerPlayerEntity) context.getSource().getEntity();
                                                    if (player == null) {
                                                        return 0;
                                                    }
                                                    boolean enabled = BoolArgumentType.getBool(context, "enabled");
                                                    setAutoSummon(player, enabled);
                                                    player.sendMessage(Text.translatable(enabled
                                                            ? "text.koneko_warden_guard.auto_summon.enabled"
                                                            : "text.koneko_warden_guard.auto_summon.disabled"), false);
                                                    return 1;
                                                }))))
        ));
    }

    private static void registerEvents() {
        UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (!(world instanceof ServerWorld serverWorld) || !(player instanceof ServerPlayerEntity serverPlayer)) {
                return ActionResult.PASS;
            }
            if (hand != Hand.MAIN_HAND || !(entity instanceof WardenEntity warden)) {
                return ActionResult.PASS;
            }
            ItemStack stack = serverPlayer.getMainHandStack();
            if (!stack.isOf(Items.SCULK)) {
                return ActionResult.PASS;
            }
            return gainWardenTrust(serverWorld, serverPlayer, warden, stack);
        });

        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (!(world instanceof ServerWorld serverWorld) || !(player instanceof ServerPlayerEntity serverPlayer)) {
                return ActionResult.PASS;
            }
            if (hand != Hand.MAIN_HAND) {
                return ActionResult.PASS;
            }
            BlockHitResult blockHit = hitResult;
            BlockState state = serverWorld.getBlockState(blockHit.getBlockPos());
            if (!state.isOf(WARDEN_IDOL)) {
                return ActionResult.PASS;
            }
            ItemStack stack = serverPlayer.getMainHandStack();
            if (!stack.isOf(Items.SCULK)) {
                return ActionResult.PASS;
            }

            return refreshTrustFromIdol(serverWorld, serverPlayer, blockHit.getBlockPos(), state, stack);
        });

        AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (!(world instanceof ServerWorld) || !(player instanceof ServerPlayerEntity serverPlayer)) {
                return ActionResult.PASS;
            }
            if (hand != Hand.MAIN_HAND) {
                return ActionResult.PASS;
            }

            // Koneko guard entities are protected summons. Player attack clicks should not assign
            // them as targets or deal damage.
            if (isKonekoFriendlyOrProtected(entity)) {
                return ActionResult.FAIL;
            }

            if (!(entity instanceof LivingEntity target)) {
                return ActionResult.PASS;
            }

            if (target instanceof WardenEntity warden && !isGuardWarden(warden)) {
                markWildWardenRetaliation(warden, serverPlayer);
            }

            if (hasTrustedGuard(serverPlayer) && isAllowedGuardTarget(serverPlayer, target)) {
                assignOwnerTarget(serverPlayer, target);
                findOwnedGuard(serverPlayer).ifPresent(guard -> updateWardenCombatTarget(guard, target));
            }
            return ActionResult.PASS;
        });

        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            if (!(entity.getEntityWorld() instanceof ServerWorld world)) {
                return true;
            }
            Entity attacker = effectiveAttacker(source);

            // Tamed wardens are stand-like protected guards. Any entity damage against them is
            // rejected, but hostile attackers are still remembered as retaliation targets.
            if (isGuardWarden(entity)) {
                WardenEntity guard = (WardenEntity) entity;
                UUID ownerUuid = getOwnerUuid(guard).orElse(null);
                if (ownerUuid != null && attacker instanceof LivingEntity living && isAllowedGuardTarget(ownerUuid, living)) {
                    assignOwnerTarget(ownerUuid, living);
                    updateWardenCombatTarget(guard, living);
                }
                return false;
            }

            if (isGuardWarden(attacker) && entity instanceof LivingEntity target) {
                WardenEntity guard = (WardenEntity) attacker;
                UUID ownerUuid = getOwnerUuid(guard).orElse(null);
                if (ownerUuid == null || !isAllowedGuardTarget(ownerUuid, target)) {
                    return false;
                }
            }

            if (entity instanceof PlayerEntity player && attacker instanceof WardenEntity attackingWarden) {
                if (isGuardWarden(attackingWarden)) {
                    return false;
                }
                if (hasTrustedGuard(player) && !canWildWardenRetaliate(attackingWarden, player)) {
                    calmWardenToward(attackingWarden, player);
                    return false;
                }
            }


            if (entity instanceof ServerPlayerEntity player && attacker instanceof LivingEntity livingAttacker) {
                if (hasTrustedGuard(player) && isAllowedGuardTarget(player, livingAttacker)) {
                    if (autoSummonEnabled(player)) {
                        tryAutoSummon(player);
                    }
                    assignOwnerTarget(player, livingAttacker);
                    findOwnedGuard(player).ifPresent(guard -> updateWardenCombatTarget(guard, livingAttacker));
                }
            }

            if (entity instanceof WardenEntity wildWarden && !isGuardWarden(wildWarden)
                    && attacker instanceof WardenEntity guard && isGuardWarden(guard)) {
                updateWardenCombatTarget(wildWarden, guard);
            }

            return true;
        });
    }

    private static ActionResult gainWardenTrust(ServerWorld world, ServerPlayerEntity player, WardenEntity warden, ItemStack stack) {
        if (isGuardWarden(warden)) {
            player.sendMessage(Text.translatable("text.koneko_warden_guard.already_guard"), true);
            return ActionResult.FAIL;
        }
        if (hasTrustedGuard(player)) {
            player.sendMessage(Text.translatable("text.koneko_warden_guard.trust_already"), true);
            return ActionResult.FAIL;
        }

        grantTrust(player, world.getTime() + TRUST_DURATION_TICKS);
        calmWardenToward(warden, player);

        if (!player.isCreative()) {
            stack.decrement(1);
        }

        world.spawnParticles(ParticleTypes.HEART, warden.getX(), warden.getY() + 2.0D, warden.getZ(), 20, 0.7D, 0.8D, 0.7D, 0.03D);
        world.playSound(null, warden.getBlockPos(), SoundEvents.ENTITY_WARDEN_HEARTBEAT, SoundCategory.HOSTILE, 1.0F, 1.3F);
        player.sendMessage(Text.translatable("text.koneko_warden_guard.tamed"), false);
        return ActionResult.SUCCESS;
    }

    private static ActionResult refreshTrustFromIdol(ServerWorld world, ServerPlayerEntity player, net.minecraft.util.math.BlockPos pos, BlockState state, ItemStack stack) {
        if (!hasTrustedGuard(player)) {
            if (hasAnyTrustMarker(player)) {
                revokeTrust(player, true);
            } else {
                player.sendMessage(Text.translatable("text.koneko_warden_guard.need_trust_first"), true);
            }
            world.setBlockState(pos, state.with(WardenIdolBlock.RUST, 3), Block.NOTIFY_ALL);
            return ActionResult.FAIL;
        }

        grantTrust(player, world.getTime() + TRUST_DURATION_TICKS);
        world.setBlockState(pos, state.with(WardenIdolBlock.RUST, 0), Block.NOTIFY_ALL);
        if (!player.isCreative()) {
            stack.decrement(1);
        }
        world.spawnParticles(ParticleTypes.SCULK_SOUL, pos.getX() + 0.5D, pos.getY() + 1.05D, pos.getZ() + 0.5D, 18, 0.35D, 0.45D, 0.35D, 0.02D);
        world.playSound(null, pos, SoundEvents.BLOCK_SCULK_CATALYST_BLOOM, SoundCategory.BLOCKS, 0.8F, 1.1F);
        player.sendMessage(Text.translatable("text.koneko_warden_guard.idol_refreshed", Math.max(0L, TRUST_DURATION_TICKS / 24000L)), true);
        return ActionResult.SUCCESS;
    }

    private static boolean toggleSummon(ServerPlayerEntity player) {
        Optional<WardenEntity> existing = findOwnedGuard(player);
        if (existing.isPresent()) {
            return recallGuard(player, true);
        }
        return summonGuard(player, true);
    }

    private static boolean summonGuard(ServerPlayerEntity player, boolean feedback) {
        if (!hasTrustedGuard(player)) {
            if (feedback) {
                player.sendMessage(Text.translatable("text.koneko_warden_guard.no_tamed"), false);
            }
            return false;
        }
        if (findOwnedGuard(player).isPresent()) {
            if (feedback) {
                player.sendMessage(Text.translatable("text.koneko_warden_guard.already_summoned"), false);
            }
            return true;
        }

        ServerWorld world = serverWorld(player);
        WardenEntity warden = EntityType.WARDEN.create(world, SpawnReason.COMMAND);
        if (warden == null) {
            return false;
        }

        Vec3d pos = idleAnchor(player);
        warden.refreshPositionAndAngles(pos.x, pos.y, pos.z, player.getYaw(), 0.0F);
        markAsGuard(warden, player.getUuid());
        warden.setPersistent();
        warden.setNoGravity(true);
        warden.setCustomName(Text.translatable("entity.koneko_warden_guard.warden_guard"));
        warden.setCustomNameVisible(false);
        world.spawnEntity(warden);
        ACTIVE_GUARDS.put(player.getUuid(), warden.getUuid());
        world.spawnParticles(ParticleTypes.SCULK_SOUL, pos.x, pos.y + 1.0D, pos.z, 24, 0.7D, 0.7D, 0.7D, 0.02D);
        world.playSound(null, warden.getBlockPos(), SoundEvents.ENTITY_WARDEN_EMERGE, SoundCategory.HOSTILE, 0.8F, 1.2F);

        if (feedback) {
            player.sendMessage(Text.translatable("text.koneko_warden_guard.summoned"), false);
        }
        return true;
    }

    private static boolean recallGuard(ServerPlayerEntity player, boolean feedback) {
        int removed = 0;
        UUID ownerUuid = player.getUuid();

        Optional<WardenEntity> cachedGuard = findCachedGuard(player);
        if (cachedGuard.isPresent()) {
            discardGuard((ServerWorld) cachedGuard.get().getEntityWorld(), cachedGuard.get());
            removed++;
        } else {
            // Fallback only scans the owner's nearby loaded area. The previous full-world scan was
            // expensive enough to tank TPS on large saves after repeated summon/recall cycles.
            ServerWorld world = serverWorld(player);
            double r = LOCAL_GUARD_RECOVERY_RANGE;
            Box box = new Box(player.getX() - r, player.getY() - r, player.getZ() - r,
                    player.getX() + r, player.getY() + r, player.getZ() + r);
            List<WardenEntity> guards = world.getEntitiesByClass(WardenEntity.class, box,
                    warden -> isGuardWarden(warden) && ownerUuid.equals(getOwnerUuid(warden).orElse(null)));
            for (WardenEntity guard : guards) {
                discardGuard(world, guard);
                removed++;
            }
        }

        ACTIVE_GUARDS.remove(ownerUuid);
        OWNER_TARGETS.remove(ownerUuid);
        if (feedback) {
            player.sendMessage(Text.translatable(removed > 0 ? "text.koneko_warden_guard.recalled" : "text.koneko_warden_guard.no_guard"), false);
        }
        return removed > 0;
    }

    private static void discardGuard(ServerWorld world, WardenEntity guard) {
        world.spawnParticles(ParticleTypes.SCULK_SOUL, guard.getX(), guard.getY() + 1.0D, guard.getZ(), 18, 0.6D, 0.6D, 0.6D, 0.02D);
        GUARD_MELEE_COOLDOWNS.remove(guard.getUuid());
        guard.discard();
    }

    private static boolean fireCommandedSonicBoom(ServerPlayerEntity player) {
        if (!hasTrustedGuard(player)) {
            player.sendMessage(Text.translatable("text.koneko_warden_guard.no_tamed"), false);
            return false;
        }
        int cooldown = PLAYER_SONIC_COOLDOWNS.getOrDefault(player.getUuid(), 0);
        if (cooldown > 0) {
            player.sendMessage(Text.translatable("text.koneko_warden_guard.sonic_cooldown", cooldown / 20 + 1), true);
            return false;
        }

        Optional<WardenEntity> guardOpt = findOwnedGuard(player);
        WardenEntity guard;
        if (guardOpt.isEmpty()) {
            if (!summonGuard(player, false)) {
                return false;
            }
            guardOpt = findOwnedGuard(player);
            if (guardOpt.isEmpty()) {
                return false;
            }
        }
        guard = guardOpt.get();

        LivingEntity target = findCrosshairTarget(player);
        if (target == null || !isAllowedGuardTarget(player, target)) {
            player.sendMessage(Text.translatable("text.koneko_warden_guard.no_crosshair_target"), true);
            return false;
        }

        assignOwnerTarget(player, target);
        updateWardenCombatTarget(guard, target);
        performSonicBoom(serverWorld(player), guard, target);
        PLAYER_SONIC_COOLDOWNS.put(player.getUuid(), SONIC_COOLDOWN_TICKS);
        return true;
    }

    private static void performSonicBoom(ServerWorld world, WardenEntity guard, LivingEntity target) {
        Vec3d from = guard.getEyePos();
        Vec3d to = target.getEyePos();
        Vec3d delta = to.subtract(from);
        int steps = Math.max(1, (int) (delta.length() * 2.0D));
        Vec3d step = delta.multiply(1.0D / steps);
        for (int i = 1; i <= steps; i++) {
            Vec3d point = from.add(step.multiply(i));
            world.spawnParticles(ParticleTypes.SONIC_BOOM, point.x, point.y, point.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
        }
        world.playSound(null, guard.getBlockPos(), SoundEvents.ENTITY_WARDEN_SONIC_BOOM, SoundCategory.HOSTILE, 2.5F, 1.0F);
        target.damage(world, world.getDamageSources().sonicBoom(guard), 10.0F);
        Vec3d knockback = delta.normalize().multiply(1.8D).add(0.0D, 0.35D, 0.0D);
        target.addVelocity(knockback.x, knockback.y, knockback.z);
    }

    private static LivingEntity findCrosshairTarget(ServerPlayerEntity player) {
        Vec3d start = player.getCameraPosVec(1.0F);
        Vec3d direction = player.getRotationVec(1.0F);
        Vec3d end = start.add(direction.multiply(SONIC_RANGE));
        Box box = player.getBoundingBox().stretch(direction.multiply(SONIC_RANGE)).expand(1.0D);
        EntityHitResult hit = ProjectileUtil.raycast(player, start, end, box,
                entity -> entity instanceof LivingEntity living
                        && living.isAlive()
                        && entity != player
                        && isAllowedGuardTarget(player, living),
                SONIC_RANGE * SONIC_RANGE);
        if (hit == null || !(hit.getEntity() instanceof LivingEntity living)) {
            return null;
        }
        return living;
    }

    private static void tickWorld(ServerWorld world) {
        List<ServerPlayerEntity> players = world.getPlayers();
        for (ServerPlayerEntity player : players) {
            if (hasAnyTrustMarker(player) && !hasTrustedGuard(player)) {
                revokeTrust(player, true);
                continue;
            }
            if (hasTrustedGuard(player)) {
                if (shouldPeriodicScan(world, player, PASSIVE_WARDEN_SCAN_INTERVAL_TICKS)) {
                    keepWildWardensPassiveNearPlayer(world, player);
                }
                findOwnedGuard(player).ifPresent(guard -> tickGuard(world, player, guard));
            }
        }
    }

    private static void tickGuard(ServerWorld world, ServerPlayerEntity owner, WardenEntity guard) {
        guard.addCommandTag(TAG_GUARD_WARDEN);
        guard.addCommandTag(TAG_KONEKO_FRIENDLY_ENTITY);
        guard.addCommandTag(TAG_KONEKO_NO_FRIENDLY_ATTACK);
        guard.addCommandTag(TAG_KONEKO_INVULNERABLE_GUARD);
        guard.addCommandTag(TAG_KONEKO_WARDEN_GUARD_ENTITY);
        guard.setPersistent();
        guard.setNoGravity(true);
        guard.setInvulnerable(true);
        guard.fallDistance = 0.0F;
        guard.getNavigation().stop();
        calmWardenToward(guard, owner);

        LivingEntity target = getCurrentTarget(world, owner, guard);
        if (target == null && shouldPeriodicScan(world, owner, AUTO_TARGET_SCAN_INTERVAL_TICKS)) {
            target = findAutoHostileTarget(world, owner, guard);
            if (target != null) {
                assignOwnerTarget(owner, target);
            }
        }

        if (target != null) {
            tickFlyingCombat(world, guard, target);
        } else {
            tickFlyingFollow(owner, guard);
        }
    }

    private static boolean shouldPeriodicScan(ServerWorld world, ServerPlayerEntity player, int intervalTicks) {
        long salt = player.getUuid().getLeastSignificantBits() & 0x7FFFFFFFL;
        return Math.floorMod(world.getTime() + salt, intervalTicks) == 0;
    }

    private static LivingEntity getCurrentTarget(ServerWorld world, ServerPlayerEntity owner, WardenEntity guard) {
        UUID targetUuid = OWNER_TARGETS.get(owner.getUuid());
        if (targetUuid == null) {
            return null;
        }
        Entity entity = world.getEntity(targetUuid);
        if (!(entity instanceof LivingEntity living) || !living.isAlive()) {
            OWNER_TARGETS.remove(owner.getUuid());
            return null;
        }
        if (!isAllowedGuardTarget(owner, living) || guard.squaredDistanceTo(living) > TARGET_FORGET_DISTANCE_SQ) {
            OWNER_TARGETS.remove(owner.getUuid());
            return null;
        }
        return living;
    }

    private static LivingEntity findAutoHostileTarget(ServerWorld world, ServerPlayerEntity owner, WardenEntity guard) {
        double r = TARGET_SCAN_RANGE;
        Box box = new Box(owner.getX() - r, owner.getY() - r, owner.getZ() - r,
                owner.getX() + r, owner.getY() + r, owner.getZ() + r);
        List<LivingEntity> candidates = world.getEntitiesByClass(LivingEntity.class, box,
                entity -> entity.isAlive()
                        && isAutoHostileTarget(owner, entity)
                        && guard.canSee(entity));
        LivingEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (LivingEntity candidate : candidates) {
            double d = guard.squaredDistanceTo(candidate);
            if (d < bestDistance) {
                best = candidate;
                bestDistance = d;
            }
        }
        return best;
    }

    private static boolean isAutoHostileTarget(ServerPlayerEntity owner, LivingEntity entity) {
        if (!isAllowedGuardTarget(owner, entity)) {
            return false;
        }
        return entity instanceof HostileEntity || (entity instanceof WardenEntity && !isGuardWarden(entity));
    }

    private static void tickFlyingCombat(ServerWorld world, WardenEntity guard, LivingEntity target) {
        updateWardenCombatTarget(guard, target);
        guard.getLookControl().lookAt(target, 30.0F, 30.0F);

        Vec3d targetPos = target.getEyePos();
        Vec3d delta = targetPos.subtract(pos(guard).add(0.0D, guard.getHeight() * 0.55D, 0.0D));
        double distanceSq = delta.lengthSquared();
        if (distanceSq > 4.2D) {
            Vec3d velocity = delta.normalize().multiply(Math.min(1.35D, 0.42D + Math.sqrt(distanceSq) * 0.08D));
            guard.setVelocity(velocity);
        } else {
            guard.setVelocity(guard.getVelocity().multiply(0.25D));
            int cooldown = GUARD_MELEE_COOLDOWNS.getOrDefault(guard.getUuid(), 0);
            if (cooldown <= 0 && guard.canSee(target)) {
                guard.tryAttack(world, target);
                guard.swingHand(Hand.MAIN_HAND);
                GUARD_MELEE_COOLDOWNS.put(guard.getUuid(), MELEE_COOLDOWN_TICKS);
            }
        }
    }

    private static void tickFlyingFollow(ServerPlayerEntity owner, WardenEntity guard) {
        guard.setTarget(null);
        Vec3d anchor = idleAnchor(owner);
        Vec3d delta = anchor.subtract(pos(guard));
        double distanceSq = delta.lengthSquared();
        if (distanceSq > FOLLOW_TELEPORT_DISTANCE_SQ) {
            guard.refreshPositionAndAngles(anchor.x, anchor.y, anchor.z, owner.getYaw(), owner.getPitch());
            guard.setVelocity(Vec3d.ZERO);
            return;
        }
        if (distanceSq > 0.06D) {
            Vec3d velocity = delta.multiply(0.45D);
            double len = velocity.length();
            if (len > 0.95D) {
                velocity = velocity.normalize().multiply(0.95D);
            }
            guard.setVelocity(velocity);
        } else {
            guard.setVelocity(guard.getVelocity().multiply(0.20D));
        }
        guard.getLookControl().lookAt(owner, 20.0F, 20.0F);
    }

    private static Vec3d idleAnchor(PlayerEntity owner) {
        Vec3d look = owner.getRotationVec(1.0F);
        Vec3d horizontal = new Vec3d(look.x, 0.0D, look.z);
        if (horizontal.lengthSquared() < 0.001D) {
            horizontal = Vec3d.fromPolar(0.0F, owner.getYaw());
            horizontal = new Vec3d(horizontal.x, 0.0D, horizontal.z);
        }
        horizontal = horizontal.normalize();
        return pos(owner).subtract(horizontal.multiply(SUMMON_DISTANCE_BEHIND)).add(0.0D, IDLE_HEIGHT, 0.0D);
    }

    private static void updateWardenCombatTarget(WardenEntity warden, LivingEntity target) {
        warden.setTarget(target);
        warden.updateAttackTarget(target);
        warden.increaseAngerAt(target, 150, false);
    }

    private static void keepWildWardensPassiveNearPlayer(ServerWorld world, ServerPlayerEntity player) {
        double r = 48.0D;
        Box box = new Box(player.getX() - r, player.getY() - r, player.getZ() - r,
                player.getX() + r, player.getY() + r, player.getZ() + r);
        List<WardenEntity> wardens = world.getEntitiesByClass(WardenEntity.class, box,
                warden -> warden.isAlive() && !isGuardWarden(warden));
        for (WardenEntity warden : wardens) {
            if (!canWildWardenRetaliate(warden, player)) {
                calmWardenToward(warden, player);
            }
        }
    }

    private static void calmWardenToward(WardenEntity warden, Entity entity) {
        warden.removeSuspect(entity);
        if (warden.getTarget() == entity) {
            warden.setTarget(null);
        }
    }

    private static void tickCooldowns(MinecraftServer server) {
        decrementCooldowns(GUARD_MELEE_COOLDOWNS);
        decrementCooldowns(PLAYER_SONIC_COOLDOWNS);
        decrementCooldowns(PLAYER_AUTO_SUMMON_COOLDOWNS);
    }

    private static void decrementCooldowns(Map<UUID, Integer> map) {
        map.entrySet().removeIf(entry -> {
            int next = entry.getValue() - 1;
            if (next <= 0) {
                return true;
            }
            entry.setValue(next);
            return false;
        });
    }

    private static void tryAutoSummon(ServerPlayerEntity player) {
        UUID uuid = player.getUuid();
        if (PLAYER_AUTO_SUMMON_COOLDOWNS.getOrDefault(uuid, 0) > 0) {
            return;
        }
        if (findOwnedGuard(player).isEmpty()) {
            summonGuard(player, false);
        }
        PLAYER_AUTO_SUMMON_COOLDOWNS.put(uuid, AUTO_SUMMON_COOLDOWN_TICKS);
    }

    private static Optional<WardenEntity> findOwnedGuard(ServerPlayerEntity player) {
        Optional<WardenEntity> cached = findCachedGuard(player);
        if (cached.isPresent()) {
            return cached;
        }

        // Recovery path for guards loaded from an old save or created before the cache existed.
        // Keep this local to the player instead of scanning every loaded entity in every dimension.
        ServerWorld world = serverWorld(player);
        double r = LOCAL_GUARD_RECOVERY_RANGE;
        Box box = new Box(player.getX() - r, player.getY() - r, player.getZ() - r,
                player.getX() + r, player.getY() + r, player.getZ() + r);
        UUID ownerUuid = player.getUuid();
        List<WardenEntity> guards = world.getEntitiesByClass(WardenEntity.class, box,
                warden -> warden.isAlive()
                        && isGuardWarden(warden)
                        && ownerUuid.equals(getOwnerUuid(warden).orElse(null)));
        if (!guards.isEmpty()) {
            WardenEntity guard = guards.get(0);
            ACTIVE_GUARDS.put(ownerUuid, guard.getUuid());
            return Optional.of(guard);
        }
        return Optional.empty();
    }

    private static Optional<WardenEntity> findCachedGuard(ServerPlayerEntity player) {
        UUID ownerUuid = player.getUuid();
        UUID guardUuid = ACTIVE_GUARDS.get(ownerUuid);
        if (guardUuid == null) {
            return Optional.empty();
        }

        MinecraftServer server = serverWorld(player).getServer();
        for (ServerWorld world : server.getWorlds()) {
            Entity entity = world.getEntity(guardUuid);
            if (!(entity instanceof WardenEntity guard) || !guard.isAlive() || !isGuardWarden(guard)) {
                continue;
            }
            if (!ownerUuid.equals(getOwnerUuid(guard).orElse(null))) {
                ACTIVE_GUARDS.remove(ownerUuid);
                return Optional.empty();
            }
            if (guard.getEntityWorld() != player.getEntityWorld()) {
                // Avoid ticking a guard in the wrong dimension. The player can summon again in the
                // current dimension; this also prevents cross-world duplicates.
                discardGuard(world, guard);
                ACTIVE_GUARDS.remove(ownerUuid);
                return Optional.empty();
            }
            return Optional.of(guard);
        }

        ACTIVE_GUARDS.remove(ownerUuid);
        return Optional.empty();
    }

    private static void markAsGuard(WardenEntity warden, UUID ownerUuid) {
        warden.addCommandTag(TAG_GUARD_WARDEN);
        warden.addCommandTag(TAG_KONEKO_FRIENDLY_ENTITY);
        warden.addCommandTag(TAG_KONEKO_NO_FRIENDLY_ATTACK);
        warden.addCommandTag(TAG_KONEKO_INVULNERABLE_GUARD);
        warden.addCommandTag(TAG_KONEKO_WARDEN_GUARD_ENTITY);
        removeOwnerTags(warden);
        warden.addCommandTag(TAG_OWNER_PREFIX + ownerUuid);
        warden.setInvulnerable(true);
        warden.setNoGravity(true);
    }

    private static void removeOwnerTags(Entity entity) {
        Set<String> copy = new HashSet<>(entity.getCommandTags());
        for (String tag : copy) {
            if (tag.startsWith(TAG_OWNER_PREFIX)) {
                entity.removeCommandTag(tag);
            }
        }
    }

    public static boolean isGuardWarden(Entity entity) {
        return entity instanceof WardenEntity && entity.getCommandTags().contains(TAG_GUARD_WARDEN) && getOwnerUuid(entity).isPresent();
    }

    public static Optional<UUID> getOwnerUuid(Entity entity) {
        if (entity == null) {
            return Optional.empty();
        }
        for (String tag : entity.getCommandTags()) {
            if (tag.startsWith(TAG_OWNER_PREFIX)) {
                try {
                    return Optional.of(UUID.fromString(tag.substring(TAG_OWNER_PREFIX.length())));
                } catch (IllegalArgumentException ignored) {
                    return Optional.empty();
                }
            }
        }
        return Optional.empty();
    }

    private static boolean hasTrustedGuard(PlayerEntity player) {
        if (!hasAnyTrustMarker(player)) {
            return false;
        }
        if (!(player.getEntityWorld() instanceof ServerWorld world)) {
            return true;
        }
        long expiry = getTrustExpiry(player);
        if (expiry <= 0L || world.getTime() >= expiry) {
            return false;
        }
        return true;
    }

    private static boolean hasAnyTrustMarker(PlayerEntity player) {
        return player.getCommandTags().contains(TAG_PLAYER_TAMED);
    }

    private static void grantTrust(ServerPlayerEntity player, long expiresAt) {
        player.addCommandTag(TAG_PLAYER_TAMED);
        removeTrustExpiryTags(player);
        player.addCommandTag(TAG_TRUST_EXPIRES_PREFIX + expiresAt);
    }

    private static long getTrustExpiry(PlayerEntity player) {
        long result = -1L;
        for (String tag : player.getCommandTags()) {
            if (tag.startsWith(TAG_TRUST_EXPIRES_PREFIX)) {
                try {
                    result = Math.max(result, Long.parseLong(tag.substring(TAG_TRUST_EXPIRES_PREFIX.length())));
                } catch (NumberFormatException ignored) {
                    return -1L;
                }
            }
        }
        return result;
    }

    private static long trustRemainingTicks(ServerPlayerEntity player) {
        long expiry = getTrustExpiry(player);
        if (expiry <= 0L) {
            return 0L;
        }
        return Math.max(0L, expiry - serverWorld(player).getTime());
    }

    private static void removeTrustExpiryTags(Entity entity) {
        Set<String> copy = new HashSet<>(entity.getCommandTags());
        for (String tag : copy) {
            if (tag.startsWith(TAG_TRUST_EXPIRES_PREFIX)) {
                entity.removeCommandTag(tag);
            }
        }
    }

    private static void revokeTrust(ServerPlayerEntity player, boolean feedback) {
        recallGuard(player, false);
        player.removeCommandTag(TAG_PLAYER_TAMED);
        removeTrustExpiryTags(player);
        OWNER_TARGETS.remove(player.getUuid());
        if (feedback) {
            player.sendMessage(Text.translatable("text.koneko_warden_guard.trust_expired"), false);
        }
    }

    private static boolean autoSummonEnabled(PlayerEntity player) {
        return player.getCommandTags().contains(TAG_PLAYER_AUTO_SUMMON);
    }

    private static void setAutoSummon(ServerPlayerEntity player, boolean enabled) {
        if (enabled) {
            player.addCommandTag(TAG_PLAYER_AUTO_SUMMON);
        } else {
            player.removeCommandTag(TAG_PLAYER_AUTO_SUMMON);
        }
    }

    private static void assignOwnerTarget(ServerPlayerEntity owner, LivingEntity target) {
        assignOwnerTarget(owner.getUuid(), target);
    }

    private static void assignOwnerTarget(UUID ownerUuid, LivingEntity target) {
        OWNER_TARGETS.put(ownerUuid, target.getUuid());
    }

    private static boolean isAllowedGuardTarget(ServerPlayerEntity owner, LivingEntity target) {
        return isAllowedGuardTarget(owner.getUuid(), target);
    }

    public static boolean isAllowedGuardTarget(UUID ownerUuid, Entity target) {
        if (!(target instanceof LivingEntity living) || !living.isAlive()) {
            return false;
        }
        if (target instanceof PlayerEntity) {
            return false;
        }
        if (isGuardWarden(target)) {
            return false;
        }
        if (isKonekoFriendlyOrProtected(target)) {
            return false;
        }
        if (isTamedPet(target)) {
            return false;
        }
        if (ownerUuid.equals(target.getUuid())) {
            return false;
        }
        return true;
    }

    private static boolean isFriendlyToOwner(UUID ownerUuid, Entity entity) {
        if (entity == null) {
            return false;
        }
        if (ownerUuid.equals(entity.getUuid())) {
            return true;
        }
        if (entity instanceof PlayerEntity) {
            return true;
        }
        if (isGuardWarden(entity)) {
            return true;
        }
        if (isKonekoFriendlyOrProtected(entity)) {
            return true;
        }
        return isTamedPet(entity);
    }

    public static boolean isKonekoFriendlyOrProtected(Entity entity) {
        if (entity == null) {
            return false;
        }
        Set<String> tags = entity.getCommandTags();
        return tags.contains(TAG_KONEKO_FRIENDLY_ENTITY)
                || tags.contains(TAG_KONEKO_NO_FRIENDLY_ATTACK)
                || tags.contains(TAG_KONEKO_INVULNERABLE_GUARD)
                || tags.contains(TAG_KONEKO_WARDEN_GUARD_ENTITY)
                || tags.contains(TAG_MARCH_ENTITY)
                || tags.contains(TAG_MARCH_AI)
                || tags.contains(TAG_MARCH_MOUNT);
    }

    private static boolean isTamedPet(Entity entity) {
        if (entity instanceof TameableEntity tameable && tameable.isTamed()) {
            return true;
        }
        if (entity instanceof AbstractHorseEntity horse && horse.isTame()) {
            return true;
        }
        return entity instanceof Tameable tameable && tameable.getOwnerReference() != null;
    }

    private static Entity effectiveAttacker(DamageSource source) {
        Entity attacker = source.getAttacker();
        if (attacker != null) {
            return attacker;
        }
        return source.getSource();
    }

    private static void markWildWardenRetaliation(WardenEntity warden, PlayerEntity player) {
        WILD_WARDEN_RETALIATION_PLAYERS.computeIfAbsent(warden.getUuid(), uuid -> new HashSet<>()).add(player.getUuid());
        updateWardenCombatTarget(warden, player);
    }

    private static boolean canWildWardenRetaliate(WardenEntity warden, PlayerEntity player) {
        Set<UUID> players = WILD_WARDEN_RETALIATION_PLAYERS.get(warden.getUuid());
        return players != null && players.contains(player.getUuid());
    }

    public static boolean shouldRejectWardenTarget(WardenEntity warden, Entity target) {
        if (target == null) {
            return false;
        }
        if (isGuardWarden(warden)) {
            UUID ownerUuid = getOwnerUuid(warden).orElse(null);
            return ownerUuid == null || !isAllowedGuardTarget(ownerUuid, target);
        }
        if (target instanceof PlayerEntity player && hasTrustedGuard(player) && !canWildWardenRetaliate(warden, player)) {
            return true;
        }
        return false;
    }

    public static boolean isGuardDamageAllowed(WardenEntity guard, Entity target) {
        UUID ownerUuid = getOwnerUuid(guard).orElse(null);
        return ownerUuid != null && isAllowedGuardTarget(ownerUuid, target);
    }
}
