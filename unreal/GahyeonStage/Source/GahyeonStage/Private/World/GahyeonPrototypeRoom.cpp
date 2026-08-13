#include "World/GahyeonPrototypeRoom.h"

#include "Components/SceneComponent.h"
#include "Components/StaticMeshComponent.h"
#include "UObject/ConstructorHelpers.h"
#include "World/GahyeonInteractionPointComponent.h"

AGahyeonPrototypeRoom::AGahyeonPrototypeRoom()
{
    PrimaryActorTick.bCanEverTick = false;
    RoomRoot = CreateDefaultSubobject<USceneComponent>(TEXT("RoomRoot"));
    SetRootComponent(RoomRoot);

    static ConstructorHelpers::FObjectFinder<UStaticMesh> CubeAsset(
        TEXT("/Engine/BasicShapes/Cube.Cube"));
    UStaticMesh* Cube = CubeAsset.Succeeded() ? CubeAsset.Object : nullptr;

    // Engine cube is 100 cm. This 16 m x 12 m fixture contains every point in
    // GahyeonHomeWorld after the Core-metres -> Unreal-centimetres axis mapping.
    AddBox(TEXT("Floor"), Cube, FVector(100.0, -450.0, -10.0), FVector(16.0, 12.0, 0.2));
    AddBox(TEXT("BackWall"), Cube, FVector(100.0, -1050.0, 150.0), FVector(16.0, 0.2, 3.0));
    AddBox(TEXT("LeftWall"), Cube, FVector(-700.0, -450.0, 150.0), FVector(0.2, 12.0, 3.0));
    AddBox(TEXT("Desk"), Cube, FVector(700.0, -100.0, 75.0), FVector(2.0, 0.8, 0.1));
    AddBox(TEXT("Bed"), Cube, FVector(0.0, 100.0, 30.0), FVector(2.1, 1.3, 0.6));
    AddBox(TEXT("Bookshelf"), Cube, FVector(365.0, -600.0, 100.0), FVector(0.5, 2.0, 2.0));
    AddBox(TEXT("Chair"), Cube, FVector(-120.0, -500.0, 45.0), FVector(0.65, 0.65, 0.9));

    UGahyeonInteractionPointComponent* DeskPoint =
        CreateDefaultSubobject<UGahyeonInteractionPointComponent>(TEXT("DeskInteraction"));
    DeskPoint->SetupAttachment(RoomRoot);
    DeskPoint->SetRelativeLocation(FVector(700.0, -200.0, 0.0));
    DeskPoint->SetRelativeRotation(FRotator(0.0, 90.0, 0.0));
    DeskPoint->Configure(TEXT("desk"), TEXT("workspace"), {TEXT("sit"), TEXT("work")});

    UGahyeonInteractionPointComponent* BedPoint =
        CreateDefaultSubobject<UGahyeonInteractionPointComponent>(TEXT("BedInteraction"));
    BedPoint->SetupAttachment(RoomRoot);
    BedPoint->SetRelativeLocation(FVector(0.0, 0.0, 0.0));
    BedPoint->SetRelativeRotation(FRotator(0.0, 180.0, 0.0));
    BedPoint->Configure(TEXT("bed"), TEXT("bedroom"), {TEXT("sleep")});

    UGahyeonInteractionPointComponent* BookshelfPoint =
        CreateDefaultSubobject<UGahyeonInteractionPointComponent>(TEXT("BookshelfInteraction"));
    BookshelfPoint->SetupAttachment(RoomRoot);
    BookshelfPoint->SetRelativeLocation(FVector(300.0, -600.0, 0.0));
    BookshelfPoint->SetRelativeRotation(FRotator(0.0, 0.0, 0.0));
    BookshelfPoint->Configure(TEXT("bookshelf"), TEXT("living_room"), {TEXT("read")});

    UGahyeonInteractionPointComponent* ChairPoint =
        CreateDefaultSubobject<UGahyeonInteractionPointComponent>(TEXT("ChairInteraction"));
    ChairPoint->SetupAttachment(RoomRoot);
    ChairPoint->SetRelativeLocation(FVector(-200.0, -500.0, 0.0));
    ChairPoint->SetRelativeRotation(FRotator(0.0, 90.0, 0.0));
    ChairPoint->Configure(TEXT("chair"), TEXT("living_room"), {TEXT("sit"), TEXT("relax")});

    UGahyeonInteractionPointComponent* WindowPoint =
        CreateDefaultSubobject<UGahyeonInteractionPointComponent>(TEXT("WindowInteraction"));
    WindowPoint->SetupAttachment(RoomRoot);
    WindowPoint->SetRelativeLocation(FVector(0.0, -900.0, 0.0));
    WindowPoint->SetRelativeRotation(FRotator(0.0, -90.0, 0.0));
    WindowPoint->Configure(TEXT("window"), TEXT("living_room"), {TEXT("look_outside")});

    UGahyeonInteractionPointComponent* RoomCenterPoint =
        CreateDefaultSubobject<UGahyeonInteractionPointComponent>(TEXT("RoomCenterInteraction"));
    RoomCenterPoint->SetupAttachment(RoomRoot);
    RoomCenterPoint->SetRelativeLocation(FVector(0.0, -200.0, 0.0));
    RoomCenterPoint->SetRelativeRotation(FRotator::ZeroRotator);
    RoomCenterPoint->Configure(TEXT("room-center"), TEXT("bedroom"), {TEXT("idle"), TEXT("walk")});
}

UStaticMeshComponent* AGahyeonPrototypeRoom::AddBox(
    FName Name,
    UStaticMesh* Cube,
    const FVector& Location,
    const FVector& Scale)
{
    UStaticMeshComponent* Component = CreateDefaultSubobject<UStaticMeshComponent>(Name);
    Component->SetupAttachment(RoomRoot);
    Component->SetStaticMesh(Cube);
    Component->SetRelativeLocation(Location);
    Component->SetRelativeScale3D(Scale);
    Component->SetCollisionProfileName(TEXT("BlockAll"));
    Component->SetCanEverAffectNavigation(true);
    Geometry.Add(Component);
    return Component;
}
