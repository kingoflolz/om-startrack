#ifndef OLYMPUS_BSA_UIPC_API_H
#define OLYMPUS_BSA_UIPC_API_H

/* Public UIPC data-plane subset needed by the BSA Data Gateway facade. */

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

#ifndef BSA_ABI_BT_HDR_DEFINED
#define BSA_ABI_BT_HDR_DEFINED 1
typedef struct {
    UINT16 event;
    UINT16 len;
    UINT16 offset;
    UINT16 layer_specific;
} BT_HDR;
#endif

typedef void tUIPC_RCV_CBACK(BT_HDR *p_msg);

#define UIPC_CH_ID_ALL 0u
#define UIPC_CH_ID_CTL 3u
#define UIPC_CH_ID_DG_FIRST 11u
#define UIPC_CH_ID_DG_NB 20u
#define UIPC_CH_ID_DG_LAST 30u
#define UIPC_CH_ID_BAD 0xffu

#define UIPC_REQ_TX_FLUSH 1u
#define UIPC_REQ_RX_FLUSH 2u
#define UIPC_WRITE_BLOCK 3u
#define UIPC_WRITE_NONBLOCK 4u
#define UIPC_REG_CBACK 5u
#define UIPC_SET_RX_WM 6u
#define UIPC_REQ_TX_READY 7u
#define UIPC_REQ_RX_READY 8u
#define UIPC_RESET 9u
#define UIPC_READ_ERROR 10u

#define UIPC_LS_TX_FLOW_OFF 0u
#define UIPC_LS_TX_FAIL 1u
#define UIPC_OPEN_EVT 0x01u
#define UIPC_CLOSE_EVT 0x02u
#define UIPC_RX_DATA_EVT 0x03u
#define UIPC_RX_DATA_READY_EVT 0x04u
#define UIPC_TX_DATA_READY_EVT 0x05u
#define UIPC_NOERR 0x00u
#define UIPC_ENOMEM 0x01u
#define UIPC_EINVAL 0x02u

void UIPC_Init(void *p_path);
BOOLEAN UIPC_Open(tUIPC_CH_ID channel_id, tUIPC_RCV_CBACK *p_callback);
void UIPC_Close(tUIPC_CH_ID channel_id);
BOOLEAN UIPC_Send(tUIPC_CH_ID channel_id,
                  UINT16 message_event,
                  UINT8 *p_buf,
                  UINT16 len);
BOOLEAN UIPC_SendBuf(tUIPC_CH_ID channel_id, BT_HDR *p_msg);
UINT32 UIPC_Read(tUIPC_CH_ID channel_id,
                 UINT16 *p_message_event,
                 UINT8 *p_buf,
                 UINT32 len);
void UIPC_ReadBuf(tUIPC_CH_ID channel_id, BT_HDR *p_msg);
BOOLEAN UIPC_Ioctl(tUIPC_CH_ID channel_id, UINT32 request, void *p_param);

#if defined(__cplusplus) && __cplusplus >= 201103L
static_assert(sizeof(BT_HDR) == 8, "BT_HDR size");
#elif defined(__STDC_VERSION__) && __STDC_VERSION__ >= 201112L
_Static_assert(sizeof(BT_HDR) == 8, "BT_HDR size");
#endif

#ifdef __cplusplus
}
#endif

#endif
