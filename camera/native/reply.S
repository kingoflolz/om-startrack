/* Append the independent gyro state to exact opcode-37 stock replies.
 * The stock request receiver and detector correction builder remain intact.
 */
    .am33_2
    .equ ATTITUDE_STATE, 0x000004e0
    .equ ATTITUDE_SEQ, ATTITUDE_STATE + 48

    .section .gyro_reply.text,"ax",@progbits
    .global gyro_reply_dispatch
gyro_reply_dispatch:
    /* Recreate the displaced CALL's register mask/frame, then its return. */
    call    gyro_reply_send,[d2,d3,a2,a3],24
    jmp     FW_AFTER_SEND

    .global gyro_reply_send
gyro_reply_send:
    movbu   (a0),d0
    cmp     1,d0
    bne     .Lstock_send
    movbu   (1,a0),d0
    cmp     0x37,d0
    bne     .Lstock_send
    movbu   (2,a0),d0
    cmp     6,d0
    bne     .Lstock_send

    /* Own both allocations. The stock >40 sender leaks on allocation or
     * queue failure, so the new recurring path releases each acquired block.
     * Both requested sizes fit the audited 128-byte pool class.
     */
    mov     a0,a3
    mov     44,d0
    call    FW_ALLOC,[],4
    mov     a0,a2
    cmp     0,a2
    beq     .Lsend_failed
    mov     54,d0
    call    FW_ALLOC,[],4
    mov     a0,d2
    cmp     0,d2
    beq     .Lfree_header
    mov     a3,a1
    inc4    a1
    mov     6,d0
    call    FW_MEMCPY,[d2,d3,a2,a3],28
    mov     d2,a0
    add     6,a0
    call    gyro_reply_snapshot,[d2,d3,a2,a3],20
    mov     (a3),d0
    mov     d0,(a2)
    mov     54,d0
    movbu   d0,(2,a2)
    mov     d2,(4,a2)

    /* Keep the existing outgoing queue, choosing its stock task/ISR API. */
    call    FW_IN_INTERRUPT,[],12
    extbu   d0
    cmp     0,d0
    bne     .Lsend_from_interrupt
    movhu   (FW_OUTGOING_QUEUE),d0
    exth    d0
    mov     a2,d1
    call    FW_QUEUE_TASK,[d2],16
    bra     .Lsend_result
.Lsend_from_interrupt:
    movhu   (FW_OUTGOING_QUEUE),d0
    exth    d0
    mov     a2,d1
    call    FW_QUEUE_INTERRUPT,[d2],16
.Lsend_result:
    cmp     0,d0
    bne     .Lfree_payload
    mov     1,d0
    ret     [d2,d3,a2,a3],24
.Lfree_payload:
    mov     d2,a0
    call    FW_FREE,[],4
.Lfree_header:
    mov     a2,a0
    call    FW_FREE,[],4
.Lsend_failed:
    clr     d0
    ret     [d2,d3,a2,a3],24
.Lstock_send:
    jmp     FW_STOCK_SEND

    .global gyro_reply_snapshot
gyro_reply_snapshot:
    /* Exactly one bounded read. Never spin if a higher-priority reader
     * interrupts a writer. Version zero makes MAIN skip a torn/odd sample
     * without replacing its previous coherent mirror or capture anchor.
     * Byte copying permits the six-byte stock prefix's unaligned suffix.
     */
    mov     (ATTITUDE_SEQ),d2
    mov     a0,a2
    mov     ATTITUDE_STATE,a1
    mov     48,d3
.Lsnapshot_copy:
    movbu   (a1),d0
    movbu   d0,(a0)
    inc     a1
    inc     a0
    add     -1,d3
    bne     .Lsnapshot_copy
    mov     (ATTITUDE_SEQ),d0
    cmp     d2,d0
    bne     .Lsnapshot_invalid
    btst    1,d2
    beq     .Lsnapshot_header
.Lsnapshot_invalid:
    clr     d0
    movbu   d0,(a2)
    movbu   d0,(1,a2)
    ret     [d2,d3,a2,a3],20
.Lsnapshot_header:
    mov     1,d0
    movbu   d0,(a2)
    clr     d0
    movbu   d0,(2,a2)
    movbu   d0,(3,a2)
    ret     [d2,d3,a2,a3],20

    .global gyro_reply_envelope_copy
gyro_reply_envelope_copy:
    /* Stock 0x40009798 blindly copies len+4, including when len>40 means
     * external storage. The outgoing task has only a 44-byte local envelope.
     * For this exact new format copy its header+pointer (8 bytes) instead.
     * All existing lengths/types/opcodes retain the displaced computation.
     */
    movbu   (2,a1),d0
    cmp     54,d0
    bne     .Loriginal_copy
    movbu   (a1),d0
    cmp     1,d0
    bne     .Loriginal_copy_reload
    movbu   (1,a1),d0
    cmp     0x37,d0
    bne     .Loriginal_copy_reload
    mov     8,d0
    jmp     FW_ENVELOPE_COPY_CONTINUE
.Loriginal_copy_reload:
    movbu   (2,a1),d0
.Loriginal_copy:
    add     4,d0
    jmp     FW_ENVELOPE_COPY_CONTINUE

    .section .gyro_reply.send_redirect,"ax",@progbits
    jmp     gyro_reply_dispatch
    /* Only CPU0's outgoing dequeue consumes this external envelope.
     * Keep the common copier stock: its code is also loaded by CPU1. */
    .section .gyro_reply.copy_redirect,"ax",@progbits
    call    gyro_reply_envelope_copy,[],4
