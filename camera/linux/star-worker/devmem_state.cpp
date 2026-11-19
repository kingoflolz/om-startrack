#include "devmem_state.h"

#include "syscalls.h"
#include "util.h"

#include <stddef.h>
#include <stdint.h>

namespace om1_stars {
namespace {

constexpr size_t kPageSize = 4096;

// Main-RTOS 0xa... uncached aliases correspond to Linux physical 0x2a... .
// The app-edge byte is instead accessed by RTOS code through its cached
// 0x60357bbc alias; the candidate Linux physical mapping declared below
// therefore requires dynamic cache-visibility qualification before promotion.
constexpr uint64_t kEventInitCookiePhysical = UINT64_C(0x2a31f1d26);
constexpr uint64_t kBleHandlerStatePhysical = UINT64_C(0x2a3212180);
constexpr uint64_t kBleModePhysical = UINT64_C(0x2a3212188);
constexpr uint64_t kBlePassCheckPhysical = UINT64_C(0x2a3212190);
constexpr uint64_t kBleConverterStatePhysical = UINT64_C(0x2a3212194);
constexpr uint64_t kStationStatePhysical = UINT64_C(0x2a320c8b4);
constexpr uint64_t kShutterTriggerStatePhysical = UINT64_C(0x2a0357bbc);

// The direct exposure evaluator reads firmware's public-ID provider cache from
// this fixed, read-only v1.9 mapping.  The high-ID cache uses a separate branch
// rooted at 0xa31f44e0; ID 15209 resolves to physical 0x2a31f4824, still
// within the same three-page mapping.
constexpr uint64_t kSettingProviderPointerTablePhysical =
    UINT64_C(0x2a31f2098);
constexpr size_t kSettingProviderPointerMappingLength = 0x3000;

// OM-1 v1.9's immutable native SettingController B09 banks.  The component's
// initialized-RAM runtime pointers use the cached 0x60... alias; Linux reaches
// the same main-RTOS bytes by adding the physical aperture delta below.  The
// descriptor and value mappings are deliberately read-only.  Exact descriptor
// fields, a double read, and a full-table FNV-1a fingerprint pin this fast path
// to the reviewed firmware image before it replaces any B08/B09 shell walk.
constexpr uint64_t kMainRtosRuntimeToPhysical = UINT64_C(0x240000000);
constexpr uint64_t kSettingCatalogCommonValuesPhysical =
    UINT64_C(0x2a0611e90);
constexpr size_t kSettingCatalogCommonValuesMappingLength = 0x2000;
constexpr uint64_t kSettingCatalogSpecialValuesPhysical =
    UINT64_C(0x2a061451c);
constexpr size_t kSettingCatalogSpecialValuesMappingLength = 0x1000;
constexpr uint64_t kSettingCatalogCommonDescriptorsPhysical =
    UINT64_C(0x2a0616924);
constexpr size_t kSettingCatalogCommonDescriptorsMappingLength = 0x1000;
constexpr uint64_t kSettingCatalogSpecialDescriptorPhysical =
    UINT64_C(0x2a061bf64);
constexpr size_t kSettingCatalogSpecialDescriptorMappingLength = 0x1000;
constexpr uint64_t kSettingCatalogFormatterPhysical =
    UINT64_C(0x2a1932000);
constexpr size_t kSettingCatalogFormatterMappingLength = 0x3000;
constexpr uint64_t kSettingCatalogFormatterRegistryPhysical =
    UINT64_C(0x2a1933fe8);
constexpr size_t kSettingCatalogFormatterRegistryCount = 40;
constexpr size_t kSettingCatalogFormatterRegistryRecordSize = 16;
constexpr uint32_t kSettingCatalogFormatterRegistryFnv1a = 0x319d4250;
constexpr size_t kSettingCatalogDescriptorSize = 20;
constexpr size_t kSettingCatalogFormatterRecordSize = 8;
constexpr uint32_t kSettingCatalogApertureVariantsRuntime = 0x61933330;
constexpr uint32_t kSettingCatalogShutterVariantsRuntime = 0x61933e64;
constexpr uint32_t kSettingCatalogFormatterRuntimeBegin = 0x61932000;
constexpr uint32_t kSettingCatalogFormatterRuntimeEnd = 0x61935000;
constexpr uint32_t kSettingCatalogLabelPointerBegin = 0x61932588;
constexpr uint32_t kSettingCatalogLabelPointerEnd = 0x61933931;
constexpr size_t kSettingCatalogV19LabelMax = 8;

// Direct SettingController/CameraInfo provider authority.  The two immutable
// public-ID maps select an encoded generic property and element.  The v1.9
// generic resolver then selects one primary bucket record.  The live-qualified
// normal-still backend requires the secondary selector flag to remain zero;
// a nonzero flag fails closed instead of following an unqualified table.
constexpr uint64_t kDirectProviderDescriptorsPhysical =
    UINT64_C(0x2a06217e4);
constexpr size_t kDirectProviderDescriptorsMappingLength = 0x4000;
constexpr uint32_t kDirectProviderMapPrimaryCount = 2129;
constexpr uint32_t kDirectProviderMapPrimaryFnv1a = 0x60f518ed;
constexpr uint64_t kDirectProviderMapSecondaryPhysical =
    UINT64_C(0x2a0623bd2);
constexpr uint32_t kDirectProviderMapSecondaryBase = 15000;
constexpr uint32_t kDirectProviderMapSecondaryCount = 288;
constexpr uint32_t kDirectProviderMapSecondaryFnv1a = 0x84583a99;
constexpr uint64_t kDirectProviderBucketsPhysical =
    UINT64_C(0x2a32a9000);
constexpr size_t kDirectProviderBucketsMappingLength = 0x9000;
constexpr uint64_t kDirectProviderSecondaryFlagPhysical =
    UINT64_C(0x2a32a9908);
constexpr uint64_t kDirectProviderPrimaryBucketsPhysical =
    UINT64_C(0x2a32a990c);
constexpr uint64_t kDirectProviderValuePhysical[3] = {
    UINT64_C(0x2a2920000), UINT64_C(0x2a2946000),
    UINT64_C(0x2a294d000)};
constexpr uint32_t kDirectProviderValueRuntime[3] = {
    UINT32_C(0x62920000), UINT32_C(0x62946000), UINT32_C(0x6294d000)};
constexpr uint64_t kDirectHelperStatePhysical = UINT64_C(0x2a2abd000);
constexpr uint64_t kDirectHelperModePhysical = UINT64_C(0x2a32f1000);
constexpr uint64_t kDirectShutterCeilingPhysical = UINT64_C(0x2a2abd794);
constexpr uint64_t kDirectShutterFloorPhysical = UINT64_C(0x2a2abd796);
constexpr uint64_t kDirectRam8d0Physical = UINT64_C(0x2a2abd8d0);
constexpr uint64_t kDirectRamde48Physical = UINT64_C(0x2a2abde48);
constexpr uint64_t kDirectHelperRecordsPhysical = UINT64_C(0x2a32f1604);
constexpr uint64_t kDirectHelperIndexPhysical = UINT64_C(0x2a32f164c);
constexpr size_t kDirectHelperRecordStride = 24;
constexpr size_t kDirectHelperModeOffset = 8;
constexpr uint8_t kDirectHelperRecordCount = 3;
constexpr uint64_t kDirectSettingDispatchPhysical =
    UINT64_C(0x2a05c4000);
constexpr size_t kDirectSettingDispatchMappingLength = 0x5000;
constexpr uint64_t kDirectSettingDispatchSemanticPhysical =
    UINT64_C(0x2a05c4680);
constexpr size_t kDirectSettingDispatchSemanticLength = 0x3d68;
constexpr uint32_t kDirectSettingDispatchSemanticFnv1a = 0xdacb497e;
constexpr uint64_t kDirectPostfilterPhysical = UINT64_C(0x2a05c9000);
constexpr size_t kDirectPostfilterMappingLength = 0x1000;
constexpr uint64_t kDirectPostfilterSemanticPhysical =
    UINT64_C(0x2a05c94cc);
constexpr size_t kDirectPostfilterSemanticLength = 0x224;
constexpr uint32_t kDirectPostfilterSemanticFnv1a = 0x377ff08e;
constexpr uint64_t kDirectApertureLegalityPhysical =
    UINT64_C(0x2a06e2000);
constexpr size_t kDirectApertureLegalityMappingLength = 0x1000;
constexpr uint64_t kDirectApertureLegalitySemanticPhysical =
    UINT64_C(0x2a06e2bf8);
constexpr size_t kDirectApertureLegalitySemanticLength = 0xa2;
constexpr uint32_t kDirectApertureLegalitySemanticFnv1a = 0x45102785;
constexpr uint64_t kDirectShutterLegalityPhysical =
    UINT64_C(0x2a06ec000);
constexpr size_t kDirectShutterLegalityMappingLength = 0x2000;
constexpr uint64_t kDirectShutterLegalitySemanticPhysical =
    UINT64_C(0x2a06ecc10);
constexpr size_t kDirectShutterLegalitySemanticLength = 0xdcc;
constexpr uint32_t kDirectShutterLegalitySemanticFnv1a = 0xffc08b86;
constexpr uint64_t kDirectIsoLegalityPhysical = UINT64_C(0x2a05cb000);
constexpr size_t kDirectIsoLegalityMappingLength = 0xb000;
constexpr uint64_t kDirectIsoRegistryPhysical = UINT64_C(0x2a05e1000);
constexpr size_t kDirectIsoRegistryMappingLength = 0x2000;
constexpr uint64_t kDirectIsoRegistrySemanticPhysical =
    UINT64_C(0x2a05e1004);
constexpr size_t kDirectIsoRegistrySemanticLength = 0x19f0;
constexpr uint32_t kDirectIsoRegistrySemanticFnv1a = 0xc0e0a7e2;

// OM-1 v1.9's two resident lens objects and the small selector/connection
// control pages used by the stock lens diagnostics.  All mappings are
// read-only.  The active object is selected only after the control guards
// agree; no pointer from camera RAM is ever used as an mmap address.
constexpr uint32_t kDirectLensRuntime[2] = {
    UINT32_C(0xa3193780), UINT32_C(0xa31b2180)};
constexpr uint64_t kDirectLensPhysical[2] = {
    UINT64_C(0x2a3193780), UINT64_C(0x2a31b2180)};
constexpr uint64_t kDirectLensHeadPagePhysical[2] = {
    UINT64_C(0x2a3193000), UINT64_C(0x2a31b2000)};
constexpr uint64_t kDirectLensCalibrationPagePhysical[2] = {
    UINT64_C(0x2a3195000), UINT64_C(0x2a31b4000)};
constexpr uint64_t kDirectLensFeaturePagePhysical[2] = {
    UINT64_C(0x2a31b0000), UINT64_C(0x2a31cf000)};
constexpr uint64_t kDirectLensControlPagePhysical = UINT64_C(0x2a296c000);
constexpr uint64_t kDirectLensFocusPagePhysical = UINT64_C(0x2a296d000);
constexpr uint64_t kDirectLensCommitPagePhysical = UINT64_C(0x2a296e000);
constexpr uint64_t kDirectLensSlotsPagePhysical = UINT64_C(0x2a32ca000);
constexpr uint64_t kDirectLensConnectionPagePhysical =
    UINT64_C(0x2a32d4000);
constexpr uint64_t kDirectLensBasePointerPhysical = UINT64_C(0x2a296c544);
constexpr uint64_t kDirectLensFocusModePhysical = UINT64_C(0x2a296d0f0);
constexpr uint64_t kDirectLensSelectorPhysical = UINT64_C(0x2a296e188);
constexpr uint64_t kDirectLensSlot0Physical = UINT64_C(0x2a32caa8c);
constexpr uint64_t kDirectLensSlot1Physical = UINT64_C(0x2a32caa94);
constexpr uint64_t kDirectLensConnection0Physical = UINT64_C(0x2a32d4b9a);
constexpr uint64_t kDirectLensConnection1Physical = UINT64_C(0x2a32d4b9b);
constexpr uint32_t kDirectLensSlot1Expected = UINT32_C(0xa31d180c);
constexpr size_t kDirectLensZoomOffset = 8;
constexpr size_t kDirectLensAutofocusOffset = 22;
constexpr size_t kDirectLensAutofocusCount = 8;
constexpr size_t kDirectLensValidPulseCountOffset = 38;
constexpr size_t kDirectLensFocusPulseOffset = 40;
constexpr size_t kDirectLensPrivateStatusOffset = 568;
constexpr size_t kDirectLensIdOffset = 1100;
constexpr size_t kDirectLensNameOffset = 1106;
constexpr size_t kDirectLensNameBytes = 32;
constexpr size_t kDirectLensRowCountOffset = 1138;
constexpr size_t kDirectLensWideOffset = 1144;
constexpr size_t kDirectLensTeleOffset = 1146;
// CHARACTER_ZM is 32 interleaved 16-byte rows.  U-shell `lens data lens 2`
// names the first three halfwords in each row L_FLenc, L_APmin, and L_APmax.
// The current L_ZMENC selects the authoritative, focal-specific aperture
// bounds used by the stock aperture path.
constexpr size_t kDirectLensZoomRowsOffset = 1164;
constexpr size_t kDirectLensZoomRowStride = 16;
constexpr size_t kDirectLensZoomRowFocalOffset = 0;
constexpr size_t kDirectLensZoomRowApertureMinOffset = 2;
constexpr size_t kDirectLensZoomRowApertureMaxOffset = 4;
constexpr size_t kDirectLensCalibrationOffset = 8256;
constexpr size_t kDirectLensCalibrationStride = 36;
constexpr size_t kDirectLensCalibrationFloatCount = 8;
constexpr size_t kDirectLensCalibrationInfinityOffset = 32;
constexpr size_t kDirectLensNormalIsOffset = 119058;
constexpr size_t kDirectLensMacroIsOffset = 119059;

struct DirectLegalityFingerprint {
  uint64_t physical;
  size_t length;
  uint32_t fnv1a;
};

constexpr DirectLegalityFingerprint kDirectIsoLegalityFingerprints[] = {
    {UINT64_C(0x2a05cb184), 0x10, 0x962c4362},
    {UINT64_C(0x2a05cd228), 0x10, 0xda655af8},
    {UINT64_C(0x2a05cffd8), 0x08, 0x2875711e},
    {UINT64_C(0x2a05d4e5c), 0x9bc, 0x0c704f2a},
};

struct DirectProviderSpec {
  int32_t public_id;
  uint16_t encoded;
  uint16_t selector;
  uint16_t element_count;
  uint32_t expected_provider_base;
  uint64_t cache_slot_physical;
};

enum DirectProviderIndex : uint8_t {
  kProviderContext41,
  kProviderContext44,
  kProviderContext109,
  kProviderContext277,
  kProviderContext26,
  kProviderContext1444,
  kProviderContext15209,
  kProviderContext24,
  kProviderContext1679,
  kProviderContext64,
  kProviderContext141,
  kProviderActive4,
  kProviderActive3,
  kProviderActive6,
  kProviderSource1445,
  kProviderSource1667,
  kProviderSource1668,
  kProviderSource1669,
  kProviderSource1670,
  kProviderSource1671,
  kProviderSource1672,
  kProviderIso598,
  kProviderIso434,
  kProviderIso435,
  kProviderIso239,
  kProviderIso754,
  kProviderIso1712,
  kProviderIso1678,
  kProviderIso1325,
  kProviderIso1606,
  kProviderIso1673,
  kProviderHigh17665,
  kProviderHigh37297Selector1,
  kProviderHigh61697,
  kProviderHigh33697,
  kProviderHigh21489,
  kDirectProviderCount,
};

constexpr DirectProviderSpec kDirectProviderSpecs[kDirectProviderCount] = {
    {41, 0x8601, 0, 1, 0x62946c3a, UINT64_C(0x2a31f213c)},
    {44, 0x8551, 0, 1, 0x62920f7e, UINT64_C(0x2a31f2148)},
    {109, 0x8f31, 0, 1, 0x6294d208, UINT64_C(0x2a31f224c)},
    {277, 0x2871, 0, 1, 0x62920979, UINT64_C(0x2a31f24ec)},
    {26, 0x85c1, 0, 1, 0x62920f85, UINT64_C(0x2a31f2100)},
    {1444, 0x6771, 0, 1, 0x62920d63, UINT64_C(0x2a31f3728)},
    {15209, 0xea51, 0, 1, 0x6294d59e, UINT64_C(0x2a31f4824)},
    {24, 0xc011, 0, 1, 0x6294d3cc, UINT64_C(0x2a31f20f8)},
    {1679, 0x1e41, 0, 1, 0x6292096d, UINT64_C(0x2a31f3ad4)},
    {64, 0x8bea, 0, 3, 0x6294d1be, UINT64_C(0x2a31f2198)},
    {141, 0x96f2, 0, 1, 0x6294d2e2, UINT64_C(0x2a31f22cc)},
    {4, 0x80da, 0, 3, 0x62920e7e, UINT64_C(0x2a31f20a8)},
    {3, 0x80ca, 0, 3, 0x62920e78, UINT64_C(0x2a31f20a4)},
    {6, 0x810a, 0, 3, 0x62920e86, UINT64_C(0x2a31f20b0)},
    {1445, 0x6782, 0, 1, 0x62920d64, UINT64_C(0x2a31f372c)},
    {1667, 0x80ca, 1, 3, 0x62920e78, UINT64_C(0x2a31f3aa4)},
    {1668, 0x80ca, 2, 3, 0x62920e78, UINT64_C(0x2a31f3aa8)},
    {1669, 0x80da, 1, 3, 0x62920e7e, UINT64_C(0x2a31f3aac)},
    {1670, 0x80da, 2, 3, 0x62920e7e, UINT64_C(0x2a31f3ab0)},
    {1671, 0x810a, 1, 3, 0x62920e86, UINT64_C(0x2a31f3ab4)},
    {1672, 0x810a, 2, 3, 0x62920e86, UINT64_C(0x2a31f3ab8)},
    {598, 0xe911, 0, 1, 0x6294d587, UINT64_C(0x2a31f29f0)},
    {434, 0xd732, 0, 1, 0x6294d452, UINT64_C(0x2a31f2760)},
    {435, 0xd752, 0, 1, 0x6294d456, UINT64_C(0x2a31f2764)},
    {239, 0x9fb4, 0, 1, 0x6294d3a8, UINT64_C(0x2a31f2454)},
    {754, 0xf0c1, 0, 1, 0x6294d732, UINT64_C(0x2a31f2c60)},
    {1712, 0x85b1, 0, 1, 0x62920f84, UINT64_C(0x2a31f3b58)},
    {1678, 0x1e31, 0, 1, 0x6292096c, UINT64_C(0x2a31f3ad0)},
    {1325, 0x9361, 0, 1, 0x6294d27d, UINT64_C(0x2a31f354c)},
    {1606, 0x1d41, 0, 1, 0x6292093e, UINT64_C(0x2a31f39b0)},
    {1673, 0x1e21, 0, 1, 0x6292096b, UINT64_C(0x2a31f3abc)},
    {-1, 0x4501, 0, 1, 0x62920a00, UINT64_C(0x2a2abdd94)},
    {-1, 0x91b1, 1, 7, 0x6294d253, UINT64_C(0x2a2abdd10)},
    {-1, 0xf101, 0, 1, 0x6294d738, UINT64_C(0x2a2abdcdc)},
    {-1, 0x83a1, 0, 1, 0x62920ed0, UINT64_C(0x2a2abddb4)},
    {-1, 0x53f1, 0, 1, 0x62920ae2, UINT64_C(0x2a2abde00)},
};

struct DirectOwnerPointer {
  uint64_t physical;
  uint32_t expected_provider_base;
};

constexpr DirectOwnerPointer kDirectOwnerPointers[] = {
    {UINT64_C(0x2a2abdce0), UINT32_C(0x6294d3cc)},  // 49169
    {UINT64_C(0x2a2abdcf0), UINT32_C(0x6294d1be)},  // 35818
    {UINT64_C(0x2a2abdd28), UINT32_C(0x6294d587)},  // 59665
    {UINT64_C(0x2a2abdd78), UINT32_C(0x6294d732)},  // 61633
    {UINT64_C(0x2a2abddfc), UINT32_C(0x62920f84)},  // 34225
    {UINT64_C(0x2a2abde0c), UINT32_C(0x62920d63)},  // 26481
    {UINT64_C(0x2a2abde14), UINT32_C(0x6292096d)},  // 7745
};

struct SettingCatalogBank {
  uint8_t setting;
  uint16_t source;
  uint16_t selector;
  uint32_t descriptor_runtime;
  uint32_t values_runtime;
  uint16_t byte_length;
  uint32_t descriptor_flags;
  uint32_t values_fnv1a;
};

struct SettingCatalogFormatterBank {
  uint8_t setting;
  uint16_t selector;
  uint32_t records_runtime;
  uint16_t count;
  uint32_t records_fnv1a;
  uint32_t strings_fnv1a;
};

constexpr SettingCatalogBank kSettingCatalogBanks[] = {
    {1, 1667, 85, 0x60616924, 0x60611e90, 80, 0x00000301,
     0xc739c549},
    {1, 1668, 85, 0x60616938, 0x60611e90, 80, 0x00000301,
     0xc739c549},
    {1, 1667, 128, 0x60616960, 0x60611ee0, 54, 0x00000301,
     0x50950226},
    {1, 1668, 128, 0x60616974, 0x60611ee0, 54, 0x00000301,
     0x50950226},
    {1, 1667, 256, 0x6061699c, 0x60611f16, 28, 0x00000301,
     0xed2c68c2},
    {1, 1668, 256, 0x606169b0, 0x60611f16, 28, 0x00000301,
     0xed2c68c2},
    {0, 1669, 85, 0x606169d8, 0x60611f32, 138, 0x00000401,
     0x28de41b1},
    {0, 1670, 85, 0x606169ec, 0x60611f32, 138, 0x00000401,
     0x28de41b1},
    {0, 1669, 128, 0x60616a14, 0x60611fbc, 100, 0x00000401,
     0xf194afff},
    {0, 1670, 128, 0x60616a28, 0x60611fbc, 100, 0x00000401,
     0xf194afff},
    {0, 1669, 256, 0x60616a50, 0x60612020, 58, 0x00000401,
     0x3ddd9979},
    {0, 1670, 256, 0x60616a64, 0x60612020, 58, 0x00000401,
     0x3ddd9979},
    {2, 1671, 85, 0x60616b40, 0x60612150, 62, 0x00000300,
     0x950f7bbc},
    {2, 1671, 256, 0x60616b68, 0x6061218e, 26, 0x00000300,
     0xd1740ba7},
    {2, 1672, 85, 0x60616b7c, 0x606121a8, 40, 0x00000301,
     0x8c38e13a},
    {2, 1672, 256, 0x60616b90, 0x606121d0, 16, 0x00000301,
     0x3f244916},
    {0, 1445, 128, 0x6061bf64, 0x6061451c, 6, 0x00000501,
     0x73667eea},
};

constexpr SettingCatalogFormatterBank kSettingCatalogFormatterBanks[] = {
    {0, 256, 0x619336c0, 77, 0x7457e5a9, 0xe7b30b36},
    {0, 128, 0x61933934, 83, 0x79108e02, 0x1af1fb6d},
    {0, 85, 0x61933bcc, 83, 0x50b6ca10, 0x1af1fb6d},
    {1, 256, 0x61932ee0, 41, 0x6c8ccc9b, 0x9a928cdd},
    {1, 128, 0x61933040, 47, 0xd88200ea, 0xaf73cdff},
    {1, 85, 0x619331b8, 47, 0x781fc476, 0xaf73cdff},
    {2, 0, 0x61932d18, 42, 0x6f9469b5, 0x97149625},
};

// The three exposure formatter registry records and the two property-3/4
// variant descriptors are part of the firmware identity, not merely lookup
// hints.  Property 6 is one unordered raw/label table.  Properties 3 and 4
// select their exact 256/128/85-step tables from these six words.
constexpr uint32_t kSettingCatalogFormatterRegistryIndex[3] = {33, 31, 30};
constexpr uint32_t kSettingCatalogFormatterRegistryExpected[3][4] = {
    {4, 2, kSettingCatalogShutterVariantsRuntime, 0},
    {3, 2, kSettingCatalogApertureVariantsRuntime, 0},
    {6, 0, 0x61932d18, 42},
};
constexpr uint32_t kSettingCatalogApertureVariantsExpected[6] = {
    0x61932ee0, 41, 0x61933040, 47, 0x619331b8, 47};
constexpr uint32_t kSettingCatalogShutterVariantsExpected[6] = {
    0x619336c0, 77, 0x61933934, 83, 0x61933bcc, 83};

static_assert(sizeof(kSettingCatalogBanks) / sizeof(kSettingCatalogBanks[0]) ==
                  17u &&
                  sizeof(kSettingCatalogFormatterBanks) /
                          sizeof(kSettingCatalogFormatterBanks[0]) ==
                      7u &&
                  kSettingCatalogCommonValuesPhysical / kPageSize ==
                      UINT64_C(0x2a0611) &&
                  (UINT64_C(0x2a06121d0) & (kPageSize - 1u)) + 16u <=
                      kSettingCatalogCommonValuesMappingLength &&
                  (UINT64_C(0x2a1933bcc) & (kPageSize - 1u)) +
                          83u * kSettingCatalogFormatterRecordSize <=
                      kSettingCatalogFormatterMappingLength &&
                  kSettingCatalogFormatterRegistryPhysical +
                          kSettingCatalogFormatterRegistryCount *
                              kSettingCatalogFormatterRegistryRecordSize <=
                      (kSettingCatalogFormatterPhysical &
                       ~(static_cast<uint64_t>(kPageSize) - 1u)) +
                          kSettingCatalogFormatterMappingLength,
              "v1.9 setting-catalog mapping bounds changed");

static_assert(kDirectProviderCount == 36 &&
                  (kDirectProviderDescriptorsPhysical &
                   ~(static_cast<uint64_t>(kPageSize) - 1u)) +
                          kDirectProviderDescriptorsMappingLength >=
                      kDirectProviderMapSecondaryPhysical +
                          kDirectProviderMapSecondaryCount * 4u &&
                  (kDirectProviderPrimaryBucketsPhysical &
                   (kPageSize - 1u)) +
                          (UINT16_MAX >> 4) * 8u + 8u <=
                      kDirectProviderBucketsMappingLength &&
                  (kDirectHelperIndexPhysical & (kPageSize - 1u)) + 1u <=
                      kPageSize &&
                  (kDirectHelperRecordsPhysical & (kPageSize - 1u)) +
                          kDirectHelperRecordCount *
                              kDirectHelperRecordStride <=
                      kPageSize &&
                  (kDirectRamde48Physical & (kPageSize - 1u)) + 1u <=
                      kPageSize &&
                  kDirectSettingDispatchSemanticPhysical +
                          kDirectSettingDispatchSemanticLength <=
                      kDirectSettingDispatchPhysical +
                          kDirectSettingDispatchMappingLength &&
                  kDirectPostfilterSemanticPhysical +
                          kDirectPostfilterSemanticLength <=
                      kDirectPostfilterPhysical +
                          kDirectPostfilterMappingLength &&
                  kDirectApertureLegalitySemanticPhysical +
                          kDirectApertureLegalitySemanticLength <=
                      kDirectApertureLegalityPhysical +
                          kDirectApertureLegalityMappingLength &&
                  kDirectShutterLegalitySemanticPhysical +
                          kDirectShutterLegalitySemanticLength <=
                      kDirectShutterLegalityPhysical +
                          kDirectShutterLegalityMappingLength &&
                  kDirectIsoRegistrySemanticPhysical +
                          kDirectIsoRegistrySemanticLength <=
                      kDirectIsoRegistryPhysical +
                          kDirectIsoRegistryMappingLength,
              "v1.9 direct provider mapping bounds changed");

static_assert(kDirectLensRuntime[1] - kDirectLensRuntime[0] == 0x1ea00u &&
                  kDirectLensPhysical[1] - kDirectLensPhysical[0] ==
                      0x1ea00u &&
                  (kDirectLensPhysical[0] & (kPageSize - 1u)) +
                          kDirectLensTeleOffset + sizeof(uint16_t) <=
                      kPageSize &&
                  (kDirectLensPhysical[1] & (kPageSize - 1u)) +
                          kDirectLensTeleOffset + sizeof(uint16_t) <=
                      kPageSize &&
                  ((kDirectLensPhysical[0] +
                    kDirectLensCalibrationOffset) &
                   (kPageSize - 1u)) +
                          32u * kDirectLensCalibrationStride <=
                      kPageSize &&
                  ((kDirectLensPhysical[1] +
                    kDirectLensCalibrationOffset) &
                   (kPageSize - 1u)) +
                          32u * kDirectLensCalibrationStride <=
                      kPageSize &&
                  ((kDirectLensPhysical[0] + kDirectLensNormalIsOffset) &
                   (kPageSize - 1u)) + 2u <= kPageSize &&
                  ((kDirectLensPhysical[1] + kDirectLensNormalIsOffset) &
                   (kPageSize - 1u)) + 2u <= kPageSize,
              "v1.9 direct lens mapping bounds changed");

// Stock diagnostic capture globals.  0x5012a3bc allocates 0x4000 bytes,
// stores the returned uncached pointer at 0xa32d4cc4, and zeroes the adjacent
// read/write halfwords.  The size-class-2 allocator used for this request
// returns one of the exact 0x4080-stride size-class-2 blocks in the range
// below.  A prior live retained capture used 0xa2722600, independently
// confirming both the pool and block lattice.  Linux physical addresses add
// the main-RTOS 0x200000000 aperture prefix to an uncached 32-bit pointer.
constexpr uint64_t kShellCaptureStatePhysical = UINT64_C(0x2a32d4cc4);
constexpr size_t kShellCaptureReadOffset = 4;
constexpr size_t kShellCaptureWriteOffset = 6;
constexpr uint32_t kShellCapturePoolBegin = UINT32_C(0xa268d380);
constexpr uint32_t kShellCapturePoolEnd = UINT32_C(0xa273a900);
constexpr uint32_t kShellCaptureBlockStride = UINT32_C(0x4080);
constexpr uint64_t kMainRtosPhysicalPrefix = UINT64_C(0x200000000);

// v1.9 DCF untransferred-record registry.  The descriptor table contains two
// 24-byte entries keyed 0/1.  Each entry's +8 pointer is the main-RTOS
// uncached alias of a 3000-record table and +12 is its exact record count.
// The Linux mappings are optional and read-only: startup/local rollover must
// first shadow a stable direct list against stock 94BB before timed discovery
// may use it.
constexpr uint64_t kTransferDescriptorPhysical = UINT64_C(0x2a2ce02ac);
constexpr size_t kTransferDescriptorStride = 24;
constexpr size_t kTransferDescriptorPointerOffset = 8;
constexpr size_t kTransferDescriptorCountOffset = 12;
constexpr size_t kTransferDescriptorOccupiedOffset = 16;
constexpr uint32_t kTransferRecordPointers[2] = {
    UINT32_C(0xa3ef4800), UINT32_C(0xa3f0bf00)};
constexpr uint64_t kTransferRecordPhysical[2] = {
    UINT64_C(0x2a3ef4800), UINT64_C(0x2a3f0bf00)};
constexpr size_t kTransferRecordStride = 32;
constexpr size_t kTransferRecordRelevantBytes = 20;
constexpr size_t kTransferRecordMetadataWords =
    kTransferRecordRelevantBytes / sizeof(uint32_t) - 2u;
constexpr size_t kTransferRecordBytes =
    static_cast<size_t>(kDevMemTransferRecordCapacity) *
    kTransferRecordStride;
constexpr size_t kTransferRecordMappingLengths[2] = {0x18000, 0x19000};

static_assert(kTransferRecordBytes == 0x17700,
              "v1.9 transfer-record table size changed");
static_assert((kTransferRecordPhysical[0] & (kPageSize - 1u)) +
                      kTransferRecordBytes <=
                  kTransferRecordMappingLengths[0] &&
              (kTransferRecordPhysical[1] & (kPageSize - 1u)) +
                      kTransferRecordBytes <=
                  kTransferRecordMappingLengths[1],
              "transfer-record table crosses its read-only mapping");

static_assert(kBleHandlerStatePhysical / kPageSize ==
                  kBleModePhysical / kPageSize &&
              kBleHandlerStatePhysical / kPageSize ==
                  kBlePassCheckPhysical / kPageSize &&
              kBleHandlerStatePhysical / kPageSize ==
                  kBleConverterStatePhysical / kPageSize,
              "BLE state candidates no longer share one page");
static_assert((kEventInitCookiePhysical & 0xfffu) + sizeof(uint8_t) <=
                  kPageSize &&
              (kBleHandlerStatePhysical & 0xfffu) + sizeof(uint32_t) <=
                  kPageSize &&
              (kBleModePhysical & 0xfffu) + sizeof(uint16_t) <= kPageSize &&
              (kBlePassCheckPhysical & 0xfffu) + sizeof(uint16_t) <=
                  kPageSize &&
              (kBleConverterStatePhysical & 0xfffu) + sizeof(uint32_t) <=
                  kPageSize &&
              (kStationStatePhysical & 0xfffu) + 5u * sizeof(uint16_t) <=
                  kPageSize &&
              (kShutterTriggerStatePhysical & 0xfffu) + sizeof(uint8_t) <=
                  kPageSize,
              "state candidate crosses its read-only mapping");
static_assert((kShellCaptureStatePhysical & 0xfffu) +
                      kShellCaptureWriteOffset + sizeof(uint16_t) <=
                  kPageSize &&
                  kDevMemShellCaptureCapacity == 0x4000 &&
                  (kShellCapturePoolEnd - kShellCapturePoolBegin) %
                          kShellCaptureBlockStride ==
                      0,
              "shell capture state or capacity changed");
static_assert(UINT64_C(0x2a31f4824) + sizeof(uint32_t) <=
                  (kSettingProviderPointerTablePhysical &
                   ~(static_cast<uint64_t>(kPageSize) - 1u)) +
                      kSettingProviderPointerMappingLength,
              "setting provider cache crosses its mapping");
static_assert((kBleHandlerStatePhysical & 3u) == 0 &&
                  (kBleModePhysical & 1u) == 0 &&
                  (kBlePassCheckPhysical & 1u) == 0 &&
                  (kBleConverterStatePhysical & 3u) == 0 &&
                  (kStationStatePhysical & 1u) == 0,
              "state candidate has unexpected alignment");

void memory_barrier() { asm volatile("dmb sy" ::: "memory"); }

bool fail_errno(Error *error, const char *operation, long result) {
  return fail_u64(error, operation,
                  static_cast<uint32_t>(os::error_number(result)));
}

const uint8_t *at(const void *page, uint64_t physical) {
  return static_cast<const uint8_t *>(page) +
         static_cast<size_t>(physical & (kPageSize - 1u));
}

const uint8_t *at_region(const void *mapping, uint64_t physical,
                         uint64_t region_start) {
  return static_cast<const uint8_t *>(mapping) +
         static_cast<size_t>(physical -
                             (region_start & ~(uint64_t(kPageSize) - 1u)));
}

template <typename T>
uint32_t read_scalar(const void *page, uint64_t physical) {
  const auto *address =
      reinterpret_cast<volatile const T *>(at(page, physical));
  memory_barrier();
  const T value = *address;
  memory_barrier();
  return static_cast<uint32_t>(value);
}

uint16_t load_le16(const uint8_t *bytes) {
  return static_cast<uint16_t>(bytes[0]) |
         static_cast<uint16_t>(static_cast<uint16_t>(bytes[1]) << 8);
}

uint32_t load_le32(const uint8_t *bytes) {
  return static_cast<uint32_t>(bytes[0]) |
         static_cast<uint32_t>(bytes[1]) << 8 |
         static_cast<uint32_t>(bytes[2]) << 16 |
         static_cast<uint32_t>(bytes[3]) << 24;
}

void read_volatile_bytes(const void *source, uint8_t *destination,
                         size_t size) {
  const auto *bytes = static_cast<volatile const uint8_t *>(source);
  memory_barrier();
  for (size_t i = 0; i < size; ++i) destination[i] = bytes[i];
  memory_barrier();
}

// Used only inside an explicitly bracketed coherent snapshot.  Keep the
// volatile byte loads (the camera owns these locations) but let the caller
// provide the two- or three-DMB transaction boundary instead of fencing every
// tiny scalar independently.
void read_volatile_bytes_unfenced(const void *source, uint8_t *destination,
                                  size_t size) {
  const auto *bytes = static_cast<volatile const uint8_t *>(source);
  for (size_t i = 0; i < size; ++i) destination[i] = bytes[i];
}

bool bytes_equal(const uint8_t *left, const uint8_t *right, size_t size) {
  for (size_t i = 0; i < size; ++i) {
    if (left[i] != right[i]) return false;
  }
  return true;
}

uint32_t fnv1a_bytes(const uint8_t *bytes, size_t size,
                     uint32_t hash = UINT32_C(2166136261)) {
  for (size_t i = 0; i < size; ++i) {
    hash ^= bytes[i];
    hash *= UINT32_C(16777619);
  }
  return hash;
}

uint32_t fnv1a_volatile(const void *source, size_t size) {
  const auto *bytes = static_cast<volatile const uint8_t *>(source);
  uint32_t hash = UINT32_C(2166136261);
  memory_barrier();
  for (size_t i = 0; i < size; ++i) {
    hash ^= bytes[i];
    hash *= UINT32_C(16777619);
  }
  memory_barrier();
  return hash;
}

const SettingCatalogBank *find_setting_catalog_bank(uint8_t setting,
                                                    uint16_t source,
                                                    uint16_t selector) {
  const SettingCatalogBank *match = nullptr;
  for (const SettingCatalogBank &bank : kSettingCatalogBanks) {
    if (bank.setting != setting || bank.source != source ||
        bank.selector != selector) {
      continue;
    }
    if (match != nullptr) return nullptr;
    match = &bank;
  }
  return match;
}

const SettingCatalogFormatterBank *find_setting_catalog_formatter(
    uint8_t setting, uint16_t selector) {
  const SettingCatalogFormatterBank *match = nullptr;
  for (const SettingCatalogFormatterBank &bank :
       kSettingCatalogFormatterBanks) {
    if (bank.setting != setting || bank.selector != selector) continue;
    if (match != nullptr) return nullptr;
    match = &bank;
  }
  return match;
}

bool catalog_runtime_range_valid(uint32_t pointer, uint32_t begin,
                                 uint32_t end, size_t size) {
  return pointer >= begin && pointer <= end &&
         size <= static_cast<size_t>(end - pointer);
}

int16_t direct_mode_helper(const int32_t context[direct_catalog::kContextCount]) {
  const int32_t s41 = context[0];
  const int32_t s44 = context[1];
  const int32_t s109 = context[2];
  const int32_t s277 = context[3];
  const int32_t s26 = context[4];
  const int32_t s1444 = context[5];
  switch (s41) {
    case 1:
    case 5:
      return 53;
    case 2:
    case 6:
      return 54;
    case 3:
    case 7:
      return 55;
    case 4:
    case 8:
      return 56;
    default:
      if (s44 != 0) return 10;
      if (s109 == 2) return 49;
      if (s109 == 1) return s277 == 0 ? 42 : 51;
      switch (s26) {
        case 4:
          return 2;
        case 5:
          return 3;
        case 6:
          return s1444 == 1 ? 62 : 4;
        case 8:
          return 12;
        case 24:
        case 190:
        case 191:
        case 192:
          return 29;
        case 36:
          return 45;
        case 37:
          return 46;
        case 101:
          return 52;
        case 185:
        case 186:
        case 187:
        case 188:
        case 189:
          return 57;
        default:
          return 1;
      }
  }
}

void direct_sources(const int32_t context[direct_catalog::kContextCount],
                    int16_t helper,
                    uint16_t sources[direct_catalog::kSettingCount]) {
  const bool alternate = helper == 10 || context[6] == 1;
  sources[direct_catalog::kShutter] =
      alternate ? UINT16_C(1670)
                : (context[5] == 1 && context[7] == 6 ? UINT16_C(1445)
                                                       : UINT16_C(1669));
  sources[direct_catalog::kAperture] =
      alternate ? UINT16_C(1668) : UINT16_C(1667);
  sources[direct_catalog::kIso] =
      alternate ? UINT16_C(1672) : UINT16_C(1671);
}

bool direct_fingerprint_matches(const void *mapping, uint64_t physical,
                                uint64_t mapping_physical, size_t length,
                                uint32_t expected) {
  if (mapping == nullptr || length == 0) return false;
  const uint8_t *bytes = at_region(mapping, physical, mapping_physical);
  const uint32_t first = fnv1a_volatile(bytes, length);
  const uint32_t second = fnv1a_volatile(bytes, length);
  return first == expected && second == expected;
}

uint64_t direct_public_descriptor_physical(const DirectProviderSpec &spec) {
  if (spec.public_id >= 0 &&
      static_cast<uint32_t>(spec.public_id) <
          kDirectProviderMapPrimaryCount) {
    return kDirectProviderDescriptorsPhysical +
           static_cast<uint64_t>(spec.public_id) * sizeof(uint32_t);
  }
  if (spec.public_id >= static_cast<int32_t>(kDirectProviderMapSecondaryBase) &&
      static_cast<uint32_t>(spec.public_id) <
          kDirectProviderMapSecondaryBase +
              kDirectProviderMapSecondaryCount) {
    return kDirectProviderMapSecondaryPhysical +
           static_cast<uint64_t>(spec.public_id -
                                 kDirectProviderMapSecondaryBase) *
               sizeof(uint32_t);
  }
  return 0;
}

uint8_t direct_provider_width(uint16_t encoded) {
  if ((encoded & 7u) == 4u) return 4;
  if ((encoded & 7u) == 2u) return 2;
  return 1;
}

bool decode_direct_provider_value(uint16_t encoded, const uint8_t *bytes,
                                  int32_t *value) {
  if (bytes == nullptr || value == nullptr) return false;
  const uint8_t width = direct_provider_width(encoded);
  uint32_t raw = bytes[0];
  if (width >= 2) raw |= static_cast<uint32_t>(bytes[1]) << 8;
  if (width == 4) {
    raw |= static_cast<uint32_t>(bytes[2]) << 16;
    raw |= static_cast<uint32_t>(bytes[3]) << 24;
  }
  const bool is_signed = (encoded & 8u) != 0;
  if (is_signed && width == 1) {
    *value = static_cast<int8_t>(raw);
  } else if (is_signed && width == 2) {
    *value = static_cast<int16_t>(raw);
  } else if (is_signed && width == 4) {
    *value = static_cast<int32_t>(raw);
  } else {
    if (width == 4 && raw > static_cast<uint32_t>(INT32_MAX)) return false;
    *value = static_cast<int32_t>(raw);
  }
  return true;
}

}  // namespace

void DevMemState::initialize() {
  memory_fd_ = -1;
  event_page_ = nullptr;
  ble_page_ = nullptr;
  station_page_ = nullptr;
  trigger_page_ = nullptr;
  setting_provider_pointer_pages_ = nullptr;
  setting_catalog_common_values_pages_ = nullptr;
  setting_catalog_special_values_page_ = nullptr;
  setting_catalog_common_descriptors_page_ = nullptr;
  setting_catalog_special_descriptor_page_ = nullptr;
  setting_catalog_formatter_pages_ = nullptr;
  setting_catalog_provider_descriptors_pages_ = nullptr;
  setting_catalog_provider_buckets_pages_ = nullptr;
  for (unsigned i = 0; i < 3; ++i) {
    setting_catalog_provider_value_pages_[i] = nullptr;
  }
  setting_catalog_helper_state_page_ = nullptr;
  setting_catalog_helper_mode_page_ = nullptr;
  setting_catalog_dispatch_pages_ = nullptr;
  setting_catalog_postfilter_page_ = nullptr;
  setting_catalog_aperture_legality_page_ = nullptr;
  setting_catalog_shutter_legality_pages_ = nullptr;
  setting_catalog_iso_legality_pages_ = nullptr;
  setting_catalog_iso_registry_page_ = nullptr;
  direct_exposure_static_certified_ = false;
  direct_exposure_native_cache_valid_ = false;
  direct_exposure_native_exposure_step_ = 0;
  direct_exposure_native_iso_step_ = 0;
  for (unsigned i = 0; i < kDevMemSettingCatalogCount; ++i) {
    direct_exposure_native_counts_[i] = 0;
    direct_exposure_native_sources_[i] = 0;
  }
  for (unsigned i = 0; i < 2; ++i) {
    lens_head_pages_[i] = nullptr;
    lens_calibration_pages_[i] = nullptr;
    lens_feature_pages_[i] = nullptr;
  }
  lens_control_page_ = nullptr;
  lens_focus_page_ = nullptr;
  lens_commit_page_ = nullptr;
  lens_slots_page_ = nullptr;
  lens_connection_page_ = nullptr;
  shell_capture_state_page_ = nullptr;
  shell_capture_ring_page_ = nullptr;
  shell_capture_ring_mapping_length_ = 0;
  shell_capture_pointer_ = 0;
  transfer_descriptor_page_ = nullptr;
  transfer_record_pages_[0] = nullptr;
  transfer_record_pages_[1] = nullptr;
}

bool DevMemState::open_read_only(Error *error) {
  clear_error(error);
  if (opened() || event_page_ != nullptr || ble_page_ != nullptr ||
      station_page_ != nullptr || trigger_page_ != nullptr ||
      setting_provider_pointer_pages_ != nullptr ||
      setting_catalog_common_values_pages_ != nullptr ||
      setting_catalog_special_values_page_ != nullptr ||
      setting_catalog_common_descriptors_page_ != nullptr ||
      setting_catalog_special_descriptor_page_ != nullptr ||
      setting_catalog_formatter_pages_ != nullptr ||
      setting_catalog_provider_descriptors_pages_ != nullptr ||
      setting_catalog_provider_buckets_pages_ != nullptr ||
      setting_catalog_provider_value_pages_[0] != nullptr ||
      setting_catalog_provider_value_pages_[1] != nullptr ||
      setting_catalog_provider_value_pages_[2] != nullptr ||
      setting_catalog_helper_state_page_ != nullptr ||
      setting_catalog_helper_mode_page_ != nullptr ||
      setting_catalog_dispatch_pages_ != nullptr ||
      setting_catalog_postfilter_page_ != nullptr ||
      setting_catalog_aperture_legality_page_ != nullptr ||
      setting_catalog_shutter_legality_pages_ != nullptr ||
      setting_catalog_iso_legality_pages_ != nullptr ||
      setting_catalog_iso_registry_page_ != nullptr ||
      lens_head_pages_[0] != nullptr || lens_head_pages_[1] != nullptr ||
      lens_calibration_pages_[0] != nullptr ||
      lens_calibration_pages_[1] != nullptr ||
      lens_feature_pages_[0] != nullptr ||
      lens_feature_pages_[1] != nullptr || lens_control_page_ != nullptr ||
      lens_focus_page_ != nullptr || lens_commit_page_ != nullptr ||
      lens_slots_page_ != nullptr || lens_connection_page_ != nullptr ||
      shell_capture_state_page_ != nullptr ||
      shell_capture_ring_page_ != nullptr ||
      transfer_descriptor_page_ != nullptr ||
      transfer_record_pages_[0] != nullptr ||
      transfer_record_pages_[1] != nullptr) {
    return fail(error, "read-only /dev/mem state view is already open");
  }

  const int flags = os::kOpenReadOnly | os::kOpenSync |
                    os::kOpenNoFollow | os::kOpenCloseOnExec;
  const long descriptor = os::open("/dev/mem", flags);
  if (os::failed(descriptor)) {
    return fail_errno(error, "opening read-only /dev/mem errno ", descriptor);
  }
  memory_fd_ = static_cast<int>(descriptor);

  auto map_page = [&](uint64_t physical, void **mapping) -> bool {
    const uint64_t page = physical / kPageSize;
    if (page > UINT32_MAX) {
      return fail(error, "state physical page is out of mmap2 range");
    }
    const long address =
        os::mmap2(nullptr, kPageSize, os::kProtectionRead, os::kMapShared,
                  memory_fd_, static_cast<uint32_t>(page));
    if (os::failed(address)) {
      return fail_errno(error, "mapping read-only /dev/mem errno ", address);
    }
    *mapping = reinterpret_cast<void *>(static_cast<uintptr_t>(address));
    return true;
  };

  if (!map_page(kEventInitCookiePhysical, &event_page_) ||
      !map_page(kBleHandlerStatePhysical, &ble_page_) ||
      !map_page(kStationStatePhysical, &station_page_) ||
      !map_page(kShutterTriggerStatePhysical, &trigger_page_)) {
    const Error primary = error == nullptr ? Error{} : *error;
    Error ignored = {};
    (void)close(&ignored);
    if (error != nullptr) *error = primary;
    return false;
  }

  // The DCF tables are an optimization, not an availability requirement.  A
  // firmware-layout or mmap mismatch leaves all proven scalar mappings open
  // and selects the retained PTP 94BB fallback instead.
  auto map_region = [&](uint64_t physical, size_t length,
                        void **mapping) -> bool {
    const uint64_t page = physical / kPageSize;
    if (page > UINT32_MAX) return false;
    const long address =
        os::mmap2(nullptr, length, os::kProtectionRead, os::kMapShared,
                  memory_fd_, static_cast<uint32_t>(page));
    if (os::failed(address)) return false;
    *mapping = reinterpret_cast<void *>(static_cast<uintptr_t>(address));
    return true;
  };

  // Shell capture remains an optional optimization.  The stock 0x902A path
  // is complete without this page, and the ring itself cannot be mapped until
  // the stock allocator publishes its dynamic pointer.
  (void)map_region(kShellCaptureStatePhysical, kPageSize,
                   &shell_capture_state_page_);
  (void)map_region(kSettingProviderPointerTablePhysical,
                   kSettingProviderPointerMappingLength,
                   &setting_provider_pointer_pages_);

  // The catalog mappings are optional at process-open time, but the API never
  // falls back to a shell table walk.  They are promoted only as one complete
  // group; a missing page makes the strict catalog snapshot fail closed while
  // all unrelated camera functionality remains available.
  if (!map_region(kSettingCatalogCommonValuesPhysical,
                  kSettingCatalogCommonValuesMappingLength,
                  &setting_catalog_common_values_pages_) ||
      !map_region(kSettingCatalogSpecialValuesPhysical,
                  kSettingCatalogSpecialValuesMappingLength,
                  &setting_catalog_special_values_page_) ||
      !map_region(kSettingCatalogCommonDescriptorsPhysical,
                  kSettingCatalogCommonDescriptorsMappingLength,
                  &setting_catalog_common_descriptors_page_) ||
      !map_region(kSettingCatalogSpecialDescriptorPhysical,
                  kSettingCatalogSpecialDescriptorMappingLength,
                  &setting_catalog_special_descriptor_page_) ||
      !map_region(kSettingCatalogFormatterPhysical,
                  kSettingCatalogFormatterMappingLength,
                  &setting_catalog_formatter_pages_) ||
      !map_region(kDirectProviderDescriptorsPhysical,
                  kDirectProviderDescriptorsMappingLength,
                  &setting_catalog_provider_descriptors_pages_) ||
      !map_region(kDirectProviderBucketsPhysical,
                  kDirectProviderBucketsMappingLength,
                  &setting_catalog_provider_buckets_pages_) ||
      !map_region(kDirectProviderValuePhysical[0], kPageSize,
                  &setting_catalog_provider_value_pages_[0]) ||
      !map_region(kDirectProviderValuePhysical[1], kPageSize,
                  &setting_catalog_provider_value_pages_[1]) ||
      !map_region(kDirectProviderValuePhysical[2], kPageSize,
                  &setting_catalog_provider_value_pages_[2]) ||
      !map_region(kDirectHelperStatePhysical, kPageSize,
                  &setting_catalog_helper_state_page_) ||
      !map_region(kDirectHelperModePhysical, kPageSize,
                  &setting_catalog_helper_mode_page_) ||
      !map_region(kDirectSettingDispatchPhysical,
                  kDirectSettingDispatchMappingLength,
                  &setting_catalog_dispatch_pages_) ||
      !map_region(kDirectPostfilterPhysical,
                  kDirectPostfilterMappingLength,
                  &setting_catalog_postfilter_page_) ||
      !map_region(kDirectApertureLegalityPhysical,
                  kDirectApertureLegalityMappingLength,
                  &setting_catalog_aperture_legality_page_) ||
      !map_region(kDirectShutterLegalityPhysical,
                  kDirectShutterLegalityMappingLength,
                  &setting_catalog_shutter_legality_pages_) ||
      !map_region(kDirectIsoLegalityPhysical,
                  kDirectIsoLegalityMappingLength,
                  &setting_catalog_iso_legality_pages_) ||
      !map_region(kDirectIsoRegistryPhysical,
                  kDirectIsoRegistryMappingLength,
                  &setting_catalog_iso_registry_page_)) {
    struct OptionalMapping {
      void **address;
      size_t length;
    };
    OptionalMapping catalog_mappings[] = {
        {&setting_catalog_iso_registry_page_,
         kDirectIsoRegistryMappingLength},
        {&setting_catalog_iso_legality_pages_,
         kDirectIsoLegalityMappingLength},
        {&setting_catalog_shutter_legality_pages_,
         kDirectShutterLegalityMappingLength},
        {&setting_catalog_aperture_legality_page_,
         kDirectApertureLegalityMappingLength},
        {&setting_catalog_postfilter_page_,
         kDirectPostfilterMappingLength},
        {&setting_catalog_dispatch_pages_,
         kDirectSettingDispatchMappingLength},
        {&setting_catalog_helper_mode_page_, kPageSize},
        {&setting_catalog_helper_state_page_, kPageSize},
        {&setting_catalog_provider_value_pages_[2], kPageSize},
        {&setting_catalog_provider_value_pages_[1], kPageSize},
        {&setting_catalog_provider_value_pages_[0], kPageSize},
        {&setting_catalog_provider_buckets_pages_,
         kDirectProviderBucketsMappingLength},
        {&setting_catalog_provider_descriptors_pages_,
         kDirectProviderDescriptorsMappingLength},
        {&setting_catalog_formatter_pages_,
         kSettingCatalogFormatterMappingLength},
        {&setting_catalog_special_descriptor_page_,
         kSettingCatalogSpecialDescriptorMappingLength},
        {&setting_catalog_common_descriptors_page_,
         kSettingCatalogCommonDescriptorsMappingLength},
        {&setting_catalog_special_values_page_,
         kSettingCatalogSpecialValuesMappingLength},
        {&setting_catalog_common_values_pages_,
         kSettingCatalogCommonValuesMappingLength},
    };
    for (const OptionalMapping &mapping : catalog_mappings) {
      if (*mapping.address == nullptr) continue;
      if (!os::failed(os::munmap(*mapping.address, mapping.length))) {
        *mapping.address = nullptr;
      }
    }
  }

  // Lens metadata is optional and independent from the exposure backend.
  // Promote its eleven pages only as one complete read-only group so a
  // partially mapped lens never leaks partial identity or focal data.
  if (!map_region(kDirectLensHeadPagePhysical[0], kPageSize,
                  &lens_head_pages_[0]) ||
      !map_region(kDirectLensHeadPagePhysical[1], kPageSize,
                  &lens_head_pages_[1]) ||
      !map_region(kDirectLensCalibrationPagePhysical[0], kPageSize,
                  &lens_calibration_pages_[0]) ||
      !map_region(kDirectLensCalibrationPagePhysical[1], kPageSize,
                  &lens_calibration_pages_[1]) ||
      !map_region(kDirectLensFeaturePagePhysical[0], kPageSize,
                  &lens_feature_pages_[0]) ||
      !map_region(kDirectLensFeaturePagePhysical[1], kPageSize,
                  &lens_feature_pages_[1]) ||
      !map_region(kDirectLensControlPagePhysical, kPageSize,
                  &lens_control_page_) ||
      !map_region(kDirectLensFocusPagePhysical, kPageSize,
                  &lens_focus_page_) ||
      !map_region(kDirectLensCommitPagePhysical, kPageSize,
                  &lens_commit_page_) ||
      !map_region(kDirectLensSlotsPagePhysical, kPageSize,
                  &lens_slots_page_) ||
      !map_region(kDirectLensConnectionPagePhysical, kPageSize,
                  &lens_connection_page_)) {
    void **lens_mappings[] = {
        &lens_connection_page_,       &lens_slots_page_,
        &lens_commit_page_,           &lens_focus_page_,
        &lens_control_page_,          &lens_feature_pages_[1],
        &lens_feature_pages_[0],      &lens_calibration_pages_[1],
        &lens_calibration_pages_[0],  &lens_head_pages_[1],
        &lens_head_pages_[0],
    };
    for (void **mapping : lens_mappings) {
      if (*mapping == nullptr) continue;
      if (!os::failed(os::munmap(*mapping, kPageSize))) *mapping = nullptr;
    }
  }

  if (!map_region(kTransferDescriptorPhysical, kPageSize,
                  &transfer_descriptor_page_) ||
      !map_region(kTransferRecordPhysical[0],
                  kTransferRecordMappingLengths[0],
                  &transfer_record_pages_[0]) ||
      !map_region(kTransferRecordPhysical[1],
                  kTransferRecordMappingLengths[1],
                  &transfer_record_pages_[1])) {
    if (transfer_record_pages_[1] != nullptr) {
      if (!os::failed(os::munmap(transfer_record_pages_[1],
                                 kTransferRecordMappingLengths[1]))) {
        transfer_record_pages_[1] = nullptr;
      }
    }
    if (transfer_record_pages_[0] != nullptr) {
      if (!os::failed(os::munmap(transfer_record_pages_[0],
                                 kTransferRecordMappingLengths[0]))) {
        transfer_record_pages_[0] = nullptr;
      }
    }
    if (transfer_descriptor_page_ != nullptr) {
      if (!os::failed(
              os::munmap(transfer_descriptor_page_, kPageSize))) {
        transfer_descriptor_page_ = nullptr;
      }
    }
  }
  return true;
}

bool DevMemState::close(Error *error) {
  clear_error(error);
  bool ok = true;
  Error first = {};
  if (shell_capture_ring_page_ != nullptr) {
    const long result = os::munmap(shell_capture_ring_page_,
                                  shell_capture_ring_mapping_length_);
    if (os::failed(result) && ok) {
      (void)fail_errno(&first, "unmapping shell capture errno ", result);
      ok = false;
    }
    shell_capture_ring_page_ = nullptr;
    shell_capture_ring_mapping_length_ = 0;
    shell_capture_pointer_ = 0;
  }
  for (unsigned slot = 2; slot != 0; --slot) {
    const unsigned index = slot - 1u;
    if (transfer_record_pages_[index] == nullptr) continue;
    const long result = os::munmap(transfer_record_pages_[index],
                                  kTransferRecordMappingLengths[index]);
    if (os::failed(result) && ok) {
      (void)fail_errno(&first, "unmapping read-only /dev/mem errno ", result);
      ok = false;
    }
    transfer_record_pages_[index] = nullptr;
  }
  if (transfer_descriptor_page_ != nullptr) {
    const long result = os::munmap(transfer_descriptor_page_, kPageSize);
    if (os::failed(result) && ok) {
      (void)fail_errno(&first, "unmapping read-only /dev/mem errno ", result);
      ok = false;
    }
    transfer_descriptor_page_ = nullptr;
  }
  struct Mapping {
    void **address;
    size_t length;
  };
  Mapping mappings[] = {
      {&lens_connection_page_, kPageSize},
      {&lens_slots_page_, kPageSize},
      {&lens_commit_page_, kPageSize},
      {&lens_focus_page_, kPageSize},
      {&lens_control_page_, kPageSize},
      {&lens_feature_pages_[1], kPageSize},
      {&lens_feature_pages_[0], kPageSize},
      {&lens_calibration_pages_[1], kPageSize},
      {&lens_calibration_pages_[0], kPageSize},
      {&lens_head_pages_[1], kPageSize},
      {&lens_head_pages_[0], kPageSize},
      {&setting_catalog_iso_registry_page_,
       kDirectIsoRegistryMappingLength},
      {&setting_catalog_iso_legality_pages_,
       kDirectIsoLegalityMappingLength},
      {&setting_catalog_shutter_legality_pages_,
       kDirectShutterLegalityMappingLength},
      {&setting_catalog_aperture_legality_page_,
       kDirectApertureLegalityMappingLength},
      {&setting_catalog_postfilter_page_,
       kDirectPostfilterMappingLength},
      {&setting_catalog_dispatch_pages_,
       kDirectSettingDispatchMappingLength},
      {&setting_catalog_helper_mode_page_, kPageSize},
      {&setting_catalog_helper_state_page_, kPageSize},
      {&setting_catalog_provider_value_pages_[2], kPageSize},
      {&setting_catalog_provider_value_pages_[1], kPageSize},
      {&setting_catalog_provider_value_pages_[0], kPageSize},
      {&setting_catalog_provider_buckets_pages_,
       kDirectProviderBucketsMappingLength},
      {&setting_catalog_provider_descriptors_pages_,
       kDirectProviderDescriptorsMappingLength},
      {&setting_catalog_formatter_pages_,
       kSettingCatalogFormatterMappingLength},
      {&setting_catalog_special_descriptor_page_,
       kSettingCatalogSpecialDescriptorMappingLength},
      {&setting_catalog_common_descriptors_page_,
       kSettingCatalogCommonDescriptorsMappingLength},
      {&setting_catalog_special_values_page_,
       kSettingCatalogSpecialValuesMappingLength},
      {&setting_catalog_common_values_pages_,
       kSettingCatalogCommonValuesMappingLength},
      {&setting_provider_pointer_pages_,
       kSettingProviderPointerMappingLength},
      {&shell_capture_state_page_, kPageSize},
      {&trigger_page_, kPageSize},
      {&station_page_, kPageSize},
      {&ble_page_, kPageSize},
      {&event_page_, kPageSize}};
  for (const Mapping &mapping : mappings) {
    void **address = mapping.address;
    if (*address == nullptr) continue;
    const long result = os::munmap(*address, mapping.length);
    if (os::failed(result) && ok) {
      (void)fail_errno(&first, "unmapping read-only /dev/mem errno ", result);
      ok = false;
    }
    *address = nullptr;
  }
  if (memory_fd_ >= 0) {
    const long result = os::close(memory_fd_);
    if (os::failed(result) && ok) {
      (void)fail_errno(&first, "closing read-only /dev/mem errno ", result);
      ok = false;
    }
    memory_fd_ = -1;
  }
  direct_exposure_static_certified_ = false;
  direct_exposure_native_cache_valid_ = false;
  if (!ok && error != nullptr) *error = first;
  return ok;
}

bool DevMemState::read(DevMemStateField field, uint32_t *value,
                       Error *error) const {
  clear_error(error);
  if (!opened() || event_page_ == nullptr || ble_page_ == nullptr ||
      station_page_ == nullptr || trigger_page_ == nullptr) {
    return fail(error, "read-only /dev/mem state view is not open");
  }
  if (value == nullptr) return fail(error, "missing /dev/mem state output");

  switch (field) {
    case DevMemStateField::kEventInitCookie:
      *value = read_scalar<uint8_t>(event_page_, kEventInitCookiePhysical);
      return true;
    case DevMemStateField::kBleHandlerState:
      *value = read_scalar<uint32_t>(ble_page_, kBleHandlerStatePhysical);
      return true;
    case DevMemStateField::kBleMode:
      *value = read_scalar<uint16_t>(ble_page_, kBleModePhysical);
      return true;
    case DevMemStateField::kBlePassCheck:
      *value = read_scalar<uint16_t>(ble_page_, kBlePassCheckPhysical);
      return true;
    case DevMemStateField::kBleConverterState:
      *value = read_scalar<uint32_t>(ble_page_, kBleConverterStatePhysical);
      return true;
    case DevMemStateField::kShutterTriggerState:
      *value =
          read_scalar<uint8_t>(trigger_page_, kShutterTriggerStatePhysical);
      return true;
  }
  return fail(error, "unknown /dev/mem state field");
}

bool DevMemState::read_station(DevMemStationState *state, Error *error) const {
  clear_error(error);
  if (!opened() || station_page_ == nullptr) {
    return fail(error, "read-only /dev/mem state view is not open");
  }
  if (state == nullptr) return fail(error, "missing /dev/mem station output");

  const auto *values = reinterpret_cast<volatile const uint16_t *>(
      at(station_page_, kStationStatePhysical));
  memory_barrier();
  state->station = values[0];
  state->registration = values[1];
  state->priority = values[2];
  state->connection = values[3];
  state->picture_transfer = values[4];
  memory_barrier();
  return true;
}

bool DevMemState::setting_catalog_tables_available() const {
  return opened() && setting_catalog_common_values_pages_ != nullptr &&
         setting_catalog_special_values_page_ != nullptr &&
         setting_catalog_common_descriptors_page_ != nullptr &&
         setting_catalog_special_descriptor_page_ != nullptr &&
         setting_catalog_formatter_pages_ != nullptr;
}

bool DevMemState::read_setting_catalog_tables(
    const uint16_t source_ids[kDevMemSettingCatalogCount],
    uint16_t exposure_step, uint16_t iso_step,
    DevMemSettingCatalogEntry
        entries[kDevMemSettingCatalogCount][kDevMemSettingCatalogMaxValues],
    uint16_t counts[kDevMemSettingCatalogCount], Error *error) const {
  clear_error(error);
  if (counts != nullptr) {
    for (unsigned setting = 0; setting < kDevMemSettingCatalogCount;
         ++setting) {
      counts[setting] = 0;
    }
  }
  if (!setting_catalog_tables_available()) {
    return fail(error, "direct setting catalog tables are unavailable");
  }
  if (source_ids == nullptr || entries == nullptr || counts == nullptr) {
    return fail(error, "missing direct setting catalog destination");
  }
  auto reset_outputs = [&]() {
    for (unsigned setting = 0; setting < kDevMemSettingCatalogCount;
         ++setting) {
      counts[setting] = 0;
      for (unsigned index = 0; index < kDevMemSettingCatalogMaxValues;
           ++index) {
        entries[setting][index].value = 0;
        entries[setting][index].label_length = 0;
        for (unsigned byte = 0; byte < kDevMemSettingLabelMax; ++byte) {
          entries[setting][index].label[byte] = '\0';
        }
      }
    }
  };
  auto reject = [&](const char *message) {
    reset_outputs();
    return fail(error, message);
  };
  reset_outputs();

  if ((exposure_step != 85 && exposure_step != 128 &&
       exposure_step != 256) ||
      (iso_step != 85 && iso_step != 256)) {
    return reject("unsupported direct setting catalog step");
  }

  auto validate_words = [&](uint32_t runtime, const uint32_t *expected,
                            size_t count) -> bool {
    const size_t bytes = count * sizeof(uint32_t);
    if (expected == nullptr || count == 0 || count > 6 ||
        !catalog_runtime_range_valid(runtime,
                                     kSettingCatalogFormatterRuntimeBegin,
                                     kSettingCatalogFormatterRuntimeEnd,
                                     bytes)) {
      return false;
    }
    uint8_t first[6 * sizeof(uint32_t)] = {};
    uint8_t second[6 * sizeof(uint32_t)] = {};
    const uint64_t physical =
        kMainRtosRuntimeToPhysical + static_cast<uint64_t>(runtime);
    const uint8_t *source = at_region(setting_catalog_formatter_pages_,
                                      physical,
                                      kSettingCatalogFormatterPhysical);
    read_volatile_bytes(source, first, bytes);
    read_volatile_bytes(source, second, bytes);
    if (!bytes_equal(first, second, bytes)) return false;
    for (size_t word = 0; word < count; ++word) {
      if (load_le32(first + word * sizeof(uint32_t)) != expected[word]) {
        return false;
      }
    }
    return true;
  };

  // Pin the public formatter dispatcher as well as its seven leaf tables.
  // This prevents an otherwise-valid-looking leaf array from being paired
  // with a different property/type/step dispatch layout.
  constexpr size_t registry_bytes =
      kSettingCatalogFormatterRegistryCount *
      kSettingCatalogFormatterRegistryRecordSize;
  uint8_t registry_first[registry_bytes] = {};
  uint8_t registry_second[registry_bytes] = {};
  const uint8_t *registry_source = at_region(
      setting_catalog_formatter_pages_,
      kSettingCatalogFormatterRegistryPhysical,
      kSettingCatalogFormatterPhysical);
  read_volatile_bytes(registry_source, registry_first, registry_bytes);
  read_volatile_bytes(registry_source, registry_second, registry_bytes);
  if (!bytes_equal(registry_first, registry_second, registry_bytes) ||
      fnv1a_bytes(registry_first, registry_bytes) !=
          kSettingCatalogFormatterRegistryFnv1a) {
    return reject("direct setting formatter registry changed");
  }
  for (unsigned setting = 0; setting < kDevMemSettingCatalogCount;
       ++setting) {
    const uint32_t runtime =
        static_cast<uint32_t>(
            kSettingCatalogFormatterRegistryPhysical -
            kMainRtosRuntimeToPhysical) +
        kSettingCatalogFormatterRegistryIndex[setting] *
            kSettingCatalogFormatterRegistryRecordSize;
    if (!validate_words(runtime,
                        kSettingCatalogFormatterRegistryExpected[setting],
                        4)) {
      return reject("direct setting formatter registry changed");
    }
  }
  if (!validate_words(kSettingCatalogApertureVariantsRuntime,
                      kSettingCatalogApertureVariantsExpected, 6) ||
      !validate_words(kSettingCatalogShutterVariantsRuntime,
                      kSettingCatalogShutterVariantsExpected, 6)) {
    return reject("direct setting formatter variants changed");
  }

  auto validate_formatter_leaf =
      [&](const SettingCatalogFormatterBank &formatter) -> bool {
    if (formatter.count == 0 ||
        formatter.count > kDevMemSettingCatalogMaxValues) {
      return false;
    }
    const size_t bytes = static_cast<size_t>(formatter.count) *
                         kSettingCatalogFormatterRecordSize;
    if (!catalog_runtime_range_valid(formatter.records_runtime,
                                     kSettingCatalogFormatterRuntimeBegin,
                                     kSettingCatalogFormatterRuntimeEnd,
                                     bytes)) {
      return false;
    }
    uint8_t records_first[kDevMemSettingCatalogMaxValues *
                          kSettingCatalogFormatterRecordSize] = {};
    uint8_t records_second[kDevMemSettingCatalogMaxValues *
                           kSettingCatalogFormatterRecordSize] = {};
    const uint64_t physical =
        kMainRtosRuntimeToPhysical + formatter.records_runtime;
    const uint8_t *records_source = at_region(
        setting_catalog_formatter_pages_, physical,
        kSettingCatalogFormatterPhysical);
    read_volatile_bytes(records_source, records_first, bytes);
    read_volatile_bytes(records_source, records_second, bytes);
    if (!bytes_equal(records_first, records_second, bytes) ||
        fnv1a_bytes(records_first, bytes) != formatter.records_fnv1a) {
      return false;
    }

    int32_t raws[kDevMemSettingCatalogMaxValues] = {};
    uint8_t label_lengths[kDevMemSettingCatalogMaxValues] = {};
    char labels[kDevMemSettingCatalogMaxValues][kSettingCatalogV19LabelMax] =
        {};
    uint32_t strings_hash = UINT32_C(2166136261);
    for (uint16_t row = 0; row < formatter.count; ++row) {
      const uint8_t *record =
          records_first + row * kSettingCatalogFormatterRecordSize;
      if (formatter.setting != 2 && load_le16(record + 2) != 0) return false;
      const int32_t raw =
          formatter.setting == 2
              ? static_cast<int32_t>(load_le32(record))
              : static_cast<int32_t>(
                    static_cast<int16_t>(load_le16(record)));
      if (raw < -32768 || raw > 32767) return false;
      for (uint16_t prior = 0; prior < row; ++prior) {
        if (raws[prior] == raw) return false;
      }
      raws[row] = raw;

      const uint32_t label_runtime = load_le32(record + 4);
      if (label_runtime < kSettingCatalogLabelPointerBegin ||
          label_runtime > kSettingCatalogLabelPointerEnd ||
          !catalog_runtime_range_valid(
              label_runtime, kSettingCatalogFormatterRuntimeBegin,
              kSettingCatalogFormatterRuntimeEnd,
              kSettingCatalogV19LabelMax + 1u)) {
        return false;
      }
      uint8_t label_first[kSettingCatalogV19LabelMax + 1u] = {};
      uint8_t label_second[kSettingCatalogV19LabelMax + 1u] = {};
      const uint64_t label_physical =
          kMainRtosRuntimeToPhysical + label_runtime;
      const uint8_t *label_source = at_region(
          setting_catalog_formatter_pages_, label_physical,
          kSettingCatalogFormatterPhysical);
      read_volatile_bytes(label_source, label_first, sizeof(label_first));
      read_volatile_bytes(label_source, label_second, sizeof(label_second));
      if (!bytes_equal(label_first, label_second, sizeof(label_first))) {
        return false;
      }
      uint8_t length = 0;
      while (length < kSettingCatalogV19LabelMax &&
             label_first[length] != 0) {
        if (label_first[length] < 0x21 || label_first[length] > 0x7e) {
          return false;
        }
        labels[row][length] = static_cast<char>(label_first[length]);
        ++length;
      }
      if (length == 0 || label_first[length] != 0) return false;
      label_lengths[row] = length;
      for (uint16_t prior = 0; prior < row; ++prior) {
        if (label_lengths[prior] != length) continue;
        bool same = true;
        for (uint8_t byte = 0; byte < length; ++byte) {
          if (labels[prior][byte] != labels[row][byte]) same = false;
        }
        if (same) return false;
      }
      strings_hash =
          fnv1a_bytes(label_first, static_cast<size_t>(length) + 1u,
                      strings_hash);
    }
    return strings_hash == formatter.strings_fnv1a;
  };

  for (const SettingCatalogFormatterBank &formatter :
       kSettingCatalogFormatterBanks) {
    if (!validate_formatter_leaf(formatter)) {
      return reject("direct setting formatter certificate changed");
    }
  }

  uint16_t result_counts[kDevMemSettingCatalogCount] = {};
  for (unsigned setting = 0; setting < kDevMemSettingCatalogCount;
       ++setting) {
    const uint16_t bank_selector =
        setting == 2 ? iso_step
                     : (setting == 0 && source_ids[setting] == 1445
                            ? UINT16_C(128)
                            : exposure_step);
    const uint16_t formatter_selector = setting == 2 ? 0 : exposure_step;
    const SettingCatalogBank *bank = find_setting_catalog_bank(
        static_cast<uint8_t>(setting), source_ids[setting], bank_selector);
    const SettingCatalogFormatterBank *formatter =
        find_setting_catalog_formatter(static_cast<uint8_t>(setting),
                                       formatter_selector);
    if (bank == nullptr || formatter == nullptr ||
        bank->byte_length == 0 || (bank->byte_length & 1u) != 0 ||
        bank->byte_length / 2u > kDevMemSettingCatalogMaxValues ||
        formatter->count == 0 ||
        formatter->count > kDevMemSettingCatalogMaxValues) {
      return reject("unsupported direct setting catalog bank");
    }

    const bool special = bank->source == 1445;
    const uint32_t descriptor_begin = special ? 0x6061b000 : 0x60616000;
    const uint32_t descriptor_end = special ? 0x6061c000 : 0x60617000;
    const uint32_t values_begin = special ? 0x60614000 : 0x60611000;
    const uint32_t values_end = special ? 0x60615000 : 0x60613000;
    if (!catalog_runtime_range_valid(bank->descriptor_runtime,
                                     descriptor_begin, descriptor_end,
                                     kSettingCatalogDescriptorSize) ||
        !catalog_runtime_range_valid(bank->values_runtime, values_begin,
                                     values_end, bank->byte_length)) {
      return reject("direct setting catalog bank escaped its region");
    }

    const uint64_t descriptor_physical =
        kMainRtosRuntimeToPhysical + bank->descriptor_runtime;
    const void *descriptor_mapping =
        special ? setting_catalog_special_descriptor_page_
                : setting_catalog_common_descriptors_page_;
    const uint64_t descriptor_region =
        special ? kSettingCatalogSpecialDescriptorPhysical
                : kSettingCatalogCommonDescriptorsPhysical;
    uint8_t descriptor_first[kSettingCatalogDescriptorSize] = {};
    uint8_t descriptor_second[kSettingCatalogDescriptorSize] = {};
    const uint8_t *descriptor_source = at_region(
        descriptor_mapping, descriptor_physical, descriptor_region);
    read_volatile_bytes(descriptor_source, descriptor_first,
                        sizeof(descriptor_first));
    read_volatile_bytes(descriptor_source, descriptor_second,
                        sizeof(descriptor_second));
    if (!bytes_equal(descriptor_first, descriptor_second,
                     sizeof(descriptor_first)) ||
        load_le16(descriptor_first) != bank->source ||
        load_le16(descriptor_first + 2) != bank->selector ||
        load_le32(descriptor_first + 4) != bank->values_runtime ||
        load_le32(descriptor_first + 8) != 0 ||
        load_le16(descriptor_first + 12) != bank->byte_length ||
        load_le16(descriptor_first + 14) != sizeof(int16_t) ||
        load_le32(descriptor_first + 16) != bank->descriptor_flags) {
      return reject("direct setting catalog descriptor changed");
    }

    uint8_t values_first[kDevMemSettingCatalogMaxValues * sizeof(int16_t)] =
        {};
    uint8_t values_second[kDevMemSettingCatalogMaxValues * sizeof(int16_t)] =
        {};
    const uint64_t values_physical =
        kMainRtosRuntimeToPhysical + bank->values_runtime;
    const void *values_mapping = special
                                     ? setting_catalog_special_values_page_
                                     : setting_catalog_common_values_pages_;
    const uint64_t values_region =
        special ? kSettingCatalogSpecialValuesPhysical
                : kSettingCatalogCommonValuesPhysical;
    const uint8_t *values_source =
        at_region(values_mapping, values_physical, values_region);
    read_volatile_bytes(values_source, values_first, bank->byte_length);
    read_volatile_bytes(values_source, values_second, bank->byte_length);
    if (!bytes_equal(values_first, values_second, bank->byte_length) ||
        fnv1a_bytes(values_first, bank->byte_length) != bank->values_fnv1a) {
      return reject("direct setting catalog values changed");
    }
    const uint16_t native_count = bank->byte_length / sizeof(int16_t);
    for (uint16_t index = 0; index < native_count; ++index) {
      const int16_t value = static_cast<int16_t>(
          load_le16(values_first + index * sizeof(int16_t)));
      for (uint16_t prior = 0; prior < index; ++prior) {
        if (static_cast<int16_t>(load_le16(
                values_first + prior * sizeof(int16_t))) == value) {
          return reject("direct setting catalog contains duplicate raw");
        }
      }
    }

    const size_t formatter_bytes =
        static_cast<size_t>(formatter->count) *
        kSettingCatalogFormatterRecordSize;
    if (!catalog_runtime_range_valid(formatter->records_runtime,
                                     kSettingCatalogFormatterRuntimeBegin,
                                     kSettingCatalogFormatterRuntimeEnd,
                                     formatter_bytes)) {
      return reject("direct setting formatter escaped its region");
    }
    uint8_t formatter_first[kDevMemSettingCatalogMaxValues *
                            kSettingCatalogFormatterRecordSize] = {};
    uint8_t formatter_second[kDevMemSettingCatalogMaxValues *
                             kSettingCatalogFormatterRecordSize] = {};
    const uint64_t formatter_physical =
        kMainRtosRuntimeToPhysical + formatter->records_runtime;
    const uint8_t *formatter_source = at_region(
        setting_catalog_formatter_pages_, formatter_physical,
        kSettingCatalogFormatterPhysical);
    read_volatile_bytes(formatter_source, formatter_first, formatter_bytes);
    read_volatile_bytes(formatter_source, formatter_second, formatter_bytes);
    if (!bytes_equal(formatter_first, formatter_second, formatter_bytes) ||
        fnv1a_bytes(formatter_first, formatter_bytes) !=
            formatter->records_fnv1a) {
      return reject("direct setting formatter records changed");
    }

    uint8_t formatter_label_lengths[kDevMemSettingCatalogMaxValues] = {};
    char formatter_labels[kDevMemSettingCatalogMaxValues]
                         [kDevMemSettingLabelMax] = {};
    int32_t formatter_raw[kDevMemSettingCatalogMaxValues] = {};
    uint32_t strings_hash = UINT32_C(2166136261);
    for (uint16_t row = 0; row < formatter->count; ++row) {
      const uint8_t *record =
          formatter_first + row * kSettingCatalogFormatterRecordSize;
      const uint32_t raw_word = load_le32(record);
      if (setting != 2 && load_le16(record + 2) != 0) {
        return reject("direct setting formatter raw is noncanonical");
      }
      const int32_t raw =
          setting == 2
              ? static_cast<int32_t>(raw_word)
              : static_cast<int32_t>(
                    static_cast<int16_t>(load_le16(record)));
      if (raw < -32768 || raw > 32767) {
        return reject("direct setting formatter raw is out of range");
      }
      for (uint16_t prior = 0; prior < row; ++prior) {
        if (formatter_raw[prior] == raw) {
          return reject("direct setting formatter contains duplicate raw");
        }
      }
      formatter_raw[row] = raw;

      const uint32_t label_runtime = load_le32(record + 4);
      if (label_runtime < kSettingCatalogLabelPointerBegin ||
          label_runtime > kSettingCatalogLabelPointerEnd ||
          !catalog_runtime_range_valid(
              label_runtime, kSettingCatalogFormatterRuntimeBegin,
              kSettingCatalogFormatterRuntimeEnd,
              kSettingCatalogV19LabelMax + 1u)) {
        return reject("direct setting label pointer is invalid");
      }
      uint8_t label_first[kSettingCatalogV19LabelMax + 1u] = {};
      uint8_t label_second[kSettingCatalogV19LabelMax + 1u] = {};
      const uint64_t label_physical =
          kMainRtosRuntimeToPhysical + label_runtime;
      const uint8_t *label_source = at_region(
          setting_catalog_formatter_pages_, label_physical,
          kSettingCatalogFormatterPhysical);
      read_volatile_bytes(label_source, label_first, sizeof(label_first));
      read_volatile_bytes(label_source, label_second, sizeof(label_second));
      if (!bytes_equal(label_first, label_second, sizeof(label_first))) {
        return reject("direct setting label changed while reading");
      }
      uint8_t label_length = 0;
      while (label_length < kSettingCatalogV19LabelMax &&
             label_first[label_length] != 0) {
        if (label_first[label_length] < 0x21 ||
            label_first[label_length] > 0x7e) {
          return reject("direct setting label is not visible ASCII");
        }
        ++label_length;
      }
      if (label_length == 0 || label_first[label_length] != 0) {
        return reject("direct setting label length is invalid");
      }
      formatter_label_lengths[row] = label_length;
      for (uint8_t byte = 0; byte < label_length; ++byte) {
        formatter_labels[row][byte] =
            static_cast<char>(label_first[byte]);
      }
      for (uint16_t prior = 0; prior < row; ++prior) {
        if (formatter_label_lengths[prior] != label_length) continue;
        bool same = true;
        for (uint8_t byte = 0; byte < label_length; ++byte) {
          if (formatter_labels[prior][byte] !=
              formatter_labels[row][byte]) {
            same = false;
          }
        }
        if (same) {
          return reject("direct setting formatter contains duplicate label");
        }
      }
      strings_hash =
          fnv1a_bytes(label_first, static_cast<size_t>(label_length) + 1u,
                      strings_hash);
    }
    if (strings_hash != formatter->strings_fnv1a) {
      return reject("direct setting formatter strings changed");
    }

    for (uint16_t index = 0; index < native_count; ++index) {
      const int16_t value = static_cast<int16_t>(
          load_le16(values_first + index * sizeof(int16_t)));
      int match = -1;
      for (uint16_t row = 0; row < formatter->count; ++row) {
        if (formatter_raw[row] != static_cast<int32_t>(value)) continue;
        if (match >= 0) {
          return reject("direct setting label mapping is ambiguous");
        }
        match = static_cast<int>(row);
      }
      if (match < 0) {
        return reject("direct setting value has no camera label");
      }
      entries[setting][index].value = value;
      entries[setting][index].label_length =
          formatter_label_lengths[match];
      for (uint8_t byte = 0; byte < formatter_label_lengths[match]; ++byte) {
        entries[setting][index].label[byte] =
            formatter_labels[match][byte];
      }
    }
    result_counts[setting] = native_count;
  }

  for (unsigned setting = 0; setting < kDevMemSettingCatalogCount;
       ++setting) {
    counts[setting] = result_counts[setting];
  }
  return true;
}

bool DevMemState::certify_direct_exposure_static(Error *error) const {
  if (direct_exposure_static_certified_) return true;
  if (!setting_catalog_tables_available() ||
      setting_catalog_dispatch_pages_ == nullptr ||
      setting_catalog_postfilter_page_ == nullptr ||
      setting_catalog_aperture_legality_page_ == nullptr ||
      setting_catalog_shutter_legality_pages_ == nullptr ||
      setting_catalog_iso_legality_pages_ == nullptr ||
      setting_catalog_iso_registry_page_ == nullptr ||
      setting_catalog_provider_descriptors_pages_ == nullptr ||
      setting_catalog_provider_buckets_pages_ == nullptr) {
    return fail(error, "direct exposure catalog is unavailable");
  }

  if (!direct_fingerprint_matches(
          setting_catalog_dispatch_pages_,
          kDirectSettingDispatchSemanticPhysical,
          kDirectSettingDispatchPhysical,
          kDirectSettingDispatchSemanticLength,
          kDirectSettingDispatchSemanticFnv1a) ||
      !direct_fingerprint_matches(
          setting_catalog_postfilter_page_,
          kDirectPostfilterSemanticPhysical, kDirectPostfilterPhysical,
          kDirectPostfilterSemanticLength,
          kDirectPostfilterSemanticFnv1a) ||
      !direct_fingerprint_matches(
          setting_catalog_aperture_legality_page_,
          kDirectApertureLegalitySemanticPhysical,
          kDirectApertureLegalityPhysical,
          kDirectApertureLegalitySemanticLength,
          kDirectApertureLegalitySemanticFnv1a) ||
      !direct_fingerprint_matches(
          setting_catalog_shutter_legality_pages_,
          kDirectShutterLegalitySemanticPhysical,
          kDirectShutterLegalityPhysical,
          kDirectShutterLegalitySemanticLength,
          kDirectShutterLegalitySemanticFnv1a) ||
      !direct_fingerprint_matches(
          setting_catalog_iso_registry_page_,
          kDirectIsoRegistrySemanticPhysical, kDirectIsoRegistryPhysical,
          kDirectIsoRegistrySemanticLength,
          kDirectIsoRegistrySemanticFnv1a)) {
    return fail(error, "direct exposure firmware certificate changed");
  }
  for (const DirectLegalityFingerprint &fingerprint :
       kDirectIsoLegalityFingerprints) {
    if (!direct_fingerprint_matches(
            setting_catalog_iso_legality_pages_, fingerprint.physical,
            kDirectIsoLegalityPhysical, fingerprint.length,
            fingerprint.fnv1a)) {
      return fail(error, "direct ISO legality certificate changed");
    }
  }

  const uint8_t *primary_map = at_region(
      setting_catalog_provider_descriptors_pages_,
      kDirectProviderDescriptorsPhysical,
      kDirectProviderDescriptorsPhysical);
  const uint8_t *secondary_map = at_region(
      setting_catalog_provider_descriptors_pages_,
      kDirectProviderMapSecondaryPhysical,
      kDirectProviderDescriptorsPhysical);
  const size_t primary_bytes =
      kDirectProviderMapPrimaryCount * sizeof(uint32_t);
  const size_t secondary_bytes =
      kDirectProviderMapSecondaryCount * sizeof(uint32_t);
  const uint32_t primary_first = fnv1a_volatile(primary_map, primary_bytes);
  const uint32_t primary_second = fnv1a_volatile(primary_map, primary_bytes);
  const uint32_t secondary_first =
      fnv1a_volatile(secondary_map, secondary_bytes);
  const uint32_t secondary_second =
      fnv1a_volatile(secondary_map, secondary_bytes);
  if (primary_first != kDirectProviderMapPrimaryFnv1a ||
      primary_second != kDirectProviderMapPrimaryFnv1a ||
      secondary_first != kDirectProviderMapSecondaryFnv1a ||
      secondary_second != kDirectProviderMapSecondaryFnv1a) {
    return fail(error, "direct exposure public map changed");
  }

  // The public maps and generic-provider buckets are initialized once for
  // this firmware session.  Certify every record used by the hot plan twice,
  // then retain only the compact address/width plan encoded above.
  for (const DirectProviderSpec &spec : kDirectProviderSpecs) {
    if (spec.selector >= spec.element_count) {
      return fail(error, "direct exposure provider selector changed");
    }
    if (spec.public_id >= 0) {
      const uint64_t descriptor_physical =
          direct_public_descriptor_physical(spec);
      if (descriptor_physical == 0) {
        return fail(error, "direct exposure descriptor escaped its map");
      }
      uint8_t first[4] = {};
      uint8_t second[4] = {};
      const uint8_t *source = at_region(
          setting_catalog_provider_descriptors_pages_, descriptor_physical,
          kDirectProviderDescriptorsPhysical);
      read_volatile_bytes(source, first, sizeof(first));
      read_volatile_bytes(source, second, sizeof(second));
      if (!bytes_equal(first, second, sizeof(first)) ||
          load_le16(first) != spec.encoded ||
          load_le16(first + 2) != spec.selector) {
        return fail(error, "direct exposure descriptor changed");
      }
    }

    const uint64_t bucket_physical =
        kDirectProviderPrimaryBucketsPhysical +
        static_cast<uint64_t>(spec.encoded >> 4) * 8u;
    uint8_t first[8] = {};
    uint8_t second[8] = {};
    const uint8_t *source = at_region(
        setting_catalog_provider_buckets_pages_, bucket_physical,
        kDirectProviderBucketsPhysical);
    read_volatile_bytes(source, first, sizeof(first));
    read_volatile_bytes(source, second, sizeof(second));
    if (!bytes_equal(first, second, sizeof(first)) ||
        load_le16(first) != spec.encoded ||
        load_le16(first + 2) != spec.element_count ||
        load_le32(first + 4) != spec.expected_provider_base) {
      return fail(error, "direct exposure provider bucket changed");
    }
  }

  direct_exposure_static_certified_ = true;
  return true;
}

bool DevMemState::read_direct_exposure_dynamic(
    direct_catalog::Snapshot *snapshot, Error *error, bool *transient,
    bool strict) const {
  clear_error(error);
  if (transient != nullptr) *transient = false;
  if (snapshot == nullptr) {
    return fail(error, "missing direct exposure snapshot destination");
  }
  if (!opened() || setting_provider_pointer_pages_ == nullptr ||
      setting_catalog_provider_descriptors_pages_ == nullptr ||
      setting_catalog_provider_buckets_pages_ == nullptr ||
      setting_catalog_provider_value_pages_[0] == nullptr ||
      setting_catalog_provider_value_pages_[1] == nullptr ||
      setting_catalog_provider_value_pages_[2] == nullptr ||
      setting_catalog_helper_state_page_ == nullptr ||
      setting_catalog_helper_mode_page_ == nullptr ||
      lens_head_pages_[0] == nullptr || lens_head_pages_[1] == nullptr ||
      lens_control_page_ == nullptr || lens_commit_page_ == nullptr ||
      lens_slots_page_ == nullptr || lens_connection_page_ == nullptr) {
    return fail(error, "direct exposure providers are unavailable");
  }

  struct ApertureContext {
    uint8_t lens_id[6];
    uint8_t selector;
    uint8_t zoom_index;
    uint8_t row_count;
    uint16_t focal_mm;
    int16_t minimum;
    int16_t maximum;
  };
  struct DynamicPass {
    int32_t value[kDirectProviderCount];
    uint32_t cache[kDirectProviderCount];
    uint8_t ram8d0;
    uint8_t ramde48;
    uint8_t helper_index;
    uint8_t helper_mode;
    int16_t shutter_ceiling;
    int16_t shutter_floor;
    ApertureContext aperture;
  };
  DynamicPass first = {};
  DynamicPass second = {};

  auto aperture_context_equal = [](const ApertureContext &left,
                                   const ApertureContext &right) -> bool {
    for (unsigned byte = 0; byte < sizeof(left.lens_id); ++byte) {
      if (left.lens_id[byte] != right.lens_id[byte]) return false;
    }
    return left.selector == right.selector &&
           left.zoom_index == right.zoom_index &&
           left.row_count == right.row_count &&
           left.focal_mm == right.focal_mm &&
           left.minimum == right.minimum && left.maximum == right.maximum;
  };

  auto read_aperture_context = [&](ApertureContext *context) -> bool {
    if (context == nullptr) return false;
    *context = {};
    uint8_t bytes[6] = {};
    read_volatile_bytes_unfenced(
        at_region(lens_control_page_, kDirectLensBasePointerPhysical,
                  kDirectLensControlPagePhysical),
        bytes, sizeof(uint32_t));
    if (load_le32(bytes) != kDirectLensRuntime[0]) return false;

    read_volatile_bytes_unfenced(
        at_region(lens_commit_page_, kDirectLensSelectorPhysical,
                  kDirectLensCommitPagePhysical),
        bytes, sizeof(uint8_t));
    context->selector = bytes[0];
    if (context->selector > 1u) return false;
    read_volatile_bytes_unfenced(
        at_region(lens_slots_page_, kDirectLensSlot0Physical,
                  kDirectLensSlotsPagePhysical),
        bytes, sizeof(uint32_t));
    if (load_le32(bytes) != kDirectLensRuntime[context->selector])
      return false;
    read_volatile_bytes_unfenced(
        at_region(lens_slots_page_, kDirectLensSlot1Physical,
                  kDirectLensSlotsPagePhysical),
        bytes, sizeof(uint32_t));
    if (load_le32(bytes) != kDirectLensSlot1Expected) return false;
    read_volatile_bytes_unfenced(
        at_region(lens_connection_page_, kDirectLensConnection0Physical,
                  kDirectLensConnectionPagePhysical),
        bytes, sizeof(uint8_t));
    if (bytes[0] != 1u) return false;
    read_volatile_bytes_unfenced(
        at_region(lens_connection_page_, kDirectLensConnection1Physical,
                  kDirectLensConnectionPagePhysical),
        bytes, sizeof(uint8_t));
    if (bytes[0] != 0u) return false;

    const uint64_t base = kDirectLensPhysical[context->selector];
    const uint64_t region = kDirectLensHeadPagePhysical[context->selector];
    read_volatile_bytes_unfenced(
        at_region(lens_head_pages_[context->selector],
                  base + kDirectLensIdOffset, region),
        context->lens_id, sizeof(context->lens_id));
    read_volatile_bytes_unfenced(
        at_region(lens_head_pages_[context->selector],
                  base + kDirectLensZoomOffset, region),
        bytes, sizeof(uint8_t));
    context->zoom_index = bytes[0];
    read_volatile_bytes_unfenced(
        at_region(lens_head_pages_[context->selector],
                  base + kDirectLensRowCountOffset, region),
        bytes, sizeof(uint8_t));
    context->row_count = bytes[0];
    if (context->row_count == 0u || context->row_count > 32u ||
        context->zoom_index >= context->row_count) {
      return false;
    }
    const uint64_t row =
        base + kDirectLensZoomRowsOffset +
        static_cast<uint64_t>(context->zoom_index) *
            kDirectLensZoomRowStride;
    read_volatile_bytes_unfenced(
        at_region(lens_head_pages_[context->selector],
                  row + kDirectLensZoomRowFocalOffset, region),
        bytes, sizeof(uint16_t));
    context->focal_mm = load_le16(bytes);
    read_volatile_bytes_unfenced(
        at_region(lens_head_pages_[context->selector],
                  row + kDirectLensZoomRowApertureMinOffset, region),
        bytes, sizeof(uint16_t));
    context->minimum = static_cast<int16_t>(load_le16(bytes));
    read_volatile_bytes_unfenced(
        at_region(lens_head_pages_[context->selector],
                  row + kDirectLensZoomRowApertureMaxOffset, region),
        bytes, sizeof(uint16_t));
    context->maximum = static_cast<int16_t>(load_le16(bytes));
    bool id_present = false;
    for (uint8_t byte : context->lens_id) id_present |= byte != 0u;
    return id_present && context->focal_mm != 0u && context->minimum >= 0 &&
           context->minimum <= context->maximum;
  };

  auto read_pass = [&](DynamicPass *pass, bool reverse,
                       bool verify_tail) -> bool {
    if (pass == nullptr) return false;
    if (!read_aperture_context(&pass->aperture)) return false;
    uint8_t flag_bytes[2] = {};
    read_volatile_bytes_unfenced(
        at_region(setting_catalog_provider_buckets_pages_,
                  kDirectProviderSecondaryFlagPhysical,
                  kDirectProviderBucketsPhysical),
        flag_bytes, sizeof(flag_bytes));
    if (load_le16(flag_bytes) != 0) return false;

    for (unsigned ordinal = 0; ordinal < kDirectProviderCount; ++ordinal) {
      const unsigned index =
          reverse ? kDirectProviderCount - 1u - ordinal : ordinal;
      const DirectProviderSpec &spec = kDirectProviderSpecs[index];
      if (spec.selector >= spec.element_count) return false;

      const void *cache_mapping = spec.public_id >= 0
                                      ? setting_provider_pointer_pages_
                                      : setting_catalog_helper_state_page_;
      const uint64_t cache_region = spec.public_id >= 0
                                        ? kSettingProviderPointerTablePhysical
                                        : kDirectHelperStatePhysical;
      uint8_t cache_bytes[4] = {};
      read_volatile_bytes_unfenced(
          at_region(cache_mapping, spec.cache_slot_physical, cache_region),
          cache_bytes, sizeof(cache_bytes));
      pass->cache[index] = load_le32(cache_bytes);
      if (spec.public_id >= 0) {
        if (pass->cache[index] != 0 &&
            pass->cache[index] != spec.expected_provider_base) {
          return false;
        }
      } else if (pass->cache[index] != spec.expected_provider_base) {
        return false;
      }

      const uint8_t width = direct_provider_width(spec.encoded);
      const uint32_t selected_runtime =
          spec.expected_provider_base +
          static_cast<uint32_t>(spec.selector) * width;
      const void *value_mapping = nullptr;
      uint64_t value_physical = 0;
      for (unsigned page = 0; page < 3; ++page) {
        if (selected_runtime < kDirectProviderValueRuntime[page] ||
            static_cast<uint64_t>(selected_runtime) + width >
                static_cast<uint64_t>(kDirectProviderValueRuntime[page]) +
                    kPageSize) {
          continue;
        }
        value_mapping = setting_catalog_provider_value_pages_[page];
        value_physical =
            kDirectProviderValuePhysical[page] +
            static_cast<uint64_t>(selected_runtime -
                                  kDirectProviderValueRuntime[page]);
        break;
      }
      if (value_mapping == nullptr) return false;
      uint8_t value_bytes[4] = {};
      read_volatile_bytes_unfenced(
          at_region(value_mapping, value_physical,
                    value_physical &
                        ~(static_cast<uint64_t>(kPageSize) - 1u)),
          value_bytes, width);
      if (!decode_direct_provider_value(spec.encoded, value_bytes,
                                        &pass->value[index])) {
        return false;
      }
    }

    for (const DirectOwnerPointer &owner : kDirectOwnerPointers) {
      uint8_t pointer_bytes[4] = {};
      read_volatile_bytes_unfenced(
          at_region(setting_catalog_helper_state_page_, owner.physical,
                    kDirectHelperStatePhysical),
          pointer_bytes, sizeof(pointer_bytes));
      if (load_le32(pointer_bytes) != owner.expected_provider_base)
        return false;
    }

    uint8_t byte = 0;
    read_volatile_bytes_unfenced(at(setting_catalog_helper_state_page_,
                                    kDirectRam8d0Physical),
                                 &byte, 1);
    pass->ram8d0 = byte;
    read_volatile_bytes_unfenced(at(setting_catalog_helper_state_page_,
                                    kDirectRamde48Physical),
                                 &byte, 1);
    pass->ramde48 = byte;
    read_volatile_bytes_unfenced(at(setting_catalog_helper_mode_page_,
                                    kDirectHelperIndexPhysical),
                                 &byte, 1);
    pass->helper_index = byte;
    if (pass->helper_index >= kDirectHelperRecordCount) return false;
    const uint64_t helper_mode_physical =
        kDirectHelperRecordsPhysical +
        static_cast<uint64_t>(pass->helper_index) *
            kDirectHelperRecordStride +
        kDirectHelperModeOffset;
    read_volatile_bytes_unfenced(at(setting_catalog_helper_mode_page_,
                                    helper_mode_physical),
                                 &byte, 1);
    pass->helper_mode = byte;

    uint8_t bound[2] = {};
    read_volatile_bytes_unfenced(at(setting_catalog_helper_state_page_,
                                    kDirectShutterCeilingPhysical),
                                 bound, sizeof(bound));
    pass->shutter_ceiling =
        static_cast<int16_t>(load_le16(bound));
    read_volatile_bytes_unfenced(at(setting_catalog_helper_state_page_,
                                    kDirectShutterFloorPhysical),
                                 bound, sizeof(bound));
    pass->shutter_floor = static_cast<int16_t>(load_le16(bound));

    if (!verify_tail) return true;
    ApertureContext aperture_tail = {};
    if (!read_aperture_context(&aperture_tail) ||
        !aperture_context_equal(pass->aperture, aperture_tail)) {
      return false;
    }
    read_volatile_bytes_unfenced(
        at_region(setting_catalog_provider_buckets_pages_,
                  kDirectProviderSecondaryFlagPhysical,
                  kDirectProviderBucketsPhysical),
        flag_bytes, sizeof(flag_bytes));
    return load_le16(flag_bytes) == 0;
  };

  bool first_ok = false;
  bool second_ok = true;
  if (strict) {
    memory_barrier();
    first_ok = read_pass(&first, false, true);
    memory_barrier();
    second_ok = read_pass(&second, true, true);
    memory_barrier();
  } else {
    // WATCH is deliberately optimistic: exactly one volatile read of every
    // dynamic field, no A/B equality and no DMB.  A mixed but constructible
    // sample is publishable and self-corrects on the next 100-ms tick.
    first_ok = read_pass(&first, false, false);
  }
  if (!first_ok || !second_ok) {
    if (!strict && transient != nullptr) *transient = true;
    return fail(error, "direct exposure provider layout changed");
  }
  if (strict) {
    for (unsigned index = 0; index < kDirectProviderCount; ++index) {
      if (first.value[index] != second.value[index] ||
          first.cache[index] != second.cache[index]) {
        if (transient != nullptr) *transient = true;
        return fail(error, "direct exposure providers changed while reading");
      }
    }
    if (first.ram8d0 != second.ram8d0 ||
        first.ramde48 != second.ramde48 ||
        first.helper_index != second.helper_index ||
        first.helper_mode != second.helper_mode ||
        first.shutter_ceiling != second.shutter_ceiling ||
        first.shutter_floor != second.shutter_floor ||
        !aperture_context_equal(first.aperture, second.aperture)) {
      if (transient != nullptr) *transient = true;
      return fail(error, "direct exposure helper state changed while reading");
    }
  }

  for (unsigned index = 0; index < direct_catalog::kContextCount; ++index)
    snapshot->context_values[index] = first.value[index];
  snapshot->mode_helper = direct_mode_helper(snapshot->context_values);
  direct_sources(snapshot->context_values, snapshot->mode_helper,
                 snapshot->source_id);

  snapshot->active[direct_catalog::kShutter] =
      first.value[kProviderActive4];
  snapshot->active[direct_catalog::kAperture] =
      first.value[kProviderActive3];
  snapshot->active[direct_catalog::kIso] = first.value[kProviderActive6];
  auto source_value = [&](uint16_t source, int32_t *value) -> bool {
    unsigned index = kDirectProviderCount;
    switch (source) {
      case 1445: index = kProviderSource1445; break;
      case 1667: index = kProviderSource1667; break;
      case 1668: index = kProviderSource1668; break;
      case 1669: index = kProviderSource1669; break;
      case 1670: index = kProviderSource1670; break;
      case 1671: index = kProviderSource1671; break;
      case 1672: index = kProviderSource1672; break;
      default: return false;
    }
    int32_t selected = first.value[index];
    if (source == 1445) {
      selected = static_cast<int16_t>(static_cast<uint16_t>(selected));
    }
    *value = selected;
    return true;
  };
  for (unsigned setting = 0; setting < direct_catalog::kSettingCount;
       ++setting) {
    if (!source_value(snapshot->source_id[setting],
                      &snapshot->selected[setting])) {
      return fail(error, "direct exposure source is unsupported");
    }
  }

  snapshot->current1669 = first.value[kProviderSource1669];
  snapshot->iso598 = first.value[kProviderIso598];
  snapshot->iso434 = first.value[kProviderIso434];
  snapshot->iso435 = first.value[kProviderIso435];
  snapshot->iso239 = first.value[kProviderIso239];
  snapshot->iso754 = first.value[kProviderIso754];
  snapshot->iso1712 = first.value[kProviderIso1712];
  snapshot->iso1678 = first.value[kProviderIso1678];
  snapshot->iso1325 = first.value[kProviderIso1325];
  snapshot->iso1606 = first.value[kProviderIso1606];
  snapshot->iso1673 = first.value[kProviderIso1673];

  auto byte_value = [&](unsigned index, uint8_t *value) -> bool {
    if (first.value[index] < 0 || first.value[index] > UINT8_MAX)
      return false;
    *value = static_cast<uint8_t>(first.value[index]);
    return true;
  };
  snapshot->ram8d0 = first.ram8d0;
  snapshot->ramde48 = first.ramde48;
  snapshot->helper_index = first.helper_index;
  snapshot->helper_mode = first.helper_mode;
  if (!byte_value(kProviderContext24, &snapshot->p49169) ||
      !byte_value(kProviderHigh17665, &snapshot->p17665) ||
      !byte_value(kProviderHigh37297Selector1,
                  &snapshot->p37297_selector1) ||
      !byte_value(kProviderContext1444, &snapshot->p26481) ||
      !byte_value(kProviderIso1712, &snapshot->p34225) ||
      !byte_value(kProviderIso598, &snapshot->p59665) ||
      !byte_value(kProviderHigh61697, &snapshot->p61697) ||
      !byte_value(kProviderIso754, &snapshot->p61633) ||
      !byte_value(kProviderHigh33697, &snapshot->p33697) ||
      !byte_value(kProviderHigh21489, &snapshot->p21489) ||
      !byte_value(kProviderContext1679, &snapshot->p7745) ||
      first.value[kProviderContext64] < INT16_MIN ||
      first.value[kProviderContext64] > INT16_MAX) {
    return fail(error, "direct exposure dependency is out of range");
  }
  snapshot->p35818 =
      static_cast<int16_t>(first.value[kProviderContext64]);
  snapshot->shutter_ceiling = first.shutter_ceiling;
  snapshot->shutter_floor = first.shutter_floor;
  for (unsigned byte = 0; byte < sizeof(snapshot->aperture_lens_id); ++byte)
    snapshot->aperture_lens_id[byte] = first.aperture.lens_id[byte];
  snapshot->aperture_lens_selector = first.aperture.selector;
  snapshot->aperture_zoom_index = first.aperture.zoom_index;
  snapshot->aperture_row_count = first.aperture.row_count;
  snapshot->aperture_focal_mm = first.aperture.focal_mm;
  snapshot->aperture_min = first.aperture.minimum;
  snapshot->aperture_max = first.aperture.maximum;
  return true;
}

bool DevMemState::read_direct_exposure_snapshot(
    direct_catalog::Snapshot *snapshot, Error *error,
    bool *transient) const {
  return read_cold_direct_exposure_snapshot(snapshot, error, transient,
                                             false);
}

bool DevMemState::read_cold_direct_exposure_snapshot(
    direct_catalog::Snapshot *snapshot, Error *error,
    bool *transient, bool strict) const {
  clear_error(error);
  if (transient != nullptr) *transient = false;
  if (snapshot == nullptr) {
    return fail(error, "missing direct exposure snapshot destination");
  }
  if (!certify_direct_exposure_static(error)) return false;

  for (unsigned setting = 0; setting < direct_catalog::kSettingCount;
       ++setting) {
    snapshot->native_count[setting] = 0;
    for (unsigned index = 0; index < direct_catalog::kMaxValues; ++index) {
      snapshot->native[setting][index].value = 0;
      snapshot->native[setting][index].label_length = 0;
      for (unsigned byte = 0; byte < direct_catalog::kLabelMax; ++byte)
        snapshot->native[setting][index].label[byte] = '\0';
    }
  }
  if (!read_direct_exposure_dynamic(snapshot, error, transient, strict)) {
    return false;
  }
  const int32_t exposure_step = snapshot->context_values[9];
  const int32_t iso_step = snapshot->context_values[10];
  if ((exposure_step != 85 && exposure_step != 128 &&
       exposure_step != 256) ||
      (iso_step != 85 && iso_step != 256)) {
    return fail(error, "unsupported direct setting catalog step");
  }

  direct_exposure_native_cache_valid_ = false;
  uint16_t counts[kDevMemSettingCatalogCount] = {};
  if (!read_setting_catalog_tables(
          snapshot->source_id,
          static_cast<uint16_t>(exposure_step),
          static_cast<uint16_t>(iso_step),
          setting_catalog_snapshot_entries_, counts, error)) {
    return false;
  }
  for (unsigned setting = 0; setting < direct_catalog::kSettingCount;
       ++setting) {
    snapshot->native_count[setting] = counts[setting];
    for (uint16_t index = 0; index < counts[setting]; ++index) {
      snapshot->native[setting][index].value =
          setting_catalog_snapshot_entries_[setting][index].value;
      snapshot->native[setting][index].label_length =
          setting_catalog_snapshot_entries_[setting][index].label_length;
      for (unsigned byte = 0; byte < direct_catalog::kLabelMax; ++byte) {
        snapshot->native[setting][index].label[byte] =
            setting_catalog_snapshot_entries_[setting][index].label[byte];
      }
    }
    direct_exposure_native_counts_[setting] = counts[setting];
    direct_exposure_native_sources_[setting] = snapshot->source_id[setting];
  }
  direct_exposure_native_exposure_step_ = exposure_step;
  direct_exposure_native_iso_step_ = iso_step;
  direct_exposure_native_cache_valid_ = true;
  return true;
}

bool DevMemState::read_cached_direct_exposure_snapshot(
    direct_catalog::Snapshot *snapshot, Error *error,
    bool *transient) const {
  clear_error(error);
  if (transient != nullptr) *transient = false;
  if (snapshot == nullptr) {
    return fail(error, "missing direct exposure snapshot destination");
  }
  if (!direct_exposure_static_certified_ ||
      !direct_exposure_native_cache_valid_) {
    return read_cold_direct_exposure_snapshot(snapshot, error, transient,
                                               true);
  }

  for (unsigned setting = 0; setting < direct_catalog::kSettingCount;
       ++setting) {
    snapshot->native_count[setting] =
        direct_exposure_native_counts_[setting];
    for (unsigned index = 0; index < direct_catalog::kMaxValues; ++index) {
      snapshot->native[setting][index].value = 0;
      snapshot->native[setting][index].label_length = 0;
      for (unsigned byte = 0; byte < direct_catalog::kLabelMax; ++byte) {
        snapshot->native[setting][index].label[byte] = '\0';
      }
    }
    for (uint16_t index = 0;
         index < direct_exposure_native_counts_[setting]; ++index) {
      snapshot->native[setting][index].value =
          setting_catalog_snapshot_entries_[setting][index].value;
      snapshot->native[setting][index].label_length =
          setting_catalog_snapshot_entries_[setting][index].label_length;
      for (unsigned byte = 0; byte < direct_catalog::kLabelMax; ++byte) {
        snapshot->native[setting][index].label[byte] =
            setting_catalog_snapshot_entries_[setting][index].label[byte];
      }
    }
  }
  if (!read_direct_exposure_dynamic(snapshot, error, transient, true)) {
    return false;
  }
  bool key_matches =
      snapshot->context_values[9] == direct_exposure_native_exposure_step_ &&
      snapshot->context_values[10] == direct_exposure_native_iso_step_;
  for (unsigned setting = 0; setting < direct_catalog::kSettingCount;
       ++setting) {
    if (snapshot->source_id[setting] !=
        direct_exposure_native_sources_[setting]) {
      key_matches = false;
    }
  }
  if (!key_matches) {
    return read_cold_direct_exposure_snapshot(snapshot, error, transient,
                                               true);
  }
  return true;
}

bool DevMemState::refresh_direct_exposure_snapshot(
    direct_catalog::Snapshot *snapshot, Error *error,
    bool *transient) const {
  clear_error(error);
  if (transient != nullptr) *transient = false;
  if (snapshot == nullptr) {
    return fail(error, "missing direct exposure snapshot destination");
  }
  if (!direct_exposure_static_certified_ ||
      !direct_exposure_native_cache_valid_) {
    return read_cold_direct_exposure_snapshot(snapshot, error, transient,
                                               false);
  }

  for (unsigned setting = 0; setting < direct_catalog::kSettingCount;
       ++setting) {
    snapshot->native_count[setting] =
        direct_exposure_native_counts_[setting];
    for (unsigned index = 0; index < direct_catalog::kMaxValues; ++index) {
      snapshot->native[setting][index].value = 0;
      snapshot->native[setting][index].label_length = 0;
      for (unsigned byte = 0; byte < direct_catalog::kLabelMax; ++byte) {
        snapshot->native[setting][index].label[byte] = '\0';
      }
    }
    for (uint16_t index = 0;
         index < direct_exposure_native_counts_[setting]; ++index) {
      snapshot->native[setting][index].value =
          setting_catalog_snapshot_entries_[setting][index].value;
      snapshot->native[setting][index].label_length =
          setting_catalog_snapshot_entries_[setting][index].label_length;
      for (unsigned byte = 0; byte < direct_catalog::kLabelMax; ++byte) {
        snapshot->native[setting][index].label[byte] =
            setting_catalog_snapshot_entries_[setting][index].label[byte];
      }
    }
  }
  if (!read_direct_exposure_dynamic(snapshot, error, transient, false)) {
    return false;
  }
  bool key_matches =
      snapshot->context_values[9] == direct_exposure_native_exposure_step_ &&
      snapshot->context_values[10] == direct_exposure_native_iso_step_;
  for (unsigned setting = 0; setting < direct_catalog::kSettingCount;
       ++setting) {
    if (snapshot->source_id[setting] !=
        direct_exposure_native_sources_[setting]) {
      key_matches = false;
    }
  }
  if (!key_matches) {
    return read_cold_direct_exposure_snapshot(snapshot, error, transient,
                                               false);
  }
  return true;
}

bool DevMemState::refresh_direct_exposure_state(
    direct_catalog::Snapshot *snapshot, Error *error,
    bool *transient) const {
  clear_error(error);
  if (transient != nullptr) *transient = false;
  if (snapshot == nullptr) {
    return fail(error, "missing direct exposure state destination");
  }
  if (!direct_exposure_static_certified_ ||
      !direct_exposure_native_cache_valid_) {
    return read_cold_direct_exposure_snapshot(snapshot, error, transient,
                                               false);
  }
  for (unsigned setting = 0; setting < direct_catalog::kSettingCount;
       ++setting) {
    if (snapshot->native_count[setting] !=
        direct_exposure_native_counts_[setting]) {
      return read_cold_direct_exposure_snapshot(snapshot, error, transient,
                                                 false);
    }
  }
  if (!read_direct_exposure_dynamic(snapshot, error, transient, false)) {
    return false;
  }
  bool key_matches =
      snapshot->context_values[9] == direct_exposure_native_exposure_step_ &&
      snapshot->context_values[10] == direct_exposure_native_iso_step_;
  for (unsigned setting = 0; setting < direct_catalog::kSettingCount;
       ++setting) {
    if (snapshot->source_id[setting] !=
        direct_exposure_native_sources_[setting]) {
      key_matches = false;
    }
  }
  if (!key_matches) {
    return read_cold_direct_exposure_snapshot(snapshot, error, transient,
                                               false);
  }
  return true;
}

bool DevMemState::read_direct_lens_snapshot(DevMemLensSnapshot *snapshot,
                                            Error *error,
                                            bool *transient) const {
  clear_error(error);
  if (transient != nullptr) *transient = false;
  if (snapshot == nullptr) {
    return fail(error, "missing direct lens snapshot destination");
  }
  *snapshot = {};
  if (!opened() || lens_head_pages_[0] == nullptr ||
      lens_head_pages_[1] == nullptr ||
      lens_calibration_pages_[0] == nullptr ||
      lens_calibration_pages_[1] == nullptr ||
      lens_feature_pages_[0] == nullptr ||
      lens_feature_pages_[1] == nullptr || lens_control_page_ == nullptr ||
      lens_focus_page_ == nullptr || lens_commit_page_ == nullptr ||
      lens_slots_page_ == nullptr || lens_connection_page_ == nullptr) {
    return fail(error, "direct lens state is unavailable");
  }

  auto read_u8 = [&](const void *mapping, uint64_t physical,
                     uint64_t region) -> uint8_t {
    uint8_t bytes[1] = {};
    read_volatile_bytes_unfenced(at_region(mapping, physical, region), bytes,
                                 sizeof(bytes));
    return bytes[0];
  };
  auto read_u16 = [&](const void *mapping, uint64_t physical,
                      uint64_t region) -> uint16_t {
    uint8_t bytes[2] = {};
    read_volatile_bytes_unfenced(at_region(mapping, physical, region), bytes,
                                 sizeof(bytes));
    return load_le16(bytes);
  };
  auto read_u32 = [&](const void *mapping, uint64_t physical,
                      uint64_t region) -> uint32_t {
    uint8_t bytes[4] = {};
    read_volatile_bytes_unfenced(at_region(mapping, physical, region), bytes,
                                 sizeof(bytes));
    return load_le32(bytes);
  };

  // WATCH intentionally reads each live guard exactly once.  These checks
  // prove that every subsequent address stays inside one of the two fixed
  // mapped objects; they are not a coherence bracket.
  if (read_u32(lens_control_page_, kDirectLensBasePointerPhysical,
               kDirectLensControlPagePhysical) != kDirectLensRuntime[0]) {
    return fail(error, "direct lens base pointer changed");
  }
  const uint8_t selector =
      read_u8(lens_commit_page_, kDirectLensSelectorPhysical,
              kDirectLensCommitPagePhysical);
  if (selector > 1u) return fail(error, "direct lens selector is invalid");
  if (read_u32(lens_slots_page_, kDirectLensSlot0Physical,
               kDirectLensSlotsPagePhysical) !=
          kDirectLensRuntime[selector] ||
      read_u32(lens_slots_page_, kDirectLensSlot1Physical,
               kDirectLensSlotsPagePhysical) != kDirectLensSlot1Expected ||
      read_u8(lens_connection_page_, kDirectLensConnection0Physical,
              kDirectLensConnectionPagePhysical) != 1u ||
      read_u8(lens_connection_page_, kDirectLensConnection1Physical,
              kDirectLensConnectionPagePhysical) != 0u ||
      read_u8(lens_focus_page_, kDirectLensFocusModePhysical,
              kDirectLensFocusPagePhysical) != 0u) {
    return fail(error, "direct lens control guards are invalid");
  }

  const uint64_t base = kDirectLensPhysical[selector];
  const uint64_t head_region = kDirectLensHeadPagePhysical[selector];
  snapshot->zoom_index =
      read_u8(lens_head_pages_[selector], base + kDirectLensZoomOffset,
              head_region);
  uint8_t autofocus[kDirectLensAutofocusCount * sizeof(uint16_t)] = {};
  read_volatile_bytes_unfenced(
      at_region(lens_head_pages_[selector],
                base + kDirectLensAutofocusOffset, head_region),
      autofocus, sizeof(autofocus));
  snapshot->autofocus_reference =
      load_le16(autofocus + sizeof(uint16_t));
  const uint8_t valid_pulse_count = read_u8(
      lens_head_pages_[selector], base + kDirectLensValidPulseCountOffset,
      head_region);
  if (valid_pulse_count > 32u) {
    return fail(error, "direct lens pulse count is invalid");
  }
  snapshot->focus_pulse = read_u16(
      lens_head_pages_[selector], base + kDirectLensFocusPulseOffset,
      head_region);
  snapshot->private_status = read_u32(
      lens_head_pages_[selector], base + kDirectLensPrivateStatusOffset,
      head_region);

  read_volatile_bytes_unfenced(
      at_region(lens_head_pages_[selector], base + kDirectLensIdOffset,
                head_region),
      snapshot->id, sizeof(snapshot->id));
  uint8_t name[kDirectLensNameBytes] = {};
  read_volatile_bytes_unfenced(
      at_region(lens_head_pages_[selector], base + kDirectLensNameOffset,
                head_region),
      name, sizeof(name));
  uint8_t name_length = 0;
  while (name_length < kDirectLensNameBytes && name[name_length] != 0u) {
    if (name[name_length] < 0x20u || name[name_length] > 0x7eu) {
      return fail(error, "direct lens name is not printable ASCII");
    }
    snapshot->name[name_length] = static_cast<char>(name[name_length]);
    ++name_length;
  }
  if (name_length == 0) return fail(error, "direct lens name is empty");
  snapshot->name_length = name_length;

  const uint8_t row_count =
      read_u8(lens_head_pages_[selector],
              base + kDirectLensRowCountOffset, head_region);
  if (row_count == 0 || row_count > 32u ||
      snapshot->zoom_index >= row_count) {
    return fail(error, "direct lens calibration row is invalid");
  }
  snapshot->wide_mm =
      read_u16(lens_head_pages_[selector], base + kDirectLensWideOffset,
               head_region);
  snapshot->tele_mm =
      read_u16(lens_head_pages_[selector], base + kDirectLensTeleOffset,
               head_region);
  if (snapshot->wide_mm == 0 || snapshot->tele_mm < snapshot->wide_mm) {
    return fail(error, "direct lens focal bounds are invalid");
  }

  const uint64_t calibration_base = base + kDirectLensCalibrationOffset;
  const uint64_t calibration_region =
      kDirectLensCalibrationPagePhysical[selector];
  uint8_t calibration[kDirectLensCalibrationStride] = {};
  read_volatile_bytes_unfenced(
      at_region(lens_calibration_pages_[selector],
                calibration_base +
                    static_cast<uint64_t>(snapshot->zoom_index) *
                        kDirectLensCalibrationStride,
                calibration_region),
      calibration, sizeof(calibration));
  for (unsigned coefficient = 0;
       coefficient < kDirectLensCalibrationFloatCount; ++coefficient) {
    const uint32_t bits =
        load_le32(calibration + coefficient * sizeof(uint32_t));
    if ((bits & UINT32_C(0x7f800000)) == UINT32_C(0x7f800000)) {
      return fail(error, "direct lens calibration is not finite");
    }
    snapshot->calibration_f32_bits[coefficient] = bits;
  }
  snapshot->infinity_reference =
      load_le16(calibration + kDirectLensCalibrationInfinityOffset);

  const uint64_t feature_region = kDirectLensFeaturePagePhysical[selector];
  const uint8_t normal_is =
      read_u8(lens_feature_pages_[selector],
              base + kDirectLensNormalIsOffset, feature_region);
  snapshot->macro_is =
      read_u8(lens_feature_pages_[selector],
              base + kDirectLensMacroIsOffset, feature_region);
  if (normal_is != 1u) {
    return fail(error, "direct lens normal calibration is unavailable");
  }
  return true;
}

bool DevMemState::shell_capture_state_available() const {
  return opened() && shell_capture_state_page_ != nullptr;
}

bool DevMemState::read_shell_capture_state(DevMemShellCaptureState *state,
                                           Error *error) const {
  clear_error(error);
  if (!shell_capture_state_available()) {
    return fail(error, "direct shell capture state is unavailable");
  }
  if (state == nullptr) {
    return fail(error, "missing direct shell capture state destination");
  }

  const auto *pointer = reinterpret_cast<volatile const uint32_t *>(
      at(shell_capture_state_page_, kShellCaptureStatePhysical));
  const auto *read = reinterpret_cast<volatile const uint16_t *>(
      at(shell_capture_state_page_,
         kShellCaptureStatePhysical + kShellCaptureReadOffset));
  const auto *write = reinterpret_cast<volatile const uint16_t *>(
      at(shell_capture_state_page_,
         kShellCaptureStatePhysical + kShellCaptureWriteOffset));
  for (unsigned attempt = 0; attempt < 4; ++attempt) {
    memory_barrier();
    const DevMemShellCaptureState first = {*pointer, *read, *write};
    memory_barrier();
    const DevMemShellCaptureState second = {*pointer, *read, *write};
    memory_barrier();
    if (first.pointer != second.pointer || first.read != second.read ||
        first.write != second.write) {
      continue;
    }
    if (second.read >= kDevMemShellCaptureCapacity ||
        second.write >= kDevMemShellCaptureCapacity) {
      return fail(error, "direct shell capture index is out of range");
    }
    *state = second;
    return true;
  }
  return fail(error, "direct shell capture state remained unstable");
}

bool DevMemState::unmap_shell_capture(Error *error) {
  clear_error(error);
  if (shell_capture_ring_page_ == nullptr) {
    shell_capture_ring_mapping_length_ = 0;
    shell_capture_pointer_ = 0;
    return true;
  }
  const long result = os::munmap(shell_capture_ring_page_,
                                shell_capture_ring_mapping_length_);
  shell_capture_ring_page_ = nullptr;
  shell_capture_ring_mapping_length_ = 0;
  shell_capture_pointer_ = 0;
  if (os::failed(result)) {
    return fail_errno(error, "unmapping shell capture errno ", result);
  }
  return true;
}

bool DevMemState::prepare_shell_capture(DevMemShellCaptureState *state,
                                        Error *error) {
  clear_error(error);
  if (state == nullptr) {
    return fail(error, "missing direct shell capture state destination");
  }
  if (shell_capture_ring_page_ != nullptr) {
    if (!unmap_shell_capture(error)) return false;
  }

  DevMemShellCaptureState before = {};
  if (!read_shell_capture_state(&before, error)) return false;
  const uint64_t end = static_cast<uint64_t>(before.pointer) +
                       kDevMemShellCaptureCapacity;
  if (before.pointer < kShellCapturePoolBegin ||
      end > kShellCapturePoolEnd ||
      (before.pointer - kShellCapturePoolBegin) %
              kShellCaptureBlockStride !=
          0) {
    return fail(error, "stock shell capture pointer is outside its pool");
  }

  const uint64_t physical =
      kMainRtosPhysicalPrefix + static_cast<uint64_t>(before.pointer);
  const uint64_t page = physical / kPageSize;
  if (page > UINT32_MAX) {
    return fail(error, "shell capture physical page is out of mmap2 range");
  }
  const size_t offset = static_cast<size_t>(physical & (kPageSize - 1u));
  const size_t required = offset + kDevMemShellCaptureCapacity;
  const size_t length =
      (required + kPageSize - 1u) & ~(static_cast<size_t>(kPageSize) - 1u);
  const long address =
      os::mmap2(nullptr, length, os::kProtectionRead, os::kMapShared,
                memory_fd_, static_cast<uint32_t>(page));
  if (os::failed(address)) {
    return fail_errno(error, "mapping shell capture read-only errno ",
                      address);
  }
  shell_capture_ring_page_ =
      reinterpret_cast<void *>(static_cast<uintptr_t>(address));
  shell_capture_ring_mapping_length_ = length;
  shell_capture_pointer_ = before.pointer;

  DevMemShellCaptureState after = {};
  if (!read_shell_capture_state(&after, error) ||
      after.pointer != before.pointer) {
    const Error primary = error == nullptr ? Error{} : *error;
    Error ignored = {};
    (void)unmap_shell_capture(&ignored);
    if (error != nullptr) {
      if (primary.text[0] != '\0') {
        *error = primary;
      } else {
        (void)fail(error, "shell capture pointer changed while mapping");
      }
    }
    return false;
  }
  *state = after;
  return true;
}

DevMemShellCaptureReadStatus DevMemState::read_shell_capture(
    uint16_t cursor, uint16_t firmware_read_checkpoint,
    uint8_t *destination, size_t capacity, size_t *size, uint16_t *next,
    Error *error) const {
  clear_error(error);
  if (size != nullptr) *size = 0;
  if (next != nullptr) *next = cursor;
  if (!shell_capture_state_available() ||
      shell_capture_ring_page_ == nullptr || shell_capture_pointer_ == 0) {
    (void)fail(error, "direct shell capture ring is unavailable");
    return DevMemShellCaptureReadStatus::kError;
  }
  if (destination == nullptr || size == nullptr || next == nullptr ||
      cursor >= kDevMemShellCaptureCapacity ||
      firmware_read_checkpoint >= kDevMemShellCaptureCapacity) {
    (void)fail(error, "invalid direct shell capture destination or cursor");
    return DevMemShellCaptureReadStatus::kError;
  }

  const auto *pointer = reinterpret_cast<volatile const uint32_t *>(
      at(shell_capture_state_page_, kShellCaptureStatePhysical));
  const auto *read = reinterpret_cast<volatile const uint16_t *>(
      at(shell_capture_state_page_,
         kShellCaptureStatePhysical + kShellCaptureReadOffset));
  const auto *write = reinterpret_cast<volatile const uint16_t *>(
      at(shell_capture_state_page_,
         kShellCaptureStatePhysical + kShellCaptureWriteOffset));

  memory_barrier();
  const uint32_t pointer_before = *pointer;
  const uint16_t read_before = *read;
  const uint16_t write_before = *write;
  memory_barrier();
  if (pointer_before != shell_capture_pointer_ ||
      read_before != firmware_read_checkpoint ||
      read_before >= kDevMemShellCaptureCapacity ||
      write_before >= kDevMemShellCaptureCapacity) {
    (void)fail(error, "direct shell capture state changed structurally");
    return DevMemShellCaptureReadStatus::kError;
  }

  const size_t available = write_before >= cursor
                               ? static_cast<size_t>(write_before - cursor)
                               : static_cast<size_t>(
                                     kDevMemShellCaptureCapacity - cursor) +
                                     write_before;
  if (available > capacity) {
    (void)fail(error, "direct shell capture destination is full");
    return DevMemShellCaptureReadStatus::kError;
  }
  const uint64_t physical =
      kMainRtosPhysicalPrefix + static_cast<uint64_t>(shell_capture_pointer_);
  const size_t mapping_offset =
      static_cast<size_t>(physical & (kPageSize - 1u));
  const auto *ring = reinterpret_cast<volatile const uint8_t *>(
      static_cast<const uint8_t *>(shell_capture_ring_page_) + mapping_offset);
  for (size_t index = 0; index < available; ++index) {
    size_t ring_index = static_cast<size_t>(cursor) + index;
    if (ring_index >= kDevMemShellCaptureCapacity) {
      ring_index -= kDevMemShellCaptureCapacity;
    }
    destination[index] = ring[ring_index];
  }
  memory_barrier();
  const uint32_t pointer_after = *pointer;
  const uint16_t read_after = *read;
  const uint16_t write_after = *write;
  memory_barrier();
  if (pointer_after != pointer_before ||
      read_after != firmware_read_checkpoint ||
      read_after >= kDevMemShellCaptureCapacity ||
      write_after >= kDevMemShellCaptureCapacity) {
    (void)fail(error, "direct shell capture changed structurally while read");
    return DevMemShellCaptureReadStatus::kError;
  }
  if (read_after != read_before || write_after != write_before) {
    return DevMemShellCaptureReadStatus::kChangedDuringRead;
  }

  *size = available;
  *next = write_before;
  return DevMemShellCaptureReadStatus::kStable;
}

bool DevMemState::transfer_records_available() const {
  return opened() && transfer_descriptor_page_ != nullptr &&
         transfer_record_pages_[0] != nullptr &&
         transfer_record_pages_[1] != nullptr;
}

bool DevMemState::read_transfer_counts(DevMemTransferCounts *counts,
                                       Error *error) const {
  clear_error(error);
  if (!transfer_records_available()) {
    return fail(error, "direct transfer-record tables are unavailable");
  }
  if (counts == nullptr) {
    return fail(error, "missing direct transfer-count destination");
  }

  // 0x518a47ac writes a complete record, then increments descriptor+16 while
  // holding the same DCF semaphore used by 94BB/94DD.  0x518a461e clears the
  // record's size before decrementing +16.  Both use the cached 0x62ce alias,
  // so Linux may observe this value late or not at all.  It is only a cheap
  // early-wake hint; CameraController periodically scans the uncached record
  // arrays regardless, and never accepts this as list or metadata authority.
  memory_barrier();
  for (unsigned slot = 0; slot < 2; ++slot) {
    const uint64_t descriptor =
        kTransferDescriptorPhysical + slot * kTransferDescriptorStride;
    const uint32_t key = read_scalar<uint32_t>(
        transfer_descriptor_page_, descriptor);
    const uint32_t pointer = read_scalar<uint32_t>(
        transfer_descriptor_page_,
        descriptor + kTransferDescriptorPointerOffset);
    const uint32_t capacity = read_scalar<uint32_t>(
        transfer_descriptor_page_,
        descriptor + kTransferDescriptorCountOffset);
    const uint32_t occupied = read_scalar<uint32_t>(
        transfer_descriptor_page_,
        descriptor + kTransferDescriptorOccupiedOffset);
    if (key != slot || pointer != kTransferRecordPointers[slot] ||
        capacity != kDevMemTransferRecordCapacity || occupied > capacity) {
      return fail(error, "v1.9 direct transfer descriptor changed");
    }
    counts->occupied[slot] = occupied;
  }
  memory_barrier();
  return true;
}

DevMemTransferListStatus DevMemState::read_stable_transfer_lists(
    DevMemTransferIdList lists[2], DevMemTransferCounts *counts,
    Error *error) {
  clear_error(error);
  if (!transfer_records_available()) {
    (void)fail(error, "direct transfer-record tables are unavailable");
    return DevMemTransferListStatus::kError;
  }
  if (lists == nullptr || counts == nullptr) {
    (void)fail(error, "missing direct transfer-list destination");
    return DevMemTransferListStatus::kError;
  }

  DevMemTransferCounts hint_before = {};
  if (!read_transfer_counts(&hint_before, error)) {
    return DevMemTransferListStatus::kError;
  }

  // First scan: retain the exact stock-94BB size fields for every record.
  // Empty records need no metadata read because stock ignores bytes 0..11
  // until size becomes nonzero.  For every occupied record, also retain all
  // exact 94DD-relevant metadata bytes 0..11.
  memory_barrier();
  for (unsigned slot = 0; slot < 2; ++slot) {
    lists[slot].count = 0;
    for (uint32_t ordinal = 0; ordinal < kDevMemTransferRecordCapacity;
         ++ordinal) {
      const uint64_t physical =
          kTransferRecordPhysical[slot] + ordinal * kTransferRecordStride;
      const auto *words = reinterpret_cast<volatile const uint32_t *>(
          at_region(transfer_record_pages_[slot], physical,
                    kTransferRecordPhysical[slot]));
      const uint32_t size_low = words[3];
      const uint32_t size_high = words[4];
      transfer_snapshot_words_[slot][ordinal][3] = size_low;
      transfer_snapshot_words_[slot][ordinal][4] = size_high;
      if ((size_low | size_high) == 0) continue;
      for (unsigned word = 0; word < kTransferRecordMetadataWords; ++word) {
        transfer_snapshot_words_[slot][ordinal][word] = words[word];
      }
      const uint32_t transfer_id =
          (static_cast<uint32_t>(slot + 1u) << 28) | (ordinal + 1u);
      lists[slot].ids[lists[slot].count++] = transfer_id;
    }
  }
  memory_barrier();

  // Second scan: compare both size words for every record, independently of
  // the cacheable descriptor hint.  This catches insertion, removal, and an
  // equal-count remove/add ABA.  Re-read bytes 0..11 exactly for every record
  // that was occupied in the first pass, so published metadata cannot change
  // while being selected.  Metadata writes to a still-empty record are
  // intentionally irrelevant until a later size transition.
  for (unsigned slot = 0; slot < 2; ++slot) {
    uint32_t occupied_index = 0;
    for (uint32_t ordinal = 0; ordinal < kDevMemTransferRecordCapacity;
         ++ordinal) {
      const uint64_t physical =
          kTransferRecordPhysical[slot] + ordinal * kTransferRecordStride;
      const auto *words = reinterpret_cast<volatile const uint32_t *>(
          at_region(transfer_record_pages_[slot], physical,
                    kTransferRecordPhysical[slot]));
      const uint32_t size_low = words[3];
      const uint32_t size_high = words[4];
      if (size_low != transfer_snapshot_words_[slot][ordinal][3] ||
          size_high != transfer_snapshot_words_[slot][ordinal][4]) {
        (void)fail(error, "direct transfer records changed during read");
        return DevMemTransferListStatus::kChangedDuringRead;
      }
      if ((size_low | size_high) == 0) continue;
      for (unsigned word = 0; word < kTransferRecordMetadataWords; ++word) {
        if (words[word] != transfer_snapshot_words_[slot][ordinal][word]) {
          (void)fail(error, "direct transfer records changed during read");
          return DevMemTransferListStatus::kChangedDuringRead;
        }
      }
      if (occupied_index >= lists[slot].count ||
          lists[slot].ids[occupied_index] !=
              ((static_cast<uint32_t>(slot + 1u) << 28) |
               (ordinal + 1u))) {
        (void)fail(error, "direct transfer list changed during read");
        return DevMemTransferListStatus::kChangedDuringRead;
      }
      ++occupied_index;
    }
    if (occupied_index != lists[slot].count) {
      (void)fail(error, "direct transfer list changed during read");
      return DevMemTransferListStatus::kChangedDuringRead;
    }
  }
  memory_barrier();
  if (!read_transfer_counts(counts, error)) {
    return DevMemTransferListStatus::kError;
  }
  return DevMemTransferListStatus::kStable;
}

bool DevMemState::read_transfer_record(uint32_t slot, uint32_t transfer_id,
                                       DevMemTransferRecord *record,
                                       Error *error) const {
  clear_error(error);
  if (!transfer_records_available()) {
    return fail(error, "direct transfer-record tables are unavailable");
  }
  const uint32_t ordinal = transfer_id & UINT32_C(0x000fffff);
  if (record == nullptr || (slot != 1 && slot != 2) ||
      (transfer_id >> 28) != slot ||
      (transfer_id & UINT32_C(0x0ff00000)) != 0 || ordinal == 0 ||
      ordinal > kDevMemTransferRecordCapacity) {
    return fail(error, "invalid direct transfer-record request");
  }

  const unsigned slot_index = slot - 1u;
  const uint64_t physical = kTransferRecordPhysical[slot_index] +
                            (ordinal - 1u) * kTransferRecordStride;
  const auto *words = reinterpret_cast<volatile const uint32_t *>(
      at_region(transfer_record_pages_[slot_index], physical,
                kTransferRecordPhysical[slot_index]));
  uint32_t values[5] = {};
  memory_barrier();
  for (unsigned word = 0; word < 5; ++word) values[word] = words[word];
  memory_barrier();

  for (unsigned byte = 0; byte < 8; ++byte) {
    record->leaf[byte] = static_cast<char>(
        (values[byte / 4u] >> ((byte % 4u) * 8u)) & 0xffu);
  }
  record->leaf[8] = '.';
  for (unsigned byte = 0; byte < 3; ++byte) {
    record->leaf[9u + byte] =
        static_cast<char>((values[2] >> (byte * 8u)) & 0xffu);
  }
  // Stock 94DD memcpy4s record+8 to output+9, then explicitly overwrites the
  // fourth copied byte with NUL.  Keep the full source word in stability
  // snapshots, but mirror the authoritative output semantics here.
  record->leaf[12] = '\0';
  record->file_size = static_cast<uint64_t>(values[3]) |
                      (static_cast<uint64_t>(values[4]) << 32);
  return true;
}

bool devmem_station_equal(const DevMemStationState &left,
                          const DevMemStationState &right) {
  return left.station == right.station &&
         left.registration == right.registration &&
         left.priority == right.priority &&
         left.connection == right.connection &&
         left.picture_transfer == right.picture_transfer;
}

bool devmem_transfer_record_equal(const DevMemTransferRecord &left,
                                  const DevMemTransferRecord &right) {
  if (left.file_size != right.file_size) return false;
  for (unsigned byte = 0; byte < 13; ++byte) {
    if (left.leaf[byte] != right.leaf[byte]) return false;
  }
  return true;
}

}  // namespace om1_stars
