/*
 * Passive opcode-0x37 bridge. Exact TX21 publishes native RATE: fractions
 * 3*u32, wholes 3*s16, TTL u16. Exact TX15 publishes PRE: offsets 3*s32,
 * tag u16 (0x5051). Linux owns validation and conversion; zero PRE cancels.
 *
 * Both bodies use one byte-copy loop for their unaligned wire values,
 * followed by one aligned u16 validity store. PRE retires its old tag before
 * replacement. Consumers deliberately accept publication races; RATE refresh
 * preserves all three fractional residuals. Every path resumes the exact
 * stock handler with d0/a0/a1, nonvolatile registers, SP and MDR intact.
 */

    .am33_2
    .section .astro_op37_bridge.dispatch,"a",@progbits
    .balign 4
    .global astro_op37_bridge_dispatch
astro_op37_bridge_dispatch:
    .long astro_op37_bridge_wrapper
    .section .astro_op37_bridge.text,"ax",@progbits
    .global astro_op37_bridge_wrapper
    .type astro_op37_bridge_wrapper,@function
astro_op37_bridge_wrapper:
    movm    [d2,a2,a3],(sp)
    movbu   (2,a0),d1
    cmp     21,d1
    bne     .Lpre
    mov     0x574,a2
    mov     -18,d2
    bra     .Lcopy_setup
.Lpre:
    cmp     15,d1
    bne     .Lstock
    mov     0x558,a2
    mov     0,d2
    movhu   d2,(12,a2)
    mov     -12,d2
.Lcopy_setup:
    mov     a0,a3
    add     5,a3
.Lcopy:
    movbu   (a3),d1
    movbu   d1,(a2)
    inc     a3
    inc     a2
    inc     d2
    bne     .Lcopy
    movbu   (a3),d1
    movbu   (1,a3),d2
    asl     8,d2
    or      d2,d1
    movhu   d1,(a2)
.Lstock:
    movm    (sp),[d2,a2,a3]
    jmp     FW_STOCK_OP37_HANDLER

    .size astro_op37_bridge_wrapper,.-astro_op37_bridge_wrapper
