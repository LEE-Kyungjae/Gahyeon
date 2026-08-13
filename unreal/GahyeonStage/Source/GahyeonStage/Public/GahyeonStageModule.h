#pragma once

#include "Modules/ModuleManager.h"

class FGahyeonStageModule final : public IModuleInterface
{
public:
    virtual void StartupModule() override;
    virtual void ShutdownModule() override;
};
