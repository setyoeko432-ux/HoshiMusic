#include "../../main/cpp/hoshi_dsp_core.h"
#include <iostream>
#include <vector>
#include <cmath>
#include <cassert>
#include <iomanip>

using namespace hoshi;

/**
 * Harness mandiri untuk menguji DspEngine tanpa dependensi JNI.
 * Digunakan untuk verifikasi bit-exact dan stabilitas sinyal.
 */

void testImpulseResponse() {
    DspEngine dsp;
    dsp.init(44100);
    dsp.setEnabled(true);

    // Gunakan preset reference (flat internal)
    float refParams[15] = {1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f};
    dsp.setParams(refParams, 15);

    std::vector<int16_t> buffer(2048, 0);
    // Impulse di sampel pertama
    buffer[0] = 16384;
    buffer[1] = 16384;

    dsp.process(buffer.data(), 1024); // 1024 frames = 2048 samples

    // Cek apakah ada output (tidak silence)
    bool hasOutput = false;
    for (auto s : buffer) {
        if (s != 0) {
            hasOutput = true;
            break;
        }
    }
    assert(hasOutput && "DSP harus menghasilkan output untuk input impulse");
    std::cout << "[PASS] testImpulseResponse" << std::endl;
}

void testBypassBitPerfect() {
    DspEngine dsp;
    dsp.init(48000);
    dsp.setEnabled(false); // BYPASS

    std::vector<int16_t> buffer = {100, -100, 500, -500, 1000, -1000, 32767, -32768};
    std::vector<int16_t> original = buffer;

    dsp.process(buffer.data(), buffer.size() / 2);

    for (size_t i = 0; i < buffer.size(); ++i) {
        assert(buffer[i] == original[i] && "Bypass harus bit-perfect (tanpa dither)");
    }
    std::cout << "[PASS] testBypassBitPerfect" << std::endl;
}

void testSilenceStability() {
    DspEngine dsp;
    dsp.init(96000);
    dsp.setEnabled(true);

    std::vector<int16_t> buffer(4096, 0);
    dsp.process(buffer.data(), 2048);

    // Cek stabilitas (tidak ada DC offset liar atau noise meledak pada silence)
    for (size_t i = 0; i < buffer.size(); ++i) {
        assert(std::abs(buffer[i]) <= 2 && "Silence output harus mendekati nol (noise floor dither)");
    }
    std::cout << "[PASS] testSilenceStability" << std::endl;
}

int main() {
    std::cout << "--- Hoshi DSP Native Harness ---" << std::endl;
    try {
        testBypassBitPerfect();
        testImpulseResponse();
        testSilenceStability();
        std::cout << "ALL NATIVE TESTS PASSED" << std::endl;
    } catch (const std::exception& e) {
        std::cerr << "TEST FAILED: " << e.what() << std::endl;
        return 1;
    }
    return 0;
}
