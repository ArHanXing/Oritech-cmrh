package rearth.oritech.init.recipes;

import dev.architectury.fluid.FluidStack;
import org.jetbrains.annotations.Nullable;
import rearth.oritech.util.FluidIngredient;
import rearth.oritech.util.SimpleCraftingInventory;
import rearth.oritech.util.SizedIngredient;

import java.util.Arrays;
import java.util.List;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeInput;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluid;

public class OritechRecipe implements Recipe<RecipeInput> {
    
    protected final OritechRecipeType type;
    protected final List<SizedIngredient> inputs;
    protected final List<ItemStack> results;
    protected final FluidIngredient fluidInput;
    protected final List<FluidStack> fluidOutputs;
    protected final int time;

    public static final OritechRecipe DUMMY = new OritechRecipe(-1, List.of(new SizedIngredient(1, Ingredient.of(Items.IRON_INGOT.getDefaultInstance()))), NonNullList.withSize(1, Items.IRON_BLOCK.getDefaultInstance()), RecipeContent.PULVERIZER, FluidIngredient.EMPTY, FluidStack.empty());
    
    public OritechRecipe(int time, List<SizedIngredient> inputs, List<ItemStack> results, OritechRecipeType type, @Nullable FluidIngredient fluidInput, @Nullable FluidStack fluidOutput) {
        this(time, inputs, results, type, fluidInput, (fluidOutput == null || fluidOutput.isEmpty()) ? List.of() : List.of(fluidOutput));
    }
    public OritechRecipe(int time, List<SizedIngredient> inputs, List<ItemStack> results, OritechRecipeType type, @Nullable FluidIngredient fluidInput, List<FluidStack> fluidOutputs) {
        this.type = type;
        this.results = results;
        this.inputs = inputs;
        this.time = time;
        if (fluidInput == null) fluidInput = FluidIngredient.EMPTY;
        this.fluidInput = fluidInput.withAmount(fluidInput.amount());
        this.fluidOutputs = fluidOutputs;
    }

    public OritechRecipe(int time, List<SizedIngredient> inputs, List<ItemStack> results, OritechRecipeType type, @Nullable FluidIngredient fluidInput, Fluid outVariant, long outAmount) {
        this(time, inputs, results, type, fluidInput, FluidStack.create(outVariant, outAmount));
    }
    
    public OritechRecipe(int time, List<SizedIngredient> inputs, List<ItemStack> results, OritechRecipeType type, Fluid inVariant, long inAmount, Fluid outVariant, long outAmount) {
        this(time, inputs, results, type, new FluidIngredient().withContent(inVariant).withAmount(inAmount), FluidStack.create(outVariant, outAmount));
    }
    
    
    @Override
    public boolean matches(RecipeInput input, Level world) {
        
        if (world.isClientSide) return false;
        
        // recipes that need more than one item (or more than one stack) may draw from any input slot
        if (inputs.size() > 1 || isSized()) {
            return complexMatch(input);
        }
        
        if (input.size() < inputs.size()) return false;
        
        var ingredients = getInputs();
        for (int i = 0; i < ingredients.size(); i++) {
            var entry = ingredients.get(i);
            if (!entry.test(input.getItem(i))) {
                return false;
            }
        }
        
        return true;
    }
    
    private boolean complexMatch(RecipeInput input) {
        
        if (!(input instanceof SimpleCraftingInventory simpleInventory)) return false;
        
        // Input does not need to be in the correct slots / split into different slots.
        // We just check if we can remove all ingredients from the inventory, and fail is any input is not able to be removed.
        var copiedInv = simpleInventory.getItems().stream().map(ItemStack::copy).toArray(ItemStack[]::new);
        
        for (var ingredient : getInputs()) {
            if (!ingredient.consume(Arrays.asList(copiedInv), true))
                return false;
        }
        
        return true;
    }
    
    @Override
    public ItemStack assemble(RecipeInput input, HolderLookup.Provider lookup) {
        return null;
    }
    
    @Override
    public boolean canCraftInDimensions(int width, int height) {
        return true;
    }
    
    @Override
    public ItemStack getResultItem(HolderLookup.Provider registriesLookup) {
        return ItemStack.EMPTY;
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return type;
    }
    
    @Override
    public boolean isSpecial() {
        return true;
    }
    
    @Override
    public RecipeType<?> getType() {
        return type;
    }
    
    @Override
    public String toString() {
        return "OritechRecipe{" +
                 "type=" + type +
                 ", inputs=" + inputs +
                 ", results=" + results +
                 ", fluidInput=" + fluidInput +
                 ", time=" + time +
                 '}';
    }
    
    public int getTime() {
        return time;
    }
    
    public List<SizedIngredient> getInputs() {
        return inputs;
    }
    
    /** Plain ingredients, for recipe viewers and other places that do not care about counts. */
    public List<Ingredient> getInputIngredients() {
        return inputs.stream().map(SizedIngredient::ingredient).toList();
    }
    
    /** True when any input needs more than a single item. */
    public boolean isSized() {
        for (var input : inputs) {
            if (input.count() > 1) return true;
        }
        return false;
    }

    // do not use this one, use getInputs if applicable to avoid unnecessary copy
    @Override
    public NonNullList<Ingredient> getIngredients() {
        return NonNullList.of(Ingredient.EMPTY, getInputIngredients().toArray(Ingredient[]::new));
    }

    public List<ItemStack> getResults() {
        return results;
    }

    public OritechRecipeType getOriType() {
        return type;
    }
    
    public @Nullable FluidIngredient getFluidInput() {
        return fluidInput;
    }
    
    public List<FluidStack> getFluidOutputs() {
        return fluidOutputs;
    }
}
