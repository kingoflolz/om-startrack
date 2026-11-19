/* Carry active PRE through the stock framing/still producer. The unchanged
 * final coupled builder remains the actuator demand authority. No new RAM,
 * physical-envelope admission, mode flags, or capture origins are introduced.
 */

    .equ PRE_ACTIVE, 0x568

    .section .pre_envelope_text,"ax",@progbits

/* Replaces the old axis-zero adoption CALL, with its exact register mask and
 * frame. Adopt FIRST: a newly published large PRE must not encounter the
 * previous tick's framing bound. Stock 1926e reinstates its policy before each
 * mode1/3 pass, so cancel uses stock bounds on the very same processing pass.
 * B+4 stays wide through planar predictor limiting at 1a40f. S+4, roll B+4,
 * all profile tables, and stock final geometry remain unchanged.
 */
    .global pre_envelope_adopt
    .type pre_envelope_adopt,@function
pre_envelope_adopt:
    call    FW_PRE_ADOPT,[d2,d3,a2,a3],20
    call    pre_envelope_active,[],4
    cmp     0,d0
    beq     .Ladopt_done
    mov     16384,d0
    movhu   d0,(0x4ec4)
    movhu   d0,(0x529c)
    mov     0x70000000,d0
    mov     d0,(0x4f58)
    mov     d0,(0x5330)
    mov     d0,(0x5708)
.Ladopt_done:
    ret     [d2,d3,a2,a3],20
    .size pre_envelope_adopt,.-pre_envelope_adopt

/* d0 is boolean. Other scratch registers may be changed. The three active
 * offsets belong to this detector task; only adoption writes them. Pending
 * command publication may interrupt but cannot change this predicate.
 */
    .global pre_envelope_active
    .type pre_envelope_active,@function
pre_envelope_active:
    movhu   (0x489a),d0
    cmp     1,d0
    beq     .Lactive_mode
    cmp     3,d0
    beq     .Lactive_mode
    clr     d0
    ret     [],4
.Lactive_mode:
    mov     (PRE_ACTIVE),d0
    mov     (PRE_ACTIVE+4),d1
    or      d1,d0
    mov     (PRE_ACTIVE+8),d1
    or      d1,d0
    ret     [],4
    .size pre_envelope_active,.-pre_envelope_active

/* The stock entry caller provides [],4, including the outgoing call-link
 * scratch word below its saved return link. The helper only changes
 * caller-scratch d0/d1. Stock callee-save registers stay untouched.
 */
    .global pre_envelope_final_builder
    .type pre_envelope_final_builder,@function
pre_envelope_final_builder:
    call    pre_envelope_active,[],4
    cmp     0,d0
    bne     .Lcoupled
    /* Replay exactly the stock entry's displaced MOVHU and CMP. */
    movhu   (0x489a),d0
    cmp     1,d0
    jmp     FW_FINAL_REPLAY_CONTINUE
.Lcoupled:
    call    FW_FINAL_COUPLED,[d2,d3,a2],16
    /* Restore the stock producer policy before leaving this processing pass.
     * Mode2 can reuse a previously accepted profile without rewriting B+4/
     * B+98, so waiting for the next mode1/3 pass would leak the override.
     * 1926e only assigns bounds and (ordinary mode1) clears the already-cleared
     * large-demand flags. It does not change the final targets just built.
     */
    call    FW_RESTORE_BOUNDS,[d2,d3,a2],12
    jmp     FW_AFTER_FINAL_BUILDER
    .size pre_envelope_final_builder,.-pre_envelope_final_builder

    .section .pre_envelope_adopt_redirect,"ax",@progbits
    .global pre_envelope_adopt_redirect
pre_envelope_adopt_redirect:
    /* Exact short native CALL encoding; the signed16 displacement is audited
     * after link and cannot be silently widened by assembler relaxation. */
    .reloc  .+1,R_MN10300_PCREL16,pre_envelope_adopt+1
    .byte   0xcd
    .short  0
    .byte   0xf0,20

    .section .pre_envelope_builder_redirect,"ax",@progbits
    .global pre_envelope_builder_redirect
pre_envelope_builder_redirect:
    jmp     pre_envelope_final_builder
