#if WITH_DEV_AUTOMATION_TESTS

#include "Misc/AutomationTest.h"
#include "World/GahyeonWorldCoordinateAdapter.h"

IMPLEMENT_SIMPLE_AUTOMATION_TEST(
    FGahyeonWorldCoordinateAdapterTest,
    "Gahyeon.World.MapsCoreMetresAndAxesToUnrealCentimetres",
    EAutomationTestFlags::EditorContext | EAutomationTestFlags::EngineFilter)

bool FGahyeonWorldCoordinateAdapterTest::RunTest(const FString& Parameters)
{
    (void)Parameters;
    const Gahyeon::WorldPosition CoreDesk{.X = 7.0, .Y = 0.0, .Z = -2.0};
    const FVector UnrealDesk =
        FGahyeonWorldCoordinateAdapter::ToUnrealCentimeters(CoreDesk);
    TestEqual(TEXT("Core X maps to Unreal X in centimetres"), UnrealDesk.X, 700.0);
    TestEqual(TEXT("Core depth maps to Unreal Y in centimetres"), UnrealDesk.Y, -200.0);
    TestEqual(TEXT("Core elevation maps to Unreal Z in centimetres"), UnrealDesk.Z, 0.0);

    const Gahyeon::WorldPosition RoundTrip =
        FGahyeonWorldCoordinateAdapter::ToCoreMeters(FVector(125.0, -350.0, 80.0));
    TestEqual(TEXT("Unreal X round-trips to Core X"), RoundTrip.X, 1.25);
    TestEqual(TEXT("Unreal Z becomes Core elevation"), RoundTrip.Y, 0.8);
    TestEqual(TEXT("Unreal Y becomes Core depth"), RoundTrip.Z, -3.5);
    return true;
}

#endif
