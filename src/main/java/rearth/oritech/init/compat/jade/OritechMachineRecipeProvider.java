package rearth.oritech.init.compat.jade;

import dev.architectury.fluid.FluidStack;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluids;
import rearth.oritech.Oritech;
import rearth.oritech.block.base.entity.MachineBlockEntity;
import rearth.oritech.block.base.entity.MultiblockMachineEntity;
import rearth.oritech.block.base.entity.UpgradableGeneratorBlockEntity;
import rearth.oritech.block.base.entity.UpgradableMachineBlockEntity;
import rearth.oritech.init.recipes.OritechRecipe;
import rearth.oritech.util.MachineAddonController;
import rearth.oritech.util.MultiblockMachineController;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.IServerDataProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.config.IPluginConfig;
import snownee.jade.api.fluid.JadeFluidObject;
import snownee.jade.api.ui.IElement;
import snownee.jade.api.ui.IElementHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * Jade provider showing what an Oritech machine is currently working on, in the
 * spirit of the HXReborn TechReborn Jade plugin:
 * <ul>
 *     <li>recipe inputs (item icons + names + counts, plus fluid input)</li>
 *     <li>recipe outputs (item icons + names + counts, plus fluid outputs)</li>
 *     <li>progress bar and elapsed / total ticks</li>
 *     <li>energy usage (or generation) per tick</li>
 *     <li>addon speed / efficiency, extra processing chambers, core quality and burst state</li>
 * </ul>
 * The recipe itself is not synced to clients that are not looking at the machine,
 * so all state is collected server side on demand through {@link IServerDataProvider}
 * (Jade polls this every 250ms while a player looks at the block).
 */
public enum OritechMachineRecipeProvider implements IBlockComponentProvider, IServerDataProvider<BlockAccessor> {

    INSTANCE;

    private static final ResourceLocation ID = Oritech.id("machine_recipe");

    // root tags
    private static final String STATS = "oritech_jade_stats";
    private static final String RECIPE = "oritech_jade_recipe";

    // stats keys
    private static final String STATS_ENERGY = "energy";
    private static final String STATS_SPEED = "speed";
    private static final String STATS_EFFICIENCY = "efficiency";
    private static final String STATS_GENERATOR = "generator";
    private static final String STATS_STEAM = "steam";
    private static final String STATS_CHAMBERS = "chambers";
    private static final String STATS_CHAMBERS_APPLY = "chambers_apply";
    private static final String STATS_CORE = "core";
    private static final String STATS_BURST = "burst";
    private static final String STATS_ASSEMBLED = "assembled";

    // recipe keys
    private static final String RECIPE_INPUTS = "inputs";
    private static final String RECIPE_OUTPUTS = "outputs";
    private static final String RECIPE_FLUID_IN = "fluid_in";
    private static final String RECIPE_FLUID_OUT = "fluid_out";
    private static final String RECIPE_TOTAL = "total";
    private static final String RECIPE_ELAPSED = "elapsed";
    private static final String RECIPE_FRACTION = "fraction";

    // fluid keys
    private static final String FLUID_ID = "id";
    private static final String FLUID_AMOUNT = "amount";

    // ---- server side: gather and sync machine state ----

