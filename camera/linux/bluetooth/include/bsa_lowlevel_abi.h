#ifndef OLYMPUS_BSA_LOWLEVEL_ABI_H
#define OLYMPUS_BSA_LOWLEVEL_ABI_H

/*
 * Low-level client ABI exported by the Olympus libbsa.so.
 *
 * Function declarations were recovered from the target ARM EABI5 library and
 * cross-checked against Cypress BSA 00.26/05.01 DWARF.  Object sizes come from
 * the target's dynamic symbol table.  Private control blocks are deliberately
 * opaque: their exported storage size is known, but consumers must not depend
 * on their implementation fields.
 *
 * Scope is intentionally limited to the GKI_*, UIPC_*, LogMsg*, and Bluetooth
 * address helpers, plus all 20 exported STT_OBJECT symbols.  Lowercase
 * implementation entry points (gki_buffer_*, uipc_cl_*, uipc_fifo_*,
 * uipc_rb_*, uipc_sema_*, uipc_shm_*, uipc_thread_*, bsa_*, and mutex helpers)
 * remain catalogued in ../abi/libbsa.exports.tsv but are not declared here.
 *
 * The binary ABI is 32-bit ARM.  The declarations compile on other hosts for
 * analysis, but linking or calling libbsa requires an ARM hard-float target.
 */

#include <stddef.h>
#include <stdint.h>
#include "bsa_uipc_api.h"

