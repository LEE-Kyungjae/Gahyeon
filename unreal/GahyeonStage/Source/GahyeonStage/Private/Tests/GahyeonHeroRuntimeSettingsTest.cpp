#include "Misc/AutomationTest.h"

#include "Character/GahyeonCharacterPawn.h"
#include "Character/GahyeonStageGameMode.h"
#include "HAL/PlatformMisc.h"

#if WITH_DEV_AUTOMATION_TESTS

IMPLEMENT_SIMPLE_AUTOMATION_TEST(FGahyeonHeroRuntimeSettingsTest,
    "Gahyeon.Hero.RuntimeClassBoundary",
    EAutomationTestFlags::EditorContext | EAutomationTestFlags::EngineFilter)

bool FGahyeonHeroRuntimeSettingsTest::RunTest(const FString& Parameters)
{
    FString Error;
    TestFalse(TEXT("empty setting preserves the source shell"),
        AGahyeonStageGameMode::ResolveHeroPawnClass(FSoftClassPath(), Error) != nullptr);
    TestTrue(TEXT("empty setting is not an error"), Error.IsEmpty());

    const FSoftClassPath ValidPath(AGahyeonCharacterPawn::StaticClass());
    const TSubclassOf<AGahyeonCharacterPawn> Valid =
        AGahyeonStageGameMode::ResolveHeroPawnClass(ValidPath, Error);
    TestTrue(TEXT("Gahyeon pawn subclasses are admitted"), Valid != nullptr);
    TestTrue(TEXT("valid class has no error"), Error.IsEmpty());

    const FSoftClassPath WrongBase(TEXT("/Script/Engine.Actor"));
    TestFalse(TEXT("generic Actor cannot replace the Hero pawn"),
        AGahyeonStageGameMode::ResolveHeroPawnClass(WrongBase, Error) != nullptr);
    TestFalse(TEXT("wrong base reports a reason"), Error.IsEmpty());

    const FString HeroGate = FPlatformMisc::GetEnvironmentVariable(TEXT("GAHYEON_HERO_MANIFEST"));
    const FSoftClassPath InstalledHero(
        TEXT("/Game/GahyeonGenerated/Characters/Gahyeon.Gahyeon_C"));
    FString InstalledError;
    const TSubclassOf<AGahyeonCharacterPawn> Installed =
        AGahyeonStageGameMode::ResolveHeroPawnClass(InstalledHero, InstalledError);
    if (!HeroGate.IsEmpty())
    {
        TestTrue(TEXT("manifest-gated Hero class must load"), Installed != nullptr);
        if (Installed != nullptr)
        {
            TestTrue(TEXT("installed Hero must preserve body/AnimInstance/Profile wiring"),
                AGahyeonStageGameMode::ValidateHeroRuntimeContract(Installed, InstalledError));
        }
        if (!InstalledError.IsEmpty()) AddError(InstalledError);
    }
    return true;
}

#endif
