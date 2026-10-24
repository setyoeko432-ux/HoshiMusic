#pragma once

namespace hoshi {
namespace tuning {

// Subsonic & Low-end protection
constexpr float kSubsonicHpHz = 45.0f;
constexpr float kSubCutHpHz = 48.0f;
constexpr float kSubCutHpVbActiveHz = 62.0f;

// Preset static & dynamic EQ center frequencies
constexpr float kPresetAirShelfHz = 7000.0f;
constexpr float kPresetBassShelfHz = 105.0f;

constexpr float kMudCutFcHz = 280.0f;
constexpr float kLowMidGapFcHz = 380.0f;
constexpr float kBoxinessFcHz = 450.0f;

constexpr float kVocalFormantFcHz = 1800.0f;
constexpr float kVocalPresenceFcHz = 3000.0f;
constexpr float kConsonantFcHz = 5000.0f;
constexpr float kUltraAirShelfHz = 15200.0f;

constexpr float kSidePresenceFcHz = 3800.0f;
constexpr float kSideAirShelfHz = 12000.0f;

constexpr float kHarshGuardFcHz = 3500.0f;
constexpr float kUnmaskFcHz = 2500.0f;

// Virtual bass chain
constexpr float kBassExtractLpfHz = 60.0f;
constexpr float kBassHarmonicHpfGenHz = 80.0f;
constexpr float kBassHarmonicLpfGenHz = 250.0f;
constexpr float kBassHarmonicHpfFinalHz = 60.0f;

// De-Esser
constexpr float kDeEsserHpfHz = 5500.0f;
constexpr float kDeEsserLpfHz = 9500.0f;

// Band splits & Crossfeed
constexpr float kProtSplitLpfHz = 140.0f;
constexpr float kSatLowLpfHz = 160.0f;
constexpr float kErHpfHz = 250.0f;

} // namespace tuning
} // namespace hoshi
