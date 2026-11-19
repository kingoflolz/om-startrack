#ifndef OLYMPUS_BSA_CLASSIC_API_H
#define OLYMPUS_BSA_CLASSIC_API_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#ifndef BSA_ABI_BASE_TYPES_DEFINED
#define BSA_ABI_BASE_TYPES_DEFINED 1
typedef uint8_t UINT8;
typedef int8_t INT8;
typedef uint16_t UINT16;
typedef uint32_t UINT32;
typedef uint8_t BOOLEAN;
typedef UINT8 BD_ADDR[6];
typedef UINT8 DEV_CLASS[3];
typedef UINT8 BD_NAME[249];
typedef UINT8 LINK_KEY[16];
typedef UINT8 PIN_CODE[16];
typedef UINT8 BD_FEATURES[8];
typedef UINT8 BT_OCTET8[8];
typedef UINT8 BT_OCTET16[16];
typedef UINT8 tBT_TRANSPORT;
typedef UINT8 tBT_DEVICE_TYPE;
typedef UINT8 tUIPC_CH_ID;
typedef UINT16 tBSA_STATUS;
typedef UINT32 tBSA_SERVICE_MASK;
typedef UINT8 tBSA_SERVICE_ID;
typedef struct {
    UINT16 len;
    union {
        UINT16 uuid16;
        UINT32 uuid32;
        UINT8 uuid128[16];
    } uu;
} tBT_UUID;
#endif

#ifndef FALSE
#define FALSE 0u
#endif
#ifndef TRUE
#define TRUE 1u
#endif
#ifndef LEN_UUID_16
#define LEN_UUID_16 2u
#define LEN_UUID_32 4u
#define LEN_UUID_128 16u
#endif
#ifndef BT_DEVICE_TYPE_BREDR
#define BSA_LINK_KEY_TYPE_COMBINATION 0x00u
#define BSA_LINK_KEY_TYPE_LOCAL_UNIT 0x01u
#define BSA_LINK_KEY_TYPE_REMOTE_UNIT 0x02u
#define BSA_LINK_KEY_TYPE_DEBUG_COMB 0x03u
#define BSA_LINK_KEY_TYPE_UNAUTH_COMB 0x04u
#define BSA_LINK_KEY_TYPE_AUTH_COMB 0x05u
#define BSA_LINK_KEY_TYPE_CHANGED_COMB 0x06u
#define BSA_LINK_KEY_TYPE_UNAUTH_COMB_P_256 0x07u
#define BSA_LINK_KEY_TYPE_AUTH_COMB_P_256 0x08u
#define BSA_LINK_KEY_TYPE_UNKNOWN 0xffu
#endif
#ifndef BLE_ADDR_PUBLIC
#define BLE_ADDR_PUBLIC 0x00u
#define BLE_ADDR_RANDOM 0x01u
#define BLE_ADDR_PUBLIC_ID 0x02u
#define BLE_ADDR_RANDOM_ID 0x03u
#define BLE_ADDR_TYPE_MASK 0x01u
#endif

#define BSA_SUCCESS 0u

#define BSA_ERROR_CLI_BAD_RSP_SIZE 100u
#define BSA_ERROR_CLI_NYI 101u
#define BSA_ERROR_CLI_DISC_BUSY 102u
#define BSA_ERROR_CLI_RES_ERROR 103u
#define BSA_ERROR_CLI_BAD_PARAM 104u
#define BSA_ERROR_CLI_NOT_CONNECTED 105u
#define BSA_ERROR_CLI_BUSY 106u
#define BSA_ERROR_CLI_UIPC_OPEN 107u
#define BSA_ERROR_CLI_UIPC_SEND 108u
#define BSA_ERROR_CLI_BAD_MSG 109u
#define BSA_ERROR_CLI_BAD_PING_RSP 110u
#define BSA_ERROR_CLI_NAME_TOO_LONG 111u
#define BSA_ERROR_CLI_ALREADY_WAITING 112u
#define BSA_ERROR_CLI_TASK_ERROR 113u
#define BSA_ERROR_CLI_INTERNAL 114u
#define BSA_ERROR_CLI_ALREADY_CONNECTED 115u
#define BSA_ERROR_CLI_MEM_FULL 116u
#define BSA_ERROR_CLI_UNEXPECTED_EVENT 117u
#define BSA_ERROR_CLI_GKI_MBOX_ERROR 118u
#define BSA_ERROR_CLI_TIMEOUT 119u
#define BSA_ERROR_CLI_BAD_EVENT 120u

#define BSA_ERROR_SRV_BAD_PARAM 200u
#define BSA_ERROR_SRV_BAD_REQ_SIZE 201u
#define BSA_ERROR_SRV_BAD_MSG_ID 202u
#define BSA_ERROR_SRV_ALREADY_ACTIVE 203u
#define BSA_ERROR_SRV_NYI 204u
#define BSA_ERROR_SRV_BAD_CLIENT 205u
#define BSA_ERROR_SRV_MEM_FULL 206u
#define BSA_ERROR_SRV_HW_ERROR 207u
#define BSA_ERROR_SRV_BLUETOOTH_DISABLE 208u
#define BSA_ERROR_SRV_INTERNAL 209u
#define BSA_ERROR_SRV_NOT_COMPILED 210u
#define BSA_ERROR_SRV_WLAN_RESET 211u
#define BSA_ERROR_SRV_BUSY 212u

#define BSA_ERROR_SRV_SEC_BOND_ACTIVE 300u
#define BSA_ERROR_SRV_SEC_RM_DEV 301u
#define BSA_ERROR_SRV_SEC_BOND_CANCEL_ERROR 302u
#define BSA_ERROR_SRV_SEC_NOT_ALLOWED 303u
#define BSA_ERROR_SRV_OPS_NO_ACCESS_PENDING 500u
#define BSA_ERROR_OPC_FAIL 520u
#define BSA_ERROR_OPC_NOT_FOUND 521u
#define BSA_ERROR_OPC_NO_PERMISSION 522u
#define BSA_ERROR_OPC_SRV_UNAVAIL 523u
#define BSA_ERROR_SRV_OPC_ALREADY_ACTIVE 524u
#define BSA_ERROR_SRV_DEV_DISC_BUSY 900u
#define BSA_ERROR_SRV_DEV_INFO_BUSY 901u
#define BSA_ERROR_SRV_DISC_ABORT_ERROR 902u
#define BSA_ERROR_SRV_TM_VSC_BUSY 1100u
#define BSA_ERROR_SRV_TM_HCI_ERROR 1101u
#define BSA_ERROR_SRV_TM_HCI_CMD_ERROR 1102u
#define BSA_ERROR_SRV_DG_OPEN_FAIL 1500u
#define BSA_ERROR_SRV_DG_CONNECTED 1501u
#define BSA_ERROR_SRV_DG_FIND_SERVICE_FAIL 1502u
#define BSA_ERROR_SRV_BLE_FAIL 1600u
#define BSA_ERROR_SRV_BLE_NOT_ENABLED 1601u
#define BSA_ERROR_SRV_BLE_NO_RESOURCE 1602u
#define BSA_ERROR_SRV_BLE_INVALID_PARAM 1603u
#define BSA_ERROR_SRV_BLE_OFFSET BSA_ERROR_SRV_BLE_NO_RESOURCE

#define BSA_TRANSPORT_UNKNOWN 0u
#define BSA_TRANSPORT_BR_EDR 1u
#define BSA_TRANSPORT_LE 2u
typedef tBT_TRANSPORT tBSA_TRANSPORT;

typedef enum {
    BSA_MGT_DISCONNECT_EVT = 0,
    BSA_MGT_STATUS_EVT = 1
} tBSA_MGT_EVT;

typedef struct {
    tBSA_STATUS reason;
} tBSA_MSG_DISCONNECT_MSG;

typedef struct {
    BOOLEAN enable;
} tBSA_MSG_STATUS_MSG;

typedef union {
    tBSA_MSG_DISCONNECT_MSG disconnect;
    tBSA_MSG_STATUS_MSG status;
} tBSA_MGT_MSG;

typedef void tBSA_MGT_CBACK(tBSA_MGT_EVT event, tBSA_MGT_MSG *p_data);

#define BSA_MGT_UIPC_PATH_MAX 100u

typedef struct {
    char uipc_path[BSA_MGT_UIPC_PATH_MAX];
    tBSA_MGT_CBACK *callback;
} tBSA_MGT_OPEN;

typedef struct {
    int dummy;
} tBSA_MGT_KILL_SERVER;

typedef struct {
    int dummy;
} tBSA_MGT_CLOSE;

typedef enum {
    BSA_DM_3D_ANNOUCEMENT_EVT = 0
} tBSA_DM_EVT;

typedef UINT8 tBSA_3DS_ANNOUNC_TYPE;

#define BSA_3DS_ANNOUNC_TYPE_ASSOS_NOTIF_MASK 0x01u
#define BSA_3DS_ANNOUNC_TYPE_BAT_LVL_RPT_MASK 0x02u
#define BSA_DM_3D_ADD_DATA_MAX 1u
#define BSA_DM_3DTV_CONNECT_TYPE_3DSG 0x00u
#define BSA_DM_3DTV_CONNECT_TYPE_RC 0x01u
#define BSA_DM_CONNECT_SUB_TYPE_3DSG_MCAST 0x00u
#define BSA_DM_CONNECT_SUB_TYPE_3DSG_MIP 0x01u
#define BSA_DM_CONNECT_SUB_TYPE_RC 0x00u
#define BSA_DM_CONNECT_SUB_TYPE_RC_VOICE 0x01u

typedef struct {
    BD_ADDR bd_addr;
    tBSA_3DS_ANNOUNC_TYPE type;
    UINT8 battery_level;
    UINT16 additional_data_len;
    UINT8 additional_data[BSA_DM_3D_ADD_DATA_MAX];
} tBSA_DM_3D_ANNOUCEMENT_MSG;

typedef union {
    tBSA_DM_3D_ANNOUCEMENT_MSG announcement;
} tBSA_DM_MSG;

typedef void tBSA_DM_CBACK(tBSA_DM_EVT event, tBSA_DM_MSG *p_data);

#define BSA_DM_CONFIG_NONE 0x00000000u
#define BSA_DM_CONFIG_VISIBILITY_MASK 0x00000001u
#define BSA_DM_CONFIG_BDADDR_MASK 0x00000002u
#define BSA_DM_CONFIG_NAME_MASK 0x00000004u
#define BSA_DM_CONFIG_DEV_CLASS_MASK 0x00000008u
#define BSA_DM_CONFIG_CHANNEL_MASK 0x00000010u
#define BSA_DM_CONFIG_BRCM_MASK 0x00000020u
#define BSA_DM_CONFIG_PAGE_SCAN_PARAM_MASK 0x00000040u
#define BSA_DM_CONFIG_INQ_SCAN_PARAM_MASK 0x00000080u
#define BSA_DM_CONFIG_TX_POWER_MASK 0x00000100u
#define BSA_DM_CONFIG_EIR_MASK 0x00000200u
#define BSA_DM_CONFIG_DUAL_STACK_MODE_MASK 0x00000400u
#define BSA_DM_CONFIG_BLE_BGCONN_TYPE_MASK 0x00000800u
#define BSA_DM_CONFIG_BLE_SCAN_PARAM_MASK 0x00001000u
#define BSA_DM_CONFIG_BLE_VISIBILITY_MASK 0x00002000u
#define BSA_DM_CONFIG_BLE_CONN_PARAM_MASK 0x00004000u
#define BSA_DM_CONFIG_BLE_ADV_CONFIG_MASK 0x00008000u
#define BSA_DM_CONFIG_BLE_ADV_PARAM_MASK 0x00010000u
#define BSA_DM_CONFIG_BLE_PRIVACY_MASK 0x00020000u
#define BSA_DM_CONFIG_LINK_POLICY_MASK 0x00040000u
#define BSA_DM_CONFIG_MONITOR_RSSI 0x00080000u
#define BSA_DM_CONFIG_BLE_CONN_SCAN_PARAM_MASK 0x00100000u
#define BSA_DM_CONFIG_UUID128_EIR_MASK 0x00200000u
#define BSA_DM_CONFIG_DEFAULT_MASK 0x0000001fu

typedef UINT32 tBSA_DM_CONFIG_MASK;
typedef UINT32 tBSA_DM_BLE_AD_MASK;
typedef UINT16 tBSA_DM_MASK;
typedef UINT8 tBSA_DM_DUAL_STACK_MODE;
typedef UINT8 tBSA_DM_CHIP_ID;
typedef UINT8 tBSA_DM_LE_ADDR_TYPE;
typedef UINT16 tBSA_DM_LP_MASK;
typedef UINT8 tBTA_BLE_ADV_EVT;
typedef UINT8 tBTA_BLE_ADV_CHNL_MAP;
typedef UINT8 tBTA_BLE_AFP;
typedef UINT8 tBTA_BLE_ADV_TX_POWER;

#define BSA_DM_EIR_SIZE_MAX 200u
#define BSA_DM_BLE_AD_DATA_LEN 31u
#define BSA_DM_BLE_AD_UUID_MAX 6u
#define BSA_DM_LAST_AFH_CHANNEL 79u