    @Override
    public void appendServerData(CompoundTag data, BlockAccessor accessor) {

        if (!(accessor.getBlockEntity() instanceof MachineBlockEntity machine)) return;

        var stats = new CompoundTag();
        stats.putFloat(STATS_ENERGY, machine.getDisplayedEnergyUsage());

        // use the base addon multipliers here (without the temporary burst bonus,
        // which is reported separately), so the numbers match the machine GUI
        var addonData = machine instanceof MachineAddonController addonController
          ? addonController.getBaseAddonData() : null;
        stats.putFloat(STATS_SPEED, addonData != null ? addonData.speed() : machine.getSpeedMultiplier());
        stats.putFloat(STATS_EFFICIENCY, addonData != null ? addonData.efficiency() : machine.getEfficiencyMultiplier());

        var generator = machine instanceof UpgradableGeneratorBlockEntity;
        stats.putBoolean(STATS_GENERATOR, generator);
        if (machine instanceof UpgradableGeneratorBlockEntity generatorEntity)
            stats.putBoolean(STATS_STEAM, generatorEntity.isProducingSteam);

        if (addonData != null)
            stats.putInt(STATS_CHAMBERS, addonData.extraChambers());

        var chambersApply = machine instanceof UpgradableMachineBlockEntity upgradable && upgradable.supportExtraChambersAuto();
        stats.putBoolean(STATS_CHAMBERS_APPLY, chambersApply);

        if (machine instanceof MultiblockMachineController multiblock)
            stats.putFloat(STATS_CORE, multiblock.getCoreQuality());
        // multiblock machines only work once all core blocks are placed
        if (machine instanceof MultiblockMachineEntity)
            stats.putBoolean(STATS_ASSEMBLED, machine.isActive(accessor.getBlockState()));

        if (machine instanceof UpgradableMachineBlockEntity upgradable) {
            if (upgradable.isBurstThrottled()) stats.putInt(STATS_BURST, 2);
            else if (upgradable.isBurstAvailable()) stats.putInt(STATS_BURST, 1);
        }

        data.put(STATS, stats);

        var recipe = machine.getCurrentRecipe();
        if (recipe == null || recipe == OritechRecipe.DUMMY) return;

        var lookup = accessor.getLevel().registryAccess();
        var recipeTag = new CompoundTag();

        // item inputs: one representative stack per ingredient
        var inputs = new ListTag();
        for (var ingredient : recipe.getInputs()) {
            var matching = ingredient.getItems();
            if (matching.length == 0) continue;
            inputs.add(matching[0].copyWithCount(1).save(lookup));
        }
        recipeTag.put(RECIPE_INPUTS, inputs);

        // item outputs: use the machine's own result calculation so that yield
        // bonuses (fragment forge byproducts, refinery modules, ...) are included
        var outputs = new ListTag();
        for (var result : machine.getCraftingResults(recipe)) {
            if (result == null || result.isEmpty()) continue;
            outputs.add(result.copy().save(lookup));
        }
        recipeTag.put(RECIPE_OUTPUTS, outputs);

        // fluid input: tag ingredients are resolved to their first matching fluid
        var fluidInput = recipe.getFluidInput();
        if (fluidInput != null && fluidInput.amount() > 0) {
            var candidates = fluidInput.getFluidStacks();
            if (!candidates.isEmpty() && !candidates.getFirst().isEmpty())
                recipeTag.put(RECIPE_FLUID_IN, writeFluid(candidates.getFirst()));
        }

        var fluidOutputs = new ListTag();
        for (var fluidStack : recipe.getFluidOutputs()) {
            if (fluidStack == null || fluidStack.isEmpty()) continue;
            fluidOutputs.add(writeFluid(fluidStack));
        }
        recipeTag.put(RECIPE_FLUID_OUT, fluidOutputs);

        // generators use the progress field as remaining burn time
        int total;
        int elapsed;
        if (machine instanceof UpgradableGeneratorBlockEntity generatorEntity) {
            total = Math.max(1, generatorEntity.getCurrentMaxBurnTime());
            elapsed = (int) Math.clamp((long) total - machine.progress, 0, total);
        } else {
            total = Math.max(1, Math.round(recipe.getTime() * machine.getSpeedMultiplier()));
            elapsed = (int) Math.clamp((long) machine.progress, 0, total);
        }
        recipeTag.putInt(RECIPE_TOTAL, total);
        recipeTag.putInt(RECIPE_ELAPSED, elapsed);

        var fraction = machine.getProgress();
        if (!Float.isFinite(fraction)) fraction = 0f;
        recipeTag.putFloat(RECIPE_FRACTION, Math.clamp(fraction, 0f, 1f));

        data.put(RECIPE, recipeTag);
    }

    private static CompoundTag writeFluid(FluidStack stack) {
        var tag = new CompoundTag();
        tag.putString(FLUID_ID, BuiltInRegistries.FLUID.getKey(stack.getFluid()).toString());
        tag.putLong(FLUID_AMOUNT, stack.getAmount());
        return tag;
    }

    // ---- client side: render ----

