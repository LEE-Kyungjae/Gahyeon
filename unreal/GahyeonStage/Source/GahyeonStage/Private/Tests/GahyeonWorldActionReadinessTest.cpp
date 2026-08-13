#if WITH_DEV_AUTOMATION_TESTS

#include "Misc/AutomationTest.h"
#include "World/GahyeonWorldActionComponent.h"

IMPLEMENT_SIMPLE_AUTOMATION_TEST(
    FGahyeonWorldActionReadinessTest,
    "Gahyeon.World.ClassifiesNavigationReadinessWithoutMaskingMissingDependencies",
    EAutomationTestFlags::EditorContext | EAutomationTestFlags::EngineFilter)

bool FGahyeonWorldActionReadinessTest::RunTest(const FString& Parameters)
{
    (void)Parameters;
    using Readiness = EGahyeonNavigationReadiness;
    TestTrue(TEXT("custom locomotion does not require NavMesh"),
        UGahyeonWorldActionComponent::ClassifyNavigationReadiness(
            false, false, false, false, false) == Readiness::CustomLocomotion);
    TestTrue(TEXT("missing pawn is visible"),
        UGahyeonWorldActionComponent::ClassifyNavigationReadiness(
            true, false, false, false, false) == Readiness::PawnUnavailable);
    TestTrue(TEXT("missing controller is visible"),
        UGahyeonWorldActionComponent::ClassifyNavigationReadiness(
            true, true, false, false, false) == Readiness::ControllerUnavailable);
    TestTrue(TEXT("missing navigation system is visible"),
        UGahyeonWorldActionComponent::ClassifyNavigationReadiness(
            true, true, true, false, false) == Readiness::NavigationSystemUnavailable);
    TestTrue(TEXT("missing baked navigation data is visible"),
        UGahyeonWorldActionComponent::ClassifyNavigationReadiness(
            true, true, true, true, false) == Readiness::NavigationDataUnavailable);
    TestTrue(TEXT("all automatic navigation dependencies are ready"),
        UGahyeonWorldActionComponent::ClassifyNavigationReadiness(
            true, true, true, true, true) == Readiness::Ready);
    return true;
}

#endif