#define BSA_DM_BLE_LIMIT_DISC_FLAG 0x01u
#define BSA_DM_BLE_GEN_DISC_FLAG 0x02u
#define BSA_DM_BLE_BREDR_NOT_SPT 0x04u
#define BSA_DM_BLE_NON_LIMIT_DISC_FLAG 0x00u
#define BSA_DM_BLE_ADV_FLAG_MASK 0x07u
#define BSA_DM_BLE_LIMIT_DISC_MASK 0x01u

#define BSA_DM_BLE_AD_BIT_DEV_NAME 0x00000001u
#define BSA_DM_BLE_AD_BIT_FLAGS 0x00000002u
#define BSA_DM_BLE_AD_BIT_MANU 0x00000004u
#define BSA_DM_BLE_AD_BIT_TX_PWR 0x00000008u
#define BSA_DM_BLE_AD_BIT_INT_RANGE 0x00000020u
#define BSA_DM_BLE_AD_BIT_SERVICE 0x00000040u
#define BSA_DM_BLE_AD_BIT_SERVICE_SOL 0x00000080u
#define BSA_DM_BLE_AD_BIT_SERVICE_DATA 0x00000100u
#define BSA_DM_BLE_AD_BIT_SIGN_DATA 0x00000200u
#define BSA_DM_BLE_AD_BIT_SERVICE_128SOL 0x00000400u
#define BSA_DM_BLE_AD_BIT_APPEARANCE 0x00000800u
#define BSA_DM_BLE_AD_BIT_PUBLIC_ADDR 0x00001000u
#define BSA_DM_BLE_AD_BIT_RANDOM_ADDR 0x00002000u
#define BSA_DM_BLE_AD_BIT_PROPRIETARY 0x00008000u
#define BSA_DM_BLE_AD_BIT_SERVICE_128 0x00010000u

#define BSA_DM_BLE_CONNECT_EVT 0x00u
#define BSA_DM_BLE_CONNECT_DIR_EVT 0x01u
#define BSA_DM_BLE_DISCOVER_EVT 0x02u
#define BSA_DM_BLE_NON_CONNECT_EVT 0x03u
#define BSA_DM_BLE_ADV_CHNL_37 0x01u
#define BSA_DM_BLE_ADV_CHNL_38 0x02u
#define BSA_DM_BLE_ADV_CHNL_39 0x04u
#define BSA_DM_BLE_ADV_CHNL_ALL 0x07u
#define BSA_DM_BLE_ADV_FILTER_ALL 0x00u
#define BSA_DM_BLE_ADV_FILTER_SCAN_WL 0x01u
#define BSA_DM_BLE_ADV_FILTER_CONN_WL 0x02u
#define BSA_DM_BLE_ADV_FILTER_SCAN_CONN_WL 0x03u
#define BSA_DM_BLE_ADV_TX_POWER_MIN 0u
#define BSA_DM_BLE_ADV_TX_POWER_LOW 1u
#define BSA_DM_BLE_ADV_TX_POWER_MID 2u
#define BSA_DM_BLE_ADV_TX_POWER_UPPER 3u
#define BSA_DM_BLE_ADV_TX_POWER_MAX 4u
#define BSA_DM_BLE_SCAN_MODE_PASS 0u
#define BSA_DM_BLE_SCAN_MODE_ACTI 1u
#define BSA_DM_BLE_SCAN_MODE_NONE 0xffu
#define BSA_DM_BLE_BG_CONN_NONE 0u
#define BSA_DM_BLE_BG_CONN_AUTO 1u
#define BSA_DM_BLE_ADV_INT_MIN 0x0020u
#define BSA_DM_BLE_ADV_INT_MAX 0x4000u
#define BSA_DM_BLE_SCAN_INT_MIN 0x0004u
#define BSA_DM_BLE_SCAN_INT_MAX 0x4000u
#define BSA_DM_BLE_SCAN_WIN_MIN 0x0004u
#define BSA_DM_BLE_SCAN_WIN_MAX 0x4000u
#define BSA_DM_BLE_CONN_INT_MIN 0x0006u
#define BSA_DM_BLE_CONN_INT_MAX 0x0c80u
#define BSA_DM_BLE_CONN_LATENCY_MAX 500u
#define BSA_DM_BLE_CONN_TOUT_MIN 0x000au
#define BSA_DM_BLE_CONN_TOUT_MAX 0x0c80u

/* Names used by the transitive BTA types in the original BSA interface. */
#define BTA_BLE_CONNECT_EVT BSA_DM_BLE_CONNECT_EVT
#define BTA_BLE_CONNECT_DIR_EVT BSA_DM_BLE_CONNECT_DIR_EVT
#define BTA_BLE_DISCOVER_EVT BSA_DM_BLE_DISCOVER_EVT
#define BTA_BLE_NON_CONNECT_EVT BSA_DM_BLE_NON_CONNECT_EVT
#define BTA_BLE_ADV_CHNL_37 BSA_DM_BLE_ADV_CHNL_37
#define BTA_BLE_ADV_CHNL_38 BSA_DM_BLE_ADV_CHNL_38
#define BTA_BLE_ADV_CHNL_39 BSA_DM_BLE_ADV_CHNL_39
#define AP_SCAN_CONN_ALL BSA_DM_BLE_ADV_FILTER_ALL
#define AP_SCAN_WL_CONN_ALL BSA_DM_BLE_ADV_FILTER_SCAN_WL
#define AP_SCAN_ALL_CONN_WL BSA_DM_BLE_ADV_FILTER_CONN_WL
#define AP_SCAN_CONN_WL BSA_DM_BLE_ADV_FILTER_SCAN_CONN_WL
#define BTA_BLE_ADV_TX_POWER_MIN BSA_DM_BLE_ADV_TX_POWER_MIN
#define BTA_BLE_ADV_TX_POWER_LOW BSA_DM_BLE_ADV_TX_POWER_LOW
#define BTA_BLE_ADV_TX_POWER_MID BSA_DM_BLE_ADV_TX_POWER_MID
#define BTA_BLE_ADV_TX_POWER_UPPER BSA_DM_BLE_ADV_TX_POWER_UPPER
#define BTA_BLE_ADV_TX_POWER_MAX BSA_DM_BLE_ADV_TX_POWER_MAX

#define BSA_DM_DUAL_STACK_MODE_BSA 0u
#define BSA_DM_DUAL_STACK_MODE_MM 1u
#define BSA_DM_DUAL_STACK_MODE_BTC 2u
#define BSA_DM_3DTV_MASTER_MASK 0x0001u
#define BSA_DM_3DTV_SLAVE_MASK 0x0002u
#define BSA_DM_RC_ASSO_MASK 0x0004u
#define BSA_DM_SHOW_ROOM_MASK 0x0008u
#define BSA_DM_RC_PAIRABLE_MASK 0x0010u
#define BSA_DM_3D_CAP_MASK 0x0020u
#define BSA_DM_3D_FACTORY_TEST_MASK 0x0040u
#define BSA_DM_3D_TX_DATA_MASK 0x0080u
#define BSA_DM_3D_TX_DATA_ONLY_MASK 0x0100u
#define BSA_DM_TX_POWER_CLASS_2 0x00u
#define BSA_DM_TX_POWER_CLASS_1_5 0x01u
#define BSA_DM_TX_POWER_UNKNOWN 0xffu

#define BSA_DM_CHIP_ID_UNKNOWN 0x00u
#define BSA_DM_CHIP_ID_2046B1 0x0cu
#define BSA_DM_CHIP_ID_20702A1 0x3fu
#define BSA_DM_CHIP_ID_20702B0 0x49u
#define BSA_DM_CHIP_ID_43242A1 0x55u
#define BSA_DM_CHIP_ID_43569A2 0x6cu
#define BSA_DM_CHIP_ID_20704A1 0x66u
#define BSA_DM_CHIP_ID_20704A2 0x7eu

#define BSA_DM_BLE_ADDR_PUBLIC 0u
#define BSA_DM_BLE_ADDR_RANDOM 1u
#define BSA_DM_BLE_ADDR_TYPE_MASK 1u
#define BSA_DM_LP_SWITCH 0x0001u
#define BSA_DM_LP_HOLD 0x0002u
#define BSA_DM_LP_SNIFF 0x0004u
#define BSA_DM_LP_PARK 0x0008u

typedef struct {
    UINT16 left_open_offset;
    UINT16 left_close_offset;
    UINT16 right_open_offset;
    UINT16 right_close_offset;
    UINT16 delay;
    UINT8 dual_view;
} tBSA_DM_3D_TX_DATA;

typedef struct {
    BD_ADDR bd_addr;
    UINT16 min_conn_int;
    UINT16 max_conn_int;
    UINT16 slave_latency;
    UINT16 supervision_tout;
    BOOLEAN preference;
} tBSA_DM_BLE_CONN_PARAM;

typedef struct {
    UINT16 interval;
    UINT16 window;
} tBSA_DM_BLE_CONN_SCAN_PARAM;

typedef struct {
    UINT16 low;
    UINT16 hi;
} tBSA_DM_BLE_INT_RANGE;

typedef struct {
    UINT8 adv_type;
    UINT8 len;
    UINT8 val[32];
} tBSA_DM_BLE_PROP_ELEM;

typedef struct {
    UINT8 num_elem;
    tBSA_DM_BLE_PROP_ELEM elem[3];
} tBSA_DM_BLE_PROPRIETARY;

typedef struct {
    BOOLEAN list_cmpl;
    UINT8 uuid128[16];
} tBSA_DM_BLE_128SERVICE;

typedef struct {
    UINT8 len;
    UINT8 flag;
    UINT8 p_val[BSA_DM_BLE_AD_DATA_LEN];
    tBSA_DM_BLE_AD_MASK adv_data_mask;
    UINT16 appearance_data;
    UINT8 num_service;
    UINT16 uuid_val[BSA_DM_BLE_AD_UUID_MAX];
    UINT8 service_data_len;
    tBT_UUID service_data_uuid;
    UINT8 service_data_val[BSA_DM_BLE_AD_DATA_LEN];
    BOOLEAN is_scan_rsp;
    UINT8 tx_power;
    tBSA_DM_BLE_INT_RANGE int_range;
    tBSA_DM_BLE_128SERVICE services_128b;
    tBSA_DM_BLE_128SERVICE sol_service_128b;
    tBSA_DM_BLE_PROPRIETARY proprietary;
    UINT8 inst_id;
} tBSA_DM_BLE_ADV_CONFIG;

typedef struct {
    tBSA_DM_LE_ADDR_TYPE type;
    BD_ADDR bd_addr;
} tBSA_DM_BLE_BD_ADDR;

typedef struct {
    UINT16 adv_int_min;
    UINT16 adv_int_max;
    tBSA_DM_BLE_BD_ADDR dir_bda;
    tBTA_BLE_ADV_EVT adv_type;
    tBTA_BLE_ADV_CHNL_MAP channel_map;
    tBTA_BLE_AFP adv_filter_policy;
    tBTA_BLE_ADV_TX_POWER tx_power;
    UINT8 inst_id;
} tBSA_DM_BLE_ADV_PARAM;

typedef struct {
    BD_ADDR link_bd_addr;
    tBSA_DM_LP_MASK policy_mask;
    BOOLEAN set;
} tBSA_DM_LINK_POLICY_PARAM;

typedef struct {
    BOOLEAN enable;
    UINT16 period;
} tBSA_DM_MONITOR_RSSI_PARAM;

typedef struct {
    BOOLEAN isAdd;
    tBT_UUID UUID;
} tBSA_DM_ADD_REMOVE_EIR_PARAM;

typedef struct {
    tBSA_DM_CONFIG_MASK config_mask;
    BOOLEAN enable;
    BOOLEAN discoverable;
    BOOLEAN connectable;
    BD_ADDR bd_addr;
    BD_NAME name;
    DEV_CLASS class_of_device;
    UINT8 first_disabled_channel;
    UINT8 last_disabled_channel;
    BD_ADDR master_3d_bd_addr;
    UINT8 path_loss_threshold;
    UINT16 page_scan_interval;
    UINT16 page_scan_window;
    UINT16 inquiry_scan_interval;
    UINT16 inquiry_scan_window;
    tBSA_DM_CBACK *callback;
    UINT8 tx_power;
    tBSA_DM_MASK brcm_mask;
    UINT8 eir_length;
    UINT8 eir_data[BSA_DM_EIR_SIZE_MAX];
    tBSA_DM_DUAL_STACK_MODE dual_stack_mode;
    tBSA_DM_3D_TX_DATA tx_data_3d;
    UINT8 ble_bg_conn_type;
    UINT16 ble_scan_interval;
    UINT16 ble_scan_window;
    UINT8 ble_scan_mode;
    BOOLEAN ble_discoverable;
    BOOLEAN ble_connectable;
    BOOLEAN privacy_enable;
    tBSA_DM_BLE_CONN_PARAM ble_conn_param;
    tBSA_DM_BLE_CONN_SCAN_PARAM ble_conn_scan_param;
    tBSA_DM_BLE_ADV_CONFIG adv_config;
    tBSA_DM_BLE_ADV_PARAM ble_adv_param;
    tBSA_DM_CHIP_ID chip_id;
    tBSA_DM_LINK_POLICY_PARAM policy_param;
    tBSA_DM_MONITOR_RSSI_PARAM monitor_rssi_param;
    tBSA_DM_ADD_REMOVE_EIR_PARAM add_remove_eir_param;
} tBSA_DM_GET_CONFIG;

