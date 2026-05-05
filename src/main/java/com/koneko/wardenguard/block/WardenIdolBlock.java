package com.koneko.wardenguard.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.HorizontalFacingBlock;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.EnumProperty;
import net.minecraft.state.property.IntProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import net.minecraft.world.WorldView;
import net.minecraft.world.tick.ScheduledTickView;

public final class WardenIdolBlock extends HorizontalFacingBlock {
    public static final MapCodec<WardenIdolBlock> CODEC = createCodec(WardenIdolBlock::new);

    public static final EnumProperty<Direction> FACING = HorizontalFacingBlock.FACING;
    public static final EnumProperty<DoubleBlockHalf> HALF = Properties.DOUBLE_BLOCK_HALF;
    public static final IntProperty RUST = IntProperty.of("rust", 0, 3);

    // With vanilla randomTickSpeed=3 this averages roughly the same order of time as a full copper oxidation path.
    private static final int RUST_RANDOM_CHANCE = 430;

    private static final VoxelShape LOWER_SHAPE = VoxelShapes.cuboid(
            0.12D, 0.0D, 0.12D,
            0.88D, 1.0D, 0.88D
    );

    private static final VoxelShape UPPER_SHAPE = VoxelShapes.cuboid(
            0.16D, 0.0D, 0.16D,
            0.84D, 1.0D, 0.84D
    );

    public WardenIdolBlock(Settings settings) {
        super(settings);
        this.setDefaultState(this.stateManager.getDefaultState()
                .with(FACING, Direction.NORTH)
                .with(HALF, DoubleBlockHalf.LOWER)
                .with(RUST, 0));
    }

    @Override
    protected MapCodec<? extends HorizontalFacingBlock> getCodec() {
        return CODEC;
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(FACING, HALF, RUST);
    }

    @Override
    public BlockState getPlacementState(ItemPlacementContext ctx) {
        BlockPos pos = ctx.getBlockPos();

        if (!ctx.getWorld().getBlockState(pos.up()).canReplace(ctx)) {
            return null;
        }

        return this.getDefaultState()
                .with(FACING, ctx.getHorizontalPlayerFacing().getOpposite())
                .with(HALF, DoubleBlockHalf.LOWER)
                .with(RUST, 0);
    }

    @Override
    public void onPlaced(World world, BlockPos pos, BlockState state, LivingEntity placer, ItemStack itemStack) {
        if (world instanceof ServerWorld && state.get(HALF) == DoubleBlockHalf.LOWER) {
            world.setBlockState(pos.up(), state.with(HALF, DoubleBlockHalf.UPPER), Block.NOTIFY_ALL);
        }
    }

    @Override
    protected BlockState rotate(BlockState state, BlockRotation rotation) {
        return state.with(FACING, rotation.rotate(state.get(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, BlockMirror mirror) {
        return state.rotate(mirror.getRotation(state.get(FACING)));
    }

    @Override
    protected boolean hasRandomTicks(BlockState state) {
        return state.get(HALF) == DoubleBlockHalf.LOWER && state.get(RUST) < 3;
    }

    @Override
    protected void randomTick(BlockState state, ServerWorld world, BlockPos pos, Random random) {
        if (state.get(HALF) != DoubleBlockHalf.LOWER) {
            return;
        }

        int rust = state.get(RUST);
        if (rust < 3 && random.nextInt(RUST_RANDOM_CHANCE) == 0) {
            world.setBlockState(pos, state.with(RUST, rust + 1), Block.NOTIFY_ALL);
        }
    }

    @Override
    protected BlockState getStateForNeighborUpdate(
            BlockState state,
            WorldView world,
            ScheduledTickView tickView,
            BlockPos pos,
            Direction direction,
            BlockPos neighborPos,
            BlockState neighborState,
            Random random
    ) {
        DoubleBlockHalf half = state.get(HALF);

        if (half == DoubleBlockHalf.LOWER && direction == Direction.UP) {
            if (!neighborState.isOf(this) || neighborState.get(HALF) != DoubleBlockHalf.UPPER) {
                return Blocks.AIR.getDefaultState();
            }

            if (neighborState.get(RUST) != state.get(RUST)) {
                return state.with(RUST, neighborState.get(RUST));
            }
        }

        if (half == DoubleBlockHalf.UPPER && direction == Direction.DOWN) {
            if (!neighborState.isOf(this) || neighborState.get(HALF) != DoubleBlockHalf.LOWER) {
                return Blocks.AIR.getDefaultState();
            }

            if (neighborState.get(RUST) != state.get(RUST)) {
                return state.with(RUST, neighborState.get(RUST));
            }
        }

        return super.getStateForNeighborUpdate(state, world, tickView, pos, direction, neighborPos, neighborState, random);
    }

    @Override
    protected VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return state.get(HALF) == DoubleBlockHalf.UPPER ? UPPER_SHAPE : LOWER_SHAPE;
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return getOutlineShape(state, world, pos, context);
    }
}
