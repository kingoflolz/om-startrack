/* Foreground gyro acquisition uses the stock BC command task and client 2.
 * Owner-0x92 and SDK callers only enqueue; neither waits for synchronous SSC.
 * This source is additive and does not alter the native-rate implementation.
 *
 * Private uncached record at 0xa0047ad0 (zeroed by image initialization):
 * +0 heartbeat, +4 remaining owner ticks, +12 eligible, +13 owns client-2 ref,
 * +16 last acquisition/release status, +20 rollback-release status.
 * Other private bytes are reserved.
 * The native lease is bounded by 60 owner ticks without heartbeat progress.
 * Linux clears enable on foreground/subscription loss. Acquisition shutdown
 * follows ordinary stock client release, including detector mode 0xff.
 */
    .am33_2
    .equ GYRO_LEASE, 0xa0047ac0
    .equ GYRO_PRIVATE, 0xa0047ad0
    .equ GYRO_LEASE_MAGIC, 0x314c5947
    .equ GYRO_QUEUE_COMMAND, 0x31415947 /* private GYA1, outside stock 0..256 */
    .equ BC_QUEUE_ID, 0x600431d6
    .equ BC_CURRENT_REQUEST, 0x62986a7c
    /* Resolve branch targets in the linker script, not assembler .equ:
     * MN10300 treats a resolved absolute CALL/JMP operand as a displacement. */
    .extern FW_QUEUE_SEND
    .extern FW_BC_BUSY_SET
    .extern FW_BC_LADDER
    .extern FW_OWNER_REEVALUATE
    .extern FW_BC_RECEIVE_CONTINUATION
    .extern FW_BC_COMMAND_CLEANUP

    .section .gyro_main.patch_bc_receive,"ax",@progbits
    jmp gyro_main_bc_receive
    nop

    .section .gyro_main.acquisition_text,"ax",@progbits
    .global gyro_main_acquisition_tick
    .global gyro_main_acquisition_wake
/* Native CALL frame: [d2,d3,a2,a3],20. Queue copies the 16-byte stack record.
 * Timeout zero means a full queue never blocks owner or SDK progress. */
gyro_main_acquisition_tick:
    mov 1,d2
    bra .Lgyro_enqueue
gyro_main_acquisition_wake:
    clr d2
.Lgyro_enqueue:
    movbu (GYRO_PRIVATE+13),d0
    cmp 1,d0
    beq .Lgyro_enqueue_active
    mov (GYRO_LEASE+8),d0
    cmp 1,d0
    bne .Lgyro_enqueue_return
.Lgyro_enqueue_active:
    add -20,sp
    clr d0
    mov d0,(4,sp)
    mov d0,(8,sp)
    mov GYRO_QUEUE_COMMAND,d0
    mov d0,(12,sp)
    mov d2,(16,sp)
    movhu (BC_QUEUE_ID),d0
    exth d0
    cmp 0,d0
    ble .Lgyro_enqueue_done
    mov 16,d1
    mov sp,a0
    inc4 a0
    mov 0,a1
    call FW_QUEUE_SEND,[d2,d3],44
.Lgyro_enqueue_done:
    add 20,sp
.Lgyro_enqueue_return:
    ret [d2,d3,a2,a3],20

    .global gyro_main_bc_receive
/* Displaces raw 0x13e67d: mov a2,(0x62986a7c). This point follows the stock
 * blocking queue receive in the BC command task. Preserve its normal path and
 * use the same busy state and cleanup for the private service command. */
gyro_main_bc_receive:
    mov a2,(BC_CURRENT_REQUEST)
    mov (8,a2),d0
    cmp GYRO_QUEUE_COMMAND,d0
    beq .Lgyro_bc_command
    jmp FW_BC_RECEIVE_CONTINUATION
.Lgyro_bc_command:
    mov 1,d0
    call FW_BC_BUSY_SET,[],4
    call gyro_main_acquisition_service,[d2,d3,a2,a3],20
    mov d0,d2
    jmp FW_BC_COMMAND_CLEANUP

/* Only the BC command task writes native ownership and eligibility. Client 2
 * stops the stock reference ladder at acquisition; it does not enable corr or
 * simulate a shutter press. Refcounts are changed before native I/O, so even
 * an unsuccessful acquire owns one reference. Balance it immediately because
 * failed acquisition must not depend on a telemetry tick chain starting. */
gyro_main_acquisition_service:
    mov GYRO_PRIVATE,a3
    mov GYRO_LEASE,a0
    mov (a0),d0
    cmp GYRO_LEASE_MAGIC,d0
    bne .Lgyro_expired
    mov (8,a0),d0
    cmp 1,d0
    bne .Lgyro_expired
    mov (12,a0),d0
    cmp 0,d0
    bne .Lgyro_expired
    mov (4,a0),d0
    cmp 0,d0
    beq .Lgyro_expired
    mov (a3),d1
    cmp d1,d0
    beq .Lgyro_same_heartbeat
    mov d0,(a3)
    mov 60,d0
    mov d0,(4,a3)
    bra .Lgyro_wanted
.Lgyro_same_heartbeat:
    mov (4,a3),d0
    cmp 0,d0
    beq .Lgyro_expired
    mov (12,a2),d1
    cmp 0,d1
    beq .Lgyro_wanted
    add -1,d0
    mov d0,(4,a3)
    beq .Lgyro_expired
.Lgyro_wanted:
    mov 1,d2
    bra .Lgyro_reconcile
.Lgyro_expired:
    clr d2
    mov d2,(4,a3)
.Lgyro_reconcile:
    movbu (13,a3),d0
    cmp d2,d0
    beq .Lgyro_service_done
    movbu d2,(13,a3)
    /* The ladder reads request+0 and writes request+160 on this lower path.
     * Reserve its full conventional 320-byte request size on this task stack. */
    add -324,sp
    mov 2,d0
    cmp 0,d2
    bne .Lgyro_request_ready
    add 256,d0
.Lgyro_request_ready:
    movhu d0,(4,sp)
    mov sp,a0
    inc4 a0
    call FW_BC_LADDER,[d2,d3,a2,a3],24
    exthu d0
    mov d0,(16,a3)
    mov d0,d3
    cmp 0,d0
    beq .Lgyro_unwind_request
    cmp 0,d2
    beq .Lgyro_unwind_request
    mov 258,d0
    movhu d0,(4,sp)
    mov sp,a0
    inc4 a0
    call FW_BC_LADDER,[d2,d3,a2,a3],24
    exthu d0
    mov d0,(20,a3)
    clr d2
    movbu d2,(13,a3)
.Lgyro_unwind_request:
    add 324,sp
    cmp 0,d3
    beq .Lgyro_status_ready
    clr d2
.Lgyro_status_ready:
    movbu d2,(12,a3)
    clr d0
    clr d1
    call FW_OWNER_REEVALUATE,[d2],8
.Lgyro_service_done:
    clr d0
    ret [d2,d3,a2,a3],20