typedef tBSA_DM_GET_CONFIG tBSA_DM_SET_CONFIG;

typedef struct {
    UINT16 vendor_id;
    UINT16 product_id;
    UINT16 version;
    BOOLEAN primary;
    UINT16 vendor_id_source;
} tBSA_DM_GET_DI;

typedef tBSA_DM_GET_DI tBSA_DM_SET_DI;

typedef enum {
    BSA_DISC_NEW_EVT = 0,
    BSA_DISC_CMPL_EVT = 1,
    BSA_DISC_DEV_INFO_EVT = 2,
    BSA_DISC_REMOTE_NAME_EVT = 3
} tBSA_DISC_EVT;

#define BSA_DISC_VID_SRC_BT 1u
#define BSA_DISC_VID_SRC_USB 2u
#define BSA_DISC_VID_PID_MAX 1u
#define BSA_EIR_DATA_LENGTH 240u

typedef struct {
    BOOLEAN valid;
    UINT16 vendor_id_source;
    UINT16 vendor;
    UINT16 product;
    UINT16 version;
} tBSA_DISC_VID_PID;

typedef struct {
    BD_ADDR bd_addr;
    DEV_CLASS class_of_device;
    BD_NAME name;
    int rssi;
    tBSA_SERVICE_MASK services;
    tBSA_DISC_VID_PID eir_vid_pid[BSA_DISC_VID_PID_MAX];
    UINT8 eir_data[BSA_EIR_DATA_LENGTH];
    UINT8 inq_result_type;
    UINT8 ble_addr_type;
    tBT_DEVICE_TYPE device_type;
} tBSA_DISC_REMOTE_DEV;

typedef tBSA_DISC_REMOTE_DEV tBSA_DISC_NEW_MSG;

typedef struct {
    BOOLEAN in_use;
    tBSA_DISC_REMOTE_DEV device;
} tBSA_DISC_DEV;

typedef struct {
    tBSA_STATUS status;
    BD_ADDR bd_addr;
    UINT8 index;
    UINT16 spec_id;
    BOOLEAN primary;
    UINT16 vendor;
    UINT16 vendor_id_source;
    UINT16 product;
    UINT16 version;
} tBSA_DISC_DEV_INFO_MSG;

typedef struct {
    UINT16 status;
    BD_ADDR bd_addr;
    UINT16 length;
    BD_NAME remote_bd_name;
} tBSA_DISC_READ_REMOTE_NAME_MSG;

typedef union {
    tBSA_DISC_NEW_MSG disc_new;
    tBSA_DISC_DEV_INFO_MSG dev_info;
    tBSA_DISC_READ_REMOTE_NAME_MSG remote_name;
} tBSA_DISC_MSG;

typedef void tBSA_DISC_CBACK(tBSA_DISC_EVT event, tBSA_DISC_MSG *p_data);

typedef UINT8 tBSA_DM_INQ_FILT;
typedef UINT8 tBSA_DM_INQ_MODE;
typedef UINT8 tBSA_DISC_BRCM_FILTER;
typedef UINT8 tBSA_DISC_UPDATE;

typedef struct {
    DEV_CLASS dev_class;
    DEV_CLASS dev_class_mask;
} tBSA_DM_COD_COND;

typedef union {
    BD_ADDR bd_addr;
    tBSA_DM_COD_COND dev_class_cond;
} tBSA_DM_INQ_COND;

#define BSA_DM_INQ_CLR 0u
#define BSA_DM_INQ_DEV_CLASS 1u
#define BSA_DM_INQ_BD_ADDR 2u
#define BSA_DM_GENERAL_INQUIRY 0x01u
#define BSA_DM_LIMITED_INQUIRY 0x02u
#define BSA_BLE_INQUIRY_NONE 0x00u
#define BSA_BLE_GENERAL_INQUIRY 0x10u
#define BSA_BLE_LIMITED_INQUIRY 0x20u
#define BSA_DISC_BRCM_3DTV_MASTER_FILTER 0x01u
#define BSA_DISC_UPDATE_END 0u
#define BSA_DISC_UPDATE_ANY 1u
#define BSA_DISC_UPDATE_NAME 2u
#define BSA_INQ_RESULT_BR 0x01u
#define BSA_INQ_RESULT_BLE 0x02u

typedef struct {
    int duration;
    tBSA_DISC_CBACK *cback;
    int nb_devices;
    tBSA_DM_INQ_FILT filter_type;
    tBSA_DM_INQ_COND filter_cond;
    tBSA_SERVICE_MASK services;
    tBSA_DM_INQ_MODE mode;
    BOOLEAN device_info;
    tBSA_DISC_BRCM_FILTER brcm_filter;
    BOOLEAN skip_name_request;
    tBSA_DISC_UPDATE update;
    INT8 inq_tx_power;
} tBSA_DISC_START;

typedef struct {
    int dummy;
} tBSA_DISC_ABORT;

typedef struct {
    BD_ADDR bd_addr;
    tBSA_DISC_CBACK *cback;
} tBSA_DISC_DEV_INFO;

typedef struct {
    BD_ADDR bd_addr;
    tBSA_DISC_CBACK *cback;
    tBSA_TRANSPORT transport;
} tBSA_DISC_READ_REMOTE_NAME;

typedef enum {
    BSA_SEC_LINK_UP_EVT = 0,
    BSA_SEC_LINK_DOWN_EVT = 1,
    BSA_SEC_PIN_REQ_EVT = 2,
    BSA_SEC_AUTH_CMPL_EVT = 3,
    BSA_SEC_BOND_CANCEL_CMPL_EVT = 4,
    BSA_SEC_AUTHORIZE_EVT = 5,
    BSA_SEC_SP_CFM_REQ_EVT = 6,
    BSA_SEC_SP_KEY_NOTIF_EVT = 7,
    BSA_SEC_SP_KEY_REQ_EVT = 8,
    BSA_SEC_SP_RMT_OOB_EVT = 9,
    BSA_SEC_LOCAL_OOB_DATA_EVT = 10,
    BSA_SEC_SP_KEYPRESS_EVT = 11,
    BSA_SEC_BLE_KEY_EVT = 12,
    BSA_SEC_BLE_PASSKEY_REQ_EVT = 13,
    BSA_SEC_SUSPENDED_EVT = 14,
    BSA_SEC_RESUMED_EVT = 15,
    BSA_SEC_RSSI_EVT = 16,
    BSA_SEC_UNKNOWN_LINKKEY_EVT = 17
} tBSA_SEC_EVT;

typedef UINT8 tBSA_SEC_AUTH_RESP;
typedef UINT8 tBSA_SEC_AUTH;
typedef UINT8 tBSA_SEC_IO_CAP;
typedef UINT8 tBSA_SEC_SP_KEY_TYPE;
typedef UINT8 tBSA_LE_KEY_TYPE;
typedef INT8 tBSA_SIG_STRENGTH_MASK;
typedef INT8 tBSA_SEC_RSSI_VALUE;
typedef UINT8 tBSA_SEC_LINK_QUALITY_VALUE;
typedef INT8 tBSA_SEC_RAW_RSSI_VALUE;

#define BSA_SEC_AUTH_PERM 0u
#define BSA_SEC_AUTH_TEMP 1u
#define BSA_SEC_NOT_AUTH 2u
#define BSA_SEC_NONE 0x00u
#define BSA_SEC_AUTHORIZATION 0x01u
#define BSA_SEC_AUTHENTICATION 0x12u
#define BSA_SEC_ENCRYPTION 0x24u
#define BSA_SEC_IO_CAP_OUT 0u
#define BSA_SEC_IO_CAP_IO 1u
#define BSA_SEC_IO_CAP_IN 2u
#define BSA_SEC_IO_CAP_NONE 3u
#define BSA_SEC_IO_CAP_KBDISP 4u
#define BSA_SEC_AUTH_REQ_SETTING 3u
#define BSA_BLE_SEC_AUTH_REQ_SETTING 0x0du
#define BSA_SEC_AUTH_SP_NO 0u
#define BSA_SEC_AUTH_SP_YES 1u
#define BSA_SEC_AUTH_AP_NO 2u
#define BSA_SEC_AUTH_AP_YES 3u
#define BSA_SEC_AUTH_SPGB_NO 4u
#define BSA_SEC_AUTH_SPGB_YES 5u
#define BSA_LE_AUTH_REQ_NO_BOND 0x00u
#define BSA_LE_AUTH_REQ_BOND 0x01u
#define BSA_LE_AUTH_REQ_MITM 0x04u
#define BSA_LE_AUTH_REQ_SC_ONLY 0x08u
#define BSA_LE_AUTH_REQ_SC_BOND 0x09u
#define BSA_LE_AUTH_REQ_SC_MITM 0x0cu
#define BSA_LE_AUTH_REQ_SC_MITM_BOND 0x0du
#define BSA_LE_AUTH_REQ_MASK 0x1du
#define BSA_SP_KEY_STARTED 0u
#define BSA_SP_KEY_ENTERED 1u
#define BSA_SP_KEY_ERASED 2u
#define BSA_SP_KEY_CLEARED 3u
#define BSA_SP_KEY_COMPLETE 4u
#define BSA_LE_KEY_PENC 0x01u
#define BSA_LE_KEY_PID 0x02u
#define BSA_LE_KEY_PCSRK 0x04u
#define BSA_LE_KEY_LENC 0x10u
#define BSA_LE_KEY_LID 0x20u
#define BSA_LE_KEY_LCSRK 0x40u
#define BT_DEVICE_TYPE_BREDR 0x01u
#define BT_DEVICE_TYPE_BLE 0x02u
#define BT_DEVICE_TYPE_DUMO 0x03u
#define BSA_SEC_SIG_STRENGTH_RSSI 1
#define BSA_SEC_SIG_STRENGTH_LINK_QUALITY 2
#define BSA_SEC_SIG_STRENGTH_RAW_RSSI 4

#define BSA_RES_SERVICE_ID 0u
#define BSA_SPP_SERVICE_ID 1u
#define BSA_DUN_SERVICE_ID 2u
#define BSA_A2DP_SRC_SERVICE_ID 3u
#define BSA_AVRCP_TG_SERVICE_ID 4u
#define BSA_HSP_SERVICE_ID 5u
#define BSA_HFP_SERVICE_ID 6u
#define BSA_OPP_SERVICE_ID 7u
#define BSA_FTP_SERVICE_ID 8u
#define BSA_SYNC_SERVICE_ID 9u
#define BSA_BPP_SERVICE_ID 10u
#define BSA_BIP_SERVICE_ID 11u
#define BSA_PANU_SERVICE_ID 12u
#define BSA_NAP_SERVICE_ID 13u
#define BSA_GN_SERVICE_ID 14u
#define BSA_SAP_SERVICE_ID 15u
#define BSA_A2DP_SERVICE_ID 16u
#define BSA_AVRCP_SERVICE_ID 17u
#define BSA_HID_SERVICE_ID 18u
#define BSA_VDP_SERVICE_ID 19u
#define BSA_PBAP_SERVICE_ID 20u
#define BSA_HSP_HS_SERVICE_ID 21u
#define BSA_HFP_HS_SERVICE_ID 22u
#define BSA_MAP_SERVICE_ID 23u
#define BSA_HL_SERVICE_ID 25u
#define BSA_HID_DEVICE_SERVICE_ID 30u
#define BSA_BLE_SERVICE_ID 31u
#define BSA_MAX_SERVICE_ID 32u

#define BSA_RES_SERVICE_MASK 0x00000001u
#define BSA_SPP_SERVICE_MASK 0x00000002u
#define BSA_DUN_SERVICE_MASK 0x00000004u
#define BSA_A2DP_SRC_SERVICE_MASK 0x00000008u
#define BSA_AVRCP_TG_SERVICE_MASK 0x00000010u
#define BSA_HSP_SERVICE_MASK 0x00000020u
#define BSA_HFP_SERVICE_MASK 0x00000040u
#define BSA_OPP_SERVICE_MASK 0x00000080u
#define BSA_FTP_SERVICE_MASK 0x00000100u
#define BSA_SYNC_SERVICE_MASK 0x00000200u
#define BSA_BPP_SERVICE_MASK 0x00000400u
#define BSA_BIP_SERVICE_MASK 0x00000800u
#define BSA_PANU_SERVICE_MASK 0x00001000u
#define BSA_NAP_SERVICE_MASK 0x00002000u
#define BSA_GN_SERVICE_MASK 0x00004000u
#define BSA_SAP_SERVICE_MASK 0x00008000u
#define BSA_A2DP_SERVICE_MASK 0x00010000u
#define BSA_AVRCP_SERVICE_MASK 0x00020000u
#define BSA_HID_SERVICE_MASK 0x00040000u
#define BSA_VDP_SERVICE_MASK 0x00080000u
#define BSA_PBAP_SERVICE_MASK 0x00100000u
#define BSA_HSP_HS_SERVICE_MASK 0x00200000u
#define BSA_HFP_HS_SERVICE_MASK 0x00400000u
#define BSA_MAS_SERVICE_MASK 0x00800000u
#define BSA_MN_SERVICE_MASK 0x01000000u
#define BSA_HL_SERVICE_MASK 0x02000000u
#define BSA_BLE_SERVICE_MASK 0x40000000u
#define BSA_HID_DEVICE_SERVICE_MASK 0x80000000u
#define BSA_ALL_SERVICE_MASK 0x7ffffffeu

