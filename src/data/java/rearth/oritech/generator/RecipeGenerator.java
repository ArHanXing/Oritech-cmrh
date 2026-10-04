package rearth.oritech.generator;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import net.fabricmc.fabric.api.datagen.v1.FabricDataOutput;
import net.fabricmc.fabric.api.datagen.v1.provider.FabricRecipeProvider;
import net.fabricmc.fabric.impl.resource.conditions.conditions.AllModsLoadedResourceCondition;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.recipes.RecipeOutput;
import rearth.oritech.api.recipe.OritechRecipeGenerator;
import rearth.oritech.generator.compat.TechRebornRecipeGenerator;
import techreborn.TechReborn;

public class RecipeGenerator extends FabricRecipeProvider {
    private final FabricDataOutput output;
    private final CompletableFuture<HolderLookup.Provider> registriesFuture;
    
    public RecipeGenerator(FabricDataOutput output, CompletableFuture<HolderLookup.Provider> registriesFuture) {
        super(output, registriesFuture);
        this.output = output;
        this.registriesFuture = registriesFuture;
    }
    
    @Override
    public void buildRecipes(RecipeOutput exporter) {
        var oritechRecipes = new OritechRecipeGenerator(output, registriesFuture);
        oritechRecipes.buildRecipes(exporter);
        
        // Tech Reborn is the only supported mod compat
        TechRebornRecipeGenerator.generateRecipes(this.withConditions(exporter, new AllModsLoadedResourceCondition(List.of(TechReborn.MOD_ID))));
    }
}
