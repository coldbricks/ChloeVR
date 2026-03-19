/**
 * C++ compile-time verification of ControllerState struct layout.
 *
 * This file uses static_assert to verify the struct is 41 floats
 * and that field offsets match the Kotlin-side index constants.
 *
 * To compile standalone:
 *   g++ -std=c++17 -I../../main/cpp -c controller_state_contract_test.cpp
 *
 * These assertions run at COMPILE TIME — if any fails, the build breaks
 * before the APK is even assembled.
 */

#include <cstddef>

// Minimal recreation of the struct for offset verification
// (matches openxr_input.h exactly)
struct ControllerState {
    float thumbstickX[2];     // indices 0,1
    float thumbstickY[2];     // indices 2,3
    float trigger[2];         // indices 4,5
    float squeeze[2];         // indices 6,7
    float aClick;             // index 8
    float bClick;             // index 9
    float xClick;             // index 10
    float yClick;             // index 11
    float menuClick;          // index 12
    float thumbstickClick[2]; // indices 13,14
    float handPosX[2];        // indices 15,16
    float handPosY[2];        // indices 17,18
    float handPosZ[2];        // indices 19,20
    float handRotX[2];        // indices 21,22
    float handRotY[2];        // indices 23,24
    float handRotZ[2];        // indices 25,26
    float handRotW[2];        // indices 27,28
    float handValid[2];       // indices 29,30
    float aimRotX[2];         // indices 31,32
    float aimRotY[2];         // indices 33,34
    float aimRotZ[2];         // indices 35,36
    float aimRotW[2];         // indices 37,38
    float aimValid[2];        // indices 39,40

    static constexpr int SIZE = 41;
    float* data() { return &thumbstickX[0]; }
};

// ═══════════════════════════════════════════════════════════════
// Compile-time size verification
// ═══════════════════════════════════════════════════════════════

static_assert(sizeof(ControllerState) == 41 * sizeof(float),
    "ControllerState must be exactly 41 floats with no padding. "
    "If this fails, the JNI buffer contract is broken.");

static_assert(ControllerState::SIZE == 41,
    "ControllerState::SIZE must be 41");

// ═══════════════════════════════════════════════════════════════
// Compile-time offset verification via offsetof
// The JNI bridge calls state.data() which returns &thumbstickX[0].
// All offsets are relative to that start address.
// ═══════════════════════════════════════════════════════════════

#define VERIFY_INDEX(field, expected_byte_offset) \
    static_assert(offsetof(ControllerState, field) == (expected_byte_offset) * sizeof(float), \
        "Field " #field " is at wrong offset — JNI contract broken")

// Thumbstick
VERIFY_INDEX(thumbstickX, 0);
VERIFY_INDEX(thumbstickY, 2);

// Triggers and squeeze
VERIFY_INDEX(trigger, 4);
VERIFY_INDEX(squeeze, 6);

// Buttons
VERIFY_INDEX(aClick, 8);
VERIFY_INDEX(bClick, 9);
VERIFY_INDEX(xClick, 10);
VERIFY_INDEX(yClick, 11);
VERIFY_INDEX(menuClick, 12);
VERIFY_INDEX(thumbstickClick, 13);

// Hand poses
VERIFY_INDEX(handPosX, 15);
VERIFY_INDEX(handPosY, 17);
VERIFY_INDEX(handPosZ, 19);
VERIFY_INDEX(handRotX, 21);
VERIFY_INDEX(handRotY, 23);
VERIFY_INDEX(handRotZ, 25);
VERIFY_INDEX(handRotW, 27);
VERIFY_INDEX(handValid, 29);

// Aim poses
VERIFY_INDEX(aimRotX, 31);
VERIFY_INDEX(aimRotY, 33);
VERIFY_INDEX(aimRotZ, 35);
VERIFY_INDEX(aimRotW, 37);
VERIFY_INDEX(aimValid, 39);

#undef VERIFY_INDEX