typedef struct {
    BD_ADDR bd_addr;
    BD_NAME bd_name;
    DEV_CLASS class_of_device;
} tBSA_SEC_PIN_REQ;

typedef struct {
    BD_ADDR bd_addr;
    BD_NAME bd_name;
    BOOLEAN key_present;
    LINK_KEY key;
    UINT8 key_type;
    BOOLEAN success;
    UINT8 fail_reason;
} tBSA_SEC_AUTH_CMPL;

typedef struct {
    tBSA_STATUS status;
} tBSA_SEC_BOND_CANCEL_CMPL;

typedef struct {
    BD_ADDR bd_addr;
    BD_NAME bd_name;
    tBSA_SERVICE_ID service;
} tBSA_SEC_AUTHORIZE;

typedef struct {
    BD_ADDR bd_addr;
    DEV_CLASS class_of_device;
    tBSA_TRANSPORT link_type;
} tBSA_SEC_LINK_UP;

typedef struct {
    BD_ADDR bd_addr;
    UINT8 status;
    tBSA_TRANSPORT link_type;
} tBSA_SEC_LINK_DOWN;

typedef struct {
    BD_ADDR bd_addr;
    DEV_CLASS class_of_device;
    BD_NAME bd_name;
    int num_val;
    BOOLEAN just_works;
    BOOLEAN is_ble;
} tBSA_SEC_SP_CFM_REQ;

typedef struct {
    BD_ADDR bd_addr;
    tBSA_SEC_SP_KEY_TYPE notif_type;
} tBSA_SEC_SP_KEY_PRESS;

typedef struct {
    BD_ADDR bd_addr;
    DEV_CLASS class_of_device;
    BD_NAME bd_name;
    int passkey;
    tBSA_TRANSPORT link_type;
} tBSA_SEC_SP_KEY_NOTIF;

typedef struct {
    BOOLEAN valid;
    BT_OCTET16 c;
    BT_OCTET16 r;
} tBSA_SEC_LOCAL_OOB_MSG;

typedef struct {
    BD_ADDR bd_addr;
    DEV_CLASS class_of_device;
    BD_NAME bd_name;
} tBSA_SEC_SP_RMT_OOB;

typedef struct {
    BD_ADDR bd_addr;
} tBSA_SEC_SUSPENDED_MSG;

typedef struct {
    BD_ADDR bd_addr;
} tBSA_SEC_RESUMED_MSG;

typedef struct {
    BT_OCTET16 ltk;
    BT_OCTET8 rand;
    UINT16 ediv;
    UINT8 sec_level;
    UINT8 key_size;
} tBSA_LE_PENC_KEYS;

typedef struct {
    UINT32 counter;
    BT_OCTET16 csrk;
    UINT8 sec_level;
} tBSA_LE_PCSRK_KEYS;

typedef struct {
    UINT32 counter;
    UINT16 div;
    UINT8 sec_level;
    BT_OCTET16 csrk;
} tBSA_LE_LCSRK_KEYS;

typedef struct {
    BT_OCTET16 irk;
    UINT8 addr_type;
    BD_ADDR static_addr;
} tBSA_LE_PID_KEYS;

typedef struct {
    BT_OCTET16 ltk;
    UINT16 div;
    UINT8 key_size;
    UINT8 sec_level;
} tBSA_LE_LENC_KEYS;

typedef union {
    tBSA_LE_PENC_KEYS penc_key;
    tBSA_LE_PCSRK_KEYS pcsrk_key;
    tBSA_LE_PID_KEYS pid_key;
    tBSA_LE_LENC_KEYS lenc_key;
    tBSA_LE_LCSRK_KEYS lcsrk_key;
} tBSA_LE_KEY_VALUE;

typedef struct {
    BD_ADDR bd_addr;
    tBSA_LE_KEY_TYPE key_type;
    tBSA_LE_KEY_VALUE key_value;
} tBSA_SEC_BLE_KEY;

typedef struct {
    BD_ADDR bd_addr;
} tBSA_SEC_BLE_PASSKEY_REQ;

typedef struct {
    BD_ADDR bd_addr;
    tBSA_SIG_STRENGTH_MASK mask;
    tBSA_SEC_RSSI_VALUE rssi_value;
    tBSA_SEC_RAW_RSSI_VALUE raw_rssi_value;
    tBSA_SEC_LINK_QUALITY_VALUE link_quality_value;
    tBSA_TRANSPORT link_type;
} tBSA_SEC_SIG_STRENGTH;

typedef struct {
    BD_ADDR bd_addr;
} tBSA_SEC_LINK_KEY_UNKNOWN;

typedef union {
    tBSA_SEC_PIN_REQ pin_req;
    tBSA_SEC_AUTH_CMPL auth_cmpl;
    tBSA_SEC_BOND_CANCEL_CMPL bond_cancel;
    tBSA_SEC_AUTHORIZE authorize;
    tBSA_SEC_LINK_UP link_up;
    tBSA_SEC_LINK_DOWN link_down;
    tBSA_SEC_SP_CFM_REQ cfm_req;
    tBSA_SEC_SP_KEY_NOTIF key_notif;
    tBSA_SEC_SP_RMT_OOB rmt_oob;
    tBSA_SEC_LOCAL_OOB_MSG local_oob;
    tBSA_SEC_SP_KEY_PRESS key_press;
    tBSA_SEC_SUSPENDED_MSG suspended;
    tBSA_SEC_RESUMED_MSG resumed;
    tBSA_SEC_BLE_KEY ble_key;
    tBSA_SEC_BLE_PASSKEY_REQ ble_passkey_req;
    tBSA_SEC_SIG_STRENGTH sig_strength;
    tBSA_SEC_LINK_KEY_UNKNOWN lost_link_key;
} tBSA_SEC_MSG;

typedef void tBSA_SEC_CBACK(tBSA_SEC_EVT event, tBSA_SEC_MSG *p_data);

typedef struct {
    tBSA_SEC_IO_CAP simple_pairing_io_cap;
    UINT8 auth_req;
    UINT8 le_auth_req;
    tBSA_SEC_CBACK *sec_cback;
    BOOLEAN ssp_debug;
} tBSA_SEC_SET_SECURITY;

typedef struct {
    BD_ADDR bd_addr;
    PIN_CODE pin_code;
    UINT8 pin_len;
    UINT32 ble_passkey;
    BOOLEAN is_ble;
    BOOLEAN ble_accept;
} tBSA_SEC_PIN_CODE_REPLY;

typedef struct {
    BD_ADDR bd_addr;
    UINT32 passkey;
} tBSA_SEC_PASSKEY_REPLY;

typedef struct {
    BD_ADDR bd_addr;
    BOOLEAN accept;
    BOOLEAN is_ble;
} tBSA_SEC_SP_CFM_REPLY;

typedef struct {
    BD_ADDR bd_addr;
} tBSA_SEC_BOND;

typedef struct {
    BD_ADDR bd_addr;
} tBSA_SEC_BOND_CANCEL;

typedef struct {
    BD_ADDR bd_addr;
    tBSA_SERVICE_ID trusted_service;
    tBSA_SEC_AUTH_RESP auth;
} tBSA_SEC_AUTH_REPLY;

typedef struct {
    BD_ADDR bd_addr;
} tBSA_SEC_REMOVE_DEV;

typedef struct {
    BD_ADDR bd_addr;
    DEV_CLASS class_of_device;
    LINK_KEY link_key;
    BOOLEAN link_key_present;
    tBSA_SERVICE_MASK trusted_services;
    BOOLEAN is_trusted;
    UINT8 key_type;
    tBSA_SEC_IO_CAP io_cap;
    BD_NAME bd_name;
    BD_FEATURES features;
    UINT8 ble_addr_type;
    tBT_DEVICE_TYPE device_type;
    UINT8 inq_result_type;
    BOOLEAN ble_link_key_present;
    tBSA_LE_PENC_KEYS le_penc_key;
    tBSA_LE_PCSRK_KEYS le_pcsrk_key;
    tBSA_LE_LCSRK_KEYS le_lcsrk_key;
    tBSA_LE_PID_KEYS le_pid_key;
    tBSA_LE_LENC_KEYS le_lenc_key;
} tBSA_SEC_ADD_DEV;

typedef struct {
    BD_ADDR bd_addr;
    BT_OCTET16 p_c;
    BT_OCTET16 p_r;
    BOOLEAN result;
} tBSA_SEC_SET_REMOTE_OOB;

typedef struct {
    int dummy;
} tBSA_SEC_READ_OOB;

#define BSA_DEV_PLATFORM_UNKNOWN 0u
#define BSA_DEV_PLATFORM_IOS 1u

typedef struct {
    BD_ADDR bd_addr;
    UINT8 platform;
} tBSA_SEC_ADD_SI_DEV;

typedef struct {
    BD_ADDR bd_addr;
} tBSA_SEC_CLOSE_ACL;

#define BSA_DG_OPEN_EVT 0u
#define BSA_DG_CLOSE_EVT 1u
#define BSA_DG_FIND_SERVICE_EVT 2u
#define BSA_DG_GET_SDP_EVT 3u
#define BSA_DG_MAX_NUM_OF_UUID 10u
#define BSA_SERVICE_NAME_LEN 35u

typedef UINT8 tBSA_DG_EVT;
typedef UINT16 tBSA_DG_HNDL;

typedef struct {
    tBSA_STATUS status;
    BD_ADDR bd_addr;
    tBSA_SERVICE_ID service;
    tBSA_DG_HNDL handle;
    tUIPC_CH_ID uipc_channel;
} tBSA_DG_OPEN_MSG;

typedef struct {
    tBSA_DG_HNDL handle;
} tBSA_DG_CLOSE_MSG;

typedef struct {
    tBSA_STATUS status;
    BD_ADDR bd_addr;
    tBT_UUID uuid;
    BOOLEAN found;
    UINT8 scn;
    char name[BSA_SERVICE_NAME_LEN];
} tBSA_DG_FIND_SERVICE_MSG;

typedef struct {
    tBT_UUID uuid[BSA_DG_MAX_NUM_OF_UUID];
    UINT16 num_of_uuid;
} tBSA_DG_SDP_DISC_ATTR;

typedef struct {
    tBSA_STATUS status;
    BD_ADDR bd_addr;
    BOOLEAN found;
    UINT8 scn;
    UINT16 protocol_uuid;
    char name[BSA_SERVICE_NAME_LEN];
    BOOLEAN complete;
    tBSA_DG_SDP_DISC_ATTR attrs;
} tBSA_DG_GET_SDP_MSG;

typedef union {
    tBSA_DG_OPEN_MSG open;
    tBSA_DG_CLOSE_MSG close;
    tBSA_DG_FIND_SERVICE_MSG find_service;
    tBSA_DG_GET_SDP_MSG sdp;
} tBSA_DG_MSG;

typedef void tBSA_DG_CBACK(tBSA_DG_EVT event, tBSA_DG_MSG *p_data);

typedef struct {
    tBSA_DG_CBACK *p_cback;
} tBSA_DG_ENABLE;

typedef struct {
    UINT8 dummy;
} tBSA_DG_DISABLE;

typedef struct {
    tBSA_SERVICE_ID service;
    tBSA_SEC_AUTH sec_mask;
    char service_name[BSA_SERVICE_NAME_LEN];
    tBSA_DG_HNDL handle;
    tBT_UUID uuid;
} tBSA_DG_LISTEN;

typedef struct {
    BD_ADDR bd_addr;
    tBSA_SERVICE_ID service;
    tBSA_SEC_AUTH sec_mask;
    char service_name[BSA_SERVICE_NAME_LEN];
    tBT_UUID uuid;
} tBSA_DG_OPEN;

typedef struct {
    tBSA_DG_HNDL handle;
} tBSA_DG_CLOSE;

typedef struct {
    tBSA_DG_HNDL handle;
} tBSA_DG_SHUTDOWN;

typedef struct {
    BD_ADDR bd_addr;
    tBT_UUID uuid;
    BOOLEAN get_sdp;
} tBSA_DG_FIND_SERVICE;

typedef struct {
    BD_ADDR bd_addr;
    UINT16 uuid16;
} tBSA_DG_GET_SDP;

#define BSA_OP_OBJECT_FMT 0u
#define BSA_OP_CUSTOM_FMT 1u
#define BSA_OP_SERVICE_NAME_LEN_MAX 150u
#define BSA_OP_OBJECT_NAME_LEN_MAX 255u

