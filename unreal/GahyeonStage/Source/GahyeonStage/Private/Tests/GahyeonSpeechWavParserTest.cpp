#if WITH_DEV_AUTOMATION_TESTS

#include "Audio/GahyeonSpeechAudioComponent.h"
#include "Misc/AutomationTest.h"

IMPLEMENT_SIMPLE_AUTOMATION_TEST(
    FGahyeonSpeechWavParserTest,
    "Gahyeon.Audio.ParsesBoundedPcm16Wav",
    EAutomationTestFlags::EditorContext | EAutomationTestFlags::EngineFilter)

namespace
{
void AppendU16(TArray<uint8>& Bytes, uint16 Value)
{
    Bytes.Add(static_cast<uint8>(Value & 0xff));
    Bytes.Add(static_cast<uint8>((Value >> 8) & 0xff));
}

void AppendU32(TArray<uint8>& Bytes, uint32 Value)
{
    Bytes.Add(static_cast<uint8>(Value & 0xff));
    Bytes.Add(static_cast<uint8>((Value >> 8) & 0xff));
    Bytes.Add(static_cast<uint8>((Value >> 16) & 0xff));
    Bytes.Add(static_cast<uint8>((Value >> 24) & 0xff));
}

void AppendFourCc(TArray<uint8>& Bytes, const char* Value)
{
    for (int32 Index = 0; Index < 4; ++Index)
    {
        Bytes.Add(static_cast<uint8>(Value[Index]));
    }
}

TArray<uint8> MonoPcm16Wav()
{
    TArray<uint8> Bytes;
    AppendFourCc(Bytes, "RIFF");
    AppendU32(Bytes, 40);
    AppendFourCc(Bytes, "WAVE");
    AppendFourCc(Bytes, "fmt ");
    AppendU32(Bytes, 16);
    AppendU16(Bytes, 1);
    AppendU16(Bytes, 1);
    AppendU32(Bytes, 24000);
    AppendU32(Bytes, 48000);
    AppendU16(Bytes, 2);
    AppendU16(Bytes, 16);
    AppendFourCc(Bytes, "data");
    AppendU32(Bytes, 4);
    AppendU16(Bytes, 0);
    AppendU16(Bytes, 0);
    return Bytes;
}
}

bool FGahyeonSpeechWavParserTest::RunTest(const FString& Parameters)
{
    (void)Parameters;
    TArray<uint8> Valid = MonoPcm16Wav();
    UGahyeonSpeechAudioComponent::FWavPcmView View;
    TestTrue(TEXT("bounded PCM16 WAV parses"),
        UGahyeonSpeechAudioComponent::ParsePcm16Wav(Valid, View));
    TestEqual(TEXT("sample rate retained"), View.SampleRate, 24000);
    TestEqual(TEXT("channel count retained"), View.Channels, 1);
    TestEqual(TEXT("PCM byte count retained"), View.PcmBytes, 4);

    TArray<uint8> Truncated = Valid;
    Truncated.SetNum(43);
    TestFalse(TEXT("truncated data chunk fails closed"),
        UGahyeonSpeechAudioComponent::ParsePcm16Wav(Truncated, View));

    TArray<uint8> FloatFormat = Valid;
    FloatFormat[20] = 3;
    TestFalse(TEXT("non-PCM format is rejected"),
        UGahyeonSpeechAudioComponent::ParsePcm16Wav(FloatFormat, View));
    return true;
}

#endif
