package portalgun.items;

import net.minecraft.ChatFormatting;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.dimension.end.EndDragonFight;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.apache.commons.lang3.Validate;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import portalgun.config.PortalGunConfig;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.McHelper;
import qouteall.imm_ptl.core.compat.GravityChangerInterface;
import qouteall.imm_ptl.core.portal.PortalManipulation;
import qouteall.imm_ptl.core.portal.PortalUtils;
import qouteall.q_misc_util.Helper;
import qouteall.q_misc_util.my_util.AARotation;
import qouteall.q_misc_util.my_util.IntBox;
import qouteall.q_misc_util.my_util.LimitedLogger;
import software.bernie.geckolib.animatable.GeoItem;
import software.bernie.geckolib.animatable.SingletonGeoAnimatable;
import software.bernie.geckolib.animatable.client.RenderProvider;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animatable.instance.SingletonAnimatableInstanceCache;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.core.animation.AnimationController;
import software.bernie.geckolib.core.animation.RawAnimation;
import software.bernie.geckolib.core.object.PlayState;
import portalgun.PortalGunMod;
import portalgun.PortalGunRecord;
import portalgun.client.renderer.PortalGunItemRenderer;
import portalgun.entities.CustomPortal;
import portalgun.misc.BlockList;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class PortalGunItem extends Item implements GeoItem {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final LimitedLogger LIMITED_LOGGER = new LimitedLogger(100);
    public static final int COOLDOWN_TICKS = 4;
    public static final double SIZE_MULTIPLIER = 31.0 / 32.0;
    
    public final AnimatableInstanceCache cache = new SingletonAnimatableInstanceCache(this);
    
    private static final RawAnimation SHOOT_ANIM = RawAnimation.begin().thenPlay("portal_shoot");
    
    private final Supplier<Object> renderProvider = GeoItem.makeRenderer(this);
    
    public PortalGunItem(Properties settings) {
        super(settings);
        
        SingletonGeoAnimatable.registerSyncedAnimatable(this);
    }
    
    // example command: /give @p portalgun:portal_gun{allowedBlocks:["#minecraft:ice","minecraft:stone"]} 1
    public static final class ItemInfo {
        public BlockList allowedBlocks;
        public int maxEnergy;
        public int remainingEnergy;
        
        public ItemInfo(
            BlockList allowedBlocks,
            int maxEnergy, // 0 for infinite energy
            int remainingEnergy
        ) {
            this.allowedBlocks = allowedBlocks;
            this.maxEnergy = maxEnergy;
            this.remainingEnergy = remainingEnergy;
        }
        
        public static ItemInfo fromTag(CompoundTag tag) {
            return new ItemInfo(
                BlockList.fromTag(tag.getList("allowedBlocks", Tag.TAG_STRING)),
                tag.contains("maxEnergy") ?
                    tag.getInt("maxEnergy") : PortalGunConfig.get().maxEnergy,
                tag.contains("remainingEnergy") ?
                    tag.getInt("remainingEnergy") : PortalGunConfig.get().maxEnergy
            );
        }
        
        public CompoundTag toTag() {
            CompoundTag tag = new CompoundTag();
            tag.put("allowedBlocks", allowedBlocks.toTag());
            tag.putInt("maxEnergy", maxEnergy);
            tag.putInt("remainingEnergy", remainingEnergy);
            return tag;
        }
        
        public ItemStack toStack() {
            ItemStack stack = new ItemStack(PortalGunMod.PORTAL_GUN);
            stack.setTag(toTag());
            return stack;
        }
        
        public boolean limitsEnergy() {
            return this.maxEnergy != 0;
        }
        
        @Override
        public boolean equals(Object obj) {
            if (obj == this) return true;
            if (obj == null || obj.getClass() != this.getClass()) return false;
            var that = (ItemInfo) obj;
            return Objects.equals(this.allowedBlocks, that.allowedBlocks) &&
                this.maxEnergy == that.maxEnergy &&
                this.remainingEnergy == that.remainingEnergy;
        }
        
        @Override
        public int hashCode() {
            return Objects.hash(allowedBlocks, maxEnergy, remainingEnergy);
        }
        
        @Override
        public String toString() {
            return "ItemInfo[" +
                "allowedBlocks=" + allowedBlocks + ", " +
                "maxEnergy=" + maxEnergy + ", " +
                "remainingEnergy=" + remainingEnergy + ']';
        }
        
    }
    
    // Utilise our own render hook to define our custom renderer
    @Override
    public void createRenderer(Consumer<Object> consumer) {
        consumer.accept(new RenderProvider() {
            private final PortalGunItemRenderer renderer = new PortalGunItemRenderer();
            
            @Override
            public BlockEntityWithoutLevelRenderer getCustomRenderer() {
                return this.renderer;
            }
        });
    }
    
    @Override
    public Supplier<Object> getRenderProvider() {
        return this.renderProvider;
    }
    
    // Register our animation controllers
    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(
            new AnimationController<>(this, "portalGunController", 1, state -> PlayState.CONTINUE)
                .triggerableAnim("shoot_anim", SHOOT_ANIM)
        );
    }
    
    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.cache;
    }
    
    @Override
    public boolean canAttackBlock(BlockState state, Level world, BlockPos pos, Player miner) {
        return false;
    }
    
    @Override
    public InteractionResultHolder<ItemStack> use(Level world, Player player, InteractionHand hand) {
        ItemStack itemStack = player.getItemInHand(hand);
        if (world.isClientSide()) {
            return InteractionResultHolder.fail(itemStack);
        }
        
        boolean success = interact(
            (ServerPlayer) player, hand,
            PortalGunRecord.PortalGunSide.orange
        );
        
        if (success) {
            player.awardStat(Stats.ITEM_USED.get(this));
            
            // always fail, to cancel hand swing
            return InteractionResultHolder.fail(itemStack);
        }
        else {
            return InteractionResultHolder.fail(itemStack);
        }
    }
    
    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level world, List<Component> tooltip, TooltipFlag context) {
        super.appendHoverText(stack, world, tooltip, context);
        
        tooltip.add(Component.translatable("item.portalgun.portal_gun_desc").withStyle(ChatFormatting.GOLD));
        
        CompoundTag tag = stack.getOrCreateTag();
        ItemInfo itemInfo = ItemInfo.fromTag(tag);
        
        if (!itemInfo.allowedBlocks.list().isEmpty()) {
            tooltip.add(Component.translatable("portal_gun.limit_allowed_blocks"));
            int displayLimit = 5;
            List<Block> allowedBlocks = itemInfo.allowedBlocks.asStream().limit(displayLimit + 1).toList();
            for (int i = 0; i < displayLimit; i++) {
                if (i < allowedBlocks.size()) {
                    Block block = allowedBlocks.get(i);
                    tooltip.add(block.getName().withStyle(ChatFormatting.LIGHT_PURPLE));
                }
            }
            if (allowedBlocks.size() > displayLimit) {
                tooltip.add(Component.literal("..."));
            }
        }
        
        if (itemInfo.limitsEnergy()) {
            tooltip.add(Component.translatable(
                "portal_gun.remaining_energy", itemInfo.remainingEnergy, itemInfo.maxEnergy
            ));
        }
    }
    
    public InteractionResult onAttack(
        Player player, Level world, InteractionHand hand
    ) {
        if (world.isClientSide()) {
            return InteractionResult.PASS;
        }
        
        boolean success = interact(
            (ServerPlayer) player, hand,
            PortalGunRecord.PortalGunSide.blue
        );
        
        if (success) {
            player.awardStat(Stats.ITEM_USED.get(this));
            
            return InteractionResult.SUCCESS;
        }
        else {
            return InteractionResult.FAIL;
        }
    }
    
    // return whether successful
    public boolean interact(
        ServerPlayer player,
        InteractionHand hand,
        PortalGunRecord.PortalGunSide side
    ) {
        ItemStack itemStack = player.getItemInHand(hand);
        player.getCooldowns().addCooldown(this, COOLDOWN_TICKS);
        
        PortalUtils.PortalAwareRaytraceResult raytraceResult = PortalUtils.portalAwareRayTrace(player, 100);
        
        if (raytraceResult == null) {
            return false;
        }
        
        BlockHitResult blockHit = raytraceResult.hitResult();
        ServerLevel world = ((ServerLevel) raytraceResult.world());
        Direction wallFacing = blockHit.getDirection();
        
        if (!checkAction(player, world)) {
            return false;
        }
        
        Validate.isTrue(blockHit.getType() == HitResult.Type.BLOCK);
        
        player.level().playSound(
            null,
            player.getX(), player.getY(), player.getZ(),
            side == PortalGunRecord.PortalGunSide.blue ?
                PortalGunMod.PORTAL1_SHOOT_EVENT : PortalGunMod.PORTAL2_SHOOT_EVENT,
            SoundSource.PLAYERS,
            1.0F, 1.0F
        );
        
        PortalGunRecord.PortalGunKind kind = PortalGunRecord.PortalGunKind._2x1;
        
        PortalGunRecord.PortalDescriptor descriptor =
            new PortalGunRecord.PortalDescriptor(player.getUUID(), kind, side);
        
        ItemInfo itemInfo = ItemInfo.fromTag(itemStack.getOrCreateTag());
        
        if (itemInfo.limitsEnergy()) {
            if (itemInfo.remainingEnergy <= 0) {
                player.displayClientMessage(
                    Component.translatable("portal_gun.out_of_energy"),
                    true
                );
                return false;
            }
        }
        
        BiPredicate<Level, BlockPos> wallPredicate = itemInfo.allowedBlocks.getWallPredicate();
        
        PortalPlacement placement = findPortalPlacement(
            player, kind, raytraceResult, descriptor, wallPredicate
        );
        
        if (placement == null) {
            return false;
        }
        
        if (itemInfo.limitsEnergy()) {
            itemInfo.remainingEnergy--;
            itemStack.setTag(itemInfo.toTag());
        }
        
        Direction rightDir = placement.rotation.transformedX;
        Direction upDir = placement.rotation.transformedY;
        
        triggerAnim(
            player,
            GeoItem.getOrAssignId(player.getItemInHand(hand), ((ServerLevel) player.level())),
            "portalGunController", "shoot_anim"
        );
        
        PortalGunRecord record = PortalGunRecord.get();
        
        PortalGunRecord.PortalDescriptor otherSideDescriptor = descriptor.getTheOtherSide();
        
        PortalGunRecord.PortalInfo thisSideInfo = record.data.get(descriptor);
        PortalGunRecord.PortalInfo otherSideInfo = record.data.get(otherSideDescriptor);
        
        Vec3 wallFacingVec = Vec3.atLowerCornerOf(wallFacing.getNormal());
        Vec3 newPortalOrigin = Helper
            .getBoxSurface(placement.areaBox.toRealNumberBox(), wallFacing.getOpposite())
            .getCenter()
            .add(wallFacingVec.scale(PortalGunMod.portalOffset));
        
        CustomPortal portal = null;
        boolean isExistingPortal = false;
        
        if (thisSideInfo != null) {
            Entity entity = world.getEntity(thisSideInfo.portalId());
            if (entity instanceof CustomPortal customPortal) {
                portal = customPortal;
                isExistingPortal = true;
            }
        }
        
        if (portal == null) {
            portal = CustomPortal.entityType.create(world);
            Validate.notNull(portal);
        }
        
        portal.setOriginPos(newPortalOrigin);
        portal.setOrientationAndSize(
            Vec3.atLowerCornerOf(rightDir.getNormal()),
            Vec3.atLowerCornerOf(upDir.getNormal()),
            kind.getWidth() * SIZE_MULTIPLIER,
            kind.getHeight() * SIZE_MULTIPLIER
        );
        portal.descriptor = descriptor;
        portal.wallBox = placement.wallBox;
        portal.airBox = placement.areaBox;
        portal.setAllowedBlocks(itemInfo.allowedBlocks);
        portal.thisSideUpdateCounter = thisSideInfo == null ? 0 : thisSideInfo.updateCounter();
        portal.otherSideUpdateCounter = otherSideInfo == null ? 0 : otherSideInfo.updateCounter();
        PortalManipulation.makePortalRound(portal, 20);
        portal.disableDefaultAnimation();
        
        if (otherSideInfo == null) {
            // it's unpaired, invisible and not teleportable
            portal.setDestinationDimension(world.dimension());
            portal.setDestination(newPortalOrigin.add(0, 10, 0));
            portal.setIsVisible(false);
            portal.teleportable = false;
        }
        else {
            // it's linked
            portal.setDestinationDimension(otherSideInfo.portalDim());
            portal.setDestination(otherSideInfo.portalPos());
            portal.setOtherSideOrientation(otherSideInfo.portalOrientation());
            portal.setIsVisible(true);
            portal.teleportable = true;
            player.level().playSound(
                null,
                player.getX(), player.getY(), player.getZ(),
                PortalGunMod.PORTAL_OPEN_EVENT,
                SoundSource.PLAYERS,
                1.0F, 1.0F
            );
        }
        
        portal.thisSideUpdateCounter += 1;
        thisSideInfo = new PortalGunRecord.PortalInfo(
            portal.getUUID(),
            world.dimension(),
            newPortalOrigin,
            portal.getOrientationRotation(),
            portal.thisSideUpdateCounter
        );
        record.data.put(descriptor, thisSideInfo);
        record.setDirty();
        
        if (!isExistingPortal) {
            McHelper.spawnServerEntity(portal);
        }
        else {
            portal.reloadAndSyncToClient();
        }
        
        return true;
    }
    
    private static boolean checkAction(ServerPlayer player, ServerLevel world) {
        if (world.dimension() == Level.END) {
            EndDragonFight endDragonFight = world.getDragonFight();
            if (endDragonFight != null) {
                if (!endDragonFight.hasPreviouslyKilledDragon()) {
                    player.displayClientMessage(
                        Component.literal("Using portal gun in end before killing any ender dragon is not allowed"),
                        true
                    );
                    return false;
                }
            }
        }
        return true;
    }
    
    private static record PortalPlacement(
        AARotation rotation,
        IntBox areaBox,
        IntBox wallBox
    ) {
    }
    
    @Nullable
    private static PortalPlacement findPortalPlacement(
        ServerPlayer player,
        PortalGunRecord.PortalGunKind kind,
        PortalUtils.PortalAwareRaytraceResult raytraceResult,
        PortalGunRecord.PortalDescriptor descriptor,
        BiPredicate<Level, BlockPos> wallPredicate
    ) {
        BlockHitResult blockHit = raytraceResult.hitResult();
        ServerLevel world = ((ServerLevel) raytraceResult.world());
        BlockPos interactingAirPos = blockHit.getBlockPos().relative(blockHit.getDirection());
        Direction wallFacing = blockHit.getDirection();
        
        Direction playerGravity = GravityChangerInterface.invoker.getGravityDirection(player);
        Direction effectiveGravity = raytraceResult.portalsPassingThrough().stream().reduce(
            playerGravity,
            (gravity, portal) -> portal.getTeleportedGravityDirection(gravity),
            (g1, g2) -> {throw new RuntimeException();}
        );
        Vec3 viewVector = player.getViewVector(1);
        Vec3 transformedViewVector = raytraceResult.portalsPassingThrough().stream().reduce(
            viewVector,
            (v, portal) -> portal.transformLocalVec(v),
            (v1, v2) -> {throw new RuntimeException();}
        );
        
        Vec3 localViewVector = GravityChangerInterface.invoker
            .transformWorldToPlayer(effectiveGravity, transformedViewVector);
        
        Direction localWallFacing = GravityChangerInterface.invoker
            .transformDirWorldToPlayer(effectiveGravity, wallFacing);
        
        Direction[] localUpDirCandidates =
            Helper.getAnotherFourDirections(localWallFacing.getAxis());
        
        Arrays.sort(localUpDirCandidates, Comparator.comparingDouble((Direction dir) -> {
            if (dir == Direction.UP) {
                // the local up direction has the highest priority
                return 1;
            }
            // horizontal dot product
            return dir.getNormal().getX() * localViewVector.x + dir.getNormal().getZ() * localViewVector.z;
        }).reversed());
        
        BlockPos portalAreaSize = new BlockPos(kind.getWidth(), kind.getHeight(), 1);
        
        for (Direction localUpDir : localUpDirCandidates) {
            Direction upDir = GravityChangerInterface.invoker
                .transformDirPlayerToWorld(effectiveGravity, localUpDir);
            
            AARotation rot = AARotation.getAARotationFromYZ(upDir, wallFacing);
            BlockPos transformedSize = rot.transform(portalAreaSize);
            IntBox portalArea = IntBox.getBoxByPosAndSignedSize(interactingAirPos, transformedSize);
            IntBox wallArea = portalArea.getMoved(wallFacing.getOpposite().getNormal());
            
            if (PortalGunMod.isAreaClear(world, portalArea) &&
                wallArea.fastStream().allMatch(p -> wallPredicate.test(world, p)) &&
                !otherPortalExistsInArea(world, wallArea, wallFacing, descriptor)
            ) {
                return new PortalPlacement(rot, portalArea, wallArea);
            }
        }
        
        return null;
    }
    
    private static boolean otherPortalExistsInArea(
        Level world, IntBox wallArea, Direction wallFacing,
        PortalGunRecord.PortalDescriptor descriptor
    ) {
        List<CustomPortal> portals = McHelper.findEntitiesByBox(
            CustomPortal.class,
            world,
            wallArea.toRealNumberBox().inflate(0.1),
            IPGlobal.maxNormalPortalRadius,
            p -> p.getApproximateFacingDirection() == wallFacing
                && IntBox.getIntersect(p.wallBox, wallArea) != null
                && !Objects.equals(p.descriptor, descriptor)
        );
        return !portals.isEmpty();
    }
    
    // PortalGun does not use vanilla damage system
    // because vanilla max damage is unchangeable
    @Override
    public boolean isBarVisible(ItemStack stack) {
        CompoundTag tag = stack.getOrCreateTag();
        int maxEnergy = tag.getInt("maxEnergy");
        return maxEnergy != 0;
    }
    
    @Override
    public int getBarWidth(ItemStack stack) {
        CompoundTag tag = stack.getOrCreateTag();
        int maxEnergy = tag.getInt("maxEnergy");
        int remainingEnergy = tag.getInt("remainingEnergy");
        
        if (maxEnergy == 0) {
            return 0;
        }
        
        return Math.round((float) remainingEnergy * 13.0F / (float) maxEnergy);
    }
    
    @Override
    public int getBarColor(ItemStack stack) {
        return 0x00d907e0;
    }
}
