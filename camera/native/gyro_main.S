/* Additive layer over the native-rate MAIN image. */
    .am33_2

    .equ GYRO_MIRROR, 0xa0047a80
    .equ GYRO_MIRROR_MAGIC, 0x31525947 /* GYR1 */
    .equ GYRO_HELD, 0xa0047adc
    .equ OWNER_INPUTS, 0x62985f80
    .equ OWNER_TIMER, 0x62985f7e

    .section .gyro_main.patch_rx,"ax",@progbits
    mov 54,d0

    .section .gyro_main.patch_parser,"a",@progbits
    .long gyro_main_op37_parser

    .section .gyro_main.patch_eligible,"ax",@progbits
    jmp gyro_main_eligible
    nop
    nop

    .section .gyro_main.patch_submit,"ax",@progbits
    jmp gyro_main_submit
    nop
    nop
    nop

    .section .gyro_main.patch_wake,"ax",@progbits
    call gyro_main_wake,[],8

    .section .gyro_main.text,"ax",@progbits
    .global gyro_main_op37_parser
/* Descriptor CALLS ABI: d0=result, a0=request, a1=transaction. Stock frees the
 * RX allocation; copy the extension first, then invoke its unchanged parser.
 * There is one serialized opcode-37 request, hence one mirror writer. */
gyro_main_op37_parser:
    movm [d2,d3,a2,a3],(sp)
    add -12,sp
    mov d0,d3
    mov a0,a2
    mov a1,a3
    exthu d0
    cmp 0,d0
    bne .Lparse_stock
    mov (16,a3),d0
    cmp 54,d0
    bne .Lparse_stock
    mov (12,a3),a1
    cmp 0,a1
    beq .Lparse_stock
    add 6,a1
    movbu (a1),d0
    cmp 1,d0
    bne .Lparse_stock
    movbu (1,a1),d0
    and 248,d0
    bne .Lparse_stock
    movbu (2,a1),d0
    movbu (3,a1),d1
    or d1,d0
    bne .Lparse_stock

    mov GYRO_MIRROR,a0
    mov (4,a0),d2
    and -2,d2
    add 2,d2
    bne .Lgeneration_ready
    mov 2,d2
.Lgeneration_ready:
    mov d2,d1
    add -1,d1
    mov d1,(4,a0)
    add 8,a0
    mov 48,d0
.Lcopy_packet:
    movbu (a1),d1
    movbu d1,(a0)
    inc a1
    inc a0
    add -1,d0
    bne .Lcopy_packet
    mov d0,(a0)
    mov d0,(4,a0)
    mov GYRO_MIRROR,a0
    mov GYRO_MIRROR_MAGIC,d0
    mov d0,(a0)
    mov d2,(4,a0) /* coherent generation commits last */
.Lparse_stock:
    mov d3,d0
    mov a2,a0
    mov a3,a1
    mov FW_STOCK_OP37_PARSER,a2
    calls (a2)
    ret [d2,d3,a2,a3],28

/* Stock old/new eligibility edge logic follows the common recomputation.
 * Keep first-stage, still-start and correction inputs intact. */
    .global gyro_main_eligible
gyro_main_eligible:
    movbu (GYRO_HELD),d0
    cmp 1,d0
    bne .Lstock_eligible
    bset 16,(OWNER_INPUTS)
    jmp FW_ELIGIBLE_EDGE
.Lstock_eligible:
    btst 128,(OWNER_INPUTS)
    jmp FW_ELIGIBLE_CONTINUATION

/* Called in owner task context, before the stock timer/eligibility guards.
 * Timer callbacks retain the stock timer/completion rendezvous. */
    .global gyro_main_submit
gyro_main_submit:
    call gyro_main_acquisition_tick,[d2,d3,a2,a3],20
    movhu (OWNER_TIMER),d0
    cmp 0,d0
    jmp FW_SUBMIT_CONTINUATION

/* Preserve v3's stock sleep-state query and RUNNING-only activity rearm.
 * Linux schedules one existing KEEP_AWAKE operation when enabling the lease. */
    .global gyro_main_wake
gyro_main_wake:
    call FW_V3_SLEEP_WRAPPER,[],8
    cmp 0,d0
    bne .Lwake_return
    call gyro_main_acquisition_wake,[d2,d3,a2,a3],20
    clr d0
.Lwake_return:
    ret [],8

    .section .gyro_main.mirror,"aw",@progbits
    .space 64,0
    .section .gyro_main.lease,"aw",@progbits
    .long 0x314c5947,0,0,0 /* static GYL1 signature, heartbeat, enable, reserved */
    .section .gyro_main.private,"aw",@progbits
    .space 48,0