#define BSA_OP_VCARD21_FMT 1u
#define BSA_OP_VCARD30_FMT 2u
#define BSA_OP_VCAL_FMT 3u
#define BSA_OP_ICAL_FMT 4u
#define BSA_OP_VNOTE_FMT 5u
#define BSA_OP_VMSG_FMT 6u
#define BSA_OP_OTHER_FMT 0xffu

#define BSA_OP_VCARD21_MASK 0x01u
#define BSA_OP_VCARD30_MASK 0x02u
#define BSA_OP_VCAL_MASK 0x04u
#define BSA_OP_ICAL_MASK 0x08u
#define BSA_OP_VNOTE_MASK 0x10u
#define BSA_OP_VMSG_MASK 0x20u
#define BSA_OP_ANY_MASK 0x40u

#define BSA_OP_ACCESS_ALLOW 0u
#define BSA_OP_ACCESS_FORBID 1u
#define BSA_OP_ACCESS_NONSUP 2u
#define BSA_OP_ACCESS_EN_AUTO_OVERWRITE 3u
#define BSA_OP_ACCESS_EN_AUTO 4u
#define BSA_OP_ACCESS_DIS_AUTO 5u

#define BSA_OP_ACCESS_NO_RESP 0u
#define BSA_OP_ACCESS_NO_AUTO_ACCEPT 1u
#define BSA_OP_ACCESS_FS_ERROR 2u

#define BSA_OP_OPER_NONE 0u
#define BSA_OP_OPER_PUSH 1u
#define BSA_OP_OPER_PULL 2u

typedef UINT8 tBSA_OP_API_FMT;
typedef UINT8 tBSA_OP_FMT;
typedef UINT8 tBSA_OP_FMT_MASK;
typedef UINT8 tBSA_OP_ACCESS;
typedef UINT8 tBSA_OP_ACCESS_RESPONSE;
typedef UINT8 tBSA_OP_OPER;

#define BSA_OPS_OPEN_EVT 1u
#define BSA_OPS_PROGRESS_EVT 2u
#define BSA_OPS_OBJECT_EVT 3u
#define BSA_OPS_CLOSE_EVT 4u
#define BSA_OPS_ACCESS_EVT 5u

typedef UINT8 tBSA_OPS_EVT;

typedef struct {
    char p_name[BSA_OP_OBJECT_NAME_LEN_MAX];
    tBSA_OP_FMT format;
} tBSA_OPS_OBJECT_EVT;

typedef struct {
    UINT32 obj_size;
    UINT16 bytes;
    tBSA_OP_OPER operation;
} tBSA_OPS_PROGRESS_EVT;

typedef struct {
    char p_name[BSA_OP_OBJECT_NAME_LEN_MAX];
    /* The target forwards a server-process pointer without repairing it.
     * Preserve the field for ABI compatibility, but never dereference it. */
    char *p_type;
    UINT32 size;
    BD_NAME dev_name;
    BD_ADDR bd_addr;
    tBSA_OP_OPER oper;
    tBSA_OP_FMT format;
    tBSA_OP_ACCESS_RESPONSE responce_required;
} tBSA_OPS_ACCESS_EVT;

typedef union {
    tBSA_OPS_OBJECT_EVT object;
    tBSA_OPS_PROGRESS_EVT prog;
    tBSA_OPS_ACCESS_EVT access;
    BD_ADDR bd_addr;
} tBSA_OPS_MSG;

typedef void tBSA_OPS_CBACK(tBSA_OPS_EVT event, tBSA_OPS_MSG *p_data);

typedef struct {
    tBSA_SEC_AUTH sec_mask;
    tBSA_OP_API_FMT api_fmt;
    tBSA_OP_FMT_MASK formats;
    char service_name[BSA_OP_SERVICE_NAME_LEN_MAX];
    tBSA_OPS_CBACK *p_cback;
} tBSA_OPS_ENABLE;

typedef struct {
    tBSA_OP_OPER oper;
    tBSA_OP_ACCESS access;
    char object_name[BSA_OP_OBJECT_NAME_LEN_MAX];
} tBSA_OPS_ACCESS;

typedef struct {
    UINT8 dummy;
} tBSA_OPS_DISABLE;

typedef struct {
    UINT8 dummy;
} tBSA_OPS_CLOSE;

#define BSA_OPC_OPEN_EVT 1u
#define BSA_OPC_PROGRESS_EVT 2u
#define BSA_OPC_OBJECT_EVT 3u
#define BSA_OPC_OBJECT_PSHD_EVT 4u
#define BSA_OPC_CLOSE_EVT 5u

typedef UINT8 tBSA_OPC_EVT;

typedef struct {
    char name[BSA_OP_OBJECT_NAME_LEN_MAX];
    tBSA_STATUS status;
} tBSA_OPC_OBJECT_MSG;

typedef struct {
    UINT32 obj_size;
    UINT16 bytes;
    tBSA_OP_OPER operation;
} tBSA_OPC_PROGRESS_MSG;

typedef union {
    tBSA_OPC_OBJECT_MSG object;
    tBSA_OPC_PROGRESS_MSG prog;
    tBSA_STATUS status;
} tBSA_OPC_MSG;

typedef void tBSA_OPC_CBACK(tBSA_OPC_EVT event, tBSA_OPC_MSG *p_data);

typedef struct {
    tBSA_SEC_AUTH sec_mask;
    tBSA_OPC_CBACK *p_cback;
    BOOLEAN single_op;
} tBSA_OPC_ENABLE;

typedef struct {
    UINT8 dummy;
} tBSA_OPC_DISABLE;

typedef struct {
    UINT8 dummy;
} tBSA_OPC_CLOSE;

typedef struct {
    BD_ADDR bd_addr;
    tBSA_OP_FMT format;
    char send_path[BSA_OP_OBJECT_NAME_LEN_MAX];
} tBSA_OPC_PUSH;

typedef struct {
    BD_ADDR bd_addr;
    tBSA_OP_FMT format;
    char recv_path[BSA_OP_OBJECT_NAME_LEN_MAX];
} tBSA_OPC_PULL_CARD;

typedef struct {
    BD_ADDR bd_addr;
    tBSA_OP_FMT format;
    char send_path[BSA_OP_OBJECT_NAME_LEN_MAX];
    char recv_path[BSA_OP_OBJECT_NAME_LEN_MAX];
} tBSA_OPC_EXCH_CARD;

#define BSA_TM_VSE_EVT 0u
#define BSA_TM_VSE_SIZE_MAX 254u
#define BSA_TM_TASK_MAX 15u
#define BSA_TM_TASK_NAME_MAX 20u
#define BSA_TM_GKI_BUF_POOL_MAX 16u
#define BSA_TM_VSC_DATA_LEN_MAX 255u
#define BSA_TM_BSA_SERVER_VERSION_LEN 50u
#define BSA_TM_TRACE_LEN_MAX 256u

#define BSA_TM_SERVER 0u
#define BSA_TM_CLIENT 1u
#define BSA_TM_TRACE_LEVEL_NONE 0u
#define BSA_TM_TRACE_LEVEL_ERROR 1u
#define BSA_TM_TRACE_LEVEL_WARNING 2u
#define BSA_TM_TRACE_LEVEL_CUSTOM 3u
#define BSA_TM_TRACE_LEVEL_API 4u
#define BSA_TM_TRACE_LEVEL_EVENT 5u
#define BSA_TM_TRACE_LEVEL_DEBUG 6u

typedef UINT8 tBSA_TM_EVT;

typedef struct {
    UINT8 length;
    UINT8 sub_event;
    UINT8 data[BSA_TM_VSE_SIZE_MAX];
} tBSA_TM_VSE_MSG;

typedef union {
    tBSA_TM_VSE_MSG vse;
} tBSA_TM_MSG;

typedef void tBSA_TM_CBACK(tBSA_TM_EVT event, tBSA_TM_MSG *p_data);

typedef struct {
    BOOLEAN task_used;
    UINT8 task_id;
    UINT8 task_state;
    UINT16 stack_size;
    UINT16 stack_used;
    INT8 task_name[BSA_TM_TASK_NAME_MAX];
} tBSA_TM_TASK_STATUS;

typedef struct {
    BOOLEAN pool_used;
    UINT16 pool_size;
    UINT16 pool_number;
    UINT16 pool_cur_use;
    UINT16 pool_max_use;
    UINT8 pool_id;
} tBSA_TM_GKI_BUF_STATUS;

typedef struct {
    UINT8 dummy;
} tBSA_TM_DUMMY;

typedef struct {
    BOOLEAN test_mode_enable;
} tBSA_TM_SET_TEST_MODE;

typedef struct {
    UINT8 location;
    BOOLEAN task_missing;
    BOOLEAN gki_buf_missing;
    tBSA_TM_TASK_STATUS task[BSA_TM_TASK_MAX];
    tBSA_TM_GKI_BUF_STATUS gki_buffer[BSA_TM_GKI_BUF_POOL_MAX];
} tBSA_TM_GET_MEM_USAGE;

typedef tBSA_TM_DUMMY tBSA_TM_PING;

typedef struct {
    UINT16 opcode;
    UINT16 length;
    tBSA_STATUS status;
    UINT8 data[BSA_TM_VSC_DATA_LEN_MAX];
} tBSA_TM_VSC;

typedef struct {
    UINT16 opcode;
    UINT16 length;
    tBSA_STATUS status;
    BOOLEAN no_opcode_swap;
    UINT8 data[BSA_TM_VSC_DATA_LEN_MAX];
} tBSA_TM_HCI_CMD;

typedef struct {
    UINT8 all_trace_level;
    UINT8 trace_text[BSA_TM_TRACE_LEN_MAX];
} tBSA_TM_SET_TRACE_LEVEL;

typedef struct {
    UINT8 major;
    UINT8 minor;
    UINT8 build;
    UINT16 config;
} tBSA_TM_FW_VERSION;

typedef struct {
    tBSA_STATUS status;
    tBSA_TM_FW_VERSION fw_version;
    UINT8 bsa_server_version[BSA_TM_BSA_SERVER_VERSION_LEN];
} tBSA_TM_READ_VERSION;

typedef struct {
    tBSA_STATUS status;
    BD_ADDR bd_addr;
} tBSA_TM_READ_CFG_BD_ADDR;

#define BTA_TM_VSE_SUB_EVENT_ALL 0xffffu

typedef struct {
    UINT16 sub_event;
    tBSA_TM_CBACK *p_callback;
} tBSA_TM_VSE_REGISTER;

typedef struct {
    UINT16 sub_event;
    BOOLEAN deregister_callback;
} tBSA_TM_VSE_DEREGISTER;

typedef struct {
    BD_ADDR bd_addr;
} tBSA_TM_DISCONNECT;

typedef struct {
    BD_ADDR bd_addr;
    UINT8 flag;
    UINT8 service_type;
    UINT32 token_rate;
    UINT32 peak_bandwidth;
    UINT32 latency;
    UINT32 delay_variation;
} tBSA_TM_QOS;

typedef struct {
    tBSA_STATUS status;
    BD_ADDR bd_addr;
    tBT_TRANSPORT transport;
    UINT16 handle;
} tBSA_TM_GET_HANDLE;

typedef struct {
    BD_ADDR bd_addr;
    INT8 rssi;
    UINT16 conn_handle;
} tBSA_TM_READRAWRSSI;

#define BSA_LE_TX_TEST_CMD 0u
#define BSA_LE_RX_TEST_CMD 1u
#define BSA_LE_END_TEST_CMD 2u

typedef struct {
    UINT8 test;
    UINT8 freq;
    UINT8 pattern;
    UINT8 payload_len;
    UINT16 retcount;
} tBSA_TM_LE_CMD;

typedef struct {
    UINT8 status;
    UINT16 count;
} tBSA_TM_LE_CMD_RESULT;

typedef struct {
    UINT8 channel_enable;
    UINT16 rx_center_frequency;
    UINT16 tx_center_frequency;
    UINT16 rx_channel_bandwidth;
    UINT16 tx_channel_bandwidth;
    UINT8 channel_type;
} tHCI_SET_MWS_CHAN_CMD_PARAMS;

typedef tHCI_SET_MWS_CHAN_CMD_PARAMS tBTM_SET_MWS_CHAN_OP_PARAMS;
typedef tBTM_SET_MWS_CHAN_OP_PARAMS tBSA_DM_SET_MWS_CHAN;

typedef struct {
    UINT16 ext_frame_duration;
    UINT16 ext_frame_sync_assert_offset;
    UINT16 ext_frame_sync_assert_jitter;
    UINT8 ext_num_periods;
    UINT16 duration_array[32];
    UINT8 type_array[32];
} tHCI_SET_EXT_FRAME_CFG_CMD_PARAMS;

typedef tHCI_SET_EXT_FRAME_CFG_CMD_PARAMS tBTM_SET_EXT_FRAME_CFG_OP_PARAMS;
typedef tBTM_SET_EXT_FRAME_CFG_OP_PARAMS tBSA_DM_SET_EXT_FRAME_CFG;

