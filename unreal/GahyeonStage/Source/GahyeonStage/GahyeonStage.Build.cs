using UnrealBuildTool;

public class GahyeonStage : ModuleRules
{
    public GahyeonStage(ReadOnlyTargetRules Target) : base(Target)
    {
        PCHUsage = PCHUsageMode.UseExplicitOrSharedPCHs;

        PublicDependencyModuleNames.AddRange(new[]
        {
            "Core",
            "CoreUObject",
            "DeveloperSettings",
            "Engine",
            "GahyeonRuntimeCore"
        });

        PrivateDependencyModuleNames.AddRange(new[]
        {
            "AIModule",
            "AudioCaptureCore",
            "EnhancedInput",
            "HTTP",
            "Json",
            "JsonUtilities",
            "NavigationSystem",
            "Projects",
            "WebSockets"
        });
    }
}
