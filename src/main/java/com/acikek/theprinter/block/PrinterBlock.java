package com.acikek.theprinter.block;

import com.acikek.theprinter.ThePrinter;
import com.acikek.theprinter.sound.ModSoundEvents;
import net.minecraft.block.*;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityTicker;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.s2c.play.StopSoundS2CPacket;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.util.*;
import net.minecraft.util.collection.DefaultedList;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.registry.Registry;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;
import org.quiltmc.qsl.block.extensions.api.QuiltBlockSettings;
import org.quiltmc.qsl.item.setting.api.QuiltItemSettings;
import org.quiltmc.qsl.networking.api.PlayerLookup;

public class PrinterBlock extends HorizontalFacingBlock implements BlockEntityProvider {

	public static final BooleanProperty ON = BooleanProperty.of("on");
	public static final BooleanProperty PRINTING = BooleanProperty.of("printing");

	public static final Settings SETTINGS = QuiltBlockSettings.of(Material.METAL)
			.strength(6.0f, 6.0f)
			.luminance(value -> value.get(ON) ? 4 : 1);

	public static final VoxelShape SHAPE = VoxelShapes.union(
			VoxelShapes.cuboid(0.0, 0.0, 0.0, 1.0, 0.75, 1.0),
			VoxelShapes.cuboid(0.125, 0.75, 0.125, 0.875, 0.875, 0.875),
			VoxelShapes.cuboid(0.0, 0.875, 0.0, 1.0, 1.0, 1.0)
	);

	public static final PrinterBlock INSTANCE = new PrinterBlock();

	public PrinterBlock() {
		super(SETTINGS);
		setDefaultState(getDefaultState()
				.with(FACING, Direction.NORTH)
				.with(ON, false)
				.with(PRINTING, false));
	}

	@Override
	public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
		return SHAPE;
	}

	@Override
	public ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, Hand hand, BlockHitResult hit) {
		if (hand == Hand.MAIN_HAND && world.getBlockEntity(pos) instanceof PrinterBlockEntity blockEntity) {
			SoundEvent event = null;
			ItemStack handStack = player.getStackInHand(hand);
			if (!handStack.isEmpty() && !state.get(ON)) {
				world.setBlockState(pos, state.with(ON, true));
				if (handStack.getItem() instanceof BlockItem blockItem) {
					world.playSound(null, pos, blockItem.getBlock().getDefaultState().getSoundGroup().getPlaceSound(), SoundCategory.BLOCKS, 1.0f, 1.3f);
				}
				blockEntity.addItem(player, handStack);
				event = ModSoundEvents.STARTUP;
			}
			else if (handStack.isEmpty()) {
				if (state.get(PRINTING)) {

				}
				else if (state.get(ON)) {
					world.setBlockState(pos, state.with(ON, false));
					blockEntity.removeItem(world, pos, player, false);
					event = ModSoundEvents.SHUTDOWN;
				}
			}
			if (event != null) {
				world.playSound(null, pos, event, SoundCategory.BLOCKS, 1.0f, 1.0f);
				return ActionResult.SUCCESS;
			}
		}
		return ActionResult.PASS;
	}

	public static void stopPrintingSound(ServerWorld world, BlockPos pos) {
		StopSoundS2CPacket packet = new StopSoundS2CPacket(ModSoundEvents.PRINTING.getId(), SoundCategory.BLOCKS);
		for (ServerPlayerEntity player : PlayerLookup.tracking(world, pos)) {
			player.networkHandler.sendPacket(packet);
		}
	}

	@Override
	public void onStateReplaced(BlockState state, World world, BlockPos pos, BlockState newState, boolean moved) {
		if (state.getBlock() != newState.getBlock() && world.getBlockEntity(pos) instanceof PrinterBlockEntity blockEntity) {
			ItemScatterer.spawn(world, pos, DefaultedList.copyOf(ItemStack.EMPTY, blockEntity.getStack(0)));
			if (world instanceof ServerWorld serverWorld) {
				blockEntity.tryDropXP(serverWorld, pos);
				if (state.get(PRINTING)) {
					stopPrintingSound(serverWorld, pos);
				}
			}
			world.removeBlockEntity(pos);
		}
	}

	@Nullable
	@Override
	public BlockState getPlacementState(ItemPlacementContext ctx) {
		return super.getPlacementState(ctx).with(FACING, ctx.getPlayerFacing().getOpposite());
	}

	@Nullable
	@Override
	public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
		return new PrinterBlockEntity(pos, state);
	}

	@Nullable
	@Override
	public <T extends BlockEntity> BlockEntityTicker<T> getTicker(World world, BlockState state, BlockEntityType<T> type) {
		return BlockWithEntity.checkType(type, PrinterBlockEntity.BLOCK_ENTITY_TYPE, PrinterBlockEntity::tick);
	}

	@Override
	protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
		super.appendProperties(builder);
		builder.add(FACING, ON, PRINTING);
	}

	public static void register() {
		Identifier id = ThePrinter.id("the_printer");
		Registry.register(Registry.BLOCK, id, INSTANCE);
		Registry.register(Registry.ITEM, id, new BlockItem(INSTANCE, new QuiltItemSettings()
				.group(ItemGroup.DECORATIONS)
				.rarity(Rarity.EPIC)));
	}
}