typedef union {
    tBSA_DM_SET_MWS_CHAN set_mws_chan;
    tBSA_DM_SET_EXT_FRAME_CFG set_ext_frame_cfg;
} tBSA_DM_COEX_OP_PARAMS;

#define BSA_DM_COEX_SET_MWS_CHAN 0u
#define BSA_DM_COEX_SET_EXT_FRAME_CFG 1u
typedef UINT8 tBSA_DM_COEX_OP;

typedef struct {
    tBSA_DM_COEX_OP coex_op;
    tBSA_DM_COEX_OP_PARAMS coex_op_params;
} tBSA_TM_COEXOP;

#define BSA_TM_DIAG_RESET_STATS_CMD 0u
#define BSA_TM_DIAG_BASIC_ACL_CMD 1u
#define BSA_TM_DIAG_EDR_ACL_CMD 2u
#define BSA_TM_DIAG_AUX_BER_CMD 3u
#define BSA_TM_DIAG_MAX_CMD 4u

typedef UINT8 tBSA_TM_DIAG_CMD;

typedef struct {
    UINT16 null_packets_rcvd;
    UINT16 poll_packets_rcvd;
    UINT16 dm1_packets_rcvd;
    UINT16 dh1_packets_rcvd;
    UINT16 dv_packets_rcvd;
    UINT16 aux1_packets_rcvd;
    UINT16 dm3_packets_rcvd;
    UINT16 dh3_packets_rcvd;
    UINT16 dm5_packets_rcvd;
    UINT16 dh5_packets_rcvd;
    UINT16 null_packets_tx;
    UINT16 poll_packets_tx;
    UINT16 dm1_packets_tx;
    UINT16 dh1_packets_tx;
    UINT16 dv_packets_tx;
    UINT16 aux1_packets_tx;
    UINT16 dm3_packets_tx;
    UINT16 dh3_packets_tx;
    UINT16 dm5_packets_tx;
    UINT16 dh5_packets_tx;
    UINT32 total_rx_acl_bytes;
    UINT32 total_tx_acl_bytes;
    UINT16 hec_errors;
    UINT16 crc_errors;
    UINT16 seqn_repeat;
    UINT16 soft_reset;
    UINT16 testmode_tx_pkts;
    UINT16 testmode_rx_pkts;
    UINT16 testmode_pkt_errors;
} tBSA_TM_BASIC_ACL_STATS_DATA;

typedef struct {
    UINT16 null_packets_rcvd;
    UINT16 poll_packets_rcvd;
    UINT16 dm1_packets_rcvd;
    UINT16 _2_dh1_packets_rcvd;
    UINT16 _3_dh1_packets_rcvd;
    UINT16 _2_dh3_packets_rcvd;
    UINT16 _3_dh3_packets_rcvd;
    UINT16 _2_dh5_packets_rcvd;
    UINT16 _3_dh5_packets_rcvd;
    UINT16 null_packets_tx;
    UINT16 poll_packets_tx;
    UINT16 dm1_packets_tx;
    UINT16 _2_dh1_packets_tx;
    UINT16 _3_dh1_packets_tx;
    UINT16 _2_dh3_packets_tx;
    UINT16 _3_dh3_packets_tx;
    UINT16 _2_dh5_packets_tx;
    UINT16 _3_dh5_packets_tx;
    UINT32 total_rx_acl_bytes;
    UINT32 total_tx_acl_bytes;
    UINT16 hec_errors;
    UINT16 crc_errors;
    UINT16 seqn_repeat;
    UINT16 soft_reset;
    UINT16 testmode_tx_pkts;
    UINT16 testmode_rx_pkts;
    UINT16 testmode_pkt_errors;
} tBSA_TM_EDR_ACL_STATS_DATA;

typedef struct {
    UINT32 packets_sent;
    UINT32 packets_received;
    UINT32 hec_errors;
    UINT32 sync_errors;
    UINT32 packet_errors;
    UINT32 bits_received;
    UINT32 bit_errors;
} tBSA_TM_AUX_BER_STATS_DATA;

typedef union {
    tBSA_TM_BASIC_ACL_STATS_DATA basic_acl;
    tBSA_TM_EDR_ACL_STATS_DATA edr_acl;
    tBSA_TM_AUX_BER_STATS_DATA aux_ber;
} tBSA_TM_DIAG_DATA;

typedef struct {
    UINT8 diag_cmd;
    tBSA_TM_DIAG_DATA diag;
} tBSA_TM_DIAG_STATS;

tBSA_STATUS BSA_MgtOpenInit(tBSA_MGT_OPEN *p_mgt_open);
tBSA_STATUS BSA_MgtOpen(tBSA_MGT_OPEN *p_mgt_open);
tBSA_STATUS BSA_MgtKillServerInit(tBSA_MGT_KILL_SERVER *p_mgt_kill_server);
tBSA_STATUS BSA_MgtKillServer(tBSA_MGT_KILL_SERVER *p_mgt_kill_server);
tBSA_STATUS BSA_MgtCloseInit(tBSA_MGT_CLOSE *p_mgt_close);
tBSA_STATUS BSA_MgtClose(tBSA_MGT_CLOSE *p_mgt_close);

tBSA_STATUS BSA_DmGetConfigInit(tBSA_DM_GET_CONFIG *p_config);
tBSA_STATUS BSA_DmGetConfig(tBSA_DM_GET_CONFIG *p_config);
tBSA_STATUS BSA_DmSetConfigInit(tBSA_DM_SET_CONFIG *p_config);
tBSA_STATUS BSA_DmSetConfig(tBSA_DM_SET_CONFIG *p_config);
tBSA_STATUS BSA_DmSetLocalDiRecord(tBSA_DM_SET_DI *p_di_record);
tBSA_STATUS BSA_DmGetLocalDiRecord(tBSA_DM_GET_DI *p_di_record);

tBSA_STATUS BSA_DiscStartInit(tBSA_DISC_START *p_disc_start);
tBSA_STATUS BSA_DiscStart(tBSA_DISC_START *p_disc_start);
tBSA_STATUS BSA_DiscAbortInit(tBSA_DISC_ABORT *p_disc_abort);
tBSA_STATUS BSA_DiscAbort(tBSA_DISC_ABORT *p_disc_abort);
tBSA_STATUS BSA_ReadRemoteNameInit(tBSA_DISC_READ_REMOTE_NAME *p_read_remote_name);
tBSA_STATUS BSA_ReadRemoteName(tBSA_DISC_READ_REMOTE_NAME *p_read_remote_name);

tBSA_STATUS BSA_SecSetSecurityInit(tBSA_SEC_SET_SECURITY *p_set_sec);
tBSA_STATUS BSA_SecSetSecurity(tBSA_SEC_SET_SECURITY *p_set_sec);
tBSA_STATUS BSA_SecPinCodeReplyInit(tBSA_SEC_PIN_CODE_REPLY *p_pin_code_reply);
tBSA_STATUS BSA_SecPinCodeReply(tBSA_SEC_PIN_CODE_REPLY *p_pin_code_reply);
tBSA_STATUS BSA_SecSpPasskeyReply(tBSA_SEC_PASSKEY_REPLY *p_passkey_reply);
tBSA_STATUS BSA_SecSpCfmReplyInit(tBSA_SEC_SP_CFM_REPLY *p_sp_cfm_reply);
tBSA_STATUS BSA_SecSpCfmReply(tBSA_SEC_SP_CFM_REPLY *p_sp_cfm_reply);
tBSA_STATUS BSA_SecBondInit(tBSA_SEC_BOND *p_sec_bond);
tBSA_STATUS BSA_SecBond(tBSA_SEC_BOND *p_sec_bond);
tBSA_STATUS BSA_SecBondCancelInit(tBSA_SEC_BOND_CANCEL *p_sec_bond_cancel);
tBSA_STATUS BSA_SecBondCancel(tBSA_SEC_BOND_CANCEL *p_sec_bond_cancel);
tBSA_STATUS BSA_SecAuthorizeReplyInit(tBSA_SEC_AUTH_REPLY *p_sec_auth_reply);
tBSA_STATUS BSA_SecAuthorizeReply(tBSA_SEC_AUTH_REPLY *p_sec_auth_reply);
tBSA_STATUS BSA_SecRemoveDeviceInit(tBSA_SEC_REMOVE_DEV *p_sec_del_dev);
tBSA_STATUS BSA_SecRemoveDevice(tBSA_SEC_REMOVE_DEV *p_sec_del_dev);
tBSA_STATUS BSA_SecAddDeviceInit(tBSA_SEC_ADD_DEV *p_sec_add_dev);
tBSA_STATUS BSA_SecAddDevice(tBSA_SEC_ADD_DEV *p_sec_add_dev);
tBSA_STATUS BSA_SecReadOOBInit(tBSA_SEC_READ_OOB *p_sec_read_oob);
tBSA_STATUS BSA_SecReadOOB(tBSA_SEC_READ_OOB *p_sec_read_oob);
tBSA_STATUS BSA_SecSetRemoteOOBInit(tBSA_SEC_SET_REMOTE_OOB *p_sec_set_remote_oob);
tBSA_STATUS BSA_SecSetRemoteOOB(tBSA_SEC_SET_REMOTE_OOB *p_sec_set_remote_oob);
tBSA_STATUS BSA_SecAddSiDevInit(tBSA_SEC_ADD_SI_DEV *p_sec_add_si_dev);
tBSA_STATUS BSA_SecAddSiDev(tBSA_SEC_ADD_SI_DEV *p_sec_add_si_dev);
tBSA_STATUS BSA_SecCloseACLInit(tBSA_SEC_CLOSE_ACL *p_sec_close_acl);
tBSA_STATUS BSA_SecCloseACL(tBSA_SEC_CLOSE_ACL *p_sec_close_acl);

tBSA_STATUS BSA_DgEnableInit(tBSA_DG_ENABLE *p_req);
tBSA_STATUS BSA_DgEnable(tBSA_DG_ENABLE *p_req);
tBSA_STATUS BSA_DgDisableInit(tBSA_DG_DISABLE *p_req);
tBSA_STATUS BSA_DgDisable(tBSA_DG_DISABLE *p_req);
tBSA_STATUS BSA_DgListenInit(tBSA_DG_LISTEN *p_req);
tBSA_STATUS BSA_DgListen(tBSA_DG_LISTEN *p_req);
tBSA_STATUS BSA_DgOpenInit(tBSA_DG_OPEN *p_req);
tBSA_STATUS BSA_DgOpen(tBSA_DG_OPEN *p_req);
tBSA_STATUS BSA_DgCloseInit(tBSA_DG_CLOSE *p_req);
tBSA_STATUS BSA_DgClose(tBSA_DG_CLOSE *p_req);
tBSA_STATUS BSA_DgShutdownInit(tBSA_DG_SHUTDOWN *p_req);
tBSA_STATUS BSA_DgShutdown(tBSA_DG_SHUTDOWN *p_req);
tBSA_STATUS BSA_DgFindServiceInit(tBSA_DG_FIND_SERVICE *p_req);
tBSA_STATUS BSA_DgFindService(tBSA_DG_FIND_SERVICE *p_req);
tBSA_STATUS BSA_DgGetSdpInit(tBSA_DG_GET_SDP *p_req);
tBSA_STATUS BSA_DgGetSdp(tBSA_DG_GET_SDP *p_req);

tBSA_STATUS BSA_OpsEnableInit(tBSA_OPS_ENABLE *p_req);
tBSA_STATUS BSA_OpsEnable(tBSA_OPS_ENABLE *p_req);
tBSA_STATUS BSA_OpsDisableInit(tBSA_OPS_DISABLE *p_req);
tBSA_STATUS BSA_OpsDisable(tBSA_OPS_DISABLE *p_req);
tBSA_STATUS BSA_OpsCloseInit(tBSA_OPS_CLOSE *p_req);
tBSA_STATUS BSA_OpsClose(tBSA_OPS_CLOSE *p_req);
tBSA_STATUS BSA_OpsAccessInit(tBSA_OPS_ACCESS *p_req);
tBSA_STATUS BSA_OpsAccess(tBSA_OPS_ACCESS *p_req);

tBSA_STATUS BSA_OpcEnableInit(tBSA_OPC_ENABLE *p_req);
tBSA_STATUS BSA_OpcEnable(tBSA_OPC_ENABLE *p_req);
tBSA_STATUS BSA_OpcDisableInit(tBSA_OPC_DISABLE *p_req);
tBSA_STATUS BSA_OpcDisable(tBSA_OPC_DISABLE *p_req);
tBSA_STATUS BSA_OpcPushInit(tBSA_OPC_PUSH *p_req);
tBSA_STATUS BSA_OpcPush(tBSA_OPC_PUSH *p_req);
tBSA_STATUS BSA_OpcPullCardInit(tBSA_OPC_PULL_CARD *p_req);
tBSA_STATUS BSA_OpcPullCard(tBSA_OPC_PULL_CARD *p_req);
tBSA_STATUS BSA_OpcExchCardInit(tBSA_OPC_EXCH_CARD *p_req);
tBSA_STATUS BSA_OpcExchCard(tBSA_OPC_EXCH_CARD *p_req);
tBSA_STATUS BSA_OpcCloseInit(tBSA_OPC_CLOSE *p_req);
tBSA_STATUS BSA_OpcClose(tBSA_OPC_CLOSE *p_req);