    @Override
    public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {

        var data = accessor.getServerData();
        if (!data.contains(STATS)) return;

        var stats = data.getCompound(STATS);
        var helper = IElementHelper.get();
        var generator = stats.getBoolean(STATS_GENERATOR);

        // multiblock machines need all core blocks before they do anything
        if (stats.contains(STATS_ASSEMBLED) && !stats.getBoolean(STATS_ASSEMBLED))
            tooltip.add(Component.translatable("message.oritech.machine.missing_core").withStyle(ChatFormatting.RED));

        // when a recipe is running, show what it consumes and produces
        if (data.contains(RECIPE)) {
            var recipe = data.getCompound(RECIPE);

            // hold shift to see the amounts of a single craft instead of the
            // full batch (extra processing chambers multiply every craft)
            var chambers = stats.getBoolean(STATS_CHAMBERS_APPLY) ? stats.getInt(STATS_CHAMBERS) : 0;
            var sneaking = accessor.getPlayer() != null && accessor.getPlayer().isShiftKeyDown();
            var batch = sneaking ? 1 : 1 + chambers;

            var inputs = new ArrayList<IElement>();
            inputs.add(label(helper, "jade.oritech.recipe.input"));
            appendStacks(inputs, helper, accessor, recipe.getList(RECIPE_INPUTS, Tag.TAG_COMPOUND), batch);
            if (recipe.contains(RECIPE_FLUID_IN))
                appendFluid(inputs, helper, recipe.getCompound(RECIPE_FLUID_IN), batch);
            if (inputs.size() > 1) tooltip.add(inputs);

            var outputs = new ArrayList<IElement>();
            outputs.add(label(helper, "jade.oritech.recipe.output"));
            appendStacks(outputs, helper, accessor, recipe.getList(RECIPE_OUTPUTS, Tag.TAG_COMPOUND), batch);
            for (var element : recipe.getList(RECIPE_FLUID_OUT, Tag.TAG_COMPOUND))
                appendFluid(outputs, helper, (CompoundTag) element, batch);
            if (outputs.size() > 1) tooltip.add(outputs);

            var fraction = recipe.getFloat(RECIPE_FRACTION);
            tooltip.add(helper.progress(fraction));

            var elapsed = recipe.getInt(RECIPE_ELAPSED);
            var total = recipe.getInt(RECIPE_TOTAL);
            if (total > 1) {
                tooltip.add(Component.translatable("jade.oritech.recipe.progress",
                  highlighted(String.valueOf(elapsed)), highlighted(String.valueOf(total))));
            } else {
                tooltip.add(Component.translatable("jade.oritech.recipe.progress_percent",
                  highlighted(String.valueOf(Math.round(fraction * 100f)))));
            }
        }

        // energy: consumption for machines, production for generators
        var energy = stats.getFloat(STATS_ENERGY);
        if (energy > 0) {
            if (generator && stats.getBoolean(STATS_STEAM)) {
                tooltip.add(Component.translatable("jade.oritech.recipe.steam",
                  highlighted(formatNumber(energy))));
            } else {
                var key = generator ? "jade.oritech.recipe.generation" : "jade.oritech.recipe.energy";
                tooltip.add(Component.translatable(key, highlighted(formatNumber(energy))));
            }
        }

        // addon stats: only shown when they actually differ from the defaults
        var speed = stats.getFloat(STATS_SPEED);
        if (speed > 0f && Math.abs(speed - 1f) > 0.01f)
            tooltip.add(Component.translatable("jade.oritech.recipe.speed",
              highlighted(String.valueOf(Math.round(100f / speed / 5f) * 5))));

        var efficiency = stats.getFloat(STATS_EFFICIENCY);
        if (efficiency > 0f && Math.abs(efficiency - 1f) > 0.03f) {
            var efficiencyText = efficiency > 1f
              ? "-" + Math.round((efficiency - 1f) * 100f / 5f) * 5
              : "+" + Math.round((1f / efficiency - 1f) * 100f / 5f) * 5;
            tooltip.add(Component.translatable("jade.oritech.recipe.efficiency", highlighted(efficiencyText)));
        }

        var chambers = stats.getInt(STATS_CHAMBERS);
        if (chambers > 0)
            tooltip.add(Component.translatable("jade.oritech.recipe.chambers", highlighted("+" + chambers)));

        if (stats.contains(STATS_CORE))
            tooltip.add(Component.translatable("jade.oritech.recipe.core",
              highlighted(String.valueOf(stats.getFloat(STATS_CORE)))));

        switch (stats.getInt(STATS_BURST)) {
            case 1 -> tooltip.add(Component.translatable("jade.oritech.recipe.burst",
              Component.translatable("jade.oritech.recipe.burst.ready").withStyle(ChatFormatting.GREEN)));
            case 2 -> tooltip.add(Component.translatable("jade.oritech.recipe.burst",
              Component.translatable("jade.oritech.recipe.burst.throttled").withStyle(ChatFormatting.RED)));
            default -> {
            }
        }
    }

    private static IElement label(IElementHelper helper, String key) {
        return helper.text(Component.translatable(key).withStyle(ChatFormatting.GRAY));
    }

    private static Component highlighted(String value) {
        return Component.literal(value).withStyle(ChatFormatting.YELLOW);
    }

    private static void appendStacks(List<IElement> line, IElementHelper helper, BlockAccessor accessor,
                                     ListTag stacks, int batch) {
        for (var element : stacks) {
            if (!(element instanceof CompoundTag tag)) continue;
            var parsed = ItemStack.parse(accessor.getLevel().registryAccess(), tag);
            if (parsed.isEmpty()) continue;
            var stack = parsed.get();
            line.add(helper.item(stack));
            line.add(helper.text(Component.literal(" " + stack.getHoverName().getString()
                                                     + " x" + stack.getCount() * batch).withStyle(ChatFormatting.WHITE)));
        }
    }

    private static void appendFluid(List<IElement> line, IElementHelper helper, CompoundTag tag, int batch) {
        var id = ResourceLocation.tryParse(tag.getString(FLUID_ID));
        if (id == null) return;
        var fluid = BuiltInRegistries.FLUID.get(id);
        var amount = tag.getLong(FLUID_AMOUNT) * batch;
        if (fluid == null || fluid == Fluids.EMPTY || amount <= 0) return;

        // the fluid name is resolved on the client, so it is localized
        var name = FluidStack.create(fluid, amount).getName().getString();
        line.add(helper.fluid(JadeFluidObject.of(fluid, amount)));
        line.add(helper.text(Component.literal(" " + name + " " + amount + " mB").withStyle(ChatFormatting.WHITE)));
    }

    /** Formats an RF/t style value, dropping the decimals for whole numbers. */
    private static String formatNumber(float value) {
        if (Math.abs(value - Math.round(value)) < 0.001f) return String.valueOf(Math.round(value));
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }

    @Override
    public ResourceLocation getUid() {
        return ID;
    }
}
