#include "Character/GahyeonCharacterPawn.h"

#include "AIController.h"
#include "Camera/CameraComponent.h"
#include "Components/CapsuleComponent.h"
#include "Components/SkeletalMeshComponent.h"
#include "Components/StaticMeshComponent.h"
#include "UObject/ConstructorHelpers.h"
#include "Debug/GahyeonRuntimeDebugComponent.h"
#include "Engine/StaticMesh.h"
#include "GameFramework/CharacterMovementComponent.h"
#include "GameFramework/SpringArmComponent.h"
#include "Presentation/GahyeonCharacterPresentationComponent.h"
#include "World/GahyeonWorldActionComponent.h"
#include "Voice/GahyeonVoiceInputComponent.h"

AGahyeonCharacterPawn::AGahyeonCharacterPawn()
{
    PrimaryActorTick.bCanEverTick = false;
    SpawnCollisionHandlingMethod = ESpawnActorCollisionHandlingMethod::AlwaysSpawn;
    AutoPossessAI = EAutoPossessAI::PlacedInWorldOrSpawned;
    AIControllerClass = AAIController::StaticClass();
    bUseControllerRotationYaw = false;

    UCharacterMovementComponent* Movement = GetCharacterMovement();
    Movement->bOrientRotationToMovement = true;
    Movement->RotationRate = FRotator(0.0f, 360.0f, 0.0f);
    Movement->MaxWalkSpeed = 180.0f;
    Movement->BrakingDecelerationWalking = 600.0f;

    Presentation = CreateDefaultSubobject<UGahyeonCharacterPresentationComponent>(
        TEXT("GahyeonPresentation"));
    VoiceInput = CreateDefaultSubobject<UGahyeonVoiceInputComponent>(
        TEXT("GahyeonVoiceInput"));
    Presentation->AddTickPrerequisiteComponent(VoiceInput);
    WorldActions = CreateDefaultSubobject<UGahyeonWorldActionComponent>(
        TEXT("GahyeonWorldActions"));
    RuntimeDebug = CreateDefaultSubobject<UGahyeonRuntimeDebugComponent>(
        TEXT("GahyeonRuntimeDebug"));

    static ConstructorHelpers::FObjectFinder<UStaticMesh> Cylinder(
        TEXT("/Engine/BasicShapes/Cylinder.Cylinder"));
    static ConstructorHelpers::FObjectFinder<UStaticMesh> Sphere(
        TEXT("/Engine/BasicShapes/Sphere.Sphere"));
    DiagnosticBody = CreateDefaultSubobject<UStaticMeshComponent>(TEXT("DiagnosticBody"));
    DiagnosticBody->SetupAttachment(GetCapsuleComponent());
    DiagnosticBody->SetCollisionEnabled(ECollisionEnabled::NoCollision);
    DiagnosticBody->SetRelativeLocation(FVector(0.0, 0.0, -5.0));
    DiagnosticBody->SetRelativeScale3D(FVector(0.42, 0.30, 1.20));
    if (Cylinder.Succeeded()) DiagnosticBody->SetStaticMesh(Cylinder.Object);

    DiagnosticHead = CreateDefaultSubobject<UStaticMeshComponent>(TEXT("DiagnosticHead"));
    DiagnosticHead->SetupAttachment(GetCapsuleComponent());
    DiagnosticHead->SetCollisionEnabled(ECollisionEnabled::NoCollision);
    DiagnosticHead->SetRelativeLocation(FVector(0.0, 0.0, 95.0));
    DiagnosticHead->SetRelativeScale3D(FVector(0.38));
    if (Sphere.Succeeded()) DiagnosticHead->SetStaticMesh(Sphere.Object);

    CameraBoom = CreateDefaultSubobject<USpringArmComponent>(TEXT("CameraBoom"));
    CameraBoom->SetupAttachment(GetCapsuleComponent());
    CameraBoom->TargetArmLength = 320.0f;
    CameraBoom->SetRelativeLocation(FVector(0.0, 0.0, 65.0));
    // Place the default Stage camera in front of the avatar, looking back at it.
    CameraBoom->SetRelativeRotation(FRotator(-8.0f, 180.0f, 0.0f));
    CameraBoom->bUsePawnControlRotation = false;
    CameraBoom->bEnableCameraLag = true;
    CameraBoom->CameraLagSpeed = 8.0f;

    FollowCamera = CreateDefaultSubobject<UCameraComponent>(TEXT("FollowCamera"));
    FollowCamera->SetupAttachment(CameraBoom, USpringArmComponent::SocketName);
    FollowCamera->bUsePawnControlRotation = false;
}

void AGahyeonCharacterPawn::BeginPlay()
{
    Super::BeginPlay();
    const bool bHasAvatar = GetMesh() != nullptr && GetMesh()->GetSkeletalMeshAsset() != nullptr;
    DiagnosticBody->SetVisibility(!bHasAvatar, true);
    DiagnosticHead->SetVisibility(!bHasAvatar, true);
    if (!bHasAvatar)
    {
        GetCharacterMovement()->SetMovementMode(MOVE_Flying);
        RuntimeDebug->SetDrawOnScreen(bEnableDiagnosticOverlayWhenNoAvatar);
    }
}