tBSA_STATUS BSA_TmSetTestModeInit(tBSA_TM_SET_TEST_MODE *p_req);
tBSA_STATUS BSA_TmSetTestMode(tBSA_TM_SET_TEST_MODE *p_req);
tBSA_STATUS BSA_TmGetMemUsageInit(tBSA_TM_GET_MEM_USAGE *p_req);
tBSA_STATUS BSA_TmGetMemUsage(tBSA_TM_GET_MEM_USAGE *p_req);
tBSA_STATUS BSA_TmVscInit(tBSA_TM_VSC *p_req);
tBSA_STATUS BSA_TmVsc(tBSA_TM_VSC *p_req);
tBSA_STATUS BSA_TmHciInit(tBSA_TM_HCI_CMD *p_req);
tBSA_STATUS BSA_TmHciCmd(tBSA_TM_HCI_CMD *p_req);
tBSA_STATUS BSA_TmPingInit(tBSA_TM_PING *p_req);
tBSA_STATUS BSA_TmPing(tBSA_TM_PING *p_req);
tBSA_STATUS BSA_TmSetTraceLevelInit(tBSA_TM_SET_TRACE_LEVEL *p_req);
tBSA_STATUS BSA_TmSetTraceLevel(tBSA_TM_SET_TRACE_LEVEL *p_req);
tBSA_STATUS BSA_TmSetTraceTextInit(tBSA_TM_SET_TRACE_LEVEL *p_req);
tBSA_STATUS BSA_TmSetTraceText(tBSA_TM_SET_TRACE_LEVEL *p_req);
tBSA_STATUS BSA_TmReadVersionInit(tBSA_TM_READ_VERSION *p_req);
tBSA_STATUS BSA_TmReadVersion(tBSA_TM_READ_VERSION *p_req);
tBSA_STATUS BSA_TmVseRegisterInit(tBSA_TM_VSE_REGISTER *p_req);
tBSA_STATUS BSA_TmVseRegister(tBSA_TM_VSE_REGISTER *p_req);
tBSA_STATUS BSA_TmVseDeregisterInit(tBSA_TM_VSE_DEREGISTER *p_req);
tBSA_STATUS BSA_TmVseDeregister(tBSA_TM_VSE_DEREGISTER *p_req);
tBSA_STATUS BSA_TmDisconnectInit(tBSA_TM_DISCONNECT *p_req);
tBSA_STATUS BSA_TmDisconnect(tBSA_TM_DISCONNECT *p_req);
tBSA_STATUS BSA_TmQosInit(tBSA_TM_QOS *p_req);
tBSA_STATUS BSA_TmQos(tBSA_TM_QOS *p_req);
tBSA_STATUS BSA_TmGetHandleInit(tBSA_TM_GET_HANDLE *p_req);
tBSA_STATUS BSA_TmGetHandle(tBSA_TM_GET_HANDLE *p_req);
tBSA_STATUS BSA_TmReadRawRssiInit(tBSA_TM_READRAWRSSI *p_req);
tBSA_STATUS BSA_TmReadRawRssi(tBSA_TM_READRAWRSSI *p_req);
tBSA_STATUS BSA_TmDiagStatsInit(tBSA_TM_DIAG_STATS *p_req);
tBSA_STATUS BSA_TmDiagStats(tBSA_TM_DIAG_STATS *p_req);
tBSA_STATUS BSA_TmLeTestCmd(tBSA_TM_LE_CMD *p_cmd);
tBSA_STATUS BSA_TmCoexOpInit(tBSA_TM_COEXOP *p_req);
tBSA_STATUS BSA_TmCoexOp(tBSA_TM_COEXOP *p_req);

#if UINTPTR_MAX == UINT32_MAX
#if defined(__cplusplus)
#define BSA_ABI_ASSERT(c, m) static_assert((c), m)
#else
#define BSA_ABI_ASSERT(c, m) _Static_assert((c), m)
#endif
#define BSA_ABI_ASSERT_SIZE(t, n) BSA_ABI_ASSERT(sizeof(t) == (n), #t " size")
#define BSA_ABI_ASSERT_OFF(t, f, n) BSA_ABI_ASSERT(offsetof(t, f) == (n), #t "." #f " offset")

BSA_ABI_ASSERT_SIZE(void *, 4);
BSA_ABI_ASSERT_SIZE(int, 4);
BSA_ABI_ASSERT_SIZE(tBSA_STATUS, 2);
BSA_ABI_ASSERT_SIZE(tBT_UUID, 20);
BSA_ABI_ASSERT_OFF(tBT_UUID, uu, 4);
BSA_ABI_ASSERT_SIZE(tBSA_MGT_EVT, 4);
BSA_ABI_ASSERT_SIZE(tBSA_DM_EVT, 4);
BSA_ABI_ASSERT_SIZE(tBSA_DISC_EVT, 4);
BSA_ABI_ASSERT_SIZE(tBSA_SEC_EVT, 4);

BSA_ABI_ASSERT_SIZE(tBSA_MSG_DISCONNECT_MSG, 2);
BSA_ABI_ASSERT_SIZE(tBSA_MSG_STATUS_MSG, 1);
BSA_ABI_ASSERT_SIZE(tBSA_MGT_MSG, 2);
BSA_ABI_ASSERT_SIZE(tBSA_MGT_OPEN, 104);
BSA_ABI_ASSERT_OFF(tBSA_MGT_OPEN, callback, 100);
BSA_ABI_ASSERT_SIZE(tBSA_MGT_KILL_SERVER, 4);
BSA_ABI_ASSERT_SIZE(tBSA_MGT_CLOSE, 4);

BSA_ABI_ASSERT_SIZE(tBSA_DM_3D_ANNOUCEMENT_MSG, 12);
BSA_ABI_ASSERT_OFF(tBSA_DM_3D_ANNOUCEMENT_MSG, additional_data, 10);
BSA_ABI_ASSERT_SIZE(tBSA_DM_MSG, 12);
BSA_ABI_ASSERT_SIZE(tBSA_DM_3D_TX_DATA, 12);
BSA_ABI_ASSERT_SIZE(tBSA_DM_BLE_CONN_PARAM, 16);
BSA_ABI_ASSERT_SIZE(tBSA_DM_BLE_CONN_SCAN_PARAM, 4);
BSA_ABI_ASSERT_SIZE(tBSA_DM_BLE_INT_RANGE, 4);
BSA_ABI_ASSERT_SIZE(tBSA_DM_BLE_PROP_ELEM, 34);
BSA_ABI_ASSERT_SIZE(tBSA_DM_BLE_PROPRIETARY, 103);
BSA_ABI_ASSERT_SIZE(tBSA_DM_BLE_128SERVICE, 17);
BSA_ABI_ASSERT_SIZE(tBSA_DM_BLE_ADV_CONFIG, 256);
BSA_ABI_ASSERT_OFF(tBSA_DM_BLE_ADV_CONFIG, adv_data_mask, 36);
BSA_ABI_ASSERT_OFF(tBSA_DM_BLE_ADV_CONFIG, service_data_uuid, 60);
BSA_ABI_ASSERT_OFF(tBSA_DM_BLE_ADV_CONFIG, proprietary, 152);
BSA_ABI_ASSERT_SIZE(tBSA_DM_BLE_BD_ADDR, 7);
BSA_ABI_ASSERT_SIZE(tBSA_DM_BLE_ADV_PARAM, 16);
BSA_ABI_ASSERT_SIZE(tBSA_DM_LINK_POLICY_PARAM, 10);
BSA_ABI_ASSERT_SIZE(tBSA_DM_MONITOR_RSSI_PARAM, 4);
BSA_ABI_ASSERT_OFF(tBSA_DM_MONITOR_RSSI_PARAM, period, 2);
BSA_ABI_ASSERT_SIZE(tBSA_DM_ADD_REMOVE_EIR_PARAM, 24);
BSA_ABI_ASSERT_OFF(tBSA_DM_ADD_REMOVE_EIR_PARAM, UUID, 4);
BSA_ABI_ASSERT_SIZE(tBSA_DM_GET_CONFIG, 848);
BSA_ABI_ASSERT_OFF(tBSA_DM_GET_CONFIG, callback, 284);
BSA_ABI_ASSERT_OFF(tBSA_DM_GET_CONFIG, eir_data, 293);
BSA_ABI_ASSERT_OFF(tBSA_DM_GET_CONFIG, ble_scan_mode, 512);
BSA_ABI_ASSERT_OFF(tBSA_DM_GET_CONFIG, adv_config, 536);
BSA_ABI_ASSERT_OFF(tBSA_DM_GET_CONFIG, ble_adv_param, 792);
BSA_ABI_ASSERT_OFF(tBSA_DM_GET_CONFIG, policy_param, 810);
BSA_ABI_ASSERT_OFF(tBSA_DM_GET_CONFIG, add_remove_eir_param, 824);
BSA_ABI_ASSERT_SIZE(tBSA_DM_GET_DI, 10);
BSA_ABI_ASSERT_OFF(tBSA_DM_GET_DI, vendor_id_source, 8);

BSA_ABI_ASSERT_SIZE(tBSA_DISC_VID_PID, 10);
BSA_ABI_ASSERT_OFF(tBSA_DISC_VID_PID, vendor_id_source, 2);
BSA_ABI_ASSERT_SIZE(tBSA_DISC_REMOTE_DEV, 524);
BSA_ABI_ASSERT_OFF(tBSA_DISC_REMOTE_DEV, rssi, 260);
BSA_ABI_ASSERT_OFF(tBSA_DISC_REMOTE_DEV, services, 264);
BSA_ABI_ASSERT_OFF(tBSA_DISC_REMOTE_DEV, eir_vid_pid, 268);
BSA_ABI_ASSERT_OFF(tBSA_DISC_REMOTE_DEV, eir_data, 278);
BSA_ABI_ASSERT_OFF(tBSA_DISC_REMOTE_DEV, inq_result_type, 518);
BSA_ABI_ASSERT_SIZE(tBSA_DISC_DEV, 528);
BSA_ABI_ASSERT_OFF(tBSA_DISC_DEV, device, 4);
BSA_ABI_ASSERT_SIZE(tBSA_DISC_DEV_INFO_MSG, 22);
BSA_ABI_ASSERT_OFF(tBSA_DISC_DEV_INFO_MSG, spec_id, 10);
BSA_ABI_ASSERT_SIZE(tBSA_DISC_READ_REMOTE_NAME_MSG, 260);
BSA_ABI_ASSERT_OFF(tBSA_DISC_READ_REMOTE_NAME_MSG, remote_bd_name, 10);
BSA_ABI_ASSERT_SIZE(tBSA_DISC_MSG, 524);
BSA_ABI_ASSERT_SIZE(tBSA_DISC_START, 32);
BSA_ABI_ASSERT_OFF(tBSA_DISC_START, cback, 4);
BSA_ABI_ASSERT_OFF(tBSA_DISC_START, filter_cond, 13);
BSA_ABI_ASSERT_OFF(tBSA_DISC_START, services, 20);
BSA_ABI_ASSERT_OFF(tBSA_DISC_START, mode, 24);
BSA_ABI_ASSERT_SIZE(tBSA_DISC_ABORT, 4);
BSA_ABI_ASSERT_SIZE(tBSA_DISC_DEV_INFO, 12);
BSA_ABI_ASSERT_OFF(tBSA_DISC_DEV_INFO, cback, 8);
BSA_ABI_ASSERT_SIZE(tBSA_DISC_READ_REMOTE_NAME, 16);
BSA_ABI_ASSERT_OFF(tBSA_DISC_READ_REMOTE_NAME, cback, 8);
BSA_ABI_ASSERT_OFF(tBSA_DISC_READ_REMOTE_NAME, transport, 12);

