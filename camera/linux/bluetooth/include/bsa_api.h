#ifndef OLYMPUS_BSA_API_H
#define OLYMPUS_BSA_API_H

/*
 * Umbrella include for the reconstructed Olympus libbsa.so client ABI.
 *
 * bsa_classic_api.h contains management, device-management, discovery,
 * security, test-mode, Object Push, and Data Gateway declarations.
 * bsa_ble_api.h contains the BLE/GATT declarations.  bsa_uipc_api.h provides
 * the public data plane needed by Data Gateway.  Other GKI, logging, and
 * implementation-object exports stay in bsa_lowlevel_abi.h.
 */
#include "bsa_classic_api.h"
#include "bsa_ble_api.h"
#include "bsa_uipc_api.h"

#endif
