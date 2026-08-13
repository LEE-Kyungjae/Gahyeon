#include "Features/IModularFeatures.h"
#include "Interfaces/IPluginManager.h"
#include "LookingGlass/GahyeonLookingGlassAttestation.h"
#include "Misc/AutomationTest.h"
#include "Misc/CommandLine.h"
#include "Misc/Parse.h"
#include "ModuleDescriptor.h"
#include "Modules/ModuleManager.h"

IMPLEMENT_SIMPLE_AUTOMATION_TEST(
    FGahyeonLookingGlassProfileTest,
    "Gahyeon.LookingGlass.PluginAvailableWhenRequired",
    EAutomationTestFlags::EditorContext | EAutomationTestFlags::EngineFilter)

bool FGahyeonLookingGlassProfileTest::RunTest(const FString& Parameters)
{
    const bool bRequired = FParse::Param(FCommandLine::Get(), TEXT("GahyeonRequireLookingGlass"));
    const TSharedPtr<IPlugin> Plugin = IPluginManager::Get().FindPlugin(TEXT("LookingGlass"));

    if (!bRequired)
    {
        TestTrue(TEXT("The canonical Stage does not require Looking Glass"), true);
        return true;
    }

    TestTrue(TEXT("Pinned Looking Glass plugin is installed"), Plugin.IsValid());
    if (Plugin.IsValid())
    {
        TestTrue(TEXT("Go project profile enables Looking Glass"), Plugin->IsEnabled());
        bool bHasRuntimeModule = false;
        for (const FModuleDescriptor& Module : Plugin->GetDescriptor().Modules)
        {
            if (Module.Name == TEXT("LookingGlassRuntime"))
            {
                bHasRuntimeModule = true;
                break;
            }
        }
        TestTrue(TEXT("Looking Glass runtime module exists"), bHasRuntimeModule);
    }
    TestTrue(TEXT("Gahyeon Looking Glass adapter module is loaded"),
        FModuleManager::Get().IsModuleLoaded(TEXT("GahyeonLookingGlassAdapter")));
    const TArray<IGahyeonLookingGlassAttestationProvider*> Providers =
        IModularFeatures::Get().GetModularFeatureImplementations<
            IGahyeonLookingGlassAttestationProvider>(
                IGahyeonLookingGlassAttestationProvider::GetModularFeatureName());
    TestEqual(TEXT("Exactly one Looking Glass attestation provider is registered"),
        Providers.Num(), 1);
    return !HasAnyErrors();
}
