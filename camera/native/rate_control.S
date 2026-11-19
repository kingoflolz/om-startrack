/*
 * OM-1 CPU0 native-command IBIS overlay.
 *
 * Linux validates public rates/focal length and converts once per command.
 * The passive opcode-0x37 bridge publishes three unsigned Q32 fractions,
 * three signed whole increments and a u16 TTL last. A common DDA adds each
 * fraction to its persistent residual, carries into the whole increment,
 * and saturates the selected native rate word. A live TTL is decremented
 * once per eligible stock tick; no receiver, generation or mode policy is
 * added. Stock downstream entry and its suppression gates remain intact.
 *
 * PRE contains three native integral offsets, a publication tag, and three
 * active offsets. The native integration/output calls see relative demand
 * plus the offset, so their original limiters bound the combined position.
 * Preposition is adopted in half-press mode 3 and retained for exposure 1.
 *
 * Trace records 45..47 (60 bytes, 0x0558..0x0593) hold PRE28 and RATE32.
 * Four stock ring bounds protect that reservation; dump lengths stay stock.
 */

    .am33_2

    .equ STATE_BASE,                   0x00000574
    .equ ST_FRACTION,                  0
    .equ ST_WHOLE,                     12
    .equ ST_TTL,                       18
    .equ ST_RESIDUAL,                  20
    .equ STATE_BYTES,                  32

    .equ PRE_BASE,                     0x00000558
    .equ PRE_PENDING,                  PRE_BASE
    .equ PRE_ACTION,                   PRE_BASE + 12
    .equ PRE_ACTIVE,                   PRE_BASE + 16
    .equ PRE_ARM,                      0x5051

    .section .astro.control_text, "ax", @progbits

    .global astro_control_consumer_hook
    .type   astro_control_consumer_hook, @function
astro_control_consumer_hook:
    movhu   (STATE_BASE + ST_TTL),d0
    cmp     0,d0
    beq     .Lconsumer_done
    add     -1,d0
    movhu   d0,(STATE_BASE + ST_TTL)
    mov     STATE_BASE,a0
    mov     STATE_BASE + ST_WHOLE,a1
    mov     STATE_BASE + ST_RESIDUAL,a2
    mov     0x4f64,a3
.Lnative_axis:
    /* RATE refresh is deliberately unsynchronized. A command interrupt may
     * replace the fraction and whole between these loads. */
    mov     (a0),d0
    mov     (a2),d1
    movhu   (a1),d2
    exth    d2
    clr     d3
    add     d0,d1
    addc    d3,d2
    mov     d1,(a2)
    mov     (a3),d0
    add     d2,d0
    bvc     .Laxis_store_selected
    mov     0x7fffffff,d0
    cmp     0,d2
    bge     .Laxis_store_selected
    inc     d0
.Laxis_store_selected:
    mov     d0,(a3)
    inc4    a0
    add     2,a1
    inc4    a2
    add     984,a3
    cmp     STATE_BASE + STATE_BYTES,a2
    bne     .Lnative_axis
    mov     1,d0
    ret     [d2,d3,a2,a3],20
.Lconsumer_done:
    clr     d0
    ret     [d2,d3,a2,a3],20
    .size astro_control_consumer_hook,.-astro_control_consumer_hook
    .global astro_control_downstream_dispatch
    .type   astro_control_downstream_dispatch, @function
astro_control_downstream_dispatch:
    call    astro_control_consumer_hook,[d2,d3,a2,a3],20
    call    FW_STOCK_DOWNSTREAM,[d2,d3,a2,a3],48
    jmp     FW_CONTINUATION
    .size   astro_control_downstream_dispatch, .-astro_control_downstream_dispatch

/* Replace the one displaced stock CALL with a far JMP into the compact cave.
 * The continuation is owned by the dispatcher above. */
    .section .astro.control_entry_redirect, "ax", @progbits
    .global astro_control_entry_redirect
    .type   astro_control_entry_redirect, @function
astro_control_entry_redirect:
    jmp     astro_control_downstream_dispatch
    .size   astro_control_entry_redirect, .-astro_control_entry_redirect

/* Retain trace records 0..44; reserve records 45..47 for PRE and RATE. */
    .section .astro.control_ring_write_wrap, "ax", @progbits
    .global astro_control_ring_write_wrap
astro_control_ring_write_wrap:
    cmp     44,d0

    .section .astro.control_ring_count_capacity, "ax", @progbits
    .global astro_control_ring_count_capacity
astro_control_ring_count_capacity:
    cmp     45,d0

    .section .astro.control_ring_oldest_wrap, "ax", @progbits
    .global astro_control_ring_oldest_wrap
astro_control_ring_oldest_wrap:
    cmp     44,d0

    .section .astro.control_ring_dump_wrap, "ax", @progbits
    .global astro_control_ring_dump_wrap
astro_control_ring_dump_wrap:
    cmp     44,d0

    .section .astro.preposition_text, "ax", @progbits

/* Enter with the stock axis in d2. Accept a command only at axis zero, then
 * translate the relative integrator before BOTH original stock calls. The
 * integration limit, output limit, error flags and final builders therefore
 * all see the combined demand. Remove the same bias from the updated native
 * integrator before the next tick's feedback calculation. No other native
 * state is written here, including predictor history and capture origins.
 *
 * The displaced calls preserve d2/d3/a2/a3, so the wrapper retains their entry
 * values too. Native CALL register masks and frame sizes are unchanged.
 */
    .global astro_preposition_native_pair
    .type   astro_preposition_native_pair, @function
