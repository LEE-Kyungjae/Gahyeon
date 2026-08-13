#include "Character/GahyeonStageGameMode.h"

#include "Character/GahyeonCharacterPawn.h"
#include "Character/GahyeonHeroRuntimeSettings.h"
#include "Animation/GahyeonCharacterAnimInstance.h"
#include "Components/SkeletalMeshComponent.h"
#include "EngineUtils.h"
#include "Engine/World.h"
#include "Presentation/GahyeonCharacterPresentationComponent.h"
#include "World/GahyeonPrototypeRoom.h"

AGahyeonStageGameMode::AGahyeonStageGameMode()
{
    DefaultPawnClass = AGahyeonCharacterPawn::StaticClass();

    const UGahyeonHeroRuntimeSettings* Settings = GetDefault<UGahyeonHeroRuntimeSettings>();
    FString Error;
    const TSubclassOf<AGahyeonCharacterPawn> HeroClass = ResolveHeroPawnClass(
        Settings->HeroPawnClass, Error);
    const bool bRuntimeValid = HeroClass
        && ValidateHeroRuntimeContract(HeroClass, Error);
    if (bRuntimeValid)
    {
        DefaultPawnClass = HeroClass;
    }
    else if (!Settings->HeroPawnClass.IsNull())
    {
        if (Settings->bRequireHeroAsset)
        {
            UE_LOG(LogTemp, Fatal, TEXT("Required Gahyeon Hero pawn could not be loaded: %s"), *Error);
        }
        UE_LOG(LogTemp, Warning,
            TEXT("Gahyeon Hero pawn unavailable; using source shell: %s"), *Error);
    }
}

void AGahyeonStageGameMode::StartPlay()
{
    Super::StartPlay();
    if (GetWorld() == nullptr || GetWorld()->GetNetMode() == NM_DedicatedServer)
    {
        return;
    }
    TActorIterator<AGahyeonPrototypeRoom> Existing(GetWorld());
    if (!Existing)
    {
        GetWorld()->SpawnActor<AGahyeonPrototypeRoom>(
            AGahyeonPrototypeRoom::StaticClass(), FTransform::Identity);
    }
}

TSubclassOf<AGahyeonCharacterPawn> AGahyeonStageGameMode::ResolveHeroPawnClass(
    const FSoftClassPath& HeroClassPath,
    FString& OutError)
{
    OutError.Reset();
    if (HeroClassPath.IsNull())
    {
        return nullptr;
    }

    UClass* LoadedClass = HeroClassPath.TryLoadClass<AGahyeonCharacterPawn>();
    if (LoadedClass == nullptr)
    {
        OutError = FString::Printf(TEXT("not found or not a GahyeonCharacterPawn subclass: %s"),
            *HeroClassPath.ToString());
        return nullptr;
    }
    if (!LoadedClass->IsChildOf(AGahyeonCharacterPawn::StaticClass()))
    {
        OutError = FString::Printf(TEXT("Hero class does not preserve the Gahyeon pawn boundary: %s"),
            *HeroClassPath.ToString());
        return nullptr;
    }
    return LoadedClass;
}

bool AGahyeonStageGameMode::ValidateHeroRuntimeContract(
    TSubclassOf<AGahyeonCharacterPawn> HeroClass,
    FString& OutError)
{
    OutError.Reset();
    AGahyeonCharacterPawn* DefaultPawn = HeroClass != nullptr
        ? Cast<AGahyeonCharacterPawn>(HeroClass->GetDefaultObject())
        : nullptr;
    if (DefaultPawn == nullptr)
    {
        OutError = TEXT("Hero class has no Gahyeon pawn default object");
        return false;
    }
    USkeletalMeshComponent* BodyMesh = DefaultPawn->GetMesh();
    if (BodyMesh == nullptr || BodyMesh->GetSkeletalMeshAsset() == nullptr)
    {
        OutError = TEXT("Hero pawn has no body skeletal mesh");
        return false;
    }
    UClass* AnimClass = BodyMesh->GetAnimClass();
    if (AnimClass == nullptr
        || !AnimClass->IsChildOf(UGahyeonCharacterAnimInstance::StaticClass()))
    {
        OutError = TEXT("Hero body AnimInstance does not preserve the Gahyeon animation bridge");
        return false;
    }
    UGahyeonCharacterPresentationComponent* Presentation =
        DefaultPawn->FindComponentByClass<UGahyeonCharacterPresentationComponent>();
    if (Presentation == nullptr || Presentation->GetProfile() == nullptr)
    {
        OutError = TEXT("Hero pawn has no Gahyeon Presentation Profile");
        return false;
    }
    FString ProfileError;
    if (!Presentation->GetProfile()->Validate(ProfileError))
    {
        OutError = TEXT("Hero Presentation Profile is invalid: ") + ProfileError;
        return false;
    }
    return true;
}
