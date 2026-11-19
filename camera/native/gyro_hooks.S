/* Base MN10300 wrappers; no new interrupt masking. Calls into GCC C use the
 * compiler's [],0 ABI, distinct from replayed vendor calls and their frames.
 */
    .equ GYRO_STATE, 0x4e0
    .equ GYRO_IRQ_SEQUENCE, GYRO_STATE + 80
    .equ GYRO_SNAPSHOT_SEQUENCE, GYRO_STATE + 88

    .section .gyro.hooks,"ax",@progbits
    .global gyro_snapshot_hook
gyro_snapshot_hook:
    /* d0 holds the stock saved IRQ mask. d1 is dead at this release call. */
    mov     (GYRO_IRQ_SEQUENCE),d1
    mov     d1,(GYRO_SNAPSHOT_SEQUENCE)
    call    FW_RELEASE_INTERRUPTS,[],4
    jmp     FW_AFTER_RELEASE

    .global gyro_sample_hook
gyro_sample_hook:
    call    FW_CALIBRATE,[d2,d3,a2,a3],52
    /* C reserves and restores its own saved-register frame. Reserve outgoing
     * call-link/scratch space so its return link cannot overlap stock locals. */
    add     -12,sp
    mov     GYRO_STATE,d0
    mov     (GYRO_SNAPSHOT_SEQUENCE),d1
    call    _gyro_attitude_native_sample,[],0
    add     12,sp
    jmp     FW_AFTER_CALIBRATE

    .global gyro_mode_hook
gyro_mode_hook:
    /* The existing PRE wrapper still owns the exact three native mode stores.
     * Save its incoming new-mode d0 while C inspects the old native mode. */
    add     -16,sp
    mov     d0,(12,sp)
    mov     d0,d1
    mov     GYRO_STATE,d0
    call    _gyro_attitude_native_transition,[],0
    mov     (12,sp),d0
    add     16,sp
    jmp     FW_PRE_MODE_HOOK

    .global gyro_irq_hook
gyro_irq_hook:
    /* Executed only on the IRQs that post a 2 kHz detector event. d0 is then
     * immediately replaced by the original timer read; no other register is
     * borrowed and no call, multiply, or MDR alteration is introduced. */
    mov     (GYRO_IRQ_SEQUENCE),d0
    inc     d0
    mov     d0,(GYRO_IRQ_SEQUENCE)
    movhu   (0x9c050010),d0
    movhu   d0,(0x5e54)
    jmp     FW_AFTER_IRQ_TIMESTAMP

    .section .gyro.release_redirect,"ax",@progbits
    jmp gyro_snapshot_hook
    nop
    nop
    .section .gyro.sample_redirect,"ax",@progbits
    jmp gyro_sample_hook
    .section .gyro.mode_redirect,"ax",@progbits
    jmp gyro_mode_hook
    .rept 4
    nop
    .endr
    .section .gyro.irq_redirect,"ax",@progbits
    jmp gyro_irq_hook
    .rept 4
    nop
    .endr

    /* 39 retained records [0x1d4,0x4e0); new state owns records 39..44. */
    .section .gyro.ring_write,"ax",@progbits
    cmp 38,d0
    .section .gyro.ring_count,"ax",@progbits
    cmp 39,d0
    .section .gyro.ring_oldest,"ax",@progbits
    cmp 38,d0
    .section .gyro.ring_dump,"ax",@progbits
    cmp 38,d0
