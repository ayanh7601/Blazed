package com.ayan7601.blazed.modules.main;

import baritone.api.BaritoneAPI;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalNear;
import com.ayan7601.blazed.BlazedAddon;
import com.ayan7601.blazed.VersionUtil;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class AutoMine extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgColumns = settings.createGroup("Tall Plants");

    // ---- General ----

    private final Setting<List<Item>> blocksToMine = sgGeneral.add(new ItemListSetting.Builder()
            .name("blocks-to-mine")
            .description("Blocks that Baritone should mine.")
            .filter(item -> item instanceof BlockItem)
            .defaultValue(Items.STONE, Items.COAL_ORE)
            .build());

    private final Setting<Boolean> autoEat = sgGeneral.add(new BoolSetting.Builder()
            .name("auto-eat")
            .description("Automatically pauses mining and eats food when hunger drops below the threshold.")
            .defaultValue(false)
            .build());

    private final Setting<Integer> eatAtHunger = sgGeneral.add(new IntSetting.Builder()
            .name("eat-at-hunger")
            .description("Eat when hunger is at or below this value.")
            .defaultValue(16)
            .min(0)
            .max(20)
            .sliderMax(20)
            .visible(autoEat::get)
            .build());

    private final Setting<List<Item>> foodItems = sgGeneral.add(new ItemListSetting.Builder()
            .name("food-items")
            .description("Food items the module is allowed to use.")
            .defaultValue(Items.BREAD, Items.COOKED_BEEF, Items.COOKED_PORKCHOP, Items.COOKED_CHICKEN, Items.COOKED_MUTTON)
            .visible(autoEat::get)
            .build());

    private final Setting<Boolean> lookAtBlock = sgGeneral.add(new BoolSetting.Builder()
            .name("look-at-block")
            .description("Visibly turns your camera towards the block being mined, instead of only rotating server-side.")
            .defaultValue(true)
            .build());

    private final Setting<Boolean> autoTool = sgGeneral.add(new BoolSetting.Builder()
            .name("auto-tool")
            .description("Switches to the fastest tool in your hotbar before breaking tall plants. Baritone already picks tools for other blocks.")
            .defaultValue(true)
            .build());

    // ---- Tall plants (bamboo, sugar cane, cactus) ----

    private final Setting<Boolean> bottomFirst = sgColumns.add(new BoolSetting.Builder()
            .name("bottom-first")
            .description("Mines bamboo, sugar cane and cactus from the bottom block so the whole column is harvested at once, then moves on to the next one.")
            .defaultValue(true)
            .build());

    private final Setting<Integer> scanRange = sgColumns.add(new IntSetting.Builder()
            .name("scan-range")
            .description("How far (in blocks) to look for plant columns.")
            .defaultValue(16)
            .min(4)
            .sliderMax(32)
            .visible(bottomFirst::get)
            .build());

    private final Setting<Boolean> collectDrops = sgColumns.add(new BoolSetting.Builder()
            .name("collect-drops")
            .description("Occasionally checks for dropped items nearby and walks over to pick them up.")
            .defaultValue(true)
            .visible(bottomFirst::get)
            .build());

    private final Setting<Integer> dropCheckInterval = sgColumns.add(new IntSetting.Builder()
            .name("drop-check-interval")
            .description("Ticks between checks for nearby drops (20 ticks = 1 second).")
            .defaultValue(40)
            .min(10)
            .sliderMax(200)
            .visible(() -> bottomFirst.get() && collectDrops.get())
            .build());

    private final Setting<Integer> collectRadius = sgColumns.add(new IntSetting.Builder()
            .name("collect-radius")
            .description("Radius around you to look for drops.")
            .defaultValue(8)
            .min(2)
            .sliderMax(16)
            .visible(() -> bottomFirst.get() && collectDrops.get())
            .build());

    private enum Phase { IDLE, GOING, BREAKING, COLLECTING }

    private static final double REACH = 4.0;

    private final Set<BlockPos> blacklist = new HashSet<>();

    private static final int EAT_TIMEOUT = 80;      // ticks without the food level changing before giving up
    private static final int EAT_RETRY_DELAY = 200;  // ticks to keep mining before trying to eat again

    private boolean wasPaused;
    private boolean mineRunning;

    private Boolean savedBlockFreeLook;

    private boolean holdingUse;
    private int eatStuckTicks;
    private int eatCooldown;
    private int lastFood = -1;

    private Phase phase = Phase.IDLE;
    private BlockPos target;
    private int phaseTicks;
    private int dropTimer;
    private int lastDropId;
    private final Set<Integer> ignoredDrops = new HashSet<>();
    private int emptyTicks;
    private int scanTimer;

    public AutoMine() {
        super(BlazedAddon.CATEGORY, "auto-mine", "Automatically mines selected blocks using Baritone.");
    }

    @Override
    public void onActivate() {
        wasPaused = false;
        mineRunning = false;
        phase = Phase.IDLE;
        target = null;
        scanTimer = 10;
        dropTimer = 0;
        blacklist.clear();
        ignoredDrops.clear();

        applyLookSettings();
        if (!columnMode()) startMining();
    }

    @Override
    public void onDeactivate() {
        stopEating();
        restoreLookSettings();
        eatCooldown = 0;
        stopPathing();
        cancelMineProcess();
        wasPaused = false;
        phase = Phase.IDLE;
        target = null;
        blacklist.clear();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null || mc.gameMode == null)
            return;

        boolean pausedThisTick = false;

        // Safety routine (auto-eat takes priority)
        if (autoEat.get() && handleAutoEat()) {
            pausedThisTick = true;
        }

        if (pausedThisTick) {
            wasPaused = true;
            return;
        }

        if (wasPaused) {
            wasPaused = false;
            mineRunning = false;
            if (!columnMode()) startMining();
        }

        if (columnMode()) {
            tickColumns();
        }
    }

    // ---- Column mode ----

    private void tickColumns() {
        switch (phase) {
            case IDLE -> {
                // Every so often, pick up any drops lying around (the bamboo falls while we move on).
                if (collectDrops.get() && ++dropTimer >= dropCheckInterval.get()) {
                    dropTimer = 0;
                    if (findDrop() != null) {
                        beginCollecting();
                        return;
                    }
                }

                if (++scanTimer >= 10) {
                    scanTimer = 0;
                    BlockPos base = findBase();
                    if (base != null) {
                        beginColumn(base);
                        return;
                    }
                }
                // No plant column nearby: let Baritone handle any non-plant blocks that are selected.
                if (!mineRunning) startMining();
            }

            case GOING -> {
                phaseTicks++;
                if (!isSelectedTall(blockAt(target))) {
                    finishColumn();
                    return;
                }
                if (inReach(target)) {
                    stopPathing();
                    phase = Phase.BREAKING;
                    phaseTicks = 0;
                    return;
                }
                if (phaseTicks > 300) giveUp();
            }

            case BREAKING -> {
                phaseTicks++;
                if (!isSelectedTall(blockAt(target))) {
                    finishColumn(); // base is broken; don't wait for the rest to fall
                    return;
                }
                if (phaseTicks > 100) {
                    giveUp();
                    return;
                }
                if (!inReach(target)) {
                    setGoal(new GoalNear(target, 3));
                    phase = Phase.GOING;
                    phaseTicks = 0;
                    return;
                }

                final BlockPos pos = target;
                if (autoTool.get()) swapToBestTool(pos);

                double yaw = Rotations.getYaw(pos);
                double pitch = Rotations.getPitch(pos);
                if (lookAtBlock.get()) { // turn the real camera; Rotations.rotate alone is server-side only
                    mc.player.setYRot((float) yaw);
                    mc.player.setXRot((float) pitch);
                }
                Rotations.rotate(yaw, pitch, () -> BlockUtils.breakBlock(pos, true));
            }

            case COLLECTING -> {
                phaseTicks++;

                ItemEntity drop = findDrop();
                if (drop == null) {
                    if (++emptyTicks >= 5) finishColumn();
                    return;
                }
                emptyTicks = 0;
                lastDropId = drop.getId();

                // Couldn't reach it in time (e.g. stuck behind something): ignore this item from now on.
                if (phaseTicks > 120) {
                    ignoredDrops.add(lastDropId);
                    finishColumn();
                    return;
                }

                if (phaseTicks % 10 == 1) setGoal(new GoalBlock(drop.blockPosition()));
            }
        }
    }

    private void beginCollecting() {
        cancelMineProcess();
        target = null;
        phase = Phase.COLLECTING;
        phaseTicks = 0;
        emptyTicks = 0;
    }

    private void beginColumn(BlockPos base) {
        cancelMineProcess(); // stop Baritone's own mining while we handle this column
        target = base;
        phaseTicks = 0;

        if (inReach(base)) {
            phase = Phase.BREAKING;
        } else {
            phase = Phase.GOING;
            setGoal(new GoalNear(base, 3));
        }
    }

    private void finishColumn() {
        stopPathing();
        phase = Phase.IDLE;
        target = null;
        scanTimer = 10; // look for the next column right away
    }

    private void giveUp() {
        if (target != null) blacklist.add(target.immutable());
        finishColumn();
    }

    /** Nearest selected plant block that has no block of its own kind underneath (the bottom of a column). */
    private BlockPos findBase() {
        BlockPos center = mc.player.blockPosition();
        int r = scanRange.get();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;

        for (BlockPos p : BlockPos.betweenClosed(center.offset(-r, -r, -r), center.offset(r, r, r))) {
            Block block = blockAt(p);
            if (!isSelectedTall(block)) continue;
            if (blockAt(p.below()) == block) continue;
            if (mc.level.getBlockState(p.below()).isAir()) continue; // loose piece of a column that is already falling
            if (blacklist.contains(p)) continue;

            double dist = p.distSqr(center);
            if (dist < bestDist) {
                bestDist = dist;
                best = p.immutable();
            }
        }
        return best;
    }

    private ItemEntity findDrop() {
        AABB box = new AABB(mc.player.blockPosition()).inflate(collectRadius.get());
        ItemEntity best = null;
        double bestDist = Double.MAX_VALUE;

        for (ItemEntity e : mc.level.getEntitiesOfClass(ItemEntity.class, box)) {
            if (!blocksToMine.get().contains(e.getItem().getItem())) continue;
            if (ignoredDrops.contains(e.getId())) continue;

            double dist = e.distanceToSqr(mc.player);
            if (dist < bestDist) {
                bestDist = dist;
                best = e;
            }
        }
        return best;
    }

    private boolean inReach(BlockPos pos) {
        return mc.player.getEyePosition().distanceTo(Vec3.atCenterOf(pos)) <= REACH;
    }

    private Block blockAt(BlockPos pos) {
        return mc.level.getBlockState(pos).getBlock();
    }

    private boolean isTall(Block block) {
        return block == Blocks.BAMBOO || block == Blocks.SUGAR_CANE || block == Blocks.CACTUS;
    }

    private boolean isSelectedTall(Block block) {
        return isTall(block) && blocksToMine.get().contains(block.asItem());
    }

    /** True when bottom-first is on and at least one selected block is a tall plant. */
    private boolean columnMode() {
        if (!bottomFirst.get()) return false;
        for (Item item : blocksToMine.get()) {
            if (item instanceof BlockItem blockItem && isTall(blockItem.getBlock())) return true;
        }
        return false;
    }

    // ---- Tool / look helpers ----

    private void swapToBestTool(BlockPos pos) {
        BlockState state = mc.level.getBlockState(pos);
        int current = VersionUtil.getSelectedSlot(mc.player);
        int bestSlot = current;
        float bestSpeed = toolSpeed(mc.player.getInventory().getItem(current), state);

        for (int i = 0; i < 9; i++) {
            float speed = toolSpeed(mc.player.getInventory().getItem(i), state);
            if (speed > bestSpeed) {
                bestSpeed = speed;
                bestSlot = i;
            }
        }

        if (bestSlot != current) InvUtils.swap(bestSlot, false);
    }

    private float toolSpeed(ItemStack stack, BlockState state) {
        // Don't pick a tool that's one hit from breaking.
        if (stack.isDamageableItem() && stack.getDamageValue() >= stack.getMaxDamage() - 1) return 0f;
        return stack.getDestroySpeed(state);
    }

    /** Makes Baritone turn the real camera when breaking blocks, instead of rotating server-side only. */
    private void applyLookSettings() {
        if (!lookAtBlock.get()) return;
        var settings = BaritoneAPI.getSettings();
        if (savedBlockFreeLook == null) savedBlockFreeLook = settings.blockFreeLook.value;
        settings.blockFreeLook.value = false;
    }

    private void restoreLookSettings() {
        if (savedBlockFreeLook != null) {
            BaritoneAPI.getSettings().blockFreeLook.value = savedBlockFreeLook;
            savedBlockFreeLook = null;
        }
    }

    // ---- Baritone helpers ----

    private void setGoal(baritone.api.pathing.goals.Goal goal) {
        var baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
        if (baritone != null) baritone.getCustomGoalProcess().setGoalAndPath(goal);
    }

    private void stopPathing() {
        var baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
        if (baritone != null) baritone.getPathingBehavior().cancelEverything();
    }

    private void startMining() {
        Block[] selectedBlocks = getSelectedBlocks();
        if (selectedBlocks.length == 0) {
            cancelMineProcess();
            return;
        }

        var baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
        if (baritone != null) {
            try {
                baritone.getMineProcess().mine(selectedBlocks);
            } catch (RuntimeException e) {
                error("Baritone failed to start mining: " + e.getMessage());
            }
            mineRunning = true; // set even on failure so we don't retry (and spam) every tick
        }
    }

    /** Returns true while the module is busy eating (mining stays paused). Never stays true indefinitely. */
    private boolean handleAutoEat() {
        if (eatCooldown > 0) {
            eatCooldown--;
            return false;
        }

        int food = mc.player.getFoodData().getFoodLevel();

        // Not hungry enough, or already full. At 20 nothing can be eaten, which used to pause mining forever.
        if (food > eatAtHunger.get() || food >= 20) {
            stopEating();
            return false;
        }

        // Eating is making progress whenever the food level changes; if it doesn't for a while, give up and mine.
        if (food != lastFood) {
            lastFood = food;
            eatStuckTicks = 0;
        }
        if (++eatStuckTicks > EAT_TIMEOUT) {
            boolean wasUsing = mc.player.isUsingItem();
            stopEating();
            if (wasUsing) mc.gameMode.releaseUsingItem(mc.player);
            eatCooldown = EAT_RETRY_DELAY;
            warning("Auto-eat made no progress, resuming mining and retrying in " + (EAT_RETRY_DELAY / 20) + "s.");
            return false;
        }

        // Already eating: keep the use key held (vanilla cancels eating when it is released) and wait.
        if (mc.player.isUsingItem()) {
            holdUse();
            return true;
        }

        // Only real food that isn't on cooldown.
        FindItemResult foodResult = InvUtils.findInHotbar(stack ->
            foodItems.get().contains(stack.getItem())
                && stack.has(DataComponents.FOOD)
                && !mc.player.getCooldowns().isOnCooldown(stack));
        if (!foodResult.found()) {
            stopEating();
            return false;
        }

        if (foodResult.isOffhand()) {
            mc.gameMode.useItem(mc.player, InteractionHand.OFF_HAND);
        } else {
            if (foodResult.isHotbar() && foodResult.slot() != VersionUtil.getSelectedSlot(mc.player)) {
                InvUtils.swap(foodResult.slot(), false);
            }
            mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
        }

        holdUse();
        mc.player.swing(InteractionHand.MAIN_HAND);
        cancelMineProcess();
        return true;
    }

    private void holdUse() {
        mc.options.keyUse.setDown(true);
        holdingUse = true;
    }

    private void stopEating() {
        if (holdingUse && mc.options != null) {
            mc.options.keyUse.setDown(false);
        }
        holdingUse = false;
        eatStuckTicks = 0;
        lastFood = -1;
    }

    /** Blocks handed to Baritone. In column mode the tall plants are excluded; the module harvests those itself. */
    private Block[] getSelectedBlocks() {
        boolean skipTall = columnMode();
        List<Block> blocks = new ArrayList<>();
        for (Item item : blocksToMine.get()) {
            if (item instanceof BlockItem blockItem) {
                if (skipTall && isTall(blockItem.getBlock())) continue;
                blocks.add(blockItem.getBlock());
            }
        }
        return blocks.toArray(Block[]::new);
    }

    private void cancelMineProcess() {
        mineRunning = false;
        if (BaritoneAPI.getProvider().getPrimaryBaritone() != null) {
            BaritoneAPI.getProvider().getPrimaryBaritone().getMineProcess().cancel();
        }
    }
}