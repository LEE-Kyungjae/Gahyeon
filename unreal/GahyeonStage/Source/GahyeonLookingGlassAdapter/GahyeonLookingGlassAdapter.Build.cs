using UnrealBuildTool;

public class GahyeonLookingGlassAdapter : ModuleRules
{
    public GahyeonLookingGlassAdapter(ReadOnlyTargetRules Target) : base(Target)
    {
        if (Target.Platform != UnrealTargetPlatform.Win64)
        {
            throw new BuildException("GahyeonLookingGlassAdapter is an opt-in Win64 module");
        }

        PCHUsage = PCHUsageMode.UseExplicitOrSharedPCHs;

        PrivateDependencyModuleNames.AddRange(new[]
        {
            "Core",
            "CoreUObject",
            "Engine",
            "GahyeonStage",
            "LookingGlassRuntime"
        });
    }
}