BSA_ABI_ASSERT_SIZE(tBSA_SEC_PIN_REQ, 258);
BSA_ABI_ASSERT_SIZE(tBSA_SEC_AUTH_CMPL, 275);
BSA_ABI_ASSERT_OFF(tBSA_SEC_AUTH_CMPL, key, 256);
BSA_ABI_ASSERT_SIZE(tBSA_SEC_BOND_CANCEL_CMPL, 2);
BSA_ABI_ASSERT_SIZE(tBSA_SEC_AUTHORIZE, 256);
BSA_ABI_ASSERT_SIZE(tBSA_SEC_LINK_UP, 10);
BSA_ABI_ASSERT_SIZE(tBSA_SEC_LINK_DOWN, 8);
BSA_ABI_ASSERT_SIZE(tBSA_SEC_SP_CFM_REQ, 268);
BSA_ABI_ASSERT_OFF(tBSA_SEC_SP_CFM_REQ, num_val, 260);
BSA_ABI_ASSERT_SIZE(tBSA_SEC_SP_KEY_PRESS, 7);
BSA_ABI_ASSERT_SIZE(tBSA_SEC_SP_KEY_NOTIF, 268);
BSA_ABI_ASSERT_OFF(tBSA_SEC_SP_KEY_NOTIF, passkey, 260);
BSA_ABI_ASSERT_SIZE(tBSA_SEC_LOCAL_OOB_MSG, 33);
BSA_ABI_ASSERT_SIZE(tBSA_SEC_SP_RMT_OOB, 258);
BSA_ABI_ASSERT_SIZE(tBSA_LE_PENC_KEYS, 28);
BSA_ABI_ASSERT_SIZE(tBSA_LE_PCSRK_KEYS, 24);
BSA_ABI_ASSERT_SIZE(tBSA_LE_LCSRK_KEYS, 24);
BSA_ABI_ASSERT_SIZE(tBSA_LE_PID_KEYS, 23);
BSA_ABI_ASSERT_SIZE(tBSA_LE_LENC_KEYS, 20);
BSA_ABI_ASSERT_SIZE(tBSA_LE_KEY_VALUE, 28);
BSA_ABI_ASSERT_SIZE(tBSA_SEC_BLE_KEY, 36);
BSA_ABI_ASSERT_OFF(tBSA_SEC_BLE_KEY, key_value, 8);
BSA_ABI_ASSERT_SIZE(tBSA_SEC_SIG_STRENGTH, 11);
BSA_ABI_ASSERT_SIZE(tBSA_SEC_MSG, 276);
BSA_ABI_ASSERT_SIZE(tBSA_SEC_SET_SECURITY, 12);
BSA_ABI_ASSERT_OFF(tBSA_SEC_SET_SECURITY, sec_cback, 4);
BSA_ABI_ASSERT_OFF(tBSA_SEC_SET_SECURITY, ssp_debug, 8);
BSA_ABI_ASSERT_SIZE(tBSA_SEC_PIN_CODE_REPLY, 32);
BSA_ABI_ASSERT_OFF(tBSA_SEC_PIN_CODE_REPLY, ble_passkey, 24);
BSA_ABI_ASSERT_SIZE(tBSA_SEC_PASSKEY_REPLY, 12);
BSA_ABI_ASSERT_OFF(tBSA_SEC_PASSKEY_REPLY, passkey, 8);
BSA_ABI_ASSERT_SIZE(tBSA_SEC_SP_CFM_REPLY, 8);
BSA_ABI_ASSERT_SIZE(tBSA_SEC_BOND, 6);
BSA_ABI_ASSERT_SIZE(tBSA_SEC_BOND_CANCEL, 6);
BSA_ABI_ASSERT_SIZE(tBSA_SEC_AUTH_REPLY, 8);
BSA_ABI_ASSERT_SIZE(tBSA_SEC_REMOVE_DEV, 6);
BSA_ABI_ASSERT_SIZE(tBSA_SEC_ADD_DEV, 416);
BSA_ABI_ASSERT_OFF(tBSA_SEC_ADD_DEV, trusted_services, 28);
BSA_ABI_ASSERT_OFF(tBSA_SEC_ADD_DEV, bd_name, 35);
BSA_ABI_ASSERT_OFF(tBSA_SEC_ADD_DEV, le_penc_key, 296);
BSA_ABI_ASSERT_OFF(tBSA_SEC_ADD_DEV, le_lenc_key, 396);
BSA_ABI_ASSERT_SIZE(tBSA_SEC_SET_REMOTE_OOB, 39);
BSA_ABI_ASSERT_SIZE(tBSA_SEC_READ_OOB, 4);
BSA_ABI_ASSERT_SIZE(tBSA_SEC_ADD_SI_DEV, 7);
BSA_ABI_ASSERT_SIZE(tBSA_SEC_CLOSE_ACL, 6);

BSA_ABI_ASSERT_SIZE(tBSA_DG_OPEN_MSG, 14);
BSA_ABI_ASSERT_OFF(tBSA_DG_OPEN_MSG, handle, 10);
BSA_ABI_ASSERT_SIZE(tBSA_DG_CLOSE_MSG, 2);
BSA_ABI_ASSERT_SIZE(tBSA_DG_FIND_SERVICE_MSG, 68);
BSA_ABI_ASSERT_SIZE(tBSA_DG_SDP_DISC_ATTR, 204);
BSA_ABI_ASSERT_SIZE(tBSA_DG_GET_SDP_MSG, 252);
BSA_ABI_ASSERT_OFF(tBSA_DG_GET_SDP_MSG, attrs, 48);
BSA_ABI_ASSERT_SIZE(tBSA_DG_MSG, 252);
BSA_ABI_ASSERT_SIZE(tBSA_DG_ENABLE, 4);
BSA_ABI_ASSERT_SIZE(tBSA_DG_DISABLE, 1);
BSA_ABI_ASSERT_SIZE(tBSA_DG_LISTEN, 60);
BSA_ABI_ASSERT_OFF(tBSA_DG_LISTEN, handle, 38);
BSA_ABI_ASSERT_OFF(tBSA_DG_LISTEN, uuid, 40);
BSA_ABI_ASSERT_SIZE(tBSA_DG_OPEN, 64);
BSA_ABI_ASSERT_OFF(tBSA_DG_OPEN, uuid, 44);
BSA_ABI_ASSERT_SIZE(tBSA_DG_CLOSE, 2);
BSA_ABI_ASSERT_SIZE(tBSA_DG_SHUTDOWN, 2);
BSA_ABI_ASSERT_SIZE(tBSA_DG_FIND_SERVICE, 32);
BSA_ABI_ASSERT_OFF(tBSA_DG_FIND_SERVICE, uuid, 8);
BSA_ABI_ASSERT_SIZE(tBSA_DG_GET_SDP, 8);

BSA_ABI_ASSERT_SIZE(tBSA_OPS_OBJECT_EVT, 256);
BSA_ABI_ASSERT_SIZE(tBSA_OPS_PROGRESS_EVT, 8);
BSA_ABI_ASSERT_SIZE(tBSA_OPS_ACCESS_EVT, 524);
BSA_ABI_ASSERT_OFF(tBSA_OPS_ACCESS_EVT, p_type, 256);
BSA_ABI_ASSERT_OFF(tBSA_OPS_ACCESS_EVT, bd_addr, 513);
BSA_ABI_ASSERT_SIZE(tBSA_OPS_MSG, 524);
BSA_ABI_ASSERT_SIZE(tBSA_OPS_ENABLE, 160);
BSA_ABI_ASSERT_OFF(tBSA_OPS_ENABLE, p_cback, 156);
BSA_ABI_ASSERT_SIZE(tBSA_OPS_ACCESS, 257);
BSA_ABI_ASSERT_SIZE(tBSA_OPS_DISABLE, 1);
BSA_ABI_ASSERT_SIZE(tBSA_OPS_CLOSE, 1);
BSA_ABI_ASSERT_SIZE(tBSA_OPC_OBJECT_MSG, 258);
BSA_ABI_ASSERT_OFF(tBSA_OPC_OBJECT_MSG, status, 256);
BSA_ABI_ASSERT_SIZE(tBSA_OPC_PROGRESS_MSG, 8);
BSA_ABI_ASSERT_SIZE(tBSA_OPC_MSG, 260);
BSA_ABI_ASSERT_SIZE(tBSA_OPC_ENABLE, 12);
BSA_ABI_ASSERT_OFF(tBSA_OPC_ENABLE, p_cback, 4);
BSA_ABI_ASSERT_SIZE(tBSA_OPC_DISABLE, 1);
BSA_ABI_ASSERT_SIZE(tBSA_OPC_CLOSE, 1);
BSA_ABI_ASSERT_SIZE(tBSA_OPC_PUSH, 262);
BSA_ABI_ASSERT_SIZE(tBSA_OPC_PULL_CARD, 262);
BSA_ABI_ASSERT_SIZE(tBSA_OPC_EXCH_CARD, 517);
BSA_ABI_ASSERT_OFF(tBSA_OPC_EXCH_CARD, recv_path, 262);

BSA_ABI_ASSERT_SIZE(tBSA_TM_VSE_MSG, 256);
BSA_ABI_ASSERT_SIZE(tBSA_TM_MSG, 256);
BSA_ABI_ASSERT_SIZE(tBSA_TM_TASK_STATUS, 28);
BSA_ABI_ASSERT_OFF(tBSA_TM_TASK_STATUS, stack_size, 4);
BSA_ABI_ASSERT_SIZE(tBSA_TM_GKI_BUF_STATUS, 12);
BSA_ABI_ASSERT_SIZE(tBSA_TM_SET_TEST_MODE, 1);
BSA_ABI_ASSERT_SIZE(tBSA_TM_GET_MEM_USAGE, 616);
BSA_ABI_ASSERT_OFF(tBSA_TM_GET_MEM_USAGE, task, 4);
BSA_ABI_ASSERT_OFF(tBSA_TM_GET_MEM_USAGE, gki_buffer, 424);
BSA_ABI_ASSERT_SIZE(tBSA_TM_PING, 1);
BSA_ABI_ASSERT_SIZE(tBSA_TM_VSC, 262);
BSA_ABI_ASSERT_OFF(tBSA_TM_VSC, data, 6);
BSA_ABI_ASSERT_SIZE(tBSA_TM_HCI_CMD, 262);
BSA_ABI_ASSERT_OFF(tBSA_TM_HCI_CMD, no_opcode_swap, 6);
BSA_ABI_ASSERT_OFF(tBSA_TM_HCI_CMD, data, 7);
BSA_ABI_ASSERT_SIZE(tBSA_TM_SET_TRACE_LEVEL, 257);
BSA_ABI_ASSERT_SIZE(tBSA_TM_FW_VERSION, 6);
BSA_ABI_ASSERT_OFF(tBSA_TM_FW_VERSION, config, 4);
BSA_ABI_ASSERT_SIZE(tBSA_TM_READ_VERSION, 58);
BSA_ABI_ASSERT_OFF(tBSA_TM_READ_VERSION, bsa_server_version, 8);
BSA_ABI_ASSERT_SIZE(tBSA_TM_READ_CFG_BD_ADDR, 8);
BSA_ABI_ASSERT_SIZE(tBSA_TM_VSE_REGISTER, 8);
BSA_ABI_ASSERT_OFF(tBSA_TM_VSE_REGISTER, p_callback, 4);
BSA_ABI_ASSERT_SIZE(tBSA_TM_VSE_DEREGISTER, 4);
BSA_ABI_ASSERT_SIZE(tBSA_TM_DISCONNECT, 6);
BSA_ABI_ASSERT_SIZE(tBSA_TM_QOS, 24);
BSA_ABI_ASSERT_SIZE(tBSA_TM_GET_HANDLE, 12);
BSA_ABI_ASSERT_OFF(tBSA_TM_GET_HANDLE, handle, 10);
BSA_ABI_ASSERT_SIZE(tBSA_TM_READRAWRSSI, 10);
BSA_ABI_ASSERT_OFF(tBSA_TM_READRAWRSSI, conn_handle, 8);
BSA_ABI_ASSERT_SIZE(tBSA_TM_LE_CMD, 6);
BSA_ABI_ASSERT_OFF(tBSA_TM_LE_CMD, retcount, 4);
BSA_ABI_ASSERT_SIZE(tBSA_TM_LE_CMD_RESULT, 4);
BSA_ABI_ASSERT_SIZE(tBSA_DM_SET_MWS_CHAN, 12);
BSA_ABI_ASSERT_SIZE(tBSA_DM_SET_EXT_FRAME_CFG, 104);
BSA_ABI_ASSERT_OFF(tBSA_DM_SET_EXT_FRAME_CFG, duration_array, 8);
BSA_ABI_ASSERT_OFF(tBSA_DM_SET_EXT_FRAME_CFG, type_array, 72);
BSA_ABI_ASSERT_SIZE(tBSA_DM_COEX_OP_PARAMS, 104);
BSA_ABI_ASSERT_SIZE(tBSA_TM_COEXOP, 106);
BSA_ABI_ASSERT_OFF(tBSA_TM_COEXOP, coex_op_params, 2);
BSA_ABI_ASSERT_SIZE(tBSA_TM_BASIC_ACL_STATS_DATA, 64);
BSA_ABI_ASSERT_OFF(tBSA_TM_BASIC_ACL_STATS_DATA, total_rx_acl_bytes, 40);
BSA_ABI_ASSERT_SIZE(tBSA_TM_EDR_ACL_STATS_DATA, 60);
BSA_ABI_ASSERT_OFF(tBSA_TM_EDR_ACL_STATS_DATA, total_rx_acl_bytes, 36);
BSA_ABI_ASSERT_SIZE(tBSA_TM_AUX_BER_STATS_DATA, 28);
BSA_ABI_ASSERT_SIZE(tBSA_TM_DIAG_DATA, 64);
BSA_ABI_ASSERT_SIZE(tBSA_TM_DIAG_STATS, 68);
BSA_ABI_ASSERT_OFF(tBSA_TM_DIAG_STATS, diag, 4);

#undef BSA_ABI_ASSERT_OFF
#undef BSA_ABI_ASSERT_SIZE
#undef BSA_ABI_ASSERT
#endif

#ifdef __cplusplus
}
#endif

#endif