#ifdef __cplusplus
extern "C" {
#endif

#ifndef BSA_ABI_UINTPTR_DEFINED
#define BSA_ABI_UINTPTR_DEFINED 1
typedef uintptr_t UINTPTR;
#endif

#ifndef BSA_ABI_BUFFER_Q_DEFINED
#define BSA_ABI_BUFFER_Q_DEFINED 1
typedef struct {
    void *p_first;
    void *p_last;
    UINT16 count;
} BUFFER_Q;
#endif

#ifndef BSA_ABI_TASKPTR_DEFINED
#define BSA_ABI_TASKPTR_DEFINED 1
typedef void (*TASKPTR)(UINT32 parameter);
#endif

/* GKI task, event, and scheduling API. */
void GKI_init(void);
void GKI_shutdown(void);
void GKI_run(void *p_task_id);
void GKI_stop(void);
UINT8 GKI_create_task(TASKPTR task_entry,
                      UINT8 task_id,
                      INT8 *p_task_name,
                      UINT16 *p_stack,
                      UINT16 stack_size);
UINT16 GKI_wait(UINT16 event_mask, UINT32 timeout);
void GKI_delay(UINT32 timeout);
UINT8 GKI_send_event(UINT8 task_id, UINT16 event);
UINT8 GKI_isend_event(UINT8 task_id, UINT16 event);
UINT8 GKI_get_taskid(void);
void *GKI_read_mbox(UINT8 mbox);
void GKI_send_msg(UINT8 task_id, UINT8 mbox, void *p_msg);
UINT32 GKI_get_os_tick_count(void);
INT8 *GKI_get_time_stamp(INT8 *p_buffer);
INT8 *GKI_map_taskname(UINT8 task_id);
void GKI_enable(void);
void GKI_disable(void);
void GKI_exception(UINT16 code, char *p_message);
void GKI_register_mempool(void *p_mem);
void *GKI_os_malloc(UINT32 size);
void GKI_os_free(void *p_mem);
UINT8 GKI_suspend_task(UINT8 task_id);
UINT8 GKI_resume_task(UINT8 task_id);
void GKI_exit_task(UINT8 task_id);
void GKI_sched_lock(void);
void GKI_sched_unlock(void);
UINT16 GKI_get_stack_used(UINT8 task_id);
void GKI_shiftdown(UINT8 *p_mem, UINT32 len, UINT32 shift_amount);
void GKI_shiftup(UINT8 *p_dest, UINT8 *p_src, UINT32 len);
void GKI_TRACE(char *p_format, ...);
void GKI_ERROR_TRACE(char *p_format, ...);

/* GKI fixed-buffer pools and intrusive queues. */
void *GKI_getbuf(UINT16 size);
void *GKI_getpoolbuf(UINT8 pool_id);
void *GKI_igetpoolbuf(UINT8 pool_id);
void GKI_freebuf(void *p_buf);
UINT16 GKI_get_buf_size(void *p_buf);
void GKI_enqueue(BUFFER_Q *p_queue, void *p_buf);
void GKI_enqueue_head(BUFFER_Q *p_queue, void *p_buf);
void *GKI_dequeue(BUFFER_Q *p_queue);
void *GKI_remove_from_queue(BUFFER_Q *p_queue, void *p_buf);
void *GKI_getfirst(BUFFER_Q *p_queue);
void *GKI_getlast(BUFFER_Q *p_queue);
void *GKI_getnext(void *p_buf);
BOOLEAN GKI_queue_is_empty(BUFFER_Q *p_queue);
void *GKI_find_buf_start(void *p_buf);
UINT8 GKI_create_pool(UINT16 size, UINT16 count, UINT8 permission, void *p_mem);
void GKI_delete_pool(UINT8 pool_id);
UINT16 GKI_poolcount(UINT8 pool_id);
UINT16 GKI_poolfreecount(UINT8 pool_id);
UINT16 GKI_get_pool_bufsize(UINT8 pool_id);
UINT16 GKI_poolutilization(UINT8 pool_id);
UINT8 GKI_set_pool_permission(UINT8 pool_id, UINT8 permission);
void GKI_change_buf_owner(void *p_buf, UINT8 task_id);
void GKI_init_q(BUFFER_Q *p_queue);

/* Trace entry points.  LogMsg_N takes N pointer-width integer arguments. */
void LogMsg(UINT32 trace_set_mask, const char *p_format, ...);
void LogMsg_0(UINT32 trace_set_mask, const char *p_format);
void LogMsg_1(UINT32 trace_set_mask, const char *p_format, UINTPTR arg1);
void LogMsg_2(UINT32 trace_set_mask,
              const char *p_format,
              UINTPTR arg1,
              UINTPTR arg2);
void LogMsg_3(UINT32 trace_set_mask,
              const char *p_format,
              UINTPTR arg1,
              UINTPTR arg2,
              UINTPTR arg3);
void LogMsg_4(UINT32 trace_set_mask,
              const char *p_format,
              UINTPTR arg1,
              UINTPTR arg2,
              UINTPTR arg3,
              UINTPTR arg4);
void LogMsg_5(UINT32 trace_set_mask,
              const char *p_format,
              UINTPTR arg1,
              UINTPTR arg2,
              UINTPTR arg3,
              UINTPTR arg4,
              UINTPTR arg5);
void LogMsg_6(UINT32 trace_set_mask,
              const char *p_format,
              UINTPTR arg1,
              UINTPTR arg2,
              UINTPTR arg3,
              UINTPTR arg4,
              UINTPTR arg5,
              UINTPTR arg6);

/* Six-byte Bluetooth-address helpers. */
void bdcpy(UINT8 *p_dest, const UINT8 *p_src);
void bdsetany(UINT8 *p_addr);
int bdcmp(const UINT8 *p_left, const UINT8 *p_right);
int bdcmpany(const UINT8 *p_addr);

/*
 * Target-sized private control blocks.  A UINT32 member preserves the natural
 * four-byte alignment of these ARM objects without exposing guessed fields.
 */
#define BSA_LOWLEVEL_OPAQUE_TYPE(type_name, byte_count) \
    typedef union {                                      \
        UINT8 opaque[(byte_count)];                      \
        UINT32 alignment;                                \
    } type_name

BSA_LOWLEVEL_OPAQUE_TYPE(tBSA_CL_MGT_CB, 8);
BSA_LOWLEVEL_OPAQUE_TYPE(tBSA_SEC_CB, 4);
BSA_LOWLEVEL_OPAQUE_TYPE(tBSA_OPS_CB, 4);
BSA_LOWLEVEL_OPAQUE_TYPE(tBSA_OPC_CB, 4);
BSA_LOWLEVEL_OPAQUE_TYPE(tBSA_DG_CB, 4);
BSA_LOWLEVEL_OPAQUE_TYPE(tGKI_CB, 346328);
typedef struct {
    char path[100];
} tUIPC;
BSA_LOWLEVEL_OPAQUE_TYPE(tUIPC_FIFO, 1284);
BSA_LOWLEVEL_OPAQUE_TYPE(tUIPC_RB_CB, 188);
BSA_LOWLEVEL_OPAQUE_TYPE(tUIPC_THREAD_TM, 28);
BSA_LOWLEVEL_OPAQUE_TYPE(tBSA_CL_DM_CB, 4);
BSA_LOWLEVEL_OPAQUE_TYPE(tBSA_DISC_CB, 20);
BSA_LOWLEVEL_OPAQUE_TYPE(tBSA_CL_TM_CB, 4);
BSA_LOWLEVEL_OPAQUE_TYPE(tBSA_BLE_CB, 12);

#undef BSA_LOWLEVEL_OPAQUE_TYPE

/* All target STT_OBJECT exports, with source-level types where known. */
extern const UINT8 bd_addr_null[6];
extern const UINT8 bd_addr_any[6];
extern tUIPC_RCV_CBACK *const uipc_cl_dg_cback_tab[20];
extern UINT8 appl_trace_level;
extern BOOLEAN global_trace_disable;
extern int log_msg_count;
extern tBSA_CL_MGT_CB bsa_cl_mgt_cb;
extern tBSA_SEC_CB bsa_sec_cb;
extern tBSA_OPS_CB bsa_ops_cb;
extern tBSA_OPC_CB bsa_opc_cb;
extern tBSA_DG_CB bsa_dg_cb;
extern tGKI_CB gki_cb;
extern tUIPC uipc_cb;
extern tUIPC_FIFO uipc_fifo_cb;
extern tUIPC_RB_CB uipc_rb_cb;
extern tUIPC_THREAD_TM uipc_thread_tm_cb[15];
extern tBSA_CL_DM_CB bsa_cl_dm_cb;
extern tBSA_DISC_CB bsa_disc_cb;
extern tBSA_CL_TM_CB bsa_cl_tm_cb;
extern tBSA_BLE_CB bsa_ble_cb;

#if defined(__cplusplus)
#define BSA_LOWLEVEL_ASSERT(condition, message) static_assert((condition), message)
#else
#define BSA_LOWLEVEL_ASSERT(condition, message) _Static_assert((condition), message)
#endif
#define BSA_LOWLEVEL_ASSERT_SIZE(type_name, byte_count) \
    BSA_LOWLEVEL_ASSERT(sizeof(type_name) == (byte_count), #type_name " size")

BSA_LOWLEVEL_ASSERT_SIZE(BT_HDR, 8);
BSA_LOWLEVEL_ASSERT_SIZE(tBSA_CL_MGT_CB, 8);
BSA_LOWLEVEL_ASSERT_SIZE(tBSA_SEC_CB, 4);
BSA_LOWLEVEL_ASSERT_SIZE(tBSA_OPS_CB, 4);
BSA_LOWLEVEL_ASSERT_SIZE(tBSA_OPC_CB, 4);
BSA_LOWLEVEL_ASSERT_SIZE(tBSA_DG_CB, 4);
BSA_LOWLEVEL_ASSERT_SIZE(tGKI_CB, 346328);
BSA_LOWLEVEL_ASSERT_SIZE(tUIPC, 100);
BSA_LOWLEVEL_ASSERT_SIZE(tUIPC_FIFO, 1284);
BSA_LOWLEVEL_ASSERT_SIZE(tUIPC_RB_CB, 188);
BSA_LOWLEVEL_ASSERT_SIZE(tUIPC_THREAD_TM, 28);
BSA_LOWLEVEL_ASSERT_SIZE(tBSA_CL_DM_CB, 4);
BSA_LOWLEVEL_ASSERT_SIZE(tBSA_DISC_CB, 20);
BSA_LOWLEVEL_ASSERT_SIZE(tBSA_CL_TM_CB, 4);
BSA_LOWLEVEL_ASSERT_SIZE(tBSA_BLE_CB, 12);
BSA_LOWLEVEL_ASSERT(sizeof(tUIPC_THREAD_TM[15]) == 420,
                    "uipc_thread_tm_cb size");

#if UINTPTR_MAX == UINT32_MAX
BSA_LOWLEVEL_ASSERT_SIZE(BUFFER_Q, 12);
BSA_LOWLEVEL_ASSERT(sizeof(tUIPC_RCV_CBACK *[20]) == 80,
                    "uipc_cl_dg_cback_tab size");
#endif

#undef BSA_LOWLEVEL_ASSERT_SIZE
#undef BSA_LOWLEVEL_ASSERT

#ifdef __cplusplus
}
#endif

#endif
