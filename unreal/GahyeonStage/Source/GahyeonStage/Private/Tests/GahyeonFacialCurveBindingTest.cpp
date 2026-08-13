#include "Misc/AutomationTest.h"
#include "Animation/GahyeonCharacterAnimInstance.h"
#include "Presentation/GahyeonCharacterPresentationProfile.h"
#include "Presentation/GahyeonCharacterPresentationComponent.h"

#if WITH_DEV_AUTOMATION_TESTS

IMPLEMENT_SIMPLE_AUTOMATION_TEST(
    FGahyeonFacialCurveBindingTest,
    "Gahyeon.Presentation.FacialCurveBindingsAreDataDrivenAndBounded",
    EAutomationTestFlags::EditorContext | EAutomationTestFlags::EngineFilter)

bool FGahyeonFacialCurveBindingTest::RunTest(const FString& Parameters)
{
    (void)Parameters;
    UGahyeonCharacterPresentationProfile* Profile =
        NewObject<UGahyeonCharacterPresentationProfile>();
    Profile->BlinkScale = 2.0;
    Profile->LeftBlinkCurve = TEXT("CTRL_expressions_eyeBlinkL");
    Profile->RightBlinkCurve = TEXT("CTRL_expressions_eyeBlinkR");
    Profile->JawOpenCurve = TEXT("CTRL_expressions_jawOpenFallback");

    FGahyeonFacialCurveBinding Happy;
    Happy.Semantic = TEXT("happy");
    Happy.CurveName = TEXT("CTRL_expressions_mouthSmileL");
    Happy.Scale = 0.5;
    Profile->EmotionCurves.Add(Happy);
    FGahyeonFacialCurveBinding HappyRight = Happy;
    HappyRight.CurveName = TEXT("CTRL_expressions_mouthSmileR");
    HappyRight.Scale = 0.75;
    Profile->EmotionCurves.Add(HappyRight);
    FGahyeonFacialCurveBinding Ah;
    Ah.Semantic = TEXT("aa");
    Ah.CurveName = TEXT("CTRL_expressions_jawOpen");
    Ah.Scale = 1.0;
    Profile->VisemeCurves.Add(Ah);

    TMap<FName, double> Emotions;
    Emotions.Add(TEXT("happy"), 0.8);
    TMap<FName, float> Weights;
    Profile->ResolveFacialCurveWeights(
        Emotions, TEXT("aa"), 1.4, NAME_None, 0.0, 0.35, 0.75, Weights);

    TestEqual(TEXT("emotion uses profile scale"),
        Weights.FindRef(TEXT("CTRL_expressions_mouthSmileL")), 0.4f);
    TestEqual(TEXT("one semantic may drive multiple local curves"),
        Weights.FindRef(TEXT("CTRL_expressions_mouthSmileR")), 0.6f);
    TestEqual(TEXT("viseme clamps to one"),
        Weights.FindRef(TEXT("CTRL_expressions_jawOpen")), 1.0f);
    TestEqual(TEXT("left blink clamps after scale"),
        Weights.FindRef(TEXT("CTRL_expressions_eyeBlinkL")), 1.0f);
    TestEqual(TEXT("right blink clamps after scale"),
        Weights.FindRef(TEXT("CTRL_expressions_eyeBlinkR")), 1.0f);
    TestEqual(TEXT("amplitude jaw fallback reaches its local curve"),
        Weights.FindRef(TEXT("CTRL_expressions_jawOpenFallback")), 0.35f);

    TestTrue(TEXT("explicit left blink semantic resolves"),
        Profile->ResolveFacialSemanticWeights(
            TEXT("direct"), TEXT("blink-left"), 0.8, Weights));
    TestEqual(TEXT("left blink semantic drives only its curve"), Weights.Num(), 1);
    TestEqual(TEXT("left blink semantic preserves requested weight"),
        Weights.FindRef(TEXT("CTRL_expressions_eyeBlinkL")), 0.8f);
    TestFalse(TEXT("left blink never leaks to right blink"),
        Weights.Contains(TEXT("CTRL_expressions_eyeBlinkR")));
    TestTrue(TEXT("explicit right blink semantic resolves"),
        Profile->ResolveFacialSemanticWeights(
            TEXT("direct"), TEXT("blink-right"), 0.6, Weights));
    TestEqual(TEXT("right blink semantic drives only its curve"), Weights.Num(), 1);
    TestFalse(TEXT("right blink never leaks to left blink"),
        Weights.Contains(TEXT("CTRL_expressions_eyeBlinkL")));
    TestTrue(TEXT("explicit viseme uses the same data binding"),
        Profile->ResolveFacialSemanticWeights(TEXT("viseme"), TEXT("aa"), 0.5, Weights));
    TestEqual(TEXT("explicit viseme preserves binding scale"),
        Weights.FindRef(TEXT("CTRL_expressions_jawOpen")), 0.5f);
    TestFalse(TEXT("unknown explicit semantic fails closed"),
        Profile->ResolveFacialSemanticWeights(TEXT("viseme"), TEXT("missing"), 1.0, Weights));
    Profile->VisemeCurves[0].Target = EGahyeonFacialTarget::ControlRig;
    TestEqual(TEXT("profile exposes Control Rig routing"),
        Profile->ResolveFacialTarget(TEXT("CTRL_expressions_jawOpen")),
        EGahyeonFacialTarget::ControlRig);
    FString ValidationError;
    TestTrue(TEXT("consistent Control Rig route validates"), Profile->Validate(ValidationError));
    Profile->JawOpenCurve = TEXT("CTRL_expressions_jawOpen");
    Profile->JawOpenTarget = EGahyeonFacialTarget::MorphTarget;
    TestFalse(TEXT("one curve cannot mix morph and Control Rig routes"),
        Profile->Validate(ValidationError));
    Profile->JawOpenCurve = TEXT("CTRL_expressions_jawOpenFallback");
    TestTrue(TEXT("separate morph fallback remains valid"), Profile->Validate(ValidationError));

    UGahyeonCharacterAnimInstance* FaceBridge = NewObject<UGahyeonCharacterAnimInstance>();
    TMap<FName, float> RigCurves;
    RigCurves.Add(TEXT("CTRL_expressions_jawOpen"), 0.75f);
    TestTrue(TEXT("native face AnimInstance accepts bounded Control Rig values"),
        FaceBridge->ApplyFacialControlRigCurves_Implementation(RigCurves, {}));
    TestEqual(TEXT("native face AnimInstance exposes applied Control Rig values"),
        FaceBridge->GetFacialControlRigCurveWeights_Implementation().Num(), 0);
    const int64 FirstRigToken = FaceBridge->GetPendingFacialControlRigToken();
    TestTrue(TEXT("each native Control Rig batch receives a positive token"),
        FirstRigToken > 0);
    TestFalse(TEXT("old or fabricated token cannot confirm graph consumption"),
        FaceBridge->ConfirmFacialControlRigConsumed(FirstRigToken + 1));
    TestTrue(TEXT("Anim Blueprint may confirm the exact pending batch"),
        FaceBridge->ConfirmFacialControlRigConsumed(FirstRigToken));
    TestEqual(TEXT("QA readback exposes only graph-consumed values"),
        FaceBridge->GetFacialControlRigCurveWeights_Implementation().FindRef(
            TEXT("CTRL_expressions_jawOpen")), 0.75f);
    TestEqual(TEXT("consumed token matches the acknowledged batch"),
        FaceBridge->GetConsumedFacialControlRigToken(), FirstRigToken);
    TestFalse(TEXT("consumed batch has a deterministic non-empty digest"),
        FaceBridge->GetConsumedFacialControlRigDigest().IsEmpty());
    RigCurves.Reset();
    TestTrue(TEXT("native face AnimInstance resets stale Control Rig values"),
        FaceBridge->ApplyFacialControlRigCurves_Implementation(
            RigCurves, {TEXT("CTRL_expressions_jawOpen")}));
    const int64 ResetRigToken = FaceBridge->GetPendingFacialControlRigToken();
    TestFalse(TEXT("reset Control Rig value is absent from QA observation"),
        FaceBridge->GetFacialControlRigCurves().Contains(
            TEXT("CTRL_expressions_jawOpen")));
    TestTrue(TEXT("reset batch must also be graph-consumed"),
        FaceBridge->ConfirmFacialControlRigConsumed(ResetRigToken));
    TestFalse(TEXT("consumed readback confirms reset after acknowledgement"),
        FaceBridge->GetFacialControlRigCurveWeights_Implementation().Contains(
            TEXT("CTRL_expressions_jawOpen")));
    RigCurves.Add(TEXT("CTRL_bad"), 1.1f);
    TestFalse(TEXT("native face AnimInstance rejects out-of-range values"),
        FaceBridge->ApplyFacialControlRigCurves_Implementation(RigCurves, {}));

    FGahyeonGesturePresentationDefinition Gesture;
    Gesture.Semantic = TEXT("explain");
    Gesture.VariantId = TEXT("explain_small_01");
    Gesture.RequiredPosture = TEXT("standing");
    Gesture.MinIntensity = 0.2;
    Gesture.MaxIntensity = 0.8;
    Profile->Gestures.Add(Gesture);
    TestNotNull(TEXT("exact local semantic/variant/posture resolves"),
        Profile->FindGesture(TEXT("explain"), TEXT("explain_small_01"), TEXT("standing"), 0.5));
    TestNull(TEXT("backend cannot substitute an animation asset id"),
        Profile->FindGesture(TEXT("explain"), TEXT("some_montage_path"), TEXT("standing"), 0.5));
    TestNull(TEXT("gesture outside its intensity band is rejected"),
        Profile->FindGesture(TEXT("explain"), TEXT("explain_small_01"), TEXT("standing"), 0.95));

    Profile->BreathScale = 1.5;
    Profile->EyeYawDegrees = 30.0;
    Profile->EyePitchDegrees = 20.0;
    Profile->HeadYawDegrees = 50.0;
    Profile->HeadPitchDegrees = 25.0;
    Profile->MicroHeadDegrees = 2.0;
    Profile->WeightShiftScale = 0.8;
    const FGahyeonResolvedProceduralPose Pose = Profile->ResolveProceduralPose(
        0.5, 0.25,
        -0.4, 0.2,
        0.5, -0.5,
        0.75,
        0.6, -0.8,
        0.4, 0.2,
        0.75);
    TestEqual(TEXT("breath is character scaled"), Pose.Breath, 0.75);
    TestEqual(TEXT("attention blends over ambient eye yaw"), Pose.EyeYawDegrees, 10.5);
    TestEqual(TEXT("attention blends over ambient eye pitch"), Pose.EyePitchDegrees, -11.0);
    TestEqual(TEXT("micro head composes with attention"), Pose.HeadYawDegrees, 21.0);
    TestEqual(TEXT("micro head pitch composes with attention"), Pose.HeadPitchDegrees, 4.0);
    TestEqual(TEXT("weight shift is character scaled"), Pose.WeightShift, 0.6);
    TestTrue(TEXT("current visible listening pose may confirm latency"),
        UGahyeonCharacterPresentationComponent::IsPoseConfirmationCurrent(
            TEXT("listening"), 8, 3, TEXT("listening"), 8, 3));
    TestFalse(TEXT("old generation pose cannot confirm latency"),
        UGahyeonCharacterPresentationComponent::IsPoseConfirmationCurrent(
            TEXT("listening"), 7, 3, TEXT("listening"), 8, 3));
    TestFalse(TEXT("old runtime pose cannot confirm latency"),
        UGahyeonCharacterPresentationComponent::IsPoseConfirmationCurrent(
            TEXT("thinking"), 8, 2, TEXT("thinking"), 8, 3));
    TestFalse(TEXT("unsupported pose cannot manufacture latency"),
        UGahyeonCharacterPresentationComponent::IsPoseConfirmationCurrent(
            TEXT("speaking"), 8, 3, TEXT("speaking"), 8, 3));
    TestTrue(TEXT("Anim bridge opens a new Listening confirmation token"),
        UGahyeonCharacterAnimInstance::ShouldOpenPoseConfirmation(
            TEXT("listening"), 8, 3, TEXT("idle"), 8, 3));
    TestFalse(TEXT("Anim bridge does not reopen the same confirmed token"),
        UGahyeonCharacterAnimInstance::ShouldOpenPoseConfirmation(
            TEXT("listening"), 8, 3, TEXT("listening"), 8, 3));
    TestFalse(TEXT("Anim bridge never confirms non-visual conversation states"),
        UGahyeonCharacterAnimInstance::ShouldOpenPoseConfirmation(
            TEXT("speaking"), 8, 3, TEXT("thinking"), 8, 3));
    TestTrue(TEXT("Anim bridge exposes an active weighted viseme confirmation"),
        UGahyeonCharacterAnimInstance::IsVisemeConfirmationCandidate(
            true, TEXT("aa"), 0.8, 8, 3));
    TestFalse(TEXT("zero-weight viseme cannot manufacture a latency sample"),
        UGahyeonCharacterAnimInstance::IsVisemeConfirmationCandidate(
            true, TEXT("aa"), 0.0, 8, 3));
    TestFalse(TEXT("inactive lip sync cannot manufacture a latency sample"),
        UGahyeonCharacterAnimInstance::IsVisemeConfirmationCandidate(
            false, TEXT("aa"), 0.8, 8, 3));
    TestFalse(TEXT("runtime epoch is required for viseme confirmation"),
        UGahyeonCharacterAnimInstance::IsVisemeConfirmationCandidate(
            true, TEXT("aa"), 0.8, 8, 0));
    return true;
}

#endif
