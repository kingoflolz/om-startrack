/* MK2 station event 1 is asynchronous, selects profile 14, and never replies
 * to a synchronous caller. Private event 7 follows the same station-owner
 * initialization, selects profile 9, then uses the existing synchronous reply.
 * All redirects are JMPs, preserving the owner handler's 88-byte frame.
 */
 .am33_2
 .equ PRIVATE_START,7
 .equ QTPC_QUEUE_ID,0x600432d0
 .equ PTP_PROFILE_STATE,0x62d38014
 .equ STATION_STATE,0xa3210918
 .equ STATION_REGISTRATION,0xa321091a
 .section .astro.patch_tpc_dispatch,"ax",@progbits
 jmp astro_tpc_dispatch
 nop
 .section .astro.patch_tpc_selector,"ax",@progbits
 jmp astro_tpc_selector
 .rept 5
 nop
 .endr
 .section .astro.patch_tpc_complete,"ax",@progbits
 jmp astro_tpc_complete
 nop
 nop
 .section .astro.tpc_text,"ax",@progbits
 .global astro_tpc_dispatch
astro_tpc_dispatch:
 mov d0,a0
 mov (a0),d0
 cmp PRIVATE_START,d0
 beq .Ltpc_private_start
 cmp d2,d0
 beq .Ltpc_private_start
 jmp FW_TPC_DISPATCH_REST
.Ltpc_private_start:
 jmp FW_TPC_START_HANDLER
 .global astro_tpc_selector
astro_tpc_selector:
 mov (28,sp),a0
 mov (8,a0),d0
 cmp PRIVATE_START,d0
 bne .Ltpc_stock_selector
 mov 9,d0
 bra .Ltpc_store_selector
.Ltpc_stock_selector:
 mov 14,d0
.Ltpc_store_selector:
 movbu d0,(a2)
 mov a2,a0
 movhu (QTPC_QUEUE_ID),d0
 jmp FW_TPC_SELECTOR_CONTINUATION
 .global astro_tpc_complete
astro_tpc_complete:
 mov (32,sp),a0
 mov (a0),d1
 cmp PRIVATE_START,d1
 beq .Ltpc_private_complete
 cmp 1,d1
 beq .Ltpc_async_complete
 jmp FW_TPC_COMPLETE_REST
.Ltpc_private_complete:
 /* Mark II START clears registration. Profile 9's file-record handler needs
  * it enabled, as in the original OM-1 START. Publish it only after the
  * private request has completed the full, successful station setup. */
 cmp 0,d2
 bne .Ltpc_private_reply
 movhu (STATION_STATE),d0
 cmp 1,d0
 bne .Ltpc_private_reply
 movbu (PTP_PROFILE_STATE),d0
 cmp 1,d0
 bne .Ltpc_private_reply
 movhu (PTP_PROFILE_STATE+2),d0
 cmp 9,d0
 bne .Ltpc_private_reply
 mov 1,d0
 movhu d0,(STATION_REGISTRATION)
.Ltpc_private_reply:
 jmp FW_TPC_SYNCHRONOUS_REPLY
.Ltpc_async_complete:
 jmp FW_TPC_ASYNCHRONOUS_REPLY