astro_preposition_native_pair:
    movm    [d2,d3,a2,a3],(sp)
    add     -4,sp
    cmp     0,d2
    bne     .Lpre_axis_ready
    call    astro_preposition_tick,[d2,d3,a2,a3],20
.Lpre_axis_ready:
    mov     d2,d0
    asl2    d0
    add     PRE_ACTIVE,d0
    mov     d0,a0
    mov     (a0),d3
    mov     984,d0
    mul     d2,d0
    add     0x4ee8,d0
    mov     d0,a2
    mov     (a2),d0
    mov     d3,d1
    call    astro_preposition_saturating_add,[],4
    mov     d0,(a2)
    mov     d2,d0
    call    FW_STOCK_INTEGRATE,[d2,d3,a2,a3],20
    mov     d2,d0
    call    FW_STOCK_OUTPUT,[d2,d3,a2,a3],28
    mov     (a2),d0
    mov     d3,d1
    not     d1
    inc     d1
    call    astro_preposition_saturating_add,[],4
    mov     d0,(a2)
    add     4,sp
    movm    (sp),[d2,d3,a2,a3]
    jmp     FW_AFTER_NATIVE_PAIR
    .size   astro_preposition_native_pair, .-astro_preposition_native_pair

/* Adopt published native offsets only during half-press. Linux owns
 * conversion and validation; a zero vector is cancellation. Publication may
 * interrupt these loads: rare mixed vectors or a lost pending tag are accepted.
 * Capture ticks leave pending commands alone until mode exit.
 */
    .global astro_preposition_tick
    .type astro_preposition_tick, @function
astro_preposition_tick:
    movhu   (0x489a),d0
    cmp     3,d0
    bne     .Lraw_tick_done
    movhu   (PRE_ACTION),d3
    cmp     PRE_ARM,d3
    bne     .Lraw_tick_done
    mov     (PRE_PENDING),d0
    mov     d0,(PRE_ACTIVE)
    mov     (PRE_PENDING + 4),d0
    mov     d0,(PRE_ACTIVE + 4)
    mov     (PRE_PENDING + 8),d0
    mov     d0,(PRE_ACTIVE + 8)
    clr     d0
    movhu   d0,(PRE_ACTION)
.Lraw_tick_done:
    ret     [d2,d3,a2,a3],20
    .size astro_preposition_tick, .-astro_preposition_tick

/* d0 = sat_s32(d0 + d1). ADD sets V on signed overflow; C distinguishes
 * negative overflow (carry) from positive overflow (no carry). MOV leaves
 * those flags intact. Linux bounds each bias so negating it is also safe.
 */
    .global astro_preposition_saturating_add
    .type   astro_preposition_saturating_add, @function
astro_preposition_saturating_add:
    add     d1,d0
    bvc     .Lpre_sum_done
    mov     0x7fffffff,d0
    bcc     .Lpre_sum_done
    inc     d0
.Lpre_sum_done:
    ret     [],4
    .size   astro_preposition_saturating_add, .-astro_preposition_saturating_add

/* Replay the displaced mode stores. Preserve active bias across 3->1 while
 * retiring pending commands; preserve both for 3->3. Initial 0->3 keeps a
 * just-published pending command but clears active state. Every other change
 * retires the tag and clears active bias BEFORE stock mode exit adopts targets.
 * Capture seeding, native reset policy and predictor handling stay stock.
 */
    .global astro_preposition_mode_transition
    .type   astro_preposition_mode_transition, @function
astro_preposition_mode_transition:
    movhu   (0x489a),d1
    movhu   d0,(0x489a)
    movhu   d1,(0x489c)
    movm    [d2,d3,a2,a3],(sp)
    mov     PRE_ACTION,a0
    mov     4,d2
    cmp     3,d1
    beq     .Lpre_from_halfpress
    cmp     0,d1
    bne     .Lpre_transition_clear
    cmp     3,d0
    bne     .Lpre_transition_clear
    inc4    a0
    mov     3,d2
    bra     .Lpre_transition_clear
.Lpre_from_halfpress:
    cmp     3,d0
    beq     .Lpre_transition_done
    cmp     1,d0
    bne     .Lpre_transition_clear
    mov     1,d2
.Lpre_transition_clear:
    clr     d0
.Lpre_clear_word:
    mov     d0,(a0)
    inc4    a0
    add     -1,d2
    bne     .Lpre_clear_word
.Lpre_transition_done:
    movm    (sp),[d2,d3,a2,a3]
    jmp     FW_MODE_CONTINUATION
    .size   astro_preposition_mode_transition, .-astro_preposition_mode_transition

    .section .astro.preposition_native_pair_redirect, "ax", @progbits
    .global astro_preposition_native_pair_redirect
astro_preposition_native_pair_redirect:
    jmp     astro_preposition_native_pair
    .rept   7
    nop
    .endr
    .section .astro.preposition_mode_redirect, "ax", @progbits
    .global astro_preposition_mode_redirect
astro_preposition_mode_redirect:
    jmp     astro_preposition_mode_transition
    .rept   4
    nop
    .endr
